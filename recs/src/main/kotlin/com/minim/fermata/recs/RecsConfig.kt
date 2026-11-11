package com.minim.fermata.recs

/** How much each signal moves a score. Starting values, to be tuned against real listening. */
data class Weights(
    val countedPlay: Double = 1.0,
    /** Extra for a play heard to (nearly) the end. */
    val completionBonus: Double = 0.5,
    val partialPlay: Double = 0.2,
    val earlySkip: Double = -0.6,
    val lateSkip: Double = -0.2,
    val queued: Double = 1.5,
    val like: Double = 3.0,
    val notInterested: Double = -6.0,
    val overIt: Double = -8.0,
    /** "Just a phase" multiplies what was learned so far by this. */
    val phaseFactor: Double = 0.25,
    /** Share of a track's signal that also goes to its artist. */
    val artistShare: Double = 0.5,
)

data class RecsConfig(
    /** Signals lose half their weight every this many days. */
    val halfLifeDays: Double = 30.0,
    val countedPlayMs: Long = 30_000,
    val earlySkipMs: Long = 30_000,
    /**
     * Confidence = maxConfidence * (1 - e^(-signals / confidenceScale)). With about 150 signals a
     * day, 2000 gives roughly 0.3 after a week and 0.8 after two months.
     */
    val confidenceScale: Double = 2_000.0,
    /** Fermata never fully takes over: YouTube's order always keeps some say. */
    val maxConfidence: Double = 0.85,
    val weights: Weights = Weights(),
) {
    val halfLifeMs: Double get() = halfLifeDays * 24 * 60 * 60 * 1000
}
