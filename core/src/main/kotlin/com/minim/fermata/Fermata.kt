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
    private val lock = java.util.concurrent.locks.ReentrantLock()

    /**
     * Fermata's lock. An app that keeps the log inside its own database and opens its database
     * transaction before calling [record] (so the event commits with its own rows) takes this
     * lock first and releases it after its transaction ends, keeping one lock order (Fermata,
     * then the database) for every caller. [transaction] does this for a block; this is for apps
     * whose transactions do not fit in one block. Re-entrant. After a transaction that recorded
     * events was rolled back, call [rolledBack].
     */
    val writeLock: java.util.concurrent.locks.Lock get() = lock

    @OptIn(kotlin.contracts.ExperimentalContracts::class)
    private inline fun <T> synchronized(@Suppress("UNUSED_PARAMETER") l: Any, block: () -> T): T {
        kotlin.contracts.contract { callsInPlace(block, kotlin.contracts.InvocationKind.EXACTLY_ONCE) }
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
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
    /** The newest history clear point in the log; read from the store on first use. */
    private var clearPoint: Long? = null
    private var clearPointRead = false

    /**
     * Recommendations: a reordered radio, shelves and the cold-start check, from a taste model
     * kept up to date as events are recorded. Built off the write lock by [FermataRecs.warmUp];
     * requests never block. See [FermataRecs].
     */
    val recs: FermataRecs = FermataRecs(store, lock, clock, config)

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
        /**
         * The time to record at, normally left out (now). An app that has to store the time inside
         * the event body takes it from [timestamp] first and passes it here, so both agree. The
         * clock rule still applies: never earlier than the newest event + 1 ms.
         */
        at: Long? = null,
    ): Event = synchronized(lock) {
        val wall = at ?: clock.millis()
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
        recs.recorded(event)
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
        // History from before a "clear listening history" never comes back, not even by import.
        if (EventLog.isCleared(event, clearedBefore())) return null
        if (!store.append(event)) return null
        // An imported id of this device (a 5.9.0 row, say) moves the counter past it, so the
        // next record() never reuses it.
        if (eventId.device == deviceId && eventId.counter > counter) counter = eventId.counter
        if (timestamp > lastTimestamp) lastTimestamp = timestamp
        applyLocal(event)
        recs.recorded(event)
        event
    }

    /**
     * Imports one legacy record (old Minim tables or the Firestore copy) under a deterministic id
     * derived from [legacyKey], so importing the same history on two phones never doubles it.
     */
    fun importLegacy(body: EventBody, timestamp: Long, legacyKey: String, source: Source = Source.IMPORT): Event? =
        recordAt(body, timestamp, source, id = EventId.legacy(legacyKey))

    /**
     * Merges events from elsewhere (another phone, a sync folder). Returns how many were new.
     * Listening history older than the newest history clear (this log's or one arriving with
     * [events]) is left out, and when the clear is new here, the history it covers is deleted.
     */
    fun merge(events: Iterable<Event>): Int = mergeAs(events, ChangeOrigin.MERGE)

    /**
     * Adds events an app built from its own old data (legacy ids, see [EventId.legacy]) in one
     * batch, with [merge]'s rules. Reported to listeners as [ChangeOrigin.IMPORT]. Safe to repeat.
     */
    fun importEvents(events: Iterable<Event>): Int = mergeAs(events, ChangeOrigin.IMPORT)

    private fun mergeAs(events: Iterable<Event>, origin: ChangeOrigin): Int {
        var change: Change? = null
        var added = 0
        synchronized(lock) {
            val incoming = events.toList()
            val apply = applier
            try {
                store.inTransaction {
                    val pruned = applyClears(incoming)
                    val local = clearedBefore()
                    val candidates = if (local == null) incoming else incoming.filterNot { EventLog.isCleared(it, local) }
                    val fresh = if (listeners.isEmpty() && apply == null) emptyList()
                    else candidates.filterNot { store.contains(it.id) }.distinctBy { it.id }
                    added = store.appendAll(candidates)
                    if (added > 0) {
                        store.maxTimestamp()?.let { if (it > lastTimestamp) lastTimestamp = it }
                        // Events of this device that came back (its own rows imported, say) move the counter on.
                        store.heads()[deviceId]?.let { if (it > counter) counter = it }
                    }
                    if (pruned || added > 0) {
                        // Only taste events make the recommendations model stale.
                        if (pruned || candidates.any { it.type in TasteModel.TYPES }) invalidate(dropRecs = pruned)
                        else invalidateProjections()
                    }
                    val c = Change(origin, fresh, historyCleared = pruned)
                    if (apply != null && (added > 0 || pruned)) apply(c)
                    change = c
                }
            } catch (t: Throwable) {
                // The store transaction rolled back: the log is as it was, so is anything the
                // applier wrote. The counter and clock only move forward; nothing is reused.
                clearPointRead = false
                invalidate(dropRecs = true)
                throw t
            }
        }
        val c = change!!
        if (added > 0 || c.historyCleared) notify(c)
        return added
    }

    @Volatile private var applier: ((Change) -> Unit)? = null

    /**
     * Sets the code that brings a merge, import or restore into the app's own tables. Unlike a
     * listener it runs inside Fermata's lock and inside the store transaction that adds the
     * events ([EventStore.inTransaction]), after they are added, so with a store kept in the
     * app's database (`SqliteEventStore.inDatabase`) the log and the app's tables are saved
     * together: a kill leaves both or neither. If it throws, the merge, import or restore throws
     * and nothing of it is kept, so the next one brings the same events again. It may call
     * [record]. Null removes it.
     */
    fun setApplier(applier: ((Change) -> Unit)?) { this.applier = applier }

    /**
     * Makes sure [record] never hands out an id of this phone at or below [counter]. For ids the
     * app knows are taken before they reach the log, such as 5.9.0's `fermata_events` rows that
     * a later import brings in under their own ids: a write before that import would otherwise
     * take one of them, and the import would then drop the old row as already seen.
     */
    fun reserveCountersThrough(counter: Long) = synchronized(lock) {
        if (counter > this.counter) this.counter = counter
    }

    /** Where a batch of new events came from; see [addListener]. */
    enum class ChangeOrigin { MERGE, IMPORT, RESTORE }

    /**
     * Events that reached the log from outside the app's own [record] calls. [added] lists the
     * events that were new (empty when no listener was registered at the time). [historyCleared]
     * is true when a "clear listening history" from elsewhere deleted history here, so anything
     * built from the log needs a full rebuild.
     */
    data class Change(val origin: ChangeOrigin, val added: List<Event>, val historyCleared: Boolean)

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Change) -> Unit>()

    /**
     * Calls [listener] after every merge, import or restore that changed the log (also from the
     * background backup job), outside Fermata's lock. The app's own [record] calls are not
     * reported: the app knows about them already.
     */
    fun addListener(listener: (Change) -> Unit) { listeners += listener }

    fun removeListener(listener: (Change) -> Unit) { listeners -= listener }

    private fun notify(change: Change) {
        for (l in listeners) runCatching { l(change) }
    }

    /**
     * Imports Minim's history once, when Fermata is first wired in: the 5.9.0 `fermata_events`
     * rows under their own ids (device id + row id), the old `plays` and `tape_plays` rows under
     * legacy ids, each play counted once. Safe to repeat. See [MinimImport].
     */
    fun importMinim(tables: MinimTables): MinimImportResult {
        val result = MinimImport.convert(tables, zone)
        return result.copy(added = mergeAs(result.events, ChangeOrigin.IMPORT))
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
     *
     * The [setApplier] code runs inside the same store transaction, so the app's tables are
     * restored together with the log; if it throws, the restore throws and nothing is kept.
     */
    fun restore(events: List<Event>): RestoreStats {
        val stats = restoreLocked(events)
        notify(Change(ChangeOrigin.RESTORE, emptyList(), historyCleared = true))
        return stats
    }

    private fun restoreLocked(events: List<Event>): RestoreStats = synchronized(lock) {
        // Dry run: the same replay the rebuild below does, on the union, before writing anything.
        val dryRun = LibraryProjector(config.countedPlayMs)
        val dryModel = TasteModel(config)
        for (e in EventLog.effective(store.all() + events)) {
            dryRun.apply(e)
            dryModel.apply(e)
        }
        dryRun.snapshot()
        val apply = applier
        try {
            store.inTransaction {
                // A history clear on either side wins: this phone's clear is kept, and the backup's
                // history from before it is not written back.
                val point = listOfNotNull(clearedBefore(), EventLog.clearedBefore(events)).maxOrNull()
                val added = store.appendAll(if (point == null) events else events.filterNot { EventLog.isCleared(it, point) })
                applyClears(events)
                store.maxTimestamp()?.let { if (it > lastTimestamp) lastTimestamp = it }
                store.heads()[deviceId]?.let { if (it > counter) counter = it }
                invalidate(dropRecs = true)
                apply?.invoke(Change(ChangeOrigin.RESTORE, emptyList(), historyCleared = true))
                built()
                RestoreStats(added, events.size)
            }
        } catch (t: Throwable) {
            clearPointRead = false
            invalidate(dropRecs = true)
            throw t
        }
    }

    /**
     * Runs [block] while holding Fermata's lock. An app that keeps the log inside its own
     * database (see `SqliteEventStore.inDatabase`) opens its database transaction inside [block]
     * and calls [record] there: the lock is re-entrant, and taking it first keeps one lock order
     * (Fermata, then the database) for every caller, so a write on the main path can never
     * deadlock against a merge or restore running on a background thread. If [block] throws,
     * the in-memory projections are dropped so they are rebuilt from what the store really holds.
     */
    /**
     * Call after an app database transaction that recorded events was rolled back: the in-memory
     * projections are dropped and rebuilt from what the store really holds. The counter and clock
     * only ever move forward, so a rolled-back id is never reused.
     */
    fun rolledBack() = synchronized(lock) {
        clearPointRead = false
        invalidate(dropRecs = true)
    }

    fun <T> transaction(block: () -> T): T = synchronized(lock) {
        try {
            block()
        } catch (t: Throwable) {
            rolledBack()
            throw t
        }
    }

    /**
     * The timestamp an event recorded now would get under the clock rule (never earlier than
     * the newest event + 1 ms), without recording anything. For times an app stores inside an
     * event body, such as [EventBody.Play.countedAt].
     */
    fun timestamp(): Long = synchronized(lock) {
        val wall = clock.millis()
        if (lastTimestamp == Long.MIN_VALUE) wall else maxOf(wall, lastTimestamp + 1)
    }

    /**
     * "Clear listening history": records a [EventBody.HistoryClear] covering everything before
     * now and deletes the listening events it covers from the store. Every phone that merges or
     * restores this event drops the same history, whichever order things arrive in. Likes,
     * playlists and "not interested" stay.
     */
    fun clearHistory(): Event = clearHistory(Long.MAX_VALUE)!!

    /**
     * Records a history clear at [before] (capped at now), for a clear that reached the app some
     * other way, such as from Firestore. Returns null, recording nothing, when the log already
     * has a clear at or after [before].
     */
    fun clearHistory(before: Long): Event? = synchronized(lock) {
        val now = timestamp()
        val point = minOf(before, now)
        if (before != Long.MAX_VALUE) clearedBefore()?.let { if (it >= point) return null }
        val event = record(EventBody.HistoryClear(point), at = now)
        clearPoint = maxOf(clearPoint ?: point, point)
        clearPointRead = true
        store.removeHistoryBefore(point)
        invalidate(dropRecs = true)
        event
    }

    /** The newest history clear point in the log, or null when history was never cleared. */
    fun clearedBefore(): Long? = synchronized(lock) {
        if (!clearPointRead) {
            clearPoint = store.clearedBefore()
            clearPointRead = true
        }
        clearPoint
    }

    /**
     * Deletes listening history covered by the newest clear point (the log's, or one in
     * [incoming] that is newer). Safe to repeat; a no-op when nothing is covered. Returns true
     * when the clear point moved or anything was deleted.
     */
    fun pruneCleared(): Boolean = synchronized(lock) { applyClears(emptyList()).also { if (it) invalidate(dropRecs = true) } }

    private fun applyClears(incoming: List<Event>): Boolean {
        val local = clearedBefore()
        val arriving = EventLog.clearedBefore(incoming)
        val point = listOfNotNull(local, arriving).maxOrNull() ?: return false
        val moved = local == null || point > local
        if (moved) clearPoint = point
        val removed = store.removeHistoryBefore(point)
        return moved || removed > 0
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

    /**
     * The version vector of the events in the log now, as a backup's manifest records it: like
     * [heads], but without counters kept only from deleted or rolled-back events.
     */
    fun loggedHeads(): Map<String, Long> = store.loggedHeads()

    /** Events a peer with version vector [heads] has not seen yet. */
    fun eventsAfter(heads: Map<String, Long>): List<Event> = store.after(heads)

    /**
     * Like [eventsAfter] without the legacy events (imported old rows, which have no counters):
     * the events of real devices past [heads]. What a backup piece holds.
     */
    fun deviceEventsAfter(heads: Map<String, Long>): List<Event> = store.deviceEventsAfter(heads)

    /** Every stored event, in log order. */
    fun events(): List<Event> = store.all()

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
        if (event.ctx.replaces != null || event.id.device == EventId.LEGACY_DEVICE) return invalidateProjections()
        if (p.apply(event)) {
            m.apply(event)
            librarySnapshot = null
        } else {
            invalidateProjections()
        }
    }

    /** Drops the projections. [dropRecs]: drop the recommendations model too (else it goes stale). */
    private fun invalidate(dropRecs: Boolean = false) {
        invalidateProjections()
        recs.invalidate(dropRecs)
    }

    /** Only the library and taste projections (the recommendations model keeps its own rules, [FermataRecs.recorded]). */
    private fun invalidateProjections() {
        projector = null
        model = null
        librarySnapshot = null
    }
}
