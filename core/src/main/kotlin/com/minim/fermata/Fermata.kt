package com.minim.fermata

import com.minim.fermata.backup.BackupArchive
import com.minim.fermata.backup.Manifest
import com.minim.fermata.library.LibraryProjector
import com.minim.fermata.library.LibraryState
import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventContext
import com.minim.fermata.log.EventId
import com.minim.fermata.log.EventLog
import com.minim.fermata.log.EventStore
import com.minim.fermata.log.Source
import com.minim.fermata.log.Subject
import com.minim.fermata.log.TasteEditKind
import com.minim.fermata.log.TrackRef
import com.minim.fermata.recs.Pick
import com.minim.fermata.recs.Ranker
import com.minim.fermata.recs.RecsConfig
import com.minim.fermata.recs.TasteModel
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.ZoneId

data class ScoredTrack(val track: TrackRef, val score: Double)

data class ScoredArtist(val name: String, val score: Double)

/** What the "your taste" screen shows. */
data class TasteView(val topTracks: List<ScoredTrack>, val topArtists: List<ScoredArtist>, val confidence: Double)

data class ImportResult(val manifest: Manifest, val added: Int, val total: Int)

/** What [Fermata.restore] did: [added] of the [total] events were new to this log. */
data class RestoreStats(val added: Int, val total: Int)

/**
 * Minim's on-device storage, backup and recommendation engine, in one object.
 *
 * ```
 * val fermata = Fermata(store = SqliteEventStore(context), deviceId = savedDeviceId)
 * fermata.record(EventBody.Play(track, listenedMs = 182_000), source = Source.RADIO)
 * val picks = fermata.rank(candidates)
 * ```
 *
 * Every change to the library is an event in [store]; [library] and the taste model are
 * projections rebuilt from it. Thread-safe.
 *
 * Clock rule: a new event's timestamp is never earlier than (newest timestamp in the log + 1 ms).
 * So an action taken after seeing another phone's event always sorts after it, even if this
 * phone's clock is behind, and a clock that jumps back cannot reorder this phone's own events.
 * Hour and day of week still come from the real wall clock.
 */
class Fermata(
    private val store: EventStore,
    val deviceId: String,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    val config: RecsConfig = RecsConfig(),
    /** A gap longer than this starts a new listening session. */
    private val sessionGapMs: Long = 30 * 60_000L,
) {
    private val lock = Any()
    private var counter: Long = store.heads()[deviceId] ?: 0L
    private var lastTimestamp: Long = store.maxTimestamp() ?: Long.MIN_VALUE
    private var session: String? = null
    private var lastWall: Long = Long.MIN_VALUE
    private var lastTrack: String? = null
    /** The track before [lastTrack], so a skip of the track just played keeps its own previous track. */
    private var lastTrackPrev: String? = null
    private var projector: LibraryProjector? = null
    private var model: TasteModel? = null
    private var librarySnapshot: LibraryState? = null

    /**
     * Records something that just happened on this phone. When [sessionId] is null Fermata keeps
     * the session itself (new session after [sessionGapMs] or more of silence); when
     * [previousTrackId] is null it fills in the track played or skipped before, within the
     * session. A skip of the track just played gets that play's previous track, as in Minim
     * 5.9.0's rows. Session ids are opaque strings: Fermata's own are `device.counter`, Minim
     * 5.9.0's are UUIDs, and nothing parses them.
     */
    fun record(
        body: EventBody,
        source: Source = Source.UNKNOWN,
        sessionId: String? = null,
        previousTrackId: String? = null,
    ): Event = synchronized(lock) {
        val wall = clock.millis()
        val ts = if (lastTimestamp == Long.MIN_VALUE) wall else maxOf(wall, lastTimestamp + 1)
        if (session == null || wall - lastWall >= sessionGapMs) {
            session = "$deviceId.${counter + 1}"
            lastTrack = null
            lastTrackPrev = null
        }
        val listenedTrack = listenedTrackId(body)
        val skipOfLast = body is EventBody.Skip && listenedTrack == lastTrack
        val prev = previousTrackId ?: when {
            listenedTrack == null -> null
            skipOfLast -> lastTrackPrev
            else -> lastTrack
        }
        val event = Event(
            EventId(deviceId, ++counter),
            EventContext.at(ts, zone, sessionId ?: session, prev, source, wallTime = wall),
            body,
        )
        store.append(event)
        lastTimestamp = ts
        lastWall = wall
        if (listenedTrack != null && !skipOfLast) {
            lastTrackPrev = prev
            lastTrack = listenedTrack
        }
        applyLocal(event)
        event
    }

    /**
     * Records something that happened in the past, for example a row of Minim's old tables.
     * Session tracking is left alone. Returns null when an event with that id already exists.
     */
    fun recordAt(
        body: EventBody,
        timestamp: Long,
        source: Source = Source.IMPORT,
        sessionId: String? = null,
        previousTrackId: String? = null,
        id: EventId? = null,
    ): Event? = synchronized(lock) {
        val eventId = id ?: EventId(deviceId, counter + 1)
        val event = Event(eventId, EventContext.at(timestamp, zone, sessionId, previousTrackId, source), body)
        if (!store.append(event)) return null
        // An imported id of this device (a 5.9.0 row, say) moves the counter past it, so the
        // next record() never reuses it.
        if (eventId.device == deviceId && eventId.counter > counter) counter = eventId.counter
        if (timestamp > lastTimestamp) lastTimestamp = timestamp
        applyLocal(event)
        event
    }

    /**
     * Imports one legacy record (old Minim tables or the Firestore copy) under a deterministic id
     * derived from [legacyKey], so importing the same history on two phones never doubles it.
     */
    fun importLegacy(body: EventBody, timestamp: Long, legacyKey: String, source: Source = Source.IMPORT): Event? =
        recordAt(body, timestamp, source, id = EventId.legacy(legacyKey))

    /** Merges events from elsewhere (another phone, a sync folder). Returns how many were new. */
    fun merge(events: Iterable<Event>): Int = synchronized(lock) {
        val added = store.appendAll(events)
        if (added > 0) {
            store.maxTimestamp()?.let { if (it > lastTimestamp) lastTimestamp = it }
            // Events of this device that came back (its own rows imported, say) move the counter on.
            store.heads()[deviceId]?.let { if (it > counter) counter = it }
            invalidate()
        }
        added
    }

    /**
     * Imports Minim's history once, when Fermata is first wired in: the 5.9.0 `fermata_events`
     * rows under their own ids (device id + row id), the old `plays` and `tape_plays` rows under
     * legacy ids, each play counted once. Safe to repeat. See [MinimImport].
     */
    fun importMinim(tables: MinimTables): MinimImportResult {
        val result = MinimImport.convert(tables, zone)
        return result.copy(added = merge(result.events))
    }

    /**
     * Restores events from a backup, all or nothing. First replays the log as it would be after
     * the restore, in memory, so events that cannot be applied are found before anything is
     * written; then stores the new events in one [EventStore.appendAll] (a single transaction in
     * [EventStore] implementations that persist); then rebuilds the library and taste model from
     * the log. If anything throws, the log, the projections, the counter and the clock are as
     * they were.
     *
     * Restored events keep their own ids. This phone keeps [deviceId]; if the backup holds events
     * with this phone's id, the counter moves past the highest of them so no id is reused.
     */
    fun restore(events: List<Event>): RestoreStats = synchronized(lock) {
        // Dry run: the same replay the rebuild below does, on the union, before writing anything.
        val dryRun = LibraryProjector(config.countedPlayMs)
        val dryModel = TasteModel(config)
        for (e in EventLog.effective(store.all() + events)) {
            dryRun.apply(e)
            dryModel.apply(e)
        }
        dryRun.snapshot()
        val added = store.appendAll(events)
        store.maxTimestamp()?.let { if (it > lastTimestamp) lastTimestamp = it }
        store.heads()[deviceId]?.let { if (it > counter) counter = it }
        invalidate()
        built()
        RestoreStats(added, events.size)
    }

    /** Drops the library and taste projections and replays the whole log now. */
    fun rebuild(): LibraryState = synchronized(lock) {
        invalidate()
        library()
    }

    /** How many events the log holds (0 on a fresh install). */
    fun eventCount(): Long = store.count()

    fun library(): LibraryState = synchronized(lock) {
        librarySnapshot ?: built().first.snapshot().also { librarySnapshot = it }
    }

    fun confidence(): Double = synchronized(lock) { built().second.confidence() }

    /**
     * Orders [candidates] (in the source's order, e.g. YouTube's radio) for this listener. At
     * zero confidence the input order comes back untouched.
     */
    fun rank(candidates: List<TrackRef>, now: Long = clock.millis()): List<Pick> = synchronized(lock) {
        Ranker.rank(candidates, built().second, now)
    }

    fun taste(limit: Int = 20, now: Long = clock.millis()): TasteView = synchronized(lock) {
        val m = built().second
        val tracks = library().tracks
        TasteView(
            topTracks = m.topTracks(limit, now).map { (id, s) -> ScoredTrack(tracks[id] ?: TrackRef(id), s) },
            topArtists = m.topArtists(limit, now).map { (name, s) -> ScoredArtist(name, s) },
            confidence = m.confidence(),
        )
    }

    /** A correction from the taste screen, recorded like any other event. */
    fun edit(kind: TasteEditKind, subject: Subject): Event = record(EventBody.TasteEdit(kind, subject))

    /** The version vector of this log: highest counter per device. */
    fun heads(): Map<String, Long> = store.heads()

    /** Events a peer with version vector [heads] has not seen yet. */
    fun eventsAfter(heads: Map<String, Long>): List<Event> = store.after(heads)

    /** Writes a `.minim` backup of the whole log to [out] (not closed). */
    fun export(out: OutputStream, appVersion: String? = null): Manifest =
        BackupArchive.write(out, store.all(), deviceId, clock.millis(), appVersion)

    /** Reads a `.minim` backup and merges it in. Safe to repeat: known events are skipped. */
    fun import(input: InputStream): ImportResult {
        val backup = BackupArchive.read(input)
        val added = merge(backup.events)
        return ImportResult(backup.manifest, added, backup.events.size)
    }

    private fun listenedTrackId(body: EventBody): String? = when (body) {
        is EventBody.Play -> body.track.id
        is EventBody.Skip -> body.track.id
        else -> null
    }

    private fun built(): Pair<LibraryProjector, TasteModel> {
        val p = projector
        val m = model
        if (p != null && m != null) return p to m
        val log = EventLog.effective(store.all())
        val newProjector = LibraryProjector(config.countedPlayMs)
        val newModel = TasteModel(config)
        for (e in log) {
            newProjector.apply(e)
            newModel.apply(e)
        }
        projector = newProjector
        model = newModel
        librarySnapshot = null
        return newProjector to newModel
    }

    private fun applyLocal(event: Event) {
        val p = projector ?: return
        val m = model ?: return
        // A replacing event, or a legacy one that may already be replaced, needs the full rebuild.
        if (event.ctx.replaces != null || event.id.device == EventId.LEGACY_DEVICE) return invalidate()
        if (p.apply(event)) {
            m.apply(event)
            librarySnapshot = null
        } else {
            invalidate()
        }
    }

    private fun invalidate() {
        projector = null
        model = null
        librarySnapshot = null
    }
}
