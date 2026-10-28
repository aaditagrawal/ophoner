package dev.ophoner.llm.models

import dev.ophoner.data.model.AuthMode
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderType
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.llm.auth.ProviderCredentialResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProviderModelResolverTest {
    private val catalog = mockk<ModelCatalogService>()
    private val credentials = mockk<ProviderCredentialResolver>()
    private val settings = mockk<SettingsRepository>()
    private val resolver = ProviderModelResolver(catalog, credentials, settings)
    private val config = ProviderConfig("id", "ChatGPT", baseUrl = "https://chatgpt.com/backend-api/codex",
        modelId = "gpt-5.4", providerType = ProviderType.CODEX_CHATGPT, authMode = AuthMode.OAUTH_DEVICE)

    @Test fun `old saved default migrates but explicit manual selection remains manual`() {
        val old = """{"id":"id","displayName":"ChatGPT","baseUrl":"https://chatgpt.com/backend-api/codex","modelId":"gpt-5.4","providerType":"CODEX_CHATGPT","authMode":"OAUTH_DEVICE"}"""
        assertTrue(Json.decodeFromString<ProviderConfig>(old).useLatestModel)
        assertFalse(Json.decodeFromString<ProviderConfig>(old.dropLast(1) + ",\"useLatestModel\":false}").useLatestModel)
        assertFalse(Json.decodeFromString<ProviderConfig>(old.replace("gpt-5.4", "chosen-model")).useLatestModel)
    }

    @Test fun `automatic selection persists the newest account model without persisting bearer tokens`() = runBlocking {
        coEvery { credentials.bearerCredential(config) } returns ("secret-token" to "account")
        coEvery { catalog.fetchModels(match { it.apiKey == "secret-token" && it.accountId == "account" }) } returns
            Result.success(CatalogModels(listOf("newest", "older")))
        coEvery { settings.updateAutomaticModel(config, "newest") } returns config.copy(modelId = "newest")
        val result = resolver.resolve(config)
        assertEquals("newest", result.modelId)
        assertTrue(result.useLatestModel)
        coVerify { settings.updateAutomaticModel(match { it.apiKey.isEmpty() }, "newest") }
    }

    @Test fun `manually selected model is never changed by catalog refresh`() = runBlocking {
        val manual = config.copy(useLatestModel = false)
        assertEquals(manual, resolver.resolve(manual))
        coVerify(exactly = 0) { catalog.fetchModels(any()) }
    }

    @Test fun `offline launch preserves last working model`() = runBlocking {
        coEvery { credentials.bearerCredential(config) } throws IOException("offline")
        assertEquals(config, resolver.resolve(config))
        coVerify(exactly = 0) { settings.updateAutomaticModel(any(), any()) }
    }

    @Test fun `first offline launch does not invent a hard coded model`() = runBlocking {
        val fresh = config.copy(modelId = "")
        coEvery { credentials.bearerCredential(fresh) } throws IOException("offline")
        val failure = runCatching { resolver.resolve(fresh) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("Could not choose a model"))
    }

    @Test fun `deleted provider is not returned as usable after catalog request`() = runBlocking {
        coEvery { credentials.bearerCredential(config) } returns ("token" to "account")
        coEvery { catalog.fetchModels(any()) } returns Result.success(CatalogModels(listOf("newest")))
        coEvery { settings.updateAutomaticModel(config, "newest") } returns null
        val failure = runCatching { resolver.resolve(config) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("provider was removed"))
    }
}
