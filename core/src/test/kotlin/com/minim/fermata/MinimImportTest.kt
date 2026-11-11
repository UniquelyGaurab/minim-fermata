package com.minim.fermata

import com.minim.fermata.log.DAY
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventId
import com.minim.fermata.log.HOUR
import com.minim.fermata.log.InMemoryEventStore
import com.minim.fermata.log.MINUTE
import com.minim.fermata.log.Source
import com.minim.fermata.log.T0
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 5.9.0 importer, on rows shaped exactly like Minim 5.9.0 writes them (see FermataLogger in
 * Minim): a play row opened at track start and updated in place, a skip row when you moved on
 * with more than 10 s left, a queued row for Play next, one play row per repeat-one loop, tape
 * rows with their speed, and the old `plays` / `tape_plays` rows written at the 30 s mark.
 */
class MinimImportTest {
    private val dev = "3f9a0c5e1b7d2468"
    private val otherPhone = "9c0ffee000000001"
    private val s1 = "7d6a1e7c-3c0b-4f4e-9f53-1b2a9d6e8c11"
    private val utc: ZoneId = ZoneOffset.UTC

    private fun eid(at: Long, track: String) = java.lang.Long.toString(at, 36) + "_" + track

    private fun row(
        id: Long, type: String, track: String, ts: Long?,
        listened: Long? = null, position: Long? = null, prev: String? = null,
        source: String? = "radio", duration: Long? = 200_000, device: String? = dev,
        title: String? = "Song $track", artist: String? = "Artist $track", speed: Int? = null,
        session: String? = s1, hour: Int? = 19, dow: Int? = 3,
    ) = FermataEventRow(
        id, type, track, title, artist, duration, ts, hour, dow, session, prev, source,
        listened, position, device, speed,
    )

    /** One evening on one phone, as 5.9.0 logs it. */
    private val rows = listOf(
        row(1, "play", "A", T0, listened = 182_000, duration = 229_000),
        row(2, "queued", "C", T0 + MINUTE, prev = "A", source = "search"),
        row(3, "play", "B", T0 + 3 * MINUTE + 5_000, listened = 4_000, prev = "A"),
        row(4, "skip", "B", T0 + 3 * MINUTE + 9_000, position = 4_000, prev = "A"),
        row(5, "play", "C", T0 + 3 * MINUTE + 9_000, listened = 200_000, prev = "B", source = "queue"),
        // Repeat one: one row per loop, each with the same track before it.
        row(6, "play", "C", T0 + 6 * MINUTE + 30_000, listened = 200_000, prev = "C", source = "queue"),
        row(7, "play", "C", T0 + 9 * MINUTE + 50_000, listened = 200_000, prev = "C", source = "queue"),
        row(
            8, "tape_play", "V", T0 + 14 * MINUTE, listened = 600_000, prev = "C", source = "tape",
            duration = 1_800_000, artist = "Some Channel", speed = 150,
        ),
        // The last row: the app was killed, so it holds the heard time as of the last save.
        // Artist page plays are "unknown"; title and artist come from the tracks table.
        row(9, "play", "D", T0 + 30 * MINUTE, listened = 25_000, prev = "V", source = "unknown", title = null, artist = null, duration = null),
    )

    private val plays = listOf(
        // Before 5.9.0: only in the old table.
        LegacyPlayRow(1, "E", T0 - DAY, 150_000, eid(T0 - DAY, "E")),
        // The same plays as rows 1, 5, 6, 7 and 9, written when each reached 30 s.
        LegacyPlayRow(2, "A", T0 + 30_000, 185_000, eid(T0 + 30_000, "A")),
        LegacyPlayRow(3, "C", T0 + 3 * MINUTE + 39_000, 200_000, eid(T0 + 3 * MINUTE + 39_000, "C")),
        LegacyPlayRow(4, "C", T0 + 7 * MINUTE, 200_000, eid(T0 + 7 * MINUTE, "C")),
        LegacyPlayRow(5, "C", T0 + 10 * MINUTE + 20_000, 200_000, eid(T0 + 10 * MINUTE + 20_000, "C")),
        // Killed at 31 s heard: the old table saved 31 s, the 5.9.0 row only 25 s.
        LegacyPlayRow(6, "D", T0 + 30 * MINUTE + 30_000, 31_000, eid(T0 + 30 * MINUTE + 30_000, "D")),
        // Another phone's play of A, synced in through Firestore: stays a legacy play.
        LegacyPlayRow(7, "A", T0 + 2 * HOUR, 90_000, eid(T0 + 2 * HOUR, "A")),
    )

    private val tapePlays = listOf(
        LegacyTapePlayRow(1, "W", "Old tape", "Chan", "", 3_600, T0 - 2 * DAY),
        LegacyTapePlayRow(2, "V", "Tape V", "Some Channel", "", 1_800, T0 + 14 * MINUTE + 30_000),
    )

    private val tracks = listOf(
        LegacyTrackRow("D", "Delta", "Artist D", 240, "https://i.ytimg.com/vi/D/hq.jpg"),
        LegacyTrackRow("E", "Echo", "Artist E", 180, ""),
        LegacyTrackRow("A", "Song A", "Artist A", 229, "https://i.ytimg.com/vi/A/hq.jpg"),
    )

    private val tables = MinimTables(rows, plays, tapePlays, tracks)

    private class FixedClock(val now: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private fun fermata(device: String, store: InMemoryEventStore = InMemoryEventStore()) =
        Fermata(store, device, FixedClock(T0 + 3 * HOUR), utc)

    @Test
    fun `every 5_9_0 row keeps its own id, time and context`() {
        val r = MinimImport.convert(tables, utc)
        val byId = r.events.associateBy { it.id }
        assertEquals(9, r.fromFermataEvents)
        for (i in 1L..9L) assertTrue(EventId(dev, i) in byId, "row $i")

        val a = byId.getValue(EventId(dev, 1))
        assertEquals(T0, a.timestamp)
        assertEquals(19, a.ctx.hour) // the row's own hour and weekday, not recomputed
        assertEquals(3, a.ctx.dayOfWeek)
        assertEquals(s1, a.ctx.sessionId) // UUID session ids are kept as they are
        assertEquals(Source.RADIO, a.ctx.source)
        assertNull(a.ctx.previousTrackId)

        val skip = byId.getValue(EventId(dev, 4)).body as EventBody.Skip
        assertEquals(4_000, skip.positionMs)
        assertEquals("A", byId.getValue(EventId(dev, 4)).ctx.previousTrackId)
        assertEquals(EventBody.Play::class, byId.getValue(EventId(dev, 3)).body::class) // a skip is a play row plus a skip row

        assertEquals(Source.SEARCH, byId.getValue(EventId(dev, 2)).ctx.source)
        assertTrue(byId.getValue(EventId(dev, 2)).body is EventBody.Queued)

        val tape = byId.getValue(EventId(dev, 8)).body as EventBody.TapePlay
        assertEquals(150, tape.speedPercent)
        assertEquals(600_000, tape.listenedMs)
        assertEquals("Some Channel", tape.tape.artist)

        val d = byId.getValue(EventId(dev, 9))
        assertEquals(Source.UNKNOWN, d.ctx.source)
        val dPlay = d.body as EventBody.Play
        assertEquals("Delta", dPlay.track.title) // filled in from tracks
        assertEquals(240_000, dPlay.track.durationMs)
        assertEquals("https://i.ytimg.com/vi/D/hq.jpg", dPlay.track.thumbnailUrl)
    }

    @Test
    fun `a play in both tables counts once and keeps the larger heard time`() {
        val r = MinimImport.convert(tables, utc)
        assertEquals(6, r.matched) // A, three loops of C, D, and the tape V
        assertEquals(3, r.fromLegacy) // E, the other phone's A, tape W
        assertEquals(0, r.skippedRows)
        val byId = r.events.associateBy { it.id }

        val a = byId.getValue(EventId(dev, 1))
        assertEquals(MinimImport.playId(plays[1]), a.ctx.replaces)
        assertEquals(185_000, (a.body as EventBody.Play).listenedMs)
        val d = byId.getValue(EventId(dev, 9))
        assertEquals(31_000, (d.body as EventBody.Play).listenedMs) // counted, as Minim counted it
        assertEquals(MinimImport.tapeId(tapePlays[1]), byId.getValue(EventId(dev, 8)).ctx.replaces)
        // The loops pair up one by one.
        assertEquals(
            listOf(plays[2], plays[3], plays[4]).map(MinimImport::playId),
            listOf(5L, 6L, 7L).map { byId.getValue(EventId(dev, it)).ctx.replaces },
        )
        // Matched old rows are not written a second time.
        for (p in listOf(plays[1], plays[2], plays[3], plays[4], plays[5])) assertTrue(MinimImport.playId(p) !in byId)
        assertTrue(MinimImport.playId(plays[0]) in byId)
        assertTrue(MinimImport.playId(plays[6]) in byId)

        val lib = fermata(dev).apply { importMinim(tables) }.library()
        assertEquals(mapOf("A" to 2, "C" to 3, "D" to 1, "E" to 1), lib.playCounts)
        assertEquals(listOf("V", "W"), lib.tapeHistory.map { it.tape.id })
    }

    @Test
    fun `the legacy copy of a matched play never counts, wherever it turns up`() {
        // This phone imports both tables.
        val phone = fermata(dev).apply { importMinim(tables) }
        val before = phone.library().playCounts
        // Another phone (or the Firestore copy) only has the old plays table, with the same rows.
        val other = fermata(otherPhone).apply { importMinim(MinimTables(plays = plays, tracks = tracks)) }
        assertEquals(mapOf("A" to 2, "C" to 3, "D" to 1, "E" to 1), other.library().playCounts)

        assertTrue(phone.merge(other.eventsAfter(emptyMap())) > 0)
        assertEquals(before, phone.library().playCounts)
        assertTrue(other.merge(phone.eventsAfter(emptyMap())) > 0)
        assertEquals(before, other.library().playCounts)
        assertEquals(phone.library(), other.library())
        assertEquals(phone.taste(), other.taste())
    }

    @Test
    fun `importing twice adds nothing and the counter moves past the rows`() {
        val store = InMemoryEventStore()
        val f = fermata(dev, store)
        val first = f.importMinim(tables)
        assertEquals(first.events.size, first.added)
        assertEquals(12, first.added) // 9 rows + 3 legacy
        assertEquals(0, f.importMinim(tables).added)
        assertEquals(MinimImport.convert(tables, utc), MinimImport.convert(tables, utc))
        // The next event this phone records cannot reuse a 5.9.0 row's id.
        assertEquals(EventId(dev, 10), f.record(EventBody.Play(com.minim.fermata.log.track("Z"), 60_000)).id)
        // Nor after a restart.
        assertEquals(EventId(dev, 11), fermata(dev, store).record(EventBody.Play(com.minim.fermata.log.track("Z"), 60_000)).id)
    }

    @Test
    fun `a restored database keeps the old phone's rows apart from the new phone's`() {
        // Android restored the database onto a new phone: rows 1 to 3 came from the old phone,
        // the new phone (new device id) went on from row 4.
        val restored = listOf(
            row(1, "play", "A", T0, listened = 100_000, device = otherPhone),
            row(2, "play", "B", T0 + 2 * MINUTE, listened = 100_000, device = otherPhone),
            row(3, "play", "C", T0 + 4 * MINUTE, listened = 100_000, device = otherPhone),
            row(4, "play", "A", T0 + DAY, listened = 100_000),
        )
        val oldPlays = listOf(
            LegacyPlayRow(1, "A", T0 + 30_000, 100_000, eid(T0 + 30_000, "A")),
            LegacyPlayRow(2, "A", T0 + DAY + 30_000, 100_000, eid(T0 + DAY + 30_000, "A")),
        )
        val r = MinimImport.convert(MinimTables(restored, oldPlays), utc)
        assertEquals(
            listOf(EventId(otherPhone, 1), EventId(otherPhone, 2), EventId(otherPhone, 3), EventId(dev, 4)),
            r.events.map { it.id },
        )
        assertEquals(2, r.matched)
        // The old phone, importing its own copy of rows 1 to 3, agrees on their ids.
        val oldPhone = fermata(otherPhone).apply { importMinim(MinimTables(restored.take(3), oldPlays.take(1))) }
        val newPhone = fermata(dev).apply { importMinim(MinimTables(restored, oldPlays)) }
        assertEquals(0, newPhone.merge(oldPhone.eventsAfter(emptyMap())))
        assertEquals(2, newPhone.library().playCounts["A"])
    }

    @Test
    fun `odd rows are handled rather than trusted`() {
        val odd = listOf(
            row(1, "play", "A", T0, listened = 50_000, device = null), // no device id
            row(2, "play", "B", null, listened = 50_000), // no timestamp: cannot be placed
            row(3, "mystery", "C", T0 + MINUTE), // unknown type
            row(4, "play", " ", T0 + MINUTE), // blank track id
            row(5, "play", "D", T0 + 2 * MINUTE, listened = null, hour = 99, dow = null, source = "ARTIST_PAGE", session = "", prev = ""),
            row(6, "skip", "D", T0 + 2 * MINUTE + 1_000, position = null),
            row(7, "tape_play", "V", T0 + 3 * MINUTE, listened = -5, speed = null),
        )
        val r = MinimImport.convert(MinimTables(odd), utc)
        assertEquals(3, r.skippedRows)
        assertEquals(4, r.events.size)
        val first = r.events[0]
        assertEquals(EventId.LEGACY_DEVICE, first.id.device)
        assertEquals(first.id, MinimImport.convert(MinimTables(odd), utc).events[0].id) // deterministic
        val d = r.events[1]
        assertEquals(0, d.ctx.hour) // recomputed from the timestamp (T0 is midnight UTC)
        assertEquals(3, d.ctx.dayOfWeek)
        assertEquals(Source.UNKNOWN, d.ctx.source)
        assertNull(d.ctx.sessionId)
        assertNull(d.ctx.previousTrackId)
        assertEquals(0, (d.body as EventBody.Play).listenedMs)
        assertEquals(0, (r.events[2].body as EventBody.Skip).positionMs)
        val tape = r.events[3].body as EventBody.TapePlay
        assertEquals(100, tape.speedPercent)
        assertEquals(0, tape.listenedMs)
    }

    @Test
    fun `old plays without a sync id get the id Minim would give them`() {
        val withId = LegacyPlayRow(1, "A", T0, 60_000, eid(T0, "A"))
        assertEquals(MinimImport.playId(withId), MinimImport.playId(withId.copy(eventId = null)))
    }
}
