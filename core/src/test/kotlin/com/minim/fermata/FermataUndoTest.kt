package com.minim.fermata

import com.minim.fermata.log.DAY
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.InMemoryEventStore
import com.minim.fermata.log.MINUTE
import com.minim.fermata.log.Subject
import com.minim.fermata.log.T0
import com.minim.fermata.log.TrackRef
import com.minim.fermata.log.track
import com.minim.fermata.recs.RecsConfig
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Undoing an old like or "not interested" comes out the same live, rebuilt, merged and restored. */
class FermataUndoTest {
    private class ManualClock(var now: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private val clock = ManualClock(T0)
    private val config = RecsConfig(coldStartMinPlays = 5, coldStartMinTracks = 3, radioMinKeep = 0)
    private val eps = 1e-9
    private val song = track("song", "Arijit Singh")

    private fun fermata(device: String) = Fermata(InMemoryEventStore(), device, clock, ZoneOffset.UTC, config = config)

    private fun Fermata.warm() = repeat(6) {
        record(EventBody.Play(track("w$it", "Warm"), 180_000))
        clock.now += 4 * MINUTE
    }

    private fun Fermata.score(t: TrackRef = song): Double {
        recs.warmUp()
        return assertNotNull(recs.modelForTest()).trackScore(t.id, clock.now)
    }

    private fun Fermata.artist(): Double = assertNotNull(recs.modelForTest()).artistScore("Arijit Singh", clock.now)

    @Test
    fun aLikeOnOnePhoneUndoneOnAnotherSixtyDaysLater() {
        val a = fermata("phoneA")
        val b = fermata("phoneB")
        a.warm()
        a.record(EventBody.Like(Subject.Track(song)))
        b.merge(a.events())
        clock.now += 60 * DAY
        b.record(EventBody.Unlike(Subject.Track(song)))
        a.merge(b.events())

        assertEquals(0.0, a.score(), eps, "was -2.25 before the fix")
        assertEquals(0.0, a.artist(), eps)
        assertEquals(0.0, b.score(), eps)

        // A restore of phone A's log onto a third phone builds the same.
        val c = fermata("phoneC")
        c.restore(a.events())
        assertEquals(0.0, c.score(), eps)
        assertEquals(0.0, c.artist(), eps)
    }

    @Test
    fun notInterestedUndoneLiveMatchesARebuild() {
        val a = fermata("phoneA")
        a.warm()
        a.recs.warmUp()
        a.record(EventBody.NotInterested(Subject.Track(song)))
        assertTrue(assertNotNull(a.recs.modelForTest()).isNotInterested(song))
        clock.now += 60 * DAY
        a.record(EventBody.Interested(Subject.Track(song)))
        val live = assertNotNull(a.recs.modelForTest())
        assertFalse(live.isNotInterested(song))
        assertEquals(0.0, live.trackScore("song", clock.now), eps, "was +4.5 before the fix")

        // Undo shows up in the radio again, and a rebuild from the log agrees.
        val out = assertNotNull(a.recs.radio(listOf(song, track("other", "Other"))))
        assertTrue(out.tracks.any { it.id == "song" })
        a.recs.invalidate(drop = true)
        assertEquals(0.0, a.score(), eps)
        assertTrue(assertNotNull(a.recs.radio(listOf(song))).tracks.any { it.id == "song" })
    }

    @Test
    fun notInterestedOnOnePhoneUndoneOnAnotherAfterAMerge() {
        val a = fermata("phoneA")
        val b = fermata("phoneB")
        a.warm()
        a.record(EventBody.Like(Subject.Track(track("liked", "Arijit Singh"))))
        a.record(EventBody.NotInterested(Subject.Track(song)))
        b.merge(a.events())
        clock.now += 10 * DAY
        b.record(EventBody.Interested(Subject.Track(song)))
        a.merge(b.events())
        assertEquals(0.0, a.score(), eps)
        assertEquals(1.5 * Math.pow(0.5, 10.0 / 30.0), a.artist(), 1e-6, "the like's share only: the hide never touched the artist")
        assertTrue(assertNotNull(a.recs.radio(listOf(song))).tracks.any { it.id == "song" })
    }
}
