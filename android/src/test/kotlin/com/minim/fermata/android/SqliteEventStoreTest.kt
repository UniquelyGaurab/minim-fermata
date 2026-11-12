package com.minim.fermata.android

import androidx.test.core.app.ApplicationProvider
import com.minim.fermata.Fermata
import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventContext
import com.minim.fermata.log.EventId
import com.minim.fermata.log.InMemoryEventStore
import com.minim.fermata.log.Subject
import com.minim.fermata.log.TrackRef
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SqliteEventStoreTest {
    private val store = SqliteEventStore(ApplicationProvider.getApplicationContext(), name = null)

    private fun event(device: String, counter: Long, ts: Long, id: String = "t$counter") =
        Event(EventId(device, counter), EventContext.at(ts), EventBody.Play(TrackRef(id, "Song", "Artist", 200_000), 60_000))

    @After
    fun close() = store.close()

    @Test
    fun behavesLikeTheInMemoryStore() {
        val events = listOf(
            event("b", 1, 1_000),
            event("a", 1, 1_000),
            event("a", 2, 500),
            Event(EventId.legacy("x"), EventContext.at(10), EventBody.Like(Subject.Artist("Queen"))),
        )
        val reference = InMemoryEventStore(events)
        assertNull(store.maxTimestamp())
        assertEquals(4, store.appendAll(events))
        assertFalse(store.append(events[0]))
        assertEquals(0, store.appendAll(events))
        assertEquals(reference.all(), store.all())
        assertEquals(reference.heads(), store.heads())
        assertEquals(4L, store.count())
        assertEquals(1_000L, store.maxTimestamp())
        assertTrue(store.contains(EventId("a", 2)))
        assertEquals(reference.after(mapOf("a" to 1L)), store.after(mapOf("a" to 1L)))
        assertEquals(reference.after(emptyMap()), store.after(emptyMap()))
    }

    @Test
    fun drivesFermata() {
        val fermata = Fermata(store, "phone")
        fermata.record(EventBody.Like(Subject.Track(TrackRef("A"))))
        fermata.record(EventBody.PlaylistCreate("p", "Mix"))
        fermata.record(EventBody.PlaylistAdd("p", TrackRef("A")))
        assertTrue(fermata.library().isLiked("A"))
        assertEquals(listOf("A"), Fermata(store, "phone").library().playlist("p")!!.tracks.map { it.id })
        assertEquals(mapOf("phone" to 3L), store.heads())
    }

    @Test
    fun historyClearDeletesOnlyOldHistoryAndKeepsTheHeads() {
        val like = Event(EventId("a", 3), EventContext.at(100), EventBody.Like(Subject.Track(TrackRef("A"))))
        val appHistory = Event(EventId("b", 2), EventContext.at(100), EventBody.Unknown("history.minim.listen", "{}"))
        val other = Event(EventId("b", 3), EventContext.at(100), EventBody.Unknown("x.custom", "{}"))
        store.appendAll(listOf(event("a", 1, 100), event("a", 2, 5_000), like, event("b", 1, 200), appHistory, other))
        store.append(Event(EventId("a", 4), EventContext.at(1_000), EventBody.HistoryClear(1_000)))
        val headsBefore = store.heads()

        assertEquals(3, store.removeHistoryBefore(1_000))
        assertEquals(listOf(EventId("a", 3), EventId("b", 3), EventId("a", 4), EventId("a", 2)), store.all().map { it.id })
        assertEquals(1_000L, store.clearedBefore())
        assertEquals(headsBefore, store.heads())

        // Deleting a device's newest events never lowers its head, so its counters are not reused.
        assertEquals(1, store.removeAll(listOf(EventId("a", 4))))
        assertEquals(4L, store.heads()["a"])
        // loggedHeads is what a backup's manifest records: only the events still in the log.
        store.appendAll(listOf(Event(EventId.legacy("likes:Z"), EventContext.at(50), EventBody.Like(Subject.Track(TrackRef("Z"))))))
        assertEquals(mapOf("a" to 3L, "b" to 3L), store.loggedHeads())
        assertEquals(com.minim.fermata.log.EventLog.heads(store.all()), store.loggedHeads())
        assertEquals(4L, Fermata(store, "a").record(EventBody.Like(Subject.Artist("Q"))).id.counter - 1)
    }

    @Test
    fun aVersionOneDatabaseGetsTheHeadsTableOnUpgrade() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "v1-fermata.db"
        context.deleteDatabase(name)
        val v1 = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        v1.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY NOT NULL, device TEXT NOT NULL, counter INTEGER NOT NULL, ts INTEGER NOT NULL, type TEXT NOT NULL, json TEXT NOT NULL)")
        v1.version = 1
        v1.close()
        val upgraded = SqliteEventStore(context, name)
        try {
            upgraded.append(event("a", 1, 100))
            assertEquals(1, upgraded.removeHistoryBefore(1_000))
            assertEquals(mapOf("a" to 1L), upgraded.heads())
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    /** An app database with its own table and the log as table "fermata_log", as Minim 5.10.0 keeps it. */
    private class AppDb(context: android.content.Context, name: String?) :
        android.database.sqlite.SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
            db.execSQL("CREATE TABLE likes (track TEXT PRIMARY KEY NOT NULL)")
            SqliteEventStore.createSchema(db, "fermata_log")
        }
        override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Test
    fun aLogInsideTheAppDatabaseSharesItsTransaction() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val helper = AppDb(context, null)
        try {
            val log = SqliteEventStore.inDatabase("fermata_log") { helper.writableDatabase }
            val fermata = Fermata(log, "phone")
            val db = helper.writableDatabase

            // Committed: the app's row and the event land together.
            fermata.transaction {
                db.beginTransaction()
                try {
                    db.execSQL("INSERT INTO likes VALUES ('A')")
                    fermata.record(EventBody.Like(Subject.Track(TrackRef("A"))))
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            // Rolled back (the app's write fails after the event was recorded): neither stays.
            val failed = runCatching {
                fermata.transaction {
                    db.beginTransaction()
                    try {
                        fermata.record(EventBody.Like(Subject.Track(TrackRef("B"))))
                        db.execSQL("INSERT INTO likes VALUES ('A')") // primary key clash
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
            }
            assertTrue(failed.isFailure)
            assertEquals(1L, log.count())
            assertEquals(1L, android.database.DatabaseUtils.queryNumEntries(db, "likes"))
            assertEquals(listOf("A"), fermata.library().likedTracks.map { it.track.id })
            // The rolled-back id is not reused.
            assertEquals(3L, fermata.record(EventBody.Like(Subject.Track(TrackRef("C")))).id.counter)

            // The table name is the app's: the default "events" table does not exist in this file.
            assertFalse(db.rawQuery("SELECT name FROM sqlite_master WHERE name = 'events'", null).use { it.moveToFirst() })
            // History clears keep heads in the log's own floors table.
            fermata.record(EventBody.Play(TrackRef("A"), 60_000))
            fermata.clearHistory()
            assertTrue(db.rawQuery("SELECT 1 FROM fermata_log_head_floors", null).use { it.moveToFirst() })
            assertEquals(log.heads(), mapOf("phone" to 5L))
            // createSchema is safe to run again on every upgrade.
            SqliteEventStore.createSchema(db, "fermata_log")
            // Like A, like C and the clear stay; the play was cleared.
            assertEquals(3L, log.count())
        } finally {
            helper.close()
        }
    }
}
