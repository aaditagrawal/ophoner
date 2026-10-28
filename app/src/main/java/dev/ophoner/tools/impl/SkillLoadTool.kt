package dev.ophoner.tools.impl

import dev.ophoner.agent.SkillLoader
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.tools.Tool
import dev.ophoner.tools.ToolExecutionContext
import dev.ophoner.tools.ToolExecutor
import dev.ophoner.tools.ToolResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

class SkillLoadTool @Inject constructor(
    private val skillLoader: SkillLoader,
    private val settings: SettingsRepository,
) : ToolExecutor {
    override val definition = Tool(
        name = "skill_load",
        description = "Load instructions for a folder skill using the exact path listed in the available skills catalog.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") { put("type", "string"); put("description", "Exact catalog path, e.g. .ophoner/skills/review.md") }
            }
            putJsonArray("required") { add(JsonPrimitive("path")) }
            put("additionalProperties", false)
        },
    )

    override suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult {
        val path = (arguments["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return ToolResult(toolUseId, "Missing string parameter: path", isError = true)
        val root = currentCoroutineContext()[ToolExecutionContext]?.rootUri
            ?: settings.observeWorkingDirectoryUri().firstOrNull()
        val skill = skillLoader.loadSkill(root, path)
            ?: return ToolResult(toolUseId, "Skill not found in the active folder catalog: $path", isError = true)
        return ToolResult(toolUseId, "Skill: ${skill.name} (${skill.path})\n${skill.content}")
    }
}
