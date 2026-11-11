package com.minim.fermata.android

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.minim.fermata.Fermata
import com.minim.fermata.backup.BackupArchive
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Backups to a folder the user picks with the Storage Access Framework
 * (`Intent.ACTION_OPEN_DOCUMENT_TREE`). Works on Android 9+, needs no storage permission, and the
 * files survive an uninstall. Pointing two phones at the same synced folder (Drive, Nextcloud,
 * Dropbox, Syncthing) is how multi-phone sync works.
 *
 * Status: stub. Writing one backup works; reading other phones' backups from the folder, pruning
 * old files and error reporting are still to do (see DESIGN.md, build step 3).
 */
object SafBackup {
    private const val PREFS = "fermata_backup"
    private const val KEY_TREE = "tree_uri"
    private const val WORK_NAME = "fermata-daily-backup"

    /** Call with the tree URI the picker returned. Keeps the permission across reboots. */
    fun setFolder(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, tree.toString()).apply()
    }

    fun folder(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)?.let(Uri::parse)

    /** Writes today's backup into [tree], replacing today's file if it exists. Returns its URI. */
    fun writeBackup(resolver: ContentResolver, tree: Uri, fermata: Fermata, appVersion: String?): Uri {
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = BackupArchive.fileName(LocalDate.now())
        // TODO: write to a temporary name and rename, so a half-written file never looks complete.
        findChild(resolver, tree, name)?.let { DocumentsContract.deleteDocument(resolver, it) }
        val file = DocumentsContract.createDocument(resolver, folder, BackupArchive.MIME_TYPE, name)
            ?: error("could not create $name in the backup folder")
        resolver.openOutputStream(file, "w")?.use { fermata.export(it, appVersion) }
            ?: error("could not open $name for writing")
        return file
    }

    /** Schedules the daily backup. The app must make [FermataHolder] available to the worker. */
    fun scheduleDaily(context: Context) {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun findChild(resolver: ContentResolver, tree: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        return null
    }
}

/** Implemented by the app's Application class so the worker can reach the one Fermata instance. */
interface FermataHolder {
    val fermata: Fermata
    val appVersion: String?
}

/** The daily backup job. Stub: writes one backup; merging the folder's other backups comes later. */
class BackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val holder = applicationContext as? FermataHolder ?: return Result.failure()
        val tree = SafBackup.folder(applicationContext) ?: return Result.success() // backups not set up
        return try {
            SafBackup.writeBackup(applicationContext.contentResolver, tree, holder.fermata, holder.appVersion)
            Result.success()
        } catch (e: SecurityException) {
            Result.failure() // the folder permission was revoked: the app should ask again
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
