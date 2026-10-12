package dev.ophoner.llm.auth

import dev.ophoner.data.model.AuthMode
import dev.ophoner.data.model.OAuthTokens
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderType
import dev.ophoner.data.repository.SettingsRepository
import okhttp3.OkHttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderCredentialResolver @Inject constructor(
    private val settingsRepository: SettingsRepository,
    @Suppress("unused") private val httpClient: OkHttpClient,
    private val openAiDeviceCodeAuth: OpenAiDeviceCodeAuth,
) {
    private val refreshMutex = Mutex()
    suspend fun resolveAccessToken(config: ProviderConfig): String {
        return bearerCredential(config).first
    }

    suspend fun bearerCredential(config: ProviderConfig): Pair<String, String?> {
        if (config.authMode == AuthMode.API_KEY ||
            (config.authMode != AuthMode.OAUTH_DEVICE && config.providerType != ProviderType.CODEX_CHATGPT)
        ) {
            return config.apiKey to config.accountId.takeIf { it.isNotBlank() }
        }
        val tokens = maybeRefresh(config.id)
        return tokens.accessToken to tokens.accountId.takeIf { it.isNotBlank() }
    }

    private suspend fun maybeRefresh(providerId: String): OAuthTokens = refreshMutex.withLock {
        // Another request may already have rotated the refresh token while we waited.
        val tokens = settingsRepository.getOAuthTokens(providerId)
        check(tokens != null) { "Log in with ChatGPT again in Settings." }
        val refreshSkewMs = 2 * 60 * 1000L
        val now = System.currentTimeMillis()
        val needsRefresh = tokens.accessToken.isBlank() ||
            tokens.expiresAtEpochMs == 0L ||
            tokens.expiresAtEpochMs <= now + refreshSkewMs

        if (!needsRefresh) {
            return@withLock tokens
        }
        if (tokens.refreshToken.isBlank()) {
            check(tokens.accessToken.isNotBlank() &&
                (tokens.expiresAtEpochMs == 0L || tokens.expiresAtEpochMs > now)) {
                "Your ChatGPT session expired. Log in with ChatGPT again in Settings."
            }
            return@withLock tokens
        }

        val refreshed = openAiDeviceCodeAuth.refresh(tokens)
        check(settingsRepository.replaceOAuthTokens(providerId, tokens, refreshed)) {
            "ChatGPT login changed. Please try again."
        }
        refreshed
    }
}
