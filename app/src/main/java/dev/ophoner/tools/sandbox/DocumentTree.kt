package dev.ophoner.tools.sandbox

/**
 * One documents-provider directory page.
 *
 * The old document-file helpers listed child ids, then each
 * child loads name, MIME type, and size with its own query. A flat directory is
 * therefore one query plus about four more per file. Callers instead project
 * document id, display name, MIME type, and size in a single child-cursor query
 * per directory (one query per path segment when walking). Rows are indexed in a
 * map for that call only. Nothing is retained: a provider, or another app, can
 * rename or replace a document between calls, and a cached URI would be wrong.
 */
interface DocumentTree {
    fun rootDocumentId(treeUri: String): String?

    fun listChildren(treeUri: String, parentDocumentId: String): DirectoryPage

    fun openInput(treeUri: String, documentId: String): java.io.InputStream?

    fun openOutput(treeUri: String, documentId: String, mode: String): java.io.OutputStream?

    fun createFile(treeUri: String, parentDocumentId: String, mimeType: String, displayName: String): DocumentEntry?

    fun createDirectory(treeUri: String, parentDocumentId: String, displayName: String): DocumentEntry?

    fun delete(treeUri: String, documentId: String): Boolean

    /**
     * Provider rename of a document inside its current directory.
     * Returns false when the provider does not support rename or the name is taken.
     */
    fun rename(treeUri: String, documentId: String, displayName: String): Boolean
}

data class DocumentEntry(
    val documentId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val supportsRename: Boolean,
) {
    val isDirectory: Boolean get() = mimeType == DOCUMENT_MIME_DIR
}

data class DirectoryPage(
    val children: List<DocumentEntry>,
    /** True when the provider is still loading or otherwise returned a partial page. */
    val incomplete: Boolean = false,
    /** Non-null when the provider failed. An empty [children] list is then not an empty directory. */
    val error: String? = null,
)

const val DOCUMENT_MIME_DIR = "vnd.android.document/directory"

/** First display name wins, matching the previous find-by-name behavior. */
internal fun indexByName(children: List<DocumentEntry>): Map<String, DocumentEntry> {
    val indexed = HashMap<String, DocumentEntry>(children.size)
    for (child in children) indexed.putIfAbsent(child.name, child)
    return indexed
}

/**
 * Relative path segments under a granted tree.
 * Returns null when the path contains `..` or a NUL, which would leave the root or the name.
 */
internal fun relativeSegments(path: String): List<String>? {
    if (path.indexOf('\u0000') >= 0) return null
    val parts = ArrayList<String>(4)
    var start = 0
    val text = path
    while (start <= text.length) {
        val slash = text.indexOf('/', start).let { if (it < 0) text.length else it }
        if (slash > start) {
            val part = text.substring(start, slash)
            if (part == "..") return null
            if (part != ".") parts.add(part)
        }
        if (slash == text.length) break
        start = slash + 1
    }
    return parts
}
