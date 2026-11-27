package dev.ophoner.tools.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShellPolicyTest {
    @Test
    fun allowlistedReadsStayAllowed() {
        for (command in listOf("ls", "echo hello", "cat readme.md", "echo su", "grep mount", "ps aux")) {
            assertTrue(command, ShellPolicy.classify(command) is ShellPolicy.Decision.Allow)
        }
    }

    @Test
    fun disguisedRootDeletesAndForeignDataPathsAreRejected() {
        val blocked = listOf(
            "su",
            "sudo su",
            "sudo -u root su",
            "busybox su",
            "toybox su",
            "/bin/su",
            "FOO=1 su",
            "echo \$(su)",
            "echo `su`",
            "sh -c su",
            "sh -c \"rm -rf /.\"",
            "ls; su",
            "poweroff",
            "mount /data",
            "rm -rf /.",
            "rm -rf //",
            "rm -rf /./",
            "rm -rf /tmp/..",
            "rm -rf -- /",
            "rm -rf -- /.",
            "cat /data/data/dev.ophoner/../com.other/secret",
            "echo halt",
        )
        for (command in blocked) {
            assertTrue(command, ShellPolicy.classify(command) is ShellPolicy.Decision.Reject)
        }
    }

    @Test
    fun ordinaryDeletesStayUnscoped() {
        val decision = ShellPolicy.classify("rm -rf /sdcard/notes")
        assertTrue(decision is ShellPolicy.Decision.Allow)
        assertTrue((decision as ShellPolicy.Decision.Allow).category == "unscoped")
    }

    @Test
    fun fuzzDoesNotThrowAndPreservesFixedCases() {
        val random = Random(20261102)
        val allowed = listOf("ls", "echo hello", "cat readme.md")
        val blocked = listOf("su", "rm -rf /.", "rm -rf //", "sudo su", "cat /data/data/dev.ophoner/../com.other/a")
        for (command in allowed) {
            assertTrue(ShellPolicy.classify(command) is ShellPolicy.Decision.Allow)
            assertTrue(ShellPolicy.classify("  $command  ") is ShellPolicy.Decision.Allow)
        }
        for (command in blocked) {
            assertTrue(ShellPolicy.classify(command) is ShellPolicy.Decision.Reject)
            assertTrue(ShellPolicy.classify(command.replace(" ", "  ")) is ShellPolicy.Decision.Reject)
        }
        val alphabet = "abcdefghijklmnopqrstuvwxyz /._-;|&$()`'\"=\\"
        repeat(1500) {
            val command = buildString {
                repeat(random.nextInt(48)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            ShellPolicy.classify(command)
            assertEquals(legacyDeny(command), ShellDenyScan.deny(command))
        }
    }

    @Test fun denyScanMatchesTheOldExpressions() {
        val cases = listOf(
            "rm -rf /",
            "rm -fr /",
            "rm -RF /",
            "rm -rf2 /",
            "rm -fr /*",
            "rm -rf /*",
            "rm -rf -- /",
            "rm -rf /tmp",
            "echo halt",
            "echo su",
            "echo poweroff",
            "chmod 777 /",
            "chmod 777 /tmp",
            "chmod -R 777 /",
            "chmod -R 777 /tmp",
            "dd if=/dev/zero of=x",
            "dd of=x",
            "dd | if=/dev/zero",
            "cat /data/data/com.other/secret",
            "cat /data/data/dev.ophoner/files",
            "cat /data/data/dev.ophoner.debug/files",
            "cat /data/data/dev.ophoner.debug.extra",
            "cat /data/user/0/com.other/a",
            "cat /data/user/0/dev.ophoner/a",
            "echo>/system/build.prop",
            " >/system/build.prop",
            ">>/system/build.prop",
            "tee /system/bin/x",
            "mkfs.ext4 /dev/block",
            ":(){ :|:& };:",
            "reboot",
            "rebooted",
            "su -",
            "sudo ls",
        )
        for (command in cases) {
            assertEquals(command, legacyDeny(command), ShellDenyScan.deny(command))
        }
        assertNull(ShellDenyScan.deny("echo hello"))
    }

    @Test fun longMissesStayLinear() {
        val started = System.nanoTime()
        assertNull(ShellDenyScan.deny("rm -" + "r".repeat(20_000) + " /"))
        assertNull(ShellDenyScan.deny("rm -" + "rf".repeat(800) + " /x"))
        assertNull(ShellDenyScan.deny("dd ".repeat(20_000)))
        assertTrue(System.nanoTime() - started < 500_000_000L)
    }
}

private val LEGACY_DENY = listOf(
    Regex("""\brm\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)\s+/(\s|$)""") to "rm -rf targeting /",
    Regex("""\brm\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)\s+--\s+/(\s|$)""") to "rm -rf targeting /",
    Regex("""\brm\s+-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*\s+/\*""") to "rm -rf targeting /*",
    Regex("""\bmkfs(\.|\s)""") to "mkfs (filesystem format)",
    Regex("""\bdd\s+[^|]*\bif=""") to "dd with input file",
    Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:""") to "fork bomb",
    Regex("""\bchmod\s+(-[a-zA-Z]*\s+)?777\s+/(\s|$)""") to "chmod 777 on /",
    Regex("""\bchmod\s+-R\s+777\s+/""") to "recursive chmod 777 on /",
    Regex("""(^|[\s;&|`])>\s*/system/""") to "write into /system",
    Regex("""(^|[\s;&|`])>>\s*/system/""") to "append into /system",
    Regex("""\btee\s+(-[a-zA-Z]+\s+)?/system/""") to "tee into /system",
    Regex("""\bmount\s+""") to "mount operation",
    Regex("""\bumount\s+""") to "umount operation",
    Regex("""\breboot\b""") to "reboot",
    Regex("""\bshutdown\b""") to "shutdown",
    Regex("""\bhalt\b""") to "halt",
    Regex("""\bsu\s+(-|\w+)?""") to "privilege escalation (su)",
    Regex("""(^|[\s;&|`>])(>|>>|tee\s+\S*\s+)?/data/data/(?!dev\.ophoner(\.debug)?(/|$))""") to
        "access to another app's /data/data",
    Regex("""(^|[\s;&|`>])(>|>>|tee\s+\S*\s+)?/data/user/\d+/(?!dev\.ophoner(\.debug)?(/|$))""") to
        "access to another app's /data/user",
)

private fun legacyDeny(command: String): String? {
    for ((pattern, reason) in LEGACY_DENY) {
        if (pattern.containsMatchIn(command)) return reason
    }
    return null
}
