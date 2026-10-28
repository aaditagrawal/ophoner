package dev.ophoner.device

/** Pure ordering logic, independent of Android permission dialogs and lifecycle. */
enum class DeviceSetupStep(val title: String) {
    PERMISSIONS("Everyday permissions"),
    ACCESSIBILITY("Control other apps"),
    NOTIFICATIONS("Read and reply to notifications"),
    STORAGE("Access shared files"),
    SETTINGS("Change system settings"),
    SHIZUKU("Connect Shizuku"),
}

internal fun nextSetupStep(
    steps: List<DeviceSetupStep>,
    satisfied: Set<DeviceSetupStep>,
    deferred: Set<DeviceSetupStep>,
): DeviceSetupStep? = steps.firstOrNull { it !in satisfied && it !in deferred }

enum class ShizukuSetupStage {
    INSTALL, DEVELOPER_OPTIONS, WIRELESS_DEBUGGING, START, AUTHORIZE, READY,
}

internal fun shizukuSetupStage(
    installed: Boolean,
    running: Boolean,
    authorized: Boolean,
    wirelessSupported: Boolean,
    developerOptions: Boolean,
    wirelessDebugging: Boolean,
): ShizukuSetupStage = when {
    running && authorized -> ShizukuSetupStage.READY
    running -> ShizukuSetupStage.AUTHORIZE
    !installed -> ShizukuSetupStage.INSTALL
    wirelessSupported && !developerOptions -> ShizukuSetupStage.DEVELOPER_OPTIONS
    wirelessSupported && !wirelessDebugging -> ShizukuSetupStage.WIRELESS_DEBUGGING
    else -> ShizukuSetupStage.START
}
