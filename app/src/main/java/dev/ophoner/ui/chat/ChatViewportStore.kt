package dev.ophoner.ui.chat

import javax.inject.Inject
import javax.inject.Singleton

data class ChatViewport(
    val messageId: String,
    val orderIndex: Int,
    val offset: Int,
    val followLatest: Boolean,
)

/** Retain scroll anchors, never message bodies or ViewModels, across thread switches. */
@Singleton
class ChatViewportStore @Inject constructor() {
    private val anchors = LinkedHashMap<String, ChatViewport>(32, 0.75f, true)

    @Synchronized fun get(id: String): ChatViewport? = anchors[id]

    @Synchronized fun put(id: String, viewport: ChatViewport) {
        anchors[id] = viewport
        while (anchors.size > 32) anchors.remove(anchors.keys.first())
    }
}
