package dev.ophoner.ui.chat

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dev.ophoner.agent.AgentRunManager
import dev.ophoner.agent.AgentRunState
import dev.ophoner.agent.SkillLoader
import dev.ophoner.data.model.ContentBlock
import dev.ophoner.data.model.Message
import dev.ophoner.data.model.MessageRole
import dev.ophoner.data.repository.ConversationRepository
import dev.ophoner.data.repository.PendingShareText
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.llm.models.ProviderModelResolver
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatHistoryTest {
    @Test fun `reopening an old anchor loads its neighborhood rather than the entire tail`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = mockk<ConversationRepository>()
            val settings = mockk<SettingsRepository>()
            val manager = mockk<AgentRunManager>()
            val runs = MutableStateFlow<AgentRunState?>(null)
            val store = ChatViewportStore()
            val anchor = ChatViewport("message-450", 450, 32, false)
            store.put("chat", anchor)
            every { settings.observeProviders() } returns flow { awaitCancellation() }
            every { manager.observeRun("chat") } returns runs
            coEvery { repo.getConversation("chat") } returns null
            coEvery { repo.pageStart("chat", Int.MAX_VALUE) } returns 950
            coEvery { repo.pageStart("chat", 450) } returns 400
            coEvery { repo.pageEnd("chat", 450) } returns 500
            coEvery { repo.pageEnd("chat", 500) } returns 550
            coEvery { repo.hasMessagesBefore("chat", any()) } returns true
            coEvery { repo.hasMessagesAfter("chat", any()) } returns true
            every { repo.observeMessagesRange("chat", 400, 500) } returns MutableStateFlow((400..500).map(::message))
            every { repo.observeMessagesRange("chat", 400, 550) } returns MutableStateFlow((400..550).map(::message))
            every { repo.observeMessagesFrom("chat", 950) } returns MutableStateFlow((950..999).map(::message))
            val model = ChatViewModel(manager, repo, settings, mockk(), mockk(), mockk(), mockk(), store,
                SavedStateHandle(mapOf("conversationId" to "chat")))
            runCurrent()
            assertEquals(anchor, model.uiState.value.restoredViewport)
            assertEquals(101, model.uiState.value.messages.size)
            assertTrue(model.uiState.value.hasNewerMessages)
            repeat(20) {
                runs.value = AgentRunState("chat", streamingText = "token $it", isRunning = true)
                runCurrent()
            }
            coVerify(exactly = 1) { repo.hasMessagesAfter("chat", 500) }
            model.loadNewerMessages()
            runCurrent()
            assertEquals(151, model.uiState.value.messages.size)
            model.showLatestMessages()
            runCurrent()
            assertEquals(50, model.uiState.value.messages.size)
            assertFalse(model.uiState.value.hasNewerMessages)
            androidx.lifecycle.ViewModelStore().apply { put("chat", model); clear() }
            runCurrent()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `history opens independently and streaming does not recopy it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = mockk<ConversationRepository>()
            val settings = mockk<SettingsRepository>()
            val manager = mockk<AgentRunManager>()
            val runs = MutableStateFlow<AgentRunState?>(null)
            val latest = (950..999).map { message(it) }
            val older = (900..999).map { message(it) }
            every { settings.observeProviders() } returns flow { awaitCancellation() }
            every { manager.observeRun("chat") } returns runs
            coEvery { repo.getConversation("chat") } returns null
            coEvery { repo.pageStart("chat", Int.MAX_VALUE) } returns 950
            coEvery { repo.pageStart("chat", 950) } returns 900
            coEvery { repo.hasMessagesBefore("chat", any()) } returns true
            every { repo.observeMessagesFrom("chat", 950) } returns MutableStateFlow(latest)
            every { repo.observeMessagesFrom("chat", 900) } returns MutableStateFlow(older)

            val model = ChatViewModel(manager, repo, settings, mockk<PendingShareText>(), mockk<SkillLoader>(),
                mockk<ProviderModelResolver>(), mockk<Context>(), ChatViewportStore(), SavedStateHandle(mapOf("conversationId" to "chat")))
            runCurrent()
            assertTrue(model.uiState.value.historyLoaded)
            assertEquals(50, model.uiState.value.messages.size)
            assertNull(model.uiState.value.providerConfig)
            val first = model.uiState.value.messages
            repeat(20) {
                runs.value = AgentRunState("chat", streamingText = "token $it", isRunning = true)
                runCurrent()
                assertSame(first, model.uiState.value.messages)
            }
            runs.value = runs.value!!.copy(checkpointMessageId = "message-999")
            runCurrent()
            assertEquals(49, model.uiState.value.messages.size)
            runs.value = runs.value!!.copy(checkpointMessageId = null)
            runCurrent()
            assertSame(first, model.uiState.value.messages)
            model.loadOlderMessages()
            runCurrent()
            assertEquals(100, model.uiState.value.messages.size)
            assertEquals("message-900", model.uiState.value.messages.first().id)
            assertFalse(model.uiState.value.loadingOlderMessages)
            verify(exactly = 0) { repo.observeMessages(any()) }
            coVerify(exactly = 0) { repo.getMessages(any()) }
            androidx.lifecycle.ViewModelStore().apply { put("chat", model); clear() }
            runCurrent()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun message(index: Int) = Message("message-$index", "chat", MessageRole.ASSISTANT,
        listOf(ContentBlock.Text("Message $index")), index, 1)
}
