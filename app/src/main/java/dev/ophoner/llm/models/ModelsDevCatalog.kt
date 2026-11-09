package dev.ophoner.llm.models

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class CatalogModels(
    val ids: List<String>,
    val warning: String? = null,
    val releaseDates: Map<String, String> = emptyMap(),
)

@Serializable
enum class ModelApi { CHAT_COMPLETIONS, RESPONSES, ANTHROPIC }

/** Public catalog only. Credentials must never be attached to these requests. */
@Singleton
class ModelsDevCatalog internal constructor(
    httpClient: OkHttpClient,
    private val json: Json,
    private val cacheFile: File,
) {
    @Inject
    constructor(
        httpClient: OkHttpClient,
        json: Json,
        @ApplicationContext context: Context,
    ) : this(httpClient, json, File(context.filesDir, "models-dev-catalog.json"))

    private val client = httpClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private var snapshot: Snapshot? = null
    private var cacheLoaded = false

    @Serializable
    private data class Snapshot(
        val models: Map<String, List<String>>,
        val etag: String? = null,
        val releaseDates: Map<String, Map<String, String>> = emptyMap(),
        val protocols: Map<String, Map<String, ModelApi>> = emptyMap(),
    )

    suspend fun cachedModels(providerId: String): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock { loadCache()?.models?.get(providerId).orEmpty() }
    }

    suspend fun modelApi(providerId: String, modelId: String): ModelApi {
        val cached = withContext(Dispatchers.IO) {
            mutex.withLock { loadCache()?.protocols?.get(providerId)?.get(modelId) }
        }
        if (cached != null) return cached
        fetchModels(providerId)
        return mutex.withLock { snapshot?.protocols?.get(providerId)?.get(modelId) }
            ?: ModelApi.CHAT_COMPLETIONS
    }

    /** Revalidate whenever the picker opens; an ETag avoids downloading unchanged data. */
    suspend fun fetchModels(providerId: String): CatalogModels = withContext(Dispatchers.IO) {
        mutex.withLock {
            val saved = loadCache()
            try {
                val request = Request.Builder()
                    .url("https://models.dev/api.json")
                    .header("Cache-Control", "no-cache")
                    .apply {
                        // Older caches contain IDs but no release metadata; download once to upgrade.
                        saved?.takeIf { it.releaseDates.isNotEmpty() && it.protocols.isNotEmpty() }?.etag
                            ?.let { header("If-None-Match", it) }
                    }
                    .build()
                val updated = client.newCall(request).execute().use { response ->
                    when {
                        response.code == 304 -> saved
                            ?: throw IOException("models.dev returned no catalog")
                        !response.isSuccessful -> throw IOException("models.dev HTTP ${response.code}")
                        else -> {
                            val raw = response.body.string()
                            val parsed = parseCatalog(raw)
                            Snapshot(
                                models = parsed.models,
                                etag = response.header("ETag"),
                                releaseDates = parsed.releaseDates,
                                protocols = parsed.protocols,
                            )
                        }
                    }
                }
                coroutineContext.ensureActive()
                val ids = updated.models[providerId]?.takeIf { it.isNotEmpty() }
                    ?: throw IOException("models.dev has no models for this provider")
                snapshot = updated
                if (updated !== saved) saveCache(updated)
                CatalogModels(ids, releaseDates = updated.releaseDates[providerId].orEmpty())
            } catch (error: Exception) {
                coroutineContext.ensureActive()
                val ids = saved?.models?.get(providerId)?.takeIf { it.isNotEmpty() }
                    ?: throw error
                CatalogModels(ids, "Could not refresh models.dev. Showing saved models.", saved.releaseDates[providerId].orEmpty())
            }
        }
    }

    private fun loadCache(): Snapshot? {
        if (!cacheLoaded) {
            snapshot = runCatching { json.decodeFromString<Snapshot>(cacheFile.readText()) }
                .getOrNull()?.takeIf { saved -> PROVIDER_IDS.all { !saved.models[it].isNullOrEmpty() } }
            cacheLoaded = true
        }
        return snapshot
    }

    private fun saveCache(updated: Snapshot) {
        // Keep the last complete catalog if storage is full or the process stops mid-write.
        val temporary = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        try {
            temporary.writeText(json.encodeToString(updated))
            Files.move(
                temporary.toPath(), cacheFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: IOException) {
            // The in-memory copy is still usable when disk persistence is unavailable.
        } finally {
            temporary.delete()
        }
    }

    private data class ParsedCatalog(
        val models: Map<String, List<String>>,
        val releaseDates: Map<String, Map<String, String>>,
        val protocols: Map<String, Map<String, ModelApi>>,
    )

    internal fun parseModels(raw: String): Map<String, List<String>> = parseCatalog(raw).models

    /** One walk of the catalog body. The three previous parsers each parsed and walked it again. */
    private fun parseCatalog(raw: String): ParsedCatalog {
        val root = json.parseToJsonElement(raw) as? JsonObject
            ?: throw IOException("Invalid models.dev catalog")
        val ids = LinkedHashMap<String, List<String>>()
        val dates = LinkedHashMap<String, Map<String, String>>()
        val protocols = LinkedHashMap<String, Map<String, ModelApi>>()
        for (providerId in PROVIDER_IDS + OPTIONAL_PROVIDER_IDS) {
            val provider = root[providerId] as? JsonObject
            val modelObj = provider?.get("models") as? JsonObject
            if (modelObj == null) {
                if (providerId !in OPTIONAL_PROVIDER_IDS) throw IOException("models.dev catalog missing $providerId")
                ids[providerId] = emptyList()
                dates[providerId] = emptyMap()
                protocols[providerId] = emptyMap()
                continue
            }
            val defaultNpm = (provider["npm"] as? JsonPrimitive)?.contentOrNull
            val ranked = ArrayList<Pair<String, String>>()
            val dateMap = LinkedHashMap<String, String>()
            val protocolMap = LinkedHashMap<String, ModelApi>()
            for ((key, element) in modelObj) {
                val model = element as? JsonObject ?: continue
                val dateId = (model["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: key.trim()
                val release = (model["release_date"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                dateMap[dateId] = release
                val protocolId = (model["id"] as? JsonPrimitive)?.contentOrNull ?: key
                val npm = ((model["provider"] as? JsonObject)?.get("npm") as? JsonPrimitive)?.contentOrNull ?: defaultNpm
                protocolMap[protocolId] = when (npm) {
                    "@ai-sdk/openai" -> ModelApi.RESPONSES
                    "@ai-sdk/anthropic" -> ModelApi.ANTHROPIC
                    else -> ModelApi.CHAT_COMPLETIONS
                }
                val modalities = model["modalities"] as? JsonObject
                fun supportsText(direction: String): Boolean =
                    (modalities?.get(direction) as? JsonArray)?.any {
                        (it as? JsonPrimitive)?.contentOrNull == "text"
                    } == true
                if ((model["tool_call"] as? JsonPrimitive)?.booleanOrNull != true ||
                    !supportsText("input") || !supportsText("output") ||
                    (model["status"] as? JsonPrimitive)?.contentOrNull == "deprecated"
                ) continue
                val id = (model["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: key.trim()
                if (id.isEmpty()) continue
                ranked.add(id to release)
            }
            val compatible = ranked.sortedWith(compareByDescending<Pair<String, String>> { it.second }.thenBy { it.first })
                .map { it.first }.distinct()
            if (compatible.isEmpty()) throw IOException("models.dev catalog has no compatible models for $providerId")
            ids[providerId] = compatible
            dates[providerId] = dateMap
            protocols[providerId] = protocolMap
        }
        return ParsedCatalog(ids, dates, protocols)
    }

    private companion object {
        val PROVIDER_IDS = listOf("openai", "anthropic", "google", "openrouter")
        val OPTIONAL_PROVIDER_IDS = listOf("opencode-go")
    }
}
