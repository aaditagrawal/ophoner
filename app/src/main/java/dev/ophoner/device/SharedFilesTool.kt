package dev.ophoner.device

import android.os.Environment
import android.media.MediaScannerConnection
import dev.ophoner.tools.ToolExecutionContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun sharedFile(root: File, path: String): File {
    require(path.isNotBlank() && !path.contains('\u0000')) { "Invalid path" }
    val base = root.canonicalFile
    val target = (if (File(path).isAbsolute) File(path) else File(base, path)).canonicalFile
    require(target == base || target.path.startsWith(base.path + File.separator)) { "Path is outside shared storage" }
    val relative = target.relativeTo(base).invariantSeparatorsPath
    require(relative != "Android" && !relative.startsWith("Android/data") && !relative.startsWith("Android/obb")) { "Other apps' private external directories are unavailable" }
    return target
}

class SharedFilesTool(access: DeviceAccess) : DeviceTool(access, DeviceCapability.STORAGE) {
    override val definition = deviceTool("shared_files", "Manage primary shared storage with all-files access. Paths are relative to shared storage or absolute inside it. Unavailable in folder-scoped chats: use file_* tools there. Reads/writes are text; copy/move support binary files. Destructive operations require approval unless YOLO is enabled.", listOf("list", "read", "write", "mkdir", "copy", "move", "delete"), mapOf("path" to "Path, use . to list storage root", "destination" to "Destination for copy/move", "text" to "Text for write, max 100000 characters", "offset" to "Read character offset", "overwrite" to "true to replace an existing file"))
    override suspend fun perform(args: JsonObject): String {
        check(currentCoroutineContext()[ToolExecutionContext]?.rootUri.isNullOrBlank()) { "Shared storage is disabled in folder chats. Use the scoped file tools or start a general chat." }
        check(access.status(DeviceCapability.STORAGE) == "Ready") { "Grant all-files access in Device capabilities" }
        val root = Environment.getExternalStorageDirectory()
        val file = sharedFile(root, args.required("path"))
        fun mutableTarget(target: File) { require(target != root.canonicalFile) { "Cannot modify storage root" } }
        fun destination(): File = sharedFile(root, args.required("destination")).also { mutableTarget(it); require(it.parentFile?.isDirectory == true) { "Destination parent does not exist" } }
        fun scan(vararg files: File) { MediaScannerConnection.scanFile(access.context, files.map { it.path }.toTypedArray(), null, null) }
        return when (args.required("action")) {
            "list" -> { require(file.isDirectory) { "Not a directory" }; val files = file.listFiles() ?: error("Cannot list directory"); buildJsonObject { put("root", root.path); put("truncated", files.size > 200); put("items", JsonArray(files.take(200).map { buildJsonObject { put("name", it.name); put("directory", it.isDirectory); put("bytes", it.length()) } })) }.toString() }
            "read" -> {
                require(file.isFile) { "Not a file" }
                val offset = (args.text("offset")?.toLongOrNull() ?: 0).also { require(it in 0..10_000_000) }
                file.bufferedReader().use { reader ->
                    var remaining = offset
                    while (remaining > 0) { val skipped = reader.skip(remaining); if (skipped <= 0) break; remaining -= skipped }
                    val buffer = CharArray(48_001); var read = 0
                    while (read < buffer.size) { val count = reader.read(buffer, read, buffer.size - read); if (count < 0) break; read += count }
                    String(buffer, 0, minOf(read, 48_000)) + if (read > 48_000) "\n[truncated; next offset=${offset + 48_000}]" else ""
                }
            }
            "write" -> {
                mutableTarget(file); val content = args.text("text") ?: error("Missing text"); require(content.length <= 100_000) { "Text too large" }
                require(!file.exists() || args.text("overwrite") == "true") { "File exists; set overwrite=true to replace" }
                require(file.parentFile?.isDirectory == true) { "Parent directory does not exist" }
                val temporary = File.createTempFile(".ophoner-", ".tmp", file.parentFile)
                try { temporary.writeText(content); if (args.text("overwrite") == "true") Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) else Files.move(temporary.toPath(), file.toPath()) } finally { temporary.delete() }
                scan(file)
                "Wrote ${content.length} characters"
            }
            "mkdir" -> { mutableTarget(file); check(file.mkdir()) { "Cannot create directory (may already exist)" }; "Created directory" }
            "copy", "move" -> {
                mutableTarget(file); require(file.isFile) { "Only individual files can be copied or moved" }; val dest = destination()
                val options = if (args.text("overwrite") == "true") arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
                if (args.required("action") == "copy") Files.copy(file.toPath(), dest.toPath(), *options) else Files.move(file.toPath(), dest.toPath(), *options)
                scan(file, dest)
                "Completed; destination=${dest.path}, bytes=${dest.length()}"
            }
            "delete" -> { mutableTarget(file); check(file.delete()) { "Delete failed. Directories must be empty." }; scan(file); "Deleted ${file.name}" }
            else -> error("Unknown action")
        }
    }
}
