package dev.ophoner.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ProviderConfig(
    val id: String,
    val displayName: String,
    val apiKey: String,
    val baseUrl: String,
    val modelId: String,
    val providerType: ProviderType,
)

@Serializable
enum class ProviderType {
    CLAUDE,
    OPENAI,
    GEMINI,
    CUSTOM_OPENAI,
    CUSTOM_ANTHROPIC,
}
