package com.minim.fermata.backup

import com.minim.fermata.log.Event
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Fermata's compact event encoding, codec `c1`: what format-2 backup files hold, and what
 * phone-to-phone sync will send. See DESIGN.md, "Backup format 2".
 *
 * It is lossless over [EventCodec]'s JSON: [decode] gives exactly the events that
 * `EventCodec.fromJson` gives for the same events written as JSON lines, for every body type,
 * unknown ones (the app's own writes) included. Nothing is summarised or dropped.
 *
 * How it gets small:
 * - **Columns.** Each event is walked as its JSON tree, and every value goes to a stream named by
 *   its kind and its key (`I:listenedMs`, `S:title`, `T:countedAt`...), so like values sit together
 *   and compress well. The streams are concatenated and compressed once with DEFLATE ([pack]).
 * - **Strings once.** Each distinct string (a title, an artist, a thumbnail link, a video id, a
 *   session) is written the first time it appears; after that it is a small number. Text joined with
 *   U+001F (how Minim stores song info and plays inside its own writes) is split first, so a song's
 *   title, artist and thumbnail inside Minim's writes are shared with every other mention of them.
 * - **Songs once.** Every `track` object (id, title, artist, length, thumbnail) goes into a song
 *   table the first time it appears; events after that point to it by number.
 * - **Small numbers.** Event times are deltas from the previous event, event counters are deltas
 *   per phone, and numbers that look like times (within about three years of the event) are stored
 *   relative to the event's own time. Object key lists are interned too.
 */
object CompactCodec {
    const val NAME = "c1"
    private const val VERSION = 1

    /** The raw (uncompressed) encoding of [events], in the order given. */
    fun encode(events: List<Event>): ByteArray = Encoder().run(events)

    /** The events in [bytes] (from [encode]), in their order. Throws [BackupException] if damaged. */
    fun decode(bytes: ByteArray): List<Event> = try {
        Decoder(bytes).run()
    } catch (e: BackupException) {
        throw e
    } catch (e: Exception) {
        throw BackupException("damaged event data: ${e.message ?: e.javaClass.simpleName}", e)
    }

    /** [encode], then DEFLATE (zlib wrapper, best compression). */
    fun pack(events: List<Event>): ByteArray = deflate(encode(events))

    /** The inverse of [pack]. Throws [BackupException] if damaged. */
    fun unpack(bytes: ByteArray): List<Event> = decode(inflate(bytes))

    fun deflate(raw: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        try {
            d.setInput(raw)
            d.finish()
            val out = ByteArrayOutputStream(raw.size / 4 + 64)
            val buf = ByteArray(32 * 1024)
            while (!d.finished()) {
                val n = d.deflate(buf)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    fun inflate(packed: ByteArray): ByteArray {
        val i = Inflater()
        try {
            i.setInput(packed)
            val out = ByteArrayOutputStream(packed.size * 4 + 64)
            val buf = ByteArray(32 * 1024)
            while (!i.finished()) {
                val n = i.inflate(buf)
                if (n == 0) {
                    if (i.needsInput()) throw BackupException("damaged event data: it ends too early")
                    if (i.needsDictionary()) throw BackupException("damaged event data: unexpected dictionary")
                }
                out.write(buf, 0, n)
                if (out.size() > MAX_RAW) throw BackupException("damaged event data: too large")
            }
            if (i.remaining > 0) throw BackupException("damaged event data: extra bytes at the end")
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw BackupException("damaged event data: ${e.message}", e)
        } finally {
            i.end()
        }
    }

    private const val MAX_RAW = 1L shl 30

    // --- value tags ---
    private const val T_NULL = 0
    private const val T_TRUE = 1
    private const val T_FALSE = 2
    private const val T_TIME = 3
    private const val T_INT = 4
    private const val T_NUMBER = 5 // any other number literal, kept as its text
    private const val T_STRING = 6
    private const val T_ARRAY = 7
    private const val T_OBJECT = 8
    private const val T_SONG = 9

    // --- string part tags ---
    private const val P_STRING = 0
    private const val P_TIME = 1
    private const val P_INT = 2
    private const val P_ID = 3

    private const val SEP = '\u001f'
    private const val SONG_KEY = "track"
    private const val TIME_FLOOR = 1_000_000_000_000L // 2001-09: smaller numbers are never times
    private const val TIME_WINDOW = 100_000_000_000L // about three years either side of the event
    private val idPattern = Regex("[A-Za-z0-9_.-]{1,64}:(0|[1-9][0-9]{0,18})")

    private fun ctxOf(key: String): String =
        if (key.length in 1..16 && key.all { it in 'a'..'z' || it in 'A'..'Z' }) key else "*"

    /** A decimal long written the canonical way (no sign on 0, no leading zeros), else null. */
    private fun canonicalLong(s: String): Long? {
        if (s.isEmpty() || s.length > 19) return null
        if (s == "0") return 0L
        val neg = s[0] == '-'
        val start = if (neg) 1 else 0
        if (start >= s.length || s[start] !in '1'..'9') return null
        for (i in start + 1 until s.length) if (s[i] !in '0'..'9') return null
        return s.toLongOrNull()
    }

    private fun zig(n: Long): Long = (n shl 1) xor (n shr 63)
    private fun unzig(n: Long): Long = (n ushr 1) xor -(n and 1)

    private class Buf {
        var a = ByteArray(64)
        var n = 0
        fun byte(b: Int) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = b.toByte()
        }
        fun varint(v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) {
                byte(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
            byte(x.toInt())
        }
        fun bytes(b: ByteArray) {
            while (n + b.size > a.size) a = a.copyOf(a.size * 2)
            System.arraycopy(b, 0, a, n, b.size)
            n += b.size
        }
    }

    private class Encoder {
        val streams = LinkedHashMap<String, Buf>()
        val strings = HashMap<String, Int>()
        val songs = HashMap<String, Int>()
        val counters = HashMap<String, Long>()
        var lastTs = 0L
        var ts = 0L

        fun s(name: String) = streams.getOrPut(name) { Buf() }

        fun str(ctx: String, v: String) {
            val known = strings[v]
            if (known != null) {
                s("S:$ctx").varint(known + 1L)
            } else {
                s("S:$ctx").varint(0)
                val b = v.toByteArray(Charsets.UTF_8)
                val t = s("str")
                t.varint(b.size.toLong())
                t.bytes(b)
                strings[v] = strings.size
            }
        }

        fun int(ctx: String, n: Long, tags: Buf, timeTag: Int, intTag: Int) {
            if (n >= TIME_FLOOR && n - ts in -TIME_WINDOW..TIME_WINDOW) {
                tags.byte(timeTag)
                s("T:$ctx").varint(zig(n - ts))
            } else {
                tags.byte(intTag)
                s("I:$ctx").varint(zig(n))
            }
        }

        fun part(ctx: String, p: String) {
            val tags = s("pt")
            val n = canonicalLong(p)
            if (n != null) {
                int(ctx, n, tags, P_TIME, P_INT)
                return
            }
            if (idPattern.matches(p)) {
                val colon = p.lastIndexOf(':')
                val device = p.substring(0, colon)
                val counter = p.substring(colon + 1).toLongOrNull()
                if (counter != null && "$device:$counter" == p) {
                    tags.byte(P_ID)
                    str("dev", device)
                    s("I:ctr").varint(zig(counter - (counters[device] ?: 0L)))
                    counters[device] = counter
                    return
                }
            }
            tags.byte(P_STRING)
            str(ctx, p)
        }

        fun value(ctx: String, v: JsonElement) {
            val tags = s("t")
            when (v) {
                is JsonNull -> tags.byte(T_NULL)
                is JsonPrimitive -> if (v.isString) {
                    tags.byte(T_STRING)
                    val text = v.content
                    if (text.indexOf(SEP) < 0) {
                        s("P:$ctx").varint(1)
                        part(ctx, text)
                    } else {
                        val parts = text.split(SEP)
                        s("P:$ctx").varint(parts.size.toLong())
                        for (p in parts) part(ctx, p)
                    }
                } else {
                    val text = v.content
                    val n = canonicalLong(text)
                    when {
                        text == "true" -> tags.byte(T_TRUE)
                        text == "false" -> tags.byte(T_FALSE)
                        n != null -> int(ctx, n, tags, T_TIME, T_INT)
                        else -> {
                            tags.byte(T_NUMBER)
                            str("num", text)
                        }
                    }
                }
                is JsonArray -> {
                    tags.byte(T_ARRAY)
                    s("N:$ctx").varint(v.size.toLong())
                    for (x in v) value(ctx, x)
                }
                is JsonObject -> if (ctx == SONG_KEY) {
                    tags.byte(T_SONG)
                    val key = v.toString()
                    val known = songs[key]
                    if (known != null) {
                        s("R").varint(known + 1L)
                    } else {
                        s("R").varint(0)
                        obj("song", v)
                        songs[key] = songs.size
                    }
                } else {
                    tags.byte(T_OBJECT)
                    obj(ctx, v)
                }
            }
        }

        fun obj(ctx: String, o: JsonObject) {
            // The key count first, so an empty object and an object with one empty key differ.
            str("K:$ctx", o.size.toString() + "\u0000" + o.keys.joinToString("\u0000"))
            for ((k, x) in o) value(ctxOf(k), x)
        }

        fun run(events: List<Event>): ByteArray {
            for (e in events) {
                ts = e.ctx.timestamp
                s("I:ts").varint(zig(ts - lastTs))
                lastTs = ts
                obj("event", EventCodec.toJson(e))
            }
            val out = Buf()
            out.varint(VERSION.toLong())
            out.varint(events.size.toLong())
            out.varint(streams.size.toLong())
            for ((name, b) in streams) {
                val nb = name.toByteArray(Charsets.UTF_8)
                out.varint(nb.size.toLong())
                out.bytes(nb)
                out.varint(b.n.toLong())
                out.bytes(b.a.copyOf(b.n))
            }
            return out.a.copyOf(out.n)
        }
    }

    private class In(val a: ByteArray, var pos: Int, val end: Int) {
        fun byte(): Int {
            if (pos >= end) throw BackupException("damaged event data: a column ends too early")
            return a[pos++].toInt() and 0xFF
        }
        fun varint(): Long {
            var shift = 0
            var r = 0L
            while (true) {
                val b = byte()
                r = r or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return r
                shift += 7
                if (shift > 63) throw BackupException("damaged event data: bad number")
            }
        }
        fun count(): Int {
            val v = varint()
            if (v < 0 || v > Int.MAX_VALUE) throw BackupException("damaged event data: bad count")
            return v.toInt()
        }
    }

    private class Decoder(bytes: ByteArray) {
        val head = In(bytes, 0, bytes.size)
        val streams = HashMap<String, In>()
        val strings = ArrayList<String>()
        val songs = ArrayList<JsonObject>()
        val counters = HashMap<String, Long>()
        var ts = 0L
        var eventCount = 0

        init {
            val version = head.count()
            if (version != VERSION) throw BackupTooNewException("event encoding $version is newer than this version reads ($VERSION)")
            eventCount = head.count()
            val n = head.count()
            repeat(n) {
                val nameLen = head.count()
                if (nameLen > head.end - head.pos) throw BackupException("damaged event data: bad column name")
                val name = String(head.a, head.pos, nameLen, Charsets.UTF_8)
                head.pos += nameLen
                val len = head.count()
                if (len > head.end - head.pos) throw BackupException("damaged event data: column $name is cut short")
                streams[name] = In(head.a, head.pos, head.pos + len)
                head.pos += len
            }
            if (head.pos != head.end) throw BackupException("damaged event data: extra bytes at the end")
        }

        fun s(name: String): In = streams[name] ?: throw BackupException("damaged event data: no column $name")

        fun str(ctx: String): String {
            val ref = s("S:$ctx").varint()
            if (ref == 0L) {
                val t = s("str")
                val len = t.count()
                if (len > t.end - t.pos) throw BackupException("damaged event data: a text is cut short")
                val v = String(t.a, t.pos, len, Charsets.UTF_8)
                t.pos += len
                strings.add(v)
                return v
            }
            if (ref > strings.size) throw BackupException("damaged event data: unknown text $ref")
            return strings[(ref - 1).toInt()]
        }

        fun part(ctx: String): String = when (val tag = s("pt").byte()) {
            P_STRING -> str(ctx)
            P_TIME -> (ts + unzig(s("T:$ctx").varint())).toString()
            P_INT -> unzig(s("I:$ctx").varint()).toString()
            P_ID -> {
                val device = str("dev")
                val counter = (counters[device] ?: 0L) + unzig(s("I:ctr").varint())
                counters[device] = counter
                "$device:$counter"
            }
            else -> throw BackupException("damaged event data: unknown part $tag")
        }

        fun value(ctx: String): JsonElement = when (val tag = s("t").byte()) {
            T_NULL -> JsonNull
            T_TRUE -> JsonPrimitive(true)
            T_FALSE -> JsonPrimitive(false)
            T_TIME -> JsonPrimitive(ts + unzig(s("T:$ctx").varint()))
            T_INT -> JsonPrimitive(unzig(s("I:$ctx").varint()))
            T_NUMBER -> Json.parseToJsonElement(str("num")) as? JsonPrimitive
                ?: throw BackupException("damaged event data: bad number")
            T_STRING -> {
                val n = s("P:$ctx").count()
                if (n == 1) JsonPrimitive(part(ctx))
                else JsonPrimitive((0 until n).joinToString(SEP.toString()) { part(ctx) })
            }
            T_ARRAY -> {
                val n = s("N:$ctx").count()
                JsonArray((0 until n).map { value(ctx) })
            }
            T_OBJECT -> obj(ctx)
            T_SONG -> {
                val ref = s("R").varint()
                if (ref == 0L) obj("song").also { songs.add(it) }
                else songs.getOrNull((ref - 1).toInt()) ?: throw BackupException("damaged event data: unknown song $ref")
            }
            else -> throw BackupException("damaged event data: unknown value $tag")
        }

        fun obj(ctx: String): JsonObject {
            val shape = str("K:$ctx")
            val cut = shape.indexOf('\u0000')
            val size = if (cut < 0) null else shape.substring(0, cut).toIntOrNull()
            if (size == null || size < 0) throw BackupException("damaged event data: bad key list")
            val keys = if (size == 0) emptyList() else shape.substring(cut + 1).split('\u0000')
            if (keys.size != size) throw BackupException("damaged event data: bad key list")
            val m = LinkedHashMap<String, JsonElement>(keys.size * 2)
            for (k in keys) m[k] = value(ctxOf(k))
            return JsonObject(m)
        }

        fun run(): List<Event> {
            val out = ArrayList<Event>(eventCount)
            repeat(eventCount) {
                ts += unzig(s("I:ts").varint())
                val o = obj("event")
                out.add(EventCodec.fromJson(o))
            }
            for ((name, st) in streams) {
                if (st.pos != st.end) throw BackupException("damaged event data: column $name has extra bytes")
            }
            return out
        }
    }
}
