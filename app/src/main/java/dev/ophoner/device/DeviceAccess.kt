package dev.ophoner.device

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class DeviceCapability(val title: String, val description: String) {
    ACCESSIBILITY("Control other apps", "Read screen controls, tap, type, scroll, and navigate. Enable the accessibility service in Android settings."),
    SHIZUKU("Advanced device control", "Use Shizuku's ADB access for system settings and app management. No root required."),
    NOTIFICATIONS("Notification access", "Read, dismiss, and reply to notifications that offer actions."),
    SCREEN("Screen images", "Send screenshots to your selected model when a task needs visual context. Requires accessibility on Android 11+."),
    CONTACTS("Contacts", "Search names and phone numbers; create or edit contacts."),
    CALENDAR("Calendar", "Read calendars and events; create, update, and delete events."),
    LOCATION("Location", "Request your current location while Ophoner is visible."),
    MEDIA("Photos, videos, and audio", "Search shared media and open items in their installed viewer."),
    STORAGE("Shared files", "Read, write, move, and delete files in shared storage. Folder chats remain scoped to their chosen folder."),
    SETTINGS("System settings", "Adjust brightness and other supported display settings."),
    CAMERA("Flashlight", "Use camera permission to control the flashlight."),
    BLUETOOTH("Bluetooth", "Access Bluetooth controls. Advanced toggles use Shizuku."),
}

@Singleton
class DeviceAccess @Inject constructor(@ApplicationContext val context: Context) {
    private val preferences = context.getSharedPreferences("device_capabilities", Context.MODE_PRIVATE)
    fun enabled(capability: DeviceCapability) = preferences.getBoolean(capability.name, false)
    fun setEnabled(capability: DeviceCapability, value: Boolean) {
        preferences.edit().putBoolean(capability.name, value).apply()
    }
    fun enableAll() {
        preferences.edit().apply {
            DeviceCapability.entries.forEach { capability ->
                if (capability != DeviceCapability.SCREEN || Build.VERSION.SDK_INT >= 30) putBoolean(capability.name, true)
            }
        }.apply()
    }
    @Suppress("DEPRECATION")
    fun shizukuInstalled(): Boolean = try {
        context.packageManager.getApplicationInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun accessibilityGranted(): Boolean {
        val component = ComponentName(context, OphonerAccessibilityService::class.java)
        return runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
        }.getOrDefault(false)
    }
    fun notificationAccessGranted(): Boolean {
        val component = ComponentName(context, OphonerNotificationService::class.java)
        return runCatching {
            if (Build.VERSION.SDK_INT >= 27) context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
            else Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
        }.getOrDefault(false)
    }
    fun requireEnabled(capability: DeviceCapability) {
        check(enabled(capability)) { "Enable '${capability.title}' in Settings > Device capabilities first." }
    }
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun permissions(capability: DeviceCapability): Array<String> = when (capability) {
        DeviceCapability.CONTACTS -> arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        DeviceCapability.CALENDAR -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        DeviceCapability.LOCATION -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        DeviceCapability.CAMERA -> arrayOf(Manifest.permission.CAMERA)
        DeviceCapability.BLUETOOTH -> if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
        DeviceCapability.MEDIA -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        DeviceCapability.STORAGE -> if (Build.VERSION.SDK_INT < 30) arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyArray()
        else -> emptyArray()
    }
    fun status(capability: DeviceCapability): String {
        if (!enabled(capability)) return "Off"
        return when (capability) {
            DeviceCapability.ACCESSIBILITY -> if (OphonerAccessibilityService.instance != null) "Ready" else if (accessibilityGranted()) "Connecting…" else "Enable service in Android settings"
            DeviceCapability.SCREEN -> when {
                Build.VERSION.SDK_INT < 30 -> "Requires Android 11 or newer"
                OphonerAccessibilityService.instance == null -> "Enable accessibility first"
                !enabled(DeviceCapability.ACCESSIBILITY) -> "Enable app control first"
                else -> "Ready"
            }
            DeviceCapability.NOTIFICATIONS -> if (OphonerNotificationService.instance != null) "Ready" else if (notificationAccessGranted()) "Connecting…" else "Grant notification access"
            DeviceCapability.SHIZUKU -> try {
                if (!rikka.shizuku.Shizuku.pingBinder()) { if (shizukuInstalled()) "Start Shizuku" else "Install Shizuku" }
                else if (rikka.shizuku.Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) "Authorize Ophoner in Shizuku" else "Ready"
            } catch (_: Exception) { if (shizukuInstalled()) "Start Shizuku" else "Install Shizuku" }
            DeviceCapability.STORAGE -> if (Build.VERSION.SDK_INT >= 30) { if (Environment.isExternalStorageManager()) "Ready" else "Grant all-files access" } else permissionStatus(capability)
            DeviceCapability.SETTINGS -> if (Settings.System.canWrite(context)) "Ready" else "Grant modify-system-settings access"
            DeviceCapability.LOCATION -> if (granted(Manifest.permission.ACCESS_COARSE_LOCATION)) "Ready" else "Grant location permission"
            else -> permissionStatus(capability)
        }
    }
    private fun permissionStatus(capability: DeviceCapability): String {
        val permissions = permissions(capability)
        return if (permissions.all(::granted)) "Ready" else if (permissions.any(::granted)) "Partial access" else "Permission needed"
    }
    fun inventory() = DeviceCapability.entries.joinToString("\n") { "${it.name.lowercase()}: ${status(it)}" }
}
