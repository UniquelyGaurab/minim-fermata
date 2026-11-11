package com.minim.fermata.sync

import com.minim.fermata.Fermata
import com.minim.fermata.backup.Backup
import com.minim.fermata.backup.BackupArchive
import com.minim.fermata.backup.BackupException
import com.minim.fermata.backup.BackupNames
import com.minim.fermata.backup.BackupRole
import com.minim.fermata.backup.BackupTooNewException
import com.minim.fermata.backup.Manifest
import java.io.FileNotFoundException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom
import java.time.Clock

/**
 * Backups, restore and multi-phone merge over a [BackupFolder]. Pure Kotlin: the android module
 * runs it on a Storage Access Framework folder, tests run it on a plain directory.
 *
 * **Backup** ([backUp]): the whole log goes to `minim-<device>-<nonce>.tmp`, which is then read
 * back and checked (format, checksums, event count, version vector, device id). Only a good file
 * is moved into place: the old `latest` becomes `previous` (the old `previous` is deleted) and the
 * temp file becomes `latest`. If any step fails the folder still has a good `latest` (the old one,
 * put back if it had been moved), and the temp file is removed. Temp files a killed run left behind
 * are cleaned up on the next run; only this phone's own files are ever written or deleted.
 *
 * **Restore** ([restore]): the chosen file is read and fully checked before anything is touched,
 * then loaded with [Fermata.restore] (all or nothing, projections rebuilt from the log).
 *
 * **Merge** ([mergeFromFolder]): for every other phone, its `latest` (or `previous`, when
 * `latest` is damaged or half-synced) is checked and merged. Merging is by event id, so repeating
 * it adds nothing; the legacy and 5.9.0 rules travel inside the events (`replaces`), so a play
 * counts once whichever phones imported which copy. A backup whose event log has the same
 * checksum as the one merged last time is not read again.
 *
 * All operations of all instances run one at a time, so the daily job and "Back up now" never
 * overlap in one app. Calls block: run them off the main thread.
 */
class FolderBackups(
    private val fermata: Fermata,
    private val state: BackupStateStore,
    private val appVersion: String? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val deviceId: String get() = fermata.deviceId

    /** Backs up the whole log into [folder] and records the result in [status]. */
    fun backUp(folder: BackupFolder?): BackupResult = synchronized(LOCK) {
        val now = clock.millis()
        val result = if (folder == null) {
            BackupResult.Failure(now, BackupError.NO_FOLDER, "No backup folder picked yet")
        } else {
            try {
                writeBackup(folder, now)
            } catch (e: SecurityException) {
                BackupResult.Failure(now, BackupError.FOLDER_ACCESS_LOST, "Lost access to the backup folder: pick it again")
            } catch (e: Exception) {
                BackupResult.Failure(now, BackupError.WRITE_FAILED, describe(e))
            }
        }
        recordResult(result)
        result
    }

    /** The last backup result, as Settings → Storage shows it. */
    fun status(): BackupStatus = state.load().status

    /**
     * Every backup in [folder], from any phone, newest first. Reads only the manifests; a file that
     * cannot be restored is still listed, with [BackupInfo.problem] saying why.
     */
    fun list(folder: BackupFolder): List<BackupInfo> = synchronized(LOCK) {
        folder.list().mapNotNull { entry ->
            val name = BackupNames.parse(entry.name) ?: return@mapNotNull null
            try {
                val m = folder.openInput(entry).use { BackupArchive.readManifest(it) }
                val tooNew = m.format > BackupArchive.FORMAT
                BackupInfo(
                    entry = entry,
                    role = name.role,
                    deviceId = m.deviceId,
                    createdAt = m.createdAt,
                    eventCount = m.eventCount,
                    fermataVersion = m.fermataVersion,
                    appVersion = m.appVersion,
                    fromThisPhone = m.deviceId == deviceId,
                    problem = if (tooNew) "Made by a newer version of Minim (backup format ${m.format})" else null,
                )
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                BackupInfo(
                    entry, name.role, name.deviceId, null, null, null, null,
                    fromThisPhone = name.deviceId == deviceId,
                    problem = "Can't be read: ${describe(e)}",
                )
            }
        }.sortedWith(compareByDescending<BackupInfo> { it.createdAt ?: Long.MIN_VALUE }.thenBy { it.fileName })
    }

    /**
     * Restores [backup]. The file is read and checked in full first; if it is damaged, gone, too
     * new, or cannot be applied, nothing changes. Restoring is a merge, so restoring into a library
     * that already has history is safe and counts nothing twice.
     */
    fun restore(folder: BackupFolder, backup: BackupInfo): RestoreResult = synchronized(LOCK) {
        val read = try {
            folder.openInput(backup.entry).use { BackupArchive.read(it) }
        } catch (e: FileNotFoundException) {
            return RestoreResult.Failure(BackupError.NOT_FOUND, "${backup.fileName} is no longer in the folder")
        } catch (e: SecurityException) {
            return RestoreResult.Failure(BackupError.FOLDER_ACCESS_LOST, "Lost access to the backup folder: pick it again")
        } catch (e: BackupTooNewException) {
            return RestoreResult.Failure(BackupError.TOO_NEW, e.message ?: "made by a newer version")
        } catch (e: BackupException) {
            return RestoreResult.Failure(BackupError.DAMAGED, e.message ?: "damaged backup")
        } catch (e: Exception) {
            return RestoreResult.Failure(BackupError.DAMAGED, describe(e))
        }
        try {
            val stats = fermata.restore(read.events)
            BackupArchive.eventsChecksum(read.manifest)?.let { sha ->
                if (read.manifest.deviceId != deviceId) remember(read.manifest.deviceId, sha)
            }
            RestoreResult.Success(
                stats.added, stats.total, read.manifest.deviceId, read.manifest.createdAt, read.manifest.fermataVersion,
            )
        } catch (e: Exception) {
            RestoreResult.Failure(BackupError.RESTORE_FAILED, describe(e))
        }
    }

    /**
     * Reads the other phones' newest backups from [folder] and merges the events this phone has
     * not seen. Damaged or half-synced files are skipped (falling back to that phone's `previous`)
     * and retried next time; a file that disappears while being read (its phone was just rotating
     * it) is looked up again once.
     */
    fun mergeFromFolder(folder: BackupFolder?): MergeResult = synchronized(LOCK) {
        val now = clock.millis()
        if (folder == null) return MergeResult(now, 0, 0, 0, 0, error = BackupError.NO_FOLDER)
        val entries = try {
            folder.list()
        } catch (e: SecurityException) {
            return MergeResult(now, 0, 0, 0, 0, error = BackupError.FOLDER_ACCESS_LOST)
        } catch (e: Exception) {
            return MergeResult(now, 0, 0, 0, 0, listOf(MergeProblem("(folder)", describe(e))), BackupError.FOLDER_UNREADABLE)
        }
        val byPhone = phonesIn(entries)
        var merged = 0
        var unchanged = 0
        var added = 0
        val problems = ArrayList<MergeProblem>()
        for ((phone, files) in byPhone) {
            for (role in listOf(BackupRole.LATEST, BackupRole.PREVIOUS)) {
                val outcome = try {
                    mergeOne(folder, phone, files[role] ?: continue)
                } catch (e: SecurityException) {
                    return MergeResult(now, byPhone.size, merged, unchanged, added, problems, BackupError.FOLDER_ACCESS_LOST)
                } catch (e: Exception) {
                    problems += MergeProblem(files.getValue(role).name, describe(e))
                    continue
                }
                when (outcome) {
                    null -> unchanged++
                    else -> {
                        merged++
                        added += outcome
                    }
                }
                break
            }
        }
        val s = state.load()
        state.save(s.copy(status = s.status.copy(lastMergeAt = now)))
        MergeResult(now, byPhone.size, merged, unchanged, added, problems)
    }

    /** The daily job: merge the other phones' backups in, then back up (so this backup carries them on). */
    fun mergeAndBackUp(folder: BackupFolder?): Pair<MergeResult, BackupResult> = synchronized(LOCK) {
        mergeFromFolder(folder) to backUp(folder)
    }

    /**
     * Whether to offer "Restore from a backup": only on an empty library (a fresh install), and not
     * after the user said no ([dismissRestoreOffer]).
     */
    fun restoreOffer(folder: BackupFolder?): RestoreOffer = synchronized(LOCK) {
        if (state.load().restoreOfferDismissed || fermata.eventCount() > 0) return RestoreOffer.NotNeeded
        if (folder == null) return RestoreOffer.PickFolder
        val backups = try {
            list(folder).filter { it.restorable }
        } catch (e: Exception) {
            return RestoreOffer.PickFolder
        }
        if (backups.isEmpty()) RestoreOffer.NoBackups else RestoreOffer.Available(backups)
    }

    fun dismissRestoreOffer() = synchronized(LOCK) {
        state.save(state.load().copy(restoreOfferDismissed = true))
    }

    // --- Backup steps ---

    private fun writeBackup(folder: BackupFolder, now: Long): BackupResult {
        val latestName = BackupNames.latest(deviceId)
        val previousName = BackupNames.previous(deviceId)
        val entries = folder.list()

        // Temp files of a killed run. Only this phone's: another phone may be writing its own now.
        for (stale in entries.filter { BackupNames.isOwnTemp(it.name, deviceId) }) {
            runCatching { folder.delete(stale) }
        }

        // 1. Write the temp file.
        val temp = folder.create(BackupNames.temp(deviceId, "$now-${nonce()}"))
        val (manifest: Manifest, bytes: Long) = try {
            folder.openOutput(temp).use { out ->
                val c = CountingOutputStream(out)
                val m = fermata.export(c, appVersion)
                c.flush()
                m to c.count
            }
        } catch (e: Exception) {
            runCatching { folder.delete(temp) }
            throw e
        }

        // 2. Read it back and check it.
        val problem = verify(folder, temp, manifest)
        if (problem != null) {
            runCatching { folder.delete(temp) }
            return BackupResult.Failure(now, BackupError.VERIFY_FAILED, "The new backup didn't check out ($problem); the old one is kept")
        }

        // 3. latest -> previous (the old previous goes), then temp -> latest.
        val oldLatest = entries.firstOrNull { it.name == latestName }
        val oldPrevious = entries.firstOrNull { it.name == previousName }
        var movedLatest: FolderEntry? = null
        var newFile = temp
        try {
            if (oldLatest != null) {
                if (oldPrevious != null) folder.delete(oldPrevious)
                movedLatest = folder.rename(oldLatest, previousName)
            }
            newFile = folder.rename(temp, latestName)
            check(newFile.name == latestName) { "the folder named the backup ${newFile.name}" }
            return BackupResult.Success(now, newFile.name, manifest.eventCount, bytes)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            // Put the old latest back, so the folder is never left without one.
            if (newFile.name != latestName) {
                runCatching { folder.delete(newFile) }
                movedLatest?.let { runCatching { folder.rename(it, latestName) } }
            }
            return BackupResult.Failure(now, BackupError.RENAME_FAILED, "Couldn't move the new backup into place (${describe(e)}); the old one is kept")
        }
    }

    /** Reads [entry] back in full and compares it with what was written. Null when it is good. */
    private fun verify(folder: BackupFolder, entry: FolderEntry, written: Manifest): String? {
        val back: Backup = try {
            folder.openInput(entry).use { BackupArchive.read(it) }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            return describe(e)
        }
        val m = back.manifest
        return when {
            m.deviceId != written.deviceId -> "device id ${m.deviceId}, expected ${written.deviceId}"
            m.eventCount != written.eventCount -> "${m.eventCount} events, expected ${written.eventCount}"
            back.events.size.toLong() != written.eventCount -> "${back.events.size} events, expected ${written.eventCount}"
            m.heads != written.heads -> "version vector differs"
            m.files != written.files -> "checksums differ"
            else -> null
        }
    }

    // --- Merge steps ---

    /** Other phones' latest/previous files, by device id. */
    private fun phonesIn(entries: List<FolderEntry>): Map<String, Map<BackupRole, FolderEntry>> {
        val phones = sortedMapOf<String, MutableMap<BackupRole, FolderEntry>>()
        for (e in entries) {
            val name = BackupNames.parse(e.name) ?: continue
            val phone = name.deviceId ?: continue // older daily files: restore only
            if (phone == deviceId) continue
            phones.getOrPut(phone) { mutableMapOf() }[name.role] = e
        }
        return phones
    }

    /** Merges one file. Returns the number of new events, or null when it was unchanged since last time. */
    private fun mergeOne(folder: BackupFolder, phone: String, entry: FolderEntry): Int? {
        val name = entry.name
        var current = entry
        val backup = try {
            readChecked(folder, current, phone)
        } catch (e: FileNotFoundException) {
            // The phone may have been rotating its files at that moment: look again once.
            current = folder.list().firstOrNull { it.name == name } ?: throw e
            readChecked(folder, current, phone)
        } ?: return null
        val added = fermata.merge(backup.events)
        BackupArchive.eventsChecksum(backup.manifest)?.let { remember(phone, it) }
        return added
    }

    /** The checked backup in [entry], or null when its event log is the one merged last time. */
    private fun readChecked(folder: BackupFolder, entry: FolderEntry, phone: String): Backup? {
        val seen = state.load().merged[phone]
        if (seen != null) {
            val m = folder.openInput(entry).use { BackupArchive.readManifest(it) }
            if (BackupArchive.eventsChecksum(m) == seen) return null
        }
        return folder.openInput(entry).use { BackupArchive.read(it) }
    }

    private fun remember(phone: String, sha: String) {
        val s = state.load()
        state.save(s.copy(merged = s.merged + (phone to sha)))
    }

    private fun recordResult(result: BackupResult) {
        val s = state.load()
        val status = when (result) {
            is BackupResult.Success -> s.status.copy(
                lastAttemptAt = result.at,
                lastSuccessAt = result.at,
                lastEventCount = result.eventCount,
                lastBytes = result.bytes,
                lastError = null,
                lastErrorMessage = null,
            )
            is BackupResult.Failure -> s.status.copy(
                lastAttemptAt = result.at,
                lastError = result.error,
                lastErrorMessage = result.message,
            )
        }
        state.save(s.copy(status = status))
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    companion object {
        /** One backup, restore or merge at a time, across every instance in the process. */
        private val LOCK = Any()
        private val random = SecureRandom()

        private fun nonce(): String = "%08x".format(random.nextInt())

        private fun describe(e: Throwable): String = when (e) {
            is FileNotFoundException -> "file not found: ${e.message}"
            is IOException, is BackupException -> e.message ?: e.javaClass.simpleName
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
