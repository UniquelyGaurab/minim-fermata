package com.minim.fermata.recs

import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventLog
import com.minim.fermata.log.Subject
import com.minim.fermata.log.TasteEditKind
import com.minim.fermata.log.TrackRef
import kotlin.math.exp
import kotlin.math.pow

/**
 * A simple decayed taste score per track and per artist, built from the log, plus the few
 * running aggregates the recommendations need ([Recommender]): when each track was last heard,
 * its newest counted plays, early-skip streaks, a long-memory "loved" score, and which tracks
 * belong to which artist. Context, co-play, fatigue and eras come later (see DESIGN.md).
 *
 * Everything is updated per event, so the model is built once from the log ([build]) and then
 * kept current by applying each new event; a request never scans the log. [build] applies events
 * in log order. Applying a phone's own new events a little out of time order gives the same
 * result as long as each track's events come in order: scores, counts and "last heard" do not
 * depend on order, skip streaks and likes depend on it per track only.
 *
 * Scores decay exponentially with [RecsConfig.halfLifeDays]. The model is a cache: it can always
 * be rebuilt from the log with [build]. Not thread-safe: callers serialise access.
 */
class TasteModel(val config: RecsConfig = RecsConfig()) {
    /** A score stored at a reference time; its value at any other time follows the decay. */
    private class Decayed(private val halfLifeMs: Double) {
        var value = 0.0
        var at = Long.MIN_VALUE

        fun add(weight: Double, t: Long) {
            when {
                at == Long.MIN_VALUE -> { value = weight; at = t }
                t >= at -> { value = value * decay(t - at) + weight; at = t }
                else -> value += weight * decay(at - t)
            }
        }

        fun valueAt(now: Long): Double = when {
            at == Long.MIN_VALUE -> 0.0
            now >= at -> value * decay(now - at)
            else -> value
        }

        fun scale(factor: Double, t: Long) {
            add(0.0, t)
            value *= factor
        }

        private fun decay(ageMs: Long): Double = 0.5.pow(ageMs / halfLifeMs)
    }

    /** The newest [size] timestamps added, oldest first. */
    private class Newest(size: Int) {
        val times = LongArray(size.coerceAtLeast(1))
        var count = 0

        fun add(t: Long) {
            if (count < times.size) {
                var i = count++
                while (i > 0 && times[i - 1] > t) { times[i] = times[i - 1]; i-- }
                times[i] = t
                return
            }
            if (t <= times[0]) return
            var i = 0
            while (i + 1 < times.size && times[i + 1] < t) { times[i] = times[i + 1]; i++ }
            times[i] = t
        }

        fun countSince(since: Long): Int {
            var n = 0
            for (i in 0 until count) if (times[i] >= since) n++
            return n
        }

        /** The oldest of the newest [n], or null with fewer than [n]. */
        fun nthNewest(n: Int): Long? = if (n in 1..count) times[count - n] else null
    }

    private inner class TrackStats {
        var ref: TrackRef? = null
        val score = Decayed(config.halfLifeMs)
        val love = Decayed(config.longHalfLifeMs)
        var plays = 0
        var lastHeardAt = Long.MIN_VALUE
        var likedAt = Long.MIN_VALUE
        val counted = Newest(maxOf(config.onRepeatMinPlays, RECENT_PLAYS_KEPT))
        var earlySkipStreak = 0
        var lastEarlySkipAt = Long.MIN_VALUE
    }

    private val tracks = HashMap<String, TrackStats>()
    private val artistScores = HashMap<String, Decayed>()
    private val artistNames = HashMap<String, String>()
    private val trackArtist = HashMap<String, String>()
    private val artistTracks = HashMap<String, LinkedHashSet<String>>()
    private val artistPlays = HashMap<String, Int>()
    private val artistEarlySkips = HashMap<String, Newest>()
    private val likedTracks = HashSet<String>()
    private val likedArtists = HashSet<String>()
    private val notInterested = HashSet<String>()

    /**
     * What a like or a "not interested" added, so its undo (unlike, "interested") takes away
     * exactly what is left of it after decay, and not the full weight again: [trackPart] went to
     * the track's scores and [artistPart] to [artistKey]'s, all at [at]. A later "just a phase"
     * or reset of the track or artist scales the parts the same way it scaled the scores.
     */
    private class Mark(val at: Long, var trackPart: Double, val artistKey: String?, var artistPart: Double)

    /** Undoable likes and "not interested", by [Subject.key] and kind. */
    private val likeMarks = HashMap<String, Mark>()
    private val notInterestedMarks = HashMap<String, Mark>()

    /** Signals counted towards confidence (plays, skips, queues, likes). */
    var signals: Long = 0
        private set

    /**
     * Counted plays (heard past the early-skip point, and [RecsConfig.countedPlayMs] or half the
     * track) and distinct tracks counted.
     */
    var countedPlays: Long = 0
        private set
    val countedTracks: Int get() = playedTracks
    private var playedTracks = 0

    /** Timestamp of the newest event applied, or Long.MIN_VALUE. */
    var lastAppliedAt: Long = Long.MIN_VALUE
        private set

    /** How much Fermata trusts itself, 0.0 to [RecsConfig.maxConfidence]. */
    fun confidence(): Double =
        if (signals <= 0) 0.0 else config.maxConfidence * (1 - exp(-signals / config.confidenceScale))

    fun apply(event: Event) {
        val w = config.weights
        val t = event.timestamp
        if (t > lastAppliedAt) lastAppliedAt = t
        when (val b = event.body) {
            // A play row with nothing heard (Minim 5.9.0 writes one for every track that left the
            // player) says nothing about taste; a skip, if any, is its own event.
            // A play that stopped before the early-skip point is an early skip, not a play
            // (Minim writes a play row as well as the skip): it counts nowhere, so the skip costs
            // its full weight and plays, "last heard", On repeat and cold start agree.
            is EventBody.Play -> if (b.listenedMs > 0 && config.heardPastEarlyPoint(b.listenedMs, b.track.durationMs)) {
                signals++
                val counted = b.listenedMs >= config.countedPlayMs ||
                    (b.track.durationMs > 0 && b.listenedMs >= b.track.durationMs / 2)
                var weight = if (counted) w.countedPlay else w.partialPlay
                if (b.track.durationMs > 0 && b.listenedMs >= b.track.durationMs * 0.9) weight += w.completionBonus
                val s = stats(b.track)
                if (counted) {
                    if (s.plays == 0) playedTracks++
                    s.plays++
                    countedPlays++
                    s.counted.add(t)
                    artistKey(b.track)?.let { artistPlays.merge(it, 1, Int::plus) }
                }
                if (t > s.lastHeardAt) s.lastHeardAt = t
                // Heard past the early-skip point: the early-skip streak is over.
                s.earlySkipStreak = 0
                addTrack(b.track, weight, t)
            }
            is EventBody.Skip -> {
                signals++
                val early = b.positionMs < config.earlySkipPointMs(b.track.durationMs)
                // A skip lowers the track only; the artist only through repeat early skips below.
                addTrack(b.track, if (early) w.earlySkip else w.lateSkip, t, artistShare = false)
                if (early) {
                    val s = stats(b.track)
                    s.earlySkipStreak++
                    s.lastEarlySkipAt = maxOf(s.lastEarlySkipAt, t)
                    artistKey(b.track)?.let { key ->
                        val skips = artistEarlySkips.getOrPut(key) { Newest(config.artistSkipsBeforePenalty) }
                        skips.add(t)
                        val nth = skips.nthNewest(config.artistSkipsBeforePenalty)
                        if (nth != null && t - nth <= config.artistSkipWindowDays * RecsConfig.DAY) {
                            artistScores.getOrPut(key) { Decayed(config.halfLifeMs) }.add(w.artistRepeatSkip, t)
                        }
                    }
                }
            }
            is EventBody.Queued -> {
                signals++
                addTrack(b.track, w.queued, t)
            }
            is EventBody.Like -> {
                signals++
                when (val s = b.subject) {
                    is Subject.Track -> if (likedTracks.add(s.track.id)) {
                        likeMarks[s.key] = addTrack(s.track, w.like, t)
                        stats(s.track).likedAt = t
                    }
                    is Subject.Artist -> if (likedArtists.add(s.key)) likeMarks[s.key] = addArtist(s.name, w.like, t)
                }
            }
            is EventBody.Unlike -> when (val s = b.subject) {
                is Subject.Track -> if (likedTracks.remove(s.track.id)) undo(likeMarks.remove(s.key), s, -w.like, t)
                is Subject.Artist -> if (likedArtists.remove(s.key)) undo(likeMarks.remove(s.key), s, -w.like, t)
            }
            // "Not interested" in a song lowers that song only, like a skip: not its artist.
            is EventBody.NotInterested -> if (notInterested.add(b.subject.key)) {
                notInterestedMarks[b.subject.key] = addSubject(b.subject, w.notInterested, t, artistShare = false)
            }
            is EventBody.Interested -> if (notInterested.remove(b.subject.key)) {
                undo(notInterestedMarks.remove(b.subject.key), b.subject, -w.notInterested, t)
            }
            is EventBody.TasteEdit -> when (b.kind) {
                TasteEditKind.OVER_IT -> addSubject(b.subject, w.overIt, t, artistShare = false)
                TasteEditKind.JUST_A_PHASE -> scale(b.subject, w.phaseFactor, t)
                TasteEditKind.RESET -> scale(b.subject, 0.0, t)
            }
            // The library events and tapes do not feed taste in this first version.
            else -> Unit
        }
    }

    fun trackScore(trackId: String, now: Long): Double = tracks[trackId]?.score?.valueAt(now) ?: 0.0

    fun artistScore(artist: String, now: Long): Double =
        artistScores[Subject.normalizeArtist(artist)]?.valueAt(now) ?: 0.0

    /** Track score plus a share of its artist's score. */
    fun score(track: TrackRef, now: Long): Double {
        val artist = artistNameOf(track)
        val artistPart = if (artist.isBlank()) 0.0 else artistScore(artist, now)
        return trackScore(track.id, now) + config.weights.artistShare * artistPart
    }

    /** The long-memory score ([RecsConfig.longHalfLifeDays]) behind "Forgotten favourites". */
    fun loveScore(trackId: String, now: Long): Double = tracks[trackId]?.love?.valueAt(now) ?: 0.0

    fun playCount(trackId: String): Int = tracks[trackId]?.plays ?: 0

    fun artistPlayCount(artist: String): Int = artistPlays[Subject.normalizeArtist(artist)] ?: 0

    fun isLiked(trackId: String): Boolean = trackId in likedTracks

    fun isArtistLiked(artist: String): Boolean = Subject.Artist(artist).key in likedArtists

    /** "Not interested" in the artist. */
    fun isArtistNotInterested(artist: String): Boolean =
        artist.isNotBlank() && Subject.Artist(artist).key in notInterested

    /** "Not interested" in the track itself or in its artist. */
    fun isNotInterested(track: TrackRef): Boolean {
        if (Subject.Track(track).key in notInterested) return true
        val artist = artistNameOf(track)
        return artist.isNotBlank() && Subject.Artist(artist).key in notInterested
    }

    /** When the track was last heard past the early-skip point, or null. */
    fun lastHeardAt(trackId: String): Long? = tracks[trackId]?.lastHeardAt?.takeIf { it != Long.MIN_VALUE }

    fun likedAt(trackId: String): Long? = tracks[trackId]?.likedAt?.takeIf { it != Long.MIN_VALUE }

    /** Counted plays within [sinceMs]..now, exact up to [RECENT_PLAYS_KEPT] (or [RecsConfig.onRepeatMinPlays]). */
    fun countedPlaysSince(trackId: String, sinceMs: Long): Int = tracks[trackId]?.counted?.countSince(sinceMs) ?: 0

    /** Early skips in a row (nothing heard past the early point in between). */
    fun earlySkipStreak(trackId: String): Int = tracks[trackId]?.earlySkipStreak ?: 0

    fun lastEarlySkipAt(trackId: String): Long? = tracks[trackId]?.lastEarlySkipAt?.takeIf { it != Long.MIN_VALUE }

    /** The newest title/artist/length seen for a track, or null for a track never seen. */
    fun trackRef(trackId: String): TrackRef? = tracks[trackId]?.ref

    /** Ids of every track the model knows. */
    fun trackIds(): Set<String> = tracks.keys

    /** The display name of an artist key or name, as last seen. */
    fun artistName(artist: String): String = artistNames[Subject.normalizeArtist(artist)] ?: artist.trim()

    /** The tracks seen for an artist (any spelling that normalises the same). */
    fun tracksOf(artist: String): Set<String> = artistTracks[Subject.normalizeArtist(artist)] ?: emptySet()

    /** Artist keys the model knows. */
    fun artistKeys(): Set<String> = artistScores.keys

    fun topTracks(limit: Int, now: Long): List<Pair<String, Double>> =
        tracks.map { it.key to it.value.score.valueAt(now) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).take(limit)

    fun topArtists(limit: Int, now: Long): List<Pair<String, Double>> =
        artistScores.map { (artistNames[it.key] ?: it.key) to it.value.valueAt(now) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).take(limit)

    private fun artistNameOf(track: TrackRef): String =
        track.artist.ifBlank { trackArtist[track.id]?.let { artistNames[it] } ?: "" }

    private fun artistKey(track: TrackRef): String? =
        if (track.artist.isBlank()) trackArtist[track.id] else Subject.normalizeArtist(track.artist)

    private fun stats(track: TrackRef): TrackStats {
        val s = tracks.getOrPut(track.id) { TrackStats() }
        val old = s.ref
        if (old == null) {
            s.ref = track
        } else if (track.title.isNotBlank() || track.artist.isNotBlank() || track.durationMs > 0) {
            s.ref = TrackRef(
                track.id,
                track.title.ifBlank { old.title },
                track.artist.ifBlank { old.artist },
                if (track.durationMs > 0) track.durationMs else old.durationMs,
            )
        }
        if (track.artist.isNotBlank()) {
            val key = Subject.normalizeArtist(track.artist)
            val prev = trackArtist.put(track.id, key)
            if (prev != null && prev != key) artistTracks[prev]?.remove(track.id)
            artistTracks.getOrPut(key) { LinkedHashSet() }.add(track.id)
            artistNames[key] = track.artist.trim()
        }
        return s
    }

    /** Adds [weight] to the track (and a share to its artist); returns what went where, for an undo. */
    private fun addTrack(track: TrackRef, weight: Double, t: Long, artistShare: Boolean = true): Mark {
        val s = stats(track)
        s.score.add(weight, t)
        s.love.add(weight, t)
        val key = if (artistShare) artistKey(track) else null
        val part = if (key != null) weight * config.weights.artistShare else 0.0
        if (key != null) artistScores.getOrPut(key) { Decayed(config.halfLifeMs) }.add(part, t)
        return Mark(t, weight, key, part)
    }

    private fun addArtist(name: String, weight: Double, t: Long): Mark {
        val key = Subject.normalizeArtist(name)
        artistNames.putIfAbsent(key, name.trim())
        artistScores.getOrPut(key) { Decayed(config.halfLifeMs) }.add(weight, t)
        return Mark(t, 0.0, key, weight)
    }

    private fun addSubject(subject: Subject, weight: Double, t: Long, artistShare: Boolean = true): Mark = when (subject) {
        is Subject.Track -> addTrack(subject.track, weight, t, artistShare)
        is Subject.Artist -> addArtist(subject.name, weight, t)
    }

    /**
     * Takes back what [mark] added: the same parts, at the time they were added, so only what is
     * left of them after decay goes. [fallback] at [t] only for a mark that was never kept.
     */
    private fun undo(mark: Mark?, subject: Subject, fallback: Double, t: Long) {
        if (mark == null) {
            addSubject(subject, fallback, t, artistShare = false)
            return
        }
        if (subject is Subject.Track && mark.trackPart != 0.0) {
            val s = stats(subject.track)
            s.score.add(-mark.trackPart, mark.at)
            s.love.add(-mark.trackPart, mark.at)
        }
        if (mark.artistKey != null && mark.artistPart != 0.0) {
            artistScores.getOrPut(mark.artistKey) { Decayed(config.halfLifeMs) }.add(-mark.artistPart, mark.at)
        }
    }

    private fun scale(subject: Subject, factor: Double, t: Long) {
        when (subject) {
            is Subject.Track -> {
                stats(subject.track).let { it.score.scale(factor, t); it.love.scale(factor, t) }
                likeMarks[subject.key]?.let { it.trackPart *= factor }
                notInterestedMarks[subject.key]?.let { it.trackPart *= factor }
            }
            is Subject.Artist -> {
                val key = Subject.normalizeArtist(subject.name)
                artistNames.putIfAbsent(key, subject.name.trim())
                artistScores.getOrPut(key) { Decayed(config.halfLifeMs) }.scale(factor, t)
                for (m in likeMarks.values) if (m.artistKey == key) m.artistPart *= factor
                for (m in notInterestedMarks.values) if (m.artistKey == key) m.artistPart *= factor
            }
        }
    }

    companion object {
        /** How many of each track's newest counted plays are kept (for "On repeat"). */
        const val RECENT_PLAYS_KEPT = 10

        /** The event types the model reads (and the clears that remove history). */
        val TYPES: Set<String> = setOf(
            EventBody.PLAY, EventBody.SKIP, EventBody.QUEUED, EventBody.LIKE, EventBody.UNLIKE,
            EventBody.NOT_INTERESTED, EventBody.INTERESTED, EventBody.TASTE_EDIT, EventBody.HISTORY_CLEAR,
        )

        /** Builds a model from a whole log (sorted, deduplicated and replaced events dropped first). */
        fun build(events: Iterable<Event>, config: RecsConfig = RecsConfig()): TasteModel {
            val model = TasteModel(config)
            for (e in EventLog.effective(events)) model.apply(e)
            return model
        }
    }
}
