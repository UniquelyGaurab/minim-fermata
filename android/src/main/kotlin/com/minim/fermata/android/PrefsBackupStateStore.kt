package com.minim.fermata.android

import android.content.SharedPreferences
import com.minim.fermata.sync.BackupError
import com.minim.fermata.sync.BackupState
import com.minim.fermata.sync.BackupStateStore
import com.minim.fermata.sync.BackupStatus

/**
 * Keeps [BackupState] in SharedPreferences (`fermata_backup`). It describes this install, so the
 * app's Auto Backup / device-transfer rules must exclude that file, like the device id.
 */
class PrefsBackupStateStore(private val prefs: SharedPreferences) : BackupStateStore {
    override fun load(): BackupState = synchronized(this) {
        val status = BackupStatus(
            lastAttemptAt = long(K_ATTEMPT),
            lastSuccessAt = long(K_SUCCESS),
            lastEventCount = prefs.getLong(K_EVENTS, 0),
            lastBytes = prefs.getLong(K_BYTES, 0),
            lastError = prefs.getString(K_ERROR, null)?.let { runCatching { BackupError.valueOf(it) }.getOrNull() },
            lastErrorMessage = prefs.getString(K_ERROR_MESSAGE, null),
            lastMergeAt = long(K_MERGE),
            lastMergeError = prefs.getString(K_MERGE_ERROR, null)?.let { runCatching { BackupError.valueOf(it) }.getOrNull() },
            lastMergeProblemCount = prefs.getInt(K_MERGE_PROBLEMS, 0),
            lastMergeProblem = prefs.getString(K_MERGE_PROBLEM, null),
        )
        val merged = prefs.all.entries
            .filter { it.key.startsWith(K_MERGED_PREFIX) && it.value is String }
            .associate { it.key.removePrefix(K_MERGED_PREFIX) to it.value as String }
        val mergedLegacy = prefs.all.entries
            .filter { it.key.startsWith(K_MERGED_LEGACY_PREFIX) && it.value is String }
            .associate { it.key.removePrefix(K_MERGED_LEGACY_PREFIX) to it.value as String }
        BackupState(status, merged, prefs.getBoolean(K_DISMISSED, false), long(K_ROTATED), mergedLegacy, prefs.getLong(K_LAST_GEN, 0L))
    }

    override fun save(state: BackupState) = synchronized(this) {
        val s = state.status
        val e = prefs.edit()
        putLong(e, K_ATTEMPT, s.lastAttemptAt)
        putLong(e, K_SUCCESS, s.lastSuccessAt)
        e.putLong(K_EVENTS, s.lastEventCount)
        e.putLong(K_BYTES, s.lastBytes)
        val error = s.lastError
        if (error == null) e.remove(K_ERROR) else e.putString(K_ERROR, error.name)
        if (s.lastErrorMessage == null) e.remove(K_ERROR_MESSAGE) else e.putString(K_ERROR_MESSAGE, s.lastErrorMessage)
        putLong(e, K_MERGE, s.lastMergeAt)
        val mergeError = s.lastMergeError
        if (mergeError == null) e.remove(K_MERGE_ERROR) else e.putString(K_MERGE_ERROR, mergeError.name)
        e.putInt(K_MERGE_PROBLEMS, s.lastMergeProblemCount)
        if (s.lastMergeProblem == null) e.remove(K_MERGE_PROBLEM) else e.putString(K_MERGE_PROBLEM, s.lastMergeProblem)
        for (key in prefs.all.keys) if (key.startsWith(K_MERGED_PREFIX)) e.remove(key)
        for ((device, sha) in state.merged) e.putString(K_MERGED_PREFIX + device, sha)
        for (key in prefs.all.keys) if (key.startsWith(K_MERGED_LEGACY_PREFIX)) e.remove(key)
        for ((device, digest) in state.mergedLegacy) e.putString(K_MERGED_LEGACY_PREFIX + device, digest)
        e.putBoolean(K_DISMISSED, state.restoreOfferDismissed)
        putLong(e, K_ROTATED, state.previousRotatedAt)
        e.putLong(K_LAST_GEN, state.lastGen)
        e.commit()
        Unit
    }

    private fun long(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0) else null

    private fun putLong(e: SharedPreferences.Editor, key: String, value: Long?) {
        if (value == null) e.remove(key) else e.putLong(key, value)
    }

    private companion object {
        const val K_ATTEMPT = "last_attempt_at"
        const val K_SUCCESS = "last_success_at"
        const val K_EVENTS = "last_event_count"
        const val K_BYTES = "last_bytes"
        const val K_ERROR = "last_error"
        const val K_ERROR_MESSAGE = "last_error_message"
        const val K_MERGE = "last_merge_at"
        const val K_MERGE_ERROR = "last_merge_error"
        const val K_MERGE_PROBLEMS = "last_merge_problem_count"
        const val K_MERGE_PROBLEM = "last_merge_problem"
        const val K_DISMISSED = "restore_offer_dismissed"
        const val K_MERGED_PREFIX = "merged."
        // Not under "merged.": that prefix holds only the event log checksums.
        const val K_MERGED_LEGACY_PREFIX = "merged_legacy."
        const val K_ROTATED = "previous_rotated_at"
        const val K_LAST_GEN = "last_gen"
    }
}
