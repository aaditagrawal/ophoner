package dev.ophoner.agent

import android.util.Log
import dev.ophoner.tools.sandbox.DocumentTree
import dev.ophoner.tools.sandbox.indexByName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class LoadedSkill(val name: String, val path: String, val content: String)

@Singleton
class SkillLoader @Inject constructor(
    private val documents: DocumentTree,
) {
    suspend fun formatForSystemPrompt(rootUri: String?): String? = withContext(Dispatchers.IO) {
        val files = discover(rootUri)
        if (files.isEmpty()) return@withContext null
        buildString {
            files.firstOrNull { it.path == "SKILL.md" }?.let { root ->
                readCapped(root, MAX_FILE_CHARS)?.let { content ->
                    append("\n\n# Project instructions (SKILL.md)\n")
                    append(content.trim())
                }
            }
            val optional = files.filter { it.path != "SKILL.md" }
            if (optional.isNotEmpty()) {
                append("\n\n# Available folder skills\n")
                append("Load a relevant skill with skill_load using its exact path before following its instructions.\n")
                for (skill in optional) {
                    val description = skillDescription(readCapped(skill, HEADER_CHARS).orEmpty())
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
            val content = readCapped(entry, minOf(remaining, MAX_FILE_CHARS)) ?: return@mapNotNull null
            remaining -= content.length
            LoadedSkill(entry.name, entry.path, content)
        }
    }

    /** Resolves only an exact discovered skill entry. No caller-supplied path traversal. */
    suspend fun loadSkill(rootUri: String?, path: String): LoadedSkill? = withContext(Dispatchers.IO) {
        if (!isSkillPath(path)) return@withContext null
        val entry = discover(rootUri).firstOrNull { it.path == path } ?: return@withContext null
        val content = readCapped(entry, MAX_FILE_CHARS) ?: return@withContext null
        LoadedSkill(entry.name, entry.path, content)
    }

    private data class Entry(val name: String, val path: String, val treeUri: String, val documentId: String)

    /**
     * One child query for the tree root, then one for `.ophoner` and one for `skills`
     * when those directories exist. Names and types come from that projection.
     * The pages are not cached past this call.
     */
    private fun discover(rootUri: String?): List<Entry> {
        if (rootUri.isNullOrBlank()) return emptyList()
        return try {
            val rootId = documents.rootDocumentId(rootUri) ?: return emptyList()
            val rootPage = documents.listChildren(rootUri, rootId)
            if (rootPage.error != null) {
                logWarn("Failed to discover skills: ${rootPage.error}")
                return emptyList()
            }
            val rootChildren = indexByName(rootPage.children)
            buildList {
                rootChildren["SKILL.md"]?.takeIf { !it.isDirectory }?.let {
                    add(Entry("SKILL.md", "SKILL.md", rootUri, it.documentId))
                }
                val folder = rootChildren[".ophoner"]?.takeIf { it.isDirectory } ?: return@buildList
                val folderPage = documents.listChildren(rootUri, folder.documentId)
                val skills = indexByName(folderPage.children)["skills"]?.takeIf { it.isDirectory } ?: return@buildList
                val skillsPage = documents.listChildren(rootUri, skills.documentId)
                skillsPage.children.asSequence().filter { !it.isDirectory }
                    .sortedBy { it.name }.mapNotNull { file ->
                        val path = ".ophoner/skills/${file.name}"
                        if (!isSkillPath(path)) null else Entry(file.name.dropLast(3), path, rootUri, file.documentId)
                    }.take(MAX_SKILLS).forEach { add(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarn("Failed to discover skills: ${e.message}")
            emptyList()
        }
    }

    private fun readCapped(file: Entry, maxChars: Int): String? = try {
        documents.openInput(file.treeUri, file.documentId)?.bufferedReader()?.use { reader ->
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
        logWarn("Failed to read skill ${file.name}: ${e.message}")
        null
    }

    private fun logWarn(message: String) {
        runCatching { Log.w(TAG, message) }
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
