package dev.ophoner.device

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ophoner.ui.components.PrimaryButton
import dev.ophoner.ui.components.SecondaryButton
import dev.ophoner.ui.theme.tokens
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSetupScreen(onBack: () -> Unit, shizukuOnly: Boolean = false, model: DeviceSetupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val t = tokens()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.runtimeResult() }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.returned() }
    fun open(intent: Intent, fallback: Intent? = null) {
        model.markWaiting(true)
        try { settings.launch(intent) } catch (_: Exception) {
            try {
                if (fallback == null) model.fail("This settings page isn't available on your device. Open Android Settings to allow this access, then return here.")
                else settings.launch(fallback)
            } catch (_: Exception) { model.fail("Couldn't open this page. Open Android Settings or Shizuku manually, then return here.") }
        }
    }
    fun authorize() {
        model.markAuthorizationRequested(true)
        try { Shizuku.requestPermission(42) } catch (_: Exception) { model.fail("Shizuku disconnected. Open Shizuku and start it again."); model.refresh() }
    }
    fun openShizuku() = open(context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
    fun wireless() = open(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"), Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    LaunchedEffect(model, shizukuOnly) { model.prepare(shizukuOnly) }
    LaunchedEffect(lifecycle, model) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.returned()
            while (true) { model.refresh(); delay(1000) }
        }
    }
    DisposableEffect(model) {
        val listener = Shizuku.OnRequestPermissionResultListener { request, result ->
            if (request == 42) model.authorizationResult(result == PackageManager.PERMISSION_GRANTED)
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }
    LaunchedEffect(model.started, model.current, model.stage, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (model.started && model.current == DeviceSetupStep.PERMISSIONS && !model.runtimeRequested) {
                model.requestingRuntime()
                permissions.launch(model.runtimePermissions())
            }
            if (model.started && model.current == DeviceSetupStep.SHIZUKU && model.stage == ShizukuSetupStage.AUTHORIZE && !model.authorizationRequested) authorize()
        }
    }
    Scaffold(containerColor = t.background, topBar = {
        TopAppBar(title = { Text(if (shizukuOnly) "Shizuku setup" else "Device access setup") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (!model.started) {
                Text(if (shizukuOnly) "More control. No root." else "Give your agent a helping hand", style = MaterialTheme.typography.headlineMedium, color = t.ink)
                Text(if (shizukuOnly) "Shizuku lets Ophoner manage apps and use advanced system controls. We'll guide you through installing it, starting it, and connecting Ophoner."
                    else "One guided setup for everything your device supports. We'll skip access you've already granted and continue when you return from Android Settings.", color = t.secondary)
                if (!shizukuOnly) {
                    SetupCard("What you'll enable", "Contacts, calendar, location, media, flashlight and Bluetooth\n\nApp control and screen images\n\nNotifications, shared files and system settings\n\nAdvanced device control through Shizuku")
                    Text("Screen content, notifications and personal data used by tools may be sent to your selected AI provider. You can skip any step or turn access off later.", style = MaterialTheme.typography.bodyMedium, color = t.secondary)
                }
                PrimaryButton(text = if (shizukuOnly) "Connect Shizuku" else "Enable all & continue", onClick = model::start, modifier = Modifier.fillMaxWidth())
            } else if (model.current == null) {
                val missing = model.needsAttention
                val complete = missing.isEmpty() && !model.notificationPermissionMissing
                Text(if (complete) "You're connected" else "Setup saved", style = MaterialTheme.typography.headlineMedium)
                Text(if (complete) "${if (shizukuOnly) "Shizuku is" else "Your device capabilities are"} ready to use."
                    else "Your granted access is ready to use. You can finish the remaining steps whenever you like.", color = t.secondary)
                missing.forEach { (capability, status) -> SetupCard(capability.title, status) }
                if (model.notificationPermissionMissing) SetupCard("Task notifications", "Not allowed")
                PrimaryButton(text = "Done", onClick = onBack, modifier = Modifier.fillMaxWidth())
                if (!complete) SecondaryButton(text = "Review remaining access", onClick = model::start, modifier = Modifier.fillMaxWidth())
            } else {
                val step = model.current!!
                if (!shizukuOnly) Text("Step ${model.steps.indexOf(step) + 1} of ${model.steps.size}", color = t.secondary, style = MaterialTheme.typography.labelLarge)
                Text(step.title, style = MaterialTheme.typography.headlineMedium)
                when (step) {
                    DeviceSetupStep.PERMISSIONS -> {
                        Text("Android will ask about contacts, calendar, location, media, camera, nearby devices and task notifications. Choose the access you want to allow.", color = t.secondary)
                        SecondaryButton(text = "Open app permissions", onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth())
                    }
                    DeviceSetupStep.ACCESSIBILITY -> {
                        Text("Open Installed apps or Downloaded apps, choose Ophoner device control, and turn on its accessibility service. This enables reading screens, tapping and typing, plus screen images on Android 11 and newer.", color = t.secondary)
                        Text("If Android blocks a sideloaded app, open Settings → Apps → Ophoner → ⋮ → Allow restricted settings, then try again.", style = MaterialTheme.typography.bodyMedium, color = t.secondary)
                        PrimaryButton(text = "Open accessibility settings", onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth())
                    }
                    DeviceSetupStep.NOTIFICATIONS -> {
                        Text("Allow Ophoner to access notifications so your agent can read, dismiss and reply to them.", color = t.secondary)
                        PrimaryButton(text = "Allow notification access", onClick = {
                            val fallback = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            open(if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, OphonerNotificationService::class.java).flattenToString()) else fallback, fallback)
                        }, modifier = Modifier.fillMaxWidth())
                    }
                    DeviceSetupStep.STORAGE -> {
                        Text("Turn on Allow access to manage all files. Your agent can work with shared files; folder chats still stay inside their chosen folder.", color = t.secondary)
                        PrimaryButton(text = "Open file access settings", onClick = { open(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")), Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }, modifier = Modifier.fillMaxWidth())
                    }
                    DeviceSetupStep.SETTINGS -> {
                        Text("Turn on Allow modifying system settings to let your agent adjust brightness and supported display settings.", color = t.secondary)
                        PrimaryButton(text = "Allow system settings", onClick = { open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth())
                    }
                    DeviceSetupStep.SHIZUKU -> {
                        when (model.stage) {
                            ShizukuSetupStage.INSTALL -> {
                                Text("First, install Shizuku. It's the companion app that gives Ophoner advanced device control without root. Return here after installing it.", color = t.secondary)
                                PrimaryButton(text = "Install Shizuku", onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")), Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) }, modifier = Modifier.fillMaxWidth())
                            }
                            ShizukuSetupStage.DEVELOPER_OPTIONS -> {
                                Text("Enable Developer options once to start Shizuku on this phone. In About phone, find Build number (on Samsung: Software information), tap it seven times, and unlock if asked. Then return here.", color = t.secondary)
                                PrimaryButton(text = "Open About phone", onClick = { open(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }, modifier = Modifier.fillMaxWidth())
                            }
                            ShizukuSetupStage.WIRELESS_DEBUGGING -> {
                                Text("Connect to Wi-Fi, then turn on Wireless debugging in Developer options. Approve your current network if Android asks, and return here.", color = t.secondary)
                                PrimaryButton(text = "Open wireless debugging", onClick = { wireless() }, modifier = Modifier.fillMaxWidth())
                            }
                            ShizukuSetupStage.START -> {
                                if (Build.VERSION.SDK_INT >= 30) {
                                    SetupCard("Pair once, then start", "1. In Shizuku, tap Pairing.\n\n2. Open Wireless debugging → Pair device with pairing code.\n\n3. Enter that code in Shizuku's pairing notification.\n\n4. Return to Shizuku and tap Start. If already paired, just tap Start.")
                                    PrimaryButton(text = "Open Shizuku", onClick = { openShizuku() }, modifier = Modifier.fillMaxWidth())
                                    SecondaryButton(text = "Open wireless debugging", onClick = { wireless() }, modifier = Modifier.fillMaxWidth())
                                } else {
                                    Text("On this Android version, connect your phone to a computer with ADB, enable USB debugging, and run this command:", color = t.secondary)
                                    val command = "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh"
                                    Text(command, style = MaterialTheme.typography.bodyMedium)
                                    SecondaryButton(text = "Copy ADB command", onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Start Shizuku", command)) }, modifier = Modifier.fillMaxWidth())
                                    PrimaryButton(text = "Open Shizuku", onClick = { openShizuku() }, modifier = Modifier.fillMaxWidth())
                                }
                                Text("Return here when Shizuku says it's running. You'll need to start it again after restarting your phone.", color = t.secondary)
                            }
                            ShizukuSetupStage.AUTHORIZE -> {
                                Text("Shizuku is running. Allow Ophoner in the Shizuku permission prompt to connect it.", color = t.secondary)
                                PrimaryButton(text = "Allow Ophoner", onClick = { authorize() }, modifier = Modifier.fillMaxWidth())
                            }
                            ShizukuSetupStage.READY -> Text("Connected", color = t.success)
                        }
                        TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"))) }) { Text("Shizuku setup help") }
                    }
                }
                model.error?.let { Text(it, color = t.danger) }
                if (step != DeviceSetupStep.PERMISSIONS) Text("Already allowed it? This page updates automatically when you return.", style = MaterialTheme.typography.bodySmall, color = t.secondary)
                TextButton(onClick = model::skip) { Text("Skip for now") }
            }
        }
    }
}

@Composable
private fun SetupCard(title: String, description: String) {
    val t = tokens()
    Card(colors = CardDefaults.cardColors(containerColor = t.panel), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = t.ink)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = t.secondary)
        }
    }
}
