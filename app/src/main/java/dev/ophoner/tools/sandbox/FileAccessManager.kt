package dev.ophoner.tools.sandbox

import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.tools.ToolExecutionContext
import dev.ophoner.tools.ToolOutputLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

data class FileInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
)

data class FileListResult(
    val files: List<FileInfo>,
    val truncated: Boolean,
)

data class FileReadResult(
    val content: String,
    val truncated: Boolean,
    val totalCharsRead: Int,
    val startedAtOffset: Int,
)

@Singleton
class FileAccessManager @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val documents: DocumentTree,
) {
    /**
     * Resolves the active SAF root: per-run [ToolExecutionContext.rootUri] if set,
     * otherwise the global settings working directory.
     */
    private suspend fun activeTreeUri(): String? {
        val overrideUri = currentCoroutineContext()[ToolExecutionContext]?.rootUri
            ?.takeIf { it.isNotBlank() }
        return overrideUri ?: settingsRepository.observeWorkingDirectoryUri().firstOrNull()
    }

    suspend fun readFile(
        relativePath: String,
        offset: Int = 0,
        limit: Int? = null,
        maxChars: Int = ToolOutputLimits.FILE_READ_MAX_CHARS,
    ): FileReadResult = withTree { treeUri, root ->
        val node = resolveExisting(treeUri, root, relativePath, "File not found: $relativePath")
        if (node.entry.isDirectory) throw IllegalArgumentException("Path is a directory: $relativePath")

        val safeOffset = offset.coerceAtLeast(0)
        val requestedLimit = limit?.coerceAtLeast(0)
        val charsToRead = when {
            requestedLimit != null -> minOf(requestedLimit, maxChars)
            else -> maxChars
        }
        val readBudget = charsToRead + 1
        val builder = StringBuilder()
        val input = documents.openInput(treeUri, node.entry.documentId)
            ?: throw IllegalStateException("Cannot read file: $relativePath")
        input.use { stream -> readChars(stream, safeOffset, readBudget, builder) }
        val hasMore = builder.length > charsToRead
        val content = if (hasMore) builder.substring(0, charsToRead) else builder.toString()
        FileReadResult(
            content = content,
            truncated = hasMore,
            totalCharsRead = content.length,
            startedAtOffset = safeOffset,
        )
    }

    suspend fun writeFile(relativePath: String, content: String): Unit = withTree { treeUri, root ->
        val segments = relativeSegments(relativePath) ?: throw IllegalArgumentException("Invalid path: $relativePath")
        if (segments.isEmpty()) throw IllegalArgumentException("Invalid path: $relativePath")
        val parent = openParent(treeUri, root, segments.dropLast(1), create = true, label = relativePath)
        val fileName = segments.last()
        if (parent.page.incomplete && fileName !in parent.indexed) {
            throw IllegalStateException("Directory listing incomplete; retry")
        }
        val existing = parent.indexed[fileName]
        if (existing?.isDirectory == true) throw IllegalArgumentException("Path is a directory: $relativePath")
        val target = existing ?: documents.createFile(
            treeUri,
            parent.entry.documentId,
            "application/octet-stream",
            fileName,
        ) ?: throw IllegalStateException("Cannot create file: $relativePath")
        val output = documents.openOutput(treeUri, target.documentId, "wt")
            ?: throw IllegalStateException("Cannot write file: $relativePath")
        output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    suspend fun listDirectory(
        relativePath: String,
        recursive: Boolean = false,
        maxEntries: Int = ToolOutputLimits.FILE_LIST_MAX_ENTRIES,
    ): FileListResult = withTree { treeUri, root ->
        val limit = maxEntries.coerceAtLeast(0)
        if (limit == 0) return@withTree FileListResult(files = emptyList(), truncated = true)
        val segments = relativeSegments(relativePath) ?: throw IllegalArgumentException("Invalid path: $relativePath")
        val dir = if (segments.isEmpty()) {
            Node(root, parentId = null)
        } else {
            resolveExisting(treeUri, root, relativePath, "Directory not found: $relativePath")
        }
        if (!dir.entry.isDirectory) throw IllegalArgumentException("Not a directory: $relativePath")
        val files = ArrayList<FileInfo>(minOf(limit, 64))
        val basePath = segments.joinToString("/")
        val truncated = listRecursive(treeUri, dir.entry, basePath, recursive, limit, files)
        FileListResult(files = files, truncated = truncated)
    }

    suspend fun deleteFile(relativePath: String): Boolean = withTree { treeUri, root ->
        val node = resolveExisting(treeUri, root, relativePath, "File not found: $relativePath")
        if (node.entry.documentId == root.documentId) {
            throw IllegalArgumentException("Refusing to delete the working directory root")
        }
        documents.delete(treeUri, node.entry.documentId)
    }

    suspend fun moveFile(from: String, to: String): Unit = withTree { treeUri, root ->
        val fromSegments = relativeSegments(from) ?: throw IllegalArgumentException("Invalid path: $from")
        val toSegments = relativeSegments(to) ?: throw IllegalArgumentException("Invalid path: $to")
        if (fromSegments.isEmpty()) throw IllegalArgumentException("Invalid path: $from")
        if (toSegments.isEmpty()) throw IllegalArgumentException("Invalid path: $to")
        if (fromSegments == toSegments) {
            throw IllegalArgumentException("Source and destination are the same path: $from")
        }
        val sourceParent = openParent(treeUri, root, fromSegments.dropLast(1), create = false, label = from)
        val source = sourceParent.indexed[fromSegments.last()]
            ?: if (sourceParent.page.incomplete) {
                throw IllegalStateException("Directory listing incomplete; retry")
            } else {
                throw IllegalArgumentException("File not found: $from")
            }
        if (source.isDirectory) throw IllegalArgumentException("Cannot move directory: $from")
        val destParent = if (fromSegments.dropLast(1) == toSegments.dropLast(1)) {
            sourceParent
        } else {
            openParent(treeUri, root, toSegments.dropLast(1), create = true, label = to)
        }
        val fileName = toSegments.last()
        if (destParent.page.incomplete && fileName !in destParent.indexed) {
            throw IllegalStateException("Directory listing incomplete; retry")
        }
        val existing = destParent.indexed[fileName]
        if (existing != null && existing.documentId == source.documentId) {
            throw IllegalArgumentException("Source and destination resolve to the same file")
        }
        if (existing?.isDirectory == true) {
            throw IllegalArgumentException("Cannot overwrite a directory: $to")
        }
        val sameDirectory = sourceParent.entry.documentId == destParent.entry.documentId
        if (existing == null && sameDirectory && source.supportsRename &&
            documents.rename(treeUri, source.documentId, fileName)
        ) {
            return@withTree
        }
        val target = existing ?: documents.createFile(
            treeUri,
            destParent.entry.documentId,
            source.mimeType.ifBlank { "application/octet-stream" },
            fileName,
        ) ?: throw IllegalStateException("Cannot create file: $to")
        val input = documents.openInput(treeUri, source.documentId)
            ?: throw IllegalStateException("Cannot read file: $from")
        input.use { inn ->
            val output = documents.openOutput(treeUri, target.documentId, "wt")
                ?: throw IllegalStateException("Cannot write file: $to")
            output.use { inn.copyTo(it) }
        }
        if (!documents.delete(treeUri, source.documentId)) {
            throw IllegalStateException("Copied to $to but failed to delete source $from (both copies may exist)")
        }
    }

    suspend fun hasWorkingDirectory(): Boolean {
        val overrideUri = currentCoroutineContext()[ToolExecutionContext]?.rootUri
        if (!overrideUri.isNullOrBlank()) return true
        return settingsRepository.observeWorkingDirectoryUri().firstOrNull() != null
    }

    private suspend fun <T> withTree(block: (treeUri: String, root: DocumentEntry) -> T): T =
        withContext(Dispatchers.IO) {
            val treeUri = activeTreeUri() ?: throw IllegalStateException("No working directory set")
            val rootId = documents.rootDocumentId(treeUri)
                ?: throw IllegalStateException("Invalid working directory")
            block(treeUri, DocumentEntry(rootId, name = "", mimeType = DOCUMENT_MIME_DIR, size = 0L, supportsRename = false))
        }

    private fun children(treeUri: String, parentId: String): DirectoryPage {
        val page = documents.listChildren(treeUri, parentId)
        if (page.error != null) throw IllegalStateException(page.error)
        return page
    }

    private fun resolveExisting(treeUri: String, root: DocumentEntry, path: String, missing: String): Node {
        val segments = relativeSegments(path) ?: throw IllegalArgumentException("Invalid path: $path")
        if (segments.isEmpty()) return Node(root, parentId = null)
        val parent = openParent(treeUri, root, segments.dropLast(1), create = false, label = path)
        val name = segments.last()
        val child = parent.indexed[name]
            ?: if (parent.page.incomplete) {
                throw IllegalStateException("Directory listing incomplete; retry")
            } else {
                throw IllegalArgumentException(missing)
            }
        return Node(child, parent.entry.documentId)
    }

    private fun openParent(
        treeUri: String,
        root: DocumentEntry,
        directorySegments: List<String>,
        create: Boolean,
        label: String,
    ): ParentDir {
        var current = root
        for (segment in directorySegments) {
            val page = children(treeUri, current.documentId)
            val indexed = indexByName(page.children)
            val child = indexed[segment]
            current = when {
                child == null && page.incomplete ->
                    throw IllegalStateException("Directory listing incomplete; retry")
                child == null && create ->
                    documents.createDirectory(treeUri, current.documentId, segment)
                        ?: throw IllegalStateException("Cannot create directory: $label")
                child == null -> throw IllegalArgumentException("Directory not found: $label")
                child.isDirectory -> child
                else -> throw IllegalArgumentException("Not a directory: $label")
            }
        }
        val page = children(treeUri, current.documentId)
        return ParentDir(current, page, indexByName(page.children))
    }

    private fun listRecursive(
        treeUri: String,
        dir: DocumentEntry,
        basePath: String,
        recursive: Boolean,
        maxEntries: Int,
        out: MutableList<FileInfo>,
    ): Boolean {
        val page = children(treeUri, dir.documentId)
        var truncated = page.incomplete
        for (child in page.children) {
            if (out.size >= maxEntries) return true
            val childPath = if (basePath.isEmpty()) child.name else "$basePath/${child.name}"
            out.add(
                FileInfo(
                    name = child.name,
                    path = childPath,
                    isDirectory = child.isDirectory,
                    size = if (child.isDirectory) 0L else child.size,
                ),
            )
            if (recursive && child.isDirectory && listRecursive(treeUri, child, childPath, true, maxEntries, out)) {
                return true
            }
        }
        return truncated
    }

    private fun readChars(stream: InputStream, safeOffset: Int, readBudget: Int, builder: StringBuilder) {
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            if (safeOffset > 0) {
                var skipped = 0L
                while (skipped < safeOffset) {
                    val n = reader.skip(safeOffset - skipped)
                    if (n <= 0) break
                    skipped += n
                }
                while (skipped < safeOffset) {
                    if (reader.read() == -1) break
                    skipped++
                }
            }
            val buf = CharArray(8_192)
            while (builder.length < readBudget) {
                val want = minOf(buf.size, readBudget - builder.length)
                val n = reader.read(buf, 0, want)
                if (n < 0) break
                builder.append(buf, 0, n)
            }
        }
    }

    private data class Node(val entry: DocumentEntry, val parentId: String?)

    private data class ParentDir(
        val entry: DocumentEntry,
        val page: DirectoryPage,
        val indexed: Map<String, DocumentEntry>,
    )
}
