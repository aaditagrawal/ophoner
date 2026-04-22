package dev.ophoner.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [
        Index("providerConfigId"),
        Index("createdAt"),
    ],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val providerConfigId: String,
    val mode: String = "general",       // "general" or "folder"
    val scopedFolderUri: String? = null, // SAF URI for folder mode
    val scopedFolderName: String? = null, // display name
    val createdAt: Long,
    val updatedAt: Long,
)
