package dev.ophoner.agent

import dev.ophoner.llm.*
import kotlinx.serialization.json.*

/** Prune obsolete screen trees from request copies, never from the stored transcript. */
internal object ScreenHistory {
    fun compact(messages: List<LlmMessage>): List<LlmMessage> {
        val screenCalls = messages.flatMap { it.content }.filterIsInstance<LlmContentBlock.ToolUse>()
            .filter { it.name == "screen_control" }.map { it.id }.toSet()
        val snapshots = messages.flatMap { it.content }.filterIsInstance<LlmContentBlock.ToolResult>()
            .filter { !it.isError && it.toolUseId in screenCalls && tree(it.output) != null }
        val keep = snapshots.takeLast(2).map { it.toolUseId }.toSet()
        if (snapshots.size <= 2) return messages
        val obsolete = snapshots.filterNot { it.toolUseId in keep }.associate { result ->
            val root = checkNotNull(tree(result.output))
            val screen = root["screen"] as? JsonObject
            val stripped = if (screen != null) JsonObject(root + ("screen" to withoutNodes(screen))) else withoutNodes(root)
            result.toolUseId to stripped.toString()
        }
        return messages.map { message -> message.copy(content = message.content.map { block ->
            if (block is LlmContentBlock.ToolResult) obsolete[block.toolUseId]?.let { block.copy(output = it) } ?: block else block
        }) }
    }

    private fun tree(output: String): JsonObject? {
        if (!output.contains("\"nodes\"")) return null
        val root = runCatching { Json.parseToJsonElement(output) as? JsonObject }.getOrNull() ?: return null
        return root.takeIf { it["nodes"] is JsonArray || (it["screen"] as? JsonObject)?.get("nodes") is JsonArray }
    }
    private fun withoutNodes(screen: JsonObject) = JsonObject(screen.filterKeys { it != "nodes" && it != "snapshot_id" } +
        ("observation" to JsonPrimitive("Older screen omitted. Use the latest returned screen; reread if you need current details.")))
}
