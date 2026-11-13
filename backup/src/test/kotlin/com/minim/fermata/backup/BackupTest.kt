package com.minim.fermata.backup

import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventContext
import com.minim.fermata.log.EventId
import com.minim.fermata.log.EventLog
import com.minim.fermata.log.FermataInfo
import com.minim.fermata.log.MINUTE
import com.minim.fermata.log.Source
import com.minim.fermata.log.Subject
import com.minim.fermata.log.T0
import com.minim.fermata.log.TasteEditKind
import com.minim.fermata.log.TestDevice
import com.minim.fermata.log.TrackRef
import com.minim.fermata.log.track
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupTest {
    private val full = TrackRef("kJQP7kiw5Fk", "Despacito \"Remix\" — ñ", "Luis Fonsi", 229_000, "https://i.ytimg.com/vi/x/hq.jpg")
    private val bare = TrackRef("bare")

    /** One of every event type, with and without optional fields. */
    internal fun everyType(): List<Event> {
        val d = TestDevice("3f9a0c5e1b7d2468")
        var t = T0
        fun next() = (t + MINUTE).also { t = it }
        return listOf(
            d.event(next(), EventBody.Play(full, 182_000), session = "s1", previous = "prev1", source = Source.RADIO),
            d.event(next(), EventBody.Play(bare, 0)),
            d.event(next(), EventBody.Skip(full, 4_500), session = "s1", source = Source.DISCOVER),
            d.event(next(), EventBody.Queued(bare), source = Source.QUEUE),
            d.event(next(), EventBody.Like(Subject.Track(full))),
            d.event(next(), EventBody.Unlike(Subject.Track(bare))),
            d.event(next(), EventBody.Like(Subject.Artist("Björk"))),
            d.event(next(), EventBody.NotInterested(Subject.Artist("Somebody"))),
            d.event(next(), EventBody.Interested(Subject.Track(full))),
            d.event(next(), EventBody.PlaylistCreate("pl-1", "Late night")),
            d.event(next(), EventBody.PlaylistRename("pl-1", "Later night")),
            d.event(next(), EventBody.PlaylistAdd("pl-1", full)),
            d.event(next(), EventBody.PlaylistAdd("pl-1", bare, index = 0)),
            d.event(next(), EventBody.PlaylistMove("pl-1", "bare", 1)),
            d.event(next(), EventBody.PlaylistRemove("pl-1", "bare")),
            d.event(next(), EventBody.PlaylistDelete("pl-1")),
            d.event(next(), EventBody.TapePlay(full, 900_000, speedPercent = 150), source = Source.TAPE),
            d.event(next(), EventBody.TasteEdit(TasteEditKind.OVER_IT, Subject.Artist("Somebody"))),
            d.event(next(), EventBody.TasteEdit(TasteEditKind.JUST_A_PHASE, Subject.Track(full))),
            d.event(next(), EventBody.Unknown("future_thing", """{"x":1,"nested":{"y":[1,2,"z"]}}""")),
            Event(EventId.legacy("play|old-1"), EventContext.at(T0 - MINUTE, source = Source.IMPORT), EventBody.Play(full, 60_000)),
            // A 5.9.0 play that stands in for the legacy copy of the same play.
            Event(
                EventId("3f9a0c5e1b7d2468", 21),
                EventContext(T0 - 2 * MINUTE, 19, 3, "2b1c7e0a-5d3f-4c1e-9a8b-0f6e5d4c3b2a", null, Source.UNKNOWN, EventId.legacy("play|old-1")),
                EventBody.Play(full, 61_000),
            ),
        )
    }

    @Test
    fun `every event type survives a JSON Lines round trip`() {
        val events = everyType()
        val out = ByteArrayOutputStream()
        Jsonl.write(events, out)
        val text = out.toString(Charsets.UTF_8)
        assertEquals(events.size, text.trim().lines().size)
        assertEquals(events, Jsonl.read(ByteArrayInputStream(out.toByteArray())))
        assertEquals(events, Jsonl.decodeGzip(Jsonl.encodeGzip(events)))
        // Encoding is stable: decode then encode gives the same text.
        for (e in events) assertEquals(EventCodec.encode(e), EventCodec.encode(EventCodec.decode(EventCodec.encode(e))))
    }

    @Test
    fun `the line format is the documented one`() {
        val e = TestDevice("dev").event(T0, EventBody.Play(TrackRef("abc", "T", "A", 1000), 500), session = "s", source = Source.SEARCH)
        assertEquals(
            """{"id":"dev:1","ts":$T0,"hour":0,"dow":3,"session":"s","source":"search","type":"play","data":{"track":{"id":"abc","title":"T","artist":"A","durationMs":1000},"listenedMs":500}}""",
            EventCodec.encode(e),
        )
    }

    @Test
    fun `an event type from a newer version is kept verbatim`() {
        val line = """{"id":"new:7","ts":$T0,"hour":0,"dow":3,"source":"carplay","type":"mood_set","data":{"mood":"calm"}}"""
        val e = EventCodec.decode(line)
        assertEquals(EventBody.Unknown("mood_set", """{"mood":"calm"}"""), e.body)
        assertEquals(Source.UNKNOWN, e.ctx.source)
        assertTrue(EventCodec.encode(e).contains(""""type":"mood_set","data":{"mood":"calm"}"""))
        // A known type with a body this version cannot read is kept too, not dropped.
        val odd = EventCodec.decode("""{"id":"new:8","ts":$T0,"hour":0,"dow":3,"type":"like","data":{"on":"album","album":"x"}}""")
        assertEquals(EventBody.Unknown("like", """{"on":"album","album":"x"}"""), odd.body)
    }

    @Test
    fun `a backup archive round-trips with its manifest`() {
        val events = everyType().shuffled(kotlin.random.Random(3))
        val out = ByteArrayOutputStream()
        val written = BackupArchive.write(out, events, deviceId = "3f9a0c5e1b7d2468", createdAt = T0, appVersion = "6.0.0")
        val backup = BackupArchive.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(written, backup.manifest)
        assertEquals(EventLog.canonical(events), backup.events)
        with(backup.manifest) {
            assertEquals(BackupArchive.FORMAT, format)
            assertEquals(FermataInfo.VERSION, fermataVersion)
            assertEquals("6.0.0", appVersion)
            assertEquals(events.size.toLong(), eventCount)
            assertEquals(mapOf("3f9a0c5e1b7d2468" to 21L), heads)
            assertEquals(listOf(BackupArchive.EVENTS), files.map { it.name })
        }
        val names = zipEntries(out.toByteArray()).keys
        assertEquals(setOf(BackupArchive.MANIFEST, BackupArchive.EVENTS), names)
    }

    @Test
    fun `a damaged backup is refused`() {
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, everyType(), "dev", T0)
        val entries = zipEntries(out.toByteArray()).toMutableMap()
        entries[BackupArchive.EVENTS] = Jsonl.encodeGzip(everyType().drop(1))
        val e = assertFailsWith<BackupException> { BackupArchive.read(ByteArrayInputStream(zip(entries))) }
        assertTrue(e.message!!.contains("checksum"))

        assertFailsWith<BackupException> {
            BackupArchive.read(ByteArrayInputStream(zip(mapOf("something.txt" to byteArrayOf(1)))))
        }
    }

    @Test
    fun `a backup from a newer format is refused with a clear message`() {
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, everyType(), "dev", T0)
        val entries = zipEntries(out.toByteArray()).toMutableMap()
        val manifest = entries.getValue(BackupArchive.MANIFEST).toString(Charsets.UTF_8)
        entries[BackupArchive.MANIFEST] = manifest.replace("\"format\": 1", "\"format\": 99").toByteArray()
        val e = assertFailsWith<BackupException> { BackupArchive.read(ByteArrayInputStream(zip(entries))) }
        assertTrue(e.message!!.contains("newer"), e.message)
    }

    @Test
    fun `backup file names are dated`() {
        assertEquals("minim-backup-2026-11-11.minim", BackupArchive.fileName(LocalDate.of(2026, 11, 11)))
        assertTrue(BackupArchive.isBackupName("minim-backup-2026-11-11.minim"))
        assertTrue(!BackupArchive.isBackupName("minim-backup-2026-11-11.minim.partial"))
    }

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                map[e.name] = z.readBytes()
            }
        }
        return map
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
