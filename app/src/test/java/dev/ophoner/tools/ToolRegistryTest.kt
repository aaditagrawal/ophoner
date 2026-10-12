package dev.ophoner.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ToolRegistryTest {
    private fun executor(name: String) = object : ToolExecutor {
        override val definition = Tool(name, "test", JsonObject(emptyMap()))
        override suspend fun execute(toolUseId: String, arguments: JsonObject) = ToolResult(toolUseId, name)
    }

    @Test fun `turn snapshot keeps schemas and dispatch stable when remote tools change`() {
        val first = executor("remote_old")
        val replacement = executor("remote_new")
        var dynamic = listOf(first)
        val registry = ToolRegistry.from(setOf(executor("builtin")), dynamicExecutors = { dynamic })
        val turn = registry.snapshot()
        dynamic = listOf(replacement)
        assertEquals(listOf("builtin", "remote_old"), turn.allTools().map { it.name })
        assertSame(first, turn.getExecutor("remote_old"))
        assertNull(turn.getExecutor("remote_new"))
        assertEquals(listOf("builtin", "remote_new"), registry.snapshot().allTools().map { it.name })
    }

    @Test fun `remote tools cannot overwrite builtin executors`() {
        val registry = ToolRegistry.from(setOf(executor("file_write")), dynamicExecutors = { listOf(executor("file_write")) })
        assertThrows(IllegalArgumentException::class.java) { registry.snapshot() }
    }

    @Test fun `initializer loads remote catalog before snapshot`() = runTest {
        var dynamic = emptyList<ToolExecutor>()
        val registry = ToolRegistry.from(emptySet(), { dynamic }, { dynamic = listOf(executor("remote_loaded")) })
        assertTrue(registry.allTools().isEmpty())
        registry.initialize()
        assertEquals("remote_loaded", registry.snapshot().allTools().single().name)
    }
}
