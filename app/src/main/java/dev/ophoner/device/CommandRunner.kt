package dev.ophoner.device

import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bounded output, concurrent pipe draining, and actual process deadlines. */
class CommandRunner {
    private val running = ConcurrentHashMap.newKeySet<Process>()
    fun cancelAll() { running.forEach { it.destroyForcibly() } }
    fun execute(command: String, timeoutMs: Long, directory: File? = null): Array<String> {
        require(command.length <= 32_768 && !command.contains('\u0000')) { "Invalid or oversized command" }
        val process = ProcessBuilder("sh", "-c", command).directory(directory).start()
        running.add(process)
        val readers = Executors.newFixedThreadPool(2)
        try {
            val stdout = readers.submit<String> { readBounded(process.inputStream) }
            val stderr = readers.submit<String> { readBounded(process.errorStream) }
            val finished = process.waitFor(timeoutMs.coerceIn(100, 120_000), TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            val output = runCatching { stdout.get(2, TimeUnit.SECONDS) }.getOrDefault("[output unavailable]")
            val error = runCatching { stderr.get(2, TimeUnit.SECONDS) }.getOrDefault("[stderr unavailable]")
            return arrayOf(output, if (finished) error else "$error\nCommand timed out", if (finished) process.exitValue().toString() else "124")
        } finally {
            running.remove(process)
            process.destroyForcibly()
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
            readers.shutdownNow()
        }
    }
    private fun readBounded(stream: InputStream): String = stream.bufferedReader().use { reader ->
        val result = StringBuilder()
        val buffer = CharArray(4096)
        var truncated = false
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            val keep = count.coerceAtMost((48_000 - result.length).coerceAtLeast(0))
            result.append(buffer, 0, keep)
            if (keep < count) truncated = true
        }
        result.toString() + if (truncated) "\n[output truncated]" else ""
    }
}
