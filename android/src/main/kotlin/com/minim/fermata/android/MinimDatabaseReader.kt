package com.minim.fermata.android

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.minim.fermata.FermataEventRow
import com.minim.fermata.LegacyPlayRow
import com.minim.fermata.LegacyTapePlayRow
import com.minim.fermata.LegacyTrackRow
import com.minim.fermata.MinimTables

/**
 * Reads what [com.minim.fermata.Fermata.importMinim] needs out of Minim's `LibraryDatabase`
 * (`minim_library.db`): `fermata_events` (version 7, Minim 5.9.0), `plays`, `tape_plays` and the
 * `tracks` metadata. Read-only. Tables or columns an older database does not have yet are read
 * as empty or null, so it works on any version Minim may upgrade from.
 *
 * ```
 * val tables = MinimDatabaseReader.read(libraryDb.readableDatabase)
 * fermata.importMinim(tables)
 * ```
 */
object MinimDatabaseReader {
    fun read(db: SQLiteDatabase): MinimTables = MinimTables(
        fermataEvents = readFermataEvents(db),
        plays = readPlays(db),
        tapePlays = readTapePlays(db),
        tracks = readTracks(db),
    )

    fun readFermataEvents(db: SQLiteDatabase): List<FermataEventRow> {
        if (!hasTable(db, "fermata_events")) return emptyList()
        return db.rawQuery("SELECT * FROM fermata_events ORDER BY id", null).use { c ->
            val out = ArrayList<FermataEventRow>(c.count)
            while (c.moveToNext()) {
                out.add(
                    FermataEventRow(
                        id = c.long("id") ?: continue,
                        type = c.str("type"),
                        trackId = c.str("track_id") ?: "",
                        title = c.str("title"),
                        artist = c.str("artist"),
                        durationMs = c.long("duration_ms"),
                        ts = c.long("ts"),
                        hour = c.int("hour"),
                        dow = c.int("dow"),
                        sessionId = c.str("session_id"),
                        prevTrackId = c.str("prev_track_id"),
                        source = c.str("source"),
                        listenedMs = c.long("listened_ms"),
                        positionMs = c.long("position_ms"),
                        deviceId = c.str("device_id"),
                        speedPct = c.int("speed_pct"),
                    ),
                )
            }
            out
        }
    }

    fun readPlays(db: SQLiteDatabase): List<LegacyPlayRow> {
        if (!hasTable(db, "plays")) return emptyList()
        return db.rawQuery("SELECT * FROM plays ORDER BY id", null).use { c ->
            val out = ArrayList<LegacyPlayRow>(c.count)
            while (c.moveToNext()) {
                out.add(
                    LegacyPlayRow(
                        id = c.long("id") ?: continue,
                        trackId = c.str("track_id") ?: "",
                        playedAt = c.long("played_at") ?: continue,
                        listenedMs = c.long("listened_ms") ?: 0L,
                        eventId = c.str("event_id"),
                        // No column at all: a database from before 4.9.0, so nothing was measured.
                        tracked = (c.int("tracked") ?: 0) == 1,
                    ),
                )
            }
            out
        }
    }

    fun readTapePlays(db: SQLiteDatabase): List<LegacyTapePlayRow> {
        if (!hasTable(db, "tape_plays")) return emptyList()
        return db.rawQuery("SELECT * FROM tape_plays ORDER BY id", null).use { c ->
            val out = ArrayList<LegacyTapePlayRow>(c.count)
            while (c.moveToNext()) {
                out.add(
                    LegacyTapePlayRow(
                        id = c.long("id") ?: continue,
                        videoId = c.str("video_id") ?: "",
                        title = c.str("title").orEmpty(),
                        channel = c.str("channel").orEmpty(),
                        thumbnailUrl = c.str("thumbnail_url").orEmpty(),
                        durationSec = c.long("duration_sec") ?: 0L,
                        playedAt = c.long("played_at") ?: continue,
                        channelId = c.str("channel_id").orEmpty(),
                    ),
                )
            }
            out
        }
    }

    fun readTracks(db: SQLiteDatabase): List<LegacyTrackRow> {
        if (!hasTable(db, "tracks")) return emptyList()
        return db.rawQuery("SELECT * FROM tracks", null).use { c ->
            val out = ArrayList<LegacyTrackRow>(c.count)
            while (c.moveToNext()) {
                val id = c.str("id") ?: continue
                out.add(
                    LegacyTrackRow(
                        id = id,
                        title = c.str("title").orEmpty(),
                        artist = c.str("artist").orEmpty(),
                        durationSec = c.long("duration_sec") ?: 0L,
                        thumbnailUrl = c.str("thumbnail_url").orEmpty(),
                        sourceUrl = c.str("source_url").orEmpty(),
                    ),
                )
            }
            out
        }
    }

    private fun hasTable(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }

    private fun Cursor.idx(column: String): Int = getColumnIndex(column)

    private fun Cursor.str(column: String): String? {
        val i = idx(column)
        return if (i < 0 || isNull(i)) null else getString(i)
    }

    private fun Cursor.long(column: String): Long? {
        val i = idx(column)
        return if (i < 0 || isNull(i)) null else getLong(i)
    }

    private fun Cursor.int(column: String): Int? {
        val i = idx(column)
        return if (i < 0 || isNull(i)) null else getInt(i)
    }
}
