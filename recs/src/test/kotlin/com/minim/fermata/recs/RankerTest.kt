package com.minim.fermata.recs

import com.minim.fermata.log.DAY
import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.MINUTE
import com.minim.fermata.log.Subject
import com.minim.fermata.log.T0
import com.minim.fermata.log.TasteEditKind
import com.minim.fermata.log.TestDevice
import com.minim.fermata.log.track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RankerTest {
    private val candidates = listOf(track("1", "Alpha"), track("2", "Beta"), track("3", "Gamma"), track("4", "Delta"))

    private fun history(vararg bodies: EventBody, start: Long = T0): List<Event> {
        val d = TestDevice("d")
        return bodies.mapIndexed { i, b -> d.event(start + i * MINUTE, b) }
    }

    @Test
    fun `zero confidence returns the input order untouched`() {
        val empty = TasteModel()
        assertEquals(0.0, empty.confidence())
        assertEquals(candidates, Ranker.rank(candidates, empty, T0).map { it.track })

        // Even with strong taste, confidence 0 means YouTube's order wins completely.
        val model = TasteModel.build(history(EventBody.Like(Subject.Track(candidates[3]))))
        val picks = Ranker.rank(candidates, model, T0 + DAY, confidence = 0.0)
        assertEquals(candidates, picks.map { it.track })
        assertTrue(picks.all { it.score == 0.0 })
    }

    @Test
    fun `no knowledge about the candidates keeps the input order at any confidence`() {
        val model = TasteModel.build(history(EventBody.Play(track("other"), 200_000)))
        assertEquals(candidates, Ranker.rank(candidates, model, T0, confidence = 0.85).map { it.track })
    }

    @Test
    fun `a liked song rises as confidence grows`() {
        val model = TasteModel.build(history(EventBody.Like(Subject.Track(candidates[3]))))
        val low = Ranker.rank(candidates, model, T0 + DAY, confidence = 0.1).map { it.track.id }
        val high = Ranker.rank(candidates, model, T0 + DAY, confidence = 0.8).map { it.track.id }
        assertEquals(listOf("1", "2", "3", "4"), low)
        assertEquals("4", high.first())
        assertEquals(listOf("1", "2", "3"), high.drop(1)) // the rest keep their order
    }

    @Test
    fun `not interested pushes a song down`() {
        val model = TasteModel.build(history(EventBody.NotInterested(Subject.Track(candidates[0]))))
        val ranked = Ranker.rank(candidates, model, T0 + DAY, confidence = 0.8).map { it.track.id }
        assertEquals("1", ranked.last())
    }

    @Test
    fun `scores halve every half-life`() {
        val config = RecsConfig(halfLifeDays = 10.0)
        val model = TasteModel.build(history(EventBody.Queued(candidates[0])), config)
        val now = model.trackScore("1", T0)
        assertEquals(config.weights.queued, now, 1e-9)
        assertEquals(now / 2, model.trackScore("1", T0 + 10 * DAY), 1e-9)
        assertEquals(now / 4, model.trackScore("1", T0 + 20 * DAY), 1e-9)
    }

    @Test
    fun `the model gives the same scores whatever order events arrive in`() {
        val log = history(
            EventBody.Play(candidates[0], 200_000),
            EventBody.Skip(candidates[1], 5_000),
            EventBody.Queued(candidates[2]),
            EventBody.Play(candidates[0], 100_000),
        )
        val one = TasteModel.build(log)
        val two = TasteModel.build(log.reversed())
        for (c in candidates) assertEquals(one.score(c, T0 + DAY), two.score(c, T0 + DAY), 1e-9)
    }

    @Test
    fun `confidence grows with listening and stays under the cap`() {
        val config = RecsConfig()
        val week = TasteModel.build(List(7 * 150) { EventBody.Play(track("t$it"), 200_000) }.let { history(*it.toTypedArray()) })
        val twoMonths = TasteModel.build(List(60 * 150) { EventBody.Play(track("t$it"), 200_000) }.let { history(*it.toTypedArray()) })
        assertTrue(week.confidence() in 0.25..0.35, "a week: ${week.confidence()}")
        assertTrue(twoMonths.confidence() > 0.8, "two months: ${twoMonths.confidence()}")
        assertTrue(twoMonths.confidence() <= config.maxConfidence)
    }

    @Test
    fun `taste edits shrink or flip what was learned`() {
        val alpha = candidates[0]
        val base = history(EventBody.Play(alpha, 200_000), EventBody.Play(alpha, 200_000))
        val before = TasteModel.build(base).trackScore("1", T0 + 2 * MINUTE)
        val phase = TasteModel.build(base + TestDevice("e").event(T0 + 2 * MINUTE, EventBody.TasteEdit(TasteEditKind.JUST_A_PHASE, Subject.Track(alpha))))
        assertEquals(before * 0.25, phase.trackScore("1", T0 + 2 * MINUTE), 1e-9)
        val over = TasteModel.build(base + TestDevice("e").event(T0 + 2 * MINUTE, EventBody.TasteEdit(TasteEditKind.OVER_IT, Subject.Artist("Alpha"))))
        assertTrue(over.artistScore("alpha", T0 + 2 * MINUTE) < 0)
        val reset = TasteModel.build(base + TestDevice("e").event(T0 + 2 * MINUTE, EventBody.TasteEdit(TasteEditKind.RESET, Subject.Track(alpha))))
        assertEquals(0.0, reset.trackScore("1", T0 + 3 * MINUTE))
    }

    @Test
    fun `reasons read like a person wrote them`() {
        val alpha = candidates[0]
        val model = TasteModel.build(
            history(
                EventBody.Play(alpha, 200_000), EventBody.Play(alpha, 200_000), EventBody.Play(alpha, 200_000),
                EventBody.Like(Subject.Track(candidates[1])),
                EventBody.Like(Subject.Artist("Gamma")),
            ),
        )
        assertEquals("You've played this 3 times", Ranker.reason(alpha, model))
        assertEquals("You liked this", Ranker.reason(candidates[1], model))
        assertEquals("You like Gamma", Ranker.reason(candidates[2], model))
        assertNull(Ranker.reason(candidates[3], model))
    }
}
