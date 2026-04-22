package dev.ophoner.tools.sandbox

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.ophoner.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
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

@Singleton
class FileAccessManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private suspend fun getRootDir(): DocumentFile? {
        val uriString = settingsRepository.observeWorkingDirectoryUri().firstOrNull() ?: return null
        val uri = Uri.parse(uriString)
        return DocumentFile.fromTreeUri(context, uri)
    }

    private fun DocumentFile.resolve(relativePath: String): DocumentFile? {
        if (relativePath.isEmpty() || relativePath == ".") return this
        val parts = relativePath.trimStart('/').split('/')
        var current: DocumentFile = this
        for (part in parts) {
            current = current.findFile(part) ?: return null
        }
        return current
    }

    private fun DocumentFile.resolveParent(relativePath: String): Pair<DocumentFile, String>? {
        val parts = relativePath.trimStart('/').split('/')
        if (parts.isEmpty()) return null
        val fileName = parts.last()
        var current: DocumentFile = this
        for (part in parts.dropLast(1)) {
            current = current.findFile(part)
                ?: current.createDirectory(part)
                ?: return null
        }
        return current to fileName
    }

    suspend fun readFile(relativePath: String): String = withContext(Dispatchers.IO) {
        val root = getRootDir() ?: throw IllegalStateException("No working directory set")
        val file = root.resolve(relativePath)
            ?: throw IllegalArgumentException("File not found: $relativePath")
        context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() }
            ?: throw IllegalStateException("Cannot read file: $relativePath")
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

    suspend fun listDirectory(relativePath: String, recursive: Boolean = false): List<FileInfo> =
        withContext(Dispatchers.IO) {
            val root = getRootDir() ?: throw IllegalStateException("No working directory set")
            val dir = if (relativePath.isEmpty() || relativePath == ".") root
                else root.resolve(relativePath)
                    ?: throw IllegalArgumentException("Directory not found: $relativePath")
            if (!dir.isDirectory) throw IllegalArgumentException("Not a directory: $relativePath")
            buildList {
                listRecursive(dir, relativePath.trimStart('/'), recursive)
            }
        }

    private fun MutableList<FileInfo>.listRecursive(
        dir: DocumentFile,
        basePath: String,
        recursive: Boolean,
    ) {
        for (child in dir.listFiles()) {
            val childPath = if (basePath.isEmpty()) child.name ?: "" else "$basePath/${child.name}"
            add(FileInfo(
                name = child.name ?: "",
                path = childPath,
                isDirectory = child.isDirectory,
                size = child.length(),
            ))
            if (recursive && child.isDirectory) {
                listRecursive(child, childPath, true)
            }
        }
    }

    suspend fun deleteFile(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val root = getRootDir() ?: throw IllegalStateException("No working directory set")
        val file = root.resolve(relativePath)
            ?: throw IllegalArgumentException("File not found: $relativePath")
        file.delete()
    }

    suspend fun moveFile(from: String, to: String): Unit = withContext(Dispatchers.IO) {
        val content = readFile(from)
        writeFile(to, content)
        deleteFile(from)
    }

    suspend fun hasWorkingDirectory(): Boolean =
        settingsRepository.observeWorkingDirectoryUri().firstOrNull() != null
}
