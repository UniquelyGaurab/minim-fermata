package com.minim.fermata.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.minim.fermata.backup.EventCodec
import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventId
import com.minim.fermata.log.EventStore

/**
 * The event log on SQLite (Android's own, no Room, matching Minim). One row per event; the event
 * itself is stored as its JSON Lines text, so the database and the backup share one format.
 *
 * Ordering and dedupe live in the schema: the primary key is the event id (INSERT OR IGNORE
 * makes re-imports harmless) and the index on (ts, device, counter) is the canonical order.
 *
 * Pass `name = null` for an in-memory database (tests).
 */
class SqliteEventStore(
    context: Context,
    name: String? = DEFAULT_NAME,
) : EventStore {
    private val helper = Helper(context.applicationContext, name)

    private val db: SQLiteDatabase get() = helper.writableDatabase

    override fun append(event: Event): Boolean =
        db.insertWithOnConflict(TABLE, null, values(event), SQLiteDatabase.CONFLICT_IGNORE) != -1L

    override fun appendAll(events: Iterable<Event>): Int {
        val database = db
        var added = 0
        database.beginTransaction()
        try {
            for (e in events) {
                if (database.insertWithOnConflict(TABLE, null, values(e), SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return added
    }

    override fun all(): List<Event> =
        db.rawQuery("SELECT json FROM $TABLE ORDER BY ts, device, counter", null).use { c ->
            val events = ArrayList<Event>(c.count)
            while (c.moveToNext()) events.add(EventCodec.decode(c.getString(0)))
            events
        }

    override fun contains(id: EventId): Boolean =
        db.rawQuery("SELECT 1 FROM $TABLE WHERE id = ? LIMIT 1", arrayOf(id.toString())).use { it.moveToFirst() }

    override fun count(): Long =
        db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /**
     * The highest counter per device, including events deleted by a history clear (kept in
     * [FLOORS]), so a counter is never handed out twice.
     */
    override fun heads(): Map<String, Long> {
        val heads = HashMap<String, Long>()
        db.rawQuery(
            "SELECT device, MAX(counter) FROM $TABLE WHERE device != ? GROUP BY device",
            arrayOf(EventId.LEGACY_DEVICE),
        ).use { c -> while (c.moveToNext()) heads[c.getString(0)] = c.getLong(1) }
        db.rawQuery("SELECT device, counter FROM $FLOORS", null).use { c ->
            while (c.moveToNext()) heads.merge(c.getString(0), c.getLong(1)) { a, b -> maxOf(a, b) }
        }
        return heads
    }

    override fun maxTimestamp(): Long? =
        db.rawQuery("SELECT MAX(ts) FROM $TABLE", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    override fun after(heads: Map<String, Long>): List<Event> {
        // Small vectors in practice (a few devices), so filter in SQL per device.
        val clauses = StringBuilder("device = ?")
        val args = arrayListOf(EventId.LEGACY_DEVICE)
        val known = heads.filterKeys { it != EventId.LEGACY_DEVICE }
        if (known.isEmpty()) {
            clauses.append(" OR 1")
        } else {
            clauses.append(" OR device NOT IN (").append(known.keys.joinToString(",") { "?" }).append(")")
            args.addAll(known.keys)
            for ((device, counter) in known) {
                clauses.append(" OR (device = ? AND counter > ?)")
                args.add(device)
                args.add(counter.toString())
            }
        }
        return db.rawQuery(
            "SELECT json FROM $TABLE WHERE $clauses ORDER BY ts, device, counter",
            args.toTypedArray(),
        ).use { c ->
            val events = ArrayList<Event>()
            while (c.moveToNext()) events.add(EventCodec.decode(c.getString(0)))
            events
        }
    }

    override fun removeAll(ids: Collection<EventId>): Int {
        val database = db
        var removed = 0
        database.beginTransaction()
        try {
            keepHeads(database)
            for (id in ids) removed += database.delete(TABLE, "id = ?", arrayOf(id.toString()))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return removed
    }

    override fun removeHistoryBefore(before: Long): Int {
        val database = db
        database.beginTransaction()
        try {
            keepHeads(database)
            val removed = database.delete(
                TABLE,
                "ts < ? AND (type IN ('${EventBody.PLAY}', '${EventBody.SKIP}', '${EventBody.QUEUED}', '${EventBody.TAPE_PLAY}') OR type LIKE ?)",
                arrayOf(before.toString(), EventBody.APP_HISTORY_PREFIX + "%"),
            )
            database.setTransactionSuccessful()
            return removed
        } finally {
            database.endTransaction()
        }
    }

    /** Saves the current heads before deleting, so they never move backwards. */
    private fun keepHeads(database: SQLiteDatabase) {
        // Plain INSERT OR IGNORE + UPDATE: UPSERT needs SQLite 3.24, newer than Android 9's.
        database.execSQL(
            "INSERT OR IGNORE INTO $FLOORS(device, counter) SELECT device, MAX(counter) FROM $TABLE WHERE device != ? GROUP BY device",
            arrayOf(EventId.LEGACY_DEVICE),
        )
        database.execSQL(
            """
            UPDATE $FLOORS SET counter = (SELECT MAX(e.counter) FROM $TABLE e WHERE e.device = $FLOORS.device)
            WHERE (SELECT MAX(e.counter) FROM $TABLE e WHERE e.device = $FLOORS.device) > counter
            """.trimIndent(),
        )
    }

    override fun clearedBefore(): Long? =
        db.rawQuery("SELECT json FROM $TABLE WHERE type = ?", arrayOf(EventBody.HISTORY_CLEAR)).use { c ->
            var best: Long? = null
            while (c.moveToNext()) {
                val b = runCatching { EventCodec.decode(c.getString(0)).body }.getOrNull() as? EventBody.HistoryClear ?: continue
                if (best == null || b.before > best) best = b.before
            }
            best
        }

    fun close() = helper.close()

    /** For tests that need to make the database fail part way. */
    internal fun database(): SQLiteDatabase = db

    private fun values(e: Event) = ContentValues().apply {
        put("id", e.id.toString())
        put("device", e.id.device)
        put("counter", e.id.counter)
        put("ts", e.timestamp)
        put("type", e.type)
        put("json", EventCodec.encode(e))
    }

    private class Helper(context: Context, name: String?) : SQLiteOpenHelper(context, name, null, VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    id TEXT PRIMARY KEY NOT NULL,
                    device TEXT NOT NULL,
                    counter INTEGER NOT NULL,
                    ts INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    json TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_events_order ON $TABLE(ts, device, counter)")
            db.execSQL("CREATE INDEX idx_events_device ON $TABLE(device, counter)")
            db.execSQL("CREATE INDEX idx_events_type ON $TABLE(type, ts)")
            createFloors(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Never drop the log: migrate forward only.
            if (oldVersion < 2) createFloors(db)
        }

        /** Version 2: the highest counter per device, kept when a history clear deletes events. */
        private fun createFloors(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $FLOORS (device TEXT PRIMARY KEY NOT NULL, counter INTEGER NOT NULL)")
        }
    }

    companion object {
        const val DEFAULT_NAME = "fermata.db"
        private const val TABLE = "events"
        private const val FLOORS = "head_floors"
        private const val VERSION = 2
    }
}
