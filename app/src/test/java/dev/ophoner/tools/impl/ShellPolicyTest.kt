package dev.ophoner.tools.impl

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
        }
    }
}
