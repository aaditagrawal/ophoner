package dev.ophoner.agent

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class LoadedSkill(val name: String, val path: String, val content: String)

@Singleton
class SkillLoader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun formatForSystemPrompt(rootUri: String?): String? = withContext(Dispatchers.IO) {
        val files = discover(rootUri)
        if (files.isEmpty()) return@withContext null
        buildString {
            files.firstOrNull { it.path == "SKILL.md" }?.let { root ->
                readCapped(root.file, MAX_FILE_CHARS)?.let { content ->
                    append("\n\n# Project instructions (SKILL.md)\n")
                    append(content.trim())
                }
            }
            val optional = files.filter { it.path != "SKILL.md" }
            if (optional.isNotEmpty()) {
                append("\n\n# Available folder skills\n")
                append("Load a relevant skill with skill_load using its exact path before following its instructions.\n")
                for (skill in optional) {
                    val description = skillDescription(readCapped(skill.file, HEADER_CHARS).orEmpty())
                    val row = "- ${skill.name} | path: ${skill.path} | $description\n"
                    if (length + row.length > MAX_TOTAL_CHARS - 100) {
                        append("[More skills omitted from this catalog to fit the instruction budget.]\n")
                        break
                    }
                    append(row)
                }
            }
        }
    }

    /** Compatibility API for callers that explicitly need full contents, with an aggregate cap. */
    suspend fun loadSkills(rootUri: String?): List<LoadedSkill> = withContext(Dispatchers.IO) {
        var remaining = MAX_TOTAL_CHARS
        discover(rootUri).mapNotNull { entry ->
            if (remaining <= 0) return@mapNotNull null
            val content = readCapped(entry.file, minOf(remaining, MAX_FILE_CHARS)) ?: return@mapNotNull null
            remaining -= content.length
            LoadedSkill(entry.name, entry.path, content)
        }
    }

    /** Resolves only an exact discovered skill entry. No caller-supplied path traversal. */
    suspend fun loadSkill(rootUri: String?, path: String): LoadedSkill? = withContext(Dispatchers.IO) {
        if (!isSkillPath(path)) return@withContext null
        val entry = discover(rootUri).firstOrNull { it.path == path } ?: return@withContext null
        val content = readCapped(entry.file, MAX_FILE_CHARS) ?: return@withContext null
        LoadedSkill(entry.name, entry.path, content)
    }

    private data class Entry(val name: String, val path: String, val file: DocumentFile)

    private fun discover(rootUri: String?): List<Entry> {
        if (rootUri.isNullOrBlank()) return emptyList()
        return try {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(rootUri)) ?: return emptyList()
            buildList {
                root.findFile("SKILL.md")?.takeIf { it.isFile }?.let { add(Entry("SKILL.md", "SKILL.md", it)) }
                val folder = root.findFile(".ophoner")?.takeIf { it.isDirectory }
                    ?.findFile("skills")?.takeIf { it.isDirectory }
                folder?.listFiles()?.asSequence()?.filter { it.isFile }
                    ?.sortedBy { it.name.orEmpty() }?.mapNotNull { file ->
                        val name = file.name ?: return@mapNotNull null
                        val path = ".ophoner/skills/$name"
                        if (!isSkillPath(path)) null else Entry(name.dropLast(3), path, file)
                    }?.take(MAX_SKILLS)?.forEach { add(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to discover skills: ${e.message}")
            emptyList()
        }
    }

    private fun readCapped(file: DocumentFile, maxChars: Int): String? = try {
        context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
            val chars = CharArray(maxChars)
            var count = 0
            while (count < maxChars) {
                val read = reader.read(chars, count, maxChars - count)
                if (read <= 0) break
                count += read
            }
            val text = String(chars, 0, count)
            if (count == maxChars && maxChars >= MAX_FILE_CHARS && reader.read() >= 0) {
                "$text\n[Skill truncated at $maxChars characters. Use file_read with offset=$maxChars to continue.]"
            } else text
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read skill ${file.name}: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "SkillLoader"
        private const val MAX_TOTAL_CHARS = 32_000
        private const val MAX_FILE_CHARS = 12_000
        private const val HEADER_CHARS = 1_500
        private const val MAX_SKILLS = 64
    }
}

internal fun isSkillPath(path: String): Boolean {
    if (path == "SKILL.md") return true
    if (!path.startsWith(".ophoner/skills/")) return false
    val name = path.removePrefix(".ophoner/skills/")
    return name.length in 4..160 && name.endsWith(".md", ignoreCase = true) &&
        name.none { it == '/' || it == '\\' || it.isISOControl() } && name != ".."
}

/** Read only the small header; descriptions are catalog text, not executable instructions. */
internal fun skillDescription(header: String): String {
    val lines = header.lineSequence().toList()
    val declared = if (lines.firstOrNull()?.trim() == "---") {
        lines.drop(1).takeWhile { it.trim() != "---" }
            .firstOrNull { it.trimStart().startsWith("description:") }
            ?.substringAfter("description:")?.trim()?.trim('"', '\'')
    } else null
    val description = declared?.takeUnless { it.isBlank() || it in setOf("|", ">", "|-", ">-") }
        ?: lines.firstOrNull { it.isNotBlank() && !it.startsWith("---") && !it.contains(':') }
        ?.trim()?.trimStart('#')?.trim()
        ?: "Load this skill to read its instructions."
    return description.replace(Regex("\\s+"), " ").take(240)
}
