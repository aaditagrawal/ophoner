package dev.ophoner.tools.impl

/**
 * Shell allow/deny classification that does not depend on Android.
 * Existing deny expressions are unchanged. Path collapsing and command-position
 * checks only add rejections.
 */
internal object ShellPolicy {
    sealed class Decision {
        data class Allow(val category: String) : Decision()
        data class Reject(val reason: String) : Decision()
    }

    fun classify(command: String): Decision = classify(command, depth = 0)

    private fun classify(command: String, depth: Int): Decision {
        if (depth > MAX_DEPTH) return Decision.Reject("nested shell too deep")
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return Decision.Reject("empty command")
        if (trimmed.contains('\u0000')) return Decision.Reject("null byte in command")

        val normalized = normalizeShellPaths(trimmed)
        for (candidate in listOf(trimmed, normalized).distinct()) {
            ShellDenyScan.deny(candidate)?.let { return Decision.Reject(it) }
        }
        Scanner(trimmed, depth).scanScript()?.let { return Decision.Reject(it) }

        val firstToken = firstCommandToken(trimmed) ?: trimmed.substringBefore(' ')
        if (firstToken in ALLOW_FIRST_TOKENS) return Decision.Allow("allowlist")
        return Decision.Allow("unscoped")
    }

    private fun firstCommandToken(command: String): String? {
        var start = 0
        while (start < command.length && command[start].isWhitespace()) start++
        while (start < command.length) {
            val space = command.indexOf(' ', start)
            if (space < 0) return command.substring(start)
            if (isAssignmentRange(command, start, space) ||
                rangeEquals(command, start, space, "sudo") ||
                rangeEquals(command, start, space, "env")
            ) {
                start = space + 1
                while (start < command.length && command[start].isWhitespace()) start++
                continue
            }
            return command.substring(start, space)
        }
        return null
    }

    private fun rangeEquals(text: String, start: Int, end: Int, token: String): Boolean {
        if (end - start != token.length) return false
        for (i in token.indices) if (text[start + i] != token[i]) return false
        return true
    }

    /** Same as `^[A-Za-z_][A-Za-z0-9_]*=.*` over [start, end), without copying the tail. */
    private fun isAssignmentRange(text: String, start: Int, end: Int): Boolean {
        if (start >= end) return false
        val first = text[start]
        if (first !in 'A'..'Z' && first !in 'a'..'z' && first != '_') return false
        var i = start + 1
        while (i < end) {
            val c = text[i]
            if (c == '=') return true
            if (c !in 'A'..'Z' && c !in 'a'..'z' && c !in '0'..'9' && c != '_') return false
            i++
        }
        return false
    }

    private class Scanner(private val text: String, private val depth: Int) {
        private var index = 0
        private var pending: String? = null
        private var steps = 0

        private fun stalled(): String? {
            steps++
            return if (steps > text.length * 8 + 32) "shell command could not be classified" else null
        }

        fun scanScript(stopAtParen: Boolean = false): String? {
            while (index < text.length) {
                stalled()?.let { return it }
                skipWs()
                if (index >= text.length) return pending
                if (text[index] == ')') {
                    if (stopAtParen) return pending
                    index++
                    continue
                }
                if (isSeparator(text[index])) {
                    index++
                    continue
                }
                scanCommand(stopAtParen)?.let { return it }
            }
            return pending
        }

        private fun scanCommand(stopAtParen: Boolean): String? {
            while (index < text.length) {
                stalled()?.let { return it }
                skipWs()
                if (index >= text.length || isSeparator(text[index])) return pending
                if (text[index] == '(') {
                    index++
                    scanScript(stopAtParen = true)?.let { return it }
                    if (index < text.length && text[index] == ')') index++
                    continue
                }
                if (text[index] == ')') {
                    if (stopAtParen) return pending
                    index++
                    continue
                }
                val before = index
                val word = readWord() ?: return pending
                if (index == before) index++
                pending?.let { return it }
                if (word.isEmpty()) continue
                if (ASSIGNMENT.matches(word)) continue
                val base = basename(word)
                val flagArgs = WRAPPER_FLAG_ARGS[base]
                if (flagArgs != null) {
                    skipWrapperFlags(flagArgs)?.let { return it }
                    continue
                }
                if (base in SHELLS) return scanShell()
                dangerousReason(base)?.let { return it }
                return skipRest()
            }
            return pending
        }

        private fun scanShell(): String? {
            while (index < text.length) {
                stalled()?.let { return it }
                skipWs()
                if (index >= text.length || isSeparator(text[index])) return pending
                if (text[index] == '-') {
                    val flag = readWord() ?: return pending
                    pending?.let { return it }
                    val requestsScript = flag == "-c" || flag == "--command" ||
                        (flag.startsWith("-") && !flag.startsWith("--") && 'c' in flag)
                    if (!requestsScript) continue
                    skipWs()
                    if (index >= text.length || isSeparator(text[index])) return "shell -c missing script"
                    val script = readWord() ?: return "shell -c missing script"
                    pending?.let { return it }
                    val nested = classify(script, depth + 1)
                    if (nested is Decision.Reject) return nested.reason
                    return skipRest()
                }
                return skipRest()
            }
            return pending
        }

        private fun skipWrapperFlags(flagArgs: Set<String>): String? {
            while (true) {
                stalled()?.let { return it }
                skipWs()
                if (index >= text.length || isSeparator(text[index]) || text[index] != '-') return pending
                val flag = readWord() ?: return pending
                pending?.let { return it }
                if (flag == "--") return pending
                val name = flag.substringBefore('=')
                if (name in flagArgs && '=' !in flag) {
                    skipWs()
                    if (index < text.length && !isSeparator(text[index]) && text[index] != '-') {
                        readWord()
                        pending?.let { return it }
                    }
                }
            }
        }

        private fun skipRest(): String? {
            while (index < text.length && !isSeparator(text[index])) {
                stalled()?.let { return it }
                if (text[index].isWhitespace() || text[index] == ')') {
                    index++
                    continue
                }
                if (text[index] == '(') {
                    index++
                    scanScript(stopAtParen = true)?.let { return it }
                    if (index < text.length && text[index] == ')') index++
                    continue
                }
                val before = index
                readWord()
                pending?.let { return it }
                if (index == before) index++
            }
            return pending
        }

        private fun readWord(): String? {
            if (index >= text.length) return null
            if (text[index] == '\'' || text[index] == '"') {
                val quote = text[index]
                index++
                val body = StringBuilder()
                while (index < text.length && text[index] != quote) {
                    if (quote == '"' && startsSubstitution()) {
                        scanSubstitution()?.let { pending = pending ?: it }
                    } else {
                        body.append(text[index])
                        index++
                    }
                }
                if (index < text.length && text[index] == quote) index++
                return body.toString()
            }
            val start = index
            val body = StringBuilder()
            while (index < text.length &&
                !text[index].isWhitespace() &&
                !isSeparator(text[index]) &&
                text[index] != '\'' &&
                text[index] != '"' &&
                text[index] != '(' &&
                text[index] != ')'
            ) {
                if (startsSubstitution()) {
                    scanSubstitution()?.let { pending = pending ?: it }
                } else {
                    body.append(text[index])
                    index++
                }
            }
            if (index == start) return null
            return body.toString()
        }

        private fun startsSubstitution(): Boolean {
            if (index >= text.length) return false
            if (text[index] == '`') return true
            return text[index] == '$' && index + 1 < text.length && text[index + 1] == '('
        }

        private fun scanSubstitution(): String? {
            if (text[index] == '`') {
                index++
                val start = index
                while (index < text.length && text[index] != '`') index++
                val inner = text.substring(start, index)
                if (index < text.length && text[index] == '`') index++
                val nested = classify(inner, depth + 1)
                return (nested as? Decision.Reject)?.reason
            }
            index += 2
            val start = index
            var depthParen = 1
            var quote: Char? = null
            while (index < text.length && depthParen > 0) {
                val c = text[index]
                if (quote != null) {
                    if (c == quote) quote = null
                    index++
                    continue
                }
                when (c) {
                    '\'', '"' -> quote = c
                    '(' -> depthParen++
                    ')' -> depthParen--
                }
                if (depthParen > 0) index++
            }
            val inner = text.substring(start, index)
            if (index < text.length && text[index] == ')') index++
            val nested = classify(inner, depth + 1)
            return (nested as? Decision.Reject)?.reason
        }

        private fun skipWs() {
            while (index < text.length && text[index].isWhitespace()) index++
        }
    }

    private fun basename(token: String): String {
        val slash = token.lastIndexOf('/')
        return (if (slash >= 0) token.substring(slash + 1) else token).lowercase()
    }

    private fun dangerousReason(base: String): String? = when {
        base == "su" -> "privilege escalation (su)"
        base == "mount" -> "mount operation"
        base == "umount" -> "umount operation"
        base == "reboot" -> "reboot"
        base == "shutdown" -> "shutdown"
        base == "halt" -> "halt"
        base == "poweroff" -> "poweroff"
        base == "mkfs" || base.startsWith("mkfs.") -> "mkfs (filesystem format)"
        else -> null
    }

    private fun isSeparator(c: Char): Boolean = c == ';' || c == '&' || c == '|' || c == '\n'

    private fun normalizeShellPaths(command: String): String {
        val out = StringBuilder(command.length)
        var index = 0
        while (index < command.length) {
            if (command[index].isWhitespace()) {
                out.append(command[index])
                index++
                continue
            }
            val start = index
            while (index < command.length && !command[index].isWhitespace()) index++
            out.append(normalizeToken(command.substring(start, index)))
        }
        return out.toString()
    }

    private fun normalizeToken(token: String): String {
        var slash = -1
        for (at in token.indices) {
            if (token[at] == '/' && (at == 0 || !token[at - 1].isLetterOrDigit())) {
                slash = at
                break
            }
        }
        if (slash < 0) return token
        val pathAndSuffix = token.substring(slash)
        val cut = pathAndSuffix.indexOfFirst { it in TOKEN_SUFFIX }
        val core = if (cut >= 0) pathAndSuffix.substring(0, cut) else pathAndSuffix
        val suffix = if (cut >= 0) pathAndSuffix.substring(cut) else ""
        val collapsed = collapseAbsolute(core)
        val rejoined = if (suffix.isEmpty()) collapsed else "$collapsed $suffix"
        return token.substring(0, slash) + rejoined
    }

    private fun collapseAbsolute(path: String): String {
        val segments = ArrayDeque<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.addLast(part)
            }
        }
        return "/" + segments.joinToString("/")
    }

    private val ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*")
    private val TOKEN_SUFFIX = setOf(';', '&', '|', '`', '"', '\'', '(', ')', '<', '>')

    private val ALLOW_FIRST_TOKENS = setOf(
        "ls", "pwd", "cat", "grep", "find", "echo", "which", "ps", "cd",
        "head", "tail", "wc", "stat", "df", "du", "date", "uname", "env",
        "printenv", "whoami",
    )

    private val SHELLS = setOf("sh", "bash", "ash", "dash", "zsh", "ksh", "mksh")

    private val WRAPPER_FLAG_ARGS = mapOf(
        "sudo" to setOf("-u", "-g", "--user", "--group", "-p", "--prompt"),
        "env" to setOf("-u", "--unset"),
        "nice" to setOf("-n", "--adjustment"),
        "stdbuf" to setOf("-i", "-o", "-e", "--input", "--output", "--error"),
        "ionice" to setOf("-c", "-n", "-p", "--class", "--classdata", "--pid"),
        "command" to emptySet(),
        "nohup" to emptySet(),
        "time" to emptySet(),
        "busybox" to emptySet(),
        "toybox" to emptySet(),
    )

    private const val MAX_DEPTH = 8
}
