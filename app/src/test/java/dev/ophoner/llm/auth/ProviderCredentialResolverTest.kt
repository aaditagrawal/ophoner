package dev.ophoner.llm.auth

import dev.ophoner.data.model.AuthMode
import dev.ophoner.data.model.OAuthTokens
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderType
import dev.ophoner.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ProviderCredentialResolverTest {
    private val settings = mockk<SettingsRepository>()
    private val auth = mockk<OpenAiDeviceCodeAuth>()
    private val resolver = ProviderCredentialResolver(settings, OkHttpClient(), auth)
    private val config = ProviderConfig("chatgpt", "ChatGPT", baseUrl = "https://chatgpt.com/backend-api/codex",
        modelId = "test-model", providerType = ProviderType.CODEX_CHATGPT, authMode = AuthMode.OAUTH_DEVICE,
        accountId = "stale-account")

    @Test fun `concurrent requests share one token refresh`() = runBlocking {
        var saved = OAuthTokens("old", "refresh", expiresAtEpochMs = 1, accountId = "account")
        val fresh = saved.copy(accessToken = "fresh", refreshToken = "rotated",
            expiresAtEpochMs = System.currentTimeMillis() + 3_600_000)
        every { settings.getOAuthTokens(config.id) } answers { saved }
        coEvery { auth.refresh(any()) } coAnswers { delay(50); fresh }
        every { settings.replaceOAuthTokens(config.id, any(), fresh) } answers { saved = fresh; true }
        val credentials = (1..8).map { async { resolver.bearerCredential(config) } }.awaitAll()
        assertTrue(credentials.all { it == "fresh" to "account" })
        coVerify(exactly = 1) { auth.refresh(any()) }
    }

    @Test fun `missing session asks to sign in instead of using a stale key`() = runBlocking {
        every { settings.getOAuthTokens(config.id) } returns null
        val failure = runCatching { resolver.resolveAccessToken(config.copy(apiKey = "stale-key")) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("Log in with ChatGPT"))
    }

    @Test fun `sign out during refresh does not restore the session`() = runBlocking {
        val tokens = OAuthTokens("old", "refresh", expiresAtEpochMs = 1)
        every { settings.getOAuthTokens(config.id) } returns tokens
        coEvery { auth.refresh(tokens) } returns tokens.copy(accessToken = "fresh")
        every { settings.replaceOAuthTokens(config.id, tokens, any()) } returns false
        val failure = runCatching { resolver.resolveAccessToken(config) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("login changed"))
        coVerify(exactly = 0) { settings.saveOAuthTokens(any(), any()) }
    }

    @Test fun `API key providers do not use ChatGPT credentials`() = runBlocking {
        assertEquals("api-key", resolver.resolveAccessToken(config.copy(authMode = AuthMode.API_KEY, apiKey = "api-key")))
    }
}
