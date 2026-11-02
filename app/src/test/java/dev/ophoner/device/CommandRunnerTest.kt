package dev.ophoner.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class CommandRunnerTest {
    @Test fun bothPipesDrainTogetherAndOutputIsCapped() {
        val runner = CommandRunner()
        val started = System.nanoTime()
        val result = runner.execute(
            "dd if=/dev/zero bs=100000 count=1 status=none | tr '\\0' o >/dev/stdout & " +
                "dd if=/dev/zero bs=100000 count=1 status=none | tr '\\0' e >&2; wait",
            8_000,
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(result[1], "0", result[2])
        assertTrue(result[0].contains("[output truncated]"))
        assertTrue(result[1].contains("[output truncated]"))
        assertTrue(result[0].length < 50_000)
        assertTrue("elapsed $elapsedMs", elapsedMs < 8_000)
    }

    @Test fun deadlineKillsASleepingCommand() {
        val started = System.nanoTime()
        val result = CommandRunner().execute("sleep 30", 500)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("124", result[2])
        assertTrue(result[1].contains("timed out"))
        assertTrue("elapsed $elapsedMs", elapsedMs in 400..8_000)
    }

    @Test fun closedStdinLetsCatExit() {
        val started = System.nanoTime()
        val result = CommandRunner().execute("cat", 5_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(result[1], "0", result[2])
        assertTrue("elapsed $elapsedMs", elapsedMs < 2_000)
    }

    @Test fun cancelAllUnblocksARunningCommand() {
        val runner = CommandRunner()
        val done = CompletableFuture<Array<String>>()
        val thread = Thread { done.complete(runner.execute("sleep 30", 20_000)) }
        thread.isDaemon = true
        thread.start()
        Thread.sleep(200)
        runner.cancelAll()
        val result = done.get(5, TimeUnit.SECONDS)
        assertFalse(result[2] == "0")
        thread.join(1_000)
        assertFalse(thread.isAlive)
    }

    @Test fun rejectsANullByte() {
        assertThrows(IllegalArgumentException::class.java) {
            CommandRunner().execute("echo \u0000", 1_000)
        }
    }
}
