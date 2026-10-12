package dev.ophoner.agent

import dev.ophoner.llm.LlmContentBlock
import dev.ophoner.tools.ToolResult
import kotlinx.serialization.json.*

/** A changing screenshot ID is not progress; changed controls or action inputs are. */
internal class ProgressWatchdog {
    private var previous: List<String>? = null
    private var repeats = 0
    fun stalled(calls: List<LlmContentBlock.ToolUse>, results: Map<String, ToolResult>): Boolean {
        if (calls.isEmpty() || calls.any { results[it.id]?.isError != false }) { previous = null; repeats = 0; return false }
        val state = calls.map { call ->
            val output = results.getValue(call.id).output
            val screen = call.name == "screen_control"
            val args = if (screen) stripSnapshotIds(call.arguments).toString() else call.arguments.toString()
            val result = if (screen) runCatching { stripSnapshotIds(Json.parseToJsonElement(output)).toString() }.getOrDefault(output) else output
            "${call.name}\n$args\n$result"
        }
        repeats = if (state == previous) repeats + 1 else 1
        previous = state
        return repeats >= 6
    }
    private fun stripSnapshotIds(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterKeys { it != "snapshot_id" }.mapValues { stripSnapshotIds(it.value) })
        is JsonArray -> JsonArray(value.map(::stripSnapshotIds))
        else -> value
    }
}
