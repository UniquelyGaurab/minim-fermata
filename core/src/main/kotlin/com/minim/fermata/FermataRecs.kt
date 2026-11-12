package com.minim.fermata

import com.minim.fermata.log.Event
import com.minim.fermata.log.EventId
import com.minim.fermata.log.EventStore
import com.minim.fermata.log.TrackRef
import com.minim.fermata.recs.ArtistPick
import com.minim.fermata.recs.Pick
import com.minim.fermata.recs.Recommender
import com.minim.fermata.recs.RecsConfig
import com.minim.fermata.recs.Reranked
import com.minim.fermata.recs.TasteModel
import java.time.Clock
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

/**
 * Recommendations for an app: a reordered radio, Discover shelves, and the cold-start check,
 * from a taste model kept in memory ([Fermata.recs]).
 *
 * Speed rules:
 * - The model is built once, by [warmUp] on a background thread the app chooses, from the
 *   taste events only ([TasteModel.TYPES]; an app's own writes are not even decoded). After that
 *   every event this phone records is applied to it as it is recorded: no request scans the log.
 * - Building does not hold Fermata's write lock, so recording (and the app's database writes)
 *   never waits for it. Events recorded while it builds are kept aside and applied at the end.
 * - Every request is non-blocking: until the model is ready it returns null, which means "keep
 *   your current order", and the same null comes back in cold start. Requests hold only this
 *   class's own short lock.
 * - A merge or import from elsewhere marks the model stale: requests keep using it (it only lacks
 *   the merged events) until the next [warmUp] rebuilds it. A history clear, a restore or a
 *   rolled-back transaction drops it, so nothing cleared is ever used.
 *
 * [now] defaults to Fermata's clock, so tests with a fixed clock are deterministic.
 */
class FermataRecs internal constructor(
    private val store: EventStore,
    private val writeLock: Lock,
    private val clock: Clock,
    val config: RecsConfig,
) {
    enum class State {
        /** Not built yet (or dropped): requests return null; call [warmUp] off the main thread. */
        NOT_READY,

        /** Built, but too little history: requests return null, apps keep their own behaviour. */
        COLD_START,

        READY,
    }

    /** The last build: events read, and milliseconds spent reading and replaying them. */
    data class BuildStats(val events: Int, val readMs: Double, val buildMs: Double)

    private val lock = Any()
    private val buildLock = ReentrantLock()
    private var model: TasteModel? = null
    private var stale = false
    private var generation = 0L
    private var pending: ArrayList<Event>? = null

    @Volatile var lastBuild: BuildStats? = null
        private set

    fun state(): State = synchronized(lock) {
        val m = model ?: return State.NOT_READY
        if (Recommender.isColdStart(m)) State.COLD_START else State.READY
    }

    /** True when [warmUp] has work to do: no model yet, or one that missed a merge. */
    fun needsWarmUp(): Boolean = synchronized(lock) { model == null || stale }

    /** The model being served, for tests. */
    internal fun modelForTest(): TasteModel? = synchronized(lock) { model }

    /**
     * Builds the model if it is missing or stale. Blocking, and safe from any thread except
     * while holding Fermata's write lock; run it on a background thread. Concurrent callers wait
     * for one build. Returns true when a model is ready afterwards.
     */
    fun warmUp(): Boolean {
        if (!needsWarmUp()) return true
        buildLock.lock()
        try {
            repeat(MAX_ATTEMPTS) {
                if (!needsWarmUp()) return true
                val gen = synchronized(lock) {
                    pending = ArrayList()
                    generation
                }
                // An app transaction that recorded events before [pending] existed holds the write
                // lock until it commits: wait for it, so its events are in what we read.
                writeLock.lock()
                writeLock.unlock()
                val t0 = System.nanoTime()
                val read = store.ofTypes(TasteModel.TYPES)
                val t1 = System.nanoTime()
                val built = TasteModel.build(read, config)
                val t2 = System.nanoTime()
                synchronized(lock) {
                    val extra = pending ?: ArrayList()
                    pending = null
                    if (generation == gen) {
                        model = withPending(built, read, extra)
                        stale = false
                        lastBuild = BuildStats(read.size, (t1 - t0) / 1e6, (t2 - t1) / 1e6)
                        return true
                    }
                }
            }
            return synchronized(lock) { model != null }
        } finally {
            buildLock.unlock()
        }
    }

    /** Applies events recorded during a build that the read did not see; rebuilds if any is out of order. */
    private fun withPending(built: TasteModel, read: List<Event>, extra: List<Event>): TasteModel {
        val taste = extra.filter { it.type in TasteModel.TYPES }
        if (taste.isEmpty()) return built
        val ids = taste.mapTo(HashSet()) { it.id }
        for (e in read) if (ids.remove(e.id) && ids.isEmpty()) break
        val missing = taste.filter { it.id in ids }
        if (missing.all { inOrder(it) }) {
            for (e in missing) built.apply(e)
            return built
        }
        return TasteModel.build(read + missing, config)
    }

    /**
     * Whether a newly recorded event can be applied to the live model as it is. An app may record
     * a play with its start time after events that came later (Minim records a play when the
     * next track starts); the model handles that, because what depends on order (skip streaks,
     * like and unlike) depends on it per track, and a phone records one track's events in order.
     * Corrections that replace an older event, legacy rows and clears need a rebuild.
     */
    private fun inOrder(e: Event): Boolean =
        e.ctx.replaces == null && e.id.device != EventId.LEGACY_DEVICE &&
            e.type != com.minim.fermata.log.EventBody.HISTORY_CLEAR

    /** Fermata calls this for every event this phone records, under its write lock. */
    internal fun recorded(e: Event) = synchronized(lock) {
        pending?.add(e)
        val m = model ?: return@synchronized
        if (e.type !in TasteModel.TYPES) return@synchronized
        if (inOrder(e)) m.apply(e) else stale = true
    }

    /** A merge or import ([drop] false: keep serving, rebuild later) or a clear/restore/rollback ([drop] true). */
    internal fun invalidate(drop: Boolean) = synchronized(lock) {
        generation++
        if (drop) {
            model = null
            stale = false
        } else if (model != null) {
            stale = true
        }
    }

    private inline fun <T> withModel(block: (TasteModel) -> T?): T? = synchronized(lock) {
        model?.let(block)
    }

    /**
     * A radio batch reordered by taste (input: the source's order, e.g. YouTube's radio), with
     * not-interested, skipped-out and recently heard tracks left out ([Recommender.radio]).
     * Null when not ready or in cold start: keep the batch as it is.
     */
    fun radio(candidates: List<TrackRef>, now: Long = clock.millis()): Reranked? =
        withModel { Recommender.radio(candidates, it, now) }

    /** Any other candidate list (a shelf) reordered by taste ([Recommender.rank]). Null: keep it. */
    fun rank(candidates: List<TrackRef>, now: Long = clock.millis()): Reranked? =
        withModel { Recommender.rank(candidates, it, now) }

    /** "On repeat" ([Recommender.onRepeat]). Null when not ready; empty in cold start. */
    fun onRepeat(limit: Int = 20, now: Long = clock.millis()): List<Pick>? =
        withModel { Recommender.onRepeat(it, now, limit) }

    /** "Forgotten favourites" ([Recommender.forgottenFavourites]). Null when not ready; empty in cold start. */
    fun forgottenFavourites(limit: Int = 20, now: Long = clock.millis()): List<Pick>? =
        withModel { Recommender.forgottenFavourites(it, now, limit) }

    /** Artists for "Because you like X" ([Recommender.becauseYouLike]). Null when not ready; empty in cold start. */
    fun becauseYouLike(artists: Int = 3, seedsPerArtist: Int = 5, now: Long = clock.millis()): List<ArtistPick>? =
        withModel { Recommender.becauseYouLike(it, now, artists, seedsPerArtist) }

    /** Heard within [RecsConfig.recentWindowMs]. False when not ready. */
    fun heardRecently(trackId: String, now: Long = clock.millis()): Boolean =
        withModel { Recommender.heardRecently(it, trackId, now) } ?: false

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
