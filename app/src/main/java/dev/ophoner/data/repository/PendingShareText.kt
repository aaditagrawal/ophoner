package dev.ophoner.data.repository

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot holder for text shared into the app via ACTION_SEND.
 * MainActivity offers text; ChatViewModel consumes it into the composer draft.
 */
@Singleton
class PendingShareText @Inject constructor() {
    private val pending = AtomicReference<String?>(null)
    private val _navigateToNewChat = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToNewChat: SharedFlow<Unit> = _navigateToNewChat.asSharedFlow()

    /**
     * @param requestNavigation When true (warm start / onNewIntent), ask the nav
     *   host to open a fresh chat so the draft is applied on a new ViewModel.
     */
    fun offer(text: String, requestNavigation: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        pending.set(trimmed)
        if (requestNavigation) {
            _navigateToNewChat.tryEmit(Unit)
        }
    }

    fun consume(): String? = pending.getAndSet(null)
}
