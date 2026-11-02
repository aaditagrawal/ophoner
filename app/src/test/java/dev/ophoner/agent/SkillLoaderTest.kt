package dev.ophoner.agent

import dev.ophoner.tools.sandbox.MemoryDocumentTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SkillLoaderTest {
    @Test fun acceptsOnlyDirectCatalogPaths() {
        assertTrue(isSkillPath("SKILL.md"))
        assertTrue(isSkillPath(".ophoner/skills/review.MD"))
        listOf("../SKILL.md", ".ophoner/skills/../../secret.md", ".ophoner/skills/nested/a.md",
            ".ophoner/skills/a\\b.md", "/tmp/a.md", ".ophoner/skills/a\nb.md", ".ophoner/skills/a.txt")
            .forEach { assertFalse(it, isSkillPath(it)) }
    }

    @Test fun indexesDescriptionWithoutBody() {
        assertEquals("Review code changes", skillDescription("---\nname: review\ndescription: 'Review code changes'\n---\nSECRET BODY"))
    }

    @Test fun discoversWithOneQueryPerDirectoryAndRereadsAfterEdits() = runBlocking {
        val tree = MemoryDocumentTree()
        tree.addFile("root", "SKILL.md", "Project rules")
        val dot = tree.addDir("root", ".ophoner")
        val skills = tree.addDir(dot, "skills")
        val review = tree.addFile(skills, "review.md", "# Review\nLook carefully")
        val loader = SkillLoader(tree)
        val loaded = loader.loadSkills(tree.uri)
        assertEquals(listOf("SKILL.md", ".ophoner/skills/review.md"), loaded.map { it.path })
        assertEquals(3, tree.listCalls)
        tree.setText(review, "# Review\nChanged")
        assertEquals("# Review\nChanged", loader.loadSkill(tree.uri, ".ophoner/skills/review.md")?.content)
        assertEquals(6, tree.listCalls)
    }

    @Test fun providerErrorYieldsNoSkills() = runBlocking {
        val tree = MemoryDocumentTree()
        tree.errorMessage = "unavailable"
        assertTrue(SkillLoader(tree).loadSkills(tree.uri).isEmpty())
    }

    @Test fun fallsBackToHeadingAndLimitsDescription() {
        assertEquals("Review", skillDescription("# Review\nLong instructions"))
        assertEquals(240, skillDescription("---\ndescription: ${"a".repeat(1000)}\n---").length)
        assertEquals("Load this skill to read its instructions.", skillDescription(""))
    }
}
