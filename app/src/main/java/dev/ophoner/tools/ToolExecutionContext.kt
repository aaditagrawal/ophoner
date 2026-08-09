package dev.ophoner.tools

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Per-run tool execution flags.
 *
 * When [rootUri] is non-null, [dev.ophoner.tools.sandbox.FileAccessManager]
 * scopes SAF operations to that tree URI instead of the global settings
 * working directory. Propagates across [kotlinx.coroutines.withContext]
 * dispatcher hops.
 *
 * [yoloMode] is snapshotted at agent-run start from settings. When true,
 * soft shell blocks are lifted and any confirmation gates should auto-approve.
 */
data class ToolExecutionContext(
    val rootUri: String? = null,
    val yoloMode: Boolean = false,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolExecutionContext>

    /** True when user confirmation / soft gates should be skipped. */
    val skipsConfirmation: Boolean get() = yoloMode
}
