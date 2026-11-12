package com.minim.fermata.sync

import com.minim.fermata.backup.BackupRole

/** Why a backup, restore or merge did not work. */
enum class BackupError {
    /** No backup folder has been picked yet. */
    NO_FOLDER,

    /** The app lost its permission for the folder (or the folder is gone): pick it again. */
    FOLDER_ACCESS_LOST,

    /** Writing the new backup failed (disk full, provider error). The old backup is untouched. */
    WRITE_FAILED,

    /** The new backup did not read back correctly, so it was thrown away. The old backup is untouched. */
    VERIFY_FAILED,

    /** The new backup was good but could not be moved into place. The old backup is still `latest`. */
    RENAME_FAILED,

    /** The folder could not be listed (a provider error); try again later. */
    FOLDER_UNREADABLE,

    /** The chosen backup file is gone. */
    NOT_FOUND,

    /** The chosen backup is damaged, half-synced, or not a Minim backup. Nothing was changed. */
    DAMAGED,

    /** The chosen backup was made by a newer version that this one cannot read. Nothing was changed. */
    TOO_NEW,

    /** Loading the backup failed. Nothing was changed. */
    RESTORE_FAILED,
}

/** The outcome of one backup. */
sealed interface BackupResult {
    val at: Long

    data class Success(
        override val at: Long,
        val fileName: String,
        val eventCount: Long,
        val bytes: Long,
    ) : BackupResult

    data class Failure(override val at: Long, val error: BackupError, val message: String) : BackupResult
}

/**
 * What Settings → Storage shows: when the last backup worked, and whether the last attempt
 * failed and why. [lastError] is null when the last attempt worked (or none was made yet).
 */
data class BackupStatus(
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastEventCount: Long = 0,
    val lastBytes: Long = 0,
    val lastError: BackupError? = null,
    val lastErrorMessage: String? = null,
    val lastMergeAt: Long? = null,
    /** The last merge could not read the folder at all ([BackupError.FOLDER_ACCESS_LOST], ...). */
    val lastMergeError: BackupError? = null,
    /** Other phones' backup files the last merge could not merge (damaged, or the app failed to apply them). */
    val lastMergeProblemCount: Int = 0,
    /** The first of those, as "file name: reason", for the status line. */
    val lastMergeProblem: String? = null,
) {
    /** True when the last merge left something out: the status line says so. */
    val mergeTroubled: Boolean get() = lastMergeError != null || lastMergeProblemCount > 0

    /** True when a backup has worked and the latest attempt did not fail. */
    val ok: Boolean get() = lastSuccessAt != null && lastError == null
}

/**
 * What is remembered between runs. Kept outside the backup (it is about this install), with no
 * secrets in it. [merged] maps another phone's device id to the checksum of the event log last
 * merged from it, so an unchanged backup is not read again.
 */
data class BackupState(
    val status: BackupStatus = BackupStatus(),
    val merged: Map<String, String> = emptyMap(),
    val restoreOfferDismissed: Boolean = false,
)

/** Where [BackupState] is kept: SharedPreferences on Android, memory in tests. */
interface BackupStateStore {
    fun load(): BackupState

    fun save(state: BackupState)
}

class InMemoryBackupStateStore(private var state: BackupState = BackupState()) : BackupStateStore {
    override fun load(): BackupState = synchronized(this) { state }

    override fun save(state: BackupState) = synchronized(this) { this.state = state }
}

/**
 * One backup file in the folder, as the restore list shows it. Read from the manifest only;
 * [restore][FolderBackups.restore] checks the whole file before using it. [problem] is set when
 * the file cannot be restored (unreadable, or made by a newer version).
 */
data class BackupInfo(
    val entry: FolderEntry,
    val role: BackupRole,
    val deviceId: String?,
    val createdAt: Long?,
    val eventCount: Long?,
    val fermataVersion: String?,
    val appVersion: String?,
    val fromThisPhone: Boolean,
    val problem: String? = null,
) {
    val fileName: String get() = entry.name
    val restorable: Boolean get() = problem == null
}

/** The outcome of a restore. */
sealed interface RestoreResult {
    data class Success(
        /** Events that were new to this phone. */
        val added: Int,
        /** Events in the backup. */
        val total: Int,
        val fromDevice: String,
        val createdAt: Long,
        val fermataVersion: String,
    ) : RestoreResult

    /** Nothing was changed. */
    data class Failure(val error: BackupError, val message: String) : RestoreResult
}

/** A file that could not be merged this time (it is retried next time). */
data class MergeProblem(val fileName: String, val message: String)

/** The outcome of reading the other phones' backups. */
data class MergeResult(
    val at: Long,
    /** Other phones whose backups were found. */
    val phones: Int,
    /** Backup files read and merged. */
    val filesMerged: Int,
    /** Phones whose backup had not changed since the last merge, so it was not read again. */
    val unchanged: Int,
    /** Events new to this phone. */
    val eventsAdded: Int,
    val problems: List<MergeProblem> = emptyList(),
    /** Set when the folder itself could not be read; then nothing was merged. */
    val error: BackupError? = null,
)

/** Whether a fresh install should offer "Restore from a backup", and what it can offer. */
sealed interface RestoreOffer {
    /** This phone already has a library, or the user said no: don't offer. */
    data object NotNeeded : RestoreOffer

    /** Empty library and no folder picked yet: offer "Restore from a backup" with a folder picker. */
    data object PickFolder : RestoreOffer

    /** Empty library, folder picked, but no restorable backup in it. */
    data object NoBackups : RestoreOffer

    /** Empty library and these restorable backups, newest first. */
    data class Available(val backups: List<BackupInfo>) : RestoreOffer
}
