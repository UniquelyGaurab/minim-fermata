package com.minim.fermata.library

import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventLog
import com.minim.fermata.log.MINUTE
import com.minim.fermata.log.Source
import com.minim.fermata.log.Subject
import com.minim.fermata.log.T0
import com.minim.fermata.log.TestDevice
import com.minim.fermata.log.TrackRef
import com.minim.fermata.log.track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryProjectionTest {
    private val songA = track("A")
    private val songB = track("B")
    private val songC = track("C")
    private val songD = track("D")

    private fun ids(state: LibraryState, playlist: String) = state.playlist(playlist)!!.tracks.map { it.id }

    // ---- last writer wins ----------------------------------------------------------------------

    @Test
    fun `the later like or unlike wins whatever order the logs merge in`() {
        val phone = TestDevice("phone")
        val tablet = TestDevice("tablet")
        val like = listOf(phone.event(T0, EventBody.Like(Subject.Track(songA))))
        val unlike = listOf(tablet.event(T0 + MINUTE, EventBody.Unlike(Subject.Track(songA))))
        assertFalse(LibraryProjection.build(EventLog.merge(like, unlike)).isLiked("A"))
        assertFalse(LibraryProjection.build(EventLog.merge(unlike, like)).isLiked("A"))
    }

    @Test
    fun `a timestamp tie is broken by device id, deterministically`() {
        // Same millisecond: the event from the device id that sorts last wins.
        val a = TestDevice("aaa")
        val b = TestDevice("bbb")
        val unlikeFromA = listOf(a.event(T0, EventBody.Unlike(Subject.Track(songA))))
        val likeFromB = listOf(b.event(T0, EventBody.Like(Subject.Track(songA))))
        // A liked it earlier, so the unlike has something to undo.
        val earlierLike = listOf(TestDevice("aaa-old").event(T0 - MINUTE, EventBody.Like(Subject.Track(songA))))
        repeat(3) {
            assertTrue(LibraryProjection.build(EventLog.merge(earlierLike, unlikeFromA, likeFromB)).isLiked("A"))
            assertTrue(LibraryProjection.build(EventLog.merge(likeFromB, unlikeFromA, earlierLike)).isLiked("A"))
        }

        // Swap the roles: now the unlike comes from the device that sorts last, and it wins.
        val unlikeFromB = listOf(TestDevice("bbb").event(T0, EventBody.Unlike(Subject.Track(songA))))
        val likeFromA = listOf(TestDevice("aaa").event(T0, EventBody.Like(Subject.Track(songA))))
        assertFalse(LibraryProjection.build(EventLog.merge(likeFromA, unlikeFromB)).isLiked("A"))
        assertFalse(LibraryProjection.build(EventLog.merge(unlikeFromB, likeFromA)).isLiked("A"))
    }

    @Test
    fun `liking again keeps the first like time, relike after unlike starts over`() {
        val d = TestDevice("d")
        val log = listOf(
            d.event(T0, EventBody.Like(Subject.Track(songA))),
            d.event(T0 + 1, EventBody.Like(Subject.Track(songB))),
            d.event(T0 + 2, EventBody.Like(Subject.Track(songA))),
            d.event(T0 + 3, EventBody.Unlike(Subject.Track(songB))),
            d.event(T0 + 4, EventBody.Like(Subject.Track(songB))),
            d.event(T0 + 5, EventBody.Like(Subject.Artist(" Daft Punk "))),
            d.event(T0 + 6, EventBody.Like(Subject.Artist("daft punk"))),
        )
        val state = LibraryProjection.build(log)
        assertEquals(listOf("B" to T0 + 4, "A" to T0), state.likedTracks.map { it.track.id to it.likedAt })
        assertEquals(listOf("Daft Punk"), state.likedArtists.map { it.name })
    }

    // ---- playlists -----------------------------------------------------------------------------

    @Test
    fun `playlist keeps order through add, insert, move and remove`() {
        val d = TestDevice("d")
        var t = T0
        val log = listOf(
            d.event(t++, EventBody.PlaylistCreate("p1", "Road trip")),
            d.event(t++, EventBody.PlaylistAdd("p1", songA)),
            d.event(t++, EventBody.PlaylistAdd("p1", songB)),
            d.event(t++, EventBody.PlaylistAdd("p1", songC)),
            d.event(t++, EventBody.PlaylistAdd("p1", songD, index = 1)),
        )
        assertEquals(listOf("A", "D", "B", "C"), ids(LibraryProjection.build(log), "p1"))

        val moved = log + d.event(t++, EventBody.PlaylistMove("p1", "C", 0))
        assertEquals(listOf("C", "A", "D", "B"), ids(LibraryProjection.build(moved), "p1"))

        val removed = moved + d.event(t++, EventBody.PlaylistRemove("p1", "A"))
        assertEquals(listOf("C", "D", "B"), ids(LibraryProjection.build(removed), "p1"))

        val more = removed +
            d.event(t++, EventBody.PlaylistAdd("p1", songB)) + // already there: no-op
            d.event(t++, EventBody.PlaylistMove("p1", "C", 99)) + // clamped to the end
            d.event(t++, EventBody.PlaylistAdd("p1", songA, index = 50)) + // past the end: append
            d.event(t++, EventBody.PlaylistRename("p1", "Road trip 2026"))
        val state = LibraryProjection.build(more)
        assertEquals(listOf("D", "B", "C", "A"), ids(state, "p1"))
        assertEquals("Road trip 2026", state.playlist("p1")!!.name)
        assertEquals(T0, state.playlist("p1")!!.createdAt)
        assertEquals(t - 1, state.playlist("p1")!!.updatedAt)
    }

    @Test
    fun `concurrent playlist edits converge to the same order on every device`() {
        val phone = TestDevice("phone")
        val tablet = TestDevice("tablet")
        val shared = listOf(
            phone.event(T0, EventBody.PlaylistCreate("p", "Mix")),
            phone.event(T0 + 1, EventBody.PlaylistAdd("p", songA)),
        )
        val onPhone = listOf(
            phone.event(T0 + 10, EventBody.PlaylistAdd("p", songB, index = 0)),
            phone.event(T0 + 12, EventBody.PlaylistMove("p", "A", 0)),
        )
        val onTablet = listOf(
            tablet.event(T0 + 11, EventBody.PlaylistAdd("p", songC, index = 0)),
            tablet.event(T0 + 12, EventBody.PlaylistRemove("p", "B")),
        )
        val one = LibraryProjection.build(EventLog.merge(shared, onPhone, onTablet))
        val two = LibraryProjection.build(EventLog.merge(onTablet, shared, onPhone))
        assertEquals(ids(one, "p"), ids(two, "p"))
        // Replay: [A] -> [B,A] -> [C,B,A] -> move A to 0 (phone, t+12) -> [A,C,B] -> remove B (tablet, t+12) -> [A,C]
        assertEquals(listOf("A", "C"), ids(one, "p"))
    }

    @Test
    fun `a deleted playlist stays deleted`() {
        val d = TestDevice("d")
        val other = TestDevice("other")
        val log = listOf(
            d.event(T0, EventBody.PlaylistCreate("p", "Old")),
            d.event(T0 + 1, EventBody.PlaylistAdd("p", songA)),
            d.event(T0 + 2, EventBody.PlaylistDelete("p")),
            other.event(T0 + 3, EventBody.PlaylistAdd("p", songB)),
            other.event(T0 + 4, EventBody.PlaylistRename("p", "Back?")),
        )
        val state = LibraryProjection.build(log)
        assertNull(state.playlist("p"))
        assertTrue(state.playlists.isEmpty())
    }

    @Test
    fun `an add seen before its create keeps the name from the create`() {
        val d = TestDevice("d")
        val log = listOf(
            d.event(T0, EventBody.PlaylistAdd("p", songA)),
            d.event(T0 + 1, EventBody.PlaylistCreate("p", "Late name")),
        )
        val p = LibraryProjection.build(log).playlist("p")!!
        assertEquals("Late name", p.name)
        assertEquals(listOf("A"), p.tracks.map { it.id })
    }

    @Test
    fun `playlists are listed in creation order`() {
        val d = TestDevice("d")
        val log = listOf(
            d.event(T0 + 5, EventBody.PlaylistCreate("second", "Second")),
            d.event(T0, EventBody.PlaylistCreate("first", "First")),
        )
        assertEquals(listOf("first", "second"), LibraryProjection.build(log).playlists.map { it.id })
    }

    // ---- history, stats, not interested, metadata --------------------------------------------

    @Test
    fun `history holds counted plays newest first, stats count every second heard`() {
        val d = TestDevice("d")
        val short = TrackRef("S", "Short", "X", durationMs = 40_000)
        val log = listOf(
            d.event(T0, EventBody.Play(songA, 120_000), session = "s1", source = Source.RADIO),
            d.event(T0 + MINUTE, EventBody.Play(songA, 10_000)), // too short to count
            d.event(T0 + 2 * MINUTE, EventBody.Play(short, 20_000)), // half of a 40 s song counts
            d.event(T0 + 3 * MINUTE, EventBody.Play(songB, 30_000)),
            d.event(T0 + 4 * MINUTE, EventBody.TapePlay(track("tape1"), 600_000, speedPercent = 125)),
        )
        val state = LibraryProjection.build(log)
        assertEquals(listOf("B", "S", "A"), state.history.map { it.track.id })
        assertEquals(Source.RADIO, state.history.last().source)
        assertEquals("s1", state.history.last().sessionId)
        assertEquals(mapOf("A" to 1, "S" to 1, "B" to 1), state.playCounts)
        assertEquals(130_000L, state.listenedMs["A"])
        assertEquals(180_000L, state.totalListenedMs)
        assertEquals(125, state.tapeHistory.single().speedPercent)
        assertEquals(5, state.eventCount)
    }

    @Test
    fun `not interested can be undone`() {
        val d = TestDevice("d")
        val log = listOf(
            d.event(T0, EventBody.NotInterested(Subject.Track(songA))),
            d.event(T0 + 1, EventBody.NotInterested(Subject.Track(songB))),
            d.event(T0 + 2, EventBody.NotInterested(Subject.Artist("Nickelback"))),
            d.event(T0 + 3, EventBody.Interested(Subject.Track(songA))),
        )
        val state = LibraryProjection.build(log)
        assertEquals(listOf("B"), state.notInterestedTracks.map { it.id })
        assertEquals(listOf("Nickelback"), state.notInterestedArtists)
    }

    @Test
    fun `newest non-empty metadata wins`() {
        val d = TestDevice("d")
        val log = listOf(
            d.event(T0, EventBody.Play(TrackRef("A", "Old title", "Artist", 200_000, "thumb1"), 60_000)),
            d.event(T0 + 1, EventBody.Like(Subject.Track(TrackRef("A", title = "New title")))),
        )
        val state = LibraryProjection.build(log)
        assertEquals(TrackRef("A", "New title", "Artist", 200_000, "thumb1"), state.tracks["A"])
        assertEquals("New title", state.history.single().track.title)
        assertEquals("thumb1", state.likedTracks.single().track.thumbnailUrl)
    }

    @Test
    fun `the projector refuses an event older than the last one applied`() {
        val d = TestDevice("d")
        val newer = d.event(T0 + 10, EventBody.Like(Subject.Track(songA)))
        val older = d.event(T0, EventBody.Like(Subject.Track(songB)))
        val p = LibraryProjector()
        assertTrue(p.apply(newer))
        assertFalse(p.apply(older))
        assertEquals(listOf("A"), p.snapshot().likedTracks.map { it.track.id })
    }
}
