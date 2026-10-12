package dev.ophoner.llm

/** Projects persisted chat cards into valid model turns. Never executes or retries historical tools. */
object LlmHistory {
    const val UNKNOWN_OUTCOME = "The execution outcome of this historical tool call is unknown because no result was recorded. " +
        "It may have already changed external state. Inspect that state before deciding whether to repeat the action."

    /**
     * Stored assistant cards may contain both calls and results. Providers require results in the
     * following tool-result turn. Only adjacent results belong to a turn; never move evidence across
     * another user/assistant turn. Each retained call gets exactly one result in call order.
     */
    fun normalize(messages: List<LlmMessage>): List<LlmMessage> {
        val normalized = mutableListOf<LlmMessage>()
        val usedCallIds = mutableSetOf<String>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index++]
            if (message.role == LlmRole.TOOL_RESULT) continue // orphan or duplicate result
            if (message.role != LlmRole.ASSISTANT) {
                val content = message.content.filterNot { it is LlmContentBlock.ToolResult || it is LlmContentBlock.ToolUse }
                if (content.isNotEmpty()) normalized.add(message.copy(content = content))
                continue
            }
            val content = message.content.filter { block ->
                when (block) {
                    is LlmContentBlock.ToolResult -> false
                    is LlmContentBlock.ToolUse -> block.id.isNotBlank() && usedCallIds.add(block.id)
                    else -> true
                }
            }
            val calls = content.filterIsInstance<LlmContentBlock.ToolUse>()
            val results = linkedMapOf<String, LlmContentBlock.ToolResult>()
            fun gather(blocks: List<LlmContentBlock>) {
                blocks.filterIsInstance<LlmContentBlock.ToolResult>().forEach { results.putIfAbsent(it.toolUseId, it) }
            }
            gather(message.content)
            while (index < messages.size && messages[index].role == LlmRole.TOOL_RESULT) {
                gather(messages[index++].content)
            }
            if (content.isNotEmpty()) normalized.add(message.copy(content = content))
            if (calls.isNotEmpty()) {
                normalized.add(LlmMessage(LlmRole.TOOL_RESULT, calls.map { call ->
                    results[call.id] ?: LlmContentBlock.ToolResult(call.id, UNKNOWN_OUTCOME, isError = true)
                }))
            }
        }
        return normalized
    }
}
