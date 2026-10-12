package dev.ophoner.device

import dev.ophoner.tools.ToolExecutionContext
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class DeviceBoundariesTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun `shared paths reject traversal and symlink escapes`() {
        val root = directory.newFolder("shared")
        val outside = directory.newFolder("private")
        assertEquals(File(root, "Downloads/note.txt"), sharedFile(root, "Downloads/note.txt"))
        assertThrows(IllegalArgumentException::class.java) { sharedFile(root, "../private") }
        assertThrows(IllegalArgumentException::class.java) { sharedFile(root, outside.path) }
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { sharedFile(root, "escape/key") }
        assertThrows(IllegalArgumentException::class.java) { sharedFile(root, "Android/data/com.example/data") }
        assertThrows(IllegalArgumentException::class.java) { sharedFile(root, "Android/obb/com.example/data") }
    }
    @Test fun `shell values remain literal and package names cannot inject commands`() {
        val value = "it's \$(touch bad); echo unsafe"
        val result = CommandRunner().execute("printf '%s' ${shellArgument(value)}", 2000)
        assertEquals(value, result[0])
        assertEquals("0", result[2])
        assertThrows(IllegalArgumentException::class.java) { checkedPackage("com.example; reboot") }
        assertEquals("com.example.app", checkedPackage("com.example.app"))
    }
    @Test fun `shell captures both pipes and bounds output`() {
        val result = CommandRunner().execute("i=0; while [ \"\$i\" -lt 2000 ]; do echo abcdefghijklmnopqrstuvwxyz; echo abcdefghijklmnopqrstuvwxyz >&2; i=\$((i+1)); done", 5000)
        assertEquals("0", result[2])
        assertTrue(result[0].contains("truncated"))
        assertTrue(result[1].contains("truncated"))
        assertTrue(result[0].length < 49_000)
    }
    @Test fun `shell deadlines stop a running process`() {
        val start = System.nanoTime()
        val result = CommandRunner().execute("exec sleep 20", 150)
        assertEquals("124", result[2])
        assertTrue((System.nanoTime() - start) / 1_000_000 < 4000)
    }
    @Test fun `approval is exact once and cancellation removes pending requests`() = runTest {
        val approval = ActionApproval()
        val args = buildJsonObject { put("action", "reply"); put("text", "hello") }
        val result = async { approval.approve("notifications", args) }
        testScheduler.runCurrent()
        assertEquals(1, approval.pending.value.size)
        approval.respond(approval.pending.value.single().id, true)
        assertTrue(result.await())
        assertTrue(approval.pending.value.isEmpty())
        val next = async { approval.approve("notifications", args) }
        testScheduler.runCurrent()
        assertEquals(1, approval.pending.value.size)
        next.cancelAndJoin()
        assertTrue(approval.pending.value.isEmpty())
    }
    @Test fun `read operations do not prompt while writes do`() {
        fun args(action: String) = buildJsonObject { put("action", action) }
        assertFalse(needsApproval("calendar", args("events")))
        assertTrue(needsApproval("calendar", args("delete")))
        assertFalse(needsApproval("notifications", args("list")))
        assertTrue(needsApproval("notifications", args("reply")))
        assertTrue(needsApproval("shell_execute", buildJsonObject { put("command", "echo hello; rm file") }))
    }
    @Test fun `yolo skips confirmations but cannot enable device capabilities`() = runTest {
        val access = mockk<DeviceAccess>()
        every { access.requireEnabled(DeviceCapability.CONTACTS) } throws IllegalStateException("Contacts disabled")
        val tool = ContactsTool(access)
        withContext(ToolExecutionContext(yoloMode = true)) {
            assertTrue(ActionApproval().approve("contacts", buildJsonObject { put("action", "create") }))
            val result = tool.execute("test", buildJsonObject { put("action", "create"); put("name", "Test") })
            assertTrue(result.isError)
            assertEquals("Contacts disabled", result.output)
        }
    }
    @Test fun `folder context rejects broad file access before touching storage`() = runTest {
        val access = mockk<DeviceAccess>()
        every { access.requireEnabled(DeviceCapability.STORAGE) } returns Unit
        withContext(ToolExecutionContext(rootUri = "content://folder", yoloMode = true)) {
            val result = SharedFilesTool(access).execute("test", buildJsonObject { put("action", "read"); put("path", "secret") })
            assertTrue(result.isError)
            assertTrue(result.output.contains("folder chats"))
        }
    }
}
