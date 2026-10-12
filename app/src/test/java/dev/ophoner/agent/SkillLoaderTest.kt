package dev.ophoner.agent

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

    @Test fun fallsBackToHeadingAndLimitsDescription() {
        assertEquals("Review", skillDescription("# Review\nLong instructions"))
        assertEquals(240, skillDescription("---\ndescription: ${"a".repeat(1000)}\n---").length)
        assertEquals("Load this skill to read its instructions.", skillDescription(""))
    }
}
