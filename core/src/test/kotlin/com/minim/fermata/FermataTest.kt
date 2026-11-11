package com.minim.fermata

import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventId
import com.minim.fermata.log.HOUR
import com.minim.fermata.log.InMemoryEventStore
import com.minim.fermata.log.MINUTE
import com.minim.fermata.log.Source
import com.minim.fermata.log.Subject
import com.minim.fermata.log.T0
import com.minim.fermata.log.TasteEditKind
import com.minim.fermata.log.track
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A clock the test moves by hand. */
private class ManualClock(var now: Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(now)
}

class FermataTest {
    private val a = track("A", "Alpha")
    private val b = track("B", "Beta")
    private val c = track("C", "Gamma")

    private fun fermata(device: String, clock: ManualClock, store: InMemoryEventStore = InMemoryEventStore()) =
        Fermata(store, device, clock, ZoneOffset.UTC)

    @Test
    fun `record assigns ids, sessions and previous tracks`() {
        val clock = ManualClock(T0)
        val f = fermata("dev", clock)
        val e1 = f.record(EventBody.Play(a, 120_000), Source.RADIO)
        clock.now += 3 * MINUTE
        val e2 = f.record(EventBody.Skip(b, 4_000), Source.RADIO)
        clock.now += MINUTE
        val like = f.record(EventBody.Like(Subject.Track(c)))
        clock.now += MINUTE
        val e3 = f.record(EventBody.Play(c, 100_000))
        clock.now += 2 * HOUR // a long pause starts a new session
        val e4 = f.record(EventBody.Play(a, 100_000))

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), listOf(e1, e2, like, e3, e4).map { it.id.counter })
        assertEquals(e1.ctx.sessionId, e3.ctx.sessionId)
        assertNotEquals(e1.ctx.sessionId, e4.ctx.sessionId)
        assertNull(e1.ctx.previousTrackId)
        assertEquals("A", e2.ctx.previousTrackId)
        assertNull(like.ctx.previousTrackId)
        assertEquals("B", e3.ctx.previousTrackId)
        assertNull(e4.ctx.previousTrackId)
        assertEquals(Source.RADIO, e2.ctx.source)
    }

    @Test
    fun `a skip of the track just played keeps that play's previous track, as 5_9_0 rows do`() {
        val clock = ManualClock(T0)
        val f = fermata("dev", clock)
        f.record(EventBody.Play(a, 120_000))
        clock.now += 3 * MINUTE
        val playB = f.record(EventBody.Play(b, 4_000))
        val skipB = f.record(EventBody.Skip(b, 4_000))
        clock.now += 5_000
        val playC = f.record(EventBody.Play(c, 100_000))
        assertEquals("A", playB.ctx.previousTrackId)
        assertEquals("A", skipB.ctx.previousTrackId)
        assertEquals("B", playC.ctx.previousTrackId)
        // Exactly 30 minutes of nothing starts a new session, as in Minim 5.9.0.
        clock.now += 30 * MINUTE
        assertNotEquals(playC.ctx.sessionId, f.record(EventBody.Play(a, 100_000)).ctx.sessionId)
    }

    @Test
    fun `the library and taste follow recorded events`() {
        val clock = ManualClock(T0)
        val f = fermata("dev", clock)
        f.record(EventBody.PlaylistCreate("p", "Mine"))
        assertEquals(0, f.library().likedTracks.size) // builds the projection, then stays live
        clock.now += 1
        f.record(EventBody.Like(Subject.Track(a)))
        f.record(EventBody.PlaylistAdd("p", a))
        f.record(EventBody.PlaylistAdd("p", b, index = 0))
        repeat(3) { f.record(EventBody.Play(c, 200_000)) }
        val lib = f.library()
        assertTrue(lib.isLiked("A"))
        assertEquals(listOf("B", "A"), lib.playlist("p")!!.tracks.map { it.id })
        assertEquals(3, lib.playCounts["C"])
        assertEquals(listOf("C", "A"), f.taste(now = clock.now).topTracks.map { it.track.id })
        f.edit(TasteEditKind.RESET, Subject.Track(c))
        assertEquals(listOf("A"), f.taste(now = clock.now).topTracks.map { it.track.id })
    }

    @Test
    fun `rank returns the input order untouched for a new listener`() {
        val f = fermata("dev", ManualClock(T0))
        val candidates = listOf(c, a, b)
        assertEquals(0.0, f.confidence())
        assertEquals(candidates, f.rank(candidates).map { it.track })
    }

    @Test
    fun `a new event never sorts before the newest event in the log`() {
        // The tablet's clock is an hour behind the phone's.
        val phone = fermata("phone", ManualClock(T0 + HOUR))
        val tabletClock = ManualClock(T0)
        val tablet = fermata("tablet", tabletClock)
        val like = phone.record(EventBody.Like(Subject.Track(a)))
        tablet.merge(listOf(like))
        val unlike = tablet.record(EventBody.Unlike(Subject.Track(a)))
        assertTrue(unlike.timestamp > like.timestamp)
        assertEquals(0, unlike.ctx.hour) // the wall clock still gives the context
        assertFalse(tablet.library().isLiked("A"))
        phone.merge(listOf(unlike))
        assertFalse(phone.library().isLiked("A"))
    }

    @Test
    fun `two phones converge after exchanging events`() {
        val phone = fermata("phone", ManualClock(T0))
        val tablet = fermata("tablet", ManualClock(T0 + 30_000))
        phone.record(EventBody.PlaylistCreate("p", "Shared"))
        tablet.merge(phone.eventsAfter(tablet.heads()))
        phone.record(EventBody.PlaylistAdd("p", a))
        phone.record(EventBody.Like(Subject.Track(b)))
        tablet.record(EventBody.PlaylistAdd("p", c, index = 0))
        tablet.record(EventBody.Play(b, 200_000))

        val toTablet = phone.eventsAfter(tablet.heads())
        val toPhone = tablet.eventsAfter(phone.heads())
        assertEquals(2, toTablet.size)
        assertEquals(2, toPhone.size)
        tablet.merge(toTablet)
        phone.merge(toPhone)
        assertEquals(0, phone.merge(toPhone)) // a second delivery changes nothing

        assertEquals(phone.library(), tablet.library())
        assertEquals(phone.taste(now = T0 + HOUR), tablet.taste(now = T0 + HOUR))
    }

    @Test
    fun `export then import restores everything, and importing twice adds nothing`() {
        val clock = ManualClock(T0)
        val source = fermata("dev", clock)
        source.record(EventBody.Like(Subject.Track(a)))
        source.record(EventBody.PlaylistCreate("p", "Gym"))
        source.record(EventBody.PlaylistAdd("p", b))
        source.record(EventBody.Play(c, 150_000), Source.SEARCH)
        source.importLegacy(EventBody.Play(a, 90_000), T0 - HOUR, "play|legacy-1")
        val out = ByteArrayOutputStream()
        val manifest = source.export(out, appVersion = "6.0.0")
        assertEquals(5, manifest.eventCount)

        // A fresh install (a new device id) restores the backup.
        val restored = fermata("new-install", ManualClock(T0 + HOUR))
        val result = restored.import(ByteArrayInputStream(out.toByteArray()))
        assertEquals(5, result.added)
        assertEquals(source.library(), restored.library())
        assertEquals(0, restored.import(ByteArrayInputStream(out.toByteArray())).added)
        // The new install's own counter starts at 1: its ids cannot clash with the old device's.
        assertEquals(EventId("new-install", 1), restored.record(EventBody.Play(a, 60_000)).id)
    }

    @Test
    fun `legacy imports are deterministic across phones`() {
        val one = fermata("one", ManualClock(T0))
        val two = fermata("two", ManualClock(T0))
        for (f in listOf(one, two)) {
            f.importLegacy(EventBody.Play(a, 90_000), T0 - HOUR, "play|row-17")
            f.importLegacy(EventBody.Like(Subject.Track(a)), T0 - 2 * HOUR, "like|A")
        }
        assertNull(one.importLegacy(EventBody.Play(a, 90_000), T0 - HOUR, "play|row-17"))
        assertEquals(0, one.merge(two.eventsAfter(emptyMap())))
        assertEquals(1, one.library().playCounts["A"])
    }

    @Test
    fun `a restarted Fermata continues its counter from the store`() {
        val store = InMemoryEventStore()
        val clock = ManualClock(T0)
        fermata("dev", clock, store).apply {
            record(EventBody.Play(a, 60_000))
            record(EventBody.Play(b, 60_000))
        }
        val again = fermata("dev", clock, store)
        val next = again.record(EventBody.Play(c, 60_000))
        assertEquals(3, next.id.counter)
        assertTrue(next.timestamp > T0) // still after the newest stored event
        assertEquals(3, again.library().history.size)
    }

    @Test
    fun recordAtUsesTheGivenTimeButKeepsTheClockRule() {
        val clock = ManualClock(T0)
        val f = fermata("p", clock)
        val first = f.record(EventBody.Play(a, 60_000), at = T0 - 5 * MINUTE)
        assertEquals(T0 - 5 * MINUTE, first.timestamp)
        // A time stored inside the body, taken from timestamp() first, matches the event's own.
        val counted = f.timestamp()
        val play = f.record(EventBody.Play(b, 40_000, countedAt = counted), at = counted)
        assertEquals(counted, play.timestamp)
        assertEquals(counted, (play.body as EventBody.Play).countedAt)
        // A time earlier than the newest event is moved to newest + 1 ms, as for record() without it.
        val late = f.record(EventBody.Play(c, 10_000), at = T0 - HOUR)
        assertEquals(play.timestamp + 1, late.timestamp)
    }

    @Test
    fun transactionDropsWhatAFailedBlockRecordedFromTheProjections() {
        val clock = ManualClock(T0)
        val store = InMemoryEventStore()
        val f = fermata("p", clock, store)
        f.record(EventBody.Like(Subject.Track(a)))
        val result = f.transaction { f.record(EventBody.Like(Subject.Track(b))); "ok" }
        assertEquals("ok", result)
        assertEquals(setOf("A", "B"), f.library().likedTracks.map { it.track.id }.toSet())

        // An app database that rolls back: the block records, then the store loses the event.
        val failed = runCatching {
            f.transaction {
                val e = f.record(EventBody.Like(Subject.Track(c)))
                store.removeAll(listOf(e.id))
                error("rolled back")
            }
        }
        assertTrue(failed.isFailure)
        assertEquals(setOf("A", "B"), f.library().likedTracks.map { it.track.id }.toSet())
        // The counter never goes back, so the next id is new even though one was rolled back.
        val next = f.record(EventBody.Like(Subject.Track(c)))
        assertEquals(4L, next.id.counter)
    }

    @Test
    fun transactionHoldsTheLockSoABackgroundMergeWaits() {
        val clock = ManualClock(T0)
        val f = fermata("p", clock)
        val other = fermata("q", ManualClock(T0))
        val incoming = other.record(EventBody.Like(Subject.Track(c)))
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val entered = java.util.concurrent.CountDownLatch(1)
        val merger = Thread {
            entered.await()
            f.merge(listOf(incoming))
            order += "merge"
        }
        merger.start()
        f.transaction {
            entered.countDown()
            Thread.sleep(200)
            f.record(EventBody.Like(Subject.Track(a)))
            order += "write"
        }
        merger.join(5_000)
        assertEquals(listOf("write", "merge"), order)
        assertEquals(2L, f.eventCount())
    }
}
