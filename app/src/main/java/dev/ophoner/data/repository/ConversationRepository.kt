package dev.ophoner.data.repository

import dev.ophoner.data.db.ConversationDao
import dev.ophoner.data.db.MessageDao
import dev.ophoner.data.entity.ConversationEntity
import dev.ophoner.data.entity.MessageEntity
import dev.ophoner.data.model.ContentBlock
import dev.ophoner.data.model.Conversation
import dev.ophoner.data.model.ConversationMode
import dev.ophoner.data.model.Message
import dev.ophoner.data.model.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val json: Json,
) {
    fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().distinctUntilChanged().map { entities ->
            entities.map { it.toDomain() }
        }.flowOn(Dispatchers.Default)

    fun observeMessages(conversationId: String): Flow<List<Message>> =
        decodeMessages(messageDao.observeByConversation(conversationId))

    fun observeMessagesFrom(conversationId: String, start: Int): Flow<List<Message>> =
        decodeMessages(messageDao.observeFrom(conversationId, start))

    suspend fun pageStart(conversationId: String, before: Int = Int.MAX_VALUE): Int =
        messageDao.pageStart(conversationId, before, 49) ?: Int.MIN_VALUE

    suspend fun hasMessagesBefore(conversationId: String, start: Int): Boolean =
        messageDao.hasBefore(conversationId, start)

    fun observeMessagesRange(conversationId: String, start: Int, end: Int): Flow<List<Message>> =
        decodeMessages(messageDao.observeRange(conversationId, start, end))

    suspend fun pageEnd(conversationId: String, after: Int): Int =
        messageDao.pageEnd(conversationId, after, 49) ?: Int.MAX_VALUE

    suspend fun hasMessagesAfter(conversationId: String, end: Int): Boolean =
        messageDao.hasAfter(conversationId, end)

    private fun decodeMessages(source: Flow<List<MessageEntity>>): Flow<List<Message>> =
        flow {
            // One cache per collector, released with the screen. Reuse immutable
            // messages, but replace edited rows and evict deleted rows on every emission.
            var previous = emptyMap<String, Pair<MessageEntity, Message>>()
            source.distinctUntilChanged().collect { entities ->
                val next = HashMap<String, Pair<MessageEntity, Message>>(entities.size)
                val messages = entities.map { entity ->
                    val cached = previous[entity.id]
                    val entry = if (cached?.first == entity) cached else entity to entity.toDomain()
                    next[entity.id] = entry
                    entry.second
                }
                previous = next
                emit(messages)
            }
        }.flowOn(Dispatchers.Default)

    suspend fun createConversation(
        providerConfigId: String,
        mode: ConversationMode = ConversationMode.GENERAL,
        scopedFolderUri: String? = null,
        scopedFolderName: String? = null,
    ): Conversation {
        val now = System.currentTimeMillis()
        val entity = ConversationEntity(
            id = UUID.randomUUID().toString(),
            title = if (mode == ConversationMode.FOLDER) scopedFolderName ?: "Folder session" else "New conversation",
            providerConfigId = providerConfigId,
            mode = mode.name.lowercase(),
            scopedFolderUri = scopedFolderUri,
            scopedFolderName = scopedFolderName,
            createdAt = now,
            updatedAt = now,
        )
        conversationDao.insert(entity)
        return entity.toDomain()
    }

    /** Snapshot the prefix; later source-thread changes cannot alter this branch. */
    suspend fun forkFromPrompt(conversationId: String, promptId: String, includePrompt: Boolean): Conversation {
        val source = conversationDao.getById(conversationId) ?: error("Conversation no longer exists.")
        val history = messageDao.getByConversation(conversationId)
        val index = history.indexOfFirst { it.id == promptId && it.role == MessageRole.USER.name }
        require(index >= 0) { "Prompt no longer exists." }
        val now = System.currentTimeMillis()
        val branch = source.copy(id = UUID.randomUUID().toString(), title = "${source.title.take(70)} · branch", createdAt = now, updatedAt = now)
        val messages = history.take(index + if (includePrompt) 1 else 0).mapIndexed { order, message ->
            message.copy(id = UUID.randomUUID().toString(), conversationId = branch.id, orderIndex = order)
        }
        conversationDao.insertBranch(branch, messages)
        return branch.toDomain()
    }

    suspend fun getConversation(conversationId: String): Conversation? =
        conversationDao.getById(conversationId)?.toDomain()

    suspend fun updateTitle(conversationId: String, title: String) {
        conversationDao.updateTitle(conversationId, title, System.currentTimeMillis())
    }

    suspend fun deleteConversation(conversationId: String) {
        conversationDao.deleteById(conversationId)
    }

    suspend fun deleteConversationsByFolderUri(uri: String) {
        conversationDao.deleteByFolderUri(uri)
    }

    suspend fun saveMessage(message: Message): Unit = withContext(Dispatchers.Default) {
        messageDao.appendOrUpdate(message.toEntity())
    }

    suspend fun getMessages(conversationId: String): List<Message> =
        withContext(Dispatchers.Default) {
            messageDao.getByConversation(conversationId).map { it.toDomain() }
        }

    suspend fun getMessageCount(conversationId: String): Int =
        messageDao.countByConversation(conversationId)

    private fun ConversationEntity.toDomain() = Conversation(
        id = id,
        title = title,
        providerConfigId = providerConfigId,
        mode = try { ConversationMode.valueOf(mode.uppercase()) } catch (_: Exception) { ConversationMode.GENERAL },
        scopedFolderUri = scopedFolderUri,
        scopedFolderName = scopedFolderName,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun MessageEntity.toDomain() = Message(
        id = id,
        conversationId = conversationId,
        role = MessageRole.valueOf(role),
        content = json.decodeFromString<List<ContentBlock>>(contentJson),
        orderIndex = orderIndex,
        createdAt = createdAt,
    )

    private fun Message.toEntity() = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role.name,
        contentJson = json.encodeToString(content),
        orderIndex = orderIndex,
        createdAt = createdAt,
    )
}
