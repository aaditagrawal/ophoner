package dev.ophoner.tools.impl

/**
 * One left-to-right pass over a command. The first reason in [REASONS] wins,
 * which is the order the old deny expressions were tried in. Each pattern keeps
 * a constant amount of state per character, so a long miss stays linear.
 */
internal object ShellDenyScan {
    fun deny(text: String): String? {
        var best = REASONS.size
        var ddOpen = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '|') ddOpen = false
            if (isWord(c) && boundary(text, i)) {
                when {
                    startsWith(text, i, "dd") && boundaryAfter(text, i + 2) && hasWs(text, i + 2) -> ddOpen = true
                    else -> keyword(text, i)?.let { hit -> if (hit < best) best = hit }
                }
            }
            if (ddOpen && startsWith(text, i, "if=") && boundary(text, i)) {
                if (DD < best) best = DD
            }
            if (c == ':' && forkBomb(text, i) && FORK < best) best = FORK
            if (c == '>') redirect(text, i)?.let { hit -> if (hit < best) best = hit }
            if (c == '/') dataPath(text, i)?.let { hit -> if (hit < best) best = hit }
            if (best == 0) return REASONS[0]
            i++
        }
        return if (best == REASONS.size) null else REASONS[best]
    }

    private fun keyword(text: String, i: Int): Int? = when {
        rm(text, i) != null -> rm(text, i)
        startsWith(text, i, "mkfs") && (at(text, i + 4) == '.' || isAsciiWs(at(text, i + 4))) -> MKFS
        chmod(text, i) != null -> chmod(text, i)
        tee(text, i) -> TEE
        wordThenWs(text, i, "mount") -> MOUNT
        wordThenWs(text, i, "umount") -> UMOUNT
        wholeWord(text, i, "reboot") -> REBOOT
        wholeWord(text, i, "shutdown") -> SHUTDOWN
        wholeWord(text, i, "halt") -> HALT
        wordThenWs(text, i, "su") -> SU
        else -> null
    }

    /** Flag contains lowercase r and f, then a bare slash, or slash-star when r precedes f. */
    private fun rm(text: String, i: Int): Int? {
        if (!wordThenWs(text, i, "rm")) return null
        var j = skipWs(text, i + 2)
        if (at(text, j) != '-') return null
        if (at(text, j + 1) == '-') return null
        var k = j + 1
        var seenR = false
        var rBeforeF = false
        var hasF = false
        while (k < text.length && isAsciiLetter(text[k])) {
            when (text[k]) {
                'r' -> seenR = true
                'f' -> {
                    hasF = true
                    if (seenR) rBeforeF = true
                }
            }
            k++
        }
        if (k == j + 1 || !seenR || !hasF) return null
        if (!isAsciiWs(at(text, k))) return null
        val afterFlag = skipWs(text, k)
        if (at(text, afterFlag) == '-' && at(text, afterFlag + 1) == '-') {
            val afterDash = skipWs(text, afterFlag + 2)
            if (isAsciiWs(at(text, afterFlag + 2)) && slashEnd(text, afterDash)) return RM_DASH
        }
        if (slashEnd(text, afterFlag)) return RM_ROOT
        if (rBeforeF && at(text, afterFlag) == '/' && at(text, afterFlag + 1) == '*') return RM_STAR
        return null
    }

    private fun chmod(text: String, i: Int): Int? {
        if (!wordThenWs(text, i, "chmod")) return null
        val body = skipWs(text, i + "chmod".length)
        val flagged = flagThenWs(text, body)
        if (flagged != null && mode777End(text, flagged.second)) return CHMOD_ROOT
        if (mode777End(text, body)) return CHMOD_ROOT
        if (flagged != null && flagged.first == "-R" && mode777Slash(text, flagged.second)) return CHMOD_RECURSIVE
        return null
    }

    private fun tee(text: String, i: Int): Boolean {
        if (!wordThenWs(text, i, "tee")) return false
        val body = skipWs(text, i + 3)
        val flagged = flagThenWs(text, body)
        val path = flagged?.second ?: body
        return startsWith(text, path, "/system/")
    }

    /** `-[A-Za-z]*` followed by whitespace. Returns the flag text and the index after that whitespace. */
    private fun flagThenWs(text: String, i: Int): Pair<String, Int>? {
        if (at(text, i) != '-') return null
        var k = i + 1
        while (k < text.length && isAsciiLetter(text[k])) k++
        if (!isAsciiWs(at(text, k))) return null
        return text.substring(i, k) to skipWs(text, k)
    }

    private fun mode777End(text: String, i: Int): Boolean {
        if (!startsWith(text, i, "777") || !isAsciiWs(at(text, i + 3))) return false
        return slashEnd(text, skipWs(text, i + 3))
    }

    private fun mode777Slash(text: String, i: Int): Boolean {
        if (!startsWith(text, i, "777") || !isAsciiWs(at(text, i + 3))) return false
        return at(text, skipWs(text, i + 3)) == '/'
    }

    private fun slashEnd(text: String, i: Int): Boolean =
        at(text, i) == '/' && (i + 1 >= text.length || isAsciiWs(text[i + 1]))

    private fun redirect(text: String, i: Int): Int? {
        if (!redirectBoundary(text, i)) return null
        val double = at(text, i + 1) == '>'
        val path = skipWs(text, if (double) i + 2 else i + 1)
        if (!startsWith(text, path, "/system/")) return null
        return if (double) APPEND_SYSTEM else WRITE_SYSTEM
    }

    private fun redirectBoundary(text: String, i: Int): Boolean {
        if (i == 0) return true
        val p = text[i - 1]
        return isAsciiWs(p) || p == ';' || p == '&' || p == '|' || p == '`'
    }

    private fun dataPath(text: String, i: Int): Int? {
        if (!prefixOk(text, i)) return null
        if (startsWith(text, i, "/data/data/")) {
            return if (owned(text, i + "/data/data/".length)) null else DATA_DATA
        }
        val rest = userRest(text, i)
        if (rest < 0 || owned(text, rest)) return null
        return if (rest >= 0 && startsWith(text, i, "/data/user/")) DATA_USER else null
    }

    private fun userRest(text: String, i: Int): Int {
        val prefix = "/data/user/"
        if (!startsWith(text, i, prefix)) return -1
        var j = i + prefix.length
        val digits = j
        while (j < text.length && text[j] in '0'..'9') j++
        if (j == digits || at(text, j) != '/') return -1
        return j + 1
    }

    private fun owned(text: String, i: Int): Boolean {
        val name = "dev.ophoner"
        if (!startsWith(text, i, name)) return false
        var j = i + name.length
        if (startsWith(text, j, ".debug")) j += ".debug".length
        return j >= text.length || text[j] == '/'
    }

    private fun prefixOk(text: String, i: Int): Boolean {
        if (i == 0) return true
        val p = text[i - 1]
        return isAsciiWs(p) || p == ';' || p == '&' || p == '|' || p == '`' || p == '>'
    }

    private fun forkBomb(text: String, i: Int): Boolean {
        if (!startsWith(text, i, ":()")) return false
        var j = skipWs(text, i + 3)
        if (at(text, j) != '{') return false
        j = skipWs(text, j + 1)
        if (at(text, j) != ':') return false
        j = skipWs(text, j + 1)
        if (at(text, j) != '|') return false
        j = skipWs(text, j + 1)
        if (at(text, j) != ':') return false
        j = skipWs(text, j + 1)
        if (at(text, j) != '&') return false
        j = skipWs(text, j + 1)
        if (at(text, j) != '}') return false
        j = skipWs(text, j + 1)
        if (at(text, j) != ';') return false
        j = skipWs(text, j + 1)
        return at(text, j) == ':'
    }

    private fun wordThenWs(text: String, i: Int, word: String): Boolean =
        startsWith(text, i, word) && isAsciiWs(at(text, i + word.length))

    private fun wholeWord(text: String, i: Int, word: String): Boolean =
        startsWith(text, i, word) && boundaryAfter(text, i + word.length)

    private fun hasWs(text: String, i: Int): Boolean = isAsciiWs(at(text, i))

    private fun boundary(text: String, i: Int): Boolean = i == 0 || !isWord(text[i - 1])

    private fun boundaryAfter(text: String, i: Int): Boolean = i >= text.length || !isWord(text[i])

    private fun skipWs(text: String, start: Int): Int {
        var i = start
        while (i < text.length && isAsciiWs(text[i])) i++
        return i
    }

    private fun startsWith(text: String, i: Int, token: String): Boolean {
        if (i < 0 || i + token.length > text.length) return false
        for (n in token.indices) if (text[i + n] != token[n]) return false
        return true
    }

    private fun at(text: String, i: Int): Char = if (i in text.indices) text[i] else '\u0000'

    private fun isWord(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_'

    private fun isAsciiLetter(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z'

    private fun isAsciiWs(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    private const val RM_ROOT = 0
    private const val RM_DASH = 1
    private const val RM_STAR = 2
    private const val MKFS = 3
    private const val DD = 4
    private const val FORK = 5
    private const val CHMOD_ROOT = 6
    private const val CHMOD_RECURSIVE = 7
    private const val WRITE_SYSTEM = 8
    private const val APPEND_SYSTEM = 9
    private const val TEE = 10
    private const val MOUNT = 11
    private const val UMOUNT = 12
    private const val REBOOT = 13
    private const val SHUTDOWN = 14
    private const val HALT = 15
    private const val SU = 16
    private const val DATA_DATA = 17
    private const val DATA_USER = 18

    private val REASONS = listOf(
        "rm -rf targeting /",
        "rm -rf targeting /",
        "rm -rf targeting /*",
        "mkfs (filesystem format)",
        "dd with input file",
        "fork bomb",
        "chmod 777 on /",
        "recursive chmod 777 on /",
        "write into /system",
        "append into /system",
        "tee into /system",
        "mount operation",
        "umount operation",
        "reboot",
        "shutdown",
        "halt",
        "privilege escalation (su)",
        "access to another app's /data/data",
        "access to another app's /data/user",
    )
}
