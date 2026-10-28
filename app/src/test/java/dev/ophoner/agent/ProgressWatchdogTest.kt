package dev.ophoner.agent

import dev.ophoner.llm.LlmContentBlock
import dev.ophoner.tools.ToolResult
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProgressWatchdogTest {
    private fun check(watch: ProgressWatchdog, snapshot: Int, text: String = "Loading", error: Boolean = false): Boolean {
        val call = LlmContentBlock.ToolUse("c$snapshot", "screen_control", buildJsonObject { put("action", "read") })
        val output = buildJsonObject { put("snapshot_id", "$snapshot"); putJsonArray("nodes") { add(JsonPrimitive(text)) } }.toString()
        return watch.stalled(listOf(call), mapOf(call.id to ToolResult(call.id, output, error)))
    }
    @Test fun `new snapshot IDs do not conceal a stuck screen`() {
        val watch = ProgressWatchdog()
        repeat(5) { assertFalse(check(watch, it)) }
        assertTrue(check(watch, 5))
    }
    @Test fun `real progress and errors reset the unchanged-result streak`() {
        val watch = ProgressWatchdog()
        repeat(5) { assertFalse(check(watch, it)) }
        assertFalse(check(watch, 6, "Ready"))
        repeat(4) { assertFalse(check(watch, 7 + it, "Ready")) }
        assertFalse(check(watch, 12, "Ready", error = true))
        assertFalse(check(watch, 13, "Ready"))
    }
}
