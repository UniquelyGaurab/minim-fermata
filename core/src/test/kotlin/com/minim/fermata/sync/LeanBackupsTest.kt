package com.minim.fermata.sync

import com.minim.fermata.Fermata
import com.minim.fermata.backup.BackupArchive
import com.minim.fermata.backup.BackupNames
import com.minim.fermata.backup.BackupRole
import com.minim.fermata.backup.LogFile
import com.minim.fermata.backup.LogFileKind
import com.minim.fermata.log.DAY
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.InMemoryEventStore
import com.minim.fermata.log.MINUTE
import com.minim.fermata.log.Subject
import com.minim.fermata.log.T0
import com.minim.fermata.log.track
import com.minim.fermata.recs.RecsConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class LeanClock(var now: Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(now)
}

/** Thrown to stop a backup dead at one step, as if the phone died there. Not an Exception, so nothing catches it. */
private class Killed : Error("killed")

/**
 * A folder that dies at its [killAt]th change (create, each write, rename, delete), so a test can
 * kill a backup at every step. Writes are cut short at the kill.
 */
private class KillingFolder(private val inner: BackupFolder) : BackupFolder by inner {
    var killAt = Int.MAX_VALUE
    var steps = 0
    val opened = ArrayList<String>()

    private fun step() {
        if (++steps == killAt) throw Killed()
    }

    override fun create(name: String): FolderEntry {
        step()
        return inner.create(name)
    }

    override fun openOutput(entry: FolderEntry): OutputStream {
        step()
        val out = inner.openOutput(entry)
        return object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                // half the bytes, then die: a file cut short
                if (steps + 1 == killAt) {
                    out.write(b, off, len / 2)
                    out.close()
                    step()
                }
                out.write(b, off, len)
            }
            override fun flush() = out.flush()
            override fun close() = out.close()
        }
    }

    override fun rename(entry: FolderEntry, newName: String): FolderEntry {
        step()
        return inner.rename(entry, newName)
    }

    override fun delete(entry: FolderEntry) {
        step()
        inner.delete(entry)
    }

    override fun openInput(entry: FolderEntry): InputStream {
        opened += entry.name
        return inner.openInput(entry)
    }
}

class LeanBackupsTest {
    private val clock = LeanClock(T0)
    private val dir: File = Files.createTempDirectory("lean").toFile()
    private val a = track("a", "Arijit Singh")
    private val b = track("b", "Pritam")

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun fermata(device: String, config: RecsConfig = RecsConfig()) =
        Fermata(InMemoryEventStore(), device, clock, ZoneOffset.UTC, config = config)

    private fun names() = dir.list()!!.filter { File(dir, it).isFile }.sorted()

    private fun Fermata.play(t: com.minim.fermata.log.TrackRef = a) {
        record(EventBody.Play(t, 180_000))
        clock.now += 4 * MINUTE
    }

    private fun latest(backups: FolderBackups, folder: BackupFolder, device: String) =
        backups.list(folder).single { it.deviceId == device && it.role == BackupRole.LATEST }

    /** What a restore of [device]'s latest backup on a fresh phone gives. */
    private fun restored(folder: BackupFolder, device: String): Fermata {
        val c = fermata("restorer")
        val cb = FolderBackups(c, InMemoryBackupStateStore(), clock = clock)
        assertIs<RestoreResult.Success>(cb.restore(folder, latest(cb, folder, device)))
        return c
    }

    // ---------------------------------------------------------------------------------------
    // Kill at every step

    /**
     * Kills a backup at each of its steps in turn, with [setUp] giving the folder and log before
     * it. After every kill the folder must restore to the log before or after, and the next
     * backup must finish and restore to the log after.
     */
    private fun killEverywhere(what: String, setUp: (Fermata, FolderBackups, BackupFolder) -> Unit, change: (Fermata) -> Unit) {
        var killAt = 1
        var finished = false
        while (!finished) {
            dir.listFiles()!!.forEach { it.deleteRecursively() }
            val f = fermata("phoneA")
            val state = InMemoryBackupStateStore()
            val plain = FileBackupFolder(dir)
            setUp(f, FolderBackups(f, state, clock = clock), plain)
            val before = f.events().map { it.id }.toSet()
            change(f)
            val after = f.events().map { it.id }.toSet()
            val killing = KillingFolder(plain).also { it.killAt = killAt }
            try {
                FolderBackups(f, state, clock = clock).backUp(killing)
                finished = true
            } catch (e: Killed) {
                // the phone died here
            }
            val got = restored(plain, "phoneA").events().map { it.id }.toSet()
            assertTrue(got == before || got == after, "$what, killed at step $killAt: restored ${got.size} events, expected ${before.size} or ${after.size}")
            // The app starts again (new objects, same saved state) and backs up.
            assertIs<BackupResult.Success>(FolderBackups(f, state, clock = clock).backUp(plain), "$what, after a kill at step $killAt")
            assertEquals(after, restored(plain, "phoneA").events().map { it.id }.toSet(), "$what, after a kill at step $killAt")
            assertTrue(names().none { it.endsWith(".tmp") }, "$what: temp files left after a kill at step $killAt: ${names()}")
            killAt++
        }
        assertTrue(killAt > 3, "$what: the backup has steps to kill ($killAt)")
    }

    @Test
    fun `a kill at any step of writing a piece leaves a backup that restores`() {
        killEverywhere("piece", { f, backups, folder ->
            f.play(a); f.play(b)
            backups.backUp(folder)
            clock.now += 2 * DAY
            f.play(a)
            backups.backUp(folder) // base + piece 1, restore point at the base
        }) { f -> f.play(b) }
    }

    @Test
    fun `a kill at any step of combining leaves a backup that restores`() {
        killEverywhere("combine", { f, backups, folder ->
            f.play(a)
            backups.backUp(folder)
            repeat(FolderBackups.MAX_PIECES.toInt()) {
                clock.now += 2 * DAY // the restore point moves each time, so old pieces are kept and later dropped
                f.play(if (it % 2 == 0) a else b)
                backups.backUp(folder)
            }
            assertEquals("minim-phoneA-g001-p0032.fermata", names().last())
        }) { f -> f.play(a) }
    }

    @Test
    fun `a kill at any step of the backup after a clear leaves a backup that restores`() {
        killEverywhere("clear", { f, backups, folder ->
            f.play(a); f.play(b)
            backups.backUp(folder)
            f.play(a)
            backups.backUp(folder)
        }) { f -> f.clearHistory() }
    }

    @Test
    fun `combining writes a new base and drops the old generation only after it is in place`() {
        val f = fermata("phoneA")
        val backups = FolderBackups(f, InMemoryBackupStateStore(), clock = clock)
        val folder = FileBackupFolder(dir)
        f.play(a)
        backups.backUp(folder)
        repeat(FolderBackups.MAX_PIECES.toInt()) {
            f.play(b)
            backups.backUp(folder)
        }
        assertEquals(33, names().size)
        f.play(a)
        val r = assertIs<BackupResult.Success>(backups.backUp(folder))
        assertEquals("minim-phoneA-g002-base.fermata", r.fileName)
        // The restore point (made today, at the first base) keeps that one file of generation 1.
        assertEquals(listOf("minim-phoneA-g001-base.fermata", "minim-phoneA-g002-base.fermata"), names())
        assertEquals(f.eventCount(), latest(backups, folder, "phoneA").eventCount)
        assertEquals(1L, backups.list(folder).single { it.role == BackupRole.PREVIOUS }.eventCount)
        // A day later the restore point moves to generation 2 and generation 1 goes.
        clock.now += 2 * DAY
        f.play(b)
        backups.backUp(folder)
        assertEquals(listOf("minim-phoneA-g002-base.fermata", "minim-phoneA-g002-p0001.fermata"), names())
    }

    // ---------------------------------------------------------------------------------------
    // Merging

    @Test
    fun `merge on open reads only the new pieces, and only headers when nothing is new`() {
        val pa = fermata("phoneA")
        val pb = fermata("phoneB")
        val ab = FolderBackups(pa, InMemoryBackupStateStore(), clock = clock)
        val bb = FolderBackups(pb, InMemoryBackupStateStore(), clock = clock)
        val folder = KillingFolder(FileBackupFolder(dir))
        repeat(5) { pa.play(a) }
        ab.backUp(folder)
        repeat(3) {
            pa.play(b)
            ab.backUp(folder)
        }
        folder.opened.clear()
        assertEquals(8, bb.mergeFromFolder(folder).eventsAdded)
        assertEquals(pa.events(), pb.events())

        pa.play(a)
        ab.backUp(folder)
        folder.opened.clear()
        val m = bb.mergeFromFolder(folder)
        assertEquals(1, m.eventsAdded)
        assertEquals(listOf("minim-phoneA-g001-p0004.fermata", "minim-phoneA-g001-p0004.fermata"), folder.opened, "header, then body, of the new piece only")

        folder.opened.clear()
        val again = bb.mergeFromFolder(folder)
        assertEquals(0, again.eventsAdded)
        assertEquals(1, again.unchanged)
        assertEquals(emptyList(), folder.opened, "nothing new: nothing read")
    }

    @Test
    fun `many small pieces merge to the same log in one go or one at a time`() {
        val pa = fermata("phoneA")
        val ab = FolderBackups(pa, InMemoryBackupStateStore(), clock = clock)
        val folder = FileBackupFolder(dir)
        val oneAtATime = fermata("phoneB")
        val ob = FolderBackups(oneAtATime, InMemoryBackupStateStore(), clock = clock)
        pa.play(a)
        ab.backUp(folder)
        repeat(30) {
            pa.play(if (it % 3 == 0) b else a)
            if (it % 5 == 0) pa.record(EventBody.Like(Subject.Track(b)))
            ab.backUp(folder)
            ob.mergeFromFolder(folder)
        }
        assertEquals(31, names().size)
        val inOneGo = fermata("phoneC")
        val m = FolderBackups(inOneGo, InMemoryBackupStateStore(), clock = clock).mergeFromFolder(folder)
        assertEquals(pa.eventCount().toInt(), m.eventsAdded)
        assertEquals(pa.events(), inOneGo.events())
        assertEquals(pa.events(), oneAtATime.events())
        assertEquals(pa.library(), inOneGo.library())
    }

    @Test
    fun `a merge follows the other phone across its combining`() {
        val pa = fermata("phoneA")
        val pb = fermata("phoneB")
        val ab = FolderBackups(pa, InMemoryBackupStateStore(), clock = clock)
        val bb = FolderBackups(pb, InMemoryBackupStateStore(), clock = clock)
        val folder = FileBackupFolder(dir)
        pa.play(a)
        ab.backUp(folder)
        repeat(FolderBackups.MAX_PIECES.toInt()) {
            pa.play(b)
            ab.backUp(folder)
            if (it == 10) bb.mergeFromFolder(folder)
        }
        pa.play(a)
        ab.backUp(folder) // combined into g002
        bb.mergeFromFolder(folder)
        assertEquals(pa.events(), pb.events())
        pa.play(b)
        ab.backUp(folder)
        assertEquals(1, bb.mergeFromFolder(folder).eventsAdded)
        assertEquals(pa.events(), pb.events())
    }

    @Test
    fun `a missing or damaged piece stops the merge there, and the next merge carries on`() {
        val pa = fermata("phoneA")
        val pb = fermata("phoneB")
        val ab = FolderBackups(pa, InMemoryBackupStateStore(), clock = clock)
        val bb = FolderBackups(pb, InMemoryBackupStateStore(), clock = clock)
        val folder = FileBackupFolder(dir)
        pa.play(a)
        ab.backUp(folder)
        repeat(3) {
            pa.play(b)
            ab.backUp(folder)
        }
        val p2 = File(dir, "minim-phoneA-g001-p0002.fermata")
        val good = p2.readBytes()

        // Half-synced: cut short.
        p2.writeBytes(good.copyOf(good.size / 2))
        val m = bb.mergeFromFolder(folder)
        assertEquals(2, m.eventsAdded, "the base and piece 1")
        assertEquals(listOf("minim-phoneA-g001-p0002.fermata"), m.problems.map { it.fileName })
        // A restore of A's latest needs every piece, so it says so and changes nothing.
        val c = fermata("phoneC")
        val cb = FolderBackups(c, InMemoryBackupStateStore(), clock = clock)
        val info = latest(cb, folder, "phoneA")
        assertEquals("minim-phoneA-g001-p0003.fermata", info.fileName)
        assertEquals(BackupError.DAMAGED, assertIs<RestoreResult.Failure>(cb.restore(folder, info)).error)
        assertEquals(0L, c.eventCount())

        // Missing (deleted, or not synced yet).
        p2.delete()
        assertEquals(0, bb.mergeFromFolder(folder).eventsAdded)
        assertEquals("minim-phoneA-g001-p0001.fermata", latest(cb, folder, "phoneA").fileName, "listed up to the gap")

        // Synced at last.
        p2.writeBytes(good)
        assertEquals(2, bb.mergeFromFolder(folder).eventsAdded)
        assertEquals(pa.events(), pb.events())
    }

    @Test
    fun `a damaged piece of its own makes the phone start a new generation`() {
        val pa = fermata("phoneA")
        val ab = FolderBackups(pa, InMemoryBackupStateStore(), clock = clock)
        val folder = FileBackupFolder(dir)
        pa.play(a)
        ab.backUp(folder)
        pa.play(b)
        ab.backUp(folder)
        File(dir, "minim-phoneA-g001-p0001.fermata").writeBytes("junk".toByteArray())
        pa.play(a)
        val r = assertIs<BackupResult.Success>(ab.backUp(folder))
        assertEquals("minim-phoneA-g002-base.fermata", r.fileName)
        assertEquals(pa.events(), restored(folder, "phoneA").events())

        // And every file gone: a new generation, never an old name.
        assertEquals(listOf("minim-phoneA-g002-base.fermata"), names())
        File(dir, "minim-phoneA-g002-base.fermata").delete()
        pa.play(b)
        assertEquals("minim-phoneA-g003-base.fermata", assertIs<BackupResult.Success>(ab.backUp(folder)).fileName)
        assertEquals(pa.events(), restored(folder, "phoneA").events())
    }

    // ---------------------------------------------------------------------------------------
    // Format 1

    @Test
    fun `a phone updating from 6_1_1 keeps its old files until its new backup has a restore point`() {
        val pa = fermata("phoneA")
        val state = InMemoryBackupStateStore()
        val folder = FileBackupFolder(dir)
        val old = Format1Backups(pa, state, clock = clock)
        pa.play(a)
        old.backUp(folder)
        clock.now += 2 * DAY
        pa.play(b)
        old.backUp(folder)
        assertEquals(listOf("minim-phoneA-latest.minim", "minim-phoneA-previous.minim"), names())

        // The update: the first backup writes a base and keeps the old files (the base is not a restore point yet).
        val lean = FolderBackups(pa, state, clock = clock)
        pa.play(a)
        clock.now += HOUR
        assertIs<BackupResult.Success>(lean.backUp(folder))
        assertEquals(listOf("minim-phoneA-g001-base.fermata", "minim-phoneA-latest.minim", "minim-phoneA-previous.minim"), names())
        // Both the old latest and the new base can be restored.
        val list = lean.list(folder)
        assertTrue(list.any { it.fileName == "minim-phoneA-latest.minim" && it.restorable })
        assertTrue(list.any { it.fileName == "minim-phoneA-g001-base.fermata" && it.restorable })

        // A day later the base becomes the restore point, and the old files go.
        clock.now += 2 * DAY
        pa.play(b)
        lean.backUp(folder)
        assertEquals(listOf("minim-phoneA-g001-base.fermata", "minim-phoneA-g001-p0001.fermata"), names())
        assertEquals(pa.events(), restored(folder, "phoneA").events())
    }

    @Test
    fun `format-1 backups still restore and merge next to format-2 ones`() {
        val old = fermata("phoneOld") // still on 6.1.1
        val new = fermata("phoneNew")
        val folder = FileBackupFolder(dir)
        repeat(3) { old.play(a) }
        old.importLegacy(EventBody.Play(b, 200_000), T0 - 400 * DAY, "legacy-1")
        Format1Backups(old, InMemoryBackupStateStore(), clock = clock).backUp(folder)
        repeat(2) { new.play(b) }
        val nb = FolderBackups(new, InMemoryBackupStateStore(), clock = clock)
        nb.backUp(folder)

        // Restore the old phone's file on a fresh phone.
        val c = fermata("phoneC")
        val cb = FolderBackups(c, InMemoryBackupStateStore(), clock = clock)
        val oldInfo = cb.list(folder).single { it.deviceId == "phoneOld" }
        assertEquals("minim-phoneOld-latest.minim", oldInfo.fileName)
        assertIs<RestoreResult.Success>(cb.restore(folder, oldInfo))
        assertEquals(old.events(), c.events())

        // Merge both kinds.
        val m = cb.mergeFromFolder(folder)
        assertEquals(2, m.eventsAdded)
        assertEquals((old.events() + new.events()).sortedWith(com.minim.fermata.log.Event.ORDER), c.events())
        // The new phone merges the old one's format-1 file too, legacy play included.
        assertEquals(4, nb.mergeFromFolder(folder).eventsAdded)
        assertEquals(c.events(), new.events())
    }

    // ---------------------------------------------------------------------------------------
    // Clear history and undo across combining

    @Test
    fun `a clear on one phone reaches the other across combining, and no file keeps the cleared plays`() {
        val pa = fermata("phoneA")
        val pb = fermata("phoneB")
        val ab = FolderBackups(pa, InMemoryBackupStateStore(), clock = clock)
        val bb = FolderBackups(pb, InMemoryBackupStateStore(), clock = clock)
        val folder = FileBackupFolder(dir)
        pa.play(a); pb.play(b)
        ab.mergeAndBackUp(folder); bb.mergeAndBackUp(folder); ab.mergeAndBackUp(folder)
        repeat(5) { pb.play(b); bb.mergeAndBackUp(folder) }
        assertEquals(pa.events().size + 5, pb.events().size)

        pa.clearHistory()
        ab.mergeAndBackUp(folder, replacePrevious = true)
        // B merges A's clear, and its next backup starts afresh too.
        bb.mergeAndBackUp(folder)
        assertTrue(pb.events().none { it.body is EventBody.Play }, "B's plays from before the clear are gone")
        for (name in names()) {
            val d = File(dir, name).inputStream().use { LogFile.read(it) }
            assertTrue(d.events.none { it.body is EventBody.Play }, "$name still holds a cleared play")
        }
        assertEquals(setOf("minim-phoneA-g002-base.fermata"), names().filter { it.startsWith("minim-phoneA") }.toSet())
        assertEquals(1, names().count { it.startsWith("minim-phoneB") })
        // Plays after the clear back up and merge as usual.
        pb.play(a)
        bb.mergeAndBackUp(folder)
        ab.mergeFromFolder(folder)
        assertEquals(1, pa.events().count { it.body is EventBody.Play })
    }

    @Test
    fun `an old like undone after combining gives the same score on the other phone and after a restore`() {
        val config = RecsConfig(coldStartMinPlays = 5, coldStartMinTracks = 3, radioMinKeep = 0)
        val pa = fermata("phoneA", config)
        val pb = fermata("phoneB", config)
        val ab = FolderBackups(pa, InMemoryBackupStateStore(), clock = clock)
        val bb = FolderBackups(pb, InMemoryBackupStateStore(), clock = clock)
        val folder = FileBackupFolder(dir)
        repeat(6) { pa.play(track("w$it", "Warm")) }
        pa.record(EventBody.Like(Subject.Track(a)))
        ab.backUp(folder)
        bb.mergeFromFolder(folder)
        // Sixty days of backups: pieces, then a combine.
        repeat(FolderBackups.MAX_PIECES.toInt() + 1) {
            clock.now += 2 * DAY
            pa.play(b)
            ab.backUp(folder)
        }
        assertTrue(names().any { it.contains("-g002-") }, names().toString())
        pa.record(EventBody.Unlike(Subject.Track(a)))
        ab.backUp(folder)
        bb.mergeFromFolder(folder)
        assertEquals(pa.events(), pb.events())
        val c = restored(folder, "phoneA")
        for (f in listOf(pa, pb)) {
            f.recs.warmUp()
            assertEquals(0.0, assertNotNull(f.recs.modelForTest()).trackScore("a", clock.now), 1e-9)
        }
        assertEquals(pa.events(), c.events())
    }

    // ---------------------------------------------------------------------------------------
    // Sizes and timings

    @Test
    fun `the backup after one new play writes a small piece, not the whole history`() {
        val pa = Fermata(InMemoryEventStore(), RealisticHistory.DEVICE, clock, ZoneOffset.UTC)
        pa.restore(RealisticHistory.generate(5_200))
        clock.now = pa.events().maxOf { it.timestamp } + DAY
        val ab = FolderBackups(pa, InMemoryBackupStateStore(), clock = clock)
        val folder = FileBackupFolder(dir)
        val base = assertIs<BackupResult.Success>(ab.backUp(folder))
        assertTrue(base.bytes < 130_000, "base ${base.bytes} bytes")
        onePlay(pa)
        val piece = assertIs<BackupResult.Success>(ab.backUpIfChanged(folder))
        assertEquals("minim-phone-g001-p0001.fermata".replace("phone", RealisticHistory.DEVICE), piece.fileName)
        assertTrue(piece.bytes < 4_000, "piece ${piece.bytes} bytes")
        assertEquals(base.bytes + piece.bytes, piece.totalBytes)
    }

    /** One new play the 6.2.0 way: the counted play, a few one-minute listening saves, the play itself. */
    private fun onePlay(f: Fermata) {
        val t = track("newsong", "Arijit Singh")
        val day = 20261113
        val w = { at: Long, ms: Long ->
            f.record(
                EventBody.Unknown(
                    "history.minim.write",
                    "{\"d\":$day,\"l\":{\"21_newsong\":$ms},\"r\":{\"newsong\":$at},\"s\":{\"newsong\":\"Kesariya\u001fArijit Singh\u001fhttps:\\/\\/i.ytimg.com\\/vi\\/newsong\\/hqdefault.jpg\u001f268000\"}}",
                ),
            )
        }
        w(clock.now + 30_000, 30_000)
        for (m in 1..4) w(clock.now + m * 60_000L, m * 60_000L)
        w(clock.now + 268_000, 268_000)
        f.record(EventBody.Play(t, 268_000, countedAt = clock.now + 30_000))
        clock.now += 5 * MINUTE
    }

    /**
     * The numbers for the release notes: sizes for 5.2K, 10K and 50K events, the listening-time
     * change, bytes and time per backup after one new play, and merge-on-open time. Only with
     * FERMATA_BENCH=1 (it takes a while).
     */
    @Test
    fun bench() {
        if (System.getenv("FERMATA_BENCH") == null) return
        for (n in listOf(5_200, 10_000, 50_000)) {
            val events = RealisticHistory.generate(n)
            val lean = RealisticHistory.generateLean(n)
            val f1 = ByteArrayOutputStream().also { BackupArchive.write(it, events, RealisticHistory.DEVICE, 1L) }.size()
            val f2 = LogFile.build(LogFileKind.BASE, RealisticHistory.DEVICE, 1, 0, events, 1L, null, null, null, null).second.size
            val f2lean = LogFile.build(LogFileKind.BASE, RealisticHistory.DEVICE, 1, 0, lean, 1L, null, null, null, null).second.size
            val saves = events.count { it.type == "history.minim.write" }
            val savesLean = lean.count { it.type == "history.minim.write" }
            println(
                "BENCH size n=$n events=${events.size} f1=$f1 (${"%.1f".format(f1.toDouble() / events.size)} B/ev) " +
                    "f2=$f2 (${"%.1f".format(f2.toDouble() / events.size)} B/ev) | lean events=${lean.size} " +
                    "f2lean=$f2lean (${"%.1f".format(f2lean.toDouble() / lean.size)} B/ev) historyWrites=$saves->$savesLean",
            )

            // A phone with this history: one base, then one new play and the backup when the app closes.
            dir.listFiles()!!.forEach { it.deleteRecursively() }
            clock.now = lean.maxOf { it.timestamp } + DAY
            val pa = Fermata(InMemoryEventStore(), RealisticHistory.DEVICE, clock, ZoneOffset.UTC)
            pa.restore(lean)
            val folder = FileBackupFolder(dir)
            val ab = FolderBackups(pa, InMemoryBackupStateStore(), clock = clock)
            var t0 = System.nanoTime()
            val base = assertIs<BackupResult.Success>(ab.backUp(folder))
            val baseMs = (System.nanoTime() - t0) / 1e6
            val pieceTimes = ArrayList<Double>()
            val pieceBytes = ArrayList<Long>()
            repeat(5) {
                clock.now += HOUR
                onePlay(pa)
                t0 = System.nanoTime()
                val r = assertIs<BackupResult.Success>(ab.backUpIfChanged(folder))
                pieceTimes += (System.nanoTime() - t0) / 1e6
                pieceBytes += r.bytes
            }
            t0 = System.nanoTime()
            val unchanged = assertIs<BackupResult.Success>(ab.backUpIfChanged(folder))
            val unchangedMs = (System.nanoTime() - t0) / 1e6
            assertFalse(unchanged.written)

            // The same with 6.1.1's format-1 writer (the whole history each time).
            val f1dir = Files.createTempDirectory("f1").toFile()
            val pOld = Fermata(InMemoryEventStore(), RealisticHistory.DEVICE, clock, ZoneOffset.UTC)
            pOld.restore(lean)
            val ob = Format1Backups(pOld, InMemoryBackupStateStore(), clock = clock)
            ob.backUp(FileBackupFolder(f1dir))
            onePlay(pOld)
            t0 = System.nanoTime()
            val old = assertIs<BackupResult.Success>(ob.backUpIfChanged(FileBackupFolder(f1dir)))
            val oldMs = (System.nanoTime() - t0) / 1e6
            f1dir.deleteRecursively()

            // Merge on open on a second phone: first everything, then one new piece, then nothing new.
            val pb = fermata("phoneB")
            val bb = FolderBackups(pb, InMemoryBackupStateStore(), clock = clock)
            t0 = System.nanoTime()
            bb.mergeFromFolder(folder)
            val firstMergeMs = (System.nanoTime() - t0) / 1e6
            onePlay(pa)
            ab.backUpIfChanged(folder)
            t0 = System.nanoTime()
            val m = bb.mergeFromFolder(folder)
            val pieceMergeMs = (System.nanoTime() - t0) / 1e6
            assertTrue(m.eventsAdded > 0)
            t0 = System.nanoTime()
            bb.mergeFromFolder(folder)
            val noneMergeMs = (System.nanoTime() - t0) / 1e6
            println(
                "BENCH backup n=$n base=${base.bytes}B in ${"%.0f".format(baseMs)}ms | piece after one play=${pieceBytes} B, " +
                    "times=${pieceTimes.map { "%.1f".format(it) }} ms | unchanged=${"%.1f".format(unchangedMs)}ms | " +
                    "format1 after one play=${old.bytes}B in ${"%.0f".format(oldMs)}ms | merge first=${"%.0f".format(firstMergeMs)}ms " +
                    "onePiece=${"%.1f".format(pieceMergeMs)}ms nothingNew=${"%.1f".format(noneMergeMs)}ms",
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Event exchange (for Wi-Fi sync in 6.3)

    @Test
    fun `two phones exchange exactly what each lacks`() {
        val pa = fermata("phoneA")
        val pb = fermata("phoneB")
        repeat(3) { pa.play(a) }
        pa.importLegacy(EventBody.Play(b, 200_000), T0 - 400 * DAY, "legacy-1")
        repeat(2) { pb.play(b) }
        val xa = EventExchange(pa)
        val xb = EventExchange(pb)

        val sa = SyncState.decode(xa.state().encode())
        val sb = SyncState.decode(xb.state().encode())
        assertEquals(xa.state(), sa)
        assertEquals(mapOf("phoneA" to 3L), sa.heads)
        assertEquals(1L, sa.legacyCount)
        val toB = xa.unpack(xa.pack(xa.missingFor(sb)))
        val toA = xb.unpack(xb.pack(xb.missingFor(sa)))
        assertEquals(4, toB.size, "three plays and the legacy one")
        assertEquals(2, toA.size)
        assertEquals(4, xb.receive(toB))
        assertEquals(2, xa.receive(toA))
        assertEquals(pa.events(), pb.events())

        // In step: nothing to send either way, and a repeat adds nothing.
        assertEquals(emptyList(), xa.missingFor(xb.state()))
        assertEquals(emptyList(), xb.missingFor(xa.state()))
        assertEquals(0, xb.receive(toB))

        // One new play: only that goes.
        pa.play(b)
        assertEquals(1, xa.missingFor(xb.state()).size)
    }

    @Test
    fun `a sync state from something else is refused`() {
        val e = runCatching { SyncState.decode("hello".toByteArray()) }.exceptionOrNull()
        assertIs<IllegalArgumentException>(e)
        // Unknown lines from a newer version are skipped.
        val s = SyncState.decode("fermata-sync 1\ndevice x\nlegacy 0 abc\nhead y 4\nshiny new\n".toByteArray())
        assertEquals(SyncState("x", mapOf("y" to 4L), 0, "abc"), s)
        assertNull(BackupNames.parseChain("minim-x-latest.minim"))
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
