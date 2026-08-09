package dev.ophoner.tools.sandbox

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.tools.ToolExecutionContext
import dev.ophoner.tools.ToolOutputLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
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
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    /**
     * Resolves the active SAF root: per-run [ToolExecutionContext.rootUri] if set,
     * otherwise the global settings working directory.
     */
    private suspend fun getRootDir(): DocumentFile? {
        val overrideUri = currentCoroutineContext()[ToolExecutionContext]?.rootUri
            ?.takeIf { it.isNotBlank() }
        val uriString = overrideUri
            ?: settingsRepository.observeWorkingDirectoryUri().firstOrNull()
            ?: return null
        val uri = Uri.parse(uriString)
        return DocumentFile.fromTreeUri(context, uri)
    }

    private fun DocumentFile.resolve(relativePath: String): DocumentFile? {
        if (relativePath.isEmpty() || relativePath == ".") return this
        val parts = relativePath.trimStart('/').split('/')
        var current: DocumentFile = this
        for (part in parts) {
            if (part.isEmpty() || part == ".") continue
            if (part == "..") return null
            current = current.findFile(part) ?: return null
        }
        return current
    }

    private fun DocumentFile.resolveParent(relativePath: String): Pair<DocumentFile, String>? {
        val parts = relativePath.trimStart('/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) return null
        val fileName = parts.last()
        var current: DocumentFile = this
        for (part in parts.dropLast(1)) {
            current = current.findFile(part)
                ?: current.createDirectory(part)
                ?: return null
        }
        return current to fileName
    }

    suspend fun readFile(
        relativePath: String,
        offset: Int = 0,
        limit: Int? = null,
        maxChars: Int = ToolOutputLimits.FILE_READ_MAX_CHARS,
    ): FileReadResult = withContext(Dispatchers.IO) {
        val root = getRootDir() ?: throw IllegalStateException("No working directory set")
        val file = root.resolve(relativePath)
            ?: throw IllegalArgumentException("File not found: $relativePath")
        if (file.isDirectory) throw IllegalArgumentException("Path is a directory: $relativePath")

        val safeOffset = offset.coerceAtLeast(0)
        val requestedLimit = limit?.coerceAtLeast(0)
        // Cap how many chars we materialize: never exceed maxChars (+1 to detect truncation).
        val charsToRead = when {
            requestedLimit != null -> minOf(requestedLimit, maxChars)
            else -> maxChars
        }
        // Read one extra char to detect whether more content remains.
        val readBudget = charsToRead + 1

        val builder = StringBuilder()
        context.contentResolver.openInputStream(file.uri)?.use { input ->
            input.bufferedReader().use { reader ->
                if (safeOffset > 0) {
                    var skipped = 0L
                    while (skipped < safeOffset) {
                        val n = reader.skip(safeOffset - skipped.toLong())
                        if (n <= 0) break
                        skipped += n
                    }
                    // skip() is char-based on Reader; if short, fall back to reading/discarding
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
        } ?: throw IllegalStateException("Cannot read file: $relativePath")

        val hasMore = builder.length > charsToRead
        val content = if (hasMore) builder.substring(0, charsToRead) else builder.toString()
        FileReadResult(
            content = content,
            truncated = hasMore,
            totalCharsRead = content.length,
            startedAtOffset = safeOffset,
        )
    }

    suspend fun writeFile(relativePath: String, content: String): Unit = withContext(Dispatchers.IO) {
        val root = getRootDir() ?: throw IllegalStateException("No working directory set")
        val (parent, fileName) = root.resolveParent(relativePath)
            ?: throw IllegalArgumentException("Invalid path: $relativePath")
        val existing = parent.findFile(fileName)
        val file = existing ?: parent.createFile("application/octet-stream", fileName)
            ?: throw IllegalStateException("Cannot create file: $relativePath")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(content.toByteArray()) }
            ?: throw IllegalStateException("Cannot write file: $relativePath")
    }

    suspend fun listDirectory(
        relativePath: String,
        recursive: Boolean = false,
        maxEntries: Int = ToolOutputLimits.FILE_LIST_MAX_ENTRIES,
    ): FileListResult = withContext(Dispatchers.IO) {
        val root = getRootDir() ?: throw IllegalStateException("No working directory set")
        val dir = if (relativePath.isEmpty() || relativePath == ".") root
        else root.resolve(relativePath)
            ?: throw IllegalArgumentException("Directory not found: $relativePath")
        if (!dir.isDirectory) throw IllegalArgumentException("Not a directory: $relativePath")
        val limit = maxEntries.coerceAtLeast(0)
        if (limit == 0) return@withContext FileListResult(files = emptyList(), truncated = true)
        val files = ArrayList<FileInfo>(minOf(limit, 64))
        val truncated = listRecursive(
            dir = dir,
            basePath = relativePath.trimStart('/'),
            recursive = recursive,
            maxEntries = limit,
            out = files,
        )
        FileListResult(files = files, truncated = truncated)
    }

    /**
     * @return true if listing stopped early because [maxEntries] was reached.
     */
    private fun listRecursive(
        dir: DocumentFile,
        basePath: String,
        recursive: Boolean,
        maxEntries: Int,
        out: MutableList<FileInfo>,
    ): Boolean {
        for (child in dir.listFiles()) {
            if (out.size >= maxEntries) return true
            val isDir = child.isDirectory
            val childPath = if (basePath.isEmpty()) child.name ?: "" else "$basePath/${child.name}"
            out.add(
                FileInfo(
                    name = child.name ?: "",
                    path = childPath,
                    isDirectory = isDir,
                    // length() is a SAF round-trip; skip for directories.
                    size = if (isDir) 0L else child.length(),
                ),
            )
            if (recursive && isDir) {
                if (listRecursive(child, childPath, true, maxEntries, out)) return true
            }
        }
        return false
    }

    suspend fun deleteFile(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val root = getRootDir() ?: throw IllegalStateException("No working directory set")
        val file = root.resolve(relativePath)
            ?: throw IllegalArgumentException("File not found: $relativePath")
        // Paths like ".", "./", or "././." resolve to the SAF tree root — never delete it.
        if (file.uri == root.uri) {
            throw IllegalArgumentException("Refusing to delete the working directory root")
        }
        file.delete()
    }

    suspend fun moveFile(from: String, to: String): Unit = withContext(Dispatchers.IO) {
        if (normalizeRelativePath(from) == normalizeRelativePath(to)) {
            throw IllegalArgumentException("Source and destination are the same path: $from")
        }
        copyFile(from, to)
        if (!deleteFile(from)) {
            throw IllegalStateException(
                "Copied to $to but failed to delete source $from (both copies may exist)",
            )
        }
    }

    private suspend fun copyFile(from: String, to: String) {
        val root = getRootDir() ?: throw IllegalStateException("No working directory set")
        val src = root.resolve(from)
            ?: throw IllegalArgumentException("File not found: $from")
        if (src.isDirectory) throw IllegalArgumentException("Cannot move directory: $from")
        val (parent, fileName) = root.resolveParent(to)
            ?: throw IllegalArgumentException("Invalid path: $to")
        val existing = parent.findFile(fileName)
        // Same document via different relative paths (e.g. "a.txt" vs "./a.txt") would
        // truncate-then-read if we open the destination with "wt" on the source URI.
        if (existing != null && existing.uri == src.uri) {
            throw IllegalArgumentException("Source and destination resolve to the same file")
        }
        val dest = existing ?: parent.createFile(src.type ?: "application/octet-stream", fileName)
            ?: throw IllegalStateException("Cannot create file: $to")
        context.contentResolver.openInputStream(src.uri)?.use { input ->
            context.contentResolver.openOutputStream(dest.uri, "wt")?.use { output ->
                input.copyTo(output)
            } ?: throw IllegalStateException("Cannot write file: $to")
        } ?: throw IllegalStateException("Cannot read file: $from")
    }

    /** Collapse ".", empty segments, and trailing slashes for same-path comparisons. */
    private fun normalizeRelativePath(path: String): String {
        val parts = path.trimStart('/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return path
        return parts.joinToString("/")
    }

    suspend fun hasWorkingDirectory(): Boolean {
        val overrideUri = currentCoroutineContext()[ToolExecutionContext]?.rootUri
        if (!overrideUri.isNullOrBlank()) return true
        return settingsRepository.observeWorkingDirectoryUri().firstOrNull() != null
    }
}
