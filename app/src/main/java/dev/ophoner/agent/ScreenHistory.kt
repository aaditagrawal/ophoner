package dev.ophoner.agent

import dev.ophoner.llm.*
import kotlinx.serialization.json.*

/** Prune obsolete screen trees from request copies, never from the stored transcript. */
internal object ScreenHistory {
    fun compact(messages: List<LlmMessage>): List<LlmMessage> {
        val screenCalls = messages.flatMap { it.content }.filterIsInstance<LlmContentBlock.ToolUse>()
            .filter { it.name == "screen_control" }.map { it.id }.toSet()
        val snapshots = ArrayList<Pair<LlmContentBlock.ToolResult, JsonObject>>()
        for (message in messages) {
            for (block in message.content) {
                val result = block as? LlmContentBlock.ToolResult ?: continue
                if (result.isError || result.toolUseId !in screenCalls) continue
                val root = tree(result.output) ?: continue
                snapshots.add(result to root)
            }
        }
        val keep = snapshots.takeLast(2).map { it.first.toolUseId }.toSet()
        if (snapshots.size <= 2) return messages
        val obsolete = HashMap<String, String>(snapshots.size)
        for ((result, root) in snapshots) {
            if (result.toolUseId in keep) continue
            val screen = root["screen"] as? JsonObject
            val stripped = if (screen != null) JsonObject(root + ("screen" to withoutNodes(screen))) else withoutNodes(root)
            obsolete[result.toolUseId] = stripped.toString()
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
