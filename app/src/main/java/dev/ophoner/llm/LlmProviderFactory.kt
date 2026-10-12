package dev.ophoner.llm

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderType
import dev.ophoner.llm.auth.ProviderCredentialResolver
import dev.ophoner.llm.providers.ClaudeProvider
import dev.ophoner.llm.providers.CodexChatGptProvider
import dev.ophoner.llm.providers.OpenAiCompatibleProvider
import dev.ophoner.llm.providers.OpenCodeProvider
import dev.ophoner.llm.models.ModelsDevCatalog
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmProviderFactory @Inject constructor(
    private val httpClient: OkHttpClient,
    private val credentialResolver: ProviderCredentialResolver,
    private val modelsDevCatalog: ModelsDevCatalog,
) {
    fun create(config: ProviderConfig): LlmProvider {
        return when (config.providerType) {
            ProviderType.CLAUDE, ProviderType.CUSTOM_ANTHROPIC -> ClaudeProvider(httpClient)
            ProviderType.CODEX_CHATGPT -> CodexChatGptProvider(httpClient, credentialResolver)
            ProviderType.OPENCODE_GO -> OpenCodeProvider(httpClient, modelsDevCatalog)
            ProviderType.OPENAI, ProviderType.OPENROUTER, ProviderType.GEMINI, ProviderType.CUSTOM_OPENAI ->
                OpenAiCompatibleProvider(httpClient)
        }
    }
}
