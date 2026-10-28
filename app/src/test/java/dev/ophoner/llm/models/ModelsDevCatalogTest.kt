package dev.ophoner.llm.models

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderPresets
import dev.ophoner.data.model.ProviderType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ModelsDevCatalogTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = mutableListOf<Request>()
    private var respond: (Request) -> Response = { response(it, fixture(), etag = "v1") }
    private val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        requests.add(chain.request())
        respond(chain.request())
    }).build()

    private fun catalog(file: File = File(temporaryFolder.root, "catalog.json")) =
        ModelsDevCatalog(client, json, file)

    @Test fun `filters unsupported and deprecated models and sorts newest first`() {
        val models = catalog().parseModels(fixture())
        assertEquals(listOf("new-model", "old-model", "undated"), models.getValue("openai"))
        assertEquals(listOf("vendor/new-model", "vendor/old-model", "vendor/undated"), models["openrouter"])
    }

    @Test fun `loads without credentials and conditionally revalidates on every fetch`() = runBlocking {
        val catalog = catalog()
        val first = catalog.fetchModels("openai")
        assertNull(first.warning)
        assertEquals("https://models.dev/api.json", requests.single().url.toString())
        assertNull(requests.single().header("Authorization"))
        assertNull(requests.single().header("x-api-key"))
        assertNull(requests.single().header("If-None-Match"))
        respond = { response(it, "", code = 304) }
        assertEquals(first, catalog.fetchModels("openai"))
        assertEquals("v1", requests.last().header("If-None-Match"))
        assertEquals("no-cache", requests.last().header("Cache-Control"))
        assertEquals(2, requests.size)
    }

    @Test fun `persists catalog across launches and retains it when offline`() = runBlocking {
        val first = catalog().fetchModels("google")
        val restarted = catalog()
        assertEquals(first.ids, restarted.cachedModels("google"))
        respond = { throw IOException("offline") }
        val offline = restarted.fetchModels("google")
        assertEquals(first.ids, offline.ids)
        assertEquals(first.releaseDates, offline.releaseDates)
        assertEquals("2026-09-01", offline.releaseDates["new-model"])
        assertNotNull(offline.warning)
    }

    @Test fun `old cache is upgraded with release dates even when its etag matches`() = runBlocking {
        File(temporaryFolder.root, "catalog.json").writeText(
            """{"models":{"openai":["old"],"anthropic":["old"],"google":["old"],"openrouter":["old"]},"etag":"v1"}""",
        )
        val updated = catalog().fetchModels("openai")
        assertNull(requests.single().header("If-None-Match"))
        assertEquals("2026-09-01", updated.releaseDates["new-model"])
    }

    @Test fun `malformed catalog cannot replace the last good cache`() = runBlocking {
        val catalog = catalog()
        val first = catalog.fetchModels("anthropic")
        for (invalid in listOf("not json", "{}", "{\"openai\":{\"models\":{}}}")) {
            respond = { response(it, invalid, etag = "broken") }
            assertEquals(first.ids, catalog.fetchModels("anthropic").ids)
            assertEquals("v1", requests.last().header("If-None-Match"))
            assertEquals(first.ids, catalog().cachedModels("anthropic"))
        }
    }

    @Test fun `new catalog replaces removed models`() = runBlocking {
        val catalog = catalog()
        catalog.fetchModels("openai")
        respond = { response(it, fixture().replace("new-model", "newer-model"), etag = "v2") }
        val updated = catalog.fetchModels("openai")
        assertTrue("newer-model" in updated.ids)
        assertFalse("new-model" in updated.ids)
        assertEquals(updated.ids, catalog().cachedModels("openai"))
    }

    @Test fun `disk failure does not discard a successful remote response`() = runBlocking {
        val catalog = catalog(File(temporaryFolder.root, "missing/directory/cache.json"))
        assertNull(catalog.fetchModels("openai").warning)
        respond = { throw IOException("offline") }
        assertTrue(catalog.fetchModels("openai").ids.isNotEmpty())
    }

    @Test fun `corrupt cache is replaced by a valid remote response`() = runBlocking {
        File(temporaryFolder.root, "catalog.json").writeText("invalid")
        val catalog = catalog()
        assertEquals(emptyList<String>(), catalog.cachedModels("openai"))
        assertTrue(catalog.fetchModels("openai").ids.isNotEmpty())
    }

    @Test fun `first offline launch reports failure rather than an empty successful catalog`() {
        respond = { response(it, "unavailable", code = 503) }
        assertThrows(IOException::class.java) { runBlocking { catalog().fetchModels("openai") } }
    }

    @Test fun `Go model protocol overrides survive offline launches`() = runBlocking {
        val model = """"tool_call":true,"modalities":{"input":["text"],"output":["text"]}"""
        respond = { response(it, fixture().dropLast(1) + """,
            "opencode-go":{"npm":"@ai-sdk/openai-compatible","models":{
                "chat":{$model},
                "responses":{$model,"provider":{"npm":"@ai-sdk/openai"}},
                "anthropic":{$model,"provider":{"npm":"@ai-sdk/anthropic"}}
            }}}""") }
        catalog().fetchModels("opencode-go")
        respond = { throw IOException("offline") }
        val restarted = catalog()
        assertEquals(ModelApi.CHAT_COMPLETIONS, restarted.modelApi("opencode-go", "chat"))
        assertEquals(ModelApi.RESPONSES, restarted.modelApi("opencode-go", "responses"))
        assertEquals(ModelApi.ANTHROPIC, restarted.modelApi("opencode-go", "anthropic"))
    }

    @Test fun `only built in API endpoints map to models dev`() {
        val expected = mapOf(
            ProviderType.OPENAI to "openai", ProviderType.CLAUDE to "anthropic",
            ProviderType.GEMINI to "google", ProviderType.OPENROUTER to "openrouter",
            ProviderType.OPENCODE_GO to "opencode-go",
        )
        for (type in ProviderType.entries) {
            val config = config(type)
            assertEquals(expected[type], ModelCatalogService.modelsDevProviderId(config))
            assertEquals(expected[type], ModelCatalogService.modelsDevProviderId(config.copy(baseUrl = config.baseUrl + "/")))
            assertNull(ModelCatalogService.modelsDevProviderId(config.copy(baseUrl = "https://private.example/v1")))
        }
    }

    @Test fun `API credentials are not sent to models dev`() = runBlocking {
        val service = ModelCatalogService(client, json, catalog())
        val result = service.fetchModels(config(ProviderType.OPENAI).copy(apiKey = "private-key"))
        assertTrue(result.isSuccess)
        assertNull(requests.single().header("Authorization"))
        assertNull(requests.single().header("x-api-key"))
    }

    @Test fun `custom endpoints use their own catalog and credentials`() = runBlocking {
        respond = { response(it, """{"data":[{"id":"private-model"}]}""") }
        val service = ModelCatalogService(client, json, catalog())
        val config = config(ProviderType.CUSTOM_OPENAI).copy(
            baseUrl = "https://private.example/v1", apiKey = "private-key",
        )
        assertEquals(listOf("private-model"), service.fetchModels(config).getOrThrow().ids)
        assertEquals("https://private.example/v1/models", requests.single().url.toString())
        assertEquals("Bearer private-key", requests.single().header("Authorization"))
    }

    @Test fun `ChatGPT models come from the account catalog rather than the public API`() = runBlocking {
        respond = {
            response(it, if (it.url.host == "models.dev") fixture()
            else """{"models":[{"slug":"subscription-model"}]}""")
        }
        val service = ModelCatalogService(client, json, catalog())
        val config = config(ProviderType.CODEX_CHATGPT).copy(apiKey = "token", accountId = "account")
        assertEquals(listOf("subscription-model"), service.fetchModels(config).getOrThrow().ids)
        val accountRequest = requests.single { it.url.host == "chatgpt.com" }
        assertEquals("account", accountRequest.header("ChatGPT-Account-ID"))
        assertNotNull(accountRequest.url.queryParameter("client_version"))
        val publicRequest = requests.single { it.url.host == "models.dev" }
        assertNull(publicRequest.header("Authorization"))
        assertNull(publicRequest.header("ChatGPT-Account-ID"))
    }

    @Test fun `overridden provider URLs do not fetch the official provider catalog`() = runBlocking {
        respond = { response(it, """{"data":[{"id":"private-model"}]}""") }
        val service = ModelCatalogService(client, json, catalog())
        for (type in listOf(ProviderType.OPENAI, ProviderType.CLAUDE, ProviderType.GEMINI, ProviderType.OPENROUTER)) {
            val config = config(type).copy(baseUrl = "https://private.example/v1", apiKey = "private-key")
            assertEquals(listOf("private-model"), service.fetchModels(config).getOrThrow().ids)
            assertEquals("https://private.example/v1/models", requests.last().url.toString())
        }
    }

    @Test fun `provider API is available as fallback on first launch`() = runBlocking {
        respond = {
            if (it.url.host == "models.dev") response(it, "unavailable", code = 503)
            else response(it, """{"data":[{"id":"provider-model"}]}""")
        }
        val service = ModelCatalogService(client, json, catalog())
        assertEquals(
            listOf("provider-model"),
            service.fetchModels(config(ProviderType.OPENAI).copy(apiKey = "key")).getOrThrow().ids,
        )
        assertEquals(listOf("models.dev", "api.openai.com"), requests.map { it.url.host })
    }

    @Test fun `selection and manual IDs survive refresh without adding hardcoded suggestions`() {
        val service = ModelCatalogService(client, json, catalog())
        val config = config(ProviderType.OPENAI).copy(
            modelId = " selected ", customModelSlugs = listOf("custom", " selected", "custom", " "),
        )
        assertEquals(
            listOf("selected", "custom", "newest", "older"),
            service.mergeModelOptions(listOf("newest", "older", "custom", "selected", ""), config),
        )
    }

    private fun config(type: ProviderType): ProviderConfig {
        val plan = requireNotNull(ProviderPresets.forType(type))
        return ProviderConfig("id", "Provider", baseUrl = plan.defaultBaseUrl, modelId = "", providerType = type)
    }

    private fun response(request: Request, body: String, code: Int = 200, etag: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(body.toResponseBody()).apply { etag?.let { header("ETag", it) } }.build()

    private fun fixture(): String = listOf("openai", "anthropic", "google", "openrouter")
        .joinToString(prefix = "{", postfix = "}") { provider ->
            val prefix = if (provider == "openrouter") "vendor/" else ""
            """"$provider":{"models":{
                "${prefix}old-model":{"tool_call":true,"release_date":"2025-01-01","modalities":{"input":["text"],"output":["text"]}},
                "${prefix}new-model":{"id":"${prefix}new-model","tool_call":true,"release_date":"2026-09-01","modalities":{"input":["text","image"],"output":["text"]},"future_field":{}},
                "${prefix}undated":{"tool_call":true,"modalities":{"input":["text"],"output":["text"]}},
                "embedding":{"tool_call":false,"modalities":{"input":["text"],"output":["text"]}},
                "image-only":{"tool_call":true,"modalities":{"input":["text"],"output":["image"]}},
                "deprecated":{"tool_call":true,"status":"deprecated","modalities":{"input":["text"],"output":["text"]}},
                "unknown-capabilities":{},"malformed":null
            }}"""
        }
}
