package com.minim.fermata.backup

import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventId
import com.minim.fermata.log.EventLog
import com.minim.fermata.log.MINUTE
import com.minim.fermata.log.T0
import com.minim.fermata.log.TestDevice
import com.minim.fermata.log.TrackRef
import java.io.ByteArrayInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogFileTest {
    private val events = BackupTest().everyType().sortedWith(Event.ORDER)

    @Test
    fun `the compact codec gives back exactly what JSON Lines gives back`() {
        assertEquals(events, CompactCodec.unpack(CompactCodec.pack(events)))
        assertEquals(events, CompactCodec.decode(CompactCodec.encode(events)))
        assertEquals(emptyList(), CompactCodec.unpack(CompactCodec.pack(emptyList())))
    }

    @Test
    fun `odd values survive the compact codec`() {
        val d = TestDevice("odd")
        val odd = listOf(
            """{"a":1.5,"b":-0,"c":12345678901234567890123,"d":"007","e":"-0","f":"","g":{},"h":[],"i":[{},[],null,true,false]}""",
            """{"":"","x":"a\u001f\u001fb","y":"\u001f","z":"dev:12\u001fdev:011\u001f1793000000000\u001f-5"}""",
            """{"t":1793000000000,"u":999999999999,"v":-1793000000000,"w":9223372036854775807,"n":-9223372036854775808}""",
            """{"track":{"id":"x"},"q":{"track":{"id":"x"}},"r":"\\\/\"quoted\" ñ 😀"}""",
        ).mapIndexed { i, j -> d.event(T0 + i * MINUTE, EventBody.Unknown("thing.$i", j)) }
        val viaJson = Jsonl.decodeGzip(Jsonl.encodeGzip(odd))
        assertEquals(viaJson, CompactCodec.unpack(CompactCodec.pack(odd)))
    }

    @Test
    fun `random damage to the compact data is caught, never a crash`() {
        val packed = CompactCodec.pack(events)
        val raw = CompactCodec.encode(events)
        val rnd = Random(3)
        repeat(400) {
            val bad = raw.copyOf()
            bad[rnd.nextInt(bad.size)] = rnd.nextInt(256).toByte()
            try {
                CompactCodec.decode(bad)
            } catch (e: BackupException) {
                // expected for most; a change that still decodes is fine too (the file checksum catches it)
            }
        }
        for (cut in listOf(0, 1, packed.size / 2, packed.size - 1)) {
            assertFailsWith<BackupException> { CompactCodec.unpack(packed.copyOf(cut)) }
        }
    }

    private fun base(evs: List<Event> = events, gen: Long = 1) = LogFile.build(
        LogFileKind.BASE, "3f9a0c5e1b7d2468", gen, 0, evs, T0, "6.2.0", null, clearedBefore = null,
        restorePoint = ChainPoint(gen, 0, T0),
    )

    @Test
    fun `a base and a piece round-trip with their headers`() {
        val (h, bytes) = base()
        val read = LogFile.read(ByteArrayInputStream(bytes))
        assertEquals(h, read.header)
        assertEquals(events, read.events)
        assertEquals(EventLog.heads(events), h.heads)
        assertEquals(1L, h.legacyCount)
        assertEquals(h, LogFile.readHeader(ByteArrayInputStream(bytes)))

        val d = TestDevice("3f9a0c5e1b7d2468")
        repeat(200) { d.event(T0, EventBody.Play(TrackRef("x"), 1)) } // move past the base's counters
        val newer = listOf(d.event(T0 + 60 * MINUTE, EventBody.Play(TrackRef("new"), 31_000)))
        val (ph, pbytes) = LogFile.build(LogFileKind.PIECE, "3f9a0c5e1b7d2468", 1, 1, newer, T0 + 61 * MINUTE, "6.2.0", h, null, h.restorePoint)
        val p = LogFile.read(ByteArrayInputStream(pbytes))
        assertEquals(newer, p.events)
        assertEquals(h.heads, ph.from)
        assertEquals(h.chainCount + 1, ph.chainCount)
        assertEquals(newer[0].id.counter, ph.heads["3f9a0c5e1b7d2468"])
        assertTrue(pbytes.size < 800, "one play costs ${pbytes.size} bytes")
    }

    @Test
    fun `damaged, cut short and foreign files are refused`() {
        val (_, bytes) = base()
        assertFailsWith<BackupException> { LogFile.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 3))) }
        assertFailsWith<BackupException> { LogFile.read(ByteArrayInputStream(bytes.copyOf(10))) }
        assertFailsWith<BackupException> { LogFile.read(ByteArrayInputStream(bytes + byteArrayOf(1))) }
        val flipped = bytes.copyOf().also { it[it.size - 5] = (it[it.size - 5] + 1).toByte() }
        assertFailsWith<BackupException> { LogFile.read(ByteArrayInputStream(flipped)) }
        assertFailsWith<BackupException> { LogFile.read(ByteArrayInputStream("PK\u0003\u0004 a zip".toByteArray())) }
        val newer = bytes.copyOf().also { it[7] = 3 }
        assertFailsWith<BackupTooNewException> { LogFile.readHeader(ByteArrayInputStream(newer)) }
    }

    @Test
    fun `format-2 names are parsed back, and nothing else is`() {
        val dev = "3f9a0c5e1b7d2468"
        assertEquals("minim-$dev-g001-base.fermata", BackupNames.chainBase(dev, 1))
        assertEquals("minim-$dev-g012-p0007.fermata", BackupNames.chainPiece(dev, 12, 7))
        assertEquals(ChainName(dev, 1, 0), BackupNames.parseChain(BackupNames.chainBase(dev, 1)))
        assertEquals(ChainName(dev, 12, 7), BackupNames.parseChain(BackupNames.chainPiece(dev, 12, 7)))
        assertEquals(ChainName("a-g1-base", 2, 3), BackupNames.parseChain(BackupNames.chainPiece("a-g1-base", 2, 3)))
        for (bad in listOf("minim-$dev-g001-base.fermata.tmp", "minim-$dev-latest.minim", "minim-$dev-g0-base.fermata", "minim-$dev-g001-p0.fermata", "x.fermata", "minim-legacy-g001-base.fermata")) {
            assertNull(BackupNames.parseChain(bad), bad)
        }
    }

    @Test
    fun `a piece refuses events the chain already has`() {
        val (h, _) = base()
        val old = events.first { it.id.device != EventId.LEGACY_DEVICE }
        assertFailsWith<IllegalArgumentException> {
            LogFile.build(LogFileKind.PIECE, "3f9a0c5e1b7d2468", 1, 1, listOf(old), T0, null, h, null, null)
        }
    }
}
