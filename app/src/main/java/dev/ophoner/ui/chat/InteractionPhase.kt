package dev.ophoner.ui.chat

/**
 * Run chrome derived from the flat agent flags.
 * Resume is the window where the loop is still inside a pause boundary
 * after the user has already asked to continue. Toggling again in that window
 * would request a fresh pause.
 */
internal sealed interface RunChrome {
    data object Hidden : RunChrome
    data class Working(val queued: Int) : RunChrome
    data class Pausing(val queued: Int) : RunChrome
    data class Paused(val queued: Int) : RunChrome
    data class Resuming(val queued: Int) : RunChrome
}

internal fun runChrome(
    running: Boolean,
    paused: Boolean,
    pauseRequested: Boolean,
    queued: Int,
): RunChrome {
    if (!running) return RunChrome.Hidden
    return when {
        paused && !pauseRequested -> RunChrome.Resuming(queued)
        paused -> RunChrome.Paused(queued)
        pauseRequested -> RunChrome.Pausing(queued)
        else -> RunChrome.Working(queued)
    }
}

/** A tool card before a result exists is still receiving arguments, not finished. */
internal sealed interface ToolCardPhase {
    data object Receiving : ToolCardPhase
    data object Running : ToolCardPhase
    data class Finished(val error: Boolean) : ToolCardPhase
}

internal fun toolCardPhase(isExecuting: Boolean, result: String?, isError: Boolean): ToolCardPhase = when {
    isExecuting -> ToolCardPhase.Running
    result == null -> ToolCardPhase.Receiving
    else -> ToolCardPhase.Finished(isError)
}
