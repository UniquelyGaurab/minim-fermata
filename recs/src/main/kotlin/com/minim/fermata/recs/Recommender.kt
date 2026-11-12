package com.minim.fermata.recs

import com.minim.fermata.log.TrackRef

/** Why a candidate was left out. */
enum class DropReason {
    /** "Not interested" in the track or its artist. */
    NOT_INTERESTED,

    /** Skipped early [RecsConfig.dropAfterEarlySkips] times in a row, recently ([RecsConfig.skipMemoryDays]). */
    SKIPPED_EARLY,

    /** Heard within [RecsConfig.recentWindowMs] (radio only). */
    HEARD_RECENTLY,
}

data class Dropped(val track: TrackRef, val reason: DropReason)

/** A reordered candidate list: [picks] in the new order, and what was left out. */
data class Reranked(val picks: List<Pick>, val dropped: List<Dropped>) {
    val tracks: List<TrackRef> get() = picks.map { it.track }
}

/** An artist for "Because you like X", with the person's own top tracks of that artist as seeds. */
data class ArtistPick(val artist: String, val score: Double, val seeds: List<TrackRef>)

/**
 * What the apps ask Fermata for: a reordered radio, shelf contents, and whether there is enough
 * history to do either ([isColdStart]). Pure functions of a [TasteModel] and a time, so they
 * are deterministic and cheap: every input is a running aggregate of the model, nothing scans
 * the log. Candidate lists cost O(n); the shelves walk the model's tracks once.
 *
 * Cold start: the radio and shelf ranking return null, which means "keep your own order".
 */
object Recommender {
    /** Too little history to trust: fewer counted plays or distinct tracks than the config asks for. */
    fun isColdStart(model: TasteModel): Boolean {
        val c = model.config
        return model.countedPlays < c.coldStartMinPlays || model.countedTracks < c.coldStartMinTracks
    }

    /** The confidence the radio and shelves blend with past cold start. */
    fun blendConfidence(model: TasteModel): Double =
        maxOf(model.confidence(), model.config.minWarmConfidence).coerceIn(0.0, 1.0)

    /** Skipped early enough times in a row, and the last of them within the skip memory. */
    fun skippedOut(model: TasteModel, trackId: String, now: Long): Boolean {
        val c = model.config
        if (model.earlySkipStreak(trackId) < c.dropAfterEarlySkips) return false
        val last = model.lastEarlySkipAt(trackId) ?: return false
        return now - last <= c.skipMemoryDays * RecsConfig.DAY
    }

    /** Heard within the recent window (or "in the future", after the clock went back). */
    fun heardRecently(model: TasteModel, trackId: String, now: Long): Boolean {
        val last = model.lastHeardAt(trackId) ?: return false
        return last >= now - model.config.recentWindowMs
    }

    /**
     * Reorders a radio batch (YouTube's order in, best first) by taste, and leaves out tracks
     * the person is not interested in, has skipped out, or heard in the last few hours. When
     * that leaves fewer than [RecsConfig.radioMinKeep], recently heard tracks come back at the
     * end (in their input order) up to that number, so a radio never runs dry. Null in cold start.
     */
    fun radio(candidates: List<TrackRef>, model: TasteModel, now: Long): Reranked? {
        if (isColdStart(model)) return null
        val keep = ArrayList<TrackRef>(candidates.size)
        val recent = ArrayList<TrackRef>()
        val dropped = ArrayList<Dropped>()
        for (t in candidates) {
            when {
                model.isNotInterested(t) -> dropped += Dropped(t, DropReason.NOT_INTERESTED)
                skippedOut(model, t.id, now) -> dropped += Dropped(t, DropReason.SKIPPED_EARLY)
                heardRecently(model, t.id, now) -> recent += t
                else -> keep += t
            }
        }
        val back = recent.take((model.config.radioMinKeep - keep.size).coerceAtLeast(0))
        for (t in recent.drop(back.size)) dropped += Dropped(t, DropReason.HEARD_RECENTLY)
        val picks = Ranker.rank(keep, model, now, blendConfidence(model)) +
            back.map { Pick(it, model.score(it, now), Ranker.reason(it, model)) }
        return Reranked(picks, dropped)
    }

    /**
     * Reorders any other candidate list (a Discover shelf) by taste, leaving out tracks the
     * person is not interested in or has skipped out. No recent-play filter. Null in cold start.
     */
    fun rank(candidates: List<TrackRef>, model: TasteModel, now: Long): Reranked? {
        if (isColdStart(model)) return null
        val keep = ArrayList<TrackRef>(candidates.size)
        val dropped = ArrayList<Dropped>()
        for (t in candidates) {
            when {
                model.isNotInterested(t) -> dropped += Dropped(t, DropReason.NOT_INTERESTED)
                skippedOut(model, t.id, now) -> dropped += Dropped(t, DropReason.SKIPPED_EARLY)
                else -> keep += t
            }
        }
        return Reranked(Ranker.rank(keep, model, now, blendConfidence(model)), dropped)
    }

    /**
     * "On repeat": tracks counted at least [RecsConfig.onRepeatMinPlays] times in the last
     * [RecsConfig.onRepeatDays], most plays first (then taste, then id). Empty in cold start.
     */
    fun onRepeat(model: TasteModel, now: Long, limit: Int = 20): List<Pick> {
        if (isColdStart(model) || limit <= 0) return emptyList()
        val c = model.config
        val since = now - (c.onRepeatDays * RecsConfig.DAY).toLong()
        val rows = ArrayList<Triple<TrackRef, Int, Double>>()
        for (id in model.trackIds()) {
            val n = model.countedPlaysSince(id, since)
            if (n < c.onRepeatMinPlays) continue
            val ref = model.trackRef(id) ?: continue
            if (model.isNotInterested(ref) || skippedOut(model, id, now)) continue
            val score = model.score(ref, now)
            if (score <= 0) continue
            rows += Triple(ref, n, score)
        }
        return rows.sortedWith(
            compareByDescending<Triple<TrackRef, Int, Double>> { it.second }.thenByDescending { it.third }.thenBy { it.first.id }
        ).take(limit).map { (ref, n, score) -> Pick(ref, score, "You've played this $n times lately") }
    }

    /**
     * "Forgotten favourites": tracks the person loved (liked, or counted
     * [RecsConfig.forgottenMinPlays]+ times) that they have not heard for
     * [RecsConfig.forgottenAfterDays], by the long-memory love score. A like newer than that
     * window does not count yet. Empty in cold start.
     */
    fun forgottenFavourites(model: TasteModel, now: Long, limit: Int = 20): List<Pick> {
        if (isColdStart(model) || limit <= 0) return emptyList()
        val c = model.config
        val before = now - (c.forgottenAfterDays * RecsConfig.DAY).toLong()
        val rows = ArrayList<Pair<TrackRef, Double>>()
        for (id in model.trackIds()) {
            val heard = model.lastHeardAt(id)
            if (heard != null && heard > before) continue
            val likedLongAgo = model.isLiked(id) && (model.likedAt(id) ?: Long.MAX_VALUE) <= before
            if (!likedLongAgo && model.playCount(id) < c.forgottenMinPlays) continue
            val ref = model.trackRef(id) ?: continue
            if (model.isNotInterested(ref) || skippedOut(model, id, now)) continue
            val love = model.loveScore(id, now)
            if (love <= 0) continue
            rows += ref to love
        }
        return rows.sortedWith(compareByDescending<Pair<TrackRef, Double>> { it.second }.thenBy { it.first.id })
            .take(limit).map { (ref, love) -> Pick(ref, love, Ranker.reason(ref, model)) }
    }

    /**
     * "Because you like X": the person's top artists by taste (liked, or counted
     * [RecsConfig.becauseMinArtistPlays]+ times), each with up to [seedsPerArtist] of their own
     * best tracks of that artist, for the app to seed a mix from. Empty in cold start.
     */
    fun becauseYouLike(model: TasteModel, now: Long, artists: Int = 3, seedsPerArtist: Int = 5): List<ArtistPick> {
        if (isColdStart(model) || artists <= 0) return emptyList()
        val c = model.config
        val rows = ArrayList<Pair<String, Double>>()
        for (key in model.artistKeys()) {
            val name = model.artistName(key)
            if (name.isBlank()) continue
            if (model.artistPlayCount(name) < c.becauseMinArtistPlays && !model.isArtistLiked(name)) continue
            if (model.isArtistNotInterested(name)) continue
            val score = model.artistScore(name, now)
            if (score > 0) rows += name to score
        }
        rows.sortWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first.lowercase() })
        val out = ArrayList<ArtistPick>()
        for ((name, score) in rows) {
            if (out.size >= artists) break
            val seeds = model.tracksOf(name).mapNotNull { id ->
                val ref = model.trackRef(id) ?: return@mapNotNull null
                if (model.isNotInterested(ref) || skippedOut(model, id, now)) return@mapNotNull null
                val s = model.trackScore(id, now)
                if (s > 0 || model.isLiked(id)) ref to s else null
            }.sortedWith(compareByDescending<Pair<TrackRef, Double>> { it.second }.thenBy { it.first.id })
                .take(seedsPerArtist).map { it.first }
            if (seeds.isNotEmpty()) out += ArtistPick(name, score, seeds)
        }
        return out
    }
}
