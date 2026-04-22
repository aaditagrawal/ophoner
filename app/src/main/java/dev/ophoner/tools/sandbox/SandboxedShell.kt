package dev.ophoner.tools.sandbox

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val privileged: Boolean = false,
)

@Singleton
class SandboxedShell @Inject constructor() {

    /**
     * Returns true if Shizuku is running and we have permission to use it.
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            // Shizuku is optional infrastructure — any exception (not installed,
            // binder not ready, permission query failure) is treated as "not
            // available". Log so the reason is observable for debugging.
            android.util.Log.d("SandboxedShell", "Shizuku unavailable: ${e.message}")
            false
        }
    }

    /**
     * Returns a status string for the Shizuku connection.
     */
    fun shizukuStatus(): ShizukuStatus {
        return try {
            if (!Shizuku.pingBinder()) {
                ShizukuStatus.NOT_RUNNING
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                ShizukuStatus.PERMISSION_NEEDED
            } else {
                ShizukuStatus.CONNECTED
            }
        } catch (e: Exception) {
            // Any thrown exception here means the Shizuku classes can't be reached
            // (app not installed / not linked). Log for diagnostics.
            android.util.Log.d("SandboxedShell", "Shizuku status check failed: ${e.message}")
            ShizukuStatus.NOT_INSTALLED
        }
    }

    suspend fun execute(
        command: String,
        timeoutMs: Long = 30_000,
        workingDir: File? = null,
    ): ShellResult = withContext(Dispatchers.IO) {
        withTimeout(timeoutMs) {
            // Try Shizuku privileged execution first
            if (isShizukuAvailable()) {
                try {
                    return@withTimeout executePrivileged(command)
                } catch (e: Exception) {
                    // Privileged path failed — fall back to the unprivileged sandboxed
                    // ProcessBuilder execution. Log so the user can see why Shizuku
                    // didn't work if results look unexpectedly unprivileged.
                    android.util.Log.w("SandboxedShell", "Privileged Shizuku execution failed, falling back", e)
                }
            }

            // Sandboxed fallback
            val process = ProcessBuilder("sh", "-c", command)
                .apply { workingDir?.let { directory(it) } }
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            ShellResult(
                stdout = stdout,
                stderr = stderr,
                exitCode = exitCode,
                privileged = false,
            )
        }
    }

    private fun executePrivileged(command: String): ShellResult {
        val binder = Shizuku.getBinder()
        val service = IShizukuService.Stub.asInterface(binder)
        val remoteProcess = service.newProcess(arrayOf("sh", "-c", command), null, null)
        var stdoutPfd: ParcelFileDescriptor? = null
        var stderrPfd: ParcelFileDescriptor? = null
        try {
            stdoutPfd = remoteProcess.inputStream
            stderrPfd = remoteProcess.errorStream
            val stdout = stdoutPfd?.let {
                FileInputStream(it.fileDescriptor).bufferedReader().use { r -> r.readText() }
            } ?: ""
            val stderr = stderrPfd?.let {
                FileInputStream(it.fileDescriptor).bufferedReader().use { r -> r.readText() }
            } ?: ""
            val exitCode = remoteProcess.waitFor()
            return ShellResult(
                stdout = stdout,
                stderr = stderr,
                exitCode = exitCode,
                privileged = true,
            )
        } finally {
            stdoutPfd?.close()
            stderrPfd?.close()
            try {
                remoteProcess.destroy()
            } catch (e: Exception) {
                android.util.Log.w("SandboxedShell", "Failed to destroy remote process", e)
            }
        }
    }
}

enum class ShizukuStatus(val displayLabel: String) {
    CONNECTED("Connected"),
    PERMISSION_NEEDED("Permission needed"),
    NOT_RUNNING("Not running"),
    NOT_INSTALLED("Not installed"),
}
