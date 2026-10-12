package dev.ophoner.ui.chat

import androidx.lifecycle.ViewModelStore
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ChatGptSpeed
import dev.ophoner.data.model.ProviderType
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.llm.models.ProviderModelResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelPickerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val settings = mockk<SettingsRepository>()
    private val resolver = mockk<ProviderModelResolver>()
    private val config = ProviderConfig("go", "Go", "key", "https://opencode.ai/zen/go/v1", "old", ProviderType.OPENCODE_GO)
    private val store = ViewModelStore()
    private lateinit var viewModel: ModelPickerViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { settings.observeProviders() } returns flowOf(listOf(config))
        viewModel = ModelPickerViewModel(settings, resolver)
        store.put("picker", viewModel)
    }

    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun `automatic selection stays busy until the latest model is resolved`() = runTest(dispatcher) {
        val pending = CompletableDeferred<ProviderConfig>()
        val automatic = config.copy(useLatestModel = true)
        coEvery { settings.selectModel("go", null, false) } returns automatic
        coEvery { resolver.resolve(automatic) } coAnswers { pending.await() }
        var selected = false
        viewModel.select("go", null) { selected = true }
        runCurrent()
        assertTrue(viewModel.state.value.selecting)
        assertFalse(selected)
        pending.complete(automatic.copy(modelId = "newest"))
        advanceUntilIdle()
        assertTrue(selected)
        assertFalse(viewModel.state.value.selecting)
    }

    @Test fun `custom slug selects the provider without invoking automatic selection`() = runTest(dispatcher) {
        coEvery { settings.selectModel("go", "my-model", true) } returns config.copy(modelId = "my-model")
        viewModel.select("go", "my-model", true) {}
        advanceUntilIdle()
        coVerify { settings.selectModel("go", "my-model", true) }
        coVerify(exactly = 0) { resolver.resolve(any()) }
        assertNull(viewModel.state.value.selectionError)
    }

    @Test fun `speed waits for persistence and does not change model or refetch catalog`() = runTest(dispatcher) {
        val pending = CompletableDeferred<Unit>()
        coEvery { settings.setChatGptSpeed("chatgpt", ChatGptSpeed.FAST) } coAnswers { pending.await() }
        var selected = false
        viewModel.selectSpeed("chatgpt", ChatGptSpeed.FAST) { selected = true }
        runCurrent()
        assertTrue(viewModel.state.value.selecting)
        assertFalse(selected)
        pending.complete(Unit)
        advanceUntilIdle()
        assertTrue(selected)
        assertFalse(viewModel.state.value.selecting)
        coVerify(exactly = 0) { resolver.resolve(any()) }
        coVerify(exactly = 0) { settings.selectModel(any(), any(), any()) }
    }

    @Test fun `speed persistence failure stays visible and releases busy state`() = runTest(dispatcher) {
        coEvery { settings.setChatGptSpeed("chatgpt", ChatGptSpeed.FAST) } throws IllegalStateException("Provider removed")
        var selected = false
        viewModel.selectSpeed("chatgpt", ChatGptSpeed.FAST) { selected = true }
        advanceUntilIdle()
        assertFalse(selected)
        assertEquals("Provider removed", viewModel.state.value.selectionError)
        assertFalse(viewModel.state.value.selecting)
    }

    @Test fun `removed provider reports an error and releases the busy state`() = runTest(dispatcher) {
        coEvery { settings.selectModel("go", null, false) } throws IllegalStateException("Provider removed")
        viewModel.select("go", null) {}
        advanceUntilIdle()
        assertEquals("Provider removed", viewModel.state.value.selectionError)
        assertFalse(viewModel.state.value.selecting)
    }
}
