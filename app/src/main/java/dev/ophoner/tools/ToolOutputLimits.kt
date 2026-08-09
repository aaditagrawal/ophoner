package dev.ophoner.tools

/** Shared caps for tool outputs returned to the model. */
object ToolOutputLimits {
    const val FILE_READ_MAX_CHARS = 80_000
    const val FILE_LIST_MAX_ENTRIES = 2_000
    const val FILE_LIST_MAX_CHARS = 80_000
    const val WEB_FETCH_MAX_CHARS = 8_000
    const val WEB_FETCH_MAX_RAW_BYTES = 512_000L
    const val SKILLS_MAX_TOTAL_CHARS = 32_000
    const val SKILL_MAX_FILE_CHARS = 12_000

    fun truncateWithNotice(text: String, maxChars: Int, label: String = "output"): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) +
            "\n\n[$label truncated at $maxChars chars; ${text.length - maxChars} chars omitted]"
    }
}
