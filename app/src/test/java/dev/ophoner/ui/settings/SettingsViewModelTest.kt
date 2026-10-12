package dev.ophoner.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModelStore
import dev.ophoner.data.model.AuthMode
import dev.ophoner.data.model.OAuthTokens
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderType
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.llm.auth.DeviceLoginSession
import dev.ophoner.llm.auth.OpenAiDeviceCodeAuth
import dev.ophoner.llm.auth.OpenRouterAuth
import dev.ophoner.llm.auth.ProviderCredentialResolver
import dev.ophoner.llm.models.ModelCatalogService
import dev.ophoner.llm.models.CatalogModels
import dev.ophoner.tools.sandbox.SandboxedShell
import dev.ophoner.tools.sandbox.ShizukuStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<SettingsRepository>(relaxed = true)
    private val auth = mockk<OpenAiDeviceCodeAuth>()
    private val catalog = mockk<ModelCatalogService>()
    private val shell = mockk<SandboxedShell>()
    private val providers = MutableStateFlow<List<ProviderConfig>>(emptyList())
    private val activeProviderId = MutableStateFlow<String?>(null)
    private val session = DeviceLoginSession("CODE-1234", "https://auth.openai.com/codex/device", "device", 5)
    private val tokens = OAuthTokens("access", "refresh", accountId = "account")
    private val store = ViewModelStore()
    private lateinit var viewModel: SettingsViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.observeProviders() } returns providers
        every { repository.observeActiveProviderId() } returns activeProviderId
        every { repository.observeWorkingDirectoryUri() } returns flowOf(null)
        every { repository.observeSystemPrompt() } returns flowOf("")
        every { repository.observeYoloMode() } returns flowOf(false)
        every { repository.observeThemeMode() } returns flowOf("SYSTEM")
        every { repository.observeUiFont() } returns flowOf("DM_SANS")
        every { repository.observeAccent() } returns flowOf("BLUE")
        every { repository.getOAuthTokens(any()) } returns null
        every { auth.accountEmail(any()) } returns null
        every { shell.shizukuStatus() } returns ShizukuStatus.NOT_INSTALLED
        coEvery { auth.startDeviceLogin(any()) } returns session
        coEvery { auth.awaitDeviceLogin(session, any()) } returns tokens
        coEvery { catalog.fetchModels(any()) } returns Result.success(CatalogModels(listOf("latest-model")))
        viewModel = SettingsViewModel(repository, auth, mockk<OpenRouterAuth>(),
            catalog, mockk<ProviderCredentialResolver>(), shell, mockk<Context>())
        store.put("settings", viewModel)
    }

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun `direct login saves and activates a ChatGPT provider without a draft`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.loginWithChatGpt()
        assertTrue(viewModel.uiState.value.deviceLogin!!.busy)
        advanceUntilIdle()
        coVerify(exactly = 1) {
            repository.connectChatGpt(match {
                it.providerType == ProviderType.CODEX_CHATGPT && it.authMode == AuthMode.OAUTH_DEVICE &&
                    it.apiKey.isEmpty() && it.accountId == "account" && it.modelId == "latest-model" && it.useLatestModel
            }, tokens)
        }
        assertNull(viewModel.uiState.value.deviceLogin)
        assertEquals("Signed in with ChatGPT", viewModel.uiState.value.authMessage)
    }

    @Test fun `reconnect preserves existing provider identity and model`() = runTest(dispatcher) {
        providers.value = listOf(ProviderConfig("existing", "My ChatGPT", baseUrl = "https://chatgpt.com/backend-api/codex",
            modelId = "my-model", providerType = ProviderType.CODEX_CHATGPT, authMode = AuthMode.OAUTH_DEVICE))
        advanceUntilIdle()
        viewModel.loginWithChatGpt()
        advanceUntilIdle()
        coVerify { repository.connectChatGpt(match { it.id == "existing" && it.modelId == "my-model" }, tokens) }
    }

    @Test fun `failed login can retry without creating a provider first`() = runTest(dispatcher) {
        coEvery { auth.startDeviceLogin(any()) } throws IllegalStateException("Offline")
        advanceUntilIdle()
        viewModel.loginWithChatGpt()
        advanceUntilIdle()
        assertEquals("Offline", viewModel.uiState.value.deviceLogin?.error)
        coVerify(exactly = 0) { repository.connectChatGpt(any(), any()) }
        coEvery { auth.startDeviceLogin(any()) } returns session
        viewModel.retryDeviceLogin()
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.connectChatGpt(any(), tokens) }
        assertNull(viewModel.uiState.value.deviceLogin)
    }

    @Test fun `cancel stops polling and double taps start only one login`() = runTest(dispatcher) {
        coEvery { auth.awaitDeviceLogin(session, any()) } coAnswers { awaitCancellation() }
        advanceUntilIdle()
        viewModel.loginWithChatGpt()
        viewModel.loginWithChatGpt()
        advanceUntilIdle()
        assertEquals("CODE-1234", viewModel.uiState.value.deviceLogin?.userCode)
        viewModel.cancelDeviceLogin()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.deviceLogin)
        coVerify(exactly = 1) { auth.startDeviceLogin(any()) }
        coVerify(exactly = 0) { repository.connectChatGpt(any(), any()) }
    }

    @Test fun `temporary network failures keep the device code visible and login active`() = runTest(dispatcher) {
        coEvery { auth.awaitDeviceLogin(session, any()) } coAnswers {
            secondArg<(String?) -> Unit>().invoke("Waiting for connection")
            awaitCancellation()
        }
        advanceUntilIdle()
        viewModel.loginWithChatGpt()
        advanceUntilIdle()
        val state = viewModel.uiState.value.deviceLogin!!
        assertEquals(session.userCode, state.userCode)
        assertEquals(session.expiresAtEpochMs, state.expiresAtEpochMs)
        assertEquals("Waiting for connection", state.networkStatus)
        assertTrue(state.busy)
        assertNull(state.error)
        viewModel.cancelDeviceLogin()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.deviceLogin)
    }

    @Test fun `sign out removes provider and chooses the remaining provider`() = runTest(dispatcher) {
        providers.value = listOf(
            ProviderConfig("chatgpt", "ChatGPT", baseUrl = "https://chatgpt.com/backend-api/codex",
                modelId = "model", providerType = ProviderType.CODEX_CHATGPT),
            ProviderConfig("other", "Other", baseUrl = "https://example.com/v1",
                modelId = "other-model", providerType = ProviderType.CUSTOM_OPENAI),
        )
        activeProviderId.value = "chatgpt"
        advanceUntilIdle()
        viewModel.signOutChatGpt("chatgpt")
        advanceUntilIdle()
        coVerify { repository.saveProviders(match { it.size == 1 && it.single().id == "other" }) }
        coVerify { repository.setActiveProvider("other") }
    }
}
