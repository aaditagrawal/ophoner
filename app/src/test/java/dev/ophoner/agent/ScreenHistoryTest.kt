package dev.ophoner.agent

import dev.ophoner.llm.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScreenHistoryTest {
    @Test fun `old trees are pruned while actions errors and latest evidence remain intact`() {
        val messages = (0..9).flatMap { i ->
            val tree = buildJsonObject {
                put("action", "click"); put("action_dispatched", true)
                putJsonObject("screen") {
                    put("snapshot_id", "s$i"); put("package", "example.app")
                    putJsonArray("nodes") { repeat(100) { n -> add(buildJsonObject { put("node_id", "$n"); put("text", "Control $n with explanatory text") }) } }
                }
            }.toString()
            listOf(
                LlmMessage(LlmRole.ASSISTANT, listOf(LlmContentBlock.ToolUse("c$i", "screen_control", buildJsonObject { put("action", "click") }))),
                LlmMessage(LlmRole.TOOL_RESULT, listOf(LlmContentBlock.ToolResult("c$i", tree, false))),
            )
        }
        val original = messages.map { it.copy(content = it.content.toList()) }
        val result = ScreenHistory.compact(messages)
        assertEquals(original, messages)
        assertEquals(messages.takeLast(4), result.takeLast(4))
        val old = result[1].content.single() as LlmContentBlock.ToolResult
        val root = Json.parseToJsonElement(old.output).jsonObject
        assertTrue(root["action_dispatched"]!!.jsonPrimitive.boolean)
        assertEquals("click", root["action"]!!.jsonPrimitive.content)
        assertFalse(root["screen"]!!.jsonObject.containsKey("nodes"))
        val before = ContextBudget.estimateTokens(messages, emptyList())
        val after = ContextBudget.estimateTokens(result, emptyList())
        println("SCREEN_CONTEXT_BENCHMARK ten 100-control screens: before=$before estimated tokens; after=$after")
        assertTrue(after < before / 3)
        val error = messages[1].copy(content = listOf((messages[1].content.single() as LlmContentBlock.ToolResult).copy(isError = true)))
        assertEquals(error, ScreenHistory.compact(listOf(messages.first(), error) + messages.drop(2))[1])
    }
}
