package com.minim.fermata.sync

import com.minim.fermata.Fermata
import com.minim.fermata.backup.BackupArchive
import com.minim.fermata.backup.BackupNames
import com.minim.fermata.backup.LogFile
import com.minim.fermata.backup.BackupRole
import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventContext
import com.minim.fermata.log.EventId
import com.minim.fermata.log.EventStore
import com.minim.fermata.log.HOUR
import com.minim.fermata.log.InMemoryEventStore
import com.minim.fermata.log.MINUTE
import com.minim.fermata.log.Source
import com.minim.fermata.log.Subject
import com.minim.fermata.log.T0
import com.minim.fermata.log.track
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class TestClock(var now: Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(now)
}

/** A folder that fails on request, to check every failure leaves a good `latest`. */
private class FaultyFolder(private val inner: BackupFolder) : BackupFolder by inner {
    /** Flip one byte of everything written from now on (a bad disk or provider). */
    var corruptWrites = false
    /** Make every rename to this name fail. */
    var failRenameTo: String? = null
    /** Make creating files fail (disk full). */
    var failCreate = false
    /** Names whose next open fails as if another phone had just moved the file. */
    val vanishOnce = HashSet<String>()
    /** Throw SecurityException from every call (permission revoked). */
    var revoked = false

    private fun gate() {
        if (revoked) throw SecurityException("no permission")
    }

    override fun list(): List<FolderEntry> { gate(); return inner.list() }

    override fun openInput(entry: FolderEntry): InputStream {
        gate()
        if (vanishOnce.remove(entry.name)) throw FileNotFoundException(entry.name)
        return inner.openInput(entry)
    }

    override fun create(name: String): FolderEntry {
        gate()
        if (failCreate) throw IOException("no space left")
        return inner.create(name)
    }

    override fun openOutput(entry: FolderEntry): OutputStream {
        gate()
        val out = inner.openOutput(entry)
        if (!corruptWrites) return out
        return object : FilterOutputStream(out) {
            var n = 0L
            override fun write(b: Int) {
                out.write(if (n++ == 200L) b xor 0xFF else b)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                for (i in off until off + len) write(b[i].toInt())
            }
        }
    }

    override fun rename(entry: FolderEntry, newName: String): FolderEntry {
        gate()
        if (newName == failRenameTo) throw IOException("rename not allowed")
        return inner.rename(entry, newName)
    }

    override fun delete(entry: FolderEntry) { gate(); inner.delete(entry) }
}

class FolderBackupsTest {
    @TempDir
    lateinit var dir: File

    private val clock = TestClock(T0)
    private val a = track("A", "Alpha")
    private val b = track("B", "Beta")
    private val c = track("C", "Gamma")

    private fun fermata(device: String, store: EventStore = InMemoryEventStore()) =
        Fermata(store, device, clock, ZoneOffset.UTC)

    private fun folder() = FileBackupFolder(dir)

    private fun names() = dir.list()!!.sorted()

    private fun Fermata.someHistory(vararg tracks: com.minim.fermata.log.TrackRef) {
        for (t in tracks) {
            record(EventBody.Play(t, 120_000), Source.RADIO)
            clock.now += 3 * MINUTE
        }
    }

    @Test
    fun `backup writes a base, then small pieces, keeps a daily restore point, and records the status`() {
        val f = fermata("phoneA")
        f.someHistory(a, b)
        f.record(EventBody.Like(Subject.Track(a)))
        val backups = FolderBackups(f, InMemoryBackupStateStore(), appVersion = "6.2.0", clock = clock)

        val first = assertIs<BackupResult.Success>(backups.backUp(folder()))
        assertEquals("minim-phoneA-g001-base.fermata", first.fileName)
        assertEquals(3L, first.eventCount)
        assertEquals(File(dir, first.fileName).length(), first.bytes)
        assertEquals(listOf("minim-phoneA-g001-base.fermata"), names())

        clock.now += HOUR
        f.someHistory(c)
        val second = assertIs<BackupResult.Success>(backups.backUp(folder()))
        assertEquals("minim-phoneA-g001-p0001.fermata", second.fileName)
        assertEquals(4L, second.eventCount)
        assertEquals(File(dir, second.fileName).length(), second.bytes)
        assertEquals(File(dir, "minim-phoneA-g001-base.fermata").length() + second.bytes, second.totalBytes)
        assertEquals(listOf("minim-phoneA-g001-base.fermata", "minim-phoneA-g001-p0001.fermata"), names())
        assertEquals(4L, info(backups, BackupRole.LATEST).eventCount)
        assertEquals(3L, info(backups, BackupRole.PREVIOUS).eventCount)
        assertEquals("minim-phoneA-g001-base.fermata", info(backups, BackupRole.PREVIOUS).fileName)
        assertEquals("6.2.0", info(backups, BackupRole.LATEST).appVersion)
        val piece = File(dir, "minim-phoneA-g001-p0001.fermata").inputStream().use { LogFile.read(it) }
        assertEquals(1, piece.events.size, "a piece holds only what is new")

        // A third backup the same day adds a piece; the restore point moves on at most once a day.
        clock.now += HOUR
        f.someHistory(a)
        assertIs<BackupResult.Success>(backups.backUp(folder()))
        assertEquals(listOf("minim-phoneA-g001-base.fermata", "minim-phoneA-g001-p0001.fermata", "minim-phoneA-g001-p0002.fermata"), names())
        assertEquals(5L, info(backups, BackupRole.LATEST).eventCount)
        assertEquals(3L, info(backups, BackupRole.PREVIOUS).eventCount)

        val status = backups.status()
        assertTrue(status.ok)
        assertEquals(clock.now, status.lastSuccessAt)
        assertEquals(5L, status.lastEventCount)
        assertNull(status.lastError)
    }

    private fun info(backups: FolderBackups, role: BackupRole) =
        backups.list(folder()).single { it.fromThisPhone && it.role == role }

    @Test
    fun `a backup that does not read back is thrown away and the old latest stays`() {
        val f = fermata("phoneA")
        f.someHistory(a)
        val faulty = FaultyFolder(folder())
        val backups = FolderBackups(f, InMemoryBackupStateStore(), clock = clock)
        assertIs<BackupResult.Success>(backups.backUp(faulty))
        val good = File(dir, "minim-phoneA-g001-base.fermata").readBytes()

        f.someHistory(b)
        faulty.corruptWrites = true
        val failed = assertIs<BackupResult.Failure>(backups.backUp(faulty))
        assertEquals(BackupError.VERIFY_FAILED, failed.error)
        assertEquals(listOf("minim-phoneA-g001-base.fermata"), names()) // temp removed, nothing else touched
        assertTrue(good.contentEquals(File(dir, "minim-phoneA-g001-base.fermata").readBytes()))

        val status = backups.status()
        assertFalse(status.ok)
        assertEquals(BackupError.VERIFY_FAILED, status.lastError)
        assertNotNull(status.lastSuccessAt) // the earlier success is still shown
    }

    @Test
    fun `if the new file cannot be renamed into place nothing changes and the next run carries on`() {
        val f = fermata("phoneA")
        f.someHistory(a)
        val faulty = FaultyFolder(folder())
        val backups = FolderBackups(f, InMemoryBackupStateStore(), clock = clock)
        backups.backUp(faulty)
        f.someHistory(b)
        backups.backUp(faulty) // base (1 event) + piece 1 (1 event)

        f.someHistory(c)
        faulty.failRenameTo = "minim-phoneA-g001-p0002.fermata"
        assertEquals(BackupError.RENAME_FAILED, assertIs<BackupResult.Failure>(backups.backUp(faulty)).error)
        assertEquals(listOf("minim-phoneA-g001-base.fermata", "minim-phoneA-g001-p0001.fermata"), names(), "no temp file left, nothing else touched")
        assertEquals(2L, info(backups, BackupRole.LATEST).eventCount)

        faulty.failRenameTo = null
        assertIs<BackupResult.Success>(backups.backUp(faulty))
        assertEquals(3L, info(backups, BackupRole.LATEST).eventCount)
    }

    @Test
    fun `write failures and lost access are reported, and nothing is left behind`() {
        val f = fermata("phoneA")
        f.someHistory(a)
        val faulty = FaultyFolder(folder())
        val backups = FolderBackups(f, InMemoryBackupStateStore(), clock = clock)
        faulty.failCreate = true
        assertEquals(BackupError.WRITE_FAILED, assertIs<BackupResult.Failure>(backups.backUp(faulty)).error)
        faulty.failCreate = false
        faulty.revoked = true
        assertEquals(BackupError.FOLDER_ACCESS_LOST, assertIs<BackupResult.Failure>(backups.backUp(faulty)).error)
        assertEquals(BackupError.NO_FOLDER, assertIs<BackupResult.Failure>(backups.backUp(null)).error)
        assertEquals(emptyList(), names())
        assertEquals(BackupError.NO_FOLDER, backups.status().lastError)
    }

    @Test
    fun `stale temp files of this phone are cleaned up, other phones' are left alone`() {
        File(dir, "minim-phoneA-123-deadbeef.tmp").writeText("half a backup")
        File(dir, "minim-phoneB-456-cafebabe.tmp").writeText("phone B is writing this right now")
        File(dir, "notes.txt").writeText("the user's own file")
        val f = fermata("phoneA")
        f.someHistory(a)
        assertIs<BackupResult.Success>(FolderBackups(f, InMemoryBackupStateStore(), clock = clock).backUp(folder()))
        assertEquals(listOf("minim-phoneA-g001-base.fermata", "minim-phoneB-456-cafebabe.tmp", "notes.txt"), names())
    }

    @Test
    fun `list shows every phone's backups newest first and flags the ones that can't be restored`() {
        val pa = fermata("phoneA")
        pa.someHistory(a)
        val backupsA = FolderBackups(pa, InMemoryBackupStateStore(), clock = clock)
        backupsA.backUp(folder())
        clock.now += HOUR
        val pb = fermata("phoneB")
        pb.someHistory(b, c)
        FolderBackups(pb, InMemoryBackupStateStore(), clock = clock).backUp(folder())
        clock.now += HOUR
        pa.someHistory(c)
        backupsA.backUp(folder())
        File(dir, "minim-phoneC-latest.minim").writeText("not a zip")
        File(dir, "minim-phoneB-99-abcd.tmp").writeText("ignored")
        olderVersionBackup(File(dir, "minim-backup-2026-11-01.minim"))

        val list = backupsA.list(folder())
        assertEquals(
            listOf(
                "minim-phoneA-g001-p0001.fermata", "minim-phoneB-g001-base.fermata", "minim-phoneA-g001-base.fermata",
                "minim-backup-2026-11-01.minim", "minim-phoneC-latest.minim",
            ),
            list.map { it.fileName },
        )
        assertTrue(list[0].fromThisPhone && list[0].role == BackupRole.LATEST)
        assertEquals("phoneB", list[1].deviceId)
        assertFalse(list[1].fromThisPhone)
        assertEquals(2L, list[1].eventCount)
        assertEquals(BackupRole.DAILY, list[3].role)
        assertEquals("0.1.0", list[3].fermataVersion)
        assertTrue(list[3].restorable)
        assertFalse(list[4].restorable)
        assertNotNull(list[4].problem)
    }

    @Test
    fun `restore loads a backup into a fresh install, keeps ids, and this phone's counter moves on`() {
        val old = fermata("oldphone")
        old.someHistory(a, b)
        old.record(EventBody.PlaylistCreate("p", "Mix"))
        old.record(EventBody.PlaylistAdd("p", a))
        old.record(EventBody.Like(Subject.Artist("Beta")))
        FolderBackups(old, InMemoryBackupStateStore(), clock = clock).backUp(folder())

        val fresh = fermata("newphone")
        val backups = FolderBackups(fresh, InMemoryBackupStateStore(), clock = clock)
        val offer = assertIs<RestoreOffer.Available>(backups.restoreOffer(folder()))
        val restored = assertIs<RestoreResult.Success>(backups.restore(folder(), offer.backups.single()))
        assertEquals(5, restored.added)
        assertEquals("oldphone", restored.fromDevice)
        assertEquals(old.library().copy(), fresh.library())
        assertEquals(old.taste(now = clock.now), fresh.taste(now = clock.now))
        assertEquals(setOf("oldphone"), fresh.heads().keys)
        assertEquals(RestoreOffer.NotNeeded, backups.restoreOffer(folder()))

        // This phone keeps its own id; its new events never clash with the old phone's.
        val next = fresh.record(EventBody.Like(Subject.Track(c)))
        assertEquals(EventId("newphone", 1), next.id)
        assertTrue(next.timestamp > fresh.library().history.maxOf { it.playedAt })

        // Restoring again changes nothing.
        assertEquals(0, assertIs<RestoreResult.Success>(backups.restore(folder(), offer.backups.single())).added)
    }

    @Test
    fun `restoring a backup that holds this phone's own ids moves the counter past them`() {
        val store = InMemoryEventStore()
        val p = fermata("phoneA", store)
        p.someHistory(a, b, c)
        val backups = FolderBackups(p, InMemoryBackupStateStore(), clock = clock)
        backups.backUp(folder())

        // Same device id, empty log (say the app's data was cleared but the id was kept).
        val again = fermata("phoneA")
        val restore = FolderBackups(again, InMemoryBackupStateStore(), clock = clock)
        assertIs<RestoreResult.Success>(restore.restore(folder(), restore.list(folder()).single()))
        assertEquals(EventId("phoneA", 4), again.record(EventBody.Play(a, 1_000)).id)
    }

    @Test
    fun `a pre-6_2 phone's damaged, missing or too-new backup changes nothing`() {
        val donor = fermata("donor")
        donor.someHistory(a, b)
        Format1Backups(donor, InMemoryBackupStateStore(), clock = clock).backUp(folder())
        val latest = File(dir, "minim-donor-latest.minim")
        val bytes = latest.readBytes()

        val p = fermata("phoneA")
        p.someHistory(c)
        val before = p.library()
        val backups = FolderBackups(p, InMemoryBackupStateStore(), clock = clock)
        val info = backups.list(folder()).first { it.deviceId == "donor" }

        // Half synced: cut short inside the event log.
        latest.writeBytes(cutInsideEvents(bytes))
        assertEquals(BackupError.DAMAGED, assertIs<RestoreResult.Failure>(backups.restore(folder(), info)).error)
        // One byte flipped inside the event log.
        latest.writeBytes(flipInsideEvents(bytes))
        assertEquals(BackupError.DAMAGED, assertIs<RestoreResult.Failure>(backups.restore(folder(), info)).error)
        // Made by a newer version.
        writeBackupWithFormat(latest, 99)
        assertEquals(BackupError.TOO_NEW, assertIs<RestoreResult.Failure>(backups.restore(folder(), info)).error)
        // Gone.
        latest.delete()
        assertEquals(BackupError.NOT_FOUND, assertIs<RestoreResult.Failure>(backups.restore(folder(), info)).error)

        assertEquals(before, p.library())
        assertEquals(1L, p.eventCount())
    }

    @Test
    fun `a restore that fails while writing leaves the log, counter and library as they were`() {
        val donor = fermata("donor")
        donor.someHistory(a, b, c)
        FolderBackups(donor, InMemoryBackupStateStore(), clock = clock).backUp(folder())

        val store = FailingStore(failAfter = 1)
        val p = fermata("phoneA", store)
        p.someHistory(a)
        val before = p.library()
        store.armed = true
        val backups = FolderBackups(p, InMemoryBackupStateStore(), clock = clock)
        val result = backups.restore(folder(), backups.list(folder()).first { it.deviceId == "donor" })
        assertEquals(BackupError.RESTORE_FAILED, assertIs<RestoreResult.Failure>(result).error)
        assertEquals(1L, p.eventCount())
        assertEquals(before, p.library())
        assertEquals(EventId("phoneA", 2), p.record(EventBody.Play(b, 1_000)).id)
    }

    @Test
    fun `a backup from an older Fermata restores and rebuilds the library from its events`() {
        olderVersionBackup(File(dir, "minim-backup-2026-11-01.minim"))
        val p = fermata("phoneA")
        val backups = FolderBackups(p, InMemoryBackupStateStore(), clock = clock)
        val info = backups.list(folder()).single()
        val r = assertIs<RestoreResult.Success>(backups.restore(folder(), info))
        assertEquals("0.1.0", r.fermataVersion)
        assertEquals(6, r.added)
        val lib = p.library()
        assertEquals(listOf("A"), lib.likedTracks.map { it.track.id })
        assertEquals(listOf("Old mix"), lib.playlists.map { it.name })
        assertEquals(listOf("A"), lib.playlists.single().tracks.map { it.id })
        assertEquals(2, lib.history.size)
        assertEquals(mapOf("A" to 1, "B" to 1), lib.playCounts)
        // The event type this version does not know is kept, and goes into the next backup.
        assertTrue(p.eventsAfter(emptyMap()).any { it.type == "future_thing" })
        assertIs<BackupResult.Success>(backups.backUp(folder()))
        assertTrue(File(dir, "minim-phoneA-g001-base.fermata").inputStream().use { LogFile.read(it) }.events.any { it.type == "future_thing" })
    }

    @Test
    fun `merge reads two other phones, adds only what is new, and repeating it adds nothing`() {
        val pa = fermata("phoneA")
        val pb = fermata("phoneB")
        val pc = fermata("phoneC")
        pa.someHistory(a, b)
        pa.record(EventBody.PlaylistCreate("p", "Shared"))
        pb.someHistory(c)
        pb.record(EventBody.Like(Subject.Track(a)))
        // Both A and B also hold the same synced legacy play (old Firestore history).
        val legacy = EventBody.Play(track("E"), 150_000)
        pa.importLegacy(legacy, T0 - HOUR, "play|xyz_E")
        pb.importLegacy(legacy, T0 - HOUR, "play|xyz_E")
        val sa = InMemoryBackupStateStore()
        val sb = InMemoryBackupStateStore()
        FolderBackups(pa, sa, clock = clock).backUp(folder())
        FolderBackups(pb, sb, clock = clock).backUp(folder())

        val backupsC = FolderBackups(pc, InMemoryBackupStateStore(), clock = clock)
        val first = backupsC.mergeFromFolder(folder())
        assertEquals(2, first.phones)
        assertEquals(2, first.filesMerged)
        assertEquals(6, first.eventsAdded) // 2 + 1 + 1 + 1 + 1, the shared legacy play once
        assertEquals(emptyList(), first.problems)
        assertEquals(1, pc.library().playCounts["E"])
        assertTrue(pc.library().isLiked("A"))
        assertEquals(listOf("Shared"), pc.library().playlists.map { it.name })

        // Nothing changed: the files are not even read in full again.
        val second = backupsC.mergeFromFolder(folder())
        assertEquals(2, second.unchanged)
        assertEquals(0, second.eventsAdded)

        // Phone A merges B, backs up again; C merges once more and gets only A's new event.
        FolderBackups(pa, sa, clock = clock).mergeFromFolder(folder())
        pa.someHistory(c)
        FolderBackups(pa, sa, clock = clock).backUp(folder())
        val third = backupsC.mergeFromFolder(folder())
        assertEquals(1, third.filesMerged)
        assertEquals(1, third.unchanged)
        assertEquals(1, third.eventsAdded)
        assertEquals(pa.library().playCounts, pc.library().playCounts)

        // Merging is by id even without the checksum shortcut.
        val noMemory = FolderBackups(pc, InMemoryBackupStateStore(), clock = clock).mergeFromFolder(folder())
        assertEquals(2, noMemory.filesMerged)
        assertEquals(0, noMemory.eventsAdded)
    }

    @Test
    fun `a 5_9_0 play and its legacy copy count once across phones whichever order they merge`() {
        val legacyId = EventId.legacy("play|abc_A")
        // Phone A imported 5.9.0's table: its play replaces the legacy copy.
        val pa = fermata("3f9a0c5e1b7d2468")
        pa.merge(
            listOf(
                Event(
                    EventId("3f9a0c5e1b7d2468", 1),
                    EventContext.at(T0, ZoneOffset.UTC, "uuid-1", null, Source.RADIO, replaces = legacyId),
                    EventBody.Play(a, 185_000),
                ),
            ),
        )
        // Phone B only had the old synced play.
        val pb = fermata("9c0ffee000000001")
        pb.importLegacy(EventBody.Play(a, 180_000), T0 + 30_000, "play|abc_A")
        FolderBackups(pa, InMemoryBackupStateStore(), clock = clock).backUp(folder())
        FolderBackups(pb, InMemoryBackupStateStore(), clock = clock).backUp(folder())

        for (device in listOf("phoneC", "phoneD")) {
            val p = fermata(device)
            FolderBackups(p, InMemoryBackupStateStore(), clock = clock).mergeFromFolder(folder())
            assertEquals(2L, p.eventCount()) // both stay in the log...
            assertEquals(mapOf("A" to 1), p.library().playCounts) // ...but the play counts once
            assertEquals(185_000L, p.library().history.single().listenedMs)
        }
        // And on the phones themselves after they merge each other.
        FolderBackups(pb, InMemoryBackupStateStore(), clock = clock).mergeFromFolder(folder())
        assertEquals(mapOf("A" to 1), pb.library().playCounts)
    }

    @Test
    fun `a pre-6_2 phone's damaged or half-synced latest falls back to previous and is retried next time`() {
        val pb = fermata("phoneB")
        pb.someHistory(a)
        val sb = InMemoryBackupStateStore()
        Format1Backups(pb, sb, clock = clock).backUp(folder())
        pb.someHistory(b)
        Format1Backups(pb, sb, clock = clock).backUp(folder())
        val latest = File(dir, "minim-phoneB-latest.minim")
        val good = latest.readBytes()
        latest.writeBytes(cutInsideEvents(good)) // still syncing

        val pc = fermata("phoneC")
        val backupsC = FolderBackups(pc, InMemoryBackupStateStore(), clock = clock)
        val first = backupsC.mergeFromFolder(folder())
        assertEquals(1, first.filesMerged)
        assertEquals(1, first.eventsAdded) // from previous
        assertEquals(listOf("minim-phoneB-latest.minim"), first.problems.map { it.fileName })

        latest.writeBytes(good) // the sync finished
        val second = backupsC.mergeFromFolder(folder())
        assertEquals(1, second.eventsAdded)
        assertEquals(emptyList(), second.problems)
        assertEquals(pb.library().playCounts, pc.library().playCounts)
    }

    @Test
    fun `a file that vanishes mid-read because its phone is rotating it is found again`() {
        val pb = fermata("phoneB")
        pb.someHistory(a, b)
        FolderBackups(pb, InMemoryBackupStateStore(), clock = clock).backUp(folder())
        val faulty = FaultyFolder(folder())
        faulty.vanishOnce += "minim-phoneB-latest.minim"
        val pc = fermata("phoneC")
        val r = FolderBackups(pc, InMemoryBackupStateStore(), clock = clock).mergeFromFolder(faulty)
        assertEquals(2, r.eventsAdded)
        assertEquals(emptyList(), r.problems)
    }

    @Test
    fun `two phones backing up into one folder at the same time never touch each other's files`() {
        val phones = (1..4).map { n -> fermata("phone$n").also { it.someHistory(a, b) } }
        val threads = phones.map { p ->
            Thread {
                val backups = FolderBackups(p, InMemoryBackupStateStore(), clock = clock)
                repeat(5) { backups.mergeAndBackUp(FileBackupFolder(dir)) }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertTrue(names().none { it.endsWith(".tmp") }, names().toString())
        assertEquals((1..4).map { "phone$it" }.toSet(), names().map { BackupNames.parseChain(it)!!.deviceId }.toSet())
        for (name in names()) File(dir, name).inputStream().use { LogFile.read(it) } // every file checks out
        val pz = fermata("phoneZ")
        FolderBackups(pz, InMemoryBackupStateStore(), clock = clock).mergeFromFolder(folder())
        assertEquals(8L, pz.eventCount())
    }

    @Test
    fun `the restore offer follows the library and the folder`() {
        val p = fermata("phoneA")
        val state = InMemoryBackupStateStore()
        val backups = FolderBackups(p, state, clock = clock)
        assertEquals(RestoreOffer.PickFolder, backups.restoreOffer(null))
        assertEquals(RestoreOffer.NoBackups, backups.restoreOffer(folder()))
        File(dir, "minim-phoneQ-latest.minim").writeText("junk")
        assertEquals(RestoreOffer.NoBackups, backups.restoreOffer(folder()))
        assertEquals(RestoreOffer.PickFolder, backups.restoreOffer(FaultyFolder(folder()).also { it.revoked = true }))
        backups.dismissRestoreOffer()
        assertEquals(RestoreOffer.NotNeeded, backups.restoreOffer(null))
        assertTrue(state.load().restoreOfferDismissed)

        val other = fermata("phoneB")
        other.someHistory(a)
        val backupsB = FolderBackups(other, InMemoryBackupStateStore(), clock = clock)
        assertEquals(RestoreOffer.NotNeeded, backupsB.restoreOffer(folder())) // has a library
    }

    @Test
    fun `file names`() {
        assertEquals("minim-3f9a0c5e1b7d2468-latest.minim", BackupNames.latest("3f9a0c5e1b7d2468"))
        assertEquals("minim-3f9a0c5e1b7d2468-previous.minim", BackupNames.previous("3f9a0c5e1b7d2468"))
        assertEquals(
            com.minim.fermata.backup.BackupName("3f9a0c5e1b7d2468", BackupRole.LATEST),
            BackupNames.parse("minim-3f9a0c5e1b7d2468-latest.minim"),
        )
        assertEquals(BackupRole.PREVIOUS, BackupNames.parse("minim-a-b-previous.minim")?.role)
        assertEquals("a-b", BackupNames.parse("minim-a-b-previous.minim")?.deviceId)
        assertEquals(BackupRole.DAILY, BackupNames.parse("minim-backup-2026-11-11.minim")?.role)
        assertNull(BackupNames.parse("minim-phone-123-abcd.tmp"))
        assertNull(BackupNames.parse("minim-phone-latest.minim (1)"))
        assertNull(BackupNames.parse("minim-legacy-latest.minim"))
        assertNull(BackupNames.parse("photo.jpg"))
        assertTrue(BackupNames.isOwnTemp(BackupNames.temp("phone", "1-ab"), "phone"))
        assertFalse(BackupNames.isOwnTemp(BackupNames.temp("phone2", "1-ab"), "phone"))
        assertFalse(BackupNames.isValidDeviceId("../x"))
    }

    // --- helpers ---

    /** Where the event log's data starts inside a backup zip (after its local file header). */
    @Test
    fun `a merge the app fails to apply is saved in the status and retried next time`() {
        val other = fermata("phoneB")
        other.someHistory(a)
        other.record(EventBody.Like(Subject.Track(b)))
        FolderBackups(other, InMemoryBackupStateStore(), clock = clock).backUp(folder())
        clock.now += MINUTE
        val mine = fermata("phoneA")
        mine.someHistory(c)
        val backups = FolderBackups(mine, InMemoryBackupStateStore(), clock = clock)
        var fail = true
        mine.setApplier { if (fail) throw IllegalStateException("database is locked") }

        val r = backups.mergeFromFolder(folder())
        assertEquals(1, r.problems.size)
        assertEquals(0, r.eventsAdded)
        assertFalse(mine.library().isLiked("B"))
        val st = backups.status()
        assertTrue(st.mergeTroubled)
        assertEquals(1, st.lastMergeProblemCount)
        assertEquals("minim-phoneB-g001-base.fermata: database is locked", st.lastMergeProblem)

        fail = false
        val again = backups.mergeFromFolder(folder())
        assertEquals(1, again.filesMerged, "not remembered as merged: read again")
        assertTrue(mine.library().isLiked("B"))
        assertFalse(backups.status().mergeTroubled)
        assertNull(backups.status().lastMergeProblem)
    }

    @Test
    fun `a folder the merge cannot read is saved in the status`() {
        val mine = fermata("phoneA")
        val backups = FolderBackups(mine, InMemoryBackupStateStore(), clock = clock)
        val goneDir = File(dir, "missing").apply { mkdirs() }
        val gone = FileBackupFolder(goneDir)
        goneDir.delete() // the folder went away after it was picked
        val r = backups.mergeFromFolder(gone)
        assertTrue(r.error != null)
        assertEquals(r.error, backups.status().lastMergeError)
        assertTrue(backups.status().mergeTroubled)
    }

    private fun eventsDataStart(bytes: ByteArray): Int {
        val name = "fermata-events.jsonl.gz".toByteArray()
        val at = (0..bytes.size - name.size).first { i -> name.indices.all { bytes[i + it] == name[it] } }
        val extraLen = (bytes[at - 2].toInt() and 0xFF) or ((bytes[at - 1].toInt() and 0xFF) shl 8)
        return at + name.size + extraLen
    }

    /** A file whose copy stopped part way through the event log. */
    private fun cutInsideEvents(bytes: ByteArray) = bytes.copyOf(eventsDataStart(bytes) + 20)

    /** One byte of the event log flipped (bit rot, a bad sync). */
    private fun flipInsideEvents(bytes: ByteArray) = bytes.copyOf().also {
        val i = eventsDataStart(bytes) + 20
        it[i] = (it[i].toInt() xor 0x55).toByte()
    }

    private fun read(name: String) = File(dir, name).inputStream().use { BackupArchive.read(it) }

    /** An [EventStore] whose appendAll fails part way, rolling back like a SQLite transaction. */
    private class FailingStore(private val failAfter: Int) : EventStore {
        private val inner = InMemoryEventStore()
        var armed = false

        override fun append(event: Event) = inner.append(event)

        override fun appendAll(events: Iterable<Event>): Int {
            if (!armed) return inner.appendAll(events)
            val staged = InMemoryEventStore(inner.all())
            var n = 0
            for (e in events) {
                if (staged.append(e) && ++n > failAfter) throw IOException("disk I/O error")
            }
            return inner.appendAll(events)
        }

        override fun all() = inner.all()
        override fun contains(id: EventId) = inner.contains(id)
        override fun count() = inner.count()
        override fun heads() = inner.heads()
        override fun maxTimestamp() = inner.maxTimestamp()
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun zip(file: File, manifest: String, events: ByteArray) {
        ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("fermata-events.jsonl.gz")); z.write(events); z.closeEntry()
        }
    }

    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun writeBackupWithFormat(file: File, format: Int) {
        val events = gzip("")
        zip(
            file,
            """{"format":$format,"createdAt":$T0,"deviceId":"donor","fermataVersion":"9.0.0","eventCount":0,
               "files":[{"name":"fermata-events.jsonl.gz","sha256":"${sha256(events)}","bytes":${events.size}}]}""",
            events,
        )
    }

    /**
     * A backup as the first Fermata (0.1.0) wrote it: the daily file name, no `heads` or
     * `appVersion` in the manifest, no `replaces` on events, plus an event type from a newer
     * version on another phone, which must survive.
     */
    private fun olderVersionBackup(file: File) {
        val lines = listOf(
            """{"id":"0ldphone00000001:1","ts":${T0 - 3 * HOUR},"hour":21,"dow":2,"session":"0ldphone00000001.1","source":"radio","type":"play","data":{"track":{"id":"A","title":"Song A","artist":"Alpha","durationMs":200000},"listenedMs":150000}}""",
            """{"id":"0ldphone00000001:2","ts":${T0 - 3 * HOUR + 1},"hour":21,"dow":2,"source":"unknown","type":"like","data":{"on":"track","track":{"id":"A"}}}""",
            """{"id":"0ldphone00000001:3","ts":${T0 - 3 * HOUR + 2},"hour":21,"dow":2,"source":"unknown","type":"playlist_create","data":{"playlist":"p1","name":"Old mix"}}""",
            """{"id":"0ldphone00000001:4","ts":${T0 - 3 * HOUR + 3},"hour":21,"dow":2,"source":"unknown","type":"playlist_add","data":{"playlist":"p1","track":{"id":"A"}}}""",
            """{"id":"legacy:12345","ts":${T0 - 2 * HOUR},"hour":22,"dow":2,"source":"import","type":"play","data":{"track":{"id":"B"},"listenedMs":90000}}""",
            """{"id":"n3wphone00000009:1","ts":${T0 - HOUR},"hour":23,"dow":2,"source":"unknown","type":"future_thing","data":{"x":1}}""",
        )
        val events = gzip(lines.joinToString("\n", postfix = "\n"))
        zip(
            file,
            """{"format":1,"createdAt":${T0 - HOUR},"deviceId":"0ldphone00000001","fermataVersion":"0.1.0","eventCount":${lines.size},
               "files":[{"name":"fermata-events.jsonl.gz","sha256":"${sha256(events)}","bytes":${events.size}}]}""",
            events,
        )
    }
}
