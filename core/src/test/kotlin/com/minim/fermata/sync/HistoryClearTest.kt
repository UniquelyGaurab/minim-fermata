package com.minim.fermata.sync

import com.minim.fermata.Fermata
import com.minim.fermata.backup.BackupArchive
import com.minim.fermata.backup.EventCodec
import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventContext
import com.minim.fermata.log.EventId
import com.minim.fermata.log.EventLog
import com.minim.fermata.log.HOUR
import com.minim.fermata.log.InMemoryEventStore
import com.minim.fermata.log.MINUTE
import com.minim.fermata.log.Source
import com.minim.fermata.log.Subject
import com.minim.fermata.log.T0
import com.minim.fermata.log.track
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class StepClock(var now: Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(now)
}

/** "Clear listening history" as an event every phone respects. */
class HistoryClearTest {
    @TempDir
    lateinit var dir: File

    private val clock = StepClock(T0)
    private val a = track("A", "Alpha")
    private val b = track("B", "Beta")
    private val c = track("C", "Gamma")

    private fun fermata(device: String) = Fermata(InMemoryEventStore(), device, clock, ZoneOffset.UTC)

    private fun Fermata.play(t: com.minim.fermata.log.TrackRef) {
        record(EventBody.Play(t, 120_000, countedAt = clock.now + 30_000), Source.RADIO)
        clock.now += 3 * MINUTE
    }

    /** An app-defined history record (Minim's hourly listening totals). */
    private fun appHistory(): EventBody =
        EventBody.Unknown("history.minim.listen", """{"ms":1000}""")

    private fun historyIds(f: Fermata) = f.library().history.map { it.track.id }

    @Test
    fun `clearing drops plays, skips, queues, tape plays and app history but keeps the library`() {
        val f = fermata("phoneA")
        f.record(EventBody.Like(Subject.Track(a)))
        f.record(EventBody.PlaylistCreate("p1", "Mix"))
        f.record(EventBody.PlaylistAdd("p1", b, null))
        f.play(a); f.play(b)
        f.record(EventBody.Skip(b, 10_000))
        f.record(EventBody.Queued(c))
        f.record(EventBody.TapePlay(c, 90_000, countedAt = clock.now))
        f.record(appHistory())
        clock.now += MINUTE

        val clear = f.clearHistory()
        assertIs<EventBody.HistoryClear>(clear.body)

        val lib = f.library()
        assertTrue(lib.history.isEmpty())
        assertTrue(lib.tapeHistory.isEmpty())
        assertTrue(lib.playCounts.isEmpty())
        assertTrue(lib.isLiked("A"))
        assertEquals(listOf("B"), lib.playlist("p1")!!.tracks.map { it.id })
        // The covered events are gone from the store itself, not only hidden.
        val types = f.events().map { it.body.type }.toSet()
        assertEquals(setOf(EventBody.LIKE, EventBody.PLAYLIST_CREATE, EventBody.PLAYLIST_ADD, EventBody.HISTORY_CLEAR), types)

        // New listening after the clear counts as usual.
        clock.now += MINUTE
        f.play(c)
        assertEquals(listOf("C"), historyIds(f))
    }

    @Test
    fun `another phone's old history never comes back after a clear, whichever way it arrives`() {
        val phoneA = fermata("phoneA")
        val phoneB = fermata("phoneB")
        phoneA.play(a)
        phoneB.play(b)
        phoneA.merge(phoneB.events())
        phoneB.merge(phoneA.events())
        clock.now += HOUR

        phoneA.clearHistory()
        assertTrue(phoneA.library().history.isEmpty())

        // A merges B's (still full) log: B's old play stays out.
        assertEquals(0, phoneA.merge(phoneB.events()))
        assertTrue(phoneA.library().history.isEmpty())

        // B merges A's log with the clear: B drops the same history, its own plays included.
        clock.now += MINUTE
        phoneB.merge(phoneA.events())
        assertTrue(phoneB.library().history.isEmpty())
        assertTrue(phoneB.events().none { EventBody.isHistory(it.body.type) })

        // Plays after the clear go both ways as usual.
        clock.now += MINUTE
        phoneB.play(c)
        phoneA.merge(phoneB.events())
        assertEquals(listOf("C"), historyIds(phoneA))
        assertEquals(EventLog.effective(phoneA.events()), EventLog.effective(phoneB.events() + phoneA.events()))
    }

    @Test
    fun `merge order does not matter`() {
        val phoneA = fermata("phoneA")
        val phoneB = fermata("phoneB")
        phoneA.play(a); phoneB.play(b)
        phoneA.record(EventBody.Like(Subject.Track(a)))
        clock.now += HOUR
        phoneA.clearHistory()
        clock.now += MINUTE
        phoneB.play(c)

        val one = fermata("x").apply { merge(phoneA.events()); merge(phoneB.events()) }
        val two = fermata("y").apply { merge(phoneB.events()); merge(phoneA.events()) }
        assertEquals(one.events(), two.events())
        assertEquals(listOf("C"), historyIds(one))
        assertEquals(one.library().copy(eventCount = 0), two.library().copy(eventCount = 0))
        assertTrue(one.library().isLiked("A"))
    }

    @Test
    fun `restoring a backup from before the clear brings back likes but not the cleared history`() {
        val f = fermata("phoneA")
        f.play(a); f.play(b)
        f.record(EventBody.Like(Subject.Track(b)))
        val oldBackup = f.events()
        clock.now += HOUR
        f.clearHistory()
        f.record(EventBody.Unlike(Subject.Track(b)))

        val stats = f.restore(oldBackup)
        assertEquals(0, stats.added)
        assertTrue(f.library().history.isEmpty())
        assertTrue(!f.library().isLiked("B"))

        // A fresh phone restoring A's old file and A's newer file, in either order.
        val newBackup = f.events()
        val fresh = fermata("phoneC")
        fresh.restore(oldBackup)
        assertEquals(listOf("B", "A"), historyIds(fresh))
        fresh.restore(newBackup)
        assertTrue(fresh.library().history.isEmpty())
        assertTrue(fresh.events().none { EventBody.isHistory(it.body.type) })
    }

    @Test
    fun `an import of old rows after a clear does not bring them back`() {
        val f = fermata("phoneA")
        clock.now += HOUR
        f.clearHistory()
        assertNull(f.importLegacy(EventBody.Play(a, 60_000), T0, "plays:1"))
        // Library rows from the same import still land.
        f.importLegacy(EventBody.Like(Subject.Track(a)), T0, "likes:A")
        assertTrue(f.library().isLiked("A"))
        assertTrue(f.library().history.isEmpty())
    }

    @Test
    fun `the clear point is read back from the store on a fresh start`() {
        val store = InMemoryEventStore()
        val f = Fermata(store, "phoneA", clock, ZoneOffset.UTC)
        f.play(a)
        clock.now += HOUR
        val clear = f.clearHistory()
        val restarted = Fermata(store, "phoneA", clock, ZoneOffset.UTC)
        assertEquals((clear.body as EventBody.HistoryClear).before, restarted.clearedBefore())
        assertNull(restarted.importLegacy(EventBody.Play(a, 60_000), T0, "plays:9"))
        // The phone's counter does not move back although its old plays were deleted.
        val next = restarted.record(EventBody.Like(Subject.Track(a)))
        assertTrue(next.id.counter > clear.id.counter)
    }

    @Test
    fun `the clear and the new play fields survive JSON`() {
        val events = listOf(
            Event(EventId("p", 1), EventContext.at(T0, ZoneOffset.UTC, null, null, Source.UNKNOWN), EventBody.HistoryClear(T0 - 5)),
            Event(
                EventId("p", 2), EventContext.at(T0, ZoneOffset.UTC, "s", null, Source.RADIO),
                EventBody.Play(a.copy(sourceUrl = "https://example.com/a.mp3"), 50_000, countedAt = T0 + 30_000, syncId = "abc_A", estimated = true),
            ),
            Event(
                EventId("p", 3), EventContext.at(T0, ZoneOffset.UTC, "s", null, Source.TAPE),
                EventBody.TapePlay(b, 40_000, 125, countedAt = T0 + 31_000, channelId = "UC1"),
            ),
            Event(EventId("p", 4), EventContext.at(T0, ZoneOffset.UTC, null, null, Source.UNKNOWN), EventBody.Play(c, 1_000)),
        )
        for (e in events) assertEquals(e, EventCodec.decode(EventCodec.encode(e)))
    }

    @Test
    fun `backing up right after a clear also replaces this phone's previous file`() {
        val f = fermata("phoneA")
        val backups = FolderBackups(f, InMemoryBackupStateStore(), clock = clock)
        val folder = FileBackupFolder(dir)
        f.play(a); f.play(b)
        assertIs<BackupResult.Success>(backups.backUp(folder))
        clock.now += HOUR
        f.play(c)
        assertIs<BackupResult.Success>(backups.backUp(folder))
        clock.now += HOUR

        f.clearHistory()
        val result = assertIs<BackupResult.Success>(backups.backUp(folder, replacePrevious = true))
        // One new base, which is also the restore point: no file keeps the cleared history.
        assertEquals(listOf("minim-phoneA-g002-base.fermata"), dir.list()!!.sorted())
        val base = File(dir, "minim-phoneA-g002-base.fermata").inputStream().use { com.minim.fermata.backup.LogFile.read(it) }
        assertTrue(base.events.none { EventBody.isHistory(it.body.type) }, "the base still holds history")
        assertEquals(result.eventCount, base.header.chainCount)
        assertEquals(com.minim.fermata.backup.ChainPoint(2, 0, base.header.createdAt), base.header.restorePoint)

        // A normal backup afterwards adds a piece; the restore point stays at the clear.
        clock.now += HOUR
        f.play(a)
        assertIs<BackupResult.Success>(backups.backUp(folder))
        assertEquals(listOf("minim-phoneA-g002-base.fermata", "minim-phoneA-g002-p0001.fermata"), dir.list()!!.sorted())
        val previous = backups.list(folder).single { it.role == com.minim.fermata.backup.BackupRole.PREVIOUS }
        assertEquals(result.eventCount, previous.eventCount)
    }

    @Test
    fun `a phone merging another phone's clear from the folder drops its history and stays dropped`() {
        val phoneA = fermata("phoneA")
        val phoneB = fermata("phoneB")
        val folder = FileBackupFolder(dir)
        val backupsA = FolderBackups(phoneA, InMemoryBackupStateStore(), clock = clock)
        val backupsB = FolderBackups(phoneB, InMemoryBackupStateStore(), clock = clock)
        phoneA.play(a); phoneB.play(b)
        backupsA.mergeAndBackUp(folder)
        backupsB.mergeAndBackUp(folder)
        backupsA.mergeAndBackUp(folder)
        assertEquals(setOf("A", "B"), historyIds(phoneA).toSet())
        clock.now += HOUR

        // Keep a copy of the folder from before the clear, as a phone that synced it earlier might.
        val before = File(dir, "before").also { it.mkdirs() }
        dir.listFiles()!!.filter { it.isFile }.forEach { it.copyTo(File(before, it.name)) }
        phoneA.clearHistory()
        backupsA.backUp(folder, replacePrevious = true)
        clock.now += MINUTE
        // B still has the old history in its own files; it merges A's clear and drops it.
        backupsB.mergeAndBackUp(folder)
        assertTrue(phoneB.library().history.isEmpty())
        // B started afresh: none of its files hold the old play any more.
        assertTrue(backupsB.list(folder).none { it.deviceId == "phoneB" && it.role == com.minim.fermata.backup.BackupRole.PREVIOUS })
        // A merges B's files: nothing comes back.
        clock.now += MINUTE
        backupsA.mergeFromFolder(folder)
        assertTrue(phoneA.library().history.isEmpty())
        // A third phone restoring B's backup from before the clear and then merging the folder.
        val phoneC = fermata("phoneC")
        val backupsC = FolderBackups(phoneC, InMemoryBackupStateStore(), clock = clock)
        val old = FileBackupFolder(before)
        val bOld = backupsC.list(old).first { it.deviceId == "phoneB" }
        assertIs<RestoreResult.Success>(backupsC.restore(old, bOld))
        assertEquals(setOf("A", "B"), historyIds(phoneC).toSet())
        backupsC.mergeFromFolder(folder)
        assertTrue(phoneC.library().history.isEmpty())
    }

    @Test
    fun `listeners hear merges, imports and restores with the new events only`() {
        val phoneA = fermata("phoneA")
        val phoneB = fermata("phoneB")
        val heard = mutableListOf<Fermata.Change>()
        phoneA.addListener { heard += it }
        phoneA.play(a)
        assertTrue(heard.isEmpty(), "the app's own records are not reported")
        phoneB.play(b); phoneB.play(c)
        phoneA.merge(phoneB.events())
        assertEquals(Fermata.ChangeOrigin.MERGE, heard.single().origin)
        assertEquals(listOf("B", "C"), heard.single().added.map { (it.body as EventBody.Play).track.id })
        phoneA.merge(phoneB.events())
        assertEquals(1, heard.size, "a merge that adds nothing is not reported")

        clock.now += HOUR
        phoneB.clearHistory()
        phoneA.merge(phoneB.events())
        assertTrue(heard.last().historyCleared)
        phoneA.importEvents(listOf(Event(EventId.legacy("likes:A"), EventContext.at(T0, ZoneOffset.UTC, null, null, Source.IMPORT), EventBody.Like(Subject.Track(a)))))
        assertEquals(Fermata.ChangeOrigin.IMPORT, heard.last().origin)
        phoneA.restore(phoneB.events())
        assertEquals(Fermata.ChangeOrigin.RESTORE, heard.last().origin)
    }
}
