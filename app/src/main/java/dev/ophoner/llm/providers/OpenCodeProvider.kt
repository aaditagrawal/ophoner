package dev.ophoner.llm.providers

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.llm.LlmMessage
import dev.ophoner.llm.LlmProvider
import dev.ophoner.llm.LlmResponseChunk
import dev.ophoner.llm.models.ModelApi
import dev.ophoner.llm.models.ModelsDevCatalog
import dev.ophoner.tools.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient

/** Go serves different models through different wire protocols; the catalog owns that mapping. */
class OpenCodeProvider(client: OkHttpClient, private val catalog: ModelsDevCatalog) : LlmProvider {
    override val providerId = "opencode-go"
    private val chat = OpenAiCompatibleProvider(client)
    private val responses = CodexChatGptProvider(client)
    private val anthropic = ClaudeProvider(client)

    override fun streamCompletion(
        messages: List<LlmMessage>, tools: List<Tool>, config: ProviderConfig,
    ): Flow<LlmResponseChunk> = flow {
        val provider = when (catalog.modelApi(providerId, config.modelId)) {
            ModelApi.CHAT_COMPLETIONS -> chat
            ModelApi.RESPONSES -> responses
            ModelApi.ANTHROPIC -> anthropic
        }
        emitAll(provider.streamCompletion(messages, tools, config))
    }
}
