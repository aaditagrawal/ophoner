package dev.ophoner.tools.impl

internal val ALLOWED_LAUNCH_SCHEMES = setOf(
    "http",
    "https",
    "mailto",
    "tel",
    "sms",
    "smsto",
    "geo",
    "market",
)

/**
 * Returns null when [raw] may be handed to ACTION_VIEW.
 * Rejects schemes that can open local files, content providers, or intent redirects.
 */
internal fun rejectedLaunchUrl(raw: String): String? {
    if (raw.any { it == '\u0000' || it == '\r' || it == '\n' || it == '\t' }) {
        return "URL contains a control character"
    }
    val trimmed = raw.trim()
    val colon = trimmed.indexOf(':')
    if (colon <= 0) return "URL must include an allowed scheme"
    val scheme = trimmed.substring(0, colon).lowercase()
    if (!scheme.matches(SCHEME)) return "URL must include an allowed scheme"
    if (scheme !in ALLOWED_LAUNCH_SCHEMES) return "scheme '$scheme' is not allowed"
    return null
}

private val SCHEME = Regex("[a-z][a-z0-9+.-]*")
