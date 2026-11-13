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
 * 6.1.1's backup writer (format 1, `latest` and `previous` `.minim` files), kept as it shipped so
 * tests can make the folder of a phone that has not updated yet, and of this phone before 6.2.
 *
 * **Backup** ([backUp]): the whole log goes to `minim-<device>-<nonce>.tmp`, which is then read
 * back and checked (format, checksums, event count, version vector, device id). Only a good file
 * is moved into place as `latest`. `latest` is replaced on every backup, but `previous` moves on
 * at most once a day ([PREVIOUS_EVERY_MS]): then the old `latest` becomes `previous` (the old
 * `previous` is deleted); otherwise the old `latest` is parked under a temp name while the new
 * file moves in, and deleted after. So backing up every time the app closes never turns
 * `previous` into a copy from five minutes ago. If any step fails the folder still has a good
 * `latest` (the old one, put back if it had been moved), and the temp file is removed. Temp files
 * a killed run left behind are cleaned up on the next run (a parked `latest` is put back first if
 * the run died before the new one moved in); only this phone's own files are ever written or
 * deleted. [backUpIfChanged] skips the whole write when `latest` already holds the log.
 *
 * **Restore** ([restore]): the chosen file is read and fully checked before anything is touched,
 * then loaded with [Fermata.restore] (all or nothing, projections rebuilt from the log).
 *
 * **Merge** ([mergeFromFolder]): for every other phone, its `latest` (or `previous`, when
 * `latest` is damaged or half-synced) is checked and merged. Merging is by event id, so repeating
 * it adds nothing; the legacy and 5.9.0 rules travel inside the events (`replaces`), so a play
 * counts once whichever phones imported which copy. A backup whose event log has the same
 * checksum as the one merged last time is not read again, and neither is one whose manifest shows
 * nothing new: its version vector is covered by this phone's, and its legacy events (which have
 * no counters) are the set merged from that phone last time. Only a backup with something new,
 * an older manifest without the legacy fields, or a manifest that can't be read is read in full,
 * so a merge when the app opens stays cheap.
 *
 * All operations of all instances run one at a time, so the daily job and "Back up now" never
 * overlap in one app. Calls block: run them off the main thread.
 */
class Format1Backups(
    private val fermata: Fermata,
    private val state: BackupStateStore,
    private val appVersion: String? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val deviceId: String get() = fermata.deviceId

    /**
     * Backs up the whole log into [folder] and records the result in [status].
     *
     * With [replacePrevious] (used right after "clear listening history") the old latest is not
     * kept as `previous`: `previous` becomes a second copy of the new backup, so none of this
     * phone's files still holds the cleared history. If the copy fails, the old file is still
     * deleted and only `latest` is left.
     */
    fun backUp(folder: BackupFolder?, replacePrevious: Boolean = false): BackupResult = synchronized(LOCK) {
        val now = clock.millis()
        val result = if (folder == null) {
            BackupResult.Failure(now, BackupError.NO_FOLDER, "No backup folder picked yet")
        } else {
            try {
                writeBackup(folder, now, replacePrevious)
            } catch (e: SecurityException) {
                BackupResult.Failure(now, BackupError.FOLDER_ACCESS_LOST, "Lost access to the backup folder: pick it again")
            } catch (e: Exception) {
                BackupResult.Failure(now, BackupError.WRITE_FAILED, describe(e))
            }
        }
        recordResult(result)
        result
    }

    /**
     * Backs up only when the log holds something this phone's `latest` in [folder] does not.
     * Reads just that file's manifest and compares its version vector and event count with the
     * log's: when they match, nothing is written and the result is a [BackupResult.Success] with
     * `written = false` (recorded in [status] as a backup that worked, since `latest` is
     * current). A missing, unreadable, older-format or other-phone `latest` means a full backup.
     * For backups the app starts itself (when it goes to the background, the daily job).
     */
    fun backUpIfChanged(folder: BackupFolder?): BackupResult = synchronized(LOCK) {
        if (folder != null) {
            val now = clock.millis()
            val current = try {
                upToDate(folder, now)
            } catch (e: SecurityException) {
                val lost = BackupResult.Failure(now, BackupError.FOLDER_ACCESS_LOST, "Lost access to the backup folder: pick it again")
                recordResult(lost)
                return lost
            }
            if (current != null) {
                recordResult(current)
                return current
            }
        }
        backUp(folder)
    }

    /** A not-written result when this phone's `latest` in [folder] already holds the whole log, else null. */
    private fun upToDate(folder: BackupFolder, now: Long): BackupResult.Success? {
        val latestName = BackupNames.latest(deviceId)
        val latest = try {
            folder.list().firstOrNull { it.name == latestName }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val m = try {
            folder.openInput(latest).use { BackupArchive.readManifest(it) }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (m.format != BackupArchive.FORMAT || m.deviceId != deviceId) return null
        // Both read under Fermata's lock, so no event can land between them.
        val (heads, count) = fermata.transaction { fermata.loggedHeads() to fermata.eventCount() }
        if (m.heads != heads || m.eventCount != count) return null
        val bytes = if (latest.size >= 0) latest.size else state.load().status.lastBytes
        return BackupResult.Success(now, latest.name, count, bytes, written = false)
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
            if (read.manifest.deviceId != deviceId) {
                remember(read.manifest.deviceId, BackupArchive.eventsChecksum(read.manifest), BackupArchive.legacyDigest(read.events))
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
        val result = mergeAll(folder)
        // Saved for the status line, so a merge that left something out is never silent.
        val s = state.load()
        state.save(
            s.copy(
                status = s.status.copy(
                    lastMergeAt = if (result.error == null) result.at else s.status.lastMergeAt,
                    lastMergeError = result.error?.takeIf { it != BackupError.NO_FOLDER },
                    lastMergeProblemCount = result.problems.size,
                    lastMergeProblem = result.problems.firstOrNull()?.let { "${it.fileName}: ${it.message}" },
                )
            )
        )
        result
    }

    private fun mergeAll(folder: BackupFolder?): MergeResult {
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
        return MergeResult(now, byPhone.size, merged, unchanged, added, problems)
    }

    /**
     * The daily job: merge the other phones' backups in, then back up (so this backup carries them
     * on). With [onlyIfChanged] the backup is [backUpIfChanged]; [replacePrevious] always writes.
     */
    fun mergeAndBackUp(
        folder: BackupFolder?,
        replacePrevious: Boolean = false,
        onlyIfChanged: Boolean = false,
    ): Pair<MergeResult, BackupResult> = synchronized(LOCK) {
        val merge = mergeFromFolder(folder)
        merge to if (onlyIfChanged && !replacePrevious) backUpIfChanged(folder) else backUp(folder, replacePrevious)
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

    private fun writeBackup(folder: BackupFolder, now: Long, replacePrevious: Boolean = false): BackupResult {
        val latestName = BackupNames.latest(deviceId)
        val previousName = BackupNames.previous(deviceId)
        var entries = folder.list()

        // Temp files of a killed run. Only this phone's: another phone may be writing its own now.
        // A run killed after parking the old latest but before the new one moved in left no latest:
        // the parked file goes back first.
        val stale = entries.filter { BackupNames.isOwnTemp(it.name, deviceId) }
        val parkedOld = if (entries.none { it.name == latestName }) {
            stale.filter { it.name.endsWith(PARKED_SUFFIX) }.maxByOrNull { it.name }
        } else {
            null
        }
        val putBack = parkedOld?.takeIf { runCatching { folder.rename(it, latestName) }.isSuccess }
        for (old in stale) if (old != putBack) runCatching { folder.delete(old) }
        if (putBack != null) entries = folder.list()

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

        // 3. When previous is due (at most once a day): latest -> previous (the old previous goes).
        //    Otherwise the old latest is parked under a temp name and deleted once the new one is in.
        //    Then temp -> latest.
        val oldLatest = entries.firstOrNull { it.name == latestName }
        val oldPrevious = entries.firstOrNull { it.name == previousName }
        val rotate = oldLatest != null && (replacePrevious || oldPrevious == null || previousDue(now))
        var movedLatest: FolderEntry? = null
        var parked: FolderEntry? = null
        var newFile = temp
        try {
            if (oldLatest != null) {
                if (rotate) {
                    if (oldPrevious != null) folder.delete(oldPrevious)
                    movedLatest = folder.rename(oldLatest, previousName)
                } else {
                    parked = folder.rename(oldLatest, BackupNames.temp(deviceId, "$now-${nonce()}$PARKED"))
                }
            }
            newFile = folder.rename(temp, latestName)
            check(newFile.name == latestName) { "the folder named the backup ${newFile.name}" }
            if (rotate) state.save(state.load().copy(previousRotatedAt = now))
            parked?.let { runCatching { folder.delete(it) } } // if this fails, the next run deletes it
            if (replacePrevious) replacePreviousWith(folder, newFile, manifest, now, movedLatest ?: oldPrevious)
            return BackupResult.Success(now, newFile.name, manifest.eventCount, bytes)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            // Put the old latest back, so the folder is never left without one.
            if (newFile.name != latestName) {
                runCatching { folder.delete(newFile) }
                movedLatest?.let { runCatching { folder.rename(it, latestName) } }
                parked?.let { runCatching { folder.rename(it, latestName) } }
            }
            return BackupResult.Failure(now, BackupError.RENAME_FAILED, "Couldn't move the new backup into place (${describe(e)}); the old one is kept")
        }
    }

    /**
     * Whether `previous` may move on now: never moved on (or not recorded), a day or more since
     * it last did, or the clock is now well before the last time (it was set back, or was ahead
     * then). In that last case it moves on once and the day counts from the new time, so a wrong
     * clock costs at most one extra move, never a `previous` that moves on every backup or never.
     */
    private fun previousDue(now: Long): Boolean {
        val last = state.load().previousRotatedAt ?: return true
        return now - last >= PREVIOUS_EVERY_MS || now < last - CLOCK_SLACK_MS
    }

    /**
     * Deletes [old] (the file that would have stayed as `previous`) and writes a checked copy of
     * [latest] as `previous`. Best effort: a failed copy leaves just `latest`, never the old file.
     */
    private fun replacePreviousWith(folder: BackupFolder, latest: FolderEntry, manifest: Manifest, now: Long, old: FolderEntry?) {
        val previousName = BackupNames.previous(deviceId)
        if (old != null) folder.delete(old)
        folder.list().filter { it.name == previousName }.forEach { runCatching { folder.delete(it) } }
        val temp = folder.create(BackupNames.temp(deviceId, "$now-${nonce()}"))
        try {
            folder.openInput(latest).use { input -> folder.openOutput(temp).use { out -> input.copyTo(out) } }
            if (verify(folder, temp, manifest) != null) {
                runCatching { folder.delete(temp) }
                return
            }
            folder.rename(temp, previousName)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            runCatching { folder.delete(temp) }
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
        remember(phone, BackupArchive.eventsChecksum(backup.manifest), BackupArchive.legacyDigest(backup.events))
        return added
    }

    /**
     * The checked backup in [entry], or null when its manifest alone shows nothing new: the same
     * event log as merged last time, or (see [nothingNew]) nothing this phone lacks. A manifest
     * that can't be read falls through to the full read, which checks the file and reports it.
     */
    private fun readChecked(folder: BackupFolder, entry: FolderEntry, phone: String): Backup? {
        val m = try {
            folder.openInput(entry).use { BackupArchive.readManifest(it) }
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (m != null) {
            val s = state.load()
            val sha = BackupArchive.eventsChecksum(m)
            if (sha != null && sha == s.merged[phone]) return null
            if (nothingNew(m, phone, s)) {
                remember(phone, sha, m.legacyDigest)
                return null
            }
        }
        return folder.openInput(entry).use { BackupArchive.read(it) }
    }

    /**
     * True when merging the backup with manifest [m] could add nothing: every device counter in
     * its version vector is one this phone's log already reaches (each phone's events reach a
     * log as a whole prefix, so the counter alone says which are there), and its legacy events
     * are none, or the same set as merged from [phone] last time. Older manifests without the
     * legacy fields, or from a newer format, say nothing here: they are read in full.
     */
    private fun nothingNew(m: Manifest, phone: String, s: BackupState): Boolean {
        if (m.format > BackupArchive.FORMAT || m.deviceId != phone) return false
        val legacyKnown = when (val n = m.legacyCount) {
            null -> false
            0L -> true
            else -> n > 0 && m.legacyDigest != null && m.legacyDigest == s.mergedLegacy[phone]
        }
        if (!legacyKnown) return false
        val local = fermata.loggedHeads()
        return m.heads.all { (device, counter) -> counter <= (local[device] ?: 0L) }
    }

    private fun remember(phone: String, sha: String?, legacyDigest: String?) {
        val s = state.load()
        state.save(
            s.copy(
                merged = if (sha != null) s.merged + (phone to sha) else s.merged,
                mergedLegacy = if (legacyDigest != null) s.mergedLegacy + (phone to legacyDigest) else s.mergedLegacy,
            )
        )
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
        /** `previous` moves on at most this often (a day); `latest` is replaced on every backup. */
        const val PREVIOUS_EVERY_MS = 24L * 60 * 60 * 1000

        /** How far the clock may go back before `previous` moves on anyway (see [previousDue]). */
        private const val CLOCK_SLACK_MS = 60L * 60 * 1000

        /** Marks the temp name of a parked old `latest` (`minim-<device>-<time>-<nonce>-old.tmp`). */
        private const val PARKED = "-old"
        private const val PARKED_SUFFIX = PARKED + BackupNames.TEMP_EXTENSION

        /**
         * One backup, restore or merge at a time, across every instance in the process: the
         * on-close backup, the daily job and "Back up now" wait for each other, whichever thread
         * or WorkManager job runs them.
         */
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
