package dev.ophoner.tools

import kotlinx.serialization.json.JsonObject

data class Tool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val policy: ToolPolicy = ToolPolicies.forTool(name),
)

data class ToolResult(
    val toolUseId: String,
    val output: String,
    val isError: Boolean = false,
    val images: List<ToolImage> = emptyList(),
)

data class ToolImage(val mediaType: String, val base64: String)

interface ToolExecutor {
    val definition: Tool
    suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult
}

class ToolRegistry(
    executors: Map<String, ToolExecutor>,
    private val dynamicExecutors: () -> List<ToolExecutor> = { emptyList() },
    private val initializeDynamic: suspend () -> Unit = {},
) {
    private val builtIns = executors.toSortedMap().toMap()
    private val builtInDefinitions = builtIns.values.map { it.definition }

    suspend fun initialize() = initializeDynamic()

    /** Capture once per model turn and use this same catalog for both schemas and dispatch. */
    fun snapshot(): ToolRegistry = ToolRegistry(catalog())

    private fun catalog(): Map<String, ToolExecutor> {
        val dynamic = dynamicExecutors()
        if (dynamic.isEmpty()) return builtIns
        val catalog = builtIns.toMutableMap()
        for (executor in dynamic) {
            val name = executor.definition.name
            require(name !in catalog) { "Duplicate tool name: $name" }
            catalog[name] = executor
        }
        return catalog.toSortedMap().toMap()
    }

    fun allTools(): List<Tool> {
        val catalog = catalog()
        return if (catalog === builtIns) builtInDefinitions else catalog.values.map { it.definition }
    }

    fun getExecutor(name: String): ToolExecutor? = catalog()[name]

    companion object {
        fun from(
            executorSet: Set<ToolExecutor>,
            dynamicExecutors: () -> List<ToolExecutor> = { emptyList() },
            initializeDynamic: suspend () -> Unit = {},
        ): ToolRegistry {
            val executors = executorSet.associateBy { it.definition.name }
            require(executors.size == executorSet.size) { "Duplicate built-in tool names" }
            return ToolRegistry(executors, dynamicExecutors, initializeDynamic)
        }
    }
}
