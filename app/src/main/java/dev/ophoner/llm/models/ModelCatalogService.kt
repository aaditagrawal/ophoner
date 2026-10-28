package dev.ophoner.llm.models

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderPresets
import dev.ophoner.data.model.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uses models.dev for public providers and account/endpoint discovery for other connections.
 */
@Singleton
class ModelCatalogService @Inject constructor(
    httpClient: OkHttpClient,
    private val json: Json,
    private val modelsDevCatalog: ModelsDevCatalog,
) {
    private val httpClient = httpClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun cachedModels(config: ProviderConfig): List<String> =
        modelsDevProviderId(config)?.let { modelsDevCatalog.cachedModels(it) }.orEmpty()

    suspend fun fetchModels(config: ProviderConfig): Result<CatalogModels> = withContext(Dispatchers.IO) {
        try {
            if (config.providerType == ProviderType.CODEX_CHATGPT) {
                // Subscription availability comes from the account; release order comes from models.dev.
                val available = fetchCodexModels(config)
                val releases = try {
                    modelsDevCatalog.fetchModels("openai")
                } catch (error: Exception) {
                    ensureActive()
                    return@withContext Result.success(CatalogModels(
                        available,
                        "Could not refresh models.dev. Showing your account's model order.",
                    ))
                }
                val accountIds = available.toSet()
                val ranked = if (releases.releaseDates.isNotEmpty()) {
                    // Stable sorting keeps the account's preferred order for same-day releases.
                    available.filter { it in releases.ids }.sortedByDescending { releases.releaseDates[it].orEmpty() }
                } else releases.ids.filter { it in accountIds }
                return@withContext Result.success(CatalogModels(
                    (ranked + available).distinct(), releases.warning,
                ))
            }
            val publicProvider = modelsDevProviderId(config)
            if (publicProvider != null) {
                try {
                    return@withContext Result.success(modelsDevCatalog.fetchModels(publicProvider))
                } catch (error: Exception) {
                    ensureActive()
                    if (config.apiKey.isBlank()) throw error
                    // First launch during a catalog outage can still use the provider's own API.
                }
            }
            val ids = when (config.providerType) {
                ProviderType.OPENAI, ProviderType.CUSTOM_OPENAI, ProviderType.OPENCODE_GO ->
                    fetchOpenAiStyleModels(config.baseUrl, bearer = config.apiKey)

                ProviderType.OPENROUTER ->
                    fetchOpenAiStyleModels(config.baseUrl, bearer = config.apiKey)

                ProviderType.CLAUDE, ProviderType.CUSTOM_ANTHROPIC ->
                    fetchAnthropicModels(config.baseUrl, apiKey = config.apiKey)

                ProviderType.GEMINI ->
                    fetchGeminiModels(config)

                ProviderType.CODEX_CHATGPT ->
                    fetchCodexModels(config)
            }
            ensureActive()
            Result.success(CatalogModels(ids))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /**
     * Keep the selection and custom ids first, followed by the catalog's release-date order.
     */
    fun mergeModelOptions(
        remote: List<String>,
        config: ProviderConfig,
    ): List<String> {
        val current = config.modelId.trim().takeIf { it.isNotEmpty() }
        val rest = LinkedHashSet<String>()
        config.customModelSlugs.forEach { slug ->
            slug.trim().takeIf { it.isNotEmpty() && it != current }?.let(rest::add)
        }
        remote.forEach { slug ->
            slug.trim().takeIf { it.isNotEmpty() && it != current }?.let(rest::add)
        }
        return if (current != null) listOf(current) + rest else rest.toList()
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
        if (modelsDevProviderId(config) == null) {
            return fetchOpenAiStyleModels(config.baseUrl, bearer = config.apiKey)
        }
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
        val url = "${config.baseUrl.trimEnd('/')}/models".toHttpUrl().newBuilder()
            // This is a Codex compatibility version, not Ophoner's app version.
            // The unversioned catalog avoids filtering models against an unrelated 0.x release.
            .addQueryParameter("client_version", "0.0.0")
            .build()
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .get()
        val accountId = config.accountId.trim()
        if (accountId.isNotEmpty()) {
            builder.header("ChatGPT-Account-ID", accountId)
        }
        val root = json.parseToJsonElement(execute(builder.build())).jsonObject
        val models = root["models"] as? JsonArray ?: root["data"] as? JsonArray
            ?: throw IOException("ChatGPT model catalog did not include a model list")
        return models.mapNotNull { element ->
            val model = element as? JsonObject ?: return@mapNotNull null
            // API-key availability does not restrict models offered to a ChatGPT subscription.
            if (model["hidden"]?.jsonPrimitive?.booleanOrNull == true ||
                model["visibility"]?.jsonPrimitive?.contentOrNull == "hide"
            ) return@mapNotNull null
            val id = (model["slug"] ?: model["id"])?.jsonPrimitive?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            id to (model["priority"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE)
        }.sortedBy { it.second }.map { it.first }.distinct().also {
            if (it.isEmpty()) throw IOException("No available ChatGPT models were returned")
        }
    }

    private fun execute(request: Request): String {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Model catalog HTTP ${response.code} (${request.url.host})")
            }
            return body
        }
    }

    private fun parseOpenAiDataIds(raw: String): List<String> {
        val root = json.parseToJsonElement(raw).jsonObject
        val data = root["data"] as? JsonArray
            ?: throw IOException("Model catalog missing data[]")
        return data.mapNotNull { element ->
            (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.distinct()
    }

    private fun parseGeminiModelNames(raw: String): List<String> {
        val root = json.parseToJsonElement(raw).jsonObject
        val models = root["models"] as? JsonArray
            ?: throw IOException("Gemini catalog missing models[]")
        return models.mapNotNull { element ->
            val name = (element as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val id = name.removePrefix("models/").trim()
            id.takeIf { it.contains("gemini", ignoreCase = true) }
        }.distinct().sorted()
    }

    private fun requireApiKey(apiKey: String) {
        if (apiKey.isBlank()) {
            throw IOException("API key required to fetch models")
        }
    }

    companion object {
        /** Never substitute the public API catalog for a custom URL or a ChatGPT subscription. */
        fun modelsDevProviderId(config: ProviderConfig): String? {
            val defaultUrl = ProviderPresets.forType(config.providerType)?.defaultBaseUrl
            if (config.baseUrl.trimEnd('/') != defaultUrl?.trimEnd('/')) return null
            return when (config.providerType) {
                ProviderType.OPENAI -> "openai"
                ProviderType.CLAUDE -> "anthropic"
                ProviderType.GEMINI -> "google"
                ProviderType.OPENROUTER -> "openrouter"
                ProviderType.OPENCODE_GO -> "opencode-go"
                else -> null
            }
        }

        private const val GEMINI_NATIVE_MODELS = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
