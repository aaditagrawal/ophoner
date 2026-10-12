package dev.ophoner.tools.impl

import dev.ophoner.agent.LoadedSkill
import dev.ophoner.agent.SkillLoader
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.tools.ToolExecutionContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class SkillLoadToolTest {
    @Test fun scopedFolderTakesPrecedenceOverGlobalSettings() = runTest {
        val loader = mockk<SkillLoader>()
        val settings = mockk<SettingsRepository>()
        val path = ".ophoner/skills/review.md"
        coEvery { loader.loadSkill("scoped", path) } returns LoadedSkill("review", path, "Instructions")
        val result = withContext(ToolExecutionContext(rootUri = "scoped")) {
            SkillLoadTool(loader, settings).execute("call", buildJsonObject { put("path", path) })
        }
        assertFalse(result.isError)
        assertTrue(result.output.contains("Instructions"))
        verify(exactly = 0) { settings.observeWorkingDirectoryUri() }
        coVerify(exactly = 1) { loader.loadSkill("scoped", path) }
    }

    @Test fun missingSkillReturnsErrorAndUsesGlobalFolder() = runTest {
        val loader = mockk<SkillLoader>()
        val settings = mockk<SettingsRepository>()
        every { settings.observeWorkingDirectoryUri() } returns flowOf("global")
        coEvery { loader.loadSkill("global", "missing") } returns null
        val result = SkillLoadTool(loader, settings).execute("call", buildJsonObject { put("path", "missing") })
        assertTrue(result.isError)
        coVerify { loader.loadSkill("global", "missing") }
    }
}
