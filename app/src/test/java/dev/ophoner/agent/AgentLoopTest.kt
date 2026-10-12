package dev.ophoner.agent

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderType
import dev.ophoner.device.ActionApproval
import dev.ophoner.device.DeviceAccess
import dev.ophoner.llm.*
import dev.ophoner.tools.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AgentLoopTest {
    private val config = ProviderConfig("test", "Test", baseUrl = "https://example.invalid", modelId = "test", providerType = ProviderType.CUSTOM_OPENAI)
    private val user = listOf(LlmMessage(LlmRole.USER, listOf(LlmContentBlock.Text("Do the task"))))
    private val schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("n") { put("type", "integer") } }
        putJsonArray("required") { add("n") }
    }

    private fun tool(name: String = "read", policy: ToolPolicy = ToolPolicy(), execute: suspend (String, JsonObject) -> ToolResult) = object : ToolExecutor {
        override val definition = Tool(name, "Test tool", schema, policy)
        override suspend fun execute(toolUseId: String, arguments: JsonObject) = execute.invoke(toolUseId, arguments)
    }

    private fun loop(tools: Set<ToolExecutor>, dynamic: () -> List<ToolExecutor> = { emptyList() }, reply: (List<LlmMessage>, List<Tool>, ProviderConfig) -> Flow<LlmResponseChunk>): AgentLoop {
        val provider = object : LlmProvider {
            override val providerId = "fake"
            override fun streamCompletion(messages: List<LlmMessage>, tools: List<Tool>, config: ProviderConfig) = reply(messages, tools, config)
        }
        val factory = mockk<LlmProviderFactory>()
        every { factory.create(any()) } returns provider
        val access = mockk<DeviceAccess>()
        every { access.inventory() } returns "test capabilities"
        val approval = mockk<ActionApproval>()
        coEvery { approval.approve(any(), any(), any()) } returns true
        return AgentLoop(factory, ToolRegistry.from(tools, dynamicExecutors = dynamic), approval, access)
    }

    private suspend fun FlowCollector<LlmResponseChunk>.call(id: String = "call", name: String = "read", args: String = "{\"n\":1}", end: Boolean = true) {
        emit(LlmResponseChunk.ToolCallStart(id, name))
        emit(LlmResponseChunk.ToolCallArgumentDelta(id, args))
        if (end) emit(LlmResponseChunk.ToolCallEnd(id))
    }
    private fun finished() = flowOf(LlmResponseChunk.TextDelta("Done"), LlmResponseChunk.Done())

    @Test fun `follow-up arriving during final response gets another model turn`() = runBlocking {
        val control = RunControl()
        val requests = AtomicInteger()
        val agent = loop(emptySet()) { messages, _, _ -> flow {
            if (requests.getAndIncrement() == 0) {
                control.submit("Use tomorrow instead") { }
                emit(LlmResponseChunk.TextDelta("First answer"))
            } else {
                assertEquals(LlmRole.USER, messages.last().role)
                assertEquals("Use tomorrow instead", (messages.last().content.single() as LlmContentBlock.Text).text)
                emit(LlmResponseChunk.TextDelta("Updated answer"))
            }
            emit(LlmResponseChunk.Done())
        } }
        val events = agent.run(user, config, control = control).toList()
        assertEquals(2, requests.get())
        assertEquals(AgentStopReason.COMPLETED, events.filterIsInstance<AgentEvent.RunStopped>().last().reason)
        assertFalse(control.submit("too late") { fail("Must not persist after completion") })
    }

    @Test fun `takeover during planning prevents stale actions from executing`() = runBlocking {
        val control = RunControl()
        val calls = AtomicInteger()
        val requests = AtomicInteger()
        val agent = loop(setOf(tool { id, _ -> calls.incrementAndGet(); ToolResult(id, "ok") })) { _, _, _ ->
            if (requests.getAndIncrement() == 0) flow {
                control.requestPause(true)
                control.requestPause(false)
                call()
                emit(LlmResponseChunk.Done("tool_calls"))
            } else finished()
        }
        val events = agent.run(user, config, control = control).toList()
        assertEquals(0, calls.get())
        assertTrue(events.filterIsInstance<AgentEvent.ToolCompleted>().single().result.output.contains("not executed"))
    }

    @Test fun `ordinary runs can execute more than forty successful steps`() = runBlocking {
        val executed = AtomicInteger()
        val agent = loop(setOf(tool { id, _ -> ToolResult(id, "step ${executed.incrementAndGet()}") })) { _, _, _ ->
            if (executed.get() < 45) flow { call(); emit(LlmResponseChunk.Done("tool_calls")) } else finished()
        }
        val events = agent.run(user, config).toList()
        assertEquals(45, executed.get())
        assertEquals(AgentStopReason.COMPLETED, events.filterIsInstance<AgentEvent.RunStopped>().last().reason)
    }

    @Test fun `returning observations with actions removes redundant model turns`() = runBlocking {
        suspend fun workflow(fused: Boolean): Int {
            var screen = 0
            var requests = 0
            val tools = setOf(
                tool("observe") { id, _ -> ToolResult(id, "screen=$screen") },
                tool("act") { id, _ -> screen++; ToolResult(id, if (fused) "screen=$screen" else "dispatched") },
            )
            val agent = loop(tools) { messages, _, _ ->
                requests++
                val observation = messages.last().content.filterIsInstance<LlmContentBlock.ToolResult>().lastOrNull()?.output
                when {
                    observation == "screen=8" -> finished()
                    observation?.startsWith("screen=") == true -> flow { call(id = "call-$requests", name = "act"); emit(LlmResponseChunk.Done("tool_calls")) }
                    else -> flow { call(id = "call-$requests", name = "observe"); emit(LlmResponseChunk.Done("tool_calls")) }
                }
            }
            agent.run(user, config).toList()
            assertEquals(8, screen)
            return requests
        }
        val baseline = workflow(false)
        val fused = workflow(true)
        println("PHONE_LOOP_BENCHMARK eight actions: baseline=$baseline model requests; fused=$fused")
        assertEquals(18, baseline)
        assertEquals(10, fused)
    }

    @Test fun `malformed JSON never gets repaired or executed`() = runBlocking {
        val executed = AtomicInteger()
        val agent = loop(setOf(tool { id, _ -> executed.incrementAndGet(); ToolResult(id, "ok") })) { _, _, _ -> flow {
            call(args = "{\"n\":1")
            emit(LlmResponseChunk.Done("tool_calls"))
        } }
        val events = agent.run(user, config).toList()
        assertEquals(0, executed.get())
        assertEquals(AgentStopReason.ERROR, events.filterIsInstance<AgentEvent.RunStopped>().single().reason)
        assertTrue(events.any { it is AgentEvent.ResponseDiscarded })
    }

    @Test fun `unfinished tool is rejected even with a completed response`() = runBlocking {
        val executed = AtomicInteger()
        val agent = loop(setOf(tool { id, _ -> executed.incrementAndGet(); ToolResult(id, "ok") })) { _, _, _ -> flow {
            call(end = false)
            emit(LlmResponseChunk.Done("tool_calls"))
        } }
        val events = agent.run(user, config).toList()
        assertEquals(0, executed.get())
        assertEquals(AgentStopReason.ERROR, events.filterIsInstance<AgentEvent.RunStopped>().single().reason)
    }

    @Test fun `schema failure returns a result for model correction without executing`() = runBlocking {
        val executed = AtomicInteger()
        val requests = AtomicInteger()
        val agent = loop(setOf(tool { id, _ -> executed.incrementAndGet(); ToolResult(id, "ok") })) { messages, _, _ ->
            if (requests.getAndIncrement() == 0) flow { call(args = "{\"n\":\"wrong\"}"); emit(LlmResponseChunk.Done("tool_calls")) }
            else {
                val result = messages.last().content.single() as LlmContentBlock.ToolResult
                assertTrue(result.isError)
                assertTrue(result.output.contains("integer"))
                finished()
            }
        }
        agent.run(user, config).toList()
        assertEquals(0, executed.get())
        assertEquals(2, requests.get())
    }

    @Test fun `retry discards partial tool calls and executes successful response once`() = runBlocking {
        val requests = AtomicInteger()
        val executed = AtomicInteger()
        val agent = loop(setOf(tool { id, _ -> executed.incrementAndGet(); ToolResult(id, "ok") })) { _, _, _ ->
            when (requests.getAndIncrement()) {
                0 -> flow { call(); emit(LlmResponseChunk.Error("offline", LlmErrorKind.NETWORK, true, 0)) }
                1 -> flow { call(); emit(LlmResponseChunk.Done("tool_calls")) }
                else -> finished()
            }
        }
        val events = agent.run(user, config).toList()
        assertEquals(1, executed.get())
        assertEquals(1, events.filterIsInstance<AgentEvent.ResponseDiscarded>().size)
        assertEquals(1, events.filterIsInstance<AgentEvent.Retrying>().size)
        assertEquals(AgentStopReason.COMPLETED, events.filterIsInstance<AgentEvent.RunStopped>().single().reason)
    }

    @Test fun `parallel reads are bounded and context results retain source order`() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val requests = AtomicInteger()
        val agent = loop(setOf(tool(policy = ToolPolicy(parallelSafe = true)) { id, args ->
            val concurrent = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, concurrent) }
            try { delay(15L * (4 - args.getValue("n").jsonPrimitive.int % 4)); ToolResult(id, id) }
            finally { active.decrementAndGet() }
        })) { messages, _, _ ->
            if (requests.getAndIncrement() == 0) flow {
                repeat(12) { call(id = "call$it", args = "{\"n\":$it}") }
                emit(LlmResponseChunk.Done("tool_calls"))
            } else {
                assertEquals((0..11).map { "call$it" }, messages.last().content.filterIsInstance<LlmContentBlock.ToolResult>().map { it.toolUseId })
                finished()
            }
        }
        withTimeout(5000) { agent.run(user, config).toList() }
        assertTrue(peak.get() > 1)
        assertTrue(peak.get() <= 4)
    }

    @Test fun `fast read result publishes before slow read finishes`() = runBlocking {
        val releaseSlow = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val agent = loop(setOf(tool(policy = ToolPolicy(parallelSafe = true)) { id, _ ->
            if (id == "slow") releaseSlow.await()
            ToolResult(id, id)
        })) { _, _, _ ->
            if (requests.getAndIncrement() == 0) flow { call("slow"); call("fast"); emit(LlmResponseChunk.Done("tool_calls")) }
            else finished()
        }
        withTimeout(3000) { agent.run(user, config).collect { if (it is AgentEvent.ToolCompleted && it.id == "fast") releaseSlow.complete(Unit) } }
        assertTrue(releaseSlow.isCompleted)
    }

    @Test fun `tool execution waits for durable checkpoint acknowledgment`() = runBlocking {
        val executed = AtomicInteger()
        val requests = AtomicInteger()
        val agent = loop(setOf(tool { id, _ -> executed.incrementAndGet(); ToolResult(id, "ok") })) { _, _, _ ->
            if (requests.getAndIncrement() == 0) flow { call(); emit(LlmResponseChunk.Done("tool_calls")) } else finished()
        }
        withTimeout(3000) { agent.run(user, config, requireDurableCheckpoints = true).collect { event ->
            when (event) {
                is AgentEvent.ToolExecuting -> {
                    delay(20)
                    assertEquals(0, executed.get())
                    event.acknowledged!!.complete(Unit)
                }
                is AgentEvent.ToolCompleted -> {
                    assertEquals(1, requests.get())
                    event.acknowledged!!.complete(Unit)
                }
                else -> Unit
            }
        } }
        assertEquals(1, executed.get())
    }

    @Test fun `screen ownership spans model turns and releases at completion`() = runBlocking {
        val firstFollowup = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondExecuted = CompletableDeferred<Unit>()
        val firstRequests = AtomicInteger()
        val secondRequests = AtomicInteger()
        val agent = loop(setOf(tool(policy = ToolPolicy(ownsPhoneUi = true)) { id, args ->
            if (args.getValue("n").jsonPrimitive.int == 2) secondExecuted.complete(Unit)
            ToolResult(id, "ok")
        })) { _, _, cfg ->
            val first = cfg.modelId == "first"
            val count = if (first) firstRequests else secondRequests
            if (count.getAndIncrement() == 0) flow { call(args = if (first) "{\"n\":1}" else "{\"n\":2}"); emit(LlmResponseChunk.Done("tool_calls")) }
            else flow {
                if (first) { firstFollowup.complete(Unit); releaseFirst.await() }
                emit(LlmResponseChunk.TextDelta("Done")); emit(LlmResponseChunk.Done())
            }
        }
        withTimeout(5000) {
            val a = launch { agent.run(user, config.copy(modelId = "first")).toList() }
            firstFollowup.await()
            val b = launch { agent.run(user, config.copy(modelId = "second")).toList() }
            delay(75)
            assertFalse(secondExecuted.isCompleted)
            releaseFirst.complete(Unit)
            joinAll(a, b)
            assertTrue(secondExecuted.isCompleted)
        }
    }

    @Test fun `tool timeout is an explicit unknown outcome`() = runBlocking {
        val requests = AtomicInteger()
        val agent = loop(setOf(tool(policy = ToolPolicy(timeoutMillis = 20)) { _, _ -> awaitCancellation() })) { _, _, _ ->
            if (requests.getAndIncrement() == 0) flow { call(); emit(LlmResponseChunk.Done("tool_calls")) } else finished()
        }
        val events = withTimeout(3000) { agent.run(user, config).toList() }
        val result = events.filterIsInstance<AgentEvent.ToolCompleted>().single().result
        assertTrue(result.isError)
        assertTrue(result.output.contains("unknown"))
    }

    @Test fun `repeated identical failures stop before iteration budget`() = runBlocking {
        val agent = loop(setOf(tool { id, _ -> ToolResult(id, "no permission", true) })) { _, _, _ -> flow {
            call(); emit(LlmResponseChunk.Done("tool_calls"))
        } }
        val events = agent.run(user, config).toList()
        assertEquals(3, events.filterIsInstance<AgentEvent.ToolCompleted>().size)
        assertEquals(AgentStopReason.STALLED, events.filterIsInstance<AgentEvent.RunStopped>().single().reason)
    }

    @Test fun `iteration exhaustion is not successful completion`() = runBlocking {
        val agent = loop(setOf(tool { id, _ -> ToolResult(id, "ok") })) { _, _, _ -> flow {
            call(); emit(LlmResponseChunk.Done("tool_calls"))
        } }
        val events = agent.run(user, config, maxIterations = 1).toList()
        assertEquals(AgentStopReason.BUDGET_EXHAUSTED, events.filterIsInstance<AgentEvent.RunStopped>().single().reason)
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolCompleted>().size)
    }

    @Test fun `oversized mandatory instructions stop before provider request`() = runBlocking {
        val agent = loop(emptySet()) { _, _, _ -> error("Must not call provider") }
        val messages = listOf(LlmMessage(LlmRole.USER, listOf(LlmContentBlock.Text("x".repeat(100_000)))))
        val events = agent.run(messages, config.copy(contextWindowTokens = 8192)).toList()
        assertEquals(AgentStopReason.CONTEXT_LIMIT, events.filterIsInstance<AgentEvent.RunStopped>().single().reason)
    }

    @Test fun `tools registered by a tool appear on the next model turn`() = runBlocking {
        val remoteCalls = AtomicInteger()
        val remote = tool(name = "mcp_example_read") { id, _ -> remoteCalls.incrementAndGet(); ToolResult(id, "remote result") }
        var dynamic: List<ToolExecutor> = emptyList()
        val install = tool(name = "install") { id, _ -> dynamic = listOf(remote); ToolResult(id, "registered") }
        val requests = AtomicInteger()
        val agent = loop(setOf(install), dynamic = { dynamic }) { _, catalog, _ ->
            when (requests.getAndIncrement()) {
                0 -> flow {
                    assertFalse(catalog.any { it.name == remote.definition.name })
                    call(name = "install"); emit(LlmResponseChunk.Done("tool_calls"))
                }
                1 -> flow {
                    assertTrue(catalog.any { it.name == remote.definition.name })
                    call(name = remote.definition.name); emit(LlmResponseChunk.Done("tool_calls"))
                }
                else -> finished()
            }
        }
        agent.run(user, config).toList()
        assertEquals(1, remoteCalls.get())
    }

    @Test fun `saved inline results are projected before first request`(): Unit = runBlocking {
        val saved = listOf(
            LlmMessage(LlmRole.ASSISTANT, listOf(
                LlmContentBlock.ToolUse("saved", "read", buildJsonObject { put("n", 1) }),
                LlmContentBlock.ToolResult("saved", "already done", false),
            )),
        ) + user
        val agent = loop(emptySet()) { messages, _, _ ->
            assertEquals("already done", messages.flatMap { it.content }.filterIsInstance<LlmContentBlock.ToolResult>().single().output)
            assertTrue(messages.any { it.role == LlmRole.TOOL_RESULT })
            finished()
        }
        agent.run(saved, config).toList()
    }

    @Test fun `provider context overflow triggers one smaller request`() = runBlocking {
        val saved = listOf(
            LlmMessage(LlmRole.USER, listOf(LlmContentBlock.Text("Read"))),
            LlmMessage(LlmRole.ASSISTANT, listOf(LlmContentBlock.ToolUse("saved", "read", buildJsonObject { put("n", 1) }))),
            LlmMessage(LlmRole.TOOL_RESULT, listOf(LlmContentBlock.ToolResult("saved", "x".repeat(30_000), false))),
        )
        val requests = AtomicInteger()
        val agent = loop(emptySet()) { messages, _, _ ->
            if (requests.getAndIncrement() == 0) flowOf(LlmResponseChunk.Error("too big", LlmErrorKind.CONTEXT_LIMIT))
            else {
                assertTrue(messages.flatMap { it.content }.filterIsInstance<LlmContentBlock.ToolResult>().single().output.length < 30_000)
                finished()
            }
        }
        val events = agent.run(saved, config).toList()
        assertEquals(2, requests.get())
        assertEquals(AgentStopReason.COMPLETED, events.filterIsInstance<AgentEvent.RunStopped>().single().reason)
    }

    @Test fun `summaries use no tools and retain user constraints`() = runBlocking {
        val dummy = object : ToolExecutor {
            override val definition = Tool("unused", "description ".repeat(550), schema)
            override suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult = error("Must not execute")
        }
        val messages = listOf(
            LlmMessage(LlmRole.USER, listOf(LlmContentBlock.Text("Never send a message"))),
            LlmMessage(LlmRole.ASSISTANT, listOf(LlmContentBlock.Text("x".repeat(12_000)))),
        ) + user
        val summaries = AtomicInteger()
        val agent = loop(setOf(dummy)) { context, tools, cfg ->
            if (tools.isEmpty()) {
                summaries.incrementAndGet()
                assertEquals(1024, cfg.maxOutputTokens)
                flowOf(LlmResponseChunk.TextDelta("Earlier investigation completed. No messages sent."), LlmResponseChunk.Done())
            } else {
                assertTrue(context.any { it.role == LlmRole.USER && it.content.contains(LlmContentBlock.Text("Never send a message")) })
                assertTrue(context.flatMap { it.content }.filterIsInstance<LlmContentBlock.Text>().any { it.text.contains("Earlier investigation completed") })
                finished()
            }
        }
        val events = agent.run(messages, config.copy(contextWindowTokens = 8192, maxOutputTokens = 1024)).toList()
        assertEquals(1, summaries.get())
        assertEquals(AgentStopReason.COMPLETED, events.filterIsInstance<AgentEvent.RunStopped>().single().reason)
    }
}
