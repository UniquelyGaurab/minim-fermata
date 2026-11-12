package com.minim.fermata.sync

import com.minim.fermata.Fermata
import com.minim.fermata.backup.BackupArchive
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
import com.minim.fermata.log.TrackRef
import com.minim.fermata.log.track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class AutoClock(var now: Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(now)
}

/** Counts what a run did to the folder, and can make steps fail. */
private class CountingFolder(private val inner: BackupFolder) : BackupFolder by inner {
    val opens = HashMap<String, Int>()
    var creates = 0
    var renames = 0
    var failCreate = false
    var failRenameTo: String? = null
    /** Fail only the next rename to [failRenameTo]. */
    var failOnce = false

    /** Calls running right now, from any thread, and the most seen at once. */
    private val active = AtomicInteger()
    val mostAtOnce = AtomicInteger()

    private inline fun <T> step(block: () -> T): T {
        val n = active.incrementAndGet()
        mostAtOnce.accumulateAndGet(n) { a, b -> maxOf(a, b) }
        try {
            Thread.sleep(1)
            return block()
        } finally {
            active.decrementAndGet()
        }
    }

    fun reset() {
        opens.clear(); creates = 0; renames = 0
    }

    override fun list(): List<FolderEntry> = step { inner.list() }

    override fun openInput(entry: FolderEntry): InputStream = step {
        synchronized(opens) { opens.merge(entry.name, 1, Int::plus) }
        inner.openInput(entry)
    }

    override fun create(name: String): FolderEntry = step {
        if (failCreate) throw IOException("no space left")
        creates++
        inner.create(name)
    }

    override fun openOutput(entry: FolderEntry): OutputStream = step { inner.openOutput(entry) }

    override fun rename(entry: FolderEntry, newName: String): FolderEntry = step {
        if (newName == failRenameTo) {
            if (failOnce) failRenameTo = null
            throw IOException("rename not allowed")
        }
        renames++
        inner.rename(entry, newName)
    }

    override fun delete(entry: FolderEntry) = step { inner.delete(entry) }
}

/** backUpIfChanged, the daily `previous`, the manifest-first merge and the backup lock. */
class AutoBackupTest {
    @TempDir
    lateinit var dir: File

    private val clock = AutoClock(T0)
    private val a = track("A", "Alpha")
    private val b = track("B", "Beta")
    private val c = track("C", "Gamma")

    private fun fermata(device: String, store: EventStore = InMemoryEventStore()) =
        Fermata(store, device, clock, ZoneOffset.UTC)

    private fun names() = dir.list()!!.sorted()

    private fun read(name: String) = File(dir, name).inputStream().use { BackupArchive.read(it) }

    private fun Fermata.play(vararg tracks: TrackRef) {
        for (t in tracks) {
            record(EventBody.Play(t, 120_000), Source.RADIO)
            clock.now += 3 * MINUTE
        }
    }

    private fun legacyLike(key: String, t: TrackRef) =
        Event(EventId.legacy(key), EventContext.at(T0 - HOUR, ZoneOffset.UTC, null, null, Source.IMPORT), EventBody.Like(Subject.Track(t)))

    // --- 1. backUpIfChanged ---

    @Test
    fun `backUpIfChanged writes the first time, then skips while nothing changed`() {
        val f = fermata("phoneA")
        f.play(a, b)
        val folder = CountingFolder(FileBackupFolder(dir))
        val backups = FolderBackups(f, InMemoryBackupStateStore(), clock = clock)

        val first = assertIs<BackupResult.Success>(backups.backUpIfChanged(folder))
        assertTrue(first.written)
        assertEquals(listOf("minim-phoneA-latest.minim"), names())
        val bytes = File(dir, "minim-phoneA-latest.minim").readBytes()

        repeat(5) {
            clock.now += 5 * MINUTE
            folder.reset()
            val again = assertIs<BackupResult.Success>(backups.backUpIfChanged(folder))
            assertFalse(again.written)
            assertEquals(2L, again.eventCount)
            assertEquals("minim-phoneA-latest.minim", again.fileName)
            assertEquals(bytes.size.toLong(), again.bytes)
            assertEquals(0, folder.creates, "nothing written")
            assertEquals(0, folder.renames)
            assertEquals(mapOf("minim-phoneA-latest.minim" to 1), folder.opens, "only the manifest is read")
        }
        assertTrue(bytes.contentEquals(File(dir, "minim-phoneA-latest.minim").readBytes()))
        assertEquals(listOf("minim-phoneA-latest.minim"), names())
        val status = backups.status()
        assertTrue(status.ok)
        assertEquals(clock.now, status.lastSuccessAt, "a current latest counts as a backup that worked")
    }

    @Test
    fun `backUpIfChanged writes when the log changed in any way`() {
        val f = fermata("phoneA")
        f.play(a)
        val backups = FolderBackups(f, InMemoryBackupStateStore(), clock = clock)
        val folder = FileBackupFolder(dir)
        backups.backUp(folder)

        fun writes(what: String) {
            clock.now += MINUTE
            val r = assertIs<BackupResult.Success>(backups.backUpIfChanged(folder), what)
            assertTrue(r.written, what)
            assertEquals(f.eventCount(), read("minim-phoneA-latest.minim").manifest.eventCount, what)
            assertFalse(assertIs<BackupResult.Success>(backups.backUpIfChanged(folder)).written, "$what, then nothing")
        }

        f.record(EventBody.Like(Subject.Track(a)))
        writes("a new like")

        val other = fermata("phoneB")
        other.play(b)
        f.merge(other.events())
        writes("events merged from another phone")

        f.importEvents(listOf(legacyLike("likes:C", c)))
        writes("a legacy event, which the version vector cannot show")

        clock.now += HOUR
        f.clearHistory()
        writes("a history clear")
    }

    @Test
    fun `backUpIfChanged writes when latest is missing, damaged or another format`() {
        val f = fermata("phoneA")
        f.play(a)
        val backups = FolderBackups(f, InMemoryBackupStateStore(), clock = clock)
        val folder = FileBackupFolder(dir)
        backups.backUp(folder)
        val latest = File(dir, "minim-phoneA-latest.minim")

        latest.delete()
        assertTrue(assertIs<BackupResult.Success>(backups.backUpIfChanged(folder)).written, "missing")

        latest.writeBytes("not a zip".toByteArray())
        assertTrue(assertIs<BackupResult.Success>(backups.backUpIfChanged(folder)).written, "damaged")
        assertEquals(1L, read(latest.name).manifest.eventCount)

        rewriteManifest(latest) { it + ("format" to Json.parseToJsonElement("99")) }
        assertTrue(assertIs<BackupResult.Success>(backups.backUpIfChanged(folder)).written, "another format")
        assertEquals(BackupArchive.FORMAT, read(latest.name).manifest.format)

        assertEquals(BackupError.NO_FOLDER, assertIs<BackupResult.Failure>(backups.backUpIfChanged(null)).error)
    }

    @Test
    fun `backUpIfChanged skips after a clear that deleted another phone's newest plays`() {
        // The store keeps the deleted counters (so they are never reused), but the manifest only
        // has the events that are left: the check compares the events that are left.
        val other = fermata("phoneB")
        other.record(EventBody.Like(Subject.Track(a)))
        other.play(b, c)
        val f = fermata("phoneA")
        f.merge(other.events())
        clock.now += HOUR
        f.clearHistory()
        assertTrue((f.heads()["phoneB"] ?: 0) > (f.loggedHeads()["phoneB"] ?: 0))
        val backups = FolderBackups(f, InMemoryBackupStateStore(), clock = clock)
        val folder = FileBackupFolder(dir)
        assertTrue(assertIs<BackupResult.Success>(backups.backUpIfChanged(folder)).written)
        assertFalse(assertIs<BackupResult.Success>(backups.backUpIfChanged(folder)).written)
    }

    @Test
    fun `a backup from before the legacy fields is still recognised as current`() {
        val f = fermata("phoneA")
        f.play(a)
        val backups = FolderBackups(f, InMemoryBackupStateStore(), clock = clock)
        backups.backUp(FileBackupFolder(dir))
        rewriteManifest(File(dir, "minim-phoneA-latest.minim")) { it - "legacyCount" - "legacyDigest" }
        assertFalse(assertIs<BackupResult.Success>(backups.backUpIfChanged(FileBackupFolder(dir))).written)
    }

    // --- 2. previous moves on at most once a day ---

    @Test
    fun `latest moves on with every backup, previous at most once a day`() {
        val f = fermata("phoneA")
        val state = InMemoryBackupStateStore()
        val backups = FolderBackups(f, state, clock = clock)
        val folder = FileBackupFolder(dir)
        f.play(a)
        backups.backUp(folder) // latest only
        f.play(b)
        backups.backUp(folder) // no previous yet: latest becomes previous
        val rotatedAt = clock.now
        assertEquals(1L, read("minim-phoneA-previous.minim").manifest.eventCount)

        // Back up every 5 minutes for most of a day, with a new play each time.
        var expectedLatest = 2L
        while (clock.now < rotatedAt + FolderBackups.PREVIOUS_EVERY_MS - 10 * MINUTE) {
            clock.now += 2 * MINUTE // play() adds 3 more
            f.play(c)
            expectedLatest++
            assertTrue(assertIs<BackupResult.Success>(backups.backUpIfChanged(folder)).written)
            assertEquals(expectedLatest, read("minim-phoneA-latest.minim").manifest.eventCount)
            assertEquals(1L, read("minim-phoneA-previous.minim").manifest.eventCount, "previous stays for the day")
            assertEquals(listOf("minim-phoneA-latest.minim", "minim-phoneA-previous.minim"), names(), "no parked files left")
        }
        assertTrue(expectedLatest > 250)

        // A day after it last moved on, previous takes the latest it had.
        clock.now = rotatedAt + FolderBackups.PREVIOUS_EVERY_MS
        f.play(a)
        backups.backUp(folder)
        assertEquals(expectedLatest, read("minim-phoneA-previous.minim").manifest.eventCount)
        assertEquals(expectedLatest + 1, read("minim-phoneA-latest.minim").manifest.eventCount)
        assertEquals(clock.now, state.load().previousRotatedAt)
    }

    @Test
    fun `a clock that jumps costs at most one extra move of previous`() {
        val f = fermata("phoneA")
        val state = InMemoryBackupStateStore()
        val backups = FolderBackups(f, state, clock = clock)
        val folder = FileBackupFolder(dir)
        f.play(a); backups.backUp(folder)
        f.play(a); backups.backUp(folder) // previous = 1 event, moved on now
        fun previousCount() = read("minim-phoneA-previous.minim").manifest.eventCount
        fun backUpNew() { f.play(b); clock.now -= 3 * MINUTE; backups.backUp(folder) }

        // Set back a little (a time sync): previous stays.
        clock.now -= 10 * MINUTE
        backUpNew()
        assertEquals(1L, previousCount())

        // Set back three days: previous moves on once, then the day counts from the new time.
        clock.now -= 3 * FolderBackups.PREVIOUS_EVERY_MS
        backUpNew()
        val afterJump = previousCount()
        assertEquals(3L, afterJump)
        repeat(5) {
            clock.now += 30 * MINUTE
            backUpNew()
            assertEquals(afterJump, previousCount(), "no move on every backup after the clock went back")
        }

        // Jumps a year ahead: one move. Back to the right time: one more, then quiet again.
        val right = clock.now
        clock.now += 365 * FolderBackups.PREVIOUS_EVERY_MS
        backUpNew()
        val ahead = previousCount()
        assertTrue(ahead > afterJump)
        clock.now = right + MINUTE
        backUpNew()
        val back = previousCount()
        assertTrue(back > ahead)
        repeat(3) {
            clock.now += HOUR
            backUpNew()
            assertEquals(back, previousCount())
        }
    }

    @Test
    fun `replacing latest in place puts the old one back when the new one cannot move in`() {
        val f = fermata("phoneA")
        val folder = CountingFolder(FileBackupFolder(dir))
        val backups = FolderBackups(f, InMemoryBackupStateStore(), clock = clock)
        f.play(a); backups.backUp(folder)
        f.play(b); backups.backUp(folder) // latest 2, previous 1
        f.play(c)
        folder.failRenameTo = "minim-phoneA-latest.minim"
        folder.failOnce = true
        assertEquals(BackupError.RENAME_FAILED, assertIs<BackupResult.Failure>(backups.backUp(folder)).error)
        assertEquals(listOf("minim-phoneA-latest.minim", "minim-phoneA-previous.minim"), names())
        assertEquals(2L, read("minim-phoneA-latest.minim").manifest.eventCount)
        assertEquals(1L, read("minim-phoneA-previous.minim").manifest.eventCount)

        // If even the way back fails, the old latest waits under its temp name (which no reader
        // takes) and the next run puts it back before anything else.
        folder.failRenameTo = "minim-phoneA-latest.minim"
        folder.failOnce = false
        assertEquals(BackupError.RENAME_FAILED, assertIs<BackupResult.Failure>(backups.backUp(folder)).error)
        assertEquals(1, names().count { it.endsWith("-old.tmp") })
        folder.failRenameTo = null
        assertTrue(assertIs<BackupResult.Success>(backups.backUpIfChanged(folder)).written)
        assertEquals(listOf("minim-phoneA-latest.minim", "minim-phoneA-previous.minim"), names())
        assertEquals(3L, read("minim-phoneA-latest.minim").manifest.eventCount)
        assertEquals(1L, read("minim-phoneA-previous.minim").manifest.eventCount)
    }

    @Test
    fun `a run killed while latest was parked gets it back on the next run`() {
        val f = fermata("phoneA")
        val folder = CountingFolder(FileBackupFolder(dir))
        val backups = FolderBackups(f, InMemoryBackupStateStore(), clock = clock)
        f.play(a); backups.backUp(folder)
        f.play(b); backups.backUp(folder)
        // As a kill would leave it: latest parked, the new temp half written, no latest.
        File(dir, "minim-phoneA-latest.minim").renameTo(File(dir, "minim-phoneA-${clock.now}-abcd-old.tmp"))
        File(dir, "minim-phoneA-${clock.now}-ef01.tmp").writeText("half")

        f.play(c)
        folder.failCreate = true // and this run cannot write either
        assertIs<BackupResult.Failure>(backups.backUp(folder))
        assertEquals(listOf("minim-phoneA-latest.minim", "minim-phoneA-previous.minim"), names())
        assertEquals(2L, read("minim-phoneA-latest.minim").manifest.eventCount, "the parked latest is back")

        folder.failCreate = false
        assertTrue(assertIs<BackupResult.Success>(backups.backUp(folder)).written)
        assertEquals(listOf("minim-phoneA-latest.minim", "minim-phoneA-previous.minim"), names())
        assertEquals(3L, read("minim-phoneA-latest.minim").manifest.eventCount)
    }

    // --- 3. the manifest-first merge ---

    @Test
    fun `a merge reads a backup in full only when its manifest shows something new`() {
        val folder = CountingFolder(FileBackupFolder(dir))
        val phoneB = fermata("phoneB")
        val bBackups = FolderBackups(phoneB, InMemoryBackupStateStore(), clock = clock)
        phoneB.play(a)
        bBackups.backUp(folder)

        val phoneA = fermata("phoneA")
        phoneA.play(b)
        val aBackups = FolderBackups(phoneA, InMemoryBackupStateStore(), clock = clock)
        aBackups.backUp(folder)

        // First merge: read in full.
        folder.reset()
        val first = aBackups.mergeFromFolder(folder)
        assertEquals(1, first.filesMerged)
        assertEquals(1, first.eventsAdded)
        assertEquals(2, folder.opens["minim-phoneB-latest.minim"], "manifest, then the whole file")

        // B merges A in and backs up: its file changed, but holds nothing A lacks.
        bBackups.mergeFromFolder(folder)
        assertTrue(assertIs<BackupResult.Success>(bBackups.backUpIfChanged(folder)).written)
        folder.reset()
        val second = aBackups.mergeFromFolder(folder)
        assertEquals(0, second.filesMerged)
        assertEquals(1, second.unchanged)
        assertEquals(0, second.eventsAdded)
        assertEquals(1, folder.opens["minim-phoneB-latest.minim"], "the manifest alone")

        // Same file again: the checksum alone.
        folder.reset()
        assertEquals(1, aBackups.mergeFromFolder(folder).unchanged)
        assertEquals(1, folder.opens["minim-phoneB-latest.minim"])

        // B likes something: read in full again.
        phoneB.record(EventBody.Like(Subject.Track(c)))
        bBackups.backUpIfChanged(folder)
        folder.reset()
        val third = aBackups.mergeFromFolder(folder)
        assertEquals(1, third.filesMerged)
        assertEquals(1, third.eventsAdded)
        assertEquals(2, folder.opens["minim-phoneB-latest.minim"])
        assertTrue(phoneA.library().isLiked("C"))
    }

    @Test
    fun `legacy events, which have no counters, make a merge read in full until it has them`() {
        val folder = CountingFolder(FileBackupFolder(dir))
        val phoneB = fermata("phoneB")
        val bBackups = FolderBackups(phoneB, InMemoryBackupStateStore(), clock = clock)
        phoneB.importEvents(listOf(legacyLike("likes:A", a)))
        phoneB.play(b)
        bBackups.backUp(folder)
        val manifest = read("minim-phoneB-latest.minim").manifest
        assertEquals(1L, manifest.legacyCount)
        assertNotNull(manifest.legacyDigest)

        val phoneA = fermata("phoneA")
        val aBackups = FolderBackups(phoneA, InMemoryBackupStateStore(), clock = clock)
        phoneA.play(c)
        // A already has B's counters (B's events came by another way) but not the legacy like.
        phoneA.merge(phoneB.events().filter { it.id.device != EventId.LEGACY_DEVICE })
        aBackups.backUp(folder)
        folder.reset()
        val r = aBackups.mergeFromFolder(folder)
        assertEquals(1, r.filesMerged, "the legacy like is new, though the version vector is not")
        assertEquals(1, r.eventsAdded)
        assertTrue(phoneA.library().isLiked("A"))

        // B takes A's events in and backs up: same legacy set, nothing new: the manifest alone.
        bBackups.mergeFromFolder(folder)
        bBackups.backUpIfChanged(folder)
        folder.reset()
        assertEquals(1, aBackups.mergeFromFolder(folder).unchanged)
        assertEquals(1, folder.opens["minim-phoneB-latest.minim"])

        // B imports one more legacy event: full read.
        phoneB.importEvents(listOf(legacyLike("likes:B", b)))
        bBackups.backUpIfChanged(folder)
        folder.reset()
        val again = aBackups.mergeFromFolder(folder)
        assertEquals(1, again.eventsAdded)
        assertEquals(2, folder.opens["minim-phoneB-latest.minim"])
    }

    @Test
    fun `an older manifest without the legacy fields is read in full`() {
        val folder = CountingFolder(FileBackupFolder(dir))
        val phoneB = fermata("phoneB")
        phoneB.play(a)
        FolderBackups(phoneB, InMemoryBackupStateStore(), clock = clock).backUp(folder)
        rewriteManifest(File(dir, "minim-phoneB-latest.minim")) { it - "legacyCount" - "legacyDigest" }
        val phoneA = fermata("phoneA")
        phoneA.merge(phoneB.events()) // A has everything already
        folder.reset()
        val r = FolderBackups(phoneA, InMemoryBackupStateStore(), clock = clock).mergeFromFolder(folder)
        assertEquals(1, r.filesMerged)
        assertEquals(0, r.eventsAdded)
        assertEquals(2, folder.opens["minim-phoneB-latest.minim"])
    }

    @Test
    fun `a damaged or missing manifest falls back to the full read and then to previous`() {
        val folder = FileBackupFolder(dir)
        val phoneB = fermata("phoneB")
        val bBackups = FolderBackups(phoneB, InMemoryBackupStateStore(), clock = clock)
        phoneB.play(a); bBackups.backUp(folder)
        phoneB.play(b); bBackups.backUp(folder) // latest 2, previous 1
        val latest = File(dir, "minim-phoneB-latest.minim")

        // The manifest entry is garbage.
        rewriteZip(latest) { name, bytes -> if (name == BackupArchive.MANIFEST) "{not json".toByteArray() else bytes }
        val phoneA = fermata("phoneA")
        val aBackups = FolderBackups(phoneA, InMemoryBackupStateStore(), clock = clock)
        val r = aBackups.mergeFromFolder(folder)
        assertEquals(1, r.problems.size, "the damaged latest is reported")
        assertEquals(1, r.filesMerged, "previous was merged instead")
        assertEquals(1, r.eventsAdded)

        // No manifest entry at all.
        rewriteZip(latest) { name, bytes -> if (name == BackupArchive.MANIFEST) null else bytes }
        val phoneC = fermata("phoneC")
        val r2 = FolderBackups(phoneC, InMemoryBackupStateStore(), clock = clock).mergeFromFolder(folder)
        assertEquals(1, r2.problems.size)
        assertEquals(1, r2.eventsAdded)

        // B backs up again: a good latest is merged in full.
        phoneB.play(c); bBackups.backUp(folder)
        assertEquals(2, aBackups.mergeFromFolder(folder).eventsAdded)
        assertFalse(aBackups.status().mergeTroubled)
    }

    // --- 4. one backup at a time ---

    @Test
    fun `backups, merges and restores never overlap, across instances and threads`() {
        val folder = CountingFolder(FileBackupFolder(dir))
        val phoneB = fermata("phoneB")
        phoneB.play(a, b)
        FolderBackups(phoneB, InMemoryBackupStateStore(), clock = clock).backUp(folder)
        val phoneA = fermata("phoneA")
        phoneA.play(c)
        val state = InMemoryBackupStateStore()
        // Separate instances, as the on-close job, the daily job and Back up now each make one.
        val onClose = FolderBackups(phoneA, state, clock = clock)
        val daily = FolderBackups(phoneA, state, clock = clock)
        val now = FolderBackups(phoneA, state, clock = clock)
        val pool = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        val results = (0 until 18).map { i ->
            pool.submit<Any> {
                start.await()
                if (i % 6 == 5) phoneA.record(EventBody.Like(Subject.Track(a)))
                when (i % 3) {
                    0 -> onClose.backUpIfChanged(folder)
                    1 -> daily.mergeAndBackUp(folder, onlyIfChanged = true).second
                    else -> now.backUp(folder)
                }
            }
        }
        start.countDown()
        results.forEach { assertIs<BackupResult.Success>(it.get(60, TimeUnit.SECONDS)) }
        pool.shutdown()
        assertEquals(1, folder.mostAtOnce.get(), "one folder call at a time")
        assertEquals(listOf("minim-phoneA-latest.minim", "minim-phoneA-previous.minim", "minim-phoneB-latest.minim"), names())
        assertEquals(phoneA.eventCount(), read("minim-phoneA-latest.minim").manifest.eventCount)
    }

    // --- helpers ---

    private fun rewriteManifest(file: File, change: (JsonObject) -> Map<String, kotlinx.serialization.json.JsonElement>) =
        rewriteZip(file) { name, bytes ->
            if (name != BackupArchive.MANIFEST) bytes
            else JsonObject(change(Json.parseToJsonElement(bytes.decodeToString()).jsonObject)).toString().toByteArray()
        }

    private fun rewriteZip(file: File, change: (String, ByteArray) -> ByteArray?) {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(file.inputStream()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                entries[e.name] = zin.readBytes()
            }
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zout ->
            for ((name, bytes) in entries) {
                val changed = change(name, bytes) ?: continue
                zout.putNextEntry(ZipEntry(name))
                zout.write(changed)
                zout.closeEntry()
            }
        }
        file.writeBytes(out.toByteArray())
    }
}
