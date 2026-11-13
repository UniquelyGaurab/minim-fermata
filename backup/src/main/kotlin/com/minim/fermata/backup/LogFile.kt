package com.minim.fermata.backup

import com.minim.fermata.log.Event
import com.minim.fermata.log.EventId
import com.minim.fermata.log.EventLog
import com.minim.fermata.log.FermataInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Whether a format-2 file is a phone's whole log (`BASE`) or only the events after the file before it (`PIECE`). */
enum class LogFileKind(val key: String) {
    BASE("base"),
    PIECE("piece");

    companion object {
        fun of(key: String): LogFileKind = entries.firstOrNull { it.key == key } ?: throw BackupException("unknown file kind $key")
    }
}

/**
 * A point in a phone's chain of files: generation [gen], up to and including piece [seq]
 * (0 = the base alone), and when this phone's clock set it ([setAt], for a restore point).
 */
data class ChainPoint(val gen: Long, val seq: Long, val setAt: Long? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("gen", gen); put("seq", seq); setAt?.let { put("setAt", it) }
    }

    companion object {
        fun fromJson(o: JsonObject) = ChainPoint(
            o.getValue("gen").jsonPrimitive.long,
            o.getValue("seq").jsonPrimitive.long,
            (o["setAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
        )
    }
}

/**
 * The header of a format-2 file: plain JSON in front of the compressed events, so a reader can
 * tell what a file holds (and skip it) without reading the events. See DESIGN.md.
 *
 * Counts, [heads], [legacyCount] and [legacyDigest] describe the whole chain up to and
 * including this file, not just this file; [events] is this file's own count. [from] is the chain's
 * version vector before this file (empty for a base): a piece can be added to a log whose version
 * vector covers [from] without leaving a gap.
 */
data class LogHeader(
    val format: Int,
    val kind: LogFileKind,
    val deviceId: String,
    val gen: Long,
    val seq: Long,
    val createdAt: Long,
    val fermataVersion: String,
    val appVersion: String?,
    val codec: String,
    val events: Long,
    val chainCount: Long,
    val from: Map<String, Long>,
    val heads: Map<String, Long>,
    val legacyCount: Long,
    val legacyDigest: String,
    val clearedBefore: Long?,
    val baseCreatedAt: Long,
    val restorePoint: ChainPoint?,
    val bodyBytes: Long,
    val bodyCrc32: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("format", format)
        put("kind", kind.key)
        put("deviceId", deviceId)
        put("gen", gen)
        put("seq", seq)
        put("createdAt", createdAt)
        put("fermataVersion", fermataVersion)
        appVersion?.let { put("appVersion", it) }
        put("codec", codec)
        put("events", events)
        put("chainCount", chainCount)
        if (from.isNotEmpty()) put("from", vector(from))
        put("heads", vector(heads))
        put("legacyCount", legacyCount)
        put("legacyDigest", legacyDigest)
        clearedBefore?.let { put("clearedBefore", it) }
        put("baseCreatedAt", baseCreatedAt)
        restorePoint?.let { put("restorePoint", it.toJson()) }
        put("bodyBytes", bodyBytes)
        put("bodyCrc32", bodyCrc32)
    }

    companion object {
        private fun vector(v: Map<String, Long>) = buildJsonObject { v.toSortedMap().forEach { (k, c) -> put(k, c) } }
        private fun JsonObject.vec(key: String) = this[key]?.jsonObject?.mapValues { it.value.jsonPrimitive.long } ?: emptyMap()
        private fun JsonObject.optLong(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

        fun fromJson(o: JsonObject): LogHeader = LogHeader(
            format = o.getValue("format").jsonPrimitive.int,
            kind = LogFileKind.of(o.getValue("kind").jsonPrimitive.content),
            deviceId = o.getValue("deviceId").jsonPrimitive.content,
            gen = o.getValue("gen").jsonPrimitive.long,
            seq = o.getValue("seq").jsonPrimitive.long,
            createdAt = o.getValue("createdAt").jsonPrimitive.long,
            fermataVersion = o.getValue("fermataVersion").jsonPrimitive.content,
            appVersion = (o["appVersion"] as? JsonPrimitive)?.contentOrNull,
            codec = o.getValue("codec").jsonPrimitive.content,
            events = o.getValue("events").jsonPrimitive.long,
            chainCount = o.getValue("chainCount").jsonPrimitive.long,
            from = o.vec("from"),
            heads = o.vec("heads"),
            legacyCount = o.getValue("legacyCount").jsonPrimitive.long,
            legacyDigest = o.getValue("legacyDigest").jsonPrimitive.content,
            clearedBefore = o.optLong("clearedBefore"),
            baseCreatedAt = o.getValue("baseCreatedAt").jsonPrimitive.long,
            restorePoint = o["restorePoint"]?.jsonObject?.let { ChainPoint.fromJson(it) },
            bodyBytes = o.getValue("bodyBytes").jsonPrimitive.long,
            bodyCrc32 = o.getValue("bodyCrc32").jsonPrimitive.content,
        )
    }
}

data class LogFileData(val header: LogHeader, val events: List<Event>)

/**
 * Backup format 2: one file per base or piece of a phone's chain.
 *
 * ```
 * "FERMATA" 0x02          8 bytes: magic and format
 * header length           4 bytes, big-endian
 * header                  UTF-8 JSON (LogHeader)
 * body                    CompactCodec.pack(events): DEFLATE (zlib) of the c1 encoding
 *                         (its length and CRC-32 are in the header)
 * ```
 *
 * Files are written once and never changed. [read] checks everything a reader relies on (the
 * format, the body's length and CRC-32, the event count, that the events belong in this file).
 */
object LogFile {
    const val FORMAT = 2
    const val EXTENSION = ".fermata"
    private val MAGIC = byteArrayOf('F'.code.toByte(), 'E'.code.toByte(), 'R'.code.toByte(), 'M'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte(), 'A'.code.toByte(), FORMAT.toByte())
    private val MAGIC_PREFIX = MAGIC.copyOf(7)
    private const val MAX_HEADER = 1 shl 20
    private const val MAX_BODY = 512L shl 20

    /**
     * Builds a file for [events] ([kind] BASE: the whole log, sorted; PIECE: the events after
     * [from]). [prev] is the header of the file before a piece (null for a base). Returns the header
     * and the file's bytes.
     */
    fun build(
        kind: LogFileKind,
        deviceId: String,
        gen: Long,
        seq: Long,
        events: List<Event>,
        createdAt: Long,
        appVersion: String?,
        prev: LogHeader?,
        clearedBefore: Long?,
        restorePoint: ChainPoint?,
    ): Pair<LogHeader, ByteArray> {
        require(BackupNames.isValidDeviceId(deviceId)) { "bad device id $deviceId" }
        val sorted = events.sortedWith(Event.ORDER)
        val body = CompactCodec.pack(sorted)
        val header = when (kind) {
            LogFileKind.BASE -> {
                require(seq == 0L && prev == null)
                val legacy = sorted.filter { it.id.device == EventId.LEGACY_DEVICE }
                LogHeader(
                    FORMAT, kind, deviceId, gen, 0, createdAt, FermataInfo.VERSION, appVersion, CompactCodec.NAME,
                    events = sorted.size.toLong(), chainCount = sorted.size.toLong(),
                    from = emptyMap(), heads = EventLog.heads(sorted),
                    legacyCount = legacy.size.toLong(),
                    legacyDigest = BackupArchive.legacyDigest(legacy),
                    clearedBefore = clearedBefore, baseCreatedAt = createdAt, restorePoint = restorePoint,
                    bodyBytes = body.size.toLong(), bodyCrc32 = crc32(body),
                )
            }
            LogFileKind.PIECE -> {
                requireNotNull(prev)
                require(seq == prev.seq + 1 && gen == prev.gen && prev.deviceId == deviceId)
                require(sorted.none { it.id.device == EventId.LEGACY_DEVICE }) { "a piece can't hold legacy events" }
                require(sorted.all { it.id.counter > (prev.heads[it.id.device] ?: 0L) }) { "a piece holds only events after the chain" }
                val heads = HashMap(prev.heads)
                for ((d, c) in EventLog.heads(sorted)) heads[d] = maxOf(c, heads[d] ?: 0L)
                LogHeader(
                    FORMAT, kind, deviceId, gen, seq, createdAt, FermataInfo.VERSION, appVersion, CompactCodec.NAME,
                    events = sorted.size.toLong(), chainCount = prev.chainCount + sorted.size,
                    from = prev.heads, heads = heads,
                    legacyCount = prev.legacyCount, legacyDigest = prev.legacyDigest,
                    clearedBefore = prev.clearedBefore, baseCreatedAt = prev.baseCreatedAt, restorePoint = restorePoint,
                    bodyBytes = body.size.toLong(), bodyCrc32 = crc32(body),
                )
            }
        }
        val headerBytes = header.toJson().toString().toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(MAGIC.size + 4 + headerBytes.size + body.size)
        out.write(MAGIC)
        out.write(byteArrayOf((headerBytes.size ushr 24).toByte(), (headerBytes.size ushr 16).toByte(), (headerBytes.size ushr 8).toByte(), headerBytes.size.toByte()))
        out.write(headerBytes)
        out.write(body)
        return header to out.toByteArray()
    }

    private fun crc32(b: ByteArray): String = java.util.zip.CRC32().also { it.update(b) }.value.toString(16).padStart(8, '0')

    fun write(out: OutputStream, bytes: ByteArray) {
        out.write(bytes)
        out.flush()
    }

    /** True when [first] (the first bytes of a file) look like a format-2 file of any version. */
    fun looksLike(first: ByteArray): Boolean =
        first.size >= MAGIC_PREFIX.size && MAGIC_PREFIX.indices.all { first[it] == MAGIC_PREFIX[it] }

    /** Reads only the header. Throws [BackupException] for anything that isn't a readable format-2 file. */
    fun readHeader(input: InputStream): LogHeader = readHeaderFrom(DataInputStream(input))

    private fun readHeaderFrom(din: DataInputStream): LogHeader {
        val magic = ByteArray(MAGIC.size)
        try {
            din.readFully(magic)
        } catch (e: EOFException) {
            throw BackupException("not a backup file (too short)")
        }
        if (!looksLike(magic)) throw BackupException("not a format-2 backup file")
        if (magic[7].toInt() > FORMAT) throw BackupTooNewException("backup format ${magic[7].toInt()} is newer than this version reads ($FORMAT)")
        if (magic[7].toInt() != FORMAT) throw BackupException("unknown backup format ${magic[7].toInt()}")
        val len = try { din.readInt() } catch (e: EOFException) { throw BackupException("the file ends inside its header") }
        if (len <= 0 || len > MAX_HEADER) throw BackupException("bad header length $len")
        val bytes = ByteArray(len)
        try {
            din.readFully(bytes)
        } catch (e: EOFException) {
            throw BackupException("the file ends inside its header")
        }
        val h = try {
            LogHeader.fromJson(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("unreadable header: ${e.message}", e)
        }
        if (h.format > FORMAT) throw BackupTooNewException("backup format ${h.format} is newer than this version reads ($FORMAT)")
        if (h.codec != CompactCodec.NAME) throw BackupTooNewException("event encoding ${h.codec} is newer than this version reads")
        if (h.bodyBytes < 0 || h.bodyBytes > MAX_BODY) throw BackupException("bad body length ${h.bodyBytes}")
        return h
    }

    /** Reads and checks a whole file. Throws [BackupException] if it is damaged or cut short. */
    fun read(input: InputStream): LogFileData {
        val din = DataInputStream(input)
        val h = readHeaderFrom(din)
        val body = ByteArray(h.bodyBytes.toInt())
        try {
            din.readFully(body)
        } catch (e: EOFException) {
            throw BackupException("the file is cut short (still syncing?)")
        }
        if (din.read() != -1) throw BackupException("extra bytes after the events")
        if (crc32(body) != h.bodyCrc32) throw BackupException("checksum mismatch: the file is damaged")
        val events = CompactCodec.unpack(body)
        if (events.size.toLong() != h.events) throw BackupException("${events.size} events, header says ${h.events}")
        when (h.kind) {
            LogFileKind.BASE -> {
                if (h.seq != 0L || h.chainCount != h.events) throw BackupException("bad base header")
                if (EventLog.heads(events) != h.heads) throw BackupException("version vector differs from the header")
                val legacy = events.filter { it.id.device == EventId.LEGACY_DEVICE }
                if (BackupArchive.legacyDigest(legacy) != h.legacyDigest) throw BackupException("legacy events differ from the header")
            }
            LogFileKind.PIECE -> {
                if (h.seq < 1) throw BackupException("bad piece number ${h.seq}")
                for (e in events) {
                    if (e.id.device == EventId.LEGACY_DEVICE) throw BackupException("a piece holds a legacy event")
                    if (e.id.counter <= (h.from[e.id.device] ?: 0L)) throw BackupException("a piece holds an event from before it")
                    if (e.id.counter > (h.heads[e.id.device] ?: -1L)) throw BackupException("an event is past the header's version vector")
                }
            }
        }
        return LogFileData(h, events)
    }
}
