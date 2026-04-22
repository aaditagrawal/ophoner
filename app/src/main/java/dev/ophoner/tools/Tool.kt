package dev.ophoner.tools

import kotlinx.serialization.json.JsonObject

data class Tool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class ToolResult(
    val toolUseId: String,
    val output: String,
    val isError: Boolean = false,
)

interface ToolExecutor {
    val definition: Tool
    suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult
}

class ToolRegistry(
    private val executors: Map<String, ToolExecutor>,
) {
    fun allTools(): List<Tool> = executors.values.map { it.definition }

    fun getExecutor(name: String): ToolExecutor? = executors[name]

    companion object {
        fun from(executorSet: Set<ToolExecutor>): ToolRegistry =
            ToolRegistry(executorSet.associateBy { it.definition.name })
    }
}
