package com.minim.fermata.backup

import com.minim.fermata.log.Event
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** JSON Lines: one event per line, UTF-8, `\n` separated. Streams are not closed. */
object Jsonl {
    fun write(events: Iterable<Event>, out: OutputStream) {
        val writer = OutputStreamWriter(out, Charsets.UTF_8)
        for (e in events) {
            writer.write(EventCodec.encode(e))
            writer.write("\n")
        }
        writer.flush()
    }

    fun read(input: InputStream): List<Event> {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val events = ArrayList<Event>()
        var lineNo = 0
        while (true) {
            val line = reader.readLine() ?: break
            lineNo++
            if (line.isBlank()) continue
            try {
                events.add(EventCodec.decode(line))
            } catch (e: Exception) {
                throw BackupException("line $lineNo is not a valid event: ${e.message}", e)
            }
        }
        return events
    }

    fun encodeGzip(events: Iterable<Event>): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { write(events, it) }
        return bytes.toByteArray()
    }

    fun decodeGzip(bytes: ByteArray): List<Event> = GZIPInputStream(bytes.inputStream()).use { read(it) }
}

open class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The backup was made by a newer version, in a format this one cannot read. */
class BackupTooNewException(message: String) : BackupException(message)
