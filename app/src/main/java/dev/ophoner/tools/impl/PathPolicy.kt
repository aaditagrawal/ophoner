package dev.ophoner.tools.impl

import java.io.File

/**
 * Defense-in-depth path validation layered on top of FileAccessManager.
 * FileAccessManager resolves paths as relative segments under a user-selected
 * document tree, so traversal outside the tree is already blocked by the
 * SAF URI sandbox. These checks reject obviously bad inputs before that walk.
 *
 * Returns null when the path is acceptable, or a human-readable reason when
 * it must be rejected.
 */
internal fun validatePath(rawPath: String): String? {
    if (rawPath.contains('\u0000')) return "path contains null byte"
    if (rawPath.isBlank()) return "path is blank"
    if (rawPath.split('/', '\\').any { it == ".." }) {
        return "path contains traversal segments"
    }

    sensitivePrefix(rawPath)?.let { return "path points into sensitive system location ($it)" }
    foreignAppData(rawPath)?.let { return "path points into another app's private data ($it)" }

    val canonical = try {
        File(rawPath).canonicalPath
    } catch (_: Exception) {
        return "path could not be canonicalized"
    }
    sensitivePrefix(canonical)?.let { return "canonical path resolves into sensitive system location ($it)" }
    foreignAppData(canonical)?.let { return "canonical path points into another app's private data ($it)" }
    return null
}

private val SENSITIVE_PREFIXES = listOf(
    "/system",
    "/proc",
    "/sys",
    "/vendor",
    "/apex",
    "/dev",
)

private fun sensitivePrefix(path: String): String? {
    for (prefix in SENSITIVE_PREFIXES) {
        if (path == prefix || path.startsWith("$prefix/")) return prefix
    }
    return null
}

private fun foreignAppData(path: String): String? {
    val prefixes = listOf("/data/data/", "/data/user/0/", "/data/user/")
    for (prefix in prefixes) {
        if (!path.startsWith(prefix)) continue
        val pkg = path.removePrefix(prefix).substringBefore('/')
        if (pkg.isNotEmpty() && pkg != "dev.ophoner" && pkg != "dev.ophoner.debug") return pkg
    }
    return null
}
