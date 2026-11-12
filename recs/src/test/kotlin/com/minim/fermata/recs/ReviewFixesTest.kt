package com.minim.fermata.recs

import com.minim.fermata.log.DAY
import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.HOUR
import com.minim.fermata.log.MINUTE
import com.minim.fermata.log.Subject
import com.minim.fermata.log.T0
import com.minim.fermata.log.TasteEditKind
import com.minim.fermata.log.TestDevice
import com.minim.fermata.log.TrackRef
import com.minim.fermata.log.track
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The dev's 6.1 review: "not interested" and the artist, undo after decay, early skips as plays. */
class ReviewFixesTest {
    private val d = TestDevice("phone")
    private val config = RecsConfig(coldStartMinPlays = 5, coldStartMinTracks = 3, radioMinKeep = 0)
    private val eps = 1e-9

    private fun play(t: TrackRef, at: Long, ms: Long = 180_000) = d.event(at, EventBody.Play(t, ms))
    private fun skip(t: TrackRef, at: Long, pos: Long = 5_000) = d.event(at, EventBody.Skip(t, pos))
    private fun like(t: TrackRef, at: Long) = d.event(at, EventBody.Like(Subject.Track(t)))
    private fun unlike(t: TrackRef, at: Long) = d.event(at, EventBody.Unlike(Subject.Track(t)))
    private fun hide(t: TrackRef, at: Long) = d.event(at, EventBody.NotInterested(Subject.Track(t)))
    private fun unhide(t: TrackRef, at: Long) = d.event(at, EventBody.Interested(Subject.Track(t)))
    private fun model(events: List<Event>) = TasteModel.build(events, config)
    private fun decay(ageMs: Long) = 0.5.pow(ageMs / config.halfLifeMs)

    /** Minim writes a play row for every listen, an early skip included, then the skip itself. */
    private fun earlySkip(t: TrackRef, at: Long, pos: Long = 5_000) =
        listOf(play(t, at, ms = pos), skip(t, at + pos, pos))

    // --- 1. "Not interested" in a song no longer sinks its artist -----------------------------

    @Test
    fun notInterestedInOneSongLeavesTheArtistWhereItWas() {
        val a1 = track("tum-hi-ho", "Arijit Singh")
        val a2 = track("channa", "Arijit Singh")
        val a3 = track("kesariya", "Arijit Singh")
        val now = T0 + 3 * DAY
        val liked = listOf(like(a1, T0), like(a2, T0 + HOUR))
        val before = model(liked).artistScore("Arijit Singh", now)
        assertTrue(before > 2.8, "two likes: about +2.86 after a few days, was $before")

        val after = model(liked + hide(a3, T0 + 2 * HOUR)).artistScore("Arijit Singh", now)
        assertEquals(before, after, eps, "the dev saw +2.86 drop to -0.07")
        val m = model(liked + hide(a3, T0 + 2 * HOUR))
        assertEquals(-6.0 * decay(now - (T0 + 2 * HOUR)), m.trackScore("kesariya", now), eps)
        assertTrue(m.isNotInterested(a3))

        // Hiding one of the liked songs takes that like's artist share back with it? No: only the song moves.
        val hiddenLiked = model(liked + hide(a1, T0 + 2 * HOUR)).artistScore("Arijit Singh", now)
        assertEquals(before, hiddenLiked, eps)
    }

    @Test
    fun notInterestedInAnArtistStillLowersTheArtist() {
        val m = model(listOf(d.event(T0, EventBody.NotInterested(Subject.Artist("Nobody")))))
        assertEquals(-6.0, m.artistScore("Nobody", T0), eps)
    }

    // --- 2. Undo removes what is left of the original, not the full weight -------------------

    @Test
    fun unlikingASixtyDayOldLikePutsTheSongBackWhereItWouldBe() {
        val s = track("s", "Artist")
        val now = T0 + 60 * DAY
        val plays = listOf(play(s, T0 - DAY), play(s, T0 + 10 * DAY))
        val without = model(plays)
        val undone = model(plays + like(s, T0) + unlike(s, now))
        assertEquals(without.trackScore("s", now), undone.trackScore("s", now), eps, "the dev saw -2.25")
        assertEquals(without.loveScore("s", now), undone.loveScore("s", now), eps)
        assertEquals(without.artistScore("Artist", now), undone.artistScore("Artist", now), eps)
        assertEquals(without.score(s, now + 5 * DAY), undone.score(s, now + 5 * DAY), eps)
        assertFalse(undone.isLiked("s"))

        // Only a like and its unlike: nothing left at all.
        val bare = model(listOf(like(s, T0), unlike(s, now)))
        assertEquals(0.0, bare.trackScore("s", now), eps)
        assertEquals(0.0, bare.artistScore("Artist", now), eps)
    }

    @Test
    fun undoingASixtyDayOldNotInterestedPutsTheSongBackWhereItWouldBe() {
        val s = track("s", "Artist")
        val now = T0 + 60 * DAY
        val m = model(listOf(hide(s, T0), unhide(s, now)))
        assertEquals(0.0, m.trackScore("s", now), eps, "the dev saw +4.5")
        assertEquals(0.0, m.artistScore("Artist", now), eps)
        assertFalse(m.isNotInterested(s))
    }

    @Test
    fun anUndoRightAwayStillTakesTheFullWeight() {
        val s = track("s", "Artist")
        val m = model(listOf(play(s, T0 - HOUR), like(s, T0), unlike(s, T0 + MINUTE)))
        assertEquals(model(listOf(play(s, T0 - HOUR))).trackScore("s", T0 + MINUTE), m.trackScore("s", T0 + MINUTE), eps)
    }

    @Test
    fun likeUnlikeLikeAgainCountsOnlyTheLatestLike() {
        val s = track("s", "Artist")
        val now = T0 + 90 * DAY
        val m = model(listOf(like(s, T0), unlike(s, T0 + 30 * DAY), like(s, T0 + 60 * DAY)))
        assertEquals(3.0 * decay(30 * DAY), m.trackScore("s", now), eps)
        assertTrue(m.isLiked("s"))
    }

    @Test
    fun anUndoAfterJustAPhaseTakesBackOnlyWhatThePhaseLeft() {
        val s = track("s", "Artist")
        val now = T0 + 60 * DAY
        val phase = d.event(T0 + 10 * DAY, EventBody.TasteEdit(TasteEditKind.JUST_A_PHASE, Subject.Track(s)))
        val m = model(listOf(like(s, T0), phase, unlike(s, now)))
        assertEquals(0.0, m.trackScore("s", now), eps)
        val reset = d.event(T0 + 10 * DAY, EventBody.TasteEdit(TasteEditKind.RESET, Subject.Artist("Artist")))
        val r = model(listOf(like(s, T0), reset, unlike(s, now)))
        assertEquals(0.0, r.artistScore("Artist", now), eps, "the reset already took the like's artist share")
    }

    @Test
    fun anUndoComesOutTheSameInAnyRebuildOrder() {
        // Events from two phones, merged and replayed in time order: same result as live.
        val s = track("s", "Artist")
        val other = TestDevice("other")
        val events = listOf(
            like(s, T0),
            other.event(T0 + 5 * DAY, EventBody.Play(s, 180_000)),
            other.event(T0 + 60 * DAY, EventBody.Unlike(Subject.Track(s))),
        )
        val live = TasteModel(config).also { m -> events.forEach(m::apply) }
        val rebuilt = TasteModel.build(events.reversed(), config)
        val now = T0 + 61 * DAY
        assertEquals(live.trackScore("s", now), rebuilt.trackScore("s", now), eps)
        assertEquals(model(listOf(events[1])).trackScore("s", now), rebuilt.trackScore("s", now), eps)
    }

    // --- 3. A play shorter than the early-skip point is not a play ----------------------------

    @Test
    fun anEarlySkipCostsTheFullWeight() {
        val s = track("s", "Artist")
        val m = model(earlySkip(s, T0))
        assertEquals(-0.6, m.trackScore("s", T0 + 5_000), 1e-6, "the dev saw -0.4")
        assertEquals(0, m.playCount("s"))
        assertNull(m.lastHeardAt("s"), "a five-second listen is not 'heard'")
        assertEquals(1, m.earlySkipStreak("s"))
    }

    @Test
    fun threeEarlySkipsOfOneArtistGiveTheFullArtistPenalty() {
        val events = (0 until 3).flatMap { earlySkip(track("t$it", "Same Artist"), T0 + it * HOUR) }
        val now = T0 + 2 * HOUR + 5_000
        assertEquals(-0.4, model(events).artistScore("Same Artist", now), 1e-3, "the dev saw -0.09")
    }

    @Test
    fun aPlayBeforeTheEarlySkipPointOfALongSongDoesNotCount() {
        // 5 minutes: the early-skip point is 20%, one minute. 45 seconds is past 30 s but short of that.
        val long = TrackRef("long", "Long", "Artist", 300_000)
        val m = model(listOf(play(long, T0, ms = 45_000)))
        assertEquals(0, m.playCount("long"))
        assertEquals(0L, m.countedPlays)
        assertEquals(0.0, m.trackScore("long", T0), eps)
        val heard = model(listOf(play(long, T0, ms = 61_000)))
        assertEquals(1L, heard.countedPlays)
    }

    @Test
    fun aShortSongPlayedToTheEndStillCounts() {
        // 20 seconds long, so its early-skip point (30 s) is past its end.
        val short = TrackRef("short", "Jingle", "Artist", 20_000)
        val m = model(listOf(play(short, T0, ms = 20_000)))
        assertEquals(1L, m.countedPlays)
        assertEquals(1, m.playCount("short"))
    }

    @Test
    fun earlySkipsDoNotCountTowardsColdStartOnRepeatOrForgottenFavourites() {
        val now = T0 + 40 * DAY
        val skipped = (0 until 12).flatMap { i -> earlySkip(track("s${i % 4}", "A"), now - 3 * DAY + i * HOUR) }
        val m = model(skipped)
        assertEquals(0L, m.countedPlays)
        assertEquals(0, m.countedTracks)
        assertTrue(Recommender.isColdStart(m), "early skips alone never warm the model up")

        // Warm, with a song played often long ago, then only early-skipped lately.
        val fav = track("fav", "B")
        val warm = (0 until 6).map { play(track("w$it", "W"), T0 + it * MINUTE) }
        val old = (0 until 6).map { play(fav, T0 + DAY + it * HOUR) }
        val recentSkip = listOf(play(fav, now - DAY, ms = 4_000))
        val f = model(warm + old + recentSkip)
        assertEquals(T0 + DAY + 5 * HOUR, f.lastHeardAt("fav"))
        assertTrue(Recommender.forgottenFavourites(f, now).any { it.track.id == "fav" }, "a 4-second listen does not make it 'heard'")

        val rep = track("rep", "C")
        val r = model(warm + (0 until 3).map { play(rep, now - DAY + it * HOUR, ms = 10_000) })
        assertTrue(Recommender.onRepeat(r, now).none { it.track.id == "rep" }, "three 10-second listens are not on repeat")
    }
}
