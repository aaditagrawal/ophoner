package dev.ophoner.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.ophoner.data.entity.ConversationEntity
import dev.ophoner.data.entity.MessageEntity

// TODO: Add ksp { arg("room.schemaLocation", "$projectDir/schemas") } to app/build.gradle.kts for schema export
@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class OphoneDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}
