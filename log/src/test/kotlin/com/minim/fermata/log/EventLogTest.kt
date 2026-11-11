package com.minim.fermata.log

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventLogTest {
    /** A random log for one device: plays, likes and unlikes at random times. */
    private fun randomLog(device: String, seed: Int, size: Int = 40): List<Event> {
        val rnd = Random(seed)
        val d = TestDevice(device)
        var t = T0
        return List(size) {
            t += rnd.nextLong(0, 10 * MINUTE)
            val tr = track("t${rnd.nextInt(8)}")
            val body = when (rnd.nextInt(3)) {
                0 -> EventBody.Play(tr, rnd.nextLong(0, 200_000))
                1 -> EventBody.Like(Subject.Track(tr))
                else -> EventBody.Unlike(Subject.Track(tr))
            }
            d.event(t, body)
        }
    }

    private val a = randomLog("aaaa", 1)
    private val b = randomLog("bbbb", 2)
    private val c = randomLog("cccc", 3)

    @Test
    fun `effective drops replaced events whatever order they arrive in`() {
        val old = Event(EventId.legacy("play|x"), EventContext.at(T0), EventBody.Play(track("A"), 40_000))
        val other = Event(EventId("dev", 2), EventContext.at(T0 + MINUTE), EventBody.Play(track("B"), 40_000))
        val replacing = Event(
            EventId("dev", 1),
            EventContext.at(T0 - 30_000, replaces = old.id),
            EventBody.Play(track("A"), 45_000),
        )
        val expected = listOf(replacing, other)
        assertEquals(expected, EventLog.effective(listOf(old, other, replacing)))
        assertEquals(expected, EventLog.effective(listOf(replacing, old, other, old)))
        // The replaced event stays in the log itself: merges are still a plain union.
        assertEquals(3, EventLog.merge(listOf(old), listOf(replacing, other)).size)
        // Without the replacing event, the old one counts.
        assertEquals(listOf(old, other), EventLog.effective(listOf(other, old)))
    }

    @Test
    fun `merge is commutative`() {
        assertEquals(EventLog.merge(a, b), EventLog.merge(b, a))
        assertEquals(EventLog.merge(a, b, c), EventLog.merge(c, a, b))
        // Shuffled input gives the same result too.
        assertEquals(EventLog.merge(a, b), EventLog.merge((a + b).shuffled(Random(7))))
    }

    @Test
    fun `merge is idempotent`() {
        val ab = EventLog.merge(a, b)
        assertEquals(EventLog.canonical(a), EventLog.merge(a, a))
        assertEquals(ab, EventLog.merge(ab, b))
        assertEquals(ab, EventLog.merge(ab, ab))
    }

    @Test
    fun `merge is associative`() {
        assertEquals(EventLog.merge(EventLog.merge(a, b), c), EventLog.merge(a, EventLog.merge(b, c)))
    }

    @Test
    fun `merge keeps every distinct event once`() {
        val merged = EventLog.merge(a, b, a.take(10), b.takeLast(5))
        assertEquals(a.size + b.size, merged.size)
        assertEquals(merged.size, merged.map { it.id }.toSet().size)
    }

    @Test
    fun `canonical order is timestamp then device then counter`() {
        val x = TestDevice("x")
        val y = TestDevice("y")
        val e1 = y.event(T0, EventBody.Queued(track("1")))
        val e2 = x.event(T0, EventBody.Queued(track("2")))
        val e3 = x.event(T0, EventBody.Queued(track("3")))
        val e0 = y.event(T0 - 1, EventBody.Queued(track("0")))
        assertEquals(listOf(e0, e2, e3, e1), EventLog.canonical(listOf(e1, e3, e0, e2)))
    }

    @Test
    fun `a corrupt duplicate id resolves the same way in any order`() {
        val id = EventId("z", 1)
        val ctx = EventContext.at(T0)
        val one = Event(id, ctx, EventBody.Queued(track("1")))
        val two = Event(id, ctx, EventBody.Queued(track("2")))
        assertEquals(EventLog.merge(listOf(one), listOf(two)), EventLog.merge(listOf(two), listOf(one)))
        assertEquals(1, EventLog.merge(listOf(one), listOf(two)).size)
    }

    @Test
    fun `heads and after skip what a peer has seen`() {
        val merged = EventLog.merge(a, b)
        val heads = EventLog.heads(merged)
        assertEquals(mapOf("aaaa" to a.size.toLong(), "bbbb" to b.size.toLong()), heads)
        assertTrue(EventLog.after(merged, heads).isEmpty())
        val partial = mapOf("aaaa" to 30L)
        val missing = EventLog.after(merged, partial)
        assertEquals(10 + b.size, missing.size)
    }

    @Test
    fun `legacy events are left out of heads and always offered`() {
        val legacy = Event(EventId.legacy("play|abc"), EventContext.at(T0), EventBody.Play(track("1"), 40_000))
        val log = a + legacy
        assertFalse(EventLog.heads(log).containsKey(EventId.LEGACY_DEVICE))
        assertEquals(listOf(legacy), EventLog.after(log, EventLog.heads(log)))
    }

    @Test
    fun `event ids round-trip and legacy ids are deterministic`() {
        val id = EventId("3f9a0c5e1b7d2468", 42)
        assertEquals("3f9a0c5e1b7d2468:42", id.toString())
        assertEquals(id, EventId.parse(id.toString()))
        assertEquals(EventId.legacy("play|e1"), EventId.legacy("play|e1"))
        assertNotEquals(EventId.legacy("play|e1"), EventId.legacy("play|e2"))
        assertTrue(EventId.legacy("play|e1").counter > 0)
        assertFailsWith<IllegalArgumentException> { EventId("x", 0) }
        assertFailsWith<IllegalArgumentException> { EventId.parse("nocounter") }
    }

    @Test
    fun `device ids are random hex`() {
        val id = DeviceIds.random()
        assertTrue(id.matches(Regex("[0-9a-f]{16}")))
        assertNotEquals(id, DeviceIds.random())
    }

    @Test
    fun `context reads hour and day of week from the wall clock`() {
        // 2026-11-11 is a Wednesday; 14:30 UTC.
        val ctx = EventContext.at(T0 + 14 * HOUR + 30 * MINUTE, java.time.ZoneOffset.UTC)
        assertEquals(14, ctx.hour)
        assertEquals(3, ctx.dayOfWeek)
        // In India (UTC+5:30) the same instant is 20:00.
        assertEquals(20, EventContext.at(ctx.timestamp, java.time.ZoneId.of("Asia/Kolkata")).hour)
    }
}

class InMemoryEventStoreTest {
    @Test
    fun `append ignores known ids and keeps canonical order`() {
        val store = InMemoryEventStore()
        val d = TestDevice("d")
        val late = d.event(T0 + HOUR, EventBody.Queued(track("late")))
        val early = Event(EventId("e", 1), EventContext.at(T0), EventBody.Queued(track("early")))
        assertNull(store.maxTimestamp())
        assertTrue(store.append(late))
        assertFalse(store.append(late))
        assertEquals(1, store.appendAll(listOf(early, late)))
        assertEquals(listOf(early, late), store.all())
        assertEquals(2, store.count())
        assertTrue(store.contains(early.id))
        assertEquals(mapOf("d" to 1L, "e" to 1L), store.heads())
        assertEquals(T0 + HOUR, store.maxTimestamp())
        assertEquals(listOf(late), store.after(mapOf("e" to 1L)))
    }
}
