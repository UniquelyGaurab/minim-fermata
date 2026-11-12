package com.minim.fermata.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
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
 *
 * // Automatic backups (main thread is fine: these only queue WorkManager jobs):
 * backups.enqueueMergeOnOpen()                       // app came to the foreground
 * backups.enqueueBackupOnBackground()                // app went to the background
 * ```
 *
 * Every call except [setFolder], [folder], [clearFolder], [status], [scheduleDaily] and the
 * `enqueue…` calls reads or writes files: call them off the main thread. Calls run one at a time
 * (every job included: Fermata holds one lock for all backups, merges and restores in the
 * process), so they are safe to make from anywhere.
 *
 * The WorkManager jobs, each under its own unique name:
 * - [DAILY_WORK]: periodic, once a day while charging; merge, then [backUpIfChanged].
 * - [NOW_WORK]: "Back up now" in the background; merge, then a full backup. KEEP: a second tap
 *   while one is queued or running does nothing. Expedited.
 * - [ON_CLOSE_WORK]: the backup when the app goes to the background; [backUpIfChanged] only.
 *   REPLACE: a newer close takes the place of one still waiting. Expedited.
 * - [MERGE_WORK]: the merge when the app opens; manifests first, see [merge]. KEEP. Expedited.
 * - [ONCE_WORK]: startup and after a history clear (see [enqueueBackupOnce]).
 *
 * Expedited jobs run right away on Android 12 and later, and fall back to ordinary one-time work
 * when the app is out of expedited quota. On Android 9 to 11 they are queued as ordinary one-time
 * work, which those versions run right away with no constraints (an expedited job there would need
 * a foreground notification). WorkManager keeps every job across the app being killed.
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
    fun backUpNow(replacePrevious: Boolean = false): BackupResult = core.backUp(backupFolder(), replacePrevious)

    /**
     * Backs up only if the log has something this phone's `latest` backup lacks (compares the
     * version vector and event count with that file's manifest), blocking. When nothing changed it
     * writes nothing and returns a [BackupResult.Success] with `written = false`.
     */
    fun backUpIfChanged(): BackupResult = core.backUpIfChanged(backupFolder())

    /**
     * The backup for when the app goes to the background: an expedited one-time job that runs
     * [FermataHolder.beforeBackup], then [backUpIfChanged], and survives the app being killed
     * right after. A newer call replaces one still waiting. Does nothing without a folder.
     */
    fun enqueueBackupOnBackground(): UUID? {
        if (folder() == null) return null
        val request = expedited(BackupWorker.MODE_ON_CLOSE)
        WorkManager.getInstance(context).enqueueUniqueWork(ON_CLOSE_WORK, ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    /**
     * The merge for when the app opens: an expedited one-time job that runs [merge] (cheap when
     * nothing is new: only manifests are read) and calls [FermataHolder.onFermataChanged] when it
     * added events. A call while one is queued or running does nothing. Does nothing without a folder.
     */
    fun enqueueMergeOnOpen(): UUID? {
        if (folder() == null) return null
        val request = expedited(BackupWorker.MODE_MERGE)
        WorkManager.getInstance(context).enqueueUniqueWork(MERGE_WORK, ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    /**
     * "Back up now" as background work: merges the other phones' backups, then backs up, even if
     * the app is closed. A second tap while one is queued does nothing. Watch the id with
     * WorkManager, or read [status] afterwards.
     */
    fun enqueueBackUpNow(): UUID {
        val request = expedited(BackupWorker.MODE_NOW)
        WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    /**
     * A one-time backup as background work, for backups the app starts itself (at startup, or
     * right after a history clear): WorkManager runs it once even if the app is closed or killed
     * meanwhile, and retries it like the daily one. It merges the other phones' backups first.
     * With [replacePrevious] (after a clear) it also replaces this phone's `previous` file; a
     * request with it replaces one still queued, so the replacement is never lost to a plain one.
     */
    fun enqueueBackupOnce(replacePrevious: Boolean = false): UUID {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(workDataOf(BackupWorker.REPLACE_PREVIOUS to replacePrevious))
            .build()
        val policy = if (replacePrevious) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE_WORK, policy, request)
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

    /**
     * Adds the events this phone has not seen from the other phones' latest backups. Reads each
     * backup's manifest first and reads the whole file only when its version vector (or its
     * legacy events) has something new, so it is cheap when nothing is.
     */
    fun merge(): MergeResult = core.mergeFromFolder(backupFolder())

    /** On a fresh install (empty library): whether to offer "Restore from a backup", and what. */
    fun restoreOffer(): RestoreOffer = core.restoreOffer(backupFolder())

    /** The user said no to the restore offer: don't offer it again on this install. */
    fun dismissRestoreOffer() = core.dismissRestoreOffer()

    /** The daily job's work: merge, then back up (only if changed, unless [onlyIfChanged] is false). */
    internal fun runScheduled(replacePrevious: Boolean = false, onlyIfChanged: Boolean = true): Pair<MergeResult, BackupResult> =
        core.mergeAndBackUp(backupFolder(), replacePrevious, onlyIfChanged)

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
        const val ONCE_WORK = "fermata-backup-once"
        const val ON_CLOSE_WORK = "fermata-backup-on-close"
        const val MERGE_WORK = "fermata-merge-on-open"
        private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        /** A one-time job in [mode]: expedited on Android 12+, with ordinary work as the fallback. */
        internal fun expedited(mode: String, sdk: Int = Build.VERSION.SDK_INT): OneTimeWorkRequest {
            val b = OneTimeWorkRequestBuilder<BackupWorker>().setInputData(workDataOf(BackupWorker.MODE to mode))
            if (sdk >= Build.VERSION_CODES.S) b.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            return b.build()
        }

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

    /**
     * Called after background work changed the log behind the app's back (the daily job merged
     * events from another phone's backup), so the app can refresh what it built from the log.
     */
    fun onFermataChanged() {}

    /**
     * Called by the daily job before it merges and backs up, so the app can first record anything
     * it still holds back (Minim records some history changes in batches). Failures are ignored.
     */
    fun beforeBackup() {}
}

/**
 * Every backup job (see [FermataBackups] for which runs when). By [MODE]: the daily job and the
 * startup one merge, then back up only if something changed; "Back up now" and the one after a
 * clear merge, then always back up; the on-close one only backs up if something changed; the
 * on-open one only merges. A missing folder or lost permission is not retried (the status shows it
 * and Settings asks the user); other failures are retried twice with WorkManager's backoff.
 */
class BackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val holder = applicationContext as? FermataHolder ?: return Result.failure()
        val fermata = try {
            holder.fermata
        } catch (e: Exception) {
            return Result.failure() // the app could not start Fermata: nothing to back up
        }
        val backups = FermataBackups(applicationContext, fermata, holder.appVersion)
        val replace = inputData.getBoolean(REPLACE_PREVIOUS, false)
        val mode = inputData.getString(MODE) ?: MODE_SCHEDULED
        if (mode == MODE_MERGE) {
            val merge = backups.merge()
            if (merge.eventsAdded > 0) runCatching { holder.onFermataChanged() }
            return Result.success() // the status shows a failed merge; the next open tries again
        }
        runCatching { holder.beforeBackup() }
        val backup = if (mode == MODE_ON_CLOSE) {
            backups.backUpIfChanged()
        } else {
            val (merge, result) = backups.runScheduled(replace, onlyIfChanged = mode == MODE_SCHEDULED && !replace)
            if (merge.eventsAdded > 0) runCatching { holder.onFermataChanged() }
            result
        }
        return when {
            backup is BackupResult.Success -> Result.success()
            backup is BackupResult.Failure && backup.error in NOT_RETRIED -> Result.success()
            runAttemptCount < MAX_RETRIES -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        /** Input: also replace this phone's `previous` file (see [FermataBackups.enqueueBackupOnce]). */
        const val REPLACE_PREVIOUS = "replace_previous"

        /** Input: which job this is. Missing means [MODE_SCHEDULED] (jobs queued by older versions). */
        const val MODE = "mode"
        const val MODE_SCHEDULED = "scheduled"
        const val MODE_NOW = "now"
        const val MODE_ON_CLOSE = "on_close"
        const val MODE_MERGE = "merge"
        private const val MAX_RETRIES = 2
        private val NOT_RETRIED = setOf(BackupError.NO_FOLDER, BackupError.FOLDER_ACCESS_LOST)
    }
}
