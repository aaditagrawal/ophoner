package dev.ophoner.ui.util

/** SAF tree URIs often store opaque path segments — show a readable folder label. */
fun formatFolderDisplayName(raw: String): String {
    val cleaned = raw
        .removePrefix("primary:")
        .removePrefix("tree/")
        .replace(':', '/')
        .trim('/')
    if (cleaned.isBlank()) return "Folder"
    return cleaned.substringAfterLast('/').ifBlank { cleaned }
}
