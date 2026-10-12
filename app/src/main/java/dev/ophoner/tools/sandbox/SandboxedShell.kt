package dev.ophoner.tools.sandbox

import dev.ophoner.device.CommandRunner
import dev.ophoner.device.DeviceAccess
import dev.ophoner.device.DeviceCapability
import dev.ophoner.device.ShizukuClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ShellResult(val stdout: String, val stderr: String, val exitCode: Int, val privileged: Boolean = false)

@Singleton
class SandboxedShell @Inject constructor(private val access: DeviceAccess, private val client: ShizukuClient) {
    private val runner = CommandRunner()
    private val execution = Mutex()
    fun isShizukuAvailable(): Boolean = access.enabled(DeviceCapability.SHIZUKU) && shizukuStatus() == ShizukuStatus.CONNECTED
    fun shizukuStatus(): ShizukuStatus = try {
        if (!Shizuku.pingBinder()) ShizukuStatus.NOT_RUNNING
        else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) ShizukuStatus.PERMISSION_NEEDED
        else ShizukuStatus.CONNECTED
    } catch (_: Exception) { ShizukuStatus.NOT_INSTALLED }

    suspend fun execute(command: String, timeoutMs: Long = 30_000, workingDir: File? = null): ShellResult {
        if (isShizukuAvailable()) return executePrivileged(command, timeoutMs)
        return execution.withLock { coroutineScope {
            val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { runner.cancelAll() }
            }
            try {
                val result = withContext(Dispatchers.IO) { runner.execute(command, timeoutMs, workingDir) }
                ShellResult(result[0], result[1], result[2].toInt(), false)
            } finally { withContext(NonCancellable) { cancellation.cancelAndJoin() } }
        } }
    }
    suspend fun executePrivileged(command: String, timeoutMs: Long = 30_000): ShellResult {
        val result = client.execute(command, timeoutMs)
        return ShellResult(result[0], result[1], result[2].toInt(), true)
    }
    fun cancelAll() { runner.cancelAll(); client.cancelAll() }
}

enum class ShizukuStatus(val displayLabel: String) {
    CONNECTED("Connected"), PERMISSION_NEEDED("Permission needed"), NOT_RUNNING("Not running"), NOT_INSTALLED("Not installed")
}
