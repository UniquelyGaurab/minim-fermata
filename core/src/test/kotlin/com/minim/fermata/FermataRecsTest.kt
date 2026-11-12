package com.minim.fermata

import com.minim.fermata.log.DAY
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventStore
import com.minim.fermata.log.Source
import com.minim.fermata.log.HOUR
import com.minim.fermata.log.InMemoryEventStore
import com.minim.fermata.log.MINUTE
import com.minim.fermata.log.Subject
import com.minim.fermata.log.T0
import com.minim.fermata.log.TrackRef
import com.minim.fermata.log.track
import com.minim.fermata.recs.DropReason
import com.minim.fermata.recs.RecsConfig
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FermataRecsTest {
    private class ManualClock(var now: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private val clock = ManualClock(T0)
    private val config = RecsConfig(coldStartMinPlays = 5, coldStartMinTracks = 3, radioMinKeep = 0)

    private fun fermata(store: EventStore = InMemoryEventStore(), device: String = "phoneA") =
        Fermata(store, device, clock, ZoneOffset.UTC, config = config)

    private fun Fermata.play(t: TrackRef, ms: Long = 180_000) {
        record(EventBody.Play(t, ms))
        clock.now += 4 * MINUTE
    }

    private fun Fermata.warm() = repeat(6) { play(track("w$it", "Warm")) }

    @Test
    fun requestsReturnNullUntilWarmedUp() {
        val f = fermata()
        f.warm()
        assertEquals(FermataRecs.State.NOT_READY, f.recs.state())
        assertNull(f.recs.radio(listOf(track("x"))), "not built yet: keep the current order")
        assertNull(f.recs.onRepeat())
        assertTrue(f.recs.warmUp())
        assertEquals(FermataRecs.State.READY, f.recs.state())
        assertNotNull(f.recs.radio(listOf(track("x"))))
        assertEquals(6, f.recs.lastBuild?.events)
    }

    @Test
    fun coldStartStateReturnsNull() {
        val f = fermata()
        f.play(track("a"))
        f.recs.warmUp()
        assertEquals(FermataRecs.State.COLD_START, f.recs.state())
        assertNull(f.recs.radio(listOf(track("a"), track("b"))))
        assertEquals(emptyList(), f.recs.onRepeat())
        // History grows past the threshold: no rebuild needed, the model updates as events come in.
        f.warm()
        assertEquals(FermataRecs.State.READY, f.recs.state())
        assertFalse(f.recs.needsWarmUp())
    }

    @Test
    fun newEventsUpdateTheModelWithoutARebuild() {
        val f = fermata()
        f.warm()
        f.recs.warmUp()
        val built = f.recs.lastBuild
        val s = track("s")
        f.record(EventBody.Skip(s, 3_000))
        f.record(EventBody.Skip(s, 2_000))
        assertFalse(f.recs.needsWarmUp())
        val out = assertNotNull(f.recs.radio(listOf(s, track("t"))))
        assertEquals(DropReason.SKIPPED_EARLY, out.dropped.single().reason)
        f.play(track("t"))
        assertTrue(f.recs.heardRecently("t"))
        clock.now += 3 * HOUR
        assertFalse(f.recs.heardRecently("t"), "the recent window is three hours")
        assertTrue(built === f.recs.lastBuild, "no rebuild happened")
    }

    @Test
    fun aMergeKeepsServingThenRebuildsOnWarmUp() {
        val a = fermata(device = "phoneA")
        val b = fermata(device = "phoneB")
        a.warm()
        a.recs.warmUp()
        val liked = track("liked")
        b.record(EventBody.Like(Subject.Track(liked)))
        a.merge(b.events())
        assertTrue(a.recs.needsWarmUp())
        assertNotNull(a.recs.radio(listOf(liked)), "still serving the model it has")
        a.recs.warmUp()
        assertFalse(a.recs.needsWarmUp())
        val input = (0 until 5).map { track("p$it", "P$it") } + liked
        val ids = assertNotNull(a.recs.radio(input)).tracks.map { it.id }
        assertTrue(ids.indexOf("liked") <= 1, "the merged like moves it from last to the top two: $ids")
    }

    @Test
    fun aMergeOfNonTasteEventsDoesNotMakeTheModelStale() {
        val a = fermata(device = "phoneA")
        val b = fermata(device = "phoneB")
        a.warm()
        a.recs.warmUp()
        b.record(EventBody.PlaylistCreate("pl1", "Mix"))
        a.merge(b.events())
        assertFalse(a.recs.needsWarmUp())
    }

    @Test
    fun aClearDropsTheModelSoClearedPlaysAreNeverUsed() {
        val f = fermata()
        f.warm()
        val t = track("t")
        f.play(t)
        f.recs.warmUp()
        assertTrue(f.recs.heardRecently("t"))
        f.clearHistory()
        assertEquals(FermataRecs.State.NOT_READY, f.recs.state())
        f.recs.warmUp()
        assertEquals(FermataRecs.State.COLD_START, f.recs.state())
        assertFalse(f.recs.heardRecently("t"))
    }

    @Test
    fun aRolledBackTransactionDropsTheModel() {
        val f = fermata()
        f.warm()
        f.recs.warmUp()
        f.transaction { f.record(EventBody.Skip(track("z"), 1_000)) }
        f.rolledBack()
        assertEquals(FermataRecs.State.NOT_READY, f.recs.state())
    }

    @Test
    fun recordsMadeDuringABuildAreNotLost() {
        val inner = InMemoryEventStore()
        val store = object : EventStore by inner {
            val reading = CountDownLatch(1)
            val release = CountDownLatch(1)
            override fun ofTypes(types: Set<String>): List<com.minim.fermata.log.Event> {
                val events = inner.ofTypes(types)
                reading.countDown()
                release.await(5, TimeUnit.SECONDS)
                return events
            }
        }
        val f = fermata(store)
        f.warm()
        val builder = thread { f.recs.warmUp() }
        assertTrue(store.reading.await(5, TimeUnit.SECONDS))
        // Recording does not wait for the build.
        val s = track("s")
        f.record(EventBody.Skip(s, 1_000))
        f.record(EventBody.Skip(s, 1_000))
        store.release.countDown()
        builder.join(5_000)
        val out = assertNotNull(f.recs.radio(listOf(s)))
        assertEquals(DropReason.SKIPPED_EARLY, out.dropped.single().reason)
    }

    @Test
    fun storeReadsOnlyTasteEvents() {
        val store = InMemoryEventStore()
        val f = fermata(store)
        f.warm()
        f.record(EventBody.PlaylistCreate("pl", "Mix"))
        f.recs.warmUp()
        assertEquals(6, f.recs.lastBuild?.events)
        assertEquals(7, store.all().size)
    }

    @Test
    fun shelvesComeFromTheLiveModel() {
        val f = fermata()
        f.warm()
        f.recs.warmUp()
        val hot = track("hot", "Hot")
        repeat(3) { f.play(hot) }
        assertEquals(listOf("hot"), f.recs.onRepeat()!!.map { it.track.id })
        repeat(3) { f.play(track("h2", "Hot")) }
        assertEquals("Hot", f.recs.becauseYouLike(artists = 1)!!.single().artist)
        clock.now += 40 * DAY
        // "Warm" tracks had one play each; "hot" had three: neither reaches five plays, so no forgotten favourites yet.
        assertEquals(emptyList(), f.recs.forgottenFavourites())
        repeat(2) { f.play(hot) }
        clock.now += 40 * DAY
        assertEquals(listOf("hot"), f.recs.forgottenFavourites()!!.map { it.track.id })
    }

    @Test
    fun aPlayRecordedWithItsStartTimeAfterLaterEventsStillCounts() {
        val f = fermata()
        f.warm()
        f.recs.warmUp()
        val t = track("t")
        val start = clock.now
        clock.now += 3 * MINUTE
        f.record(EventBody.Queued(track("q")))
        // An app recording a play after the fact, with the time it started.
        f.recordAt(EventBody.Play(t, 180_000), start, Source.UNKNOWN)
        assertFalse(f.recs.needsWarmUp(), "applied as it came, no rebuild")
        assertTrue(f.recs.heardRecently("t"))
        val live = f.recs.rank(listOf(t, track("q")))
        val rebuilt = fermata(InMemoryEventStore(f.events())).also { it.recs.warmUp() }.recs.rank(listOf(t, track("q")))
        assertEquals(rebuilt, live)
    }
}
