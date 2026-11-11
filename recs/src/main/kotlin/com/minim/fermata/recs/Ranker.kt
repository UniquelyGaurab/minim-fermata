package com.minim.fermata.recs

import com.minim.fermata.log.TrackRef

/** A ranked candidate. [reason] is one line a person can read, or null when there is none. */
data class Pick(val track: TrackRef, val score: Double, val reason: String?)

/**
 * Blends the order a source (YouTube) gave with Fermata's taste, weighted by confidence:
 *
 *     final = (1 - c) * sourceScore + c * tasteScore
 *
 * where sourceScore falls linearly from 1 (first candidate) to 0 (last) and tasteScore is the
 * model's score min-max normalised over the candidates. At confidence 0 the input order comes
 * back untouched ("never worse than YouTube Music"); ties always keep the input order.
 */
object Ranker {
    fun rank(
        candidates: List<TrackRef>,
        model: TasteModel,
        now: Long,
        confidence: Double = model.confidence(),
    ): List<Pick> {
        val c = confidence.coerceIn(0.0, 1.0)
        if (c == 0.0 || candidates.size < 2) {
            return candidates.map { Pick(it, if (c == 0.0) 0.0 else model.score(it, now), reason(it, model)) }
        }
        val n = candidates.size
        val taste = candidates.map { model.score(it, now) }
        val min = taste.min()
        val max = taste.max()
        val range = max - min
        val blended = candidates.indices.map { i ->
            val source = 1.0 - i.toDouble() / (n - 1)
            val tasteNorm = if (range > 0) (taste[i] - min) / range else 0.0
            Triple(i, (1 - c) * source + c * tasteNorm, taste[i])
        }
        return blended
            .sortedWith(compareByDescending<Triple<Int, Double, Double>> { it.second }.thenBy { it.first })
            .map { (i, score, _) -> Pick(candidates[i], score, reason(candidates[i], model)) }
    }

    /** The first reason that applies, in order of strength. */
    fun reason(track: TrackRef, model: TasteModel): String? {
        val plays = model.playCount(track.id)
        val artist = track.artist.trim()
        return when {
            model.isLiked(track.id) -> "You liked this"
            plays >= 3 -> "You've played this $plays times"
            artist.isNotEmpty() && model.isArtistLiked(artist) -> "You like $artist"
            artist.isNotEmpty() && model.artistPlayCount(artist) >= 5 -> "You often play $artist"
            else -> null
        }
    }
}
