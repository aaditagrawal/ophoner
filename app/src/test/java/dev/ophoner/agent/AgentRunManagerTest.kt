package dev.ophoner.agent

import dev.ophoner.data.model.Message
import dev.ophoner.data.model.MessageRole
import dev.ophoner.data.model.ContentBlock
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.repository.ConversationRepository
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.tools.ToolResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunManagerTest {
    @Test fun `argument deltas are batched and fully flushed before execution and persistence`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val loop = mockk<AgentLoop>()
            val repository = mockk<ConversationRepository>()
            val settings = mockk<SettingsRepository>()
            val config = mockk<ProviderConfig>()
            val saved = mutableListOf<Message>()
            coEvery { repository.getMessageCount("chat") } answers { saved.size }
            coEvery { repository.saveMessage(any()) } answers {
                val message = firstArg<Message>()
                saved.removeAll { it.id == message.id }; saved.add(message); Unit
            }
            every { settings.observeYoloMode() } returns flowOf(false)
            every { loop.run(any(), any(), any(), any(), any(), any(), any(), any()) } returns flow {
                emit(AgentEvent.ToolCallStarted("tool", "file_write"))
                emit(AgentEvent.ToolCallArgDelta("tool", "{\"text\":\""))
                repeat(10_000) { emit(AgentEvent.ToolCallArgDelta("tool", "x")) }
                emit(AgentEvent.TextDelta("hello"))
                delay(51) // Exercise the timed publication as well as the final flush.
                emit(AgentEvent.ToolCallArgDelta("unknown-id", "tail\"}"))
                emit(AgentEvent.ToolExecuting("tool", "file_write"))
                emit(AgentEvent.ToolCompleted("tool", ToolResult("tool", "saved")))
                emit(AgentEvent.IterationComplete)
                emit(AgentEvent.TextDelta("done"))
                emit(AgentEvent.Finished)
            }
            val manager = AgentRunManager(loop, repository, settings, backgroundScope, StandardTestDispatcher(testScheduler))
            val observed = mutableListOf<AgentRunState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                manager.observeRun("chat").collect { if (it != null) observed.add(it) }
            }
            manager.startRun("chat", emptyList(), config)
            // Background-scope work is deliberately excluded by advanceUntilIdle.
            advanceTimeBy(100)
            runCurrent()

            val executing = observed.first { it.activeToolCalls.any { tool -> tool.isExecuting } }
            assertEquals("{\"text\":\"" + "x".repeat(10_000) + "tail\"}", executing.activeToolCalls.single().arguments)
            assertTrue("10,000 deltas should not publish 10,000 UI states: ${observed.size}", observed.size < 20)
            assertEquals(2, saved.size)
            assertEquals("{\"text\":\"" + "x".repeat(10_000) + "tail\"}", saved[0].content.filterIsInstance<ContentBlock.ToolUse>().single().argumentsJson)
            assertEquals("done", saved[1].content.filterIsInstance<ContentBlock.Text>().single().text)
            assertFalse(manager.runs.value.getValue("chat").isRunning)
            assertEquals("", manager.runs.value.getValue("chat").streamingText)
        } finally {
            Dispatchers.resetMain()
        }
    }
    @Test fun `completed actions and unknown outcomes survive cancellation in the same iteration`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val loop = mockk<AgentLoop>()
            val repository = mockk<ConversationRepository>()
            val settings = mockk<SettingsRepository>()
            val config = mockk<ProviderConfig>()
            val rows = linkedMapOf<String, Message>()
            coEvery { repository.getMessageCount("chat") } answers { rows.size }
            coEvery { repository.saveMessage(any()) } answers {
                val message = firstArg<Message>()
                rows[message.id] = message
            }
            every { settings.observeYoloMode() } returns flowOf(false)
            var reachedSecondTool = false
            every { loop.run(any(), any(), any(), any(), any(), any(), any(), any()) } returns flow {
                for (id in listOf("first", "second")) {
                    emit(AgentEvent.ToolCallStarted(id, "file_write"))
                    emit(AgentEvent.ToolCallArgDelta(id, "{}"))
                }
                val beforeFirst = CompletableDeferred<Unit>()
                emit(AgentEvent.ToolExecuting("first", "file_write", beforeFirst))
                beforeFirst.await()
                assertTrue(rows.values.single().content.filterIsInstance<ContentBlock.ToolResult>().first().output.contains("Outcome unknown"))
                val afterFirst = CompletableDeferred<Unit>()
                emit(AgentEvent.ToolCompleted("first", ToolResult("first", "saved"), afterFirst))
                afterFirst.await()
                assertEquals("saved", rows.values.single().content.filterIsInstance<ContentBlock.ToolResult>().first().output)
                val beforeSecond = CompletableDeferred<Unit>()
                emit(AgentEvent.ToolExecuting("second", "file_write", beforeSecond))
                beforeSecond.await()
                reachedSecondTool = true
                awaitCancellation()
            }
            val manager = AgentRunManager(loop, repository, settings, backgroundScope, StandardTestDispatcher(testScheduler))
            manager.startRun("chat", emptyList(), config)
            runCurrent()
            assertTrue(reachedSecondTool)
            manager.cancelRun("chat")
            runCurrent()
            assertEquals(1, rows.size)
            val results = rows.values.single().content.filterIsInstance<ContentBlock.ToolResult>()
            assertEquals("saved", results[0].output)
            assertFalse(results[0].isError)
            assertTrue(results[1].output.contains("Outcome unknown"))
            assertTrue(results[1].isError)
            assertFalse(manager.runs.value.getValue("chat").isRunning)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `discarded provider attempt never enters persisted history`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val loop = mockk<AgentLoop>()
            val repository = mockk<ConversationRepository>()
            val settings = mockk<SettingsRepository>()
            val config = mockk<ProviderConfig>()
            val rows = mutableListOf<Message>()
            coEvery { repository.getMessageCount("chat") } returns 0
            coEvery { repository.saveMessage(any()) } answers { rows.add(firstArg()); Unit }
            every { settings.observeYoloMode() } returns flowOf(false)
            every { loop.run(any(), any(), any(), any(), any(), any(), any(), any()) } returns flow {
                emit(AgentEvent.TextDelta("failed attempt"))
                emit(AgentEvent.Usage(999, 999))
                emit(AgentEvent.ToolCallStarted("partial", "file_write"))
                emit(AgentEvent.ToolCallArgDelta("partial", "{"))
                emit(AgentEvent.ResponseDiscarded)
                emit(AgentEvent.Retrying(attempt = 1, delayMillis = 250))
                emit(AgentEvent.TextDelta("successful response"))
                emit(AgentEvent.Usage(10, null))
                emit(AgentEvent.Usage(null, 20))
                emit(AgentEvent.Finished)
            }
            val manager = AgentRunManager(loop, repository, settings, backgroundScope, StandardTestDispatcher(testScheduler))
            manager.startRun("chat", emptyList(), config)
            runCurrent()
            assertEquals(1, rows.size)
            assertEquals("successful response", rows.single().content.filterIsInstance<ContentBlock.Text>().single().text)
            assertTrue(rows.single().content.filterIsInstance<ContentBlock.ToolUse>().isEmpty())
            val stats = rows.single().content.filterIsInstance<ContentBlock.Stats>().single()
            assertEquals(10L, stats.inputTokens)
            assertEquals(20L, stats.outputTokens)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `failed write ahead checkpoint prevents execution acknowledgement`() = runTest {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any(), any()) } returns 0
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val loop = mockk<AgentLoop>()
            val repository = mockk<ConversationRepository>()
            val settings = mockk<SettingsRepository>()
            val config = mockk<ProviderConfig>()
            coEvery { repository.getMessageCount("chat") } returns 0
            coEvery { repository.saveMessage(any()) } throws IllegalStateException("disk full")
            every { settings.observeYoloMode() } returns flowOf(false)
            val ack = CompletableDeferred<Unit>()
            var executed = false
            every { loop.run(any(), any(), any(), any(), any(), any(), any(), any()) } returns flow {
                emit(AgentEvent.ToolCallStarted("tool", "file_write"))
                emit(AgentEvent.ToolCallArgDelta("tool", "{}"))
                emit(AgentEvent.ToolExecuting("tool", "file_write", ack))
                ack.await()
                executed = true
            }
            val manager = AgentRunManager(loop, repository, settings, backgroundScope, StandardTestDispatcher(testScheduler))
            manager.startRun("chat", emptyList(), config)
            runCurrent()
            assertTrue(ack.isCancelled)
            assertFalse(executed)
            assertTrue(manager.runs.value.getValue("chat").error.orEmpty().contains("disk full"))
        } finally { Dispatchers.resetMain(); unmockkStatic(android.util.Log::class) }
    }

    @Test fun `legacy stats deserialize with unknown token counts`() {
        val stats = kotlinx.serialization.json.Json.decodeFromString<ContentBlock.Stats>(
            """{"outputChars":12,"durationMs":34}""",
        )
        assertEquals(12, stats.outputChars)
        assertEquals(34L, stats.durationMs)
        assertNull(stats.inputTokens)
        assertNull(stats.outputTokens)
    }

    @Test fun `follow-ups survive a retry of a partially streamed response`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val loop = mockk<AgentLoop>()
            val repository = mockk<ConversationRepository>()
            val settings = mockk<SettingsRepository>()
            val config = mockk<ProviderConfig>()
            val rows = linkedMapOf<String, Message>()
            coEvery { repository.getMessageCount("chat") } answers { rows.size }
            coEvery { repository.saveMessage(any()) } answers { val row = firstArg<Message>(); rows[row.id] = row; Unit }
            every { settings.observeYoloMode() } returns flowOf(false)
            val retry = CompletableDeferred<Unit>()
            every { loop.run(any(), any(), any(), any(), any(), any(), any(), any()) } returns flow {
                emit(AgentEvent.TextDelta("partial response"))
                retry.await()
                emit(AgentEvent.ResponseDiscarded)
                emit(AgentEvent.TextDelta("recovered response"))
                emit(AgentEvent.Finished)
            }
            val manager = AgentRunManager(loop, repository, settings, backgroundScope, StandardTestDispatcher(testScheduler))
            manager.startRun("chat", emptyList(), config)
            runCurrent()
            val sent = async { manager.sendFollowUp("chat", "Use tomorrow") }
            runCurrent()
            assertTrue(sent.await())
            assertEquals("Use tomorrow", rows.values.single { it.role == MessageRole.USER }.content.filterIsInstance<ContentBlock.Text>().single().text)
            retry.complete(Unit)
            runCurrent()
            assertEquals("recovered response", rows.values.single { it.role == MessageRole.ASSISTANT }.content.filterIsInstance<ContentBlock.Text>().single().text)
            assertFalse(manager.isRunActive("chat"))
            assertFalse(manager.sendFollowUp("chat", "Too late"))
        } finally { Dispatchers.resetMain() }
    }

}
