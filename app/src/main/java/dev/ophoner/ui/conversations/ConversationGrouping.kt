package dev.ophoner.ui.conversations

import dev.ophoner.data.model.Conversation
import dev.ophoner.data.model.ConversationMode
import dev.ophoner.data.model.PinnedFolder

/**
 * A grouping of folder-scoped conversations plus any pinned folder metadata.
 * Keyed by folder URI when available; legacy name-only groups get a `name:` prefixed key.
 */
internal data class ProjectGroup(
    val key: String,
    val uri: String,
    val name: String,
    val chats: List<Conversation>,
    val latestActivity: Long,
)

internal fun buildProjects(
    conversations: List<Conversation>,
    pinned: List<PinnedFolder>,
): List<ProjectGroup> {
    val folderConvs = conversations.filter { it.mode == ConversationMode.FOLDER }

    val byUri = folderConvs
        .filter { it.scopedFolderUri != null }
        .groupBy { it.scopedFolderUri!! }
    val byNameOnly = folderConvs
        .filter { it.scopedFolderUri == null }
        .groupBy { it.scopedFolderName ?: "Unknown folder" }

    val pinnedByUri = pinned.associateBy { it.uri }
    val allUris = pinnedByUri.keys + byUri.keys

    val uriProjects = allUris.map { uri ->
        val convs = byUri[uri].orEmpty().sortedByDescending { it.updatedAt }
        val name = convs.firstOrNull()?.scopedFolderName
            ?: pinnedByUri[uri]?.name
            ?: "folder"
        val latest = convs.maxOfOrNull { it.updatedAt }
            ?: pinnedByUri[uri]?.createdAt
            ?: 0L
        ProjectGroup(key = uri, uri = uri, name = name, chats = convs, latestActivity = latest)
    }

    val orphanNameProjects = byNameOnly.map { (name, convs) ->
        val sorted = convs.sortedByDescending { it.updatedAt }
        ProjectGroup(
            key = "name:$name",
            uri = sorted.firstOrNull()?.scopedFolderUri.orEmpty(),
            name = name,
            chats = sorted,
            latestActivity = sorted.maxOf { it.updatedAt },
        )
    }

    return (uriProjects + orphanNameProjects).sortedByDescending { it.latestActivity }
}
