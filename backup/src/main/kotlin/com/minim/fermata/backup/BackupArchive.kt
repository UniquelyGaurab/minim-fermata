package com.minim.fermata.backup

import com.minim.fermata.log.Event
import com.minim.fermata.log.EventLog
import com.minim.fermata.log.FermataInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupFileEntry(val name: String, val sha256: String, val bytes: Long)

/** `manifest.json`: what the backup holds, and checksums to verify it. Never holds secrets. */
data class Manifest(
    val format: Int,
    val createdAt: Long,
    val deviceId: String,
    val fermataVersion: String,
    val appVersion: String?,
    val eventCount: Long,
    /** Highest counter per device in the backup, so a sync can tell what is new at a glance. */
    val heads: Map<String, Long>,
    val files: List<BackupFileEntry>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("format", format)
        put("createdAt", createdAt)
        put("deviceId", deviceId)
        put("fermataVersion", fermataVersion)
        appVersion?.let { put("appVersion", it) }
        put("eventCount", eventCount)
        put("heads", buildJsonObject { heads.toSortedMap().forEach { (k, v) -> put(k, v) } })
        put("files", buildJsonArray {
            files.forEach { f ->
                add(buildJsonObject { put("name", f.name); put("sha256", f.sha256); put("bytes", f.bytes) })
            }
        })
    }

    companion object {
        fun fromJson(o: JsonObject): Manifest = Manifest(
            format = o.getValue("format").jsonPrimitive.int,
            createdAt = o.getValue("createdAt").jsonPrimitive.long,
            deviceId = o.getValue("deviceId").jsonPrimitive.content,
            fermataVersion = o.getValue("fermataVersion").jsonPrimitive.content,
            appVersion = (o["appVersion"] as? JsonPrimitive)?.contentOrNull,
            eventCount = o.getValue("eventCount").jsonPrimitive.long,
            heads = o["heads"]?.jsonObject?.mapValues { it.value.jsonPrimitive.long } ?: emptyMap(),
            files = o.getValue("files").jsonArray.map {
                val f = it.jsonObject
                BackupFileEntry(
                    f.getValue("name").jsonPrimitive.content,
                    f.getValue("sha256").jsonPrimitive.content,
                    f.getValue("bytes").jsonPrimitive.long,
                )
            },
        )
    }
}

data class Backup(val manifest: Manifest, val events: List<Event>)

/**
 * The `.minim` backup file: a zip holding `manifest.json` and `fermata-events.jsonl.gz`.
 *
 * Since Minim's whole library is events, the event log is the whole backup. A model snapshot
 * (`fermata-model.json.gz`) may be added later as an optional speed-up; readers must ignore
 * files they do not know. The Gemini API key and other secrets are never written: they are not
 * events, and this format has no place for settings.
 */
object BackupArchive {
    /** The format version this code writes and the newest it can read. */
    const val FORMAT = 1
    const val MANIFEST = "manifest.json"
    const val EVENTS = "fermata-events.jsonl.gz"
    const val EXTENSION = ".minim"
    const val MIME_TYPE = "application/octet-stream"

    /** `minim-backup-2026-11-11.minim` */
    fun fileName(date: LocalDate): String = "minim-backup-$date$EXTENSION"

    fun isBackupName(name: String): Boolean = name.startsWith("minim-backup-") && name.endsWith(EXTENSION)

    /** Writes a backup of [events] to [out]. Finishes the zip but does not close [out]. */
    fun write(
        out: OutputStream,
        events: Iterable<Event>,
        deviceId: String,
        createdAt: Long,
        appVersion: String? = null,
    ): Manifest {
        val log = EventLog.canonical(events)
        val eventBytes = Jsonl.encodeGzip(log)
        val manifest = Manifest(
            format = FORMAT,
            createdAt = createdAt,
            deviceId = deviceId,
            fermataVersion = FermataInfo.VERSION,
            appVersion = appVersion,
            eventCount = log.size.toLong(),
            heads = EventLog.heads(log),
            files = listOf(BackupFileEntry(EVENTS, sha256(eventBytes), eventBytes.size.toLong())),
        )
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(prettyJson.encodeToString(JsonObject.serializer(), manifest.toJson()).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(EVENTS))
        zip.write(eventBytes)
        zip.closeEntry()
        zip.finish()
        out.flush()
        return manifest
    }

    /** Reads and verifies a backup. Throws [BackupException] when it is damaged or too new. */
    fun read(input: InputStream): Backup {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(input).let { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readAllBytesCompat()
                zip.closeEntry()
            }
        }
        val manifestBytes = entries[MANIFEST] ?: throw BackupException("not a Minim backup: no $MANIFEST")
        val manifest = try {
            Manifest.fromJson(Json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)).jsonObject)
        } catch (e: Exception) {
            throw BackupException("unreadable $MANIFEST: ${e.message}", e)
        }
        if (manifest.format > FORMAT) {
            throw BackupException("backup format ${manifest.format} is newer than this version reads ($FORMAT)")
        }
        for (f in manifest.files) {
            val bytes = entries[f.name] ?: throw BackupException("missing ${f.name}")
            if (sha256(bytes) != f.sha256) throw BackupException("checksum mismatch in ${f.name}")
        }
        val eventBytes = entries[EVENTS] ?: throw BackupException("missing $EVENTS")
        val events = Jsonl.decodeGzip(eventBytes)
        if (events.size.toLong() != manifest.eventCount) {
            throw BackupException("expected ${manifest.eventCount} events, found ${events.size}")
        }
        return Backup(manifest, events)
    }

    private val prettyJson = Json { prettyPrint = true }

    internal fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // InputStream.readAllBytes() needs API 33 on Android; this works on API 28.
    private fun InputStream.readAllBytesCompat(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
