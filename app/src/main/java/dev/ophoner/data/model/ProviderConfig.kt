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
    /** Follow the catalog. The previous ChatGPT preset migrates to automatic selection. */
    val useLatestModel: Boolean = providerType == ProviderType.CODEX_CHATGPT &&
        (modelId.isBlank() || modelId == "gpt-5.4"),
    /** Conservative defaults for custom endpoints; may be overridden in provider imports. */
    val contextWindowTokens: Int = 32_768,
    val maxOutputTokens: Int = 4_096,
    val promptCaching: Boolean = true,
    /** Processing speed for the ChatGPT subscription connection. */
    val chatGptSpeed: ChatGptSpeed = ChatGptSpeed.STANDARD,
)

@Serializable
enum class ChatGptSpeed(val label: String, val serviceTier: String) {
    STANDARD("Standard", "default"),
    FAST("Fast", "fast"),
}

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
    OPENCODE_GO,
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
