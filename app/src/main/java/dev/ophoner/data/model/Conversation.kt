package dev.ophoner.data.model

enum class ConversationMode { GENERAL, FOLDER }

data class Conversation(
    val id: String,
    val title: String,
    val providerConfigId: String,
    val mode: ConversationMode = ConversationMode.GENERAL,
    val scopedFolderUri: String? = null,
    val scopedFolderName: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
