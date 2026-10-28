package dev.ophoner.llm.models

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderType
import io.mockk.mockk
import io.mockk.coEvery
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogServiceTest {
    @Test fun `ChatGPT default uses newest release available to account with account priority for ties`() = runBlocking {
        val releases = mockk<ModelsDevCatalog>()
        coEvery { releases.fetchModels("openai") } returns CatalogModels(
            listOf("not-on-plan", "new-small", "new-full", "old"),
            releaseDates = mapOf("not-on-plan" to "2027-03-01", "new-small" to "2027-02-01",
                "new-full" to "2027-02-01", "old" to "2026-01-01"),
        )
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"models":[{"slug":"old","priority":0},{"slug":"new-small","priority":2},{"slug":"new-full","priority":1},{"slug":"hidden","visibility":"hide"},{"slug":"subscription-only","supported_in_api":false}]}""".toResponseBody()).build()
        }.build()
        val config = ProviderConfig("id", "ChatGPT", "access", "https://chatgpt.com/backend-api/codex",
            "", ProviderType.CODEX_CHATGPT, accountId = "account")
        val result = ModelCatalogService(client, Json, releases).fetchModels(config).getOrThrow()
        assertEquals(listOf("new-full", "new-small", "old", "subscription-only"), result.ids)
    }

    @Test fun `models dev outage retains only account models`() = runBlocking {
        val releases = mockk<ModelsDevCatalog>()
        coEvery { releases.fetchModels("openai") } throws IOException("offline")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"models":[{"slug":"available"}]}""".toResponseBody()).build()
        }.build()
        val config = ProviderConfig("id", "ChatGPT", "access", "https://chatgpt.com/backend-api/codex",
            "", ProviderType.CODEX_CHATGPT, accountId = "account")
        val result = ModelCatalogService(client, Json, releases).fetchModels(config).getOrThrow()
        assertEquals(listOf("available"), result.ids)
        assertNotNull(result.warning)
    }

    @Test fun `ChatGPT catalog accepts model slugs and sends account credentials`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("Bearer access", request.header("Authorization"))
            assertEquals("account", request.header("ChatGPT-Account-ID"))
            assertEquals("0.0.0", request.url.queryParameter("client_version"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"models":[{"slug":"model-one"},{"slug":"model-two"},{"slug":"model-one"}]}""".toResponseBody()).build()
        }.build()
        val config = ProviderConfig("id", "ChatGPT", "access", "https://chatgpt.com/backend-api/codex",
            "model-one", ProviderType.CODEX_CHATGPT, accountId = "account")
        val catalog = ModelCatalogService(client, Json, mockk<ModelsDevCatalog>())
        assertEquals(listOf("model-one", "model-two"), catalog.fetchModels(config).getOrThrow().ids)
    }
}
