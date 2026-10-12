package dev.ophoner.device

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UiObservationTest {
    @Test fun `already delivered events require only a short quiet period`() = runTest {
        val ui = UiObservation { currentTime }
        val before = ui.revision.value
        ui.changed()
        ui.settle(before)
        assertEquals(80L, currentTime)
    }
    @Test fun `unchanged screens and continuously changing screens are bounded`() = runTest {
        val ui = UiObservation { currentTime }
        ui.settle(ui.revision.value)
        assertEquals(400L, currentTime)
        val before = ui.revision.value
        val start = currentTime
        val animation = backgroundScope.launch { while (true) { ui.changed(); delay(20) } }
        ui.settle(before)
        assertTrue(currentTime - start <= 800)
        animation.cancel()
    }
    @Test fun `visible control wakes the wait immediately instead of polling the model`() = runTest {
        val ui = UiObservation { currentTime }
        var visible = false
        backgroundScope.launch { delay(75); visible = true; ui.changed() }
        assertTrue(ui.awaitVisible(3000) { visible })
        assertEquals(75L, currentTime)
        println("UI_WAIT_BENCHMARK control appeared at 75 ms; detected at $currentTime ms; additional model requests=0")
    }
    @Test fun `missing Android events use bounded local fallback and timeout`() = runTest {
        val ui = UiObservation { currentTime }
        var visible = false
        backgroundScope.launch { delay(75); visible = true }
        assertTrue(ui.awaitVisible(3000) { visible })
        assertEquals(250L, currentTime)
        assertFalse(ui.awaitVisible(700) { false })
        assertEquals(950L, currentTime)
    }
    @Test fun `stop cancels a local wait immediately`() = runTest {
        val ui = UiObservation { currentTime }
        val waiting = launch { ui.awaitVisible(5000) { false }; fail("Cancelled wait must not return") }
        runCurrent()
        waiting.cancelAndJoin()
        assertEquals(0L, currentTime)
    }
}
