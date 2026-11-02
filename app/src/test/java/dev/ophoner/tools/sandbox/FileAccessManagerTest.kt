package dev.ophoner.tools.sandbox

import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.tools.ToolExecutionContext
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileAccessManagerTest {
    @Test fun flatListingIsOneQueryAndALaterEditIsVisible() = runBlocking {
        val tree = MemoryDocumentTree()
        repeat(40) { tree.addFile("root", "f$it.txt", "x$it") }
        val file = tree.addFile("root", "note.txt", "one")
        val manager = manager(tree)
        withContext(ToolExecutionContext(rootUri = tree.uri)) {
            val listed = manager.listDirectory(".")
            assertEquals(41, listed.files.size)
            assertFalse(listed.truncated)
            assertEquals(1, tree.listCalls)
            assertEquals("one", manager.readFile("note.txt").content)
            tree.setText(file, "two")
            assertEquals("two", manager.readFile("note.txt").content)
        }
        assertTrue(tree.listCalls > 1)
    }

    @Test fun nestedReadCostsOneQueryPerSegment() = runBlocking {
        val tree = MemoryDocumentTree()
        val dir = tree.addDir("root", "a")
        tree.addFile(dir, "b.txt", "body")
        val manager = manager(tree)
        withContext(ToolExecutionContext(rootUri = tree.uri)) {
            assertEquals("body", manager.readFile("a/b.txt").content)
        }
        assertEquals(2, tree.listCalls)
    }

    @Test fun recursiveListingQueriesEachDirectoryOnce() = runBlocking {
        val tree = MemoryDocumentTree()
        val dir = tree.addDir("root", "sub")
        repeat(5) { tree.addFile(dir, "f$it.txt", "x") }
        val manager = manager(tree)
        withContext(ToolExecutionContext(rootUri = tree.uri)) {
            val listed = manager.listDirectory(".", recursive = true)
            assertEquals(6, listed.files.size)
            assertEquals("sub/f0.txt", listed.files.first { it.name == "f0.txt" }.path)
        }
        assertEquals(2, tree.listCalls)
    }

    @Test fun providerErrorIsNotAnEmptyDirectory() = runBlocking {
        val tree = MemoryDocumentTree()
        tree.addFile("root", "a.txt", "secret")
        tree.errorMessage = "provider exploded"
        val manager = manager(tree)
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                withContext(ToolExecutionContext(rootUri = tree.uri)) {
                    manager.listDirectory(".")
                }
            }
        }
        assertTrue(error.message!!.contains("provider exploded"))
    }

    @Test fun partialPageDoesNotInventAMissingFile() = runBlocking {
        val tree = MemoryDocumentTree()
        tree.addFile("root", "seen.txt", "yes")
        tree.incompleteIds = setOf("root")
        val manager = manager(tree)
        withContext(ToolExecutionContext(rootUri = tree.uri)) {
            val listed = manager.listDirectory(".")
            assertTrue(listed.truncated)
            assertEquals(listOf("seen.txt"), listed.files.map { it.name })
            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking(coroutineContext) { manager.writeFile("new.txt", "nope") }
            }
            assertTrue(error.message!!.contains("incomplete"))
        }
        assertEquals(0, tree.createCalls)
    }

    @Test fun traversalAndForeignTreesDoNotQueryChildren() = runBlocking {
        val tree = MemoryDocumentTree()
        tree.addFile("root", "a.txt", "secret")
        val manager = manager(tree, settingsUri = "content://other")
        val escaped = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                withContext(ToolExecutionContext(rootUri = tree.uri)) { manager.readFile("../secret") }
            }
        }
        assertTrue(escaped.message!!.contains("Invalid path"))
        val foreign = assertThrows(IllegalStateException::class.java) {
            runBlocking { manager.readFile("a.txt") }
        }
        assertTrue(foreign.message!!.contains("Invalid working directory"))
        assertEquals(0, tree.listCalls)
        withContext(ToolExecutionContext(rootUri = tree.uri)) {
            assertEquals("secret", manager.readFile("a.txt").content)
        }
    }

    @Test fun rootDeleteIsRefused() = runBlocking {
        val tree = MemoryDocumentTree()
        val manager = manager(tree)
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                withContext(ToolExecutionContext(rootUri = tree.uri)) { manager.deleteFile(".") }
            }
        }
        assertTrue(error.message!!.contains("working directory"))
    }

    @Test fun sameDirectoryRenameSkipsByteCopyUntilDestinationExists() = runBlocking {
        val tree = MemoryDocumentTree()
        tree.addFile("root", "a.txt", "hello")
        tree.addFile("root", "b.txt", "old")
        val manager = manager(tree)
        withContext(ToolExecutionContext(rootUri = tree.uri)) {
            manager.moveFile("a.txt", "c.txt")
            assertEquals(1, tree.renameCalls)
            assertEquals(0, tree.outputOpens)
            assertEquals("hello", manager.readFile("c.txt").content)
            manager.moveFile("b.txt", "c.txt")
            assertEquals(1, tree.renameCalls)
            assertEquals("old", manager.readFile("c.txt").content)
        }
        assertTrue(tree.outputOpens >= 1)
    }

    @Test fun renameFallsBackToCopyWhenTheProviderCannotRename() = runBlocking {
        val tree = MemoryDocumentTree()
        tree.addFile("root", "a.txt", "hello", supportsRename = false)
        tree.addDir("root", "sub")
        tree.addFile("root", "cross.txt", "cross")
        val manager = manager(tree)
        withContext(ToolExecutionContext(rootUri = tree.uri)) {
            manager.moveFile("a.txt", "b.txt")
            assertEquals("hello", manager.readFile("b.txt").content)
            manager.moveFile("cross.txt", "sub/cross.txt")
            assertEquals("cross", manager.readFile("sub/cross.txt").content)
        }
        assertEquals(0, tree.renameCalls)
        assertTrue(tree.outputOpens >= 2)
    }

    @Test fun listingStopsAtTheEntryCap() = runBlocking {
        val tree = MemoryDocumentTree()
        repeat(10) { tree.addFile("root", "f$it.txt", "x") }
        val manager = manager(tree)
        withContext(ToolExecutionContext(rootUri = tree.uri)) {
            val listed = manager.listDirectory(".", maxEntries = 3)
            assertEquals(3, listed.files.size)
            assertTrue(listed.truncated)
        }
        assertEquals(1, tree.listCalls)
    }

    @Test fun writeCreatesMissingParentsAndReadsAWindow() = runBlocking {
        val tree = MemoryDocumentTree()
        val manager = manager(tree)
        withContext(ToolExecutionContext(rootUri = tree.uri)) {
            manager.writeFile("dir/note.txt", "abcdef")
            val window = manager.readFile("dir/note.txt", offset = 2, limit = 3)
            assertEquals("cde", window.content)
            assertTrue(window.truncated)
            assertEquals(2, window.startedAtOffset)
        }
    }

    private fun manager(tree: MemoryDocumentTree, settingsUri: String? = null): FileAccessManager {
        val settings = mockk<SettingsRepository>()
        every { settings.observeWorkingDirectoryUri() } returns flowOf(settingsUri)
        return FileAccessManager(settings, tree)
    }
}
