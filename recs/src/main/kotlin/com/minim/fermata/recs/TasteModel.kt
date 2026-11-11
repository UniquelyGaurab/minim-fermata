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
 * A simple decayed taste score per track and per artist, built from the log. This is the first,
 * deliberately small model; context, co-play, fatigue and eras come later (see DESIGN.md).
 *
 * Scores decay exponentially with [RecsConfig.halfLifeDays]. The model is a cache: it can always
 * be rebuilt from the log with [build].
 */
class TasteModel(val config: RecsConfig = RecsConfig()) {
    /** A score stored at a reference time; its value at any other time follows the decay. */
    private inner class Decayed {
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
    }

    private val trackScores = HashMap<String, Decayed>()
    private val artistScores = HashMap<String, Decayed>()
    private val artistNames = HashMap<String, String>()
    private val trackArtist = HashMap<String, String>()
    private val trackPlays = HashMap<String, Int>()
    private val artistPlays = HashMap<String, Int>()
    private val likedTracks = HashSet<String>()
    private val likedArtists = HashSet<String>()
    private val notInterested = HashSet<String>()

    /** Signals counted towards confidence (plays, skips, queues, likes). */
    var signals: Long = 0
        private set

    /** How much Fermata trusts itself, 0.0 to [RecsConfig.maxConfidence]. */
    fun confidence(): Double =
        if (signals <= 0) 0.0 else config.maxConfidence * (1 - exp(-signals / config.confidenceScale))

    fun apply(event: Event) {
        val w = config.weights
        val t = event.timestamp
        when (val b = event.body) {
            is EventBody.Play -> {
                signals++
                val counted = b.listenedMs >= config.countedPlayMs ||
                    (b.track.durationMs > 0 && b.listenedMs >= b.track.durationMs / 2)
                var weight = if (counted) w.countedPlay else w.partialPlay
                if (b.track.durationMs > 0 && b.listenedMs >= b.track.durationMs * 0.9) weight += w.completionBonus
                if (counted) {
                    trackPlays.merge(b.track.id, 1, Int::plus)
                    artistKey(b.track)?.let { artistPlays.merge(it, 1, Int::plus) }
                }
                addTrack(b.track, weight, t)
            }
            is EventBody.Skip -> {
                signals++
                addTrack(b.track, if (b.positionMs < config.earlySkipMs) w.earlySkip else w.lateSkip, t)
            }
            is EventBody.Queued -> {
                signals++
                addTrack(b.track, w.queued, t)
            }
            is EventBody.Like -> {
                signals++
                when (val s = b.subject) {
                    is Subject.Track -> if (likedTracks.add(s.track.id)) addTrack(s.track, w.like, t)
                    is Subject.Artist -> if (likedArtists.add(s.key)) addArtist(s.name, w.like, t)
                }
            }
            is EventBody.Unlike -> when (val s = b.subject) {
                is Subject.Track -> if (likedTracks.remove(s.track.id)) addTrack(s.track, -w.like, t)
                is Subject.Artist -> if (likedArtists.remove(s.key)) addArtist(s.name, -w.like, t)
            }
            is EventBody.NotInterested -> if (notInterested.add(b.subject.key)) addSubject(b.subject, w.notInterested, t)
            is EventBody.Interested -> if (notInterested.remove(b.subject.key)) addSubject(b.subject, -w.notInterested, t)
            is EventBody.TasteEdit -> when (b.kind) {
                TasteEditKind.OVER_IT -> addSubject(b.subject, w.overIt, t, artistShare = false)
                TasteEditKind.JUST_A_PHASE -> scoreOf(b.subject).scale(w.phaseFactor, t)
                TasteEditKind.RESET -> scoreOf(b.subject).scale(0.0, t)
            }
            // The library events and tapes do not feed taste in this first version.
            else -> Unit
        }
    }

    fun trackScore(trackId: String, now: Long): Double = trackScores[trackId]?.valueAt(now) ?: 0.0

    fun artistScore(artist: String, now: Long): Double =
        artistScores[Subject.normalizeArtist(artist)]?.valueAt(now) ?: 0.0

    /** Track score plus a share of its artist's score. */
    fun score(track: TrackRef, now: Long): Double {
        val artist = track.artist.ifBlank { trackArtist[track.id]?.let { artistNames[it] } ?: "" }
        val artistPart = if (artist.isBlank()) 0.0 else artistScore(artist, now)
        return trackScore(track.id, now) + config.weights.artistShare * artistPart
    }

    fun playCount(trackId: String): Int = trackPlays[trackId] ?: 0

    fun artistPlayCount(artist: String): Int = artistPlays[Subject.normalizeArtist(artist)] ?: 0

    fun isLiked(trackId: String): Boolean = trackId in likedTracks

    fun isArtistLiked(artist: String): Boolean = Subject.Artist(artist).key in likedArtists

    fun topTracks(limit: Int, now: Long): List<Pair<String, Double>> =
        trackScores.map { it.key to it.value.valueAt(now) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).take(limit)

    fun topArtists(limit: Int, now: Long): List<Pair<String, Double>> =
        artistScores.map { (artistNames[it.key] ?: it.key) to it.value.valueAt(now) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).take(limit)

    private fun decay(ageMs: Long): Double = 0.5.pow(ageMs / config.halfLifeMs)

    private fun artistKey(track: TrackRef): String? =
        if (track.artist.isBlank()) trackArtist[track.id] else Subject.normalizeArtist(track.artist)

    private fun addTrack(track: TrackRef, weight: Double, t: Long, artistShare: Boolean = true) {
        trackScores.getOrPut(track.id) { Decayed() }.add(weight, t)
        if (track.artist.isNotBlank()) {
            val key = Subject.normalizeArtist(track.artist)
            trackArtist[track.id] = key
            artistNames[key] = track.artist.trim()
        }
        if (artistShare) artistKey(track)?.let { artistScores.getOrPut(it) { Decayed() }.add(weight * config.weights.artistShare, t) }
    }

    private fun addArtist(name: String, weight: Double, t: Long) {
        val key = Subject.normalizeArtist(name)
        artistNames.putIfAbsent(key, name.trim())
        artistScores.getOrPut(key) { Decayed() }.add(weight, t)
    }

    private fun addSubject(subject: Subject, weight: Double, t: Long, artistShare: Boolean = true) = when (subject) {
        is Subject.Track -> addTrack(subject.track, weight, t, artistShare)
        is Subject.Artist -> addArtist(subject.name, weight, t)
    }

    private fun scoreOf(subject: Subject): Decayed = when (subject) {
        is Subject.Track -> trackScores.getOrPut(subject.track.id) { Decayed() }
        is Subject.Artist -> {
            val key = Subject.normalizeArtist(subject.name)
            artistNames.putIfAbsent(key, subject.name.trim())
            artistScores.getOrPut(key) { Decayed() }
        }
    }

    companion object {
        /** Builds a model from a whole log (sorted and deduplicated first). */
        fun build(events: Iterable<Event>, config: RecsConfig = RecsConfig()): TasteModel {
            val model = TasteModel(config)
            for (e in EventLog.canonical(events)) model.apply(e)
            return model
        }
    }
}
