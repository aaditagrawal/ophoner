package dev.ophoner.tools.impl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import dev.ophoner.tools.Tool
import dev.ophoner.tools.ToolExecutor
import dev.ophoner.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

class DeviceControlTool @Inject constructor(
    private val context: Context,
) : ToolExecutor {

    override val definition = Tool(
        name = "device_control",
        description = buildString {
            append("Control device hardware and settings using Android APIs. ")
            append("Supported actions: get_brightness, set_brightness, get_volume, set_volume, ")
            append("toggle_wifi, toggle_bluetooth, get_battery, get_clipboard, set_clipboard, ")
            append("vibrate, get_device_info, toggle_flashlight, get_network_info.")
        },
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    put(
                        "description",
                        "The action to perform: get_brightness, set_brightness, get_volume, set_volume, " +
                            "toggle_wifi, toggle_bluetooth, get_battery, get_clipboard, set_clipboard, " +
                            "vibrate, get_device_info, toggle_flashlight, get_network_info",
                    )
                    putJsonArray("enum") {
                        add(JsonPrimitive("get_brightness"))
                        add(JsonPrimitive("set_brightness"))
                        add(JsonPrimitive("get_volume"))
                        add(JsonPrimitive("set_volume"))
                        add(JsonPrimitive("toggle_wifi"))
                        add(JsonPrimitive("toggle_bluetooth"))
                        add(JsonPrimitive("get_battery"))
                        add(JsonPrimitive("get_clipboard"))
                        add(JsonPrimitive("set_clipboard"))
                        add(JsonPrimitive("vibrate"))
                        add(JsonPrimitive("get_device_info"))
                        add(JsonPrimitive("toggle_flashlight"))
                        add(JsonPrimitive("get_network_info"))
                    }
                }
                putJsonObject("value") {
                    put("type", "string")
                    put(
                        "description",
                        "Value for the action. For set_brightness: 0-255. For set_volume: 0-100 (percentage). " +
                            "For toggle_wifi/toggle_bluetooth/toggle_flashlight: 'on' or 'off'. " +
                            "For set_clipboard: the text to copy. For vibrate: duration in ms (default 200).",
                    )
                }
                putJsonObject("stream") {
                    put("type", "string")
                    put("description", "Audio stream for get_volume/set_volume: 'media', 'ring', or 'alarm'. Default: 'media'.")
                    putJsonArray("enum") {
                        add(JsonPrimitive("media"))
                        add(JsonPrimitive("ring"))
                        add(JsonPrimitive("alarm"))
                    }
                }
            }
            putJsonArray("required") { add(JsonPrimitive("action")) }
        },
    )

    override suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult {
        val action = arguments["action"]?.jsonPrimitive?.content
            ?: return ToolResult(toolUseId, "Missing required parameter: action", isError = true)
        val value = arguments["value"]?.jsonPrimitive?.content
        val stream = arguments["stream"]?.jsonPrimitive?.content ?: "media"

        return try {
            val output = when (action) {
                "get_brightness" -> getBrightness()
                "set_brightness" -> setBrightness(value)
                "get_volume" -> getVolume(stream)
                "set_volume" -> setVolume(value, stream)
                "toggle_wifi" -> toggleWifi(value)
                "toggle_bluetooth" -> toggleBluetooth(value)
                "get_battery" -> getBattery()
                "get_clipboard" -> getClipboard()
                "set_clipboard" -> setClipboard(value)
                "vibrate" -> vibrate(value)
                "get_device_info" -> getDeviceInfo()
                "toggle_flashlight" -> toggleFlashlight(value)
                "get_network_info" -> getNetworkInfo()
                else -> return ToolResult(toolUseId, "Unknown action: $action", isError = true)
            }
            ToolResult(toolUseId, output)
        } catch (e: Exception) {
            ToolResult(toolUseId, "Error performing $action: ${e.message}", isError = true)
        }
    }

    private fun getBrightness(): String {
        return try {
            val brightness = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
            )
            "Screen brightness: $brightness/255"
        } catch (e: Settings.SettingNotFoundException) {
            "Could not read screen brightness setting."
        }
    }

    private fun setBrightness(value: String?): String {
        if (value == null) return "Missing 'value' parameter. Provide brightness 0-255."
        val brightness = value.toIntOrNull()
            ?: return "Invalid brightness value: '$value'. Must be an integer 0-255."
        if (brightness !in 0..255) return "Brightness must be between 0 and 255, got $brightness."

        if (!Settings.System.canWrite(context)) {
            return "Cannot modify system settings. The user must grant WRITE_SETTINGS permission: " +
                "go to Settings > Apps > Ophoner > Modify system settings and enable it."
        }
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
        return "Screen brightness set to $brightness/255."
    }

    private fun getVolume(stream: String): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamType = resolveStream(stream) ?: return "Unknown stream: '$stream'. Use 'media', 'ring', or 'alarm'."
        val current = audioManager.getStreamVolume(streamType)
        val max = audioManager.getStreamMaxVolume(streamType)
        val pct = if (max > 0) (current * 100) / max else 0
        return "Volume ($stream): $current/$max ($pct%)"
    }

    private fun setVolume(value: String?, stream: String): String {
        if (value == null) return "Missing 'value' parameter. Provide volume 0-100 (percentage)."
        val pct = value.toIntOrNull()
            ?: return "Invalid volume value: '$value'. Must be an integer 0-100."
        if (pct !in 0..100) return "Volume percentage must be between 0 and 100, got $pct."

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamType = resolveStream(stream) ?: return "Unknown stream: '$stream'. Use 'media', 'ring', or 'alarm'."
        val max = audioManager.getStreamMaxVolume(streamType)
        val target = (pct * max) / 100
        audioManager.setStreamVolume(streamType, target, 0)
        return "Volume ($stream) set to $target/$max ($pct%)."
    }

    private fun resolveStream(stream: String): Int? = when (stream) {
        "media" -> AudioManager.STREAM_MUSIC
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun toggleWifi(value: String?): String {
        if (value == null) return "Missing 'value' parameter. Use 'on' or 'off'."
        if (value !in listOf("on", "off")) return "Invalid value: '$value'. Use 'on' or 'off'."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return "Direct WiFi toggling is not supported on Android 10+. " +
                "The user can open WiFi settings via Settings > Network & Internet > WiFi."
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiManager.isWifiEnabled = (value == "on")
        return "WiFi turned $value."
    }

    @SuppressLint("MissingPermission")
    private fun toggleBluetooth(value: String?): String {
        if (value == null) return "Missing 'value' parameter. Use 'on' or 'off'."
        if (value !in listOf("on", "off")) return "Invalid value: '$value'. Use 'on' or 'off'."

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return "Bluetooth service not available on this device."
        val adapter = bluetoothManager.adapter
            ?: return "No Bluetooth adapter found on this device."

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "Direct Bluetooth toggling is not supported on Android 13+. " +
                "The user can toggle Bluetooth via Settings > Connected devices."
        } else {
            try {
                @Suppress("DEPRECATION")
                if (value == "on") adapter.enable() else adapter.disable()
                "Bluetooth turned $value."
            } catch (e: SecurityException) {
                "Bluetooth permission denied. The app needs BLUETOOTH_ADMIN permission to toggle Bluetooth."
            }
        }
    }

    private fun getBattery(): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = batteryManager.isCharging
        val status = if (charging) "charging" else "not charging"
        return "Battery: $level% ($status)"
    }

    private fun getClipboard(): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
            ?: return "Clipboard is empty."
        if (clip.itemCount == 0) return "Clipboard is empty."
        val text = clip.getItemAt(0).coerceToText(context).toString()
        return "Clipboard content: $text"
    }

    private fun setClipboard(value: String?): String {
        if (value == null) return "Missing 'value' parameter. Provide text to copy to clipboard."
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("ophoner", value)
        clipboard.setPrimaryClip(clip)
        return "Text copied to clipboard."
    }

    private fun vibrate(value: String?): String {
        val durationMs = value?.toLongOrNull() ?: 200L
        if (durationMs <= 0) return "Duration must be positive, got $durationMs."
        if (durationMs > 10_000) return "Duration capped at 10000ms for safety."

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (!vibrator.hasVibrator()) return "This device does not have a vibrator."

        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        return "Vibrated for ${durationMs}ms."
    }

    private fun getDeviceInfo(): String {
        return buildString {
            appendLine("Model: ${Build.MODEL}")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Brand: ${Build.BRAND}")
            appendLine("Device: ${Build.DEVICE}")
            appendLine("Android version: ${Build.VERSION.RELEASE}")
            appendLine("SDK level: ${Build.VERSION.SDK_INT}")
            appendLine("Build number: ${Build.DISPLAY}")
            appendLine("Hardware: ${Build.HARDWARE}")
            append("Product: ${Build.PRODUCT}")
        }
    }

    private fun toggleFlashlight(value: String?): String {
        if (value == null) return "Missing 'value' parameter. Use 'on' or 'off'."
        if (value !in listOf("on", "off")) return "Invalid value: '$value'. Use 'on' or 'off'."

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "No camera with flashlight found on this device."

        cameraManager.setTorchMode(cameraId, value == "on")
        return "Flashlight turned $value."
    }

    @SuppressLint("MissingPermission")
    private fun getNetworkInfo(): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
            ?: return "No active network connection."
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return "No active network connection."

        val connectionType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Unknown"
        }

        return buildString {
            appendLine("Connection type: $connectionType")
            if (connectionType == "WiFi") {
                try {
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    val wifiInfo = wifiManager.connectionInfo
                    @Suppress("DEPRECATION")
                    appendLine("SSID: ${wifiInfo.ssid}")
                    val ip = wifiInfo.ipAddress
                    val ipStr = "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
                    appendLine("IP address: $ipStr")
                    appendLine("Link speed: ${wifiInfo.linkSpeed} Mbps")
                } catch (e: SecurityException) {
                    appendLine("WiFi details unavailable (location permission required).")
                }
            }
            val downKbps = capabilities.linkDownstreamBandwidthKbps
            val upKbps = capabilities.linkUpstreamBandwidthKbps
            appendLine("Downstream bandwidth: ${downKbps}kbps")
            append("Upstream bandwidth: ${upKbps}kbps")
        }
    }
}
