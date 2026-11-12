package com.minim.fermata.recs

import com.minim.fermata.log.DAY
import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.HOUR
import com.minim.fermata.log.MINUTE
import com.minim.fermata.log.Subject
import com.minim.fermata.log.T0
import com.minim.fermata.log.TestDevice
import com.minim.fermata.log.TrackRef
import com.minim.fermata.log.track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecommenderTest {
    private val d = TestDevice("phone")
    private val config = RecsConfig(coldStartMinPlays = 5, coldStartMinTracks = 3, radioMinKeep = 0)

    private fun play(t: TrackRef, at: Long, ms: Long = 180_000) = d.event(at, EventBody.Play(t, ms))
    private fun skip(t: TrackRef, at: Long, pos: Long = 5_000) = d.event(at, EventBody.Skip(t, pos))
    private fun like(t: TrackRef, at: Long) = d.event(at, EventBody.Like(Subject.Track(t)))
    private fun model(events: List<Event>, c: RecsConfig = config) = TasteModel.build(events, c)

    /** Enough plays of filler tracks, long ago, to be past cold start without touching the tracks under test. */
    private fun warm(at: Long = T0 - 60 * DAY): List<Event> =
        (0 until 6).map { play(track("filler$it", "Filler"), at + it * MINUTE) }

    private val now = T0 + 10 * DAY

    @Test
    fun coldStartReturnsNullSoAppsKeepTheirOrder() {
        val m = model(listOf(play(track("a"), T0), play(track("b"), T0 + MINUTE)))
        assertTrue(Recommender.isColdStart(m))
        val input = listOf(track("x"), track("y"))
        assertNull(Recommender.radio(input, m, now))
        assertNull(Recommender.rank(input, m, now))
        assertTrue(Recommender.onRepeat(m, now).isEmpty())
        assertTrue(Recommender.forgottenFavourites(m, now).isEmpty())
        assertTrue(Recommender.becauseYouLike(m, now).isEmpty())
    }

    @Test
    fun coldStartNeedsBothEnoughPlaysAndEnoughTracks() {
        val one = track("one")
        val sameTrack = model((0 until 10).map { play(one, T0 + it * HOUR) })
        assertTrue(Recommender.isColdStart(sameTrack), "ten plays of one track is not enough")
        assertFalse(Recommender.isColdStart(model(warm())))
        // Plays with nothing heard, or shorter than a counted play, do not count.
        val partial = model((0 until 10).map { play(track("p$it"), T0 + it * HOUR, ms = 5_000) })
        assertTrue(Recommender.isColdStart(partial))
    }

    @Test
    fun radioPutsLikedSongsAndArtistsHigher() {
        val liked = track("liked", "Nobody")
        val fromLovedArtist = track("new", "Loved")
        val plain = track("plain", "Someone")
        val events = warm() + like(liked, T0) + (0 until 6).map { play(track("l$it", "Loved"), T0 + it * HOUR) }
        // YouTube put both at the end of ten.
        val input = (0 until 8).map { track("p$it", "Someone $it") } + fromLovedArtist + liked
        val out = assertNotNull(Recommender.radio(input, model(events), now))
        val ids = out.tracks.map { it.id }
        assertTrue(ids.indexOf("liked") <= 1, "liked song moves to the top two: $ids")
        assertTrue(ids.indexOf("new") <= 4, "a loved artist's song moves up from ninth to the top five: $ids")
        assertTrue(ids.indexOf("liked") < ids.indexOf("new"))
        assertEquals(input.size, ids.size)
        assertTrue(out.dropped.isEmpty())
        assertEquals("You liked this", out.picks[ids.indexOf("liked")].reason)
        assertEquals(0.0, model(events).score(plain, now))
    }

    @Test
    fun radioKeepsSourceOrderWhenTasteIsEqual() {
        val input = (1..5).map { track("c$it", "Unknown $it") }
        val out = assertNotNull(Recommender.radio(input, model(warm()), now))
        assertEquals(input.map { it.id }, out.tracks.map { it.id })
    }

    @Test
    fun earlySkipPushesASongDownAndALateSkipLess() {
        val early = track("early", "E")
        val late = track("late", "L")
        val plain = track("plain", "P")
        val events = warm() + skip(early, T0, pos = 10_000) + skip(late, T0 + MINUTE, pos = 150_000)
        val m = model(events)
        assertTrue(m.trackScore("early", now) < m.trackScore("late", now))
        assertTrue(m.trackScore("late", now) < 0)
        val out = assertNotNull(Recommender.radio(listOf(early, plain, track("p2"), track("p3")), m, now))
        assertEquals(listOf("plain", "p2", "early", "p3"), out.tracks.map { it.id }, "YouTube's first song falls to third")
    }

    @Test
    fun earlySkipPointUsesTwentyPercentOfLongTracks() {
        val long = track("long", durationMs = 600_000) // 20% = 120 s
        assertEquals(120_000, config.earlySkipPointMs(long.durationMs))
        assertEquals(30_000, config.earlySkipPointMs(100_000))
        assertEquals(30_000, config.earlySkipPointMs(0))
        val m = model(warm() + skip(long, T0, pos = 90_000))
        assertEquals(1, m.earlySkipStreak("long"), "90 s into a 10 min track is early")
    }

    @Test
    fun repeatedEarlySkipsDropASongUntilItIsHeardAgain() {
        val s = track("s", "S")
        val once = model(warm() + skip(s, T0))
        assertTrue(assertNotNull(Recommender.radio(listOf(s), once, now)).dropped.isEmpty(), "one skip only lowers it")

        val twice = warm() + skip(s, T0) + skip(s, T0 + DAY)
        val out = assertNotNull(Recommender.radio(listOf(s, track("o")), model(twice), now))
        assertEquals(listOf(Dropped(s, DropReason.SKIPPED_EARLY)), out.dropped)
        assertEquals(listOf("o"), out.tracks.map { it.id })

        // Heard past the early point: the streak resets and the song comes back.
        val heard = twice + play(s, T0 + 2 * DAY, ms = 100_000)
        assertTrue(assertNotNull(Recommender.radio(listOf(s), model(heard), now)).dropped.isEmpty())

        // A streak older than the skip memory is forgotten.
        val later = T0 + DAY + (config.skipMemoryDays * DAY).toLong() + DAY
        assertTrue(assertNotNull(Recommender.radio(listOf(s), model(twice), later)).dropped.isEmpty())
    }

    @Test
    fun repeatEarlySkipsOfAnArtistLowerTheArtistALittle() {
        val events = warm() + (0 until 2).map { skip(track("x$it", "Skipped"), T0 + it * HOUR) }
        val m2 = model(events)
        assertEquals(0.0, m2.artistScore("Skipped", now), "two skips leave the artist alone")
        val m3 = model(events + skip(track("x2", "Skipped"), T0 + 2 * HOUR))
        val artist = m3.artistScore("Skipped", now)
        assertTrue(artist < 0)
        assertTrue(artist > config.weights.earlySkip, "lowered only a little: less than one early skip of a track")
        // An unheard track by that artist now ranks below one by an unknown artist.
        val out = assertNotNull(Recommender.radio(listOf(track("new", "Skipped"), track("o1", "Other"), track("o2", "Other 2")), m3, now))
        assertEquals(listOf("o1", "new", "o2"), out.tracks.map { it.id })
    }

    @Test
    fun artistSkipsSpreadOverMoreThanTheWindowDoNotCount() {
        val gap = (config.artistSkipWindowDays * DAY).toLong()
        val events = warm() + (0 until 3).map { skip(track("x$it", "Slow"), T0 + it * gap) }
        assertEquals(0.0, model(events).artistScore("Slow", T0 + 3 * gap))
    }

    @Test
    fun radioLeavesOutTracksHeardInTheLastFewHours() {
        val recent = track("recent")
        val older = track("older")
        val events = warm() + play(older, now - 4 * HOUR) + play(recent, now - 2 * HOUR)
        val out = assertNotNull(Recommender.radio(listOf(recent, older, track("new")), model(events), now))
        assertEquals(setOf("older", "new"), out.tracks.map { it.id }.toSet())
        assertEquals(listOf(Dropped(recent, DropReason.HEARD_RECENTLY)), out.dropped)
        // Shelves are not filtered by recent plays.
        assertTrue(assertNotNull(Recommender.rank(listOf(recent), model(events), now)).dropped.isEmpty())
    }

    @Test
    fun radioBringsRecentTracksBackRatherThanRunningDry() {
        val c = config.copy(radioMinKeep = 3)
        val heard = (0 until 4).map { track("h$it") }
        val events = warm() + heard.mapIndexed { i, t -> play(t, now - HOUR + i * MINUTE) }
        val out = assertNotNull(Recommender.radio(heard + track("fresh"), model(events, c), now))
        assertEquals(listOf("fresh", "h0", "h1"), out.tracks.map { it.id })
        assertEquals(listOf("h2", "h3"), out.dropped.map { it.track.id })
    }

    @Test
    fun notInterestedTracksAndArtistsAreDropped() {
        val t = track("t", "A")
        val byArtist = track("u", "Muted")
        val events = warm() + d.event(T0, EventBody.NotInterested(Subject.Track(t))) +
            d.event(T0 + 1, EventBody.NotInterested(Subject.Artist("Muted")))
        val out = assertNotNull(Recommender.rank(listOf(t, byArtist, track("ok")), model(events), now))
        assertEquals(listOf("ok"), out.tracks.map { it.id })
        assertEquals(setOf(DropReason.NOT_INTERESTED), out.dropped.map { it.reason }.toSet())
        val undone = events + d.event(T0 + 2, EventBody.Interested(Subject.Track(t)))
        assertEquals(1, assertNotNull(Recommender.rank(listOf(t, byArtist), model(undone), now)).dropped.size)
    }

    @Test
    fun onRepeatListsTracksPlayedOftenThisWeek() {
        val hot = track("hot")
        val warmish = track("warm")
        val old = track("old")
        val events = warm() +
            (0 until 5).map { play(old, now - 20 * DAY + it * HOUR) } +
            (0 until 4).map { play(hot, now - 3 * DAY + it * HOUR) } +
            (0 until 3).map { play(warmish, now - 2 * DAY + it * HOUR) } +
            (0 until 2).map { play(track("twice"), now - DAY + it * HOUR) }
        val shelf = Recommender.onRepeat(model(events), now)
        assertEquals(listOf("hot", "warm"), shelf.map { it.track.id })
        assertEquals("Song hot", shelf[0].track.title)
    }

    @Test
    fun forgottenFavouritesAreLovedSongsNotHeardForAMonth() {
        val loved = track("loved")
        val likedLongAgo = track("likedOld")
        val stillPlaying = track("current")
        val likedRecently = track("likedNew")
        val longAgo = now - 90 * DAY
        val events = warm(longAgo - DAY) +
            (0 until 8).map { play(loved, longAgo + it * HOUR) } +
            like(likedLongAgo, longAgo) +
            (0 until 8).map { play(stillPlaying, longAgo + it * HOUR) } + play(stillPlaying, now - DAY) +
            like(likedRecently, now - 2 * DAY)
        val shelf = Recommender.forgottenFavourites(model(events), now)
        assertEquals(listOf("loved", "likedOld"), shelf.map { it.track.id })
    }

    @Test
    fun becauseYouLikeGivesTopArtistsWithTheirOwnBestTracksAsSeeds() {
        val events = warm() +
            (0 until 8).map { play(track("a${it % 3}", "Alpha"), T0 + it * HOUR) } +
            like(track("a2", "Alpha"), T0 + DAY) +
            (0 until 5).map { play(track("b$it", "Beta"), T0 + it * HOUR) } +
            (0 until 2).map { play(track("c$it", "Gamma"), T0 + it * HOUR) }
        val picks = Recommender.becauseYouLike(model(events), now, artists = 5, seedsPerArtist = 2)
        assertEquals(listOf("Alpha", "Beta", "Filler"), picks.map { it.artist }, "Gamma has too few plays")
        assertEquals("a2", picks[0].seeds.first().id, "the liked track is the best seed")
        assertEquals(2, picks[0].seeds.size)
    }

    @Test
    fun sameInputsGiveTheSameOutput() {
        val events = warm() + (0 until 50).map { play(track("t${it % 7}", "A${it % 3}"), T0 + it * HOUR, ms = 20_000L + it * 3_000) }
        val input = (0 until 7).map { track("t$it", "A${it % 3}") }.reversed()
        val first = Recommender.radio(input, model(events), now)
        repeat(3) { assertEquals(first, Recommender.radio(input, model(events.shuffled()), now)) }
    }

    @Test
    fun incrementalUpdatesMatchAFullRebuild() {
        val events = warm() + (0 until 200).map {
            val t = track("t${it % 13}", "A${it % 4}")
            when (it % 5) {
                0 -> skip(t, T0 + it * MINUTE, pos = (it % 3) * 20_000L)
                1 -> like(t, T0 + it * MINUTE)
                else -> play(t, T0 + it * MINUTE, ms = 15_000L * (it % 9))
            }
        }
        val incremental = TasteModel(config)
        for (e in events) incremental.apply(e)
        val rebuilt = model(events)
        for (i in 0 until 13) {
            val id = "t$i"
            assertEquals(rebuilt.trackScore(id, now), incremental.trackScore(id, now), 1e-9)
            assertEquals(rebuilt.earlySkipStreak(id), incremental.earlySkipStreak(id))
            assertEquals(rebuilt.lastHeardAt(id), incremental.lastHeardAt(id))
        }
        assertEquals(rebuilt.countedPlays, incremental.countedPlays)
    }
}
