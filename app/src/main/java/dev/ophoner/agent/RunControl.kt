package dev.ophoner.agent

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Follow-ups cross into model context only between complete assistant/tool turns. */
class RunControl {
    private val lock = Mutex()
    private val pending = mutableListOf<String>()
    private var closed = false
    private val revision = AtomicLong()
    val generation get() = revision.get()
    fun requestPause(value: Boolean) {
        if (value && !paused.value) revision.incrementAndGet()
        paused.value = value
    }
    val pendingCount = MutableStateFlow(0)
    val paused = MutableStateFlow(false)
    val atPauseBoundary = MutableStateFlow(false)

    suspend fun submit(text: String, persist: suspend () -> Unit): Boolean = lock.withLock {
        if (closed) return@withLock false
        persist()
        pending.add(text)
        pendingCount.value = pending.size
        true
    }

    /** null means completion won the race; submissions must use a new run. */
    suspend fun take(closeIfEmpty: Boolean = false): List<String>? = lock.withLock {
        if (closed) return@withLock null
        if (pending.isEmpty() && closeIfEmpty) { closed = true; return@withLock null }
        pending.toList().also { pending.clear(); pendingCount.value = 0 }
    }

    suspend fun close() = lock.withLock { closed = true }

    suspend fun awaitReady() {
        if (!paused.value) return
        atPauseBoundary.value = true
        try { paused.first { !it } } finally { atPauseBoundary.value = false }
    }
}
