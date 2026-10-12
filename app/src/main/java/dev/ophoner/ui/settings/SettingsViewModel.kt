package dev.ophoner.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.ophoner.data.model.AuthMode
import dev.ophoner.data.model.OAuthTokens
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderPresets
import dev.ophoner.data.model.ProviderType
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.llm.auth.OpenAiDeviceCodeAuth
import dev.ophoner.llm.auth.OpenRouterAuth
import dev.ophoner.llm.auth.Pkce
import dev.ophoner.llm.auth.ProviderCredentialResolver
import dev.ophoner.llm.models.ModelCatalogService
import dev.ophoner.tools.sandbox.SandboxedShell
import dev.ophoner.tools.sandbox.ShizukuStatus
import dev.ophoner.ui.theme.AccentChoice
import dev.ophoner.ui.theme.ThemeMode
import dev.ophoner.ui.theme.UiFont
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class DeviceLoginUi(
    val userCode: String,
    val verificationUrl: String,
    val busy: Boolean = true,
    val error: String? = null,
    val saving: Boolean = false,
    val networkStatus: String? = null,
    val expiresAtEpochMs: Long? = null,
)

data class SettingsUiState(
    val providers: List<ProviderConfig> = emptyList(),
    val activeProviderId: String? = null,
    val workingDirUri: String? = null,
    val workingDirDisplay: String? = null,
    val systemPrompt: String = "",
    val importExportMessage: String? = null,
    val shizukuStatus: ShizukuStatus = ShizukuStatus.NOT_INSTALLED,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val uiFont: UiFont = UiFont.DM_SANS,
    val accent: AccentChoice = AccentChoice.BLUE,
    val yoloMode: Boolean = false,
    val deviceLogin: DeviceLoginUi? = null,
    val openRouterPkcePending: Boolean = false,
    val openRouterAuthUrl: String? = null,
    val modelOptions: List<String> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelCatalogMessage: String? = null,
    val authMessage: String? = null,
    val connectedChatGptIds: Set<String> = emptySet(),
    val chatGptEmails: Map<String, String> = emptyMap(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val deviceCodeAuth: OpenAiDeviceCodeAuth,
    private val openRouterAuth: OpenRouterAuth,
    private val modelCatalog: ModelCatalogService,
    private val credentialResolver: ProviderCredentialResolver,
    private val shell: SandboxedShell,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var deviceLoginJob: Job? = null
    private var deviceLoginDraft: ProviderConfig? = null
    private var openRouterVerifier: String? = null
    private var modelFetchJob: Job? = null

    init {
        refreshShizukuStatus()

        viewModelScope.launch {
            val appearance = combine(
                settingsRepository.observeThemeMode(),
                settingsRepository.observeUiFont(),
                settingsRepository.observeAccent(),
            ) { themeRaw, fontRaw, accentRaw ->
                Triple(
                    runCatching { ThemeMode.valueOf(themeRaw) }.getOrDefault(ThemeMode.SYSTEM),
                    UiFont.parse(fontRaw),
                    runCatching { AccentChoice.valueOf(accentRaw) }.getOrDefault(AccentChoice.BLUE),
                )
            }

            val agentPrefs = combine(
                settingsRepository.observeSystemPrompt(),
                settingsRepository.observeYoloMode(),
            ) { systemPrompt, yoloMode -> systemPrompt to yoloMode }

            combine(
                settingsRepository.observeProviders(),
                settingsRepository.observeActiveProviderId(),
                settingsRepository.observeWorkingDirectoryUri(),
                agentPrefs,
                appearance,
            ) { providers, activeId, dirUri, agent, appearancePrefs ->
                val (systemPrompt, yoloMode) = agent
                val (themeMode, uiFont, accent) = appearancePrefs
                _uiState.value.copy(
                    providers = providers,
                    connectedChatGptIds = providers.filter {
                        it.providerType == ProviderType.CODEX_CHATGPT &&
                            settingsRepository.getOAuthTokens(it.id)?.accessToken?.isNotBlank() == true
                    }.map { it.id }.toSet(),
                    chatGptEmails = providers.filter { it.providerType == ProviderType.CODEX_CHATGPT }
                        .mapNotNull { provider ->
                            settingsRepository.getOAuthTokens(provider.id)?.let(deviceCodeAuth::accountEmail)
                                ?.let { provider.id to it }
                        }.toMap(),
                    activeProviderId = activeId,
                    workingDirUri = dirUri,
                    workingDirDisplay = dirUri?.let { uri ->
                        Uri.parse(uri).lastPathSegment?.replace("primary:", "/")
                    },
                    systemPrompt = systemPrompt,
                    themeMode = themeMode,
                    uiFont = uiFont,
                    accent = accent,
                    yoloMode = yoloMode,
                )
            }.collect { state -> _uiState.value = state }
        }
    }

    fun chatGptTokens(providerId: String): OAuthTokens? = settingsRepository.getOAuthTokens(providerId)

    fun addProvider(config: ProviderConfig) {
        viewModelScope.launch {
            val current = _uiState.value.providers
            if (current.any { it.id == config.id }) {
                upsertProvider(config)
            } else {
                settingsRepository.saveProviders(current + config)
                if (current.isEmpty()) {
                    settingsRepository.setActiveProvider(config.id)
                }
            }
        }
    }

    fun updateProvider(config: ProviderConfig) {
        viewModelScope.launch {
            val updated = _uiState.value.providers.map {
                if (it.id == config.id) config else it
            }
            settingsRepository.saveProviders(updated)
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            val updated = _uiState.value.providers.filter { it.id != id }
            settingsRepository.saveProviders(updated)
            if (_uiState.value.activeProviderId == id) {
                settingsRepository.setActiveProvider(updated.firstOrNull()?.id ?: "")
            }
        }
    }

    fun setActiveProvider(id: String) {
        viewModelScope.launch {
            settingsRepository.setActiveProvider(id)
        }
    }

    fun setWorkingDirectory(uri: Uri) {
        viewModelScope.launch {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            settingsRepository.setWorkingDirectory(uri.toString())
        }
    }

    fun setSystemPrompt(prompt: String) {
        viewModelScope.launch {
            settingsRepository.setSystemPrompt(prompt)
        }
    }

    fun exportProviders(uri: Uri) {
        viewModelScope.launch {
            settingsRepository.exportProviders(uri)
                .onSuccess { count ->
                    _uiState.update { it.copy(importExportMessage = "Exported $count provider(s)") }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(importExportMessage = "Export failed: ${e.message}") }
                }
        }
    }

    fun importProviders(uri: Uri) {
        viewModelScope.launch {
            settingsRepository.importProviders(uri)
                .onSuccess { count ->
                    val msg = if (count > 0) "Imported $count new provider(s)"
                    else "No new providers to import (all duplicates)"
                    _uiState.update { it.copy(importExportMessage = msg) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(importExportMessage = "Import failed: ${e.message}") }
                }
        }
    }

    fun clearImportExportMessage() {
        _uiState.update { it.copy(importExportMessage = null) }
    }

    fun clearAuthMessage() {
        _uiState.update { it.copy(authMessage = null) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode.name) }
    }

    fun setUiFont(font: UiFont) {
        viewModelScope.launch { settingsRepository.setUiFont(font.name) }
    }

    fun setAccent(accent: AccentChoice) {
        viewModelScope.launch { settingsRepository.setAccent(accent.name) }
    }

    fun setYoloMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setYoloMode(enabled) }
    }

    fun refreshShizukuStatus() {
        _uiState.update { it.copy(shizukuStatus = shell.shizukuStatus()) }
    }

    fun requestShizukuPermission() {
        try {
            rikka.shizuku.Shizuku.requestPermission(0)
        } catch (_: Exception) {
            // Shizuku not available
        }
    }

    fun openUrl(url: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { e ->
                _uiState.update { it.copy(authMessage = "Could not open URL: ${e.message}") }
            }
    }

    fun seedModelOptions(config: ProviderConfig) {
        modelFetchJob?.cancel()
        _uiState.update {
            it.copy(
                modelOptions = modelCatalog.mergeModelOptions(emptyList(), config),
                modelsLoading = false,
                modelCatalogMessage = null,
            )
        }
        val canDiscover = ModelCatalogService.modelsDevProviderId(config) != null ||
            config.apiKey.isNotBlank() ||
            config.id in _uiState.value.connectedChatGptIds
        if (config.baseUrl.isNotBlank() && canDiscover) refreshModelsForDraft(config)
    }

    fun refreshModelsForDraft(config: ProviderConfig) {
        modelFetchJob?.cancel()
        modelFetchJob = viewModelScope.launch {
            _uiState.update { it.copy(modelsLoading = true, modelCatalogMessage = null) }
            try {
                val cached = modelCatalog.cachedModels(config)
                ensureActive()
                if (cached.isNotEmpty()) {
                    _uiState.update {
                        it.copy(modelOptions = modelCatalog.mergeModelOptions(cached, config))
                    }
                }
                val remote = modelCatalog.fetchModels(config.withFetchCredentials()).getOrThrow()
                ensureActive()
                _uiState.update {
                    it.copy(
                        modelOptions = modelCatalog.mergeModelOptions(remote.ids, config),
                        modelsLoading = false,
                        modelCatalogMessage = remote.warning,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (error: Exception) {
                ensureActive()
                _uiState.update {
                    it.copy(
                        modelsLoading = false,
                        modelCatalogMessage = "Could not refresh models: ${error.message ?: "Connection failed"}. You can still enter a model ID.",
                    )
                }
            }
        }
    }

    fun addCustomModelSlug(providerId: String, slug: String) {
        val trimmed = slug.trim()
        if (trimmed.isEmpty() || providerId.isBlank()) return
        viewModelScope.launch {
            val updated = _uiState.value.providers.map { provider ->
                if (provider.id != providerId) provider
                else provider.copy(
                    customModelSlugs = (provider.customModelSlugs + trimmed).distinct(),
                )
            }
            settingsRepository.saveProviders(updated)
        }
    }

    fun loginWithChatGpt() {
        val existing = _uiState.value.providers.firstOrNull { it.providerType == ProviderType.CODEX_CHATGPT }
        val plan = requireNotNull(ProviderPresets.forType(ProviderType.CODEX_CHATGPT))
        beginCodexDeviceLogin(existing ?: ProviderConfig(
            id = UUID.randomUUID().toString(),
            displayName = plan.defaultDisplayName,
            baseUrl = plan.defaultBaseUrl,
            modelId = plan.defaultModelId,
            providerType = plan.providerType,
            authMode = plan.authMode,
            planLabel = plan.planLabel,
        ))
    }

    fun retryDeviceLogin() {
        deviceLoginDraft?.let(::beginCodexDeviceLogin)
    }

    fun signOutChatGpt(id: String) {
        cancelDeviceLogin()
        deleteProvider(id)
    }

    fun beginCodexDeviceLogin(draft: ProviderConfig) {
        if (deviceLoginJob?.isActive == true) return
        deviceLoginDraft = draft
        _uiState.update {
            it.copy(deviceLogin = DeviceLoginUi(userCode = "", verificationUrl = ""), authMessage = null)
        }
        deviceLoginJob = viewModelScope.launch {
            try {
                val session = deviceCodeAuth.startDeviceLogin(::updateLoginNetworkStatus)
                _uiState.update {
                    it.copy(
                        deviceLogin = DeviceLoginUi(
                            userCode = session.userCode,
                            verificationUrl = session.verificationUrl,
                            busy = true,
                            expiresAtEpochMs = session.expiresAtEpochMs,
                        ),
                    )
                }
                val tokens = deviceCodeAuth.awaitDeviceLogin(session, ::updateLoginNetworkStatus)
                ensureActive()
                val providerId = draft.id.ifBlank { UUID.randomUUID().toString() }
                val plan = ProviderPresets.forType(ProviderType.CODEX_CHATGPT)
                val config = draft.copy(
                    id = providerId,
                    displayName = draft.displayName.ifBlank {
                        plan?.defaultDisplayName ?: "ChatGPT Codex"
                    },
                    apiKey = "",
                    baseUrl = draft.baseUrl.ifBlank {
                        plan?.defaultBaseUrl ?: "https://chatgpt.com/backend-api/codex"
                    },
                    modelId = draft.modelId,
                    providerType = ProviderType.CODEX_CHATGPT,
                    authMode = AuthMode.OAUTH_DEVICE,
                    accountId = tokens.accountId,
                    planLabel = draft.planLabel.ifBlank { plan?.planLabel ?: "ChatGPT" },
                )
                _uiState.update { it.copy(deviceLogin = it.deviceLogin?.copy(saving = true)) }
                var modelWarning: String? = null
                val selected = if (config.useLatestModel) {
                    val catalog = modelCatalog.fetchModels(config.copy(apiKey = tokens.accessToken))
                        .getOrElse { error ->
                            ensureActive()
                            modelWarning = "Choose a model in Settings when your connection is available."
                            null
                        }
                    if (catalog?.ids?.isNotEmpty() == true) config.copy(modelId = catalog.ids.first()) else config
                } else config
                // Finish the local commit even if the screen is closed at this point.
                withContext(NonCancellable) { settingsRepository.connectChatGpt(selected, tokens) }
                deviceLoginDraft = null
                _uiState.update {
                    it.copy(
                        deviceLogin = null,
                        authMessage = listOfNotNull("Signed in with ChatGPT", modelWarning).joinToString(". "),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    val current = it.deviceLogin
                    it.copy(
                        deviceLogin = DeviceLoginUi(
                            userCode = current?.userCode.orEmpty(),
                            verificationUrl = current?.verificationUrl.orEmpty(),
                            busy = false,
                            error = e.message ?: "Device login failed",
                        ),
                    )
                }
            }
        }
    }

    fun cancelDeviceLogin() {
        if (_uiState.value.deviceLogin?.saving == true) return
        deviceLoginJob?.cancel()
        deviceLoginJob = null
        deviceLoginDraft = null
        _uiState.update { it.copy(deviceLogin = null) }
    }

    private fun updateLoginNetworkStatus(message: String?) {
        _uiState.update { state ->
            state.copy(deviceLogin = state.deviceLogin?.copy(networkStatus = message))
        }
    }

    /** Generates PKCE, stores verifier, returns authorize URL (also mirrored in state). */
    fun beginOpenRouterLogin(): String? {
        return try {
            val pkce = Pkce.generate()
            openRouterVerifier = pkce.codeVerifier
            val url = openRouterAuth.buildAuthorizeUrl(pkce)
            _uiState.update {
                it.copy(
                    openRouterPkcePending = true,
                    openRouterAuthUrl = url,
                    authMessage = null,
                )
            }
            url
        } catch (e: Exception) {
            openRouterVerifier = null
            _uiState.update {
                it.copy(
                    openRouterPkcePending = false,
                    openRouterAuthUrl = null,
                    authMessage = "OpenRouter login failed: ${e.message}",
                )
            }
            null
        }
    }

    fun completeOpenRouterLogin(code: String, draft: ProviderConfig) {
        val trimmedCode = code.trim()
        if (trimmedCode.isEmpty()) {
            _uiState.update { it.copy(authMessage = "Paste the OpenRouter authorization code") }
            return
        }
        val verifier = openRouterVerifier
        if (verifier.isNullOrBlank()) {
            _uiState.update { it.copy(authMessage = "Start OpenRouter sign-in first") }
            return
        }
        viewModelScope.launch {
            try {
                val result = openRouterAuth.exchangeCode(trimmedCode, verifier)
                val plan = ProviderPresets.forType(ProviderType.OPENROUTER)
                val providerId = draft.id.ifBlank { UUID.randomUUID().toString() }
                val config = draft.copy(
                    id = providerId,
                    displayName = draft.displayName.ifBlank {
                        plan?.defaultDisplayName ?: "OpenRouter"
                    },
                    apiKey = result.key,
                    baseUrl = draft.baseUrl.ifBlank {
                        plan?.defaultBaseUrl ?: "https://openrouter.ai/api/v1"
                    },
                    modelId = draft.modelId.ifBlank {
                        plan?.defaultModelId ?: "anthropic/claude-sonnet-4.5"
                    },
                    providerType = ProviderType.OPENROUTER,
                    authMode = AuthMode.API_KEY,
                    planLabel = draft.planLabel.ifBlank { plan?.planLabel ?: "OpenRouter" },
                )
                upsertProvider(config)
                settingsRepository.setActiveProvider(config.id)
                clearOpenRouterPkce()
                _uiState.update { it.copy(authMessage = "Signed in with OpenRouter") }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(authMessage = "OpenRouter exchange failed: ${e.message}")
                }
            }
        }
    }

    fun clearOpenRouterPkce() {
        openRouterVerifier = null
        _uiState.update {
            it.copy(openRouterPkcePending = false, openRouterAuthUrl = null)
        }
    }

    private suspend fun upsertProvider(config: ProviderConfig) {
        val current = _uiState.value.providers
        val updated = if (current.any { it.id == config.id }) {
            current.map { if (it.id == config.id) config else it }
        } else {
            current + config
        }
        settingsRepository.saveProviders(updated)
    }

    private suspend fun ProviderConfig.withFetchCredentials(): ProviderConfig {
        if (providerType != ProviderType.CODEX_CHATGPT) return this
        val (token, account) = credentialResolver.bearerCredential(this)
        return copy(apiKey = token, accountId = account.orEmpty())
    }

    override fun onCleared() {
        deviceLoginJob?.cancel()
        super.onCleared()
    }
}
