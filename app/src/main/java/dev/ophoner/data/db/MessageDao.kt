package dev.ophoner.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Transaction
import androidx.room.Query
import dev.ophoner.data.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT orderIndex FROM messages WHERE id = :id")
    suspend fun orderOf(id: String): Int?

    @Query("SELECT COALESCE(MAX(orderIndex), -1) + 1 FROM messages WHERE conversationId = :conversationId")
    suspend fun nextOrder(conversationId: String): Int

    @Transaction
    suspend fun appendOrUpdate(message: MessageEntity) {
        insert(message.copy(orderIndex = orderOf(message.id) ?: nextOrder(message.conversationId)))
    }

    @Query("SELECT orderIndex FROM messages WHERE conversationId = :conversationId AND orderIndex < :before ORDER BY orderIndex DESC LIMIT 1 OFFSET :offset")
    suspend fun pageStart(conversationId: String, before: Int, offset: Int): Int?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND orderIndex >= :start ORDER BY orderIndex ASC, id ASC")
    fun observeFrom(conversationId: String, start: Int): Flow<List<MessageEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE conversationId = :conversationId AND orderIndex < :start)")
    suspend fun hasBefore(conversationId: String, start: Int): Boolean

    @Query("SELECT orderIndex FROM messages WHERE conversationId = :conversationId AND orderIndex > :after ORDER BY orderIndex ASC LIMIT 1 OFFSET :offset")
    suspend fun pageEnd(conversationId: String, after: Int, offset: Int): Int?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND orderIndex BETWEEN :start AND :end ORDER BY orderIndex ASC, id ASC")
    fun observeRange(conversationId: String, start: Int, end: Int): Flow<List<MessageEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE conversationId = :conversationId AND orderIndex > :end)")
    suspend fun hasAfter(conversationId: String, end: Int): Boolean

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY orderIndex ASC")
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY orderIndex ASC")
    suspend fun getByConversation(conversationId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countByConversation(conversationId: String): Int

}
