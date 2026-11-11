package com.minim.fermata.android

import android.database.sqlite.SQLiteDatabase
import com.minim.fermata.Fermata
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventId
import com.minim.fermata.log.InMemoryEventStore
import com.minim.fermata.log.Source
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reads a database built with the exact tables of Minim 5.9.0's LibraryDatabase (version 7):
 * `tracks` and `plays` (with the 5.6 `event_id` and `tracked` columns), `tape_plays` and
 * `fermata_events`, the CREATE statements copied from Minim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MinimDatabaseReaderTest {
    private val db = SQLiteDatabase.create(null)

    @After
    fun close() = db.close()

    private fun minim590Schema() {
        db.execSQL(
            """
            CREATE TABLE tracks (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                duration_sec INTEGER NOT NULL,
                thumbnail_url TEXT NOT NULL,
                source_url TEXT NOT NULL,
                is_liked INTEGER NOT NULL DEFAULT 0,
                liked_at INTEGER NOT NULL DEFAULT 0,
                last_played_at INTEGER NOT NULL DEFAULT 0,
                play_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS plays (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                track_id TEXT NOT NULL,
                played_at INTEGER NOT NULL,
                listened_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("ALTER TABLE plays ADD COLUMN tracked INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE plays ADD COLUMN event_id TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tape_plays (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                video_id TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                channel TEXT NOT NULL DEFAULT '',
                channel_id TEXT NOT NULL DEFAULT '',
                thumbnail_url TEXT NOT NULL DEFAULT '',
                duration_sec INTEGER NOT NULL DEFAULT 0,
                played_at INTEGER NOT NULL,
                day INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS fermata_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT,
                track_id TEXT NOT NULL,
                title TEXT,
                artist TEXT,
                duration_ms INTEGER,
                ts INTEGER,
                hour INTEGER,
                dow INTEGER,
                session_id TEXT,
                prev_track_id TEXT,
                source TEXT,
                listened_ms INTEGER,
                position_ms INTEGER,
                device_id TEXT,
                speed_pct INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fermata_events_ts ON fermata_events(ts)")
    }

    private val dev = "3f9a0c5e1b7d2468"
    private val session = "7d6a1e7c-3c0b-4f4e-9f53-1b2a9d6e8c11"
    private val t0 = 1_794_355_200_000L

    private fun eid(at: Long, track: String) = java.lang.Long.toString(at, 36) + "_" + track

    @Test
    fun readsA590DatabaseAndImportsIt() {
        minim590Schema()
        db.execSQL(
            "INSERT INTO tracks (id, title, artist, duration_sec, thumbnail_url, source_url) VALUES " +
                "('A', 'Song A', 'Artist A', 229, 'https://i.ytimg.com/vi/A/hq.jpg', ''), ('E', 'Echo', 'Artist E', 180, '', '')",
        )
        db.execSQL("INSERT INTO plays (track_id, played_at, listened_ms, event_id) VALUES ('E', ?, 150000, ?)", arrayOf<Any>(t0 - 86_400_000, eid(t0 - 86_400_000, "E")))
        db.execSQL("INSERT INTO plays (track_id, played_at, listened_ms, event_id) VALUES ('A', ?, 185000, ?)", arrayOf<Any>(t0 + 30_000, eid(t0 + 30_000, "A")))
        db.execSQL(
            "INSERT INTO tape_plays (video_id, title, channel, duration_sec, played_at, day) VALUES ('V', 'Tape V', 'Chan', 1800, ?, 20405)",
            arrayOf<Any>(t0 + 10 * 60_000 + 30_000),
        )
        // As FermataLogger writes them: play opened at start then updated, a skip, a queued, a tape.
        db.execSQL(
            "INSERT INTO fermata_events (type, track_id, title, artist, duration_ms, ts, hour, dow, session_id, prev_track_id, source, listened_ms, position_ms, device_id, speed_pct) VALUES " +
                "('play', 'A', 'Song A', 'Artist A', 229000, ?, 19, 3, ?, NULL, 'radio', 182000, NULL, ?, NULL)," +
                "('queued', 'B', 'Song B', 'Artist B', NULL, ?, 19, 3, ?, 'A', 'queue', NULL, NULL, ?, NULL)," +
                "('play', 'B', 'Song B', 'Artist B', 200000, ?, 19, 3, ?, 'A', 'queue', 4000, NULL, ?, NULL)," +
                "('skip', 'B', 'Song B', 'Artist B', 200000, ?, 19, 3, ?, 'A', 'queue', NULL, 4000, ?, NULL)," +
                "('tape_play', 'V', 'Tape V', 'Chan', 1800000, ?, 19, 3, ?, 'B', 'tape', 600000, NULL, ?, 150)",
            arrayOf<Any>(
                t0, session, dev,
                t0 + 60_000, session, dev,
                t0 + 4 * 60_000, session, dev,
                t0 + 4 * 60_000 + 4_000, session, dev,
                t0 + 10 * 60_000, session, dev,
            ),
        )

        val tables = MinimDatabaseReader.read(db)
        assertEquals(5, tables.fermataEvents.size)
        assertEquals(2, tables.plays.size)
        assertEquals(1, tables.tapePlays.size)
        assertEquals(2, tables.tracks.size)
        val first = tables.fermataEvents[0]
        assertEquals(1L, first.id)
        assertEquals(dev, first.deviceId)
        assertEquals(182_000L, first.listenedMs)
        assertNull(first.positionMs)
        assertNull(first.prevTrackId)
        assertEquals(4_000L, tables.fermataEvents[3].positionMs)
        assertEquals(150, tables.fermataEvents[4].speedPct)
        assertEquals(eid(t0 + 30_000, "A"), tables.plays[1].eventId)

        val fermata = Fermata(InMemoryEventStore(), dev, zone = ZoneOffset.UTC)
        val result = fermata.importMinim(tables)
        assertEquals(5, result.fromFermataEvents)
        assertEquals(2, result.matched) // A's play and the tape
        assertEquals(1, result.fromLegacy) // E from before 5.9.0
        assertEquals(6, result.added)
        val events = fermata.eventsAfter(emptyMap()).associateBy { it.id }
        assertEquals(Source.QUEUE, events.getValue(EventId(dev, 3)).ctx.source)
        assertTrue(events.getValue(EventId(dev, 4)).body is EventBody.Skip)
        assertEquals(mapOf("A" to 1, "E" to 1), fermata.library().playCounts)
    }

    @Test
    fun anOlderDatabaseWithoutFermataEventsReadsAsEmpty() {
        db.execSQL("CREATE TABLE plays (id INTEGER PRIMARY KEY AUTOINCREMENT, track_id TEXT NOT NULL, played_at INTEGER NOT NULL, listened_ms INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("INSERT INTO plays (track_id, played_at, listened_ms) VALUES ('A', ?, 60000)", arrayOf<Any>(t0))
        val tables = MinimDatabaseReader.read(db)
        assertTrue(tables.fermataEvents.isEmpty())
        assertTrue(tables.tapePlays.isEmpty())
        assertEquals(1, tables.plays.size)
        assertNull(tables.plays[0].eventId) // the importer gives it the id Minim would
    }
}
