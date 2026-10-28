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
                            Snapshot(
                                models = parseModels(raw),
                                etag = response.header("ETag"),
                                releaseDates = parseReleaseDates(raw),
                                protocols = parseProtocols(raw),
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

    internal fun parseModels(raw: String): Map<String, List<String>> {
        val root = json.parseToJsonElement(raw) as? JsonObject
            ?: throw IOException("Invalid models.dev catalog")
        return (PROVIDER_IDS + OPTIONAL_PROVIDER_IDS).associateWith { providerId ->
            val models = (root[providerId] as? JsonObject)?.get("models") as? JsonObject
                ?: if (providerId in OPTIONAL_PROVIDER_IDS) return@associateWith emptyList()
                else throw IOException("models.dev catalog missing $providerId")
            models.mapNotNull { (key, element) ->
                val model = element as? JsonObject ?: return@mapNotNull null
                val modalities = model["modalities"] as? JsonObject
                fun supportsText(direction: String): Boolean =
                    (modalities?.get(direction) as? JsonArray)?.any {
                        (it as? JsonPrimitive)?.contentOrNull == "text"
                    } == true
                // Ophoner needs text input/output and tools, not embedding or image-only models.
                if ((model["tool_call"] as? JsonPrimitive)?.booleanOrNull != true ||
                    !supportsText("input") || !supportsText("output") ||
                    (model["status"] as? JsonPrimitive)?.contentOrNull == "deprecated"
                ) return@mapNotNull null
                val id = (model["id"] as? JsonPrimitive)?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: key.trim()
                if (id.isEmpty()) return@mapNotNull null
                val release = (model["release_date"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                id to release
            }.sortedWith(compareByDescending<Pair<String, String>> { it.second }.thenBy { it.first })
                .map { it.first }.distinct().takeIf { it.isNotEmpty() }
                ?: throw IOException("models.dev catalog has no compatible models for $providerId")
        }
    }

    private fun parseReleaseDates(raw: String): Map<String, Map<String, String>> {
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
        return (PROVIDER_IDS + OPTIONAL_PROVIDER_IDS).associateWith { provider ->
            val models = (root[provider] as? JsonObject)?.get("models") as? JsonObject
            models.orEmpty().mapNotNull { (key, element) ->
                val model = element as? JsonObject ?: return@mapNotNull null
                val id = (model["id"] as? JsonPrimitive)?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: key.trim()
                val date = (model["release_date"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                id to date
            }.toMap()
        }
    }

    private fun parseProtocols(raw: String): Map<String, Map<String, ModelApi>> {
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
        return (PROVIDER_IDS + OPTIONAL_PROVIDER_IDS).associateWith { providerId ->
            val provider = root[providerId] as? JsonObject
            val models = provider?.get("models") as? JsonObject
            val defaultNpm = (provider?.get("npm") as? JsonPrimitive)?.contentOrNull
            models.orEmpty().mapNotNull { (key, element) ->
                val model = element as? JsonObject ?: return@mapNotNull null
                val id = (model["id"] as? JsonPrimitive)?.contentOrNull ?: key
                val npm = ((model["provider"] as? JsonObject)?.get("npm") as? JsonPrimitive)?.contentOrNull ?: defaultNpm
                id to when (npm) {
                    "@ai-sdk/openai" -> ModelApi.RESPONSES
                    "@ai-sdk/anthropic" -> ModelApi.ANTHROPIC
                    else -> ModelApi.CHAT_COMPLETIONS
                }
            }.toMap()
        }
    }

    private companion object {
        val PROVIDER_IDS = listOf("openai", "anthropic", "google", "openrouter")
        val OPTIONAL_PROVIDER_IDS = listOf("opencode-go")
    }
}
