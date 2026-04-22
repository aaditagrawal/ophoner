package dev.ophoner.llm

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderType
import dev.ophoner.llm.providers.ClaudeProvider
import dev.ophoner.llm.providers.OpenAiCompatibleProvider
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmProviderFactory @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    fun create(config: ProviderConfig): LlmProvider {
        return when (config.providerType) {
            ProviderType.CLAUDE, ProviderType.CUSTOM_ANTHROPIC -> ClaudeProvider(httpClient)
            ProviderType.OPENAI, ProviderType.GEMINI, ProviderType.CUSTOM_OPENAI ->
                OpenAiCompatibleProvider(httpClient)
        }
    }
}
