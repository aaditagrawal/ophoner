package dev.ophoner.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PinnedFolder(
    val uri: String,
    val name: String,
    val createdAt: Long,
)
