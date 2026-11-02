package dev.ophoner.tools.sandbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** In-memory documents provider. Each [listChildren] call counts as one directory query. */
class MemoryDocumentTree(
    val uri: String = "content://test/tree/root",
) : DocumentTree {
    private class Node(
        val id: String,
        var name: String,
        var mimeType: String,
        var parentId: String?,
        var bytes: ByteArray,
        var supportsRename: Boolean,
    )

    private val nodes = LinkedHashMap<String, Node>()
    private var nextId = 1

    var listCalls = 0
    var renameCalls = 0
    var outputOpens = 0
    var createCalls = 0
    var errorMessage: String? = null
    var incompleteIds: Set<String> = emptySet()

    init {
        nodes["root"] = Node("root", "", DOCUMENT_MIME_DIR, null, ByteArray(0), false)
    }

    fun addDir(parentId: String, name: String): String =
        add(parentId, name, DOCUMENT_MIME_DIR, ByteArray(0), supportsRename = true)

    fun addFile(parentId: String, name: String, text: String, supportsRename: Boolean = true): String =
        add(parentId, name, "text/plain", text.toByteArray(Charsets.UTF_8), supportsRename)

    fun setText(id: String, text: String) {
        nodes.getValue(id).bytes = text.toByteArray(Charsets.UTF_8)
    }

    private fun add(parentId: String, name: String, mimeType: String, bytes: ByteArray, supportsRename: Boolean): String {
        check(parentId in nodes) { "Missing parent $parentId" }
        val id = "doc${nextId++}"
        nodes[id] = Node(id, name, mimeType, parentId, bytes, supportsRename)
        return id
    }

    override fun rootDocumentId(treeUri: String): String? = if (treeUri == uri) "root" else null

    override fun listChildren(treeUri: String, parentDocumentId: String): DirectoryPage {
        listCalls++
        if (treeUri != uri) return DirectoryPage(emptyList(), incomplete = true, error = "Outside the granted tree")
        errorMessage?.let { return DirectoryPage(emptyList(), incomplete = true, error = it) }
        if (parentDocumentId !in nodes) return DirectoryPage(emptyList(), incomplete = true, error = "Missing parent")
        val children = nodes.values.filter { it.parentId == parentDocumentId }.map { it.toEntry() }
        return DirectoryPage(children, incomplete = parentDocumentId in incompleteIds)
    }

    override fun openInput(treeUri: String, documentId: String): InputStream? {
        val node = nodes[documentId] ?: return null
        return ByteArrayInputStream(node.bytes.copyOf())
    }

    override fun openOutput(treeUri: String, documentId: String, mode: String): OutputStream? {
        val node = nodes[documentId] ?: return null
        outputOpens++
        return object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                node.bytes = toByteArray()
            }
        }
    }

    override fun createFile(treeUri: String, parentDocumentId: String, mimeType: String, displayName: String): DocumentEntry? =
        create(parentDocumentId, displayName, mimeType)

    override fun createDirectory(treeUri: String, parentDocumentId: String, displayName: String): DocumentEntry? =
        create(parentDocumentId, displayName, DOCUMENT_MIME_DIR)

    override fun delete(treeUri: String, documentId: String): Boolean {
        if (documentId == "root" || documentId !in nodes) return false
        fun drop(id: String) {
            nodes.values.filter { it.parentId == id }.map { it.id }.forEach { drop(it) }
            nodes.remove(id)
        }
        drop(documentId)
        return true
    }

    override fun rename(treeUri: String, documentId: String, displayName: String): Boolean {
        renameCalls++
        val node = nodes[documentId] ?: return false
        if (!node.supportsRename) return false
        val taken = nodes.values.any { it.parentId == node.parentId && it.name == displayName && it.id != node.id }
        if (taken) return false
        node.name = displayName
        return true
    }

    private fun create(parentId: String, name: String, mimeType: String): DocumentEntry? {
        createCalls++
        val parent = nodes[parentId] ?: return null
        if (!parent.mimeType.startsWith(DOCUMENT_MIME_DIR)) return null
        return nodes.getValue(add(parentId, name, mimeType, ByteArray(0), supportsRename = true)).toEntry()
    }

    private fun Node.toEntry() = DocumentEntry(id, name, mimeType, bytes.size.toLong(), supportsRename)
}
