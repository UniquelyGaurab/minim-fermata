package com.minim.fermata.android

import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * A Storage Access Framework provider over a plain directory, behaving like Android's own
 * ExternalStorageProvider where it matters: a create or rename onto a taken name gets
 * `name (1).ext` instead of failing, and a create with a known MIME type may add an extension.
 * Switches let tests make it fail.
 */
class TestDocumentsProvider : DocumentsProvider() {
    /** Flip one byte when a freshly written temp file is read back (a bad provider or disk). */
    @Volatile var corruptReads = false

    /** Make renames to this name fail. */
    @Volatile var failRenameTo: String? = null

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf("root_id"))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: COLUMNS)
        row(c, documentId, file(documentId))
        return c
    }

    override fun queryChildDocuments(parentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: COLUMNS)
        for (f in (dir.listFiles() ?: emptyArray()).sortedBy { it.name }) row(c, f.name, f)
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val f = file(documentId)
        if (!f.exists()) throw FileNotFoundException(documentId)
        if (corruptReads && mode == "r" && f.name.endsWith(".tmp")) {
            // Hand back a copy with byte 200 flipped, as a bad provider or disk would.
            val bytes = f.readBytes()
            if (bytes.size > 200) bytes[200] = (bytes[200].toInt() xor 0xFF).toByte()
            val copy = File.createTempFile("corrupt", ".bin").apply { writeBytes(bytes); deleteOnExit() }
            return ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        var name = displayName
        if (mimeType == "application/zip" && !name.endsWith(".zip")) name += ".zip"
        val f = unique(name)
        if (!f.createNewFile()) throw IOException("cannot create $name")
        return f.name
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        if (displayName == failRenameTo) throw IllegalStateException("rename refused")
        val from = file(documentId)
        if (!from.exists()) throw FileNotFoundException(documentId)
        val to = unique(displayName)
        if (!from.renameTo(to)) throw IllegalStateException("rename failed")
        return to.name
    }

    override fun deleteDocument(documentId: String) {
        val f = file(documentId)
        if (!f.delete()) throw FileNotFoundException(documentId)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == ROOT && documentId != ROOT

    private fun file(documentId: String): File = if (documentId == ROOT) dir else File(dir, documentId)

    private fun unique(name: String): File {
        var f = File(dir, name)
        var n = 1
        val dot = name.lastIndexOf('.')
        while (f.exists()) {
            f = File(dir, if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)")
            n++
        }
        return f
    }

    private fun row(c: MatrixCursor, id: String, f: File) {
        if (!f.exists()) return
        val values = c.columnNames.map {
            when (it) {
                Document.COLUMN_DOCUMENT_ID -> id
                Document.COLUMN_DISPLAY_NAME -> if (id == ROOT) "Backups" else f.name
                Document.COLUMN_MIME_TYPE -> if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
                Document.COLUMN_SIZE -> f.length()
                Document.COLUMN_LAST_MODIFIED -> f.lastModified()
                Document.COLUMN_FLAGS -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or
                    Document.FLAG_SUPPORTS_RENAME or Document.FLAG_DIR_SUPPORTS_CREATE
                else -> null
            }
        }
        c.addRow(values)
    }

    companion object {
        const val AUTHORITY = "com.minim.fermata.test.documents"
        const val ROOT = "root"
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        /** The directory every instance serves (one per test). */
        lateinit var dir: File

        val tree: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT)

        fun register(directory: File): TestDocumentsProvider {
            dir = directory
            val info = ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
                writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
            }
            return Robolectric.buildContentProvider(TestDocumentsProvider::class.java).create(info).get()
        }
    }
}
