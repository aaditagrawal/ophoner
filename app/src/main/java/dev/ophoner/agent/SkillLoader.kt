package dev.ophoner.agent

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class LoadedSkill(
    val name: String,
    val path: String,
    val content: String,
)

@Singleton
class SkillLoader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun formatForSystemPrompt(rootUri: String?): String? {
        val skills = loadSkills(rootUri)
        if (skills.isEmpty()) return null
        return buildString {
            append("\n\n# Folder skills\n")
            append("The following project skills were loaded from the active folder. Follow them when relevant.\n")
            for (skill in skills) {
                append("\n## Skill: ")
                append(skill.name)
                append(" (")
                append(skill.path)
                append(")\n")
                append(skill.content.trim())
                append('\n')
            }
        }
    }

    suspend fun loadSkills(rootUri: String?): List<LoadedSkill> = withContext(Dispatchers.IO) {
        if (rootUri.isNullOrBlank()) return@withContext emptyList()
        val root = try {
            DocumentFile.fromTreeUri(context, Uri.parse(rootUri))
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to open skills root: ${t.message}")
            null
        } ?: return@withContext emptyList()

        val skills = mutableListOf<LoadedSkill>()
        var remaining = MAX_TOTAL_CHARS

        val rootSkill = root.findFile("SKILL.md")
        if (rootSkill != null && rootSkill.isFile) {
            remaining = addSkill(skills, remaining, "SKILL.md", "SKILL.md", rootSkill)
        }

        val ophonerDir = root.findFile(".ophoner")
        val skillsDir = if (ophonerDir != null && ophonerDir.isDirectory) {
            ophonerDir.findFile("skills")
        } else {
            null
        }
        if (skillsDir != null && skillsDir.isDirectory) {
            val files = skillsDir.listFiles()
                .filter { it.isFile && (it.name?.endsWith(".md", ignoreCase = true) == true) }
                .sortedBy { it.name?.lowercase() }
            for (file in files) {
                val name = file.name ?: continue
                if (name.equals("SKILL.md", ignoreCase = true) &&
                    skills.any { it.path == "SKILL.md" }
                ) {
                    continue
                }
                remaining = addSkill(
                    skills,
                    remaining,
                    ".ophoner/skills/$name",
                    name.removeSuffix(".md"),
                    file,
                )
                if (remaining <= 0) break
            }
        }
        skills
    }

    private fun addSkill(
        skills: MutableList<LoadedSkill>,
        remaining: Int,
        path: String,
        name: String,
        file: DocumentFile,
    ): Int {
        if (remaining <= 0) return remaining
        val text = readCapped(file, minOf(MAX_FILE_CHARS, remaining)) ?: return remaining
        if (text.isBlank()) return remaining
        skills.add(LoadedSkill(name = name, path = path, content = text))
        return remaining - text.length
    }

    private fun readCapped(file: DocumentFile, maxChars: Int): String? {
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                input.bufferedReader().use { reader ->
                    val builder = StringBuilder()
                    val buf = CharArray(4_096)
                    while (builder.length < maxChars) {
                        val want = minOf(buf.size, maxChars - builder.length)
                        val n = reader.read(buf, 0, want)
                        if (n < 0) break
                        builder.append(buf, 0, n)
                    }
                    builder.toString()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read skill ${file.name}: ${t.message}")
            null
        }
    }

    companion object {
        private const val TAG = "SkillLoader"
        private const val MAX_TOTAL_CHARS = 32_000
        private const val MAX_FILE_CHARS = 12_000
    }
}
