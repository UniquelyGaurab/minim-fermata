package com.minim.fermata

import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventContext
import com.minim.fermata.log.EventId
import com.minim.fermata.log.Source
import com.minim.fermata.log.TrackRef
import java.time.ZoneId

/**
 * One row of Minim 5.9.0's `fermata_events` table (LibraryDatabase version 7), column for column.
 * Nullable exactly where the table is: only `id` and `track_id` are guaranteed.
 */
data class FermataEventRow(
    /** INTEGER PRIMARY KEY AUTOINCREMENT: this phone's event counter. */
    val id: Long,
    /** play, skip, queued or tape_play. */
    val type: String?,
    val trackId: String,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
    /** Epoch ms. For play and tape_play rows, when the track started. */
    val ts: Long?,
    val hour: Int? = null,
    /** ISO day of week, 1 = Monday. */
    val dow: Int? = null,
    /** A UUID in 5.9.0; opaque. */
    val sessionId: String? = null,
    val prevTrackId: String? = null,
    /** radio, search, library, playlist, discover, tape, queue or unknown. */
    val source: String? = null,
    /** Play and tape_play rows: time heard, updated in place while the track played. */
    val listenedMs: Long? = null,
    /** Skip rows: where the track was left. */
    val positionMs: Long? = null,
    /** 16 hex characters, the phone that wrote the row (a restored database keeps the old phone's). */
    val deviceId: String? = null,
    /** Tape rows: playback speed in percent. */
    val speedPct: Int? = null,
)

/** One row of Minim's old `plays` table (a counted play: 30 s heard, or 80% of a song under 37.5 s). */
data class LegacyPlayRow(
    val id: Long,
    val trackId: String,
    /** When the play counted (the 30 s mark), not when the track started. */
    val playedAt: Long,
    val listenedMs: Long = 0,
    /** Sync id, `base36(played_at)_trackId`; the same on every phone the play synced to. */
    val eventId: String? = null,
)

/** One row of Minim's old `tape_plays` table (a tape that passed 30 s heard). */
data class LegacyTapePlayRow(
    val id: Long,
    val videoId: String,
    val title: String = "",
    val channel: String = "",
    val thumbnailUrl: String = "",
    val durationSec: Long = 0,
    val playedAt: Long,
)

/** Display metadata from Minim's `tracks` table, used to fill in what a row does not carry. */
data class LegacyTrackRow(
    val id: String,
    val title: String = "",
    val artist: String = "",
    val durationSec: Long = 0,
    val thumbnailUrl: String = "",
)

/** What Minim reads out of its database for [MinimImport]. */
data class MinimTables(
    val fermataEvents: List<FermataEventRow> = emptyList(),
    val plays: List<LegacyPlayRow> = emptyList(),
    val tapePlays: List<LegacyTapePlayRow> = emptyList(),
    val tracks: List<LegacyTrackRow> = emptyList(),
)

data class MinimImportResult(
    val events: List<Event>,
    /** 5.9.0 rows turned into events. */
    val fromFermataEvents: Int,
    /** Old plays and tape plays that had no 5.9.0 row and became legacy events. */
    val fromLegacy: Int,
    /** Old plays and tape plays that were the same play as a 5.9.0 row (counted once). */
    val matched: Int,
    /** Rows that could not become an event (no timestamp, blank track id, unknown type). */
    val skippedRows: Int,
    /** Set by [Fermata.importMinim]: how many events were new to the log. */
    val added: Int = 0,
)

/**
 * Turns Minim's tables into Fermata events. Pure and deterministic: the same tables always give
 * the same events, so importing twice, or on two phones, never doubles anything.
 *
 * - `fermata_events` rows keep their identity: `EventId(device_id, id)`. A row whose device id is
 *   missing falls back to a legacy id built from its content.
 * - Old `plays` rows use `legacy("play|<event_id>")`, old `tape_plays` rows
 *   `legacy("tape|<video_id>|<played_at>")`.
 * - A 5.9.0 play row and an old play of the same track that counted while that row was the one
 *   playing are the same play. Only the 5.9.0 event is written, with
 *   [EventContext.replaces] pointing at the legacy id, so the legacy copy is ignored wherever it
 *   turns up later (another phone's import, the Firestore copy). Its heard time is the larger of
 *   the two, because the old table may have saved a few seconds more before a kill.
 * - Rows keep their phone-clock timestamps; Fermata's clock rule applies to new events only.
 */
object MinimImport {
    /** Old plays count at the 30 s mark; allow a little clock slack before the row's start. */
    private const val START_SLACK_MS = 5_000L

    /** A play row never claims an old play more than this long after it started. */
    private const val MAX_WINDOW_MS = 12 * 60 * 60_000L

    /** Old tape plays were written at 30 s heard, so that much was heard at least. */
    const val LEGACY_TAPE_LISTENED_MS = 30_000L

    private val sources = setOf("radio", "search", "library", "playlist", "discover", "tape", "queue", "unknown")

    fun convert(tables: MinimTables, zone: ZoneId = ZoneId.systemDefault()): MinimImportResult {
        val meta = tables.tracks.associateBy { it.id }
        var skipped = 0
        val rows = tables.fermataEvents.filter { r ->
            val ok = r.ts != null && r.trackId.isNotBlank() && r.type in TYPES
            if (!ok) skipped++
            ok
        }

        val legacyPlays = tables.plays.filter { r ->
            (r.trackId.isNotBlank()).also { if (!it) skipped++ }
        }
        val legacyTapes = tables.tapePlays.filter { r ->
            (r.videoId.isNotBlank()).also { if (!it) skipped++ }
        }

        // Which old play each 5.9.0 play row stands for.
        val playMatch = match(rows, EventBody.PLAY, legacyPlays, { it.trackId }, { it.playedAt }, { it.id })
        val tapeMatch = match(rows, EventBody.TAPE_PLAY, legacyTapes, { it.videoId }, { it.playedAt }, { it.id })

        val events = ArrayList<Event>(rows.size + legacyPlays.size + legacyTapes.size)
        for (r in rows) {
            val ts = r.ts!!
            val track = trackOf(r, meta)
            var replaces: EventId? = null
            val body: EventBody = when (r.type) {
                EventBody.PLAY -> {
                    var heard = (r.listenedMs ?: 0L).coerceAtLeast(0L)
                    playMatch[r]?.let { old ->
                        replaces = playId(old)
                        heard = maxOf(heard, old.listenedMs)
                    }
                    EventBody.Play(track, heard)
                }
                EventBody.TAPE_PLAY -> {
                    var heard = (r.listenedMs ?: 0L).coerceAtLeast(0L)
                    tapeMatch[r]?.let { old ->
                        replaces = tapeId(old)
                        heard = maxOf(heard, LEGACY_TAPE_LISTENED_MS)
                    }
                    EventBody.TapePlay(track, heard, r.speedPct?.takeIf { it > 0 } ?: 100)
                }
                EventBody.SKIP -> EventBody.Skip(track, (r.positionMs ?: 0L).coerceAtLeast(0L))
                else -> EventBody.Queued(track)
            }
            events.add(Event(rowId(r), context(r, ts, zone, replaces), body))
        }

        val claimedPlays = playMatch.values.toSet()
        val claimedTapes = tapeMatch.values.toSet()
        var legacyCount = 0
        for (p in legacyPlays) {
            if (p in claimedPlays) continue
            val m = meta[p.trackId]
            val track = TrackRef(
                p.trackId, m?.title.orEmpty(), m?.artist.orEmpty(),
                (m?.durationSec ?: 0L).coerceAtLeast(0L) * 1000, m?.thumbnailUrl.orEmpty(),
            )
            events.add(
                Event(playId(p), EventContext.at(p.playedAt, zone, source = Source.IMPORT), EventBody.Play(track, p.listenedMs.coerceAtLeast(0L))),
            )
            legacyCount++
        }
        for (t in legacyTapes) {
            if (t in claimedTapes) continue
            val tape = TrackRef(t.videoId, t.title, t.channel, t.durationSec.coerceAtLeast(0L) * 1000, t.thumbnailUrl)
            events.add(
                Event(tapeId(t), EventContext.at(t.playedAt, zone, source = Source.IMPORT), EventBody.TapePlay(tape, LEGACY_TAPE_LISTENED_MS)),
            )
            legacyCount++
        }

        return MinimImportResult(
            events = events,
            fromFermataEvents = rows.size,
            fromLegacy = legacyCount,
            matched = claimedPlays.size + claimedTapes.size,
            skippedRows = skipped,
        )
    }

    /** The legacy id of an old `plays` row. Rows without a sync id get the one Minim would give. */
    fun playId(p: LegacyPlayRow): EventId =
        EventId.legacy("play|" + (p.eventId?.takeIf { it.isNotBlank() } ?: (java.lang.Long.toString(p.playedAt, 36) + "_" + p.trackId)))

    fun tapeId(t: LegacyTapePlayRow): EventId = EventId.legacy("tape|${t.videoId}|${t.playedAt}")

    /** `EventId(device_id, id)`; a row without a usable device id gets a legacy id from its content. */
    fun rowId(r: FermataEventRow): EventId {
        val device = r.deviceId?.trim()
        return if (device != null && device.isNotEmpty() && device != EventId.LEGACY_DEVICE && ':' !in device && r.id >= 1) {
            EventId(device, r.id)
        } else {
            EventId.legacy("fermata_events|${r.ts}|${r.type}|${r.trackId}|${r.id}")
        }
    }

    private val TYPES = setOf(EventBody.PLAY, EventBody.SKIP, EventBody.QUEUED, EventBody.TAPE_PLAY)

    private fun context(r: FermataEventRow, ts: Long, zone: ZoneId, replaces: EventId?): EventContext {
        val source = r.source?.trim()?.lowercase()?.takeIf { it in sources }?.let { Source.fromKey(it) } ?: Source.UNKNOWN
        val session = r.sessionId?.takeIf { it.isNotBlank() }
        val prev = r.prevTrackId?.takeIf { it.isNotBlank() }
        val hour = r.hour
        val dow = r.dow
        return if (hour != null && hour in 0..23 && dow != null && dow in 1..7) {
            EventContext(ts, hour, dow, session, prev, source, replaces)
        } else {
            EventContext.at(ts, zone, session, prev, source, replaces = replaces)
        }
    }

    private fun trackOf(r: FermataEventRow, meta: Map<String, LegacyTrackRow>): TrackRef {
        val m = meta[r.trackId]
        val duration = r.durationMs?.takeIf { it > 0 } ?: ((m?.durationSec ?: 0L).coerceAtLeast(0L) * 1000)
        return TrackRef(
            r.trackId,
            r.title?.takeIf { it.isNotBlank() } ?: m?.title.orEmpty(),
            r.artist?.takeIf { it.isNotBlank() } ?: m?.artist.orEmpty(),
            duration,
            m?.thumbnailUrl.orEmpty(),
        )
    }

    /**
     * Pairs 5.9.0 rows of [type] with old rows, one to one. A row's window runs from its start to
     * the start of the next play or tape row written by the same phone (capped at 12 hours): the
     * time it was the open row. An old row of the same track whose time falls in that window, the
     * earliest unclaimed one, is the same play. Repeat-one loops each have their own row and
     * their own old play, so they pair up loop by loop.
     */
    private fun <L : Any> match(
        rows: List<FermataEventRow>,
        type: String,
        legacy: List<L>,
        trackOf: (L) -> String,
        timeOf: (L) -> Long,
        idOf: (L) -> Long,
    ): Map<FermataEventRow, L> {
        if (legacy.isEmpty()) return emptyMap()
        val byTrack = legacy.groupBy(trackOf).mapValues { (_, l) ->
            l.sortedWith(compareBy(timeOf).thenBy(idOf)).toMutableList()
        }
        val opens = rows.filter { it.type == EventBody.PLAY || it.type == EventBody.TAPE_PLAY }
        val result = HashMap<FermataEventRow, L>()
        for ((_, devRows) in opens.groupBy { it.deviceId?.trim().orEmpty() }) {
            val ordered = devRows.sortedBy { it.id }
            ordered.forEachIndexed { i, r ->
                if (r.type != type) return@forEachIndexed
                val candidates = byTrack[r.trackId] ?: return@forEachIndexed
                val start = r.ts!!
                val next = ordered.getOrNull(i + 1)?.ts?.takeIf { it > start }
                val end = minOf(next ?: Long.MAX_VALUE, start + MAX_WINDOW_MS)
                val hit = candidates.firstOrNull { val t = timeOf(it); t >= start - START_SLACK_MS && t < end }
                if (hit != null) {
                    candidates.remove(hit)
                    result[r] = hit
                }
            }
        }
        return result
    }
}
