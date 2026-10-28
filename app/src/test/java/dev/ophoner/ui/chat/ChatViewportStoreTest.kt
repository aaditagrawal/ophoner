package dev.ophoner.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ChatViewportStoreTest {
    @Test fun `thread anchors restore independently with bounded retention`() {
        val store = ChatViewportStore()
        val anchor = ChatViewport("message", 120, 47, false)
        repeat(32) { store.put("$it", anchor.copy(orderIndex = it)) }
        assertEquals(anchor.copy(orderIndex = 0), store.get("0"))
        store.put("new", anchor)
        assertNull(store.get("1")) // Least recently used, not the refreshed zero entry.
        assertEquals(anchor, store.get("new"))
        assertNotNull(store.get("0"))
    }
}
