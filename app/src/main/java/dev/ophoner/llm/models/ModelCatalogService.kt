package dev.ophoner.llm.models

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderPresets
import dev.ophoner.data.model.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches remote model id lists for settings pickers.
 * Callers should merge [fetchModels] results with [mergeModelOptions] (custom slugs + presets).
 */
@Singleton
class ModelCatalogService @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetchModels(config: ProviderConfig): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            when (config.providerType) {
                ProviderType.OPENAI, ProviderType.CUSTOM_OPENAI ->
                    fetchOpenAiStyleModels(config.baseUrl, bearer = config.apiKey)

                ProviderType.OPENROUTER ->
                    prioritizeOpenRouter(
                        fetchOpenAiStyleModels(OPENROUTER_MODELS_URL, bearer = config.apiKey),
                    )

                ProviderType.CLAUDE, ProviderType.CUSTOM_ANTHROPIC ->
                    fetchAnthropicModels(config.baseUrl, apiKey = config.apiKey)

                ProviderType.GEMINI ->
                    fetchGeminiModels(config)

                ProviderType.CODEX_CHATGPT ->
                    fetchCodexModels(config)
            }
        }
    }

    /**
     * Union of current model, custom slugs, presets, and remote ids.
     * Distinct; current [ProviderConfig.modelId] first when non-blank; remainder sorted.
     */
    fun mergeModelOptions(
        remote: List<String>,
        config: ProviderConfig,
        presets: List<String>,
    ): List<String> {
        val current = config.modelId.trim().takeIf { it.isNotEmpty() }
        val rest = LinkedHashSet<String>()
        config.customModelSlugs.forEach { slug ->
            slug.trim().takeIf { it.isNotEmpty() && it != current }?.let(rest::add)
        }
        presets.forEach { slug ->
            slug.trim().takeIf { it.isNotEmpty() && it != current }?.let(rest::add)
        }
        remote.forEach { slug ->
            slug.trim().takeIf { it.isNotEmpty() && it != current }?.let(rest::add)
        }
        val sortedRest = rest.sorted()
        return if (current != null) listOf(current) + sortedRest else sortedRest
    }

    private fun fetchOpenAiStyleModels(baseOrUrl: String, bearer: String): List<String> {
        requireApiKey(bearer)
        val url = if (baseOrUrl.contains("/models")) {
            baseOrUrl.trimEnd('/')
        } else {
            "${baseOrUrl.trimEnd('/')}/models"
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearer")
            .get()
            .build()
        return parseOpenAiDataIds(execute(request))
    }

    private fun fetchAnthropicModels(baseUrl: String, apiKey: String): List<String> {
        requireApiKey(apiKey)
        val base = baseUrl.trimEnd('/')
        val url = if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
        val request = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .get()
            .build()
        return parseOpenAiDataIds(execute(request))
    }

    private fun fetchGeminiModels(config: ProviderConfig): List<String> {
        requireApiKey(config.apiKey)
        // Prefer native Generative Language list; strip "models/" and keep gemini* names.
        return runCatching {
            val url = GEMINI_NATIVE_MODELS.toHttpUrl().newBuilder()
                .addQueryParameter("key", config.apiKey)
                .build()
            val request = Request.Builder().url(url).get().build()
            parseGeminiModelNames(execute(request))
        }.getOrElse { nativeError ->
            val base = config.baseUrl.trimEnd('/')
            val openaiCompat = base.contains("openai", ignoreCase = true)
            if (!openaiCompat) throw nativeError
            fetchOpenAiStyleModels(base, bearer = config.apiKey)
        }
    }

    private fun fetchCodexModels(config: ProviderConfig): List<String> {
        requireApiKey(config.apiKey)
        val url = "${config.baseUrl.trimEnd('/')}/models"
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .get()
        val accountId = config.accountId.trim()
        if (accountId.isNotEmpty()) {
            builder.header("ChatGPT-Account-ID", accountId)
        }
        return parseOpenAiDataIds(execute(builder.build()))
    }

    private fun execute(request: Request): String {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Model catalog HTTP ${response.code}: $body")
            }
            return body
        }
    }

    private fun parseOpenAiDataIds(raw: String): List<String> {
        val root = json.parseToJsonElement(raw).jsonObject
        val data = root["data"] as? JsonArray
            ?: throw IOException("Model catalog missing data[]: $raw")
        return data.mapNotNull { element ->
            (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.distinct()
    }

    private fun parseGeminiModelNames(raw: String): List<String> {
        val root = json.parseToJsonElement(raw).jsonObject
        val models = root["models"] as? JsonArray
            ?: throw IOException("Gemini catalog missing models[]: $raw")
        return models.mapNotNull { element ->
            val name = (element as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val id = name.removePrefix("models/").trim()
            id.takeIf { it.contains("gemini", ignoreCase = true) }
        }.distinct().sorted()
    }

    private fun prioritizeOpenRouter(ids: List<String>): List<String> {
        val popular = ProviderPresets.forType(ProviderType.OPENROUTER)?.suggestedModels.orEmpty()
        val popularSet = popular.toSet()
        val presentPopular = popular.filter { it in ids.toSet() }
        val rest = ids.filterNot { it in popularSet }.sorted()
        return presentPopular + rest
    }

    private fun requireApiKey(apiKey: String) {
        if (apiKey.isBlank()) {
            throw IOException("API key required to fetch models")
        }
    }

    private companion object {
        const val OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models"
        const val GEMINI_NATIVE_MODELS = "https://generativelanguage.googleapis.com/v1beta/models"
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
