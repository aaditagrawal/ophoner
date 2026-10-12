package dev.ophoner.device

import dev.ophoner.tools.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.required(key: String): String = text(key)?.takeIf { it.isNotBlank() } ?: error("Missing '$key'")
internal fun JsonObject.number(key: String): Long = text(key)?.toLongOrNull() ?: error("'$key' must be an integer")
internal fun deviceTool(name: String, description: String, actions: List<String>, fields: Map<String, String> = emptyMap()): Tool = Tool(name, description, buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("action") { put("type", "string"); put("enum", JsonArray(actions.map(::JsonPrimitive))) }
        fields.forEach { (name, description) -> putJsonObject(name) { put("type", "string"); put("description", description) } }
    }
    putJsonArray("required") { add(JsonPrimitive("action")) }
})

abstract class DeviceTool(protected val access: DeviceAccess, private val capability: DeviceCapability?) : ToolExecutor {
    final override suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            capability?.let(access::requireEnabled)
            ToolResult(toolUseId, perform(arguments))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ToolResult(toolUseId, e.message ?: "Device action failed", true) }
    }
    protected abstract suspend fun perform(args: JsonObject): String
}

class DeviceCapabilitiesTool(access: DeviceAccess) : DeviceTool(access, null) {
    override val definition = deviceTool("device_capabilities", "Discover enabled device capabilities and missing permissions. Recheck after the user grants access. Tools cannot grant themselves access.", listOf("status"))
    override suspend fun perform(args: JsonObject): String { require(args.required("action") == "status"); return access.inventory() }
}
