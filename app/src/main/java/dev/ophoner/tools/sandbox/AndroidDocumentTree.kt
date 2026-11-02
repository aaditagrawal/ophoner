package dev.ophoner.tools.sandbox

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage Access Framework adapter. Each directory is one child-documents query
 * with a covering projection. A page that is still loading is observed once and
 * re-queried, bounded, because cloud providers fill the cursor after notifyChange.
 */
@Singleton
class AndroidDocumentTree @Inject constructor(
    @ApplicationContext private val context: Context,
) : DocumentTree {
    private val resolver get() = context.contentResolver

    override fun rootDocumentId(treeUri: String): String? = try {
        DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override fun listChildren(treeUri: String, parentDocumentId: String): DirectoryPage {
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(treeUri), parentDocumentId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DirectoryPage(emptyList(), incomplete = true, error = e.message ?: "Invalid document tree")
        }
        val first = queryChildren(childrenUri)
        if (first.error != null || !first.incomplete) return first
        val arrived = CountDownLatch(1)
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                arrived.countDown()
            }
        }
        try {
            resolver.registerContentObserver(childrenUri, false, observer)
            arrived.await(LOADING_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return first
        } finally {
            runCatching { resolver.unregisterContentObserver(observer) }
        }
        return queryChildren(childrenUri)
    }

    override fun openInput(treeUri: String, documentId: String) = try {
        resolver.openInputStream(documentUri(treeUri, documentId))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override fun openOutput(treeUri: String, documentId: String, mode: String) = try {
        resolver.openOutputStream(documentUri(treeUri, documentId), mode)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override fun createFile(
        treeUri: String,
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): DocumentEntry? = create(treeUri, parentDocumentId, mimeType, displayName)

    override fun createDirectory(treeUri: String, parentDocumentId: String, displayName: String): DocumentEntry? =
        create(treeUri, parentDocumentId, DOCUMENT_MIME_DIR, displayName)

    override fun delete(treeUri: String, documentId: String): Boolean = try {
        DocumentsContract.deleteDocument(resolver, documentUri(treeUri, documentId))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    override fun rename(treeUri: String, documentId: String, displayName: String): Boolean = try {
        DocumentsContract.renameDocument(resolver, documentUri(treeUri, documentId), displayName) != null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private fun create(treeUri: String, parentDocumentId: String, mimeType: String, displayName: String): DocumentEntry? =
        try {
            val created = DocumentsContract.createDocument(
                resolver,
                documentUri(treeUri, parentDocumentId),
                mimeType,
                displayName,
            ) ?: return null
            DocumentEntry(
                documentId = DocumentsContract.getDocumentId(created),
                name = displayName,
                mimeType = mimeType,
                size = 0L,
                supportsRename = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private fun documentUri(treeUri: String, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), documentId)

    private fun queryChildren(childrenUri: Uri): DirectoryPage = try {
        val cursor = resolver.query(childrenUri, PROJECTION, null, null, null)
            ?: return DirectoryPage(emptyList(), incomplete = true, error = "Documents provider returned no cursor")
        cursor.use { rows ->
            val idColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val flagsColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            if (idColumn < 0) {
                return DirectoryPage(emptyList(), incomplete = true, error = "Documents provider omitted document ids")
            }
            val children = ArrayList<DocumentEntry>(if (rows.count > 0) rows.count else 16)
            while (rows.moveToNext()) {
                val id = rows.getString(idColumn) ?: continue
                val flags = if (flagsColumn >= 0 && !rows.isNull(flagsColumn)) rows.getInt(flagsColumn) else 0
                children.add(
                    DocumentEntry(
                        documentId = id,
                        name = if (nameColumn >= 0 && !rows.isNull(nameColumn)) rows.getString(nameColumn).orEmpty() else "",
                        mimeType = if (mimeColumn >= 0 && !rows.isNull(mimeColumn)) {
                            rows.getString(mimeColumn) ?: "application/octet-stream"
                        } else {
                            "application/octet-stream"
                        },
                        size = if (sizeColumn >= 0 && !rows.isNull(sizeColumn)) rows.getLong(sizeColumn) else 0L,
                        supportsRename = flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0,
                    ),
                )
            }
            val extras = runCatching { rows.extras }.getOrNull()
            val loading = extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
            val error = extras?.getString(DocumentsContract.EXTRA_ERROR)
            DirectoryPage(children, incomplete = loading || error != null, error = error)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DirectoryPage(emptyList(), incomplete = true, error = e.message ?: "Documents provider query failed")
    }

    private companion object {
        const val LOADING_WAIT_MS = 300L
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
