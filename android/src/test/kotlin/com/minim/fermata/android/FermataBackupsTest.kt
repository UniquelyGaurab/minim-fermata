package com.minim.fermata.android

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.minim.fermata.Fermata
import com.minim.fermata.backup.BackupArchive
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventId
import com.minim.fermata.log.InMemoryEventStore
import com.minim.fermata.log.Source
import com.minim.fermata.log.Subject
import com.minim.fermata.log.TrackRef
import com.minim.fermata.sync.BackupError
import com.minim.fermata.sync.BackupResult
import com.minim.fermata.sync.FileBackupFolder
import com.minim.fermata.sync.FolderBackups
import com.minim.fermata.sync.InMemoryBackupStateStore
import com.minim.fermata.sync.RestoreOffer
import com.minim.fermata.sync.RestoreResult
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app's Application, as Minim will implement it, so the worker finds Fermata. */
class TestApp : Application(), FermataHolder {
    lateinit var holder: Fermata
    override val fermata: Fermata get() = holder
    override val appVersion: String = "5.10.0-test"
    var onBeforeBackup: () -> Unit = {}
    override fun beforeBackup() = onBeforeBackup()
}

private class StepClock(var now: Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(now).also { now += 1_000 }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestApp::class)
class FermataBackupsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File
    private lateinit var provider: TestDocumentsProvider
    private val stores = ArrayList<SqliteEventStore>()
    private val clock = StepClock(1_794_355_200_000L)

    private val a = TrackRef("A", "Song A", "Alpha", 200_000)
    private val b = TrackRef("B", "Song B", "Beta", 200_000)
    private val c = TrackRef("C", "Song C", "Gamma", 200_000)

    @Before
    fun setUp() {
        dir = tmp.newFolder("Backups")
        provider = TestDocumentsProvider.register(dir)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() = stores.forEach { it.close() }

    /** A phone with a real SQLite log (in memory). */
    private fun phone(device: String): Pair<Fermata, SqliteEventStore> {
        val store = SqliteEventStore(context, name = null).also { stores += it }
        return Fermata(store, device, clock, ZoneOffset.UTC) to store
    }

    private fun Fermata.plays(vararg tracks: TrackRef) = tracks.forEach { record(EventBody.Play(it, 120_000), Source.RADIO) }

    private fun names() = dir.list()!!.sorted()

    private fun read(name: String) = File(dir, name).inputStream().use { BackupArchive.read(it) }

    @Test
    fun backUpNowWritesTempVerifiesAndRotatesThroughSaf() {
        val (f, _) = phone("phoneA")
        f.plays(a, b)
        val backups = FermataBackups(context, f, "5.10.0")
        assertNull(backups.folder())
        assertEquals(BackupError.NO_FOLDER, assertIs<BackupResult.Failure>(backups.backUpNow()).error)

        backups.setFolder(TestDocumentsProvider.tree)
        assertTrue(backups.hasFolderAccess())
        val first = assertIs<BackupResult.Success>(backups.backUpNow())
        assertEquals("minim-phoneA-latest.minim", first.fileName)
        assertEquals(listOf("minim-phoneA-latest.minim"), names())

        f.plays(c)
        assertIs<BackupResult.Success>(backups.backUpNow())
        assertEquals(listOf("minim-phoneA-latest.minim", "minim-phoneA-previous.minim"), names())
        assertEquals(3L, read("minim-phoneA-latest.minim").manifest.eventCount)
        assertEquals(2L, read("minim-phoneA-previous.minim").manifest.eventCount)
        assertEquals("5.10.0", read("minim-phoneA-latest.minim").manifest.appVersion)

        // The status survives the app restarting (a new instance reads it back).
        val status = FermataBackups(context, f).status()
        assertTrue(status.ok)
        assertEquals(3L, status.lastEventCount)
        assertNotNull(status.lastSuccessAt)
    }

    @Test
    fun aCorruptWriteIsCaughtAndTheOldLatestStays() {
        val (f, _) = phone("phoneA")
        f.plays(a)
        val backups = FermataBackups(context, f).apply { setFolder(TestDocumentsProvider.tree) }
        backups.backUpNow()
        val good = File(dir, "minim-phoneA-latest.minim").readBytes()

        f.plays(b, c)
        provider.corruptReads = true
        val failed = assertIs<BackupResult.Failure>(backups.backUpNow())
        assertEquals(BackupError.VERIFY_FAILED, failed.error)
        assertEquals(listOf("minim-phoneA-latest.minim"), names())
        assertTrue(good.contentEquals(File(dir, "minim-phoneA-latest.minim").readBytes()))
        assertEquals(BackupError.VERIFY_FAILED, backups.status().lastError)

        provider.corruptReads = false
        assertIs<BackupResult.Success>(backups.backUpNow())
        assertNull(backups.status().lastError)
    }

    @Test
    fun aFailedRenameRollsBackToTheOldLatest() {
        val (f, _) = phone("phoneA")
        f.plays(a)
        val backups = FermataBackups(context, f).apply { setFolder(TestDocumentsProvider.tree) }
        backups.backUpNow()
        f.plays(b)
        provider.failRenameTo = "minim-phoneA-previous.minim"
        assertEquals(BackupError.RENAME_FAILED, assertIs<BackupResult.Failure>(backups.backUpNow()).error)
        assertEquals(listOf("minim-phoneA-latest.minim"), names())
        assertEquals(1L, read("minim-phoneA-latest.minim").manifest.eventCount)
    }

    @Test
    fun lostPermissionIsReportedNotRetried() {
        val (f, _) = phone("phoneA")
        f.plays(a)
        val backups = FermataBackups(context, f).apply { setFolder(TestDocumentsProvider.tree) }
        context.contentResolver.releasePersistableUriPermission(
            TestDocumentsProvider.tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        assertFalse(backups.hasFolderAccess())
        assertEquals(BackupError.FOLDER_ACCESS_LOST, assertIs<BackupResult.Failure>(backups.backUpNow()).error)
        assertEquals(BackupError.FOLDER_ACCESS_LOST, backups.status().lastError)
        assertEquals(emptyList(), backups.listBackups())
        assertEquals(BackupError.FOLDER_ACCESS_LOST, backups.merge().error)
        assertEquals(emptyList(), names())
    }

    @Test
    fun freshInstallOffersAndRestoresAnotherPhonesBackup() {
        val (old, _) = phone("oldphone")
        old.plays(a, b)
        old.record(EventBody.Like(Subject.Track(a)))
        old.record(EventBody.PlaylistCreate("p", "Road trip"))
        old.record(EventBody.PlaylistAdd("p", b))
        FermataBackups(context, old).apply { setFolder(TestDocumentsProvider.tree) }.backUpNow()

        val (fresh, store) = phone("newphone")
        val backups = FermataBackups(context, fresh)
        val offer = assertIs<RestoreOffer.Available>(backups.restoreOffer())
        val chosen = offer.backups.single()
        assertEquals("oldphone", chosen.deviceId)
        assertFalse(chosen.fromThisPhone)
        val r = assertIs<RestoreResult.Success>(backups.restore(chosen))
        assertEquals(5, r.added)
        assertEquals(5L, store.count())
        assertEquals(old.library(), fresh.library())
        assertTrue(fresh.library().isLiked("A"))
        assertEquals(listOf("B"), fresh.library().playlist("p")!!.tracks.map { it.id })
        assertEquals(RestoreOffer.NotNeeded, backups.restoreOffer())
        // Original ids kept; this phone writes under its own id from 1.
        assertEquals(setOf("oldphone"), store.heads().keys)
        assertEquals(EventId("newphone", 1), fresh.record(EventBody.Play(c, 1_000)).id)
    }

    @Test
    fun aRestoreThatFailsInSqliteChangesNothing() {
        val donor = Fermata(InMemoryEventStore(), "donor", clock, ZoneOffset.UTC)
        donor.plays(a, b, c, a, b)
        FolderBackups(donor, InMemoryBackupStateStore(), clock = clock).backUp(FileBackupFolder(dir))

        val (f, store) = phone("phoneA")
        f.plays(c)
        val before = f.library()
        // The disk fills up after two more rows: the insert fails inside the transaction.
        store.database().execSQL(
            "CREATE TRIGGER full BEFORE INSERT ON events WHEN (SELECT COUNT(*) FROM events) >= 3 " +
                "BEGIN SELECT RAISE(ABORT, 'database or disk is full'); END",
        )
        val backups = FermataBackups(context, f).apply { setFolder(TestDocumentsProvider.tree) }
        val result = backups.restore(backups.listBackups().single { it.deviceId == "donor" })
        assertEquals(BackupError.RESTORE_FAILED, assertIs<RestoreResult.Failure>(result).error)
        assertEquals(1L, store.count())
        assertEquals(before, f.library())

        store.database().execSQL("DROP TRIGGER full")
        assertEquals(EventId("phoneA", 2), f.record(EventBody.Play(a, 1_000)).id)
    }

    @Test
    fun corruptAndHalfSyncedBackupsAreRefusedBeforeAnythingChanges() {
        val donor = Fermata(InMemoryEventStore(), "donor", clock, ZoneOffset.UTC)
        donor.plays(a, b)
        FolderBackups(donor, InMemoryBackupStateStore(), clock = clock).backUp(FileBackupFolder(dir))
        val file = File(dir, "minim-donor-latest.minim")
        val good = file.readBytes()
        File(dir, "minim-junk-latest.minim").writeText("<html>sign in to your cloud</html>")

        val (f, store) = phone("phoneA")
        val backups = FermataBackups(context, f).apply { setFolder(TestDocumentsProvider.tree) }
        val list = backups.listBackups()
        assertFalse(list.single { it.fileName == "minim-junk-latest.minim" }.restorable)
        val info = list.single { it.deviceId == "donor" }

        file.writeBytes(good.copyOf(eventsDataStart(good) + 10))
        assertEquals(BackupError.DAMAGED, assertIs<RestoreResult.Failure>(backups.restore(info)).error)
        assertEquals(0L, store.count())
        // Only that phone's previous would be used by a merge; with none, it is reported and retried.
        val merge = backups.merge()
        assertEquals(0, merge.eventsAdded)
        assertEquals(setOf("minim-donor-latest.minim", "minim-junk-latest.minim"), merge.problems.map { it.fileName }.toSet())

        file.writeBytes(good)
        assertEquals(2, backups.merge().eventsAdded)
    }

    @Test
    fun restoresABackupFromTheFirstFermataVersion() {
        writeOldBackup(File(dir, "minim-backup-2026-11-01.minim"))
        val (f, _) = phone("phoneA")
        val backups = FermataBackups(context, f).apply { setFolder(TestDocumentsProvider.tree) }
        val info = assertIs<RestoreOffer.Available>(backups.restoreOffer()).backups.single()
        assertEquals("0.1.0", info.fermataVersion)
        val r = assertIs<RestoreResult.Success>(backups.restore(info))
        assertEquals(3, r.added)
        val lib = f.library()
        assertTrue(lib.isLiked("A"))
        assertEquals(mapOf("A" to 1, "B" to 1), lib.playCounts)
        // And the next backup is in the new format, under this phone's own name.
        assertIs<BackupResult.Success>(backups.backUpNow())
        assertTrue("minim-phoneA-latest.minim" in names())
        assertTrue("minim-backup-2026-11-01.minim" in names()) // never deleted
    }

    @Test
    fun mergesTwoOtherPhonesOnceAndCarriesThemIntoItsOwnBackup() {
        val pb = Fermata(InMemoryEventStore(), "phoneB", clock, ZoneOffset.UTC)
        val pc = Fermata(InMemoryEventStore(), "phoneC", clock, ZoneOffset.UTC)
        pb.plays(a, b)
        pb.record(EventBody.Like(Subject.Artist("Gamma")))
        pc.plays(c)
        // Both had the same old synced play.
        pb.importLegacy(EventBody.Play(a, 150_000), 1_700_000_000_000L, "play|uid_A")
        pc.importLegacy(EventBody.Play(a, 150_000), 1_700_000_000_000L, "play|uid_A")
        val sb = InMemoryBackupStateStore()
        FolderBackups(pb, sb, clock = clock).backUp(FileBackupFolder(dir))
        FolderBackups(pc, InMemoryBackupStateStore(), clock = clock).backUp(FileBackupFolder(dir))
        // Phone B is in the middle of writing its next backup.
        File(dir, "minim-phoneB-99-0badf00d.tmp").writeBytes(ByteArray(10))

        val (pa, store) = phone("phoneA")
        pa.plays(a)
        val backups = FermataBackups(context, pa).apply { setFolder(TestDocumentsProvider.tree) }
        val first = backups.merge()
        assertEquals(2, first.phones)
        assertEquals(5, first.eventsAdded) // 3 from B, 1 from C, the shared legacy play once
        assertEquals(6L, store.count())
        assertEquals(mapOf("A" to 3, "B" to 1, "C" to 1), pa.library().playCounts) // A: phone A, phone B, and the shared legacy play once
        assertEquals(0, backups.merge().eventsAdded)
        assertEquals(2, backups.merge().unchanged)

        assertIs<BackupResult.Success>(backups.backUpNow())
        assertEquals(6L, read("minim-phoneA-latest.minim").manifest.eventCount)
        assertTrue(File(dir, "minim-phoneB-99-0badf00d.tmp").exists()) // another phone's file: untouched

        // Phone B adds one more; only that comes over.
        pb.plays(c)
        FolderBackups(pb, sb, clock = clock).backUp(FileBackupFolder(dir))
        val third = backups.merge()
        assertEquals(1, third.eventsAdded)
        assertEquals(1, third.unchanged)
        assertNotNull(backups.status().lastMergeAt)
    }

    @Test
    fun dailyWorkIsScheduledWhileChargingAndTheWorkerBacksUp() {
        val (f, _) = phone("phoneA")
        f.plays(a)
        (context as TestApp).holder = f
        val backups = FermataBackups(context, f)
        backups.setFolder(TestDocumentsProvider.tree)

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(FermataBackups.DAILY_WORK).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        val spec = FermataBackups.dailyRequest().workSpec
        assertTrue(spec.constraints.requiresCharging())
        assertTrue(spec.constraints.requiresBatteryNotLow())
        assertEquals(24 * 60 * 60 * 1000L, spec.intervalDuration)

        // Scheduling again (every app start) keeps one job.
        backups.scheduleDaily()
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(FermataBackups.DAILY_WORK).get().size)

        val worker = TestListenableWorkerBuilder<BackupWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(listOf("minim-phoneA-latest.minim"), names())
        assertTrue(backups.status().ok)
        assertNotNull(backups.status().lastMergeAt)

        backups.enqueueBackUpNow()
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(FermataBackups.NOW_WORK).get().size)

        // No folder: nothing to retry.
        backups.clearFolder()
        assertEquals(ListenableWorker.Result.success(), TestListenableWorkerBuilder<BackupWorker>(context).build().doWork())
        assertEquals(BackupError.NO_FOLDER, backups.status().lastError)
        assertTrue(
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(FermataBackups.DAILY_WORK).get()
                .all { it.state == WorkInfo.State.CANCELLED },
        )
    }

    @Test
    fun stateStoreRoundTrips() {
        val prefs = context.getSharedPreferences("state-test", Context.MODE_PRIVATE)
        val store = PrefsBackupStateStore(prefs)
        val state = com.minim.fermata.sync.BackupState(
            com.minim.fermata.sync.BackupStatus(1, 2, 3, 4, BackupError.VERIFY_FAILED, "bad", 5),
            mapOf("phoneB" to "abc", "phoneC" to "def"),
            restoreOfferDismissed = true,
        )
        store.save(state)
        assertEquals(state, PrefsBackupStateStore(prefs).load())
        val next = state.copy(status = state.status.copy(lastError = null, lastErrorMessage = null), merged = mapOf("phoneB" to "x"))
        store.save(next)
        assertEquals(next, store.load())
    }

    // --- helpers ---

    private fun eventsDataStart(bytes: ByteArray): Int {
        val name = "fermata-events.jsonl.gz".toByteArray()
        val at = (0..bytes.size - name.size).first { i -> name.indices.all { bytes[i + it] == name[it] } }
        val extraLen = (bytes[at - 2].toInt() and 0xFF) or ((bytes[at - 1].toInt() and 0xFF) shl 8)
        return at + name.size + extraLen
    }

    /** A backup as Fermata 0.1.0 wrote it: daily name, no heads/appVersion, no `replaces`. */
    private fun writeOldBackup(file: File) {
        val t = 1_794_300_000_000L
        val lines = listOf(
            """{"id":"0ldphone00000001:1","ts":$t,"hour":10,"dow":2,"source":"radio","type":"play","data":{"track":{"id":"A","title":"Song A","artist":"Alpha","durationMs":200000},"listenedMs":150000}}""",
            """{"id":"0ldphone00000001:2","ts":${t + 1},"hour":10,"dow":2,"source":"unknown","type":"like","data":{"on":"track","track":{"id":"A"}}}""",
            """{"id":"legacy:777","ts":${t - 1000},"hour":10,"dow":2,"source":"import","type":"play","data":{"track":{"id":"B"},"listenedMs":90000}}""",
        )
        val gz = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(lines.joinToString("\n", postfix = "\n").toByteArray()) }
        }.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(gz).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val manifest = """{"format":1,"createdAt":$t,"deviceId":"0ldphone00000001","fermataVersion":"0.1.0","eventCount":3,
            "files":[{"name":"fermata-events.jsonl.gz","sha256":"$sha","bytes":${gz.size}}]}"""
        ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("fermata-events.jsonl.gz")); z.write(gz); z.closeEntry()
        }
    }

    @Test
    fun dailyJobLetsTheAppRecordHeldBackHistoryBeforeBackingUp() {
        val (f, _) = phone("phoneA")
        f.plays(a)
        val app = context as TestApp
        app.holder = f
        FermataBackups(context, f).setFolder(TestDocumentsProvider.tree)
        var calls = 0
        // Minim saves plays still in progress here; the backup must already include them.
        app.onBeforeBackup = { calls++; f.plays(b) }
        try {
            assertEquals(ListenableWorker.Result.success(), TestListenableWorkerBuilder<BackupWorker>(context).build().doWork())
        } finally {
            app.onBeforeBackup = {}
        }
        assertEquals(1, calls)
        assertEquals(2L, read("minim-phoneA-latest.minim").manifest.eventCount)
    }

    @Test
    fun aFailingBeforeBackupDoesNotStopTheBackup() {
        val (f, _) = phone("phoneA")
        f.plays(a, b)
        val app = context as TestApp
        app.holder = f
        val backups = FermataBackups(context, f)
        backups.setFolder(TestDocumentsProvider.tree)
        app.onBeforeBackup = { error("app not ready") }
        try {
            assertEquals(ListenableWorker.Result.success(), TestListenableWorkerBuilder<BackupWorker>(context).build().doWork())
        } finally {
            app.onBeforeBackup = {}
        }
        assertTrue(backups.status().ok)
        assertEquals(2L, read("minim-phoneA-latest.minim").manifest.eventCount)
    }
}
