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
        conversationDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }

    fun observeMessages(conversationId: String): Flow<List<Message>> =
        messageDao.observeByConversation(conversationId).map { entities ->
            entities.map { it.toDomain() }
        }

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

    suspend fun updateTitle(conversationId: String, title: String) {
        conversationDao.updateTitle(conversationId, title, System.currentTimeMillis())
    }

    suspend fun deleteConversation(conversationId: String) {
        conversationDao.deleteById(conversationId)
    }

    suspend fun saveMessage(message: Message) {
        messageDao.insert(message.toEntity())
    }

    suspend fun saveMessages(messages: List<Message>) {
        messageDao.insertAll(messages.map { it.toEntity() })
    }

    suspend fun getMessages(conversationId: String): List<Message> =
        messageDao.getByConversation(conversationId).map { it.toDomain() }

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
