package dev.ophoner.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ProviderConfig(
    val id: String,
    val displayName: String,
    val apiKey: String = "",
    val baseUrl: String,
    val modelId: String,
    val providerType: ProviderType,
    val authMode: AuthMode = AuthMode.API_KEY,
    /** ChatGPT account id (Codex device-code login). */
    val accountId: String = "",
    /** User-added model ids beyond the remote catalog. */
    val customModelSlugs: List<String> = emptyList(),
    /** Optional plan label shown in settings (e.g. "ChatGPT Plus", "OpenRouter"). */
    val planLabel: String = "",
)

@Serializable
enum class AuthMode {
    /** Paste / import an API key (OpenAI, Anthropic Console, Gemini, OpenRouter key). */
    API_KEY,
    /** OpenAI Codex ChatGPT device-code OAuth. */
    OAUTH_DEVICE,
    /** OpenRouter PKCE → long-lived API key. */
    OAUTH_PKCE,
}

@Serializable
enum class ProviderType {
    CLAUDE,
    OPENAI,
    OPENROUTER,
    GEMINI,
    /** ChatGPT subscription via Codex device-code + chatgpt.com/backend-api/codex. */
    CODEX_CHATGPT,
    CUSTOM_OPENAI,
    CUSTOM_ANTHROPIC,
}

@Serializable
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String = "",
    val idToken: String = "",
    /** Epoch millis; 0 means unknown / treat as needing refresh when refreshToken present. */
    val expiresAtEpochMs: Long = 0L,
    val accountId: String = "",
)
