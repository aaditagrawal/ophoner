package dev.ophoner.device

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import rikka.shizuku.Shizuku
import javax.inject.Inject

@HiltViewModel
class DeviceSetupViewModel @Inject constructor(
    val access: DeviceAccess,
    private val saved: SavedStateHandle,
) : ViewModel() {
    var started by mutableStateOf(saved.get<Boolean>("started") ?: false); private set
    var shizukuOnly by mutableStateOf(saved.get<Boolean>("shizuku_only") ?: false); private set
    var statuses by mutableStateOf<Map<DeviceCapability, String>>(emptyMap()); private set
    var current by mutableStateOf<DeviceSetupStep?>(null); private set
    var stage by mutableStateOf(ShizukuSetupStage.INSTALL); private set
    var error by mutableStateOf<String?>(null); private set
    var waiting by mutableStateOf(saved.get<Boolean>("waiting") ?: false); private set
    var authorizationRequested by mutableStateOf(saved.get<Boolean>("shizuku_requested") ?: false); private set
    var runtimeRequested by mutableStateOf(saved.get<Boolean>("runtime_requested") ?: false); private set
    private var deferred: Set<DeviceSetupStep>
        get() = saved.get<ArrayList<String>>("deferred").orEmpty().mapNotNull { name -> DeviceSetupStep.entries.firstOrNull { it.name == name } }.toSet()
        set(value) { saved["deferred"] = ArrayList(value.map { it.name }) }
    val steps get() = if (shizukuOnly) listOf(DeviceSetupStep.SHIZUKU) else DeviceSetupStep.entries
    val supportedCapabilities get() = DeviceCapability.entries.filter { it != DeviceCapability.SCREEN || Build.VERSION.SDK_INT >= 30 }
    val needsAttention get() = statuses.filter { (capability, status) -> capability in supportedCapabilities && (!shizukuOnly || capability == DeviceCapability.SHIZUKU) && status != "Ready" }
    val notificationPermissionMissing get() = !shizukuOnly && Build.VERSION.SDK_INT >= 33 && !access.granted(Manifest.permission.POST_NOTIFICATIONS)
    val completedCount get() = steps.count { satisfied(it) }

    fun prepare(singleShizuku: Boolean) {
        shizukuOnly = singleShizuku
        saved["shizuku_only"] = singleShizuku
        refresh()
    }
    fun start() {
        if (shizukuOnly) access.setEnabled(DeviceCapability.SHIZUKU, true)
        else access.enableAll()
        deferred = emptySet()
        started = true
        saved["started"] = true
        markWaiting(false)
        markAuthorizationRequested(false)
        runtimeRequested = false
        saved["runtime_requested"] = false
        error = null
        refresh()
    }
    fun refresh() {
        statuses = DeviceCapability.entries.associateWith(access::status)
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val authorized = running && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        stage = shizukuSetupStage(
            installed = access.shizukuInstalled(), running = running, authorized = authorized,
            wirelessSupported = Build.VERSION.SDK_INT >= 30,
            developerOptions = runCatching { Settings.Global.getInt(access.context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrDefault(false),
            wirelessDebugging = runCatching { Settings.Global.getInt(access.context.contentResolver, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false),
        )
        if (started) {
            val next = nextSetupStep(steps, steps.filter(::satisfied).toSet(), deferred)
            if (next != current) { error = null; markWaiting(false) }
            current = next
        }
    }
    private fun satisfied(step: DeviceSetupStep): Boolean = when (step) {
        DeviceSetupStep.PERMISSIONS -> runtimePermissions().isEmpty()
        DeviceSetupStep.ACCESSIBILITY -> access.accessibilityGranted()
        DeviceSetupStep.NOTIFICATIONS -> access.notificationAccessGranted()
        DeviceSetupStep.STORAGE -> if (Build.VERSION.SDK_INT >= 30) access.status(DeviceCapability.STORAGE) == "Ready" else true
        DeviceSetupStep.SETTINGS -> Settings.System.canWrite(access.context)
        DeviceSetupStep.SHIZUKU -> stage == ShizukuSetupStage.READY
    }
    fun runtimePermissions(): Array<String> {
        val requested = supportedCapabilities.flatMap { access.permissions(it).toList() }.toMutableSet()
        if (Build.VERSION.SDK_INT >= 33) requested.add(Manifest.permission.POST_NOTIFICATIONS)
        // Approximate location is a valid choice; don't ask again for fine access.
        if (access.granted(Manifest.permission.ACCESS_COARSE_LOCATION)) requested.remove(Manifest.permission.ACCESS_FINE_LOCATION)
        return requested.filterNot(access::granted).toTypedArray()
    }
    fun runtimeResult() {
        markWaiting(false)
        // A denial is a choice, not a loop. Finish other steps and show missing access in the summary.
        deferred = deferred + DeviceSetupStep.PERMISSIONS
        refresh()
    }
    fun requestingRuntime() { runtimeRequested = true; saved["runtime_requested"] = true; markWaiting(true) }
    fun returned() {
        markWaiting(false)
        refresh()
    }
    fun skip() {
        current?.let { deferred = deferred + it }
        markWaiting(false)
        error = null
        refresh()
    }
    fun markWaiting(value: Boolean) { waiting = value; saved["waiting"] = value }
    fun markAuthorizationRequested(value: Boolean) { authorizationRequested = value; saved["shizuku_requested"] = value }
    fun fail(message: String) { markWaiting(false); error = message }
    fun authorizationResult(granted: Boolean) {
        markWaiting(false)
        refresh()
        if (!granted) error = "Shizuku access wasn't granted. You can try again or skip it for now."
    }
}
