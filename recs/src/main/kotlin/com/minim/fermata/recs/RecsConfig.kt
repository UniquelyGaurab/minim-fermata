package com.minim.fermata.recs

/** How much each signal moves a score. Starting values, to be tuned against real listening. */
data class Weights(
    val countedPlay: Double = 1.0,
    /** Extra for a play heard to (nearly) the end. */
    val completionBonus: Double = 0.5,
    val partialPlay: Double = 0.2,
    /** A skip before [RecsConfig.earlySkipMs] / [RecsConfig.earlySkipFraction]. Goes to the track only. */
    val earlySkip: Double = -0.6,
    val lateSkip: Double = -0.2,
    /**
     * Added to an artist for each early skip once [RecsConfig.artistSkipsBeforePenalty] early
     * skips of that artist fall within [RecsConfig.artistSkipWindowDays]. A single skip never
     * touches the artist; repeat skips lower it a little.
     */
    val artistRepeatSkip: Double = -0.4,
    val queued: Double = 1.5,
    val like: Double = 3.0,
    val notInterested: Double = -6.0,
    val overIt: Double = -8.0,
    /** "Just a phase" multiplies what was learned so far by this. */
    val phaseFactor: Double = 0.25,
    /** Share of a track's signal that also goes to its artist (skips excepted, see [artistRepeatSkip]). */
    val artistShare: Double = 0.5,
)

data class RecsConfig(
    /** Signals lose half their weight every this many days. */
    val halfLifeDays: Double = 30.0,
    val countedPlayMs: Long = 30_000,
    /**
     * A skip is early when it comes before this much of the track, or before [earlySkipFraction]
     * of a track whose length is known, whichever is later.
     */
    val earlySkipMs: Long = 30_000,
    val earlySkipFraction: Double = 0.2,
    /**
     * Confidence = maxConfidence * (1 - e^(-signals / confidenceScale)). With about 150 signals a
     * day, 2000 gives roughly 0.3 after a week and 0.8 after two months.
     */
    val confidenceScale: Double = 2_000.0,
    /** Fermata never fully takes over: YouTube's order always keeps some say. */
    val maxConfidence: Double = 0.85,
    val weights: Weights = Weights(),

    // ---- recommendations (Recommender) ----

    /** Cold start: fewer counted plays than this, or fewer distinct tracks played, and apps keep their own order. */
    val coldStartMinPlays: Int = 30,
    val coldStartMinTracks: Int = 10,
    /**
     * Past cold start, the radio and shelves blend with at least this confidence, so likes and
     * skips show from the first days rather than after weeks ([confidence] grows slowly).
     */
    val minWarmConfidence: Double = 0.5,
    /** The radio leaves out tracks heard within this window ("nothing I played in the last few hours"). */
    val recentWindowMs: Long = 3 * HOUR,
    /** A track skipped early this many times in a row (nothing heard past the early point in between) is dropped. */
    val dropAfterEarlySkips: Int = 2,
    /** Such a streak is forgotten when its last early skip is older than this. */
    val skipMemoryDays: Double = 60.0,
    /** Repeat skips: this many early skips of one artist within [artistSkipWindowDays] start [Weights.artistRepeatSkip]. */
    val artistSkipsBeforePenalty: Int = 3,
    val artistSkipWindowDays: Double = 14.0,
    /** If filtering leaves the radio fewer tracks than this, recently heard ones come back at the end. */
    val radioMinKeep: Int = 10,
    /** "On repeat": tracks counted at least [onRepeatMinPlays] times within [onRepeatDays]. */
    val onRepeatDays: Double = 7.0,
    val onRepeatMinPlays: Int = 3,
    /** "Forgotten favourites": liked, or counted [forgottenMinPlays]+ times, and not heard for [forgottenAfterDays]. */
    val forgottenAfterDays: Double = 30.0,
    val forgottenMinPlays: Int = 5,
    /** Long memory for "loved" (forgotten favourites): signals lose half their weight every this many days. */
    val longHalfLifeDays: Double = 180.0,
    /** "Because you like X": an artist needs this many counted plays (or a like) to be X. */
    val becauseMinArtistPlays: Int = 5,
) {
    val halfLifeMs: Double get() = halfLifeDays * DAY
    val longHalfLifeMs: Double get() = longHalfLifeDays * DAY

    /** Where a skip stops being early, for a track of [durationMs] (0: unknown). */
    fun earlySkipPointMs(durationMs: Long): Long =
        if (durationMs > 0) maxOf(earlySkipMs, (durationMs * earlySkipFraction).toLong()) else earlySkipMs

    companion object {
        const val HOUR: Long = 60 * 60 * 1000L
        const val DAY: Long = 24 * HOUR
    }
}
