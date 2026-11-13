package com.minim.fermata.sync

import com.minim.fermata.Fermata
import com.minim.fermata.backup.Backup
import com.minim.fermata.backup.BackupArchive
import com.minim.fermata.backup.BackupException
import com.minim.fermata.backup.BackupNames
import com.minim.fermata.backup.BackupRole
import com.minim.fermata.backup.BackupTooNewException
import com.minim.fermata.backup.ChainPoint
import com.minim.fermata.backup.LogFile
import com.minim.fermata.backup.LogFileData
import com.minim.fermata.backup.LogFileKind
import com.minim.fermata.backup.LogHeader
import com.minim.fermata.backup.Manifest
import com.minim.fermata.log.Event
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SecureRandom
import java.time.Clock

/**
 * Backups, restore and multi-phone merge over a [BackupFolder]. Pure Kotlin: the android module
 * runs it on a Storage Access Framework folder, tests run it on a plain directory.
 *
 * **Backup** ([backUp]), format 2 (Minim 6.2, see DESIGN.md "Backup files, format 2"). Each phone
 * keeps one *chain* of files it writes once and never changes:
 * - `minim-<device>-g<gen>-base.fermata`: the whole log when generation `gen` started;
 * - `minim-<device>-g<gen>-p<seq>.fermata`: the events of real devices logged after piece
 *   `seq - 1` (or the base). A backup after one new play writes one small piece.
 *
 * Every file starts with a readable JSON header holding the chain's version vector after it, so
 * "is this new?" never needs more than a header. A file is written as `minim-<device>-<nonce>.tmp`,
 * read back and checked in full, and only then renamed to its final name: the rename is the commit.
 * Now and then ([combineDue]), after a clear history, or when the log no longer continues the chain
 * (merged-in imported plays, say), the next backup writes a new base, generation `gen + 1`; the old
 * generation's files are deleted only after the new base is in place, so a kill at any step leaves
 * a whole chain behind. Temp files a killed run left are deleted on the next run.
 *
 * The restore point ("previous") is a point in the chain, kept in every header. It moves on at
 * most once a day ([PREVIOUS_EVERY_MS]) to the state before that backup, so backing up every time
 * the app closes never turns it into a copy from five minutes ago; the files it needs are kept.
 * After a clear history it is set to the new base, so no file keeps the cleared history.
 * [backUpIfChanged] skips the whole write when the chain already holds the log. Format-1 files
 * (`-latest.minim`, `-previous.minim`) this phone wrote before 6.2 are kept until the chain has a
 * restore point of its own, then deleted; nothing writes format 1 any more.
 *
 * **Restore** ([restore]): every file of the chosen chain up to the chosen point is read and fully
 * checked (checksums, counts, and that each piece starts where the one before ended) before
 * anything is touched, then loaded with [Fermata.restore] (all or nothing). Format-1 files restore
 * as before.
 *
 * **Merge** ([mergeFromFolder]): for every other phone, the newest generation whose base reads.
 * What was merged is remembered per phone as a chain point, so a merge on open reads only the new
 * pieces, and skips the body of any file whose header's version vector this phone already has. A
 * half-synced piece stops that phone's merge there; the next merge carries on. Phones still on
 * 6.1.1 or older are merged from their format-1 files as before. Merging is by event id, so
 * repeating it adds nothing.
 *
 * Only this phone's own files are ever written or deleted, so Syncthing and similar tools never
 * see two phones write one file. All of this runs one at a time ([LOCK]).
 */
class FolderBackups(
    private val fermata: Fermata,
    private val state: BackupStateStore,
    private val appVersion: String? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val deviceId: String get() = fermata.deviceId

    /**
     * Backs up what is new. [replacePrevious] is for after "clear history": the next file is a new
     * base, which becomes the restore point too, and every older file of this phone is deleted, so
     * no backup keeps the cleared history.
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
     * Like [backUp], but writes nothing when this phone's chain already holds the log (same
     * version vector, event count and clear point). For the backup when the app closes.
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

    private fun upToDate(folder: BackupFolder, now: Long): BackupResult.Success? {
        val entries = try {
            folder.list()
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val own = ownTip(folder, entries) ?: return null
        val snap = fermata.transaction { Snapshot(fermata.loggedHeads(), fermata.eventCount(), fermata.clearedBefore()) }
        val h = own.header
        if (h.heads != snap.heads || h.chainCount != snap.count || h.clearedBefore != snap.clearedBefore) return null
        return BackupResult.Success(now, own.entry.name, h.chainCount, size(own.entry), written = false, totalBytes = own.totalBytes)
    }

    fun status(): BackupStatus = state.load().status

    /**
     * The backups in [folder], newest first: for each phone on 6.2 or newer its latest point and
     * its restore point; for older phones their format-1 files. Reads headers only.
     */
    fun list(folder: BackupFolder): List<BackupInfo> = synchronized(LOCK) {
        val entries = folder.list()
        val out = ArrayList<BackupInfo>()
        val chained = HashSet<String>()
        for ((device, gens) in chainsIn(entries)) {
            val infos = chainInfos(folder, device, gens)
            // Until the chain has a restore point of its own, the pre-6.2 files stay listed.
            if (infos.any { it.problem == null && it.role == BackupRole.PREVIOUS }) chained += device
            out += infos
        }
        for (entry in entries) {
            val name = BackupNames.parse(entry.name) ?: continue
            if (name.deviceId != null && name.deviceId in chained) continue // the chain supersedes them
            out += format1Info(folder, entry, name.role, name.deviceId)
        }
        out.sortedWith(compareByDescending<BackupInfo> { it.createdAt ?: Long.MIN_VALUE }.thenBy { it.fileName })
    }

    private fun chainInfos(folder: BackupFolder, device: String, gens: Map<Long, Map<Long, FolderEntry>>): List<BackupInfo> {
        val out = ArrayList<BackupInfo>()
        for (gen in gens.keys.sortedDescending()) {
            val files = contiguous(gens.getValue(gen))
            for (i in files.indices.reversed()) {
                val entry = files[i]
                val h = try {
                    readHeader(folder, entry, device, gen, i.toLong())
                } catch (e: SecurityException) {
                    throw e
                } catch (e: BackupTooNewException) {
                    out += problemInfo(entry, BackupRole.LATEST, device, "Made by a newer version of Minim (${e.message})")
                    return out
                } catch (e: Exception) {
                    out += problemInfo(entry, BackupRole.LATEST, device, "Can't be read: ${describe(e)}")
                    continue
                }
                out += chainInfo(entry, BackupRole.LATEST, h)
                val rp = h.restorePoint
                if (rp != null && !(rp.gen == gen && rp.seq == h.seq)) {
                    val rpEntry = gens[rp.gen]?.let { contiguous(it) }?.getOrNull(rp.seq.toInt())
                    if (rpEntry != null) {
                        out += try {
                            chainInfo(rpEntry, BackupRole.PREVIOUS, readHeader(folder, rpEntry, device, rp.gen, rp.seq))
                        } catch (e: SecurityException) {
                            throw e
                        } catch (e: Exception) {
                            problemInfo(rpEntry, BackupRole.PREVIOUS, device, "Can't be read: ${describe(e)}")
                        }
                    }
                }
                return out
            }
        }
        return out
    }

    private fun chainInfo(entry: FolderEntry, role: BackupRole, h: LogHeader) = BackupInfo(
        entry = entry,
        role = role,
        deviceId = h.deviceId,
        createdAt = h.createdAt,
        eventCount = h.chainCount,
        fermataVersion = h.fermataVersion,
        appVersion = h.appVersion,
        fromThisPhone = h.deviceId == deviceId,
    )

    private fun problemInfo(entry: FolderEntry, role: BackupRole, device: String?, problem: String) =
        BackupInfo(entry, role, device, null, null, null, null, fromThisPhone = device == deviceId, problem = problem)

    private fun format1Info(folder: BackupFolder, entry: FolderEntry, role: BackupRole, device: String?): BackupInfo = try {
        val m = folder.openInput(entry).use { BackupArchive.readManifest(it) }
        val tooNew = m.format > BackupArchive.FORMAT
        BackupInfo(
            entry = entry,
            role = role,
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
        problemInfo(entry, role, device, "Can't be read: ${describe(e)}")
    }

    /**
     * Restores [backup]: for a format-2 file, the chain up to and including it; for a format-1
     * file, that file. Everything is read and checked before the log is touched.
     */
    fun restore(folder: BackupFolder, backup: BackupInfo): RestoreResult = synchronized(LOCK) {
        val chain = BackupNames.parseChain(backup.fileName)
        val header: LogHeader?
        val read: Backup
        try {
            if (chain != null) {
                val (h, events) = readChain(folder, chain.deviceId, chain.gen, chain.seq)
                header = h
                read = Backup(manifestOf(h), events)
            } else {
                header = null
                read = folder.openInput(backup.entry).use { BackupArchive.read(it) }
            }
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
                if (header != null) rememberChain(header.deviceId, header.gen, header.seq, header.legacyDigest)
                else remember(read.manifest.deviceId, BackupArchive.eventsChecksum(read.manifest), BackupArchive.legacyDigest(read.events))
            }
            RestoreResult.Success(stats.added, stats.total, read.manifest.deviceId, read.manifest.createdAt, read.manifest.fermataVersion)
        } catch (e: Exception) {
            RestoreResult.Failure(BackupError.RESTORE_FAILED, describe(e))
        }
    }

    private fun manifestOf(h: LogHeader) = Manifest(
        format = h.format, createdAt = h.createdAt, deviceId = h.deviceId, fermataVersion = h.fermataVersion,
        appVersion = h.appVersion, eventCount = h.chainCount, heads = h.heads, files = emptyList(),
        legacyCount = h.legacyCount, legacyDigest = h.legacyDigest,
    )

    /**
     * Reads and checks the base and pieces 1..[seq] of [device]'s generation [gen]. Throws
     * [FileNotFoundException] when a file is missing, [BackupException] when one is damaged or
     * the pieces don't follow on from each other.
     */
    private fun readChain(folder: BackupFolder, device: String, gen: Long, seq: Long): Pair<LogHeader, List<Event>> {
        val files = chainsIn(folder.list())[device]?.get(gen) ?: throw FileNotFoundException(BackupNames.chainBase(device, gen))
        val events = ArrayList<Event>()
        var prev: LogHeader? = null
        for (s in 0..seq) {
            val entry = files[s] ?: throw FileNotFoundException(BackupNames.chainFile(device, gen, s))
            val h = readFile(folder, entry, device, gen, s).let { d ->
                events += d.events
                d.header
            }
            if (prev != null) {
                if (h.from != prev.heads) throw BackupException("${entry.name} doesn't follow on from the piece before it")
                if (h.chainCount != prev.chainCount + h.events) throw BackupException("${entry.name}: the event count doesn't add up")
                if (h.clearedBefore != prev.clearedBefore || h.legacyDigest != prev.legacyDigest) {
                    throw BackupException("${entry.name} belongs to another chain")
                }
            }
            prev = h
        }
        return prev!! to events
    }

    /**
     * Merges the backups of every other phone in [folder] into this phone's log; see the class
     * comment. Reads nothing but headers when nothing is new.
     */
    fun mergeFromFolder(folder: BackupFolder?): MergeResult = synchronized(LOCK) {
        val result = mergeAll(folder)
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
        val chains = chainsIn(entries).filterKeys { it != deviceId }
        val format1 = phonesIn(entries)
        val phones = (chains.keys + format1.keys).toSortedSet()
        var merged = 0
        var unchanged = 0
        var added = 0
        val problems = ArrayList<MergeProblem>()
        for (phone in phones) {
            try {
                val gens = chains[phone]
                val chainOutcome = if (gens != null) mergeChain(folder, phone, gens, problems) else null
                if (chainOutcome != null) {
                    if (chainOutcome == NOTHING_NEW) unchanged++ else {
                        merged++
                        added += chainOutcome
                    }
                    continue
                }
                // A phone on 6.1.1 or older, or one whose chain can't be read at all yet.
                val files = format1[phone] ?: continue
                for (role in listOf(BackupRole.LATEST, BackupRole.PREVIOUS)) {
                    val entry = files[role] ?: continue
                    val outcome = try {
                        mergeOne(folder, phone, entry)
                    } catch (e: SecurityException) {
                        throw e
                    } catch (e: Exception) {
                        problems += MergeProblem(entry.name, describe(e))
                        continue
                    }
                    if (outcome == null) unchanged++ else {
                        merged++
                        added += outcome
                    }
                    break
                }
            } catch (e: SecurityException) {
                return MergeResult(now, phones.size, merged, unchanged, added, problems, BackupError.FOLDER_ACCESS_LOST)
            }
        }
        return MergeResult(now, phones.size, merged, unchanged, added, problems)
    }

    /**
     * Merges [phone]'s chain: its newest generation whose base reads (an older one while a new
     * base is still syncing). Returns how many events were added, [NOTHING_NEW] when no file
     * body had to be read, or null when no generation could be read at all.
     */
    private fun mergeChain(
        folder: BackupFolder,
        phone: String,
        gens: Map<Long, Map<Long, FolderEntry>>,
        problems: MutableList<MergeProblem>,
    ): Int? {
        for (gen in gens.keys.sortedDescending()) {
            val files = contiguous(gens.getValue(gen))
            if (files.isEmpty()) continue
            val cursor = chainCursor(phone)
            var start = if (cursor != null && cursor.gen == gen) cursor.seq + 1 else 0L
            var added = 0
            var readAny = false
            if (start == 0L) {
                try {
                    val h = readHeader(folder, files[0], phone, gen, 0)
                    if (!nothingNewIn(h, phone)) {
                        added += fermata.merge(readFile(folder, files[0], phone, gen, 0).events)
                        readAny = true
                    }
                    rememberChain(phone, gen, 0, h.legacyDigest)
                } catch (e: SecurityException) {
                    throw e
                } catch (e: FileNotFoundException) {
                    continue // deleted while we looked: the phone combined again
                } catch (e: Exception) {
                    problems += MergeProblem(files[0].name, describe(e))
                    continue
                }
                start = 1
            }
            for (s in start until files.size) {
                val entry = files[s.toInt()]
                try {
                    val h = readHeader(folder, entry, phone, gen, s)
                    if (!covered(h.heads, fermata.loggedHeads())) {
                        added += fermata.merge(readFile(folder, entry, phone, gen, s).events)
                        readAny = true
                    }
                    rememberChain(phone, gen, s, null)
                } catch (e: SecurityException) {
                    throw e
                } catch (e: FileNotFoundException) {
                    break // the phone combined its pieces meanwhile; the next merge reads the new base
                } catch (e: Exception) {
                    // Half-synced or damaged: stop here, the next merge carries on from this piece.
                    problems += MergeProblem(entry.name, describe(e))
                    break
                }
            }
            return if (readAny) added else NOTHING_NEW
        }
        return null
    }

    /** True when a file with header [h] holds nothing this phone lacks: its body needn't be read. */
    private fun nothingNewIn(h: LogHeader, phone: String): Boolean {
        val legacyKnown = h.legacyCount == 0L || h.legacyDigest == state.load().mergedLegacy[phone]
        return legacyKnown && covered(h.heads, fermata.loggedHeads())
    }

    private fun chainCursor(phone: String): ChainPoint? {
        val parts = (state.load().merged[CHAIN_KEY + phone] ?: return null).split(':')
        if (parts.size != 2) return null
        return ChainPoint(parts[0].toLongOrNull() ?: return null, parts[1].toLongOrNull() ?: return null)
    }

    private fun rememberChain(phone: String, gen: Long, seq: Long, legacyDigest: String?) {
        val s = state.load()
        state.save(
            s.copy(
                merged = s.merged + (CHAIN_KEY + phone to "$gen:$seq"),
                mergedLegacy = if (legacyDigest != null) s.mergedLegacy + (phone to legacyDigest) else s.mergedLegacy,
            )
        )
    }

    /**
     * Merges, then backs up, under one lock. [onlyIfChanged] uses [backUpIfChanged] (ignored
     * with [replacePrevious], which always writes).
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
     * What to offer on a fresh install: nothing when the log has events or the offer was
     * dismissed, otherwise the restorable backups in [folder] (or a prompt to pick one).
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

    // ---------------------------------------------------------------------------------------
    // Writing

    private data class Snapshot(
        val heads: Map<String, Long>,
        val count: Long,
        val clearedBefore: Long?,
        val newEvents: List<Event>? = null,
    )

    private class OwnTip(val gen: Long, val entry: FolderEntry, val header: LogHeader, val files: List<FolderEntry>) {
        val totalBytes: Long get() = files.sumOf { maxOf(it.size, 0L) }
    }

    /**
     * This phone's chain: the newest generation with a base, up to its last file that reads.
     * Null when there is none, or when nothing of that generation reads (the next backup then
     * starts a new generation).
     */
    private fun ownTip(folder: BackupFolder, entries: List<FolderEntry>): OwnTip? {
        val gens = chainsIn(entries)[deviceId] ?: return null
        val gen = gens.keys.sortedDescending().firstOrNull { gens.getValue(it).containsKey(0L) } ?: return null
        val files = contiguous(gens.getValue(gen))
        for (i in files.indices.reversed()) {
            val h = try {
                readHeader(folder, files[i], deviceId, gen, i.toLong())
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            return OwnTip(gen, files[i], h, files.subList(0, i + 1))
        }
        return null
    }

    private fun writeBackup(folder: BackupFolder, now: Long, replacePrevious: Boolean): BackupResult {
        val entries = folder.list()
        for (t in entries) if (BackupNames.isOwnTemp(t.name, deviceId)) runCatching { folder.delete(t) }
        val own = ownTip(folder, entries)
        val snap = fermata.transaction {
            Snapshot(
                fermata.loggedHeads(), fermata.eventCount(), fermata.clearedBefore(),
                own?.let { fermata.deviceEventsAfter(it.header.heads) },
            )
        }
        // After a clear (here, or merged in from another phone) start afresh, so no file keeps
        // the cleared history.
        val fresh = replacePrevious || (own != null && own.header.clearedBefore != snap.clearedBefore)
        val newEvents = snap.newEvents.orEmpty()
        val continues = own != null && !fresh && covered(own.header.heads, snap.heads) &&
            own.header.chainCount + newEvents.size == snap.count
        if (own != null && continues && newEvents.isEmpty()) {
            // Nothing new since the last file ("Back up now" twice): nothing to write.
            cleanUp(folder, own.gen, own.header.seq, own.header.restorePoint)
            return BackupResult.Success(now, own.entry.name, own.header.chainCount, size(own.entry), written = false, totalBytes = own.totalBytes)
        }
        // A file of this generation past the tip (a damaged piece, or one after a gap) would clash
        // with the next piece's name: start a new generation instead.
        val strays = own != null && chainsIn(entries)[deviceId]?.get(own.gen)?.keys?.any { it > own.header.seq } == true
        val piece = own != null && continues && !strays && !combineDue(own)
        val rotate = own != null && !fresh && previousDue(now)
        val restorePoint: ChainPoint? = if (own != null && rotate) {
            ChainPoint(own.gen, own.header.seq, now) // the state before this backup
        } else {
            own?.header?.restorePoint
        }
        val header: LogHeader
        val bytes: ByteArray
        val name: String
        if (own != null && piece) {
            val seq = own.header.seq + 1
            val built = LogFile.build(
                LogFileKind.PIECE, deviceId, own.gen, seq, newEvents, now, appVersion,
                prev = own.header, clearedBefore = snap.clearedBefore, restorePoint = restorePoint,
            )
            header = built.first
            bytes = built.second
            name = BackupNames.chainPiece(deviceId, own.gen, seq)
        } else {
            val gen = maxOf(chainsIn(entries)[deviceId]?.keys?.maxOrNull() ?: 0L, state.load().lastGen) + 1
            val (events, cleared) = fermata.transaction { fermata.events() to fermata.clearedBefore() }
            val built = LogFile.build(
                LogFileKind.BASE, deviceId, gen, 0, events, now, appVersion,
                prev = null, clearedBefore = cleared, restorePoint = if (fresh) ChainPoint(gen, 0, now) else restorePoint,
            )
            header = built.first
            bytes = built.second
            name = BackupNames.chainBase(deviceId, gen)
        }
        val written = writeFile(folder, name, header, bytes, now)
        if (written !is BackupResult.Success) return written
        if (rotate || fresh) state.save(state.load().copy(previousRotatedAt = now))
        if (header.gen > state.load().lastGen) state.save(state.load().copy(lastGen = header.gen))
        // Committed. Files nothing points to go only now; if this is killed, the next run does it.
        cleanUp(folder, header.gen, header.seq, header.restorePoint)
        val total = (if (own != null && piece) own.totalBytes else 0L) + bytes.size
        return written.copy(totalBytes = total)
    }

    /**
     * Writes [bytes] to a temp file, reads it back and checks it against [header], then renames
     * it to [name]. On any failure the temp file is removed and the folder is as it was.
     */
    private fun writeFile(folder: BackupFolder, name: String, header: LogHeader, bytes: ByteArray, now: Long): BackupResult {
        val temp = folder.create(BackupNames.temp(deviceId, "$now-${nonce()}"))
        try {
            folder.openOutput(temp).use { LogFile.write(it, bytes) }
        } catch (e: Exception) {
            runCatching { folder.delete(temp) }
            throw e
        }
        val problem = try {
            val back = folder.openInput(temp).use { LogFile.read(it) }
            when {
                back.header != header -> "the header differs"
                back.events.size.toLong() != header.events -> "${back.events.size} events, expected ${header.events}"
                else -> null
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            describe(e)
        }
        if (problem != null) {
            runCatching { folder.delete(temp) }
            return BackupResult.Failure(now, BackupError.VERIFY_FAILED, "The new backup didn't check out ($problem); the old one is kept")
        }
        return try {
            val moved = folder.rename(temp, name)
            check(moved.name == name) { "the folder named the backup ${moved.name}" }
            BackupResult.Success(now, name, header.chainCount, bytes.size.toLong())
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            runCatching { folder.delete(temp) }
            BackupResult.Failure(now, BackupError.RENAME_FAILED, "Couldn't move the new backup into place (${describe(e)}); the old one is kept")
        }
    }

    /**
     * Deletes this phone's files nothing points to any more: other generations, pieces past the
     * tip, the restore point's generation past the restore point, and (once the chain has a
     * restore point of its own) the format-1 files from before 6.2. Best effort; the next run
     * finishes what fails.
     */
    private fun cleanUp(folder: BackupFolder, gen: Long, seq: Long, restorePoint: ChainPoint?) {
        val entries = try {
            folder.list()
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            return
        }
        for (e in entries) {
            val c = BackupNames.parseChain(e.name)
            val drop = if (c != null) {
                c.deviceId == deviceId &&
                    !(c.gen == gen && c.seq <= seq) &&
                    !(restorePoint != null && c.gen == restorePoint.gen && c.seq <= restorePoint.seq)
            } else {
                restorePoint != null && BackupNames.parse(e.name)?.deviceId == deviceId
            }
            if (drop) runCatching { folder.delete(e) }
        }
    }

    /**
     * True when the next backup should combine the chain into a new base: [MAX_PIECES] pieces, or
     * pieces adding up to half the base and at least [COMBINE_MIN_BYTES] (a piece compresses less
     * well than one base).
     */
    private fun combineDue(own: OwnTip): Boolean {
        if (own.header.seq >= MAX_PIECES) return true
        val pieceBytes = own.files.drop(1).sumOf { maxOf(it.size, 0L) }
        val baseBytes = maxOf(own.files[0].size, 0L)
        return pieceBytes >= COMBINE_MIN_BYTES && pieceBytes * 2 >= baseBytes
    }

    private fun previousDue(now: Long): Boolean {
        val last = state.load().previousRotatedAt ?: return true
        return now - last >= PREVIOUS_EVERY_MS || now < last - CLOCK_SLACK_MS
    }

    // ---------------------------------------------------------------------------------------
    // Reading

    /** device -> generation -> piece number -> file, for every format-2 file in [entries]. */
    private fun chainsIn(entries: List<FolderEntry>): Map<String, Map<Long, Map<Long, FolderEntry>>> {
        val out = sortedMapOf<String, MutableMap<Long, MutableMap<Long, FolderEntry>>>()
        for (e in entries) {
            val c = BackupNames.parseChain(e.name) ?: continue
            out.getOrPut(c.deviceId) { sortedMapOf() }.getOrPut(c.gen) { sortedMapOf() }[c.seq] = e
        }
        return out
    }

    /** The base and the pieces after it with no gap: empty when there is no base. */
    private fun contiguous(files: Map<Long, FolderEntry>): List<FolderEntry> {
        val out = ArrayList<FolderEntry>()
        var s = 0L
        while (true) out += files[s++] ?: break
        return out
    }

    private fun readHeader(folder: BackupFolder, entry: FolderEntry, device: String, gen: Long, seq: Long): LogHeader =
        folder.openInput(entry).use { LogFile.readHeader(it) }.also { checkName(it, entry, device, gen, seq) }

    private fun readFile(folder: BackupFolder, entry: FolderEntry, device: String, gen: Long, seq: Long): LogFileData =
        folder.openInput(entry).use { LogFile.read(it) }.also { checkName(it.header, entry, device, gen, seq) }

    private fun checkName(h: LogHeader, entry: FolderEntry, device: String, gen: Long, seq: Long) {
        if (h.deviceId != device || h.gen != gen || h.seq != seq) {
            throw BackupException("${entry.name} says it is ${h.deviceId} g${h.gen} p${h.seq}")
        }
        if ((seq == 0L) != (h.kind == LogFileKind.BASE)) throw BackupException("${entry.name} is the wrong kind of file")
    }

    // ---------------------------------------------------------------------------------------
    // Format 1 (phones on 6.1.1 or older): merged as before

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

    private fun mergeOne(folder: BackupFolder, phone: String, entry: FolderEntry): Int? {
        val name = entry.name
        var current = entry
        val backup = try {
            readChecked(folder, current, phone)
        } catch (e: FileNotFoundException) {
            current = folder.list().firstOrNull { it.name == name } ?: throw e
            readChecked(folder, current, phone)
        } ?: return null
        val added = fermata.merge(backup.events)
        remember(phone, BackupArchive.eventsChecksum(backup.manifest), BackupArchive.legacyDigest(backup.events))
        return added
    }

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

    private fun nothingNew(m: Manifest, phone: String, s: BackupState): Boolean {
        if (m.format > BackupArchive.FORMAT || m.deviceId != phone) return false
        val legacyKnown = when (val n = m.legacyCount) {
            null -> false
            0L -> true
            else -> n > 0 && m.legacyDigest != null && m.legacyDigest == s.mergedLegacy[phone]
        }
        if (!legacyKnown) return false
        return covered(m.heads, fermata.loggedHeads())
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
                lastBytes = if (result.totalBytes > 0) result.totalBytes else result.bytes,
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

    companion object {
        /** How often the restore point ("previous") moves on: at most once a day. */
        const val PREVIOUS_EVERY_MS = 24L * 60 * 60 * 1000

        /** A chain with this many pieces is combined into a new base on the next backup. */
        const val MAX_PIECES = 32L

        /** Pieces are combined once they add up to half the base, and to at least this much. */
        const val COMBINE_MIN_BYTES = 64L * 1024

        /** A clock set back by more than this also moves the restore point on (else it would stick). */
        private const val CLOCK_SLACK_MS = 60L * 60 * 1000

        private const val CHAIN_KEY = "chain:"
        private const val NOTHING_NEW = -1

        /** One backup, restore or merge at a time, across every instance in the process. */
        private val LOCK = Any()
        private val random = SecureRandom()

        private fun nonce(): String = "%08x".format(random.nextInt())

        private fun size(e: FolderEntry): Long = maxOf(e.size, 0L)

        /** True when version vector [local] has everything [heads] counts. */
        internal fun covered(heads: Map<String, Long>, local: Map<String, Long>): Boolean =
            heads.all { (device, counter) -> counter <= (local[device] ?: 0L) }

        private fun describe(e: Throwable): String = when (e) {
            is FileNotFoundException -> "file not found: ${e.message}"
            is IOException, is BackupException -> e.message ?: e.javaClass.simpleName
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
