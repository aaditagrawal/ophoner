package dev.ophoner.tools.impl

import dev.ophoner.mcp.McpManager
import dev.ophoner.tools.Tool
import dev.ophoner.tools.ToolExecutionContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class McpManageToolTest {
    @Test fun `unapproved add never contacts manager`() = runTest {
        val manager = mockk<McpManager>()
        val tool = McpManageTool(manager)
        val result = tool.execute("id", buildJsonObject {
            put("action", "add"); put("name", "server"); put("url", "https://example.com/mcp")
        })
        assertTrue(result.isError)
        coVerify(exactly = 0) { manager.initialize() }
        coVerify(exactly = 0) { manager.add(any(), any()) }
    }

    @Test fun `approved remove reaches manager and reports result`() = runTest {
        val manager = mockk<McpManager>()
        coEvery { manager.initialize() } returns Unit
        coEvery { manager.remove("server") } returns true
        val result = withContext(ToolExecutionContext(actionApproved = true)) {
            McpManageTool(manager).execute("id", buildJsonObject { put("action", "remove"); put("name", "server") })
        }
        assertFalse(result.isError)
        assertTrue(result.output.contains("Removed"))
        coVerify(exactly = 1) { manager.remove("server") }
    }

    @Test fun `list is read only and does not require approval`() = runTest {
        val manager = mockk<McpManager>()
        coEvery { manager.initialize() } returns Unit
        every { manager.servers() } returns emptyList()
        val tool = McpManageTool(manager)
        val args = buildJsonObject { put("action", "list") }
        assertFalse(tool.definition.policy.needsApproval(args))
        assertTrue(tool.definition.policy.needsApproval(buildJsonObject { put("action", "refresh") }))
        assertEquals("No MCP servers configured.", tool.execute("id", args).output)
    }
    @Test fun `selection updates active schemas without approval`() = runTest {
        val manager = mockk<McpManager>()
        coEvery { manager.initialize() } returns Unit
        coEvery { manager.selectTools(listOf("mcp_server_read")) } returns listOf(
            Tool("mcp_server_read", "Read", JsonObject(emptyMap())),
        )
        val tool = McpManageTool(manager)
        val args = buildJsonObject {
            put("action", "select")
            putJsonArray("names") { add(JsonPrimitive("mcp_server_read")) }
        }
        assertFalse(tool.definition.policy.needsApproval(args))
        val result = tool.execute("id", args)
        assertFalse(result.isError)
        assertTrue(result.output.contains("mcp_server_read"))
        coVerify(exactly = 1) { manager.selectTools(listOf("mcp_server_read")) }
    }

    @Test fun `catalog output is bounded with an explicit truncation notice and supports filtering`() = runTest {
        val manager = mockk<McpManager>()
        coEvery { manager.initialize() } returns Unit
        every { manager.toolCatalog() } returns (0 until 200).map {
            Tool("mcp_server_tool_$it", "description ".repeat(30), JsonObject(emptyMap()))
        }
        val tool = McpManageTool(manager)
        val args = buildJsonObject { put("action", "tools") }
        assertFalse(tool.definition.policy.needsApproval(args))
        val all = tool.execute("id", args)
        assertFalse(all.isError)
        assertTrue(all.output.length <= 12_000)
        assertTrue(all.output.contains("of 200 matching tools"))
        assertTrue(all.output.contains("Narrow query"))
        val filtered = tool.execute("id", buildJsonObject { put("action", "tools"); put("query", "tool_199") })
        assertTrue(filtered.output.startsWith("mcp_server_tool_199:"))
        assertFalse(filtered.output.contains("Narrow query"))
    }

}
