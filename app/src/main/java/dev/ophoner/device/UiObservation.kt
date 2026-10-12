package dev.ophoner.device

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Event-driven local waits, bounded even on screens that animate continuously. */
internal class UiObservation(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    val revision = MutableStateFlow(0L)
    private var lastChange = 0L

    fun changed() { lastChange = now(); revision.value++ }

    suspend fun settle(before: Long) {
        withTimeoutOrNull(400) { revision.first { it > before } }
        val deadline = now() + 800
        while (now() < deadline) {
            val quietFor = now() - lastChange
            if (quietFor >= 80) return
            delay((80 - quietFor).coerceAtMost(80).coerceAtMost(deadline - now()).coerceAtLeast(1))
        }
    }

    suspend fun awaitVisible(timeoutMillis: Long, visible: () -> Boolean): Boolean {
        val deadline = now() + timeoutMillis.coerceIn(0, 5000)
        while (true) {
            val before = revision.value
            if (visible()) return true
            val remaining = deadline - now()
            if (remaining <= 0) return false
            // Some apps omit content events; the bounded local fallback still detects them.
            withTimeoutOrNull(remaining.coerceAtMost(250)) { revision.first { it > before } }
        }
    }
}
