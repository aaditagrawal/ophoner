package dev.ophoner.data.repository

import dev.ophoner.data.db.ConversationDao
import dev.ophoner.data.db.MessageDao
import dev.ophoner.data.entity.MessageEntity
import dev.ophoner.data.model.ContentBlock
import dev.ophoner.data.model.Message
import dev.ophoner.data.model.MessageRole
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import dev.ophoner.data.entity.ConversationEntity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.system.measureNanoTime

class ConversationRepositoryTest {
    private val dao = mockk<MessageDao>()
    private val repository = ConversationRepository(mockk<ConversationDao>(), dao, Json)

    private fun row(id: String, text: String = id) = MessageEntity(
        id, "chat", "ASSISTANT", Json.encodeToString<List<ContentBlock>>(listOf(ContentBlock.Text(text))), 0, 1,
    )

    @Test fun `fork copies only through chosen prompt and retains folder scope`() = runBlocking {
        val conversations = mockk<ConversationDao>()
        val source = ConversationEntity("chat", "A task", "provider", "folder", "content://folder", "Project", 1, 1)
        coEvery { conversations.getById("chat") } returns source
        val history = listOf(row("u1").copy(role = "USER"), row("a1"), row("u2").copy(role = "USER"), row("a2"))
        coEvery { dao.getByConversation("chat") } returns history
        val branch = slot<ConversationEntity>()
        val copied = slot<List<MessageEntity>>()
        coEvery { conversations.insertBranch(capture(branch), capture(copied)) } returns Unit
        val repo = ConversationRepository(conversations, dao, Json)
        val fork = repo.forkFromPrompt("chat", "u2", true)
        assertEquals(3, copied.captured.size)
        assertEquals(listOf(0, 1, 2), copied.captured.map { it.orderIndex })
        assertTrue(copied.captured.all { it.conversationId == fork.id && it.id !in history.map { it.id } })
        assertEquals(source.scopedFolderUri, fork.scopedFolderUri)
        assertEquals(source.providerConfigId, fork.providerConfigId)
        assertEquals(history.take(3).map { it.contentJson }, copied.captured.map { it.contentJson })
        repo.forkFromPrompt("chat", "u2", false)
        assertEquals(2, copied.captured.size)
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test fun `appends reuse history but edited deleted and reinserted rows stay fresh`() = runBlocking {
        val a = row("a")
        val b = row("b")
        every { dao.observeByConversation("chat") } returns flowOf(
            listOf(a), listOf(a.copy(), b), listOf(a.copy(), b),
            listOf(a.copy(orderIndex = 2), row("b", "edited")),
            listOf(a), listOf(a, b),
        )
        val snapshots = repository.observeMessages("chat").toList()
        assertEquals(5, snapshots.size) // The identical Room invalidation is suppressed.
        assertSame(snapshots[0][0], snapshots[1][0])
        assertNotSame(snapshots[1][0], snapshots[2][0])
        assertEquals(2, snapshots[2][0].orderIndex)
        assertEquals(listOf(ContentBlock.Text("edited")), snapshots[2][1].content)
        assertEquals(1, snapshots[3].size)
        assertEquals(listOf(ContentBlock.Text("b")), snapshots[4][1].content)
        assertNotSame(snapshots[1][1], snapshots[4][1])
    }

    @Test fun `observers do not retain one another's history`() = runBlocking {
        every { dao.observeByConversation("chat") } returns flowOf(listOf(row("a")))
        val flow = repository.observeMessages("chat")
        val first = flow.toList().single().single()
        val second = flow.toList().single().single()
        assertEquals(first, second)
        assertNotSame(first, second)
    }

    @Test fun `report history append benchmark when requested`() = runBlocking {
        if (System.getenv("OPHONER_PERF") != "1") return@runBlocking
        val rows = List(1_020) { row("$it", "history text ".repeat(500)).copy(orderIndex = it) }
        val frames = (1_000..1_020).map { rows.take(it) }
        every { dao.observeByConversation("chat") } returns frames.asFlow()
        val baseline = frames.asFlow().map { entities ->
            entities.map { entity ->
                Message(entity.id, entity.conversationId, MessageRole.valueOf(entity.role),
                    Json.decodeFromString<List<ContentBlock>>(entity.contentJson), entity.orderIndex, entity.createdAt)
            }
        }.flowOn(Dispatchers.Default)
        repeat(3) { baseline.toList(); repository.observeMessages("chat").toList() }
        val before = List(7) { measureNanoTime { baseline.toList() } }.sorted()[3]
        val after = List(7) { measureNanoTime { repository.observeMessages("chat").toList() } }.sorted()[3]
        println("HISTORY median: before=${before / 1e6} ms after=${after / 1e6} ms; 1000 rows + 20 appends")
    }
}
