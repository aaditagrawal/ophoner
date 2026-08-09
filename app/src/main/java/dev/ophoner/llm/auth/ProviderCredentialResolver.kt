package dev.ophoner.llm.auth

import dev.ophoner.data.model.AuthMode
import dev.ophoner.data.model.OAuthTokens
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderType
import dev.ophoner.data.repository.SettingsRepository
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderCredentialResolver @Inject constructor(
    private val settingsRepository: SettingsRepository,
    @Suppress("unused") private val httpClient: OkHttpClient,
    private val openAiDeviceCodeAuth: OpenAiDeviceCodeAuth,
) {
    suspend fun resolveAccessToken(config: ProviderConfig): String {
        if (config.authMode == AuthMode.API_KEY) {
            return config.apiKey
        }

        val tokens = settingsRepository.getOAuthTokens(config.id)
        if (tokens == null) {
            return config.apiKey
        }

        val useOauth = config.authMode == AuthMode.OAUTH_DEVICE ||
            config.providerType == ProviderType.CODEX_CHATGPT
        if (!useOauth) {
            return config.apiKey.ifBlank { tokens.accessToken }
        }

        val refreshed = maybeRefresh(config.id, tokens)
        val access = refreshed.accessToken
        return if (config.providerType == ProviderType.CODEX_CHATGPT) {
            access.ifBlank { config.apiKey }
        } else {
            config.apiKey.ifBlank { access }
        }
    }

    suspend fun bearerCredential(config: ProviderConfig): Pair<String, String?> {
        val bearer = resolveAccessToken(config)
        val fromConfig = config.accountId.takeIf { it.isNotBlank() }
        val fromTokens = settingsRepository.getOAuthTokens(config.id)?.accountId
            ?.takeIf { it.isNotBlank() }
        return bearer to (fromConfig ?: fromTokens)
    }

    private suspend fun maybeRefresh(providerId: String, tokens: OAuthTokens): OAuthTokens {
        val refreshSkewMs = 2 * 60 * 1000L
        val now = System.currentTimeMillis()
        val needsRefresh = tokens.accessToken.isBlank() ||
            tokens.expiresAtEpochMs == 0L ||
            tokens.expiresAtEpochMs <= now + refreshSkewMs

        if (!needsRefresh || tokens.refreshToken.isBlank()) {
            return tokens
        }

        val refreshed = openAiDeviceCodeAuth.refresh(tokens)
        settingsRepository.saveOAuthTokens(providerId, refreshed)
        return refreshed
    }
}
