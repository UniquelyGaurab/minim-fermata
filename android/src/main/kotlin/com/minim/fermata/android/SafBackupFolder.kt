package com.minim.fermata.android

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.minim.fermata.sync.BackupFolder
import com.minim.fermata.sync.FolderEntry
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A [BackupFolder] on a Storage Access Framework tree the user picked with
 * `Intent.ACTION_OPEN_DOCUMENT_TREE`. [FolderEntry.ref] is the document URI.
 *
 * SAF providers differ, so this stays on the safe side:
 * - files are created as `application/octet-stream`, the one type for which providers keep the
 *   name exactly as given (with a known type, some add their own extension);
 * - when a provider hands back a different name (`latest (1).minim` because the name was taken),
 *   a new file is removed again, a renamed one gets its old name back, and the call fails, so
 *   callers never act on the wrong file;
 * - output is synced to disk before it is closed, where the provider gives a real file.
 */
class SafBackupFolder(private val resolver: ContentResolver, val tree: Uri) : BackupFolder {
    private val folderDoc: Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    override fun list(): List<FolderEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<FolderEntry>()
        val cursor = resolver.query(children, COLUMNS, null, null)
            ?: throw IOException("the backup folder could not be listed")
        cursor.use { c ->
            while (c.moveToNext()) {
                if (c.getString(2) == Document.MIME_TYPE_DIR) continue
                val name = c.getString(1) ?: continue
                out += FolderEntry(
                    name = name,
                    ref = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString(),
                    size = if (c.isNull(3)) -1 else c.getLong(3),
                    lastModified = if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        }
        return out
    }

    override fun openInput(entry: FolderEntry): InputStream =
        resolver.openInputStream(Uri.parse(entry.ref)) ?: throw FileNotFoundException(entry.name)

    override fun create(name: String): FolderEntry {
        val uri = DocumentsContract.createDocument(resolver, folderDoc, MIME, name)
            ?: throw IOException("could not create $name in the backup folder")
        val entry = entryFor(uri)
        if (entry.name != name) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw IOException("$name already exists (the folder offered ${entry.name})")
        }
        return entry
    }

    override fun openOutput(entry: FolderEntry): OutputStream {
        val uri = Uri.parse(entry.ref)
        val pfd = try {
            resolver.openFileDescriptor(uri, "w")
        } catch (e: UnsupportedOperationException) {
            null
        }
        if (pfd != null) return SyncingOutputStream(pfd)
        return resolver.openOutputStream(uri, "w") ?: throw FileNotFoundException(entry.name)
    }

    override fun rename(entry: FolderEntry, newName: String): FolderEntry {
        val uri = DocumentsContract.renameDocument(resolver, Uri.parse(entry.ref), newName)
            ?: Uri.parse(entry.ref) // null: renamed in place, the URI did not change
        val renamed = entryFor(uri)
        if (renamed.name != newName) {
            // The name was taken and the provider picked another: put the old name back.
            runCatching { DocumentsContract.renameDocument(resolver, uri, entry.name) }
            throw IOException("$newName already exists (the folder offered ${renamed.name})")
        }
        return renamed
    }

    override fun delete(entry: FolderEntry) {
        if (!DocumentsContract.deleteDocument(resolver, Uri.parse(entry.ref))) {
            throw IOException("could not delete ${entry.name}")
        }
    }

    private fun entryFor(uri: Uri): FolderEntry {
        val cursor = resolver.query(uri, COLUMNS, null, null) ?: throw FileNotFoundException(uri.toString())
        cursor.use { c ->
            if (!c.moveToFirst()) throw FileNotFoundException(uri.toString())
            return FolderEntry(
                name = c.getString(1),
                ref = uri.toString(),
                size = if (c.isNull(3)) -1 else c.getLong(3),
                lastModified = if (c.isNull(4)) 0 else c.getLong(4),
            )
        }
    }

    /** Writes through a file descriptor and syncs it before closing, so a written backup is on disk. */
    private class SyncingOutputStream(private val pfd: ParcelFileDescriptor) : FileOutputStream(pfd.fileDescriptor) {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            try {
                flush()
                try {
                    getFD().sync()
                } catch (e: IOException) {
                    // Pipes and some providers can't sync (SyncFailedException); the provider commits on close.
                }
            } finally {
                try {
                    super.close()
                } finally {
                    pfd.close() // reports provider-side write errors (closeWithError)
                }
            }
        }
    }

    companion object {
        private const val MIME = "application/octet-stream"
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
