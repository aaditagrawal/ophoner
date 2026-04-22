package dev.ophoner.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.ophoner.data.model.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val providersKey = stringPreferencesKey("providers")
    private val activeProviderKey = stringPreferencesKey("active_provider_id")
    private val workingDirKey = stringPreferencesKey("working_directory_uri")
    private val systemPromptKey = stringPreferencesKey("system_prompt")

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

    suspend fun saveProviders(providers: List<ProviderConfig>) {
        // Persist api keys in EncryptedSharedPreferences keyed by provider id,
        // and store only non-sensitive metadata in DataStore.
        val existingIds = observeProviders().first().map { it.id }.toSet()
        val incomingIds = providers.map { it.id }.toSet()

        // Remove secrets for providers that are no longer present.
        (existingIds - incomingIds).forEach { id -> secureStorage.remove(apiKeyPref(id)) }

        providers.forEach { provider ->
            secureStorage.putString(apiKeyPref(provider.id), provider.apiKey)
        }

        val sanitized = providers.map { it.copy(apiKey = "") }
        context.dataStore.edit { prefs ->
            prefs[providersKey] = json.encodeToString(sanitized)
        }
    }

    /**
     * Export providers as pretty-printed JSON to the given SAF URI.
     */
    suspend fun exportProviders(uri: Uri): Result<Int> = runCatching {
        val providers = observeProviders().first()
        val jsonStr = prettyJson.encodeToString(providers)
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(jsonStr.toByteArray())
        } ?: throw IllegalStateException("Could not open output stream")
        providers.size
    }

    /**
     * Import providers from a SAF URI. Merges with existing providers --
     * duplicates (matched by displayName + baseUrl + modelId) are skipped.
     * Returns the number of newly added providers.
     */
    suspend fun importProviders(uri: Uri): Result<Int> = runCatching {
        val rawInput = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().readText()
        } ?: throw IllegalStateException("Could not open input stream")

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

    suspend fun getActiveProvider(): ProviderConfig? {
        val prefs = context.dataStore.data.first()
        val activeId = prefs[activeProviderKey] ?: return null
        val providers = json.decodeFromString<List<ProviderConfig>>(prefs[providersKey] ?: "[]")
        return providers.find { it.id == activeId }?.withSecureApiKey()
    }

    private fun ProviderConfig.withSecureApiKey(): ProviderConfig =
        copy(apiKey = secureStorage.getString(apiKeyPref(id)) ?: "")

    private fun apiKeyPref(providerId: String): String = "provider_api_key_$providerId"
}
