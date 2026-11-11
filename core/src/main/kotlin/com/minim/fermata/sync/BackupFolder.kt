package com.minim.fermata.sync

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

/**
 * A file in a [BackupFolder]. [ref] is the folder's own handle for it (a document URI on Android,
 * a path here); [size] and [lastModified] are as of the listing, -1 / 0 when unknown.
 */
data class FolderEntry(val name: String, val ref: String, val size: Long = -1, val lastModified: Long = 0)

/**
 * The folder backups go to: flat, files only. On Android it is a Storage Access Framework tree
 * the user picked (`SafBackupFolder` in the android module); [FileBackupFolder] serves tests and
 * JVM tools. The rules match what SAF providers can actually promise:
 *
 * - [create] makes a new file with exactly that name; it fails if the name is taken.
 * - [rename] gives exactly the new name or fails; a file with that name must not exist (SAF has
 *   no "rename over"), so callers delete the target first.
 * - Nothing is atomic across calls, and another phone may change its own files at any moment.
 *
 * Implementations throw [FileNotFoundException] for a file that is gone, [SecurityException] when
 * access to the folder was lost, and [IOException] for everything else.
 */
interface BackupFolder {
    fun list(): List<FolderEntry>

    fun openInput(entry: FolderEntry): InputStream

    fun create(name: String): FolderEntry

    /** Opens [entry] for writing from the start (it is a file [create] just made). */
    fun openOutput(entry: FolderEntry): OutputStream

    fun rename(entry: FolderEntry, newName: String): FolderEntry

    fun delete(entry: FolderEntry)
}

/** A [BackupFolder] on a plain directory, for tests and JVM tools. */
class FileBackupFolder(private val dir: File) : BackupFolder {
    init {
        require(dir.isDirectory) { "not a directory: $dir" }
    }

    override fun list(): List<FolderEntry> =
        (dir.listFiles() ?: throw IOException("cannot list $dir")).filter { it.isFile }.map(::entry)

    override fun openInput(entry: FolderEntry): InputStream = File(entry.ref).inputStream()

    override fun create(name: String): FolderEntry {
        val file = File(dir, name)
        if (!file.createNewFile()) throw IOException("$name already exists")
        return entry(file)
    }

    override fun openOutput(entry: FolderEntry): OutputStream {
        val file = File(entry.ref)
        if (!file.exists()) throw FileNotFoundException(entry.name)
        return file.outputStream()
    }

    override fun rename(entry: FolderEntry, newName: String): FolderEntry {
        val target = File(dir, newName)
        // Like SAF, a rename never overwrites. (An atomic move on Linux would replace silently.)
        if (target.exists()) throw IOException("$newName already exists")
        try {
            Files.move(File(entry.ref).toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: NoSuchFileException) {
            throw FileNotFoundException(entry.name)
        } catch (e: FileAlreadyExistsException) {
            throw IOException("$newName already exists", e)
        }
        return entry(target)
    }

    override fun delete(entry: FolderEntry) {
        val file = File(entry.ref)
        if (!file.exists()) throw FileNotFoundException(entry.name)
        if (!file.delete()) throw IOException("cannot delete ${entry.name}")
    }

    private fun entry(file: File) = FolderEntry(file.name, file.path, file.length(), file.lastModified())
}
