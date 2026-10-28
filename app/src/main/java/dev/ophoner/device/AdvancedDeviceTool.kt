package dev.ophoner.device

import dev.ophoner.tools.sandbox.SandboxedShell
import kotlinx.serialization.json.JsonObject

internal fun shellArgument(value: String): String = "'" + value.replace("'", "'\\''") + "'"
internal fun checkedPackage(value: String): String {
    require(value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "Invalid package name" }
    return value
}

class AdvancedDeviceTool(access: DeviceAccess, private val shell: SandboxedShell) : DeviceTool(access, DeviceCapability.SHIZUKU) {
    override val definition = deviceTool("advanced_device", "Non-root Shizuku device actions. Availability varies by Android/OEM; inspect returned status. Use get_setting before set_setting. Package operations affect the specified package only.",
        listOf("identity", "wifi", "bluetooth", "get_setting", "set_setting", "list_packages", "force_stop", "enable_app", "disable_app", "foreground_app", "key", "diagnostics"),
        mapOf("value" to "on/off for radios, setting value, or key: back/home/recents/volume_up/volume_down", "namespace" to "system, secure, or global", "name" to "Setting key", "package" to "Exact installed package name", "service" to "battery, power, connectivity, wifi, or audio"))
    override suspend fun perform(args: JsonObject): String {
        val action = args.required("action")
        val command = when (action) {
            "identity" -> "id"
            "wifi", "bluetooth" -> {
                val value = args.required("value"); require(value in setOf("on", "off"))
                if (action == "wifi") "svc wifi ${if (value == "on") "enable" else "disable"}; cmd wifi status"
                else "cmd bluetooth_manager ${if (value == "on") "enable" else "disable"}"
            }
            "get_setting", "set_setting" -> {
                val namespace = args.required("namespace"); require(namespace in setOf("system", "secure", "global"))
                val name = args.required("name"); require(name.matches(Regex("[a-zA-Z0-9_]+")))
                // These grants must remain a user-controlled setup flow.
                require(name !in setOf("enabled_accessibility_services", "accessibility_enabled", "enabled_notification_listeners", "adb_enabled", "adb_wifi_enabled")) { "Change access grants through Android settings" }
                val get = "settings get $namespace ${shellArgument(name)}"
                if (action == "get_setting") get else "settings put $namespace ${shellArgument(name)} ${shellArgument(args.required("value"))} && $get"
            }
            "list_packages" -> "pm list packages"
            "foreground_app" -> "dumpsys activity activities"
            "force_stop", "enable_app", "disable_app" -> {
                val pkg = checkedPackage(args.required("package"))
                require(pkg != access.context.packageName && pkg != "moe.shizuku.privileged.api") { "Cannot disable or stop Ophoner or Shizuku" }
                when (action) { "force_stop" -> "am force-stop ${shellArgument(pkg)}"; "enable_app" -> "pm enable ${shellArgument(pkg)}"; else -> "pm disable-user --user current ${shellArgument(pkg)}" }
            }
            "key" -> "input keyevent " + (mapOf("back" to 4, "home" to 3, "recents" to 187, "volume_up" to 24, "volume_down" to 25)[args.required("value")] ?: error("Unsupported key"))
            "diagnostics" -> "dumpsys " + args.required("service").also { require(it in setOf("battery", "power", "connectivity", "wifi", "audio")) }
            else -> error("Unknown action")
        }
        val result = shell.executePrivileged(command)
        check(result.exitCode == 0) { "Android rejected action (${result.exitCode}): ${result.stderr}\n${result.stdout}" }
        return result.stdout.ifBlank { "Command completed. Verify state with a read action." } + result.stderr.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
    }
}
