package dev.ophoner.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBatchesTest {
    @Test fun readsShareABoundedBatchAndMutationsStayAlone() {
        val calls = listOf("r", "r", "r", "r", "r", "w", "r", "r")
        val batches = planToolBatches(calls, maxParallel = 4) { it == "r" }
        assertEquals(listOf(4, 1, 1, 2), batches.map { it.calls.size })
        assertTrue(batches[0].parallel)
        assertTrue(batches[1].parallel)
        assertFalse(batches[2].parallel)
        assertEquals(listOf("w"), batches[2].calls)
        assertTrue(batches[3].parallel)
    }

    @Test fun emptyPlanIsEmpty() {
        assertTrue(planToolBatches(emptyList<String>(), 4) { true }.isEmpty())
    }
}
