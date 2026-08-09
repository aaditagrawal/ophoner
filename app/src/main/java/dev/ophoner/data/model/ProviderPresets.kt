package dev.ophoner.data.model

/**
 * Built-in provider plans / defaults for the settings "Add provider" flow.
 * Users can still override base URL and model slug freely.
 */
object ProviderPresets {
    data class Plan(
        val id: String,
        val title: String,
        val subtitle: String,
        val providerType: ProviderType,
        val authMode: AuthMode,
        val defaultBaseUrl: String,
        val defaultModelId: String,
        val defaultDisplayName: String,
        val planLabel: String,
        /** Suggested model slugs shown before a live catalog fetch. */
        val suggestedModels: List<String> = emptyList(),
        val apiKeyHint: String = "",
        val consoleUrl: String = "",
        val supportsDeviceLogin: Boolean = false,
        val supportsPkceLogin: Boolean = false,
    )

    val ALL: List<Plan> = listOf(
        Plan(
            id = "openai_api",
            title = "OpenAI API",
            subtitle = "Platform usage key (sk-…)",
            providerType = ProviderType.OPENAI,
            authMode = AuthMode.API_KEY,
            defaultBaseUrl = "https://api.openai.com/v1",
            defaultModelId = "gpt-5.4",
            defaultDisplayName = "OpenAI",
            planLabel = "API",
            suggestedModels = listOf("gpt-5.4", "gpt-5.2", "gpt-4.1", "o4-mini", "o3"),
            apiKeyHint = "platform.openai.com/api-keys",
            consoleUrl = "https://platform.openai.com/api-keys",
        ),
        Plan(
            id = "codex_chatgpt",
            title = "ChatGPT / Codex",
            subtitle = "Sign in with device code (Plus/Pro/Team)",
            providerType = ProviderType.CODEX_CHATGPT,
            authMode = AuthMode.OAUTH_DEVICE,
            defaultBaseUrl = "https://chatgpt.com/backend-api/codex",
            defaultModelId = "gpt-5.4",
            defaultDisplayName = "ChatGPT Codex",
            planLabel = "ChatGPT",
            suggestedModels = listOf("gpt-5.4", "gpt-5.2", "gpt-5.1-codex", "o3", "o4-mini"),
            consoleUrl = "https://auth.openai.com/codex/device",
            supportsDeviceLogin = true,
        ),
        Plan(
            id = "claude_api",
            title = "Anthropic Claude",
            subtitle = "Console API key (sk-ant-…). Claude.ai login is not allowed in third-party apps.",
            providerType = ProviderType.CLAUDE,
            authMode = AuthMode.API_KEY,
            defaultBaseUrl = "https://api.anthropic.com",
            defaultModelId = "claude-sonnet-4-5",
            defaultDisplayName = "Claude",
            planLabel = "API",
            suggestedModels = listOf(
                "claude-opus-4-5",
                "claude-sonnet-4-5",
                "claude-haiku-4-5",
                "claude-sonnet-4-20250514",
            ),
            apiKeyHint = "console.anthropic.com/settings/keys",
            consoleUrl = "https://console.anthropic.com/settings/keys",
        ),
        Plan(
            id = "openrouter",
            title = "OpenRouter",
            subtitle = "PKCE login or paste sk-or-… key",
            providerType = ProviderType.OPENROUTER,
            authMode = AuthMode.API_KEY,
            defaultBaseUrl = "https://openrouter.ai/api/v1",
            defaultModelId = "anthropic/claude-sonnet-4.5",
            defaultDisplayName = "OpenRouter",
            planLabel = "OpenRouter",
            suggestedModels = listOf(
                "anthropic/claude-sonnet-4.5",
                "openai/gpt-5.4",
                "google/gemini-2.5-pro",
                "deepseek/deepseek-chat",
            ),
            apiKeyHint = "openrouter.ai/keys",
            consoleUrl = "https://openrouter.ai/keys",
            supportsPkceLogin = true,
        ),
        Plan(
            id = "gemini",
            title = "Google Gemini",
            subtitle = "AI Studio API key (AIza…)",
            providerType = ProviderType.GEMINI,
            authMode = AuthMode.API_KEY,
            defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            defaultModelId = "gemini-2.5-pro",
            defaultDisplayName = "Gemini",
            planLabel = "AI Studio",
            suggestedModels = listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash"),
            apiKeyHint = "aistudio.google.com/apikey",
            consoleUrl = "https://aistudio.google.com/apikey",
        ),
        Plan(
            id = "custom_openai",
            title = "Custom OpenAI-compatible",
            subtitle = "Any /v1/chat/completions endpoint",
            providerType = ProviderType.CUSTOM_OPENAI,
            authMode = AuthMode.API_KEY,
            defaultBaseUrl = "",
            defaultModelId = "",
            defaultDisplayName = "Custom OpenAI",
            planLabel = "Custom",
        ),
        Plan(
            id = "custom_anthropic",
            title = "Custom Anthropic-compatible",
            subtitle = "Any /v1/messages endpoint",
            providerType = ProviderType.CUSTOM_ANTHROPIC,
            authMode = AuthMode.API_KEY,
            defaultBaseUrl = "",
            defaultModelId = "",
            defaultDisplayName = "Custom Anthropic",
            planLabel = "Custom",
        ),
    )

    fun forType(type: ProviderType): Plan? = ALL.find { it.providerType == type && it.id != "custom_openai" }
        ?: ALL.find { it.providerType == type }
}
