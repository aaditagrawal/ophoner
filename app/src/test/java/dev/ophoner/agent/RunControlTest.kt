package dev.ophoner.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunControlTest {
    @Test fun `follow-ups are persisted before becoming visible and consumed in order`() = runTest {
        val control = RunControl()
        val saved = mutableListOf<String>()
        assertTrue(control.submit("first") { saved.add("first") })
        assertTrue(control.submit("second") { saved.add("second") })
        assertEquals(listOf("first", "second"), saved)
        assertEquals(2, control.pendingCount.value)
        assertEquals(saved, control.take())
        assertEquals(0, control.pendingCount.value)
    }

    @Test fun `completion waits for a submission already saving`() = runTest {
        val control = RunControl()
        val save = CompletableDeferred<Unit>()
        val submitted = async { control.submit("don't send it") { save.await() } }
        runCurrent()
        val finish = async { control.take(closeIfEmpty = true) }
        runCurrent()
        assertFalse(finish.isCompleted)
        save.complete(Unit)
        assertTrue(submitted.await())
        assertEquals(listOf("don't send it"), finish.await())
        assertNull(control.take(closeIfEmpty = true))
        var persisted = false
        assertFalse(control.submit("too late") { persisted = true })
        assertFalse(persisted)
    }

    @Test fun `failed persistence never queues a prompt`() = runTest {
        val control = RunControl()
        try { control.submit("lost") { error("disk full") }; fail("Expected failure") } catch (_: IllegalStateException) { }
        assertTrue(control.take()!!.isEmpty())
    }

    @Test fun `pause waits at a boundary and invalidates actions planned before takeover`() = runTest {
        val control = RunControl()
        val generation = control.generation
        control.requestPause(true)
        val wait = async { control.awaitReady() }
        runCurrent()
        assertTrue(control.atPauseBoundary.value)
        assertFalse(wait.isCompleted)
        assertNotEquals(generation, control.generation)
        control.requestPause(false)
        wait.await()
        assertFalse(control.atPauseBoundary.value)
    }
}
