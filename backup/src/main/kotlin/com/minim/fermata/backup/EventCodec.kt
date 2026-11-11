package com.minim.fermata.backup

import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventContext
import com.minim.fermata.log.EventId
import com.minim.fermata.log.Source
import com.minim.fermata.log.Subject
import com.minim.fermata.log.TasteEditKind
import com.minim.fermata.log.TrackRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One event <-> one JSON object (one line of JSON Lines). The format is open and readable:
 *
 * ```
 * {"id":"3f9a0c5e1b7d2468:42","ts":1762851000000,"hour":14,"dow":3,"session":"s1",
 *  "prev":"dQw4w9WgXcQ","source":"radio","type":"play",
 *  "data":{"track":{"id":"kJQP7kiw5Fk","title":"...","artist":"...","durationMs":229000},"listenedMs":182000}}
 * ```
 *
 * Unknown types (from a newer version) decode to [EventBody.Unknown] with `data` kept verbatim,
 * and encode back unchanged.
 */
object EventCodec {
    fun encode(event: Event): String = toJson(event).toString()

    fun decode(line: String): Event = fromJson(Json.parseToJsonElement(line).jsonObject)

    fun toJson(event: Event): JsonObject = buildJsonObject {
        put("id", event.id.toString())
        put("ts", event.ctx.timestamp)
        put("hour", event.ctx.hour)
        put("dow", event.ctx.dayOfWeek)
        event.ctx.sessionId?.let { put("session", it) }
        event.ctx.previousTrackId?.let { put("prev", it) }
        put("source", event.ctx.source.key)
        event.ctx.replaces?.let { put("replaces", it.toString()) }
        put("type", event.body.type)
        put("data", bodyToJson(event.body))
    }

    fun fromJson(o: JsonObject): Event {
        val ctx = EventContext(
            timestamp = o.long("ts"),
            hour = o.int("hour"),
            dayOfWeek = o.int("dow"),
            sessionId = o.strOrNull("session"),
            previousTrackId = o.strOrNull("prev"),
            source = Source.fromKey(o.strOrNull("source")),
            replaces = o.strOrNull("replaces")?.let { runCatching { EventId.parse(it) }.getOrNull() },
        )
        val type = o.str("type")
        val data = o["data"]?.jsonObject ?: JsonObject(emptyMap())
        // A known type that does not parse is kept as Unknown, so nothing is ever lost.
        val body = runCatching { bodyFromJson(type, data) }.getOrNull() ?: EventBody.Unknown(type, data.toString())
        return Event(EventId.parse(o.str("id")), ctx, body)
    }

    private fun bodyToJson(body: EventBody): JsonObject = when (body) {
        is EventBody.Unknown -> Json.parseToJsonElement(body.json).jsonObject
        else -> buildJsonObject {
            when (body) {
                is EventBody.Play -> {
                    put("track", track(body.track)); put("listenedMs", body.listenedMs)
                    body.countedAt?.let { put("countedAt", it) }
                    body.syncId?.let { put("syncId", it) }
                    if (body.estimated) put("estimated", true)
                }
                is EventBody.Skip -> { put("track", track(body.track)); put("positionMs", body.positionMs) }
                is EventBody.Queued -> put("track", track(body.track))
                is EventBody.Like -> subject(body.subject)
                is EventBody.Unlike -> subject(body.subject)
                is EventBody.NotInterested -> subject(body.subject)
                is EventBody.Interested -> subject(body.subject)
                is EventBody.PlaylistCreate -> { put("playlist", body.playlistId); put("name", body.name) }
                is EventBody.PlaylistRename -> { put("playlist", body.playlistId); put("name", body.name) }
                is EventBody.PlaylistDelete -> put("playlist", body.playlistId)
                is EventBody.PlaylistAdd -> {
                    put("playlist", body.playlistId)
                    put("track", track(body.track))
                    body.index?.let { put("index", it) }
                }
                is EventBody.PlaylistRemove -> { put("playlist", body.playlistId); put("trackId", body.trackId) }
                is EventBody.PlaylistMove -> {
                    put("playlist", body.playlistId); put("trackId", body.trackId); put("toIndex", body.toIndex)
                }
                is EventBody.TapePlay -> {
                    put("tape", track(body.tape)); put("listenedMs", body.listenedMs); put("speed", body.speedPercent)
                    body.countedAt?.let { put("countedAt", it) }
                    if (body.channelId.isNotEmpty()) put("channelId", body.channelId)
                }
                is EventBody.HistoryClear -> put("before", body.before)
                is EventBody.TasteEdit -> { put("kind", body.kind.key); subject(body.subject) }
                is EventBody.Unknown -> error("handled above")
            }
        }
    }

    private fun bodyFromJson(type: String, d: JsonObject): EventBody? = when (type) {
        EventBody.PLAY -> EventBody.Play(
            d.track("track"), d.long("listenedMs"),
            countedAt = d["countedAt"]?.jsonPrimitive?.longOrNull,
            syncId = d.strOrNull("syncId"),
            estimated = d["estimated"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
        EventBody.SKIP -> EventBody.Skip(d.track("track"), d.long("positionMs"))
        EventBody.QUEUED -> EventBody.Queued(d.track("track"))
        EventBody.LIKE -> d.subject()?.let { EventBody.Like(it) }
        EventBody.UNLIKE -> d.subject()?.let { EventBody.Unlike(it) }
        EventBody.NOT_INTERESTED -> d.subject()?.let { EventBody.NotInterested(it) }
        EventBody.INTERESTED -> d.subject()?.let { EventBody.Interested(it) }
        EventBody.PLAYLIST_CREATE -> EventBody.PlaylistCreate(d.str("playlist"), d.str("name"))
        EventBody.PLAYLIST_RENAME -> EventBody.PlaylistRename(d.str("playlist"), d.str("name"))
        EventBody.PLAYLIST_DELETE -> EventBody.PlaylistDelete(d.str("playlist"))
        EventBody.PLAYLIST_ADD -> EventBody.PlaylistAdd(d.str("playlist"), d.track("track"), d["index"]?.jsonPrimitive?.intOrNull)
        EventBody.PLAYLIST_REMOVE -> EventBody.PlaylistRemove(d.str("playlist"), d.str("trackId"))
        EventBody.PLAYLIST_MOVE -> EventBody.PlaylistMove(d.str("playlist"), d.str("trackId"), d.int("toIndex"))
        EventBody.TAPE_PLAY -> EventBody.TapePlay(
            d.track("tape"), d.long("listenedMs"), d["speed"]?.jsonPrimitive?.intOrNull ?: 100,
            countedAt = d["countedAt"]?.jsonPrimitive?.longOrNull,
            channelId = d.strOrNull("channelId") ?: "",
        )
        EventBody.HISTORY_CLEAR -> EventBody.HistoryClear(d.long("before"))
        EventBody.TASTE_EDIT -> {
            val kind = TasteEditKind.fromKey(d.str("kind"))
            val subject = d.subject()
            if (kind != null && subject != null) EventBody.TasteEdit(kind, subject) else null
        }
        else -> null
    }

    private fun track(t: TrackRef): JsonObject = buildJsonObject {
        put("id", t.id)
        if (t.title.isNotEmpty()) put("title", t.title)
        if (t.artist.isNotEmpty()) put("artist", t.artist)
        if (t.durationMs != 0L) put("durationMs", t.durationMs)
        if (t.thumbnailUrl.isNotEmpty()) put("thumb", t.thumbnailUrl)
        if (t.sourceUrl.isNotEmpty()) put("src", t.sourceUrl)
    }

    private fun JsonObjectBuilder.subject(s: Subject) {
        when (s) {
            is Subject.Track -> { put("on", "track"); put("track", track(s.track)) }
            is Subject.Artist -> { put("on", "artist"); put("artist", s.name) }
        }
    }

    private fun JsonObject.subject(): Subject? = when (strOrNull("on")) {
        "track" -> Subject.Track(track("track"))
        "artist" -> Subject.Artist(str("artist"))
        else -> null
    }

    private fun JsonObject.track(key: String): TrackRef {
        val t = getValue(key).jsonObject
        return TrackRef(
            id = t.str("id"),
            title = t.strOrNull("title") ?: "",
            artist = t.strOrNull("artist") ?: "",
            durationMs = t["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L,
            thumbnailUrl = t.strOrNull("thumb") ?: "",
            sourceUrl = t.strOrNull("src") ?: "",
        )
    }

    private fun JsonObject.str(key: String): String =
        strOrNull(key) ?: throw IllegalArgumentException("missing \"$key\"")

    private fun JsonObject.strOrNull(key: String): String? {
        val p = this[key] as? JsonPrimitive ?: return null
        return if (p.isString) p.content else p.contentOrNull
    }

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
}
