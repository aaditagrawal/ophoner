package dev.ophoner.llm

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class LlmHistoryTest {
    private fun call(id: String) = LlmContentBlock.ToolUse(id, "read", JsonObject(emptyMap()))
    private fun result(id: String) = LlmContentBlock.ToolResult(id, "result $id", false)

    @Test fun `inline and separate results merge in call order without duplicates`() {
        val history = listOf(
            LlmMessage(LlmRole.ASSISTANT, listOf(LlmContentBlock.Text("Reading"), call("a"), call("b"), result("b"))),
            LlmMessage(LlmRole.TOOL_RESULT, listOf(result("a"), result("b"), result("orphan"))),
            LlmMessage(LlmRole.USER, listOf(LlmContentBlock.Text("continue"))),
        )
        val normalized = LlmHistory.normalize(history)
        assertEquals(3, normalized.size)
        assertEquals(listOf(result("a"), result("b")), normalized[1].content)
        assertFalse(normalized[0].content.any { it is LlmContentBlock.ToolResult })
        assertEquals(history.last(), normalized.last())
        assertEquals(normalized, LlmHistory.normalize(normalized))
    }

    @Test fun `missing results report unknown state and do not borrow from later turns`() {
        val history = listOf(
            LlmMessage(LlmRole.ASSISTANT, listOf(call("a"))),
            LlmMessage(LlmRole.USER, listOf(LlmContentBlock.Text("stop"))),
            LlmMessage(LlmRole.TOOL_RESULT, listOf(result("a"))),
        )
        val normalized = LlmHistory.normalize(history)
        val result = normalized[1].content.single() as LlmContentBlock.ToolResult
        assertTrue(result.isError)
        assertEquals(LlmHistory.UNKNOWN_OUTCOME, result.output)
        assertEquals(3, normalized.size)
        assertEquals(normalized, LlmHistory.normalize(normalized))
    }

    @Test fun `orphan results and duplicate calls never enter provider history`() {
        val history = listOf(
            LlmMessage(LlmRole.TOOL_RESULT, listOf(result("orphan"))),
            LlmMessage(LlmRole.ASSISTANT, listOf(call("a"), call("a"), result("a"))),
            LlmMessage(LlmRole.ASSISTANT, listOf(call("a"), result("a"))),
        )
        val normalized = LlmHistory.normalize(history)
        assertEquals(2, normalized.size)
        assertEquals(listOf(call("a")), normalized[0].content)
        assertEquals(listOf(result("a")), normalized[1].content)
        assertEquals(normalized, LlmHistory.normalize(normalized))
    }

    @Test fun `already valid ordinary and tool histories are unchanged`() {
        val history = listOf(
            LlmMessage(LlmRole.SYSTEM, listOf(LlmContentBlock.Text("rules"))),
            LlmMessage(LlmRole.USER, listOf(LlmContentBlock.Text("read"))),
            LlmMessage(LlmRole.ASSISTANT, listOf(call("a"))),
            LlmMessage(LlmRole.TOOL_RESULT, listOf(result("a"))),
            LlmMessage(LlmRole.ASSISTANT, listOf(LlmContentBlock.Text("done"))),
        )
        assertEquals(history, LlmHistory.normalize(history))
    }
}
