package com.minim.fermata.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.minim.fermata.Fermata
import com.minim.fermata.sync.BackupError
import com.minim.fermata.sync.BackupFolder
import com.minim.fermata.sync.BackupInfo
import com.minim.fermata.sync.BackupResult
import com.minim.fermata.sync.BackupStatus
import com.minim.fermata.sync.FolderBackups
import com.minim.fermata.sync.FolderEntry
import com.minim.fermata.sync.MergeResult
import com.minim.fermata.sync.RestoreOffer
import com.minim.fermata.sync.RestoreResult
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Backups, restore and multi-phone merge for Minim, on a folder the user picks with the Storage
 * Access Framework. This is the whole API the integration release calls; there is no UI here.
 *
 * ```
 * // Settings → Storage → Backup folder:
 * val pick = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
 *     if (uri != null) backups.setFolder(uri)       // keeps the permission, schedules the daily backup
 * }
 * backups.backUpNow()                                // blocking: call off the main thread
 * backups.status()                                   // last backup time, or the error
 * backups.listBackups()                              // every phone's backups in the folder
 * backups.restore(chosen)                            // all or nothing
 * backups.merge()                                    // pull in the other phones' new events
 * backups.restoreOffer()                             // fresh install: offer a restore?
 * ```
 *
 * Every call except [setFolder], [folder], [clearFolder], [status], [scheduleDaily] and
 * [enqueueBackUpNow] reads or writes files: call them off the main thread. Calls run one at a time
 * (the daily job included), so they are safe to make from anywhere.
 */
class FermataBackups(
    context: Context,
    private val fermata: Fermata,
    appVersion: String? = null,
) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val core = FolderBackups(fermata, PrefsBackupStateStore(prefs), appVersion)

    // --- Folder ---

    /**
     * Uses [tree] (from `ACTION_OPEN_DOCUMENT_TREE`) as the backup folder: keeps read and write
     * permission across reboots, lets go of the previous folder, and schedules the daily backup.
     */
    fun setFolder(tree: Uri) {
        val resolver = context.contentResolver
        resolver.takePersistableUriPermission(tree, RW)
        val old = folder()
        prefs.edit().putString(KEY_TREE, tree.toString()).commit()
        if (old != null && old != tree) {
            runCatching { resolver.releasePersistableUriPermission(old, RW) }
        }
        scheduleDaily()
    }

    /** The folder the user picked, or null. It may have lost its permission: see [hasFolderAccess]. */
    fun folder(): Uri? = prefs.getString(KEY_TREE, null)?.let(Uri::parse)

    /** True when a folder is set and the app still holds read and write permission for it. */
    fun hasFolderAccess(): Boolean {
        val tree = folder() ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }
    }

    /** Stops backing up: forgets the folder, lets go of its permission, cancels the daily job. */
    fun clearFolder() {
        val old = folder()
        prefs.edit().remove(KEY_TREE).commit()
        old?.let { runCatching { context.contentResolver.releasePersistableUriPermission(it, RW) } }
        WorkManager.getInstance(context).cancelUniqueWork(DAILY_WORK)
    }

    // --- Backup ---

    /** Backs up now, blocking until it is done. The result is also kept for [status]. */
    fun backUpNow(): BackupResult = core.backUp(backupFolder())

    /**
     * "Back up now" as background work: merges the other phones' backups, then backs up, even if
     * the app is closed. A second tap while one is queued does nothing. Watch the id with
     * WorkManager, or read [status] afterwards.
     */
    fun enqueueBackUpNow(): UUID {
        val request = OneTimeWorkRequestBuilder<BackupWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    /** When the last backup worked, and whether the last attempt failed and why. */
    fun status(): BackupStatus = core.status()

    /**
     * The daily backup: once a day while charging and the battery is not low; it merges the other
     * phones' backups first. [setFolder] calls this; calling it again only updates the schedule.
     */
    fun scheduleDaily() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DAILY_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            dailyRequest(),
        )
    }

    // --- Restore and merge ---

    /** Every backup in the folder, from any phone, newest first (read from their manifests). */
    fun listBackups(): List<BackupInfo> {
        val folder = backupFolder() ?: return emptyList()
        return try {
            core.list(folder)
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** Restores [backup] (from [listBackups]). Checked in full first; on any failure nothing changes. */
    fun restore(backup: BackupInfo): RestoreResult {
        val folder = backupFolder() ?: return RestoreResult.Failure(BackupError.NO_FOLDER, "No backup folder picked yet")
        return core.restore(folder, backup)
    }

    /** Adds the events this phone has not seen from the other phones' latest backups. */
    fun merge(): MergeResult = core.mergeFromFolder(backupFolder())

    /** On a fresh install (empty library): whether to offer "Restore from a backup", and what. */
    fun restoreOffer(): RestoreOffer = core.restoreOffer(backupFolder())

    /** The user said no to the restore offer: don't offer it again on this install. */
    fun dismissRestoreOffer() = core.dismissRestoreOffer()

    /** The daily job's work: merge, then back up. */
    internal fun runScheduled(): Pair<MergeResult, BackupResult> = core.mergeAndBackUp(backupFolder())

    /** The folder to use: null when none is set, one that reports lost access when the permission is gone. */
    private fun backupFolder(): BackupFolder? {
        val tree = folder() ?: return null
        if (!hasFolderAccess()) return LostFolder
        return SafBackupFolder(context.contentResolver, tree)
    }

    /** A folder whose permission was revoked: every call fails the way SAF does. */
    private object LostFolder : BackupFolder {
        private fun lost(): Nothing = throw SecurityException("no persisted permission for the backup folder")
        override fun list(): List<FolderEntry> = lost()
        override fun openInput(entry: FolderEntry) = lost()
        override fun create(name: String) = lost()
        override fun openOutput(entry: FolderEntry) = lost()
        override fun rename(entry: FolderEntry, newName: String) = lost()
        override fun delete(entry: FolderEntry) = lost()
    }

    companion object {
        internal const val PREFS = "fermata_backup"
        private const val KEY_TREE = "tree_uri"
        const val DAILY_WORK = "fermata-daily-backup"
        const val NOW_WORK = "fermata-backup-now"
        private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        internal fun dailyRequest() = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
    }
}

/** Implemented by the app's Application class so the worker can reach the one Fermata instance. */
interface FermataHolder {
    val fermata: Fermata
    val appVersion: String?
}

/**
 * The daily backup and "Back up now": merges the other phones' backups, then backs up. A missing
 * folder or lost permission is not retried (the status shows it and Settings asks the user); other
 * failures are retried twice with WorkManager's backoff, then wait for the next day.
 */
class BackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val holder = applicationContext as? FermataHolder ?: return Result.failure()
        val (_, backup) = FermataBackups(applicationContext, holder.fermata, holder.appVersion).runScheduled()
        return when {
            backup is BackupResult.Success -> Result.success()
            backup is BackupResult.Failure && backup.error in NOT_RETRIED -> Result.success()
            runAttemptCount < MAX_RETRIES -> Result.retry()
            else -> Result.success()
        }
    }

    private companion object {
        const val MAX_RETRIES = 2
        val NOT_RETRIED = setOf(BackupError.NO_FOLDER, BackupError.FOLDER_ACCESS_LOST)
    }
}
