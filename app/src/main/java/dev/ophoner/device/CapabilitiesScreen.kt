package dev.ophoner.device

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.ophoner.tools.ToolRegistry
import dev.ophoner.ui.theme.tokens
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import rikka.shizuku.Shizuku
import javax.inject.Inject

@HiltViewModel
class CapabilitiesViewModel @Inject constructor(val access: DeviceAccess, private val tools: ToolRegistry) : ViewModel() {
    var revision by mutableIntStateOf(0); private set
    var results by mutableStateOf<Map<DeviceCapability, String>>(emptyMap()); private set
    var testing by mutableStateOf<Set<DeviceCapability>>(emptySet()); private set
    fun refresh() { revision++ }
    fun enable(capability: DeviceCapability, enabled: Boolean) { access.setEnabled(capability, enabled); refresh() }
    fun test(capability: DeviceCapability) {
        if (capability in testing) return
        testing = testing + capability
        viewModelScope.launch {
            try {
                val pair = when (capability) {
                    DeviceCapability.ACCESSIBILITY -> "screen_control" to "read"
                    DeviceCapability.SHIZUKU -> "advanced_device" to "identity"
                    DeviceCapability.NOTIFICATIONS -> "notifications" to "list"
                    DeviceCapability.SCREEN -> "screen_capture" to "capture"
                    DeviceCapability.CONTACTS -> "contacts" to "search"
                    DeviceCapability.CALENDAR -> "calendar" to "calendars"
                    DeviceCapability.LOCATION -> "location" to "current"
                    DeviceCapability.MEDIA -> "media" to "search"
                    DeviceCapability.STORAGE -> "shared_files" to "list"
                    else -> null
                }
                val result = if (pair == null) access.status(capability) else {
                    val response = tools.getExecutor(pair.first)!!.execute("capability-test", buildJsonObject { put("action", pair.second); put("kind", "images"); put("path", ".") })
                    if (response.isError) response.output else if (capability == DeviceCapability.SCREEN) "Screenshot captured successfully. This test did not send it to a model." else "Test succeeded: ${response.output.take(600)}"
                }
                results = results + (capability to result)
            } finally { testing = testing - capability; refresh() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilitiesScreen(onBack: () -> Unit, onOpenSetup: () -> Unit, onOpenShizuku: () -> Unit, model: CapabilitiesViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val t = tokens()
    val revision = model.revision
    var error by remember { mutableStateOf<String?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.refresh() }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, _ -> model.refresh() }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }
    fun setup(capability: DeviceCapability) {
        error = null
        if (capability == DeviceCapability.SHIZUKU) { onOpenShizuku(); return }
        try {
            val intent = when (capability) {
                DeviceCapability.ACCESSIBILITY, DeviceCapability.SCREEN -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                DeviceCapability.NOTIFICATIONS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                DeviceCapability.SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                DeviceCapability.STORAGE -> if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")) else null
                else -> null
            }
            if (intent != null) settings.launch(intent)
            else model.access.permissions(capability).takeIf { it.isNotEmpty() }?.let(permissions::launch)
        } catch (e: Exception) { error = "Could not open setup: ${e.message}. Use Android Settings > Apps > Ophoner." }
    }
    Scaffold(containerColor = t.background, topBar = { TopAppBar(title = { Text("Device capabilities") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                dev.ophoner.ui.components.PrimaryButton(text = "Enable all", onClick = onOpenSetup, modifier = Modifier.fillMaxWidth())
                Text("Recommended · A guided setup for all available access.", color = t.secondary, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                Text("Enable the access you want Ophoner to use. Screen content, notifications, and personal data returned by tools may be sent to your selected AI provider. Android permissions are granted separately.", color = t.secondary)
                error?.let { Text(it, color = t.danger) }
            }
            items(DeviceCapability.entries, key = { it.name }) { capability ->
                val enabled = remember(revision, capability) { model.access.enabled(capability) }
                val status = remember(revision, capability) { model.access.status(capability) }
                Card(colors = CardDefaults.cardColors(containerColor = t.panel)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) { Text(capability.title, style = MaterialTheme.typography.titleMedium); Text(status, color = if (status == "Ready") t.success else t.secondary) }
                            Switch(checked = enabled, onCheckedChange = { model.enable(capability, it) }, modifier = Modifier.semantics { contentDescription = "${capability.title} access" })
                        }
                        Text(capability.description, color = t.secondary, style = MaterialTheme.typography.bodyMedium)
                        Row {
                            TextButton(onClick = { setup(capability) }, enabled = enabled) { Text("Set up access") }
                            TextButton(onClick = { model.test(capability) }, enabled = enabled && capability !in model.testing) { Text(if (capability in model.testing) "Testing…" else "Test") }
                        }
                        model.results[capability]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                Text("Background tasks", style = MaterialTheme.typography.titleMedium)
                Text("Active runs show a notification with Stop and Open controls. Android may limit long background runs; battery settings do not remove those limits.", color = t.secondary)
                Row {
                    if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }) { Text("Allow notifications") }
                    TextButton(onClick = { settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("App settings") }
                }
            }
        }
    }
}
