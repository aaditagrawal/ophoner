package dev.ophoner.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.ophoner.data.model.OAuthTokens
import dev.ophoner.data.model.PinnedFolder
import dev.ophoner.data.model.ChatGptSpeed
import dev.ophoner.data.model.ProviderType
import dev.ophoner.data.model.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "ophoner_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
    private val secureStorage: SecureStorage,
) {
    private val oauthLock = Any()
    private val providersKey = stringPreferencesKey("providers")
    private val activeProviderKey = stringPreferencesKey("active_provider_id")
    private val workingDirKey = stringPreferencesKey("working_directory_uri")
    private val systemPromptKey = stringPreferencesKey("system_prompt")
    private val pinnedFoldersKey = stringPreferencesKey("pinned_folders")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val uiFontKey = stringPreferencesKey("ui_font")
    private val accentKey = stringPreferencesKey("accent_color")
    private val yoloModeKey = booleanPreferencesKey("yolo_mode")

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun observeProviders(): Flow<List<ProviderConfig>> = context.dataStore.data.map { prefs ->
        val raw = prefs[providersKey] ?: "[]"
        json.decodeFromString<List<ProviderConfig>>(raw).map { it.withSecureApiKey() }
    }

    fun observeActiveProviderId(): Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[activeProviderKey]
    }

    fun observeWorkingDirectoryUri(): Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[workingDirKey]
    }

    fun observeSystemPrompt(): Flow<String> = context.dataStore.data.map { prefs ->
        prefs[systemPromptKey] ?: "You are Ophoner, an AI agent running on an Android device. You have tools to read/write files, execute shell commands, and search the web. Use them to help the user accomplish tasks on their phone."
    }

    fun observeThemeMode(): Flow<String> = context.dataStore.data.map { prefs ->
        prefs[themeModeKey] ?: "SYSTEM"
    }

    fun observeUiFont(): Flow<String> = context.dataStore.data.map { prefs ->
        prefs[uiFontKey] ?: "DM_SANS"
    }

    fun observeAccent(): Flow<String> = context.dataStore.data.map { prefs ->
        prefs[accentKey] ?: "BLUE"
    }

    fun observeYoloMode(): Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[yoloModeKey] ?: false
    }

    suspend fun setYoloMode(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[yoloModeKey] = enabled }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[themeModeKey] = mode }
    }

    suspend fun setUiFont(font: String) {
        context.dataStore.edit { prefs -> prefs[uiFontKey] = font }
    }

    suspend fun setAccent(accent: String) {
        context.dataStore.edit { prefs -> prefs[accentKey] = accent }
    }

    suspend fun saveProviders(providers: List<ProviderConfig>) {
        // Persist api keys / oauth blobs in EncryptedSharedPreferences keyed by
        // provider id, and store only non-sensitive metadata in DataStore.
        val existingIds = observeProviders().first().map { it.id }.toSet()
        val incomingIds = providers.map { it.id }.toSet()

        (existingIds - incomingIds).forEach { id ->
            secureStorage.remove(apiKeyPref(id))
            clearOAuthTokens(id)
        }

        providers.forEach { provider ->
            secureStorage.putString(apiKeyPref(provider.id), provider.apiKey)
        }

        val sanitized = providers.map { it.copy(apiKey = "") }
        context.dataStore.edit { prefs ->
            prefs[providersKey] = json.encodeToString(sanitized)
        }
    }

    suspend fun saveOAuthTokens(providerId: String, tokens: OAuthTokens) {
        synchronized(oauthLock) {
            secureStorage.putString(oauthPref(providerId), json.encodeToString(tokens))
        }
    }

    /** Switch the active provider and its model atomically, retaining credentials and other settings. */
    suspend fun selectModel(providerId: String, modelId: String?, addSlug: Boolean = false): ProviderConfig {
        var selected: ProviderConfig? = null
        context.dataStore.edit { prefs ->
            val providers = json.decodeFromString<List<ProviderConfig>>(prefs[providersKey] ?: "[]")
            val current = providers.firstOrNull { it.id == providerId }
                ?: error("This provider was removed. Connect a provider in Settings.")
            val slug = modelId?.trim()?.also { require(it.isNotEmpty()) { "Enter a model slug." } }
            val updated = current.copy(
                modelId = slug ?: current.modelId,
                useLatestModel = slug == null,
                customModelSlugs = if (addSlug && slug != null) (current.customModelSlugs + slug).distinct()
                    else current.customModelSlugs,
            )
            prefs[providersKey] = json.encodeToString(providers.map { if (it.id == providerId) updated else it })
            prefs[activeProviderKey] = providerId
            selected = updated.withSecureApiKey()
        }
        return checkNotNull(selected)
    }

    /** Update only speed, preserving concurrent model edits and the active provider. */
    suspend fun setChatGptSpeed(providerId: String, speed: ChatGptSpeed) {
        context.dataStore.edit { prefs ->
            val providers = json.decodeFromString<List<ProviderConfig>>(prefs[providersKey] ?: "[]")
            val current = providers.firstOrNull { it.id == providerId }
                ?: error("This provider was removed. Connect a provider in Settings.")
            require(current.providerType == ProviderType.CODEX_CHATGPT) {
                "Speed selection is available for ChatGPT connections."
            }
            prefs[providersKey] = json.encodeToString(providers.map {
                if (it.id == providerId) it.copy(chatGptSpeed = speed) else it
            })
        }
    }

    /** A background catalog refresh must not overwrite a manual edit or recreate a deleted provider. */
    suspend fun updateAutomaticModel(expected: ProviderConfig, modelId: String): ProviderConfig? {
        var result: ProviderConfig? = null
        context.dataStore.edit { prefs ->
            val providers = json.decodeFromString<List<ProviderConfig>>(prefs[providersKey] ?: "[]")
            val current = providers.firstOrNull { it.id == expected.id } ?: return@edit
            if (current != expected.copy(apiKey = "") || !current.useLatestModel) {
                result = current.withSecureApiKey()
                return@edit
            }
            val updated = current.copy(modelId = modelId)
            prefs[providersKey] = json.encodeToString(providers.map { if (it.id == updated.id) updated else it })
            result = updated.withSecureApiKey()
        }
        return result
    }

    fun replaceOAuthTokens(providerId: String, expected: OAuthTokens, replacement: OAuthTokens): Boolean =
        synchronized(oauthLock) {
            if (getOAuthTokens(providerId) != expected) return@synchronized false
            secureStorage.putString(oauthPref(providerId), json.encodeToString(replacement))
            true
        }

    fun getOAuthTokens(providerId: String): OAuthTokens? {
        val raw = secureStorage.getString(oauthPref(providerId)) ?: return null
        return runCatching { json.decodeFromString<OAuthTokens>(raw) }.getOrNull()
    }

    suspend fun clearOAuthTokens(providerId: String) {
        synchronized(oauthLock) { secureStorage.remove(oauthPref(providerId)) }
    }

    /** Credentials must exist before observers can select the new provider. */
    suspend fun connectChatGpt(config: ProviderConfig, tokens: OAuthTokens) {
        val previous = getOAuthTokens(config.id)
        saveOAuthTokens(config.id, tokens)
        try {
            context.dataStore.edit { prefs ->
                val providers = json.decodeFromString<List<ProviderConfig>>(prefs[providersKey] ?: "[]")
                val sanitized = config.copy(apiKey = "")
                val updated = if (providers.any { it.id == config.id }) {
                    providers.map { if (it.id == config.id) sanitized else it }
                } else providers + sanitized
                prefs[providersKey] = json.encodeToString(updated)
                prefs[activeProviderKey] = config.id
            }
            secureStorage.remove(apiKeyPref(config.id))
        } catch (e: Exception) {
            if (previous == null) clearOAuthTokens(config.id) else saveOAuthTokens(config.id, previous)
            throw e
        }
    }

    /**
     * Export providers as pretty-printed JSON to the given SAF URI.
     */
    suspend fun exportProviders(uri: Uri): Result<Int> = runCatching {
        val providers = observeProviders().first()
        val jsonStr = prettyJson.encodeToString(providers)
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(jsonStr.toByteArray())
            } ?: throw IllegalStateException("Could not open output stream")
        }
        providers.size
    }

    /**
     * Import providers from a SAF URI. Merges with existing providers --
     * duplicates (matched by displayName + baseUrl + modelId) are skipped.
     * Returns the number of newly added providers.
     */
    suspend fun importProviders(uri: Uri): Result<Int> = runCatching {
        val rawInput = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: throw IllegalStateException("Could not open input stream")
        }

        val incoming = json.decodeFromString<List<ProviderConfig>>(rawInput)
        val existing = observeProviders().first()

        val existingKeys = existing.map { Triple(it.displayName, it.baseUrl, it.modelId) }.toSet()
        val newProviders = incoming.filter { provider ->
            Triple(provider.displayName, provider.baseUrl, provider.modelId) !in existingKeys
        }.map { provider ->
            // Assign fresh IDs to avoid collisions
            provider.copy(id = java.util.UUID.randomUUID().toString())
        }

        if (newProviders.isNotEmpty()) {
            val merged = existing + newProviders
            saveProviders(merged)
            // Auto-activate if we went from empty to having providers
            if (existing.isEmpty()) {
                setActiveProvider(merged.first().id)
            }
        }
        newProviders.size
    }

    suspend fun setActiveProvider(id: String) {
        context.dataStore.edit { prefs ->
            prefs[activeProviderKey] = id
        }
    }

    suspend fun setWorkingDirectory(uri: String) {
        context.dataStore.edit { prefs ->
            prefs[workingDirKey] = uri
        }
    }

    suspend fun setSystemPrompt(prompt: String) {
        context.dataStore.edit { prefs ->
            prefs[systemPromptKey] = prompt
        }
    }

    fun observePinnedFolders(): Flow<List<PinnedFolder>> = context.dataStore.data.map { prefs ->
        val raw = prefs[pinnedFoldersKey] ?: "[]"
        runCatching { json.decodeFromString<List<PinnedFolder>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun pinFolder(uri: String, name: String) {
        context.dataStore.edit { prefs ->
            val raw = prefs[pinnedFoldersKey] ?: "[]"
            val current = runCatching { json.decodeFromString<List<PinnedFolder>>(raw) }
                .getOrDefault(emptyList())
            if (current.none { it.uri == uri }) {
                val updated = current + PinnedFolder(
                    uri = uri,
                    name = name,
                    createdAt = System.currentTimeMillis(),
                )
                prefs[pinnedFoldersKey] = json.encodeToString(updated)
            }
        }
    }

    suspend fun unpinFolder(uri: String) {
        context.dataStore.edit { prefs ->
            val raw = prefs[pinnedFoldersKey] ?: "[]"
            val current = runCatching { json.decodeFromString<List<PinnedFolder>>(raw) }
                .getOrDefault(emptyList())
            val updated = current.filter { it.uri != uri }
            prefs[pinnedFoldersKey] = json.encodeToString(updated)
        }
    }

    private fun ProviderConfig.withSecureApiKey(): ProviderConfig =
        copy(apiKey = secureStorage.getString(apiKeyPref(id)) ?: "")

    private fun apiKeyPref(providerId: String): String = "provider_api_key_$providerId"

    private fun oauthPref(providerId: String): String = "provider_oauth_$providerId"
}
