package com.minim.fermata.recs

import com.minim.fermata.log.DAY
import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.MINUTE
import com.minim.fermata.log.Subject
import com.minim.fermata.log.T0
import com.minim.fermata.log.TestDevice
import com.minim.fermata.log.TrackRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Timings with a realistic large history: a year of listening, popular tracks played far more
 * than the rest. Prints the numbers (see the test report's standard output) and fails only on
 * limits far above them, so a slow CI machine does not flake. These are JVM numbers on a
 * desktop CPU; a mid-range phone is several times slower, which is why ranking stays O(n) in the
 * candidates and the build runs off the main thread.
 */
class RecsBenchmarkTest {
    private fun history(size: Int, seed: Int = 7): List<Event> {
        val rnd = Random(seed)
        val d = TestDevice("phone")
        val artists = 400
        val tracks = (0 until 4_000).map { i ->
            val artist = (i * 7919 % artists)
            TrackRef("vid$i", "Song $i", "Artist $artist", 150_000L + (i % 120) * 1_000)
        }
        // Zipf-ish: low indices are the favourites.
        fun pick(): TrackRef = tracks[(tracks.size * rnd.nextDouble().let { it * it * it }).toInt()]
        val span = 365 * DAY
        val step = span / size
        return (0 until size).map { i ->
            val t = pick()
            val at = T0 - span + i * step
            when (rnd.nextInt(100)) {
                in 0..64 -> d.event(at, EventBody.Play(t, (t.durationMs * rnd.nextDouble(0.5, 1.0)).toLong()))
                in 65..84 -> d.event(at, EventBody.Skip(t, rnd.nextLong(1_000, t.durationMs)))
                in 85..92 -> d.event(at, EventBody.Play(t, rnd.nextLong(1_000, 25_000)))
                in 93..95 -> d.event(at, EventBody.Queued(t))
                in 96..97 -> d.event(at, EventBody.Like(Subject.Track(t)))
                98 -> d.event(at, EventBody.Unlike(Subject.Track(t)))
                else -> d.event(at, EventBody.NotInterested(Subject.Track(t)))
            }
        }
    }

    private inline fun timeMs(repeat: Int = 1, block: () -> Unit): Double {
        val t0 = System.nanoTime()
        repeat(repeat) { block() }
        return (System.nanoTime() - t0) / 1e6 / repeat
    }

    private fun run(size: Int): Map<String, Double> {
        val events = history(size)
        val now = T0
        repeat(3) { TasteModel.build(events) } // warm the JIT
        lateinit var model: TasteModel
        val build = timeMs(5) { model = TasteModel.build(events) }
        val candidates = (0 until 50).map { TrackRef("vid${it * 37}", "Song", "Artist ${it * 37 * 7919 % 400}", 200_000) }
        repeat(200) { Recommender.radio(candidates, model, now); Recommender.onRepeat(model, now) }
        val radio = timeMs(1_000) { assertNotNull(Recommender.radio(candidates, model, now)) }
        val shelf = timeMs(1_000) { Recommender.rank(candidates.take(20), model, now) }
        val onRepeat = timeMs(200) { Recommender.onRepeat(model, now) }
        val forgotten = timeMs(200) { Recommender.forgottenFavourites(model, now) }
        val because = timeMs(200) { Recommender.becauseYouLike(model, now) }
        val next = Event(
            com.minim.fermata.log.EventId("phone", 1_000_000),
            com.minim.fermata.log.EventContext.at(now + MINUTE, java.time.ZoneOffset.UTC),
            EventBody.Play(candidates[0], 120_000),
        )
        val apply = timeMs(10_000) { model.apply(next) } * 1_000 // microseconds
        return linkedMapOf(
            "events" to size.toDouble(), "tracks" to model.trackIds().size.toDouble(),
            "build ms" to build, "radio(50) ms" to radio, "rank(20) ms" to shelf,
            "onRepeat ms" to onRepeat, "forgotten ms" to forgotten, "becauseYouLike ms" to because,
            "apply one event us" to apply,
        )
    }

    @Test
    fun rankingStaysFastWithALargeHistory() {
        for (size in listOf(10_000, 50_000)) {
            val r = run(size)
            println("RECS_BENCHMARK " + r.entries.joinToString(", ") { "${it.key}=${"%.3f".format(it.value)}" })
            assertTrue(r.getValue("radio(50) ms") < 20, "radio rerank: $r")
            assertTrue(r.getValue("onRepeat ms") < 50 && r.getValue("forgotten ms") < 50 && r.getValue("becauseYouLike ms") < 50, "shelves: $r")
            assertTrue(r.getValue("build ms") < 3_000, "build: $r")
        }
    }
}
