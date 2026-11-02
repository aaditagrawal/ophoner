package dev.ophoner.device

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs one shell command with a real process deadline.
 *
 * Stdout and stderr are drained together. A pipe buffer is only about 64KiB, so
 * reading one stream to completion before the other deadlocks a child that fills
 * both. The deadline is [Process.waitFor], not a coroutine timer wrapped around
 * a still-running process. Sandbox and Shizuku execution both use this class.
 * Stdin is closed immediately because the tool does not supply input; leaving
 * it open makes `cat` block until the deadline.
 */
class CommandRunner {
    private val running = ConcurrentHashMap.newKeySet<Process>()

    fun cancelAll() {
        running.forEach { process -> runCatching { process.destroyForcibly() } }
    }

    fun execute(command: String, timeoutMs: Long, directory: File? = null): Array<String> {
        require(command.length <= 32_768 && !command.contains('\u0000')) { "Invalid or oversized command" }
        val process = ProcessBuilder("sh", "-c", command)
            .directory(directory)
            .redirectErrorStream(false)
            .start()
        running.add(process)
        runCatching { process.outputStream.close() }
        val stdout = PipeDrain(process.inputStream)
        val stderr = PipeDrain(process.errorStream)
        val readers = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "ophoner-shell").apply { isDaemon = true }
        }
        readers.execute(stdout)
        readers.execute(stderr)
        try {
            val finished = process.waitFor(timeoutMs.coerceIn(100, 120_000), TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            stdout.await(DRAIN_GRACE_MS)
            stderr.await(DRAIN_GRACE_MS)
            val output = stdout.snapshot()
            val error = stderr.snapshot()
            val code = if (finished) process.exitValue() else 124
            val reportedError = if (finished) {
                error
            } else if (error.isEmpty() || error == "[output unavailable]") {
                "Command timed out"
            } else {
                "$error\nCommand timed out"
            }
            return arrayOf(output, reportedError, code.toString())
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            throw e
        } finally {
            running.remove(process)
            runCatching { process.destroyForcibly() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            stdout.await(DRAIN_CLOSE_MS)
            stderr.await(DRAIN_CLOSE_MS)
            readers.shutdownNow()
        }
    }

    private class PipeDrain(private val stream: InputStream) : Runnable {
        private val buffer = StringBuilder()
        private val finished = CountDownLatch(1)
        @Volatile private var truncated = false
        @Volatile private var done = false

        override fun run() {
            try {
                InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                    val chunk = CharArray(4096)
                    while (true) {
                        val count = reader.read(chunk)
                        if (count < 0) break
                        synchronized(buffer) {
                            val room = (OUTPUT_CHARS - buffer.length).coerceAtLeast(0)
                            val keep = count.coerceAtMost(room)
                            if (keep > 0) buffer.append(chunk, 0, keep)
                            if (keep < count) truncated = true
                        }
                    }
                }
            } catch (_: IOException) {
                // The deadline path closes the stream to unblock the read.
            } finally {
                done = true
                finished.countDown()
            }
        }

        fun await(timeoutMs: Long) {
            finished.await(timeoutMs, TimeUnit.MILLISECONDS)
        }

        fun snapshot(): String {
            val text = synchronized(buffer) { buffer.toString() }
            if (!done && text.isEmpty()) return "[output unavailable]"
            return if (truncated) text + "\n[output truncated]" else text
        }
    }

    private companion object {
        const val OUTPUT_CHARS = 48_000
        const val DRAIN_GRACE_MS = 2_000L
        const val DRAIN_CLOSE_MS = 200L
    }
}
