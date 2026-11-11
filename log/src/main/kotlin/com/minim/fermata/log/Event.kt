package com.minim.fermata.log

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Where a play or an action came from. [key] is the stable name written to the log. */
enum class Source(val key: String) {
    RADIO("radio"),
    SEARCH("search"),
    LIBRARY("library"),
    PLAYLIST("playlist"),
    DISCOVER("discover"),
    TAPE("tape"),
    QUEUE("queue"),
    IMPORT("import"),
    UNKNOWN("unknown");

    companion object {
        fun fromKey(key: String?): Source = entries.firstOrNull { it.key == key } ?: UNKNOWN
    }
}

/**
 * A song (or a tape video) as the log remembers it. Only [id] is required; the rest is display
 * metadata, and the newest non-empty value seen for a track wins in the library projection.
 */
data class TrackRef(
    val id: String,
    val title: String = "",
    val artist: String = "",
    val durationMs: Long = 0,
    val thumbnailUrl: String = "",
) {
    init {
        require(id.isNotBlank()) { "track id must not be blank" }
    }
}

/** What a like, a "not interested" or a taste edit is about: one track or one artist. */
sealed interface Subject {
    /** Stable identity, used to resolve conflicts: `track:<id>` or `artist:<normalised name>`. */
    val key: String

    data class Track(val track: TrackRef) : Subject {
        override val key: String get() = "track:${track.id}"
    }

    data class Artist(val name: String) : Subject {
        init {
            require(name.isNotBlank()) { "artist name must not be blank" }
        }

        override val key: String get() = "artist:${normalizeArtist(name)}"
    }

    companion object {
        fun normalizeArtist(name: String): String = name.trim().lowercase(Locale.ROOT)
    }
}

/** Corrections from the taste screen. */
enum class TasteEditKind(val key: String) {
    /** "I'm over this": push the subject down from now on. */
    OVER_IT("over_it"),

    /** "That was just a phase": shrink what was learned so far, keep learning afterwards. */
    JUST_A_PHASE("just_a_phase"),

    /** Forget everything learned about the subject so far. */
    RESET("reset");

    companion object {
        fun fromKey(key: String): TasteEditKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The context every event carries. [timestamp] is the ordering clock (epoch milliseconds); [hour]
 * and [dayOfWeek] are the listener's local wall time when it happened, used to learn context.
 */
data class EventContext(
    val timestamp: Long,
    val hour: Int,
    /** ISO day of week: 1 = Monday ... 7 = Sunday. */
    val dayOfWeek: Int,
    val sessionId: String? = null,
    val previousTrackId: String? = null,
    val source: Source = Source.UNKNOWN,
    /**
     * The id of an older record of the same thing that this event stands in for, or null. Used
     * when history is imported twice from different tables: a play from Minim 5.9.0's
     * `fermata_events` replaces the legacy event for the same play in the old `plays` table, so
     * that play counts once wherever the legacy copy turns up (another phone, the Firestore copy).
     * Both stay in the log; projections skip the replaced one (see [EventLog.effective]).
     */
    val replaces: EventId? = null,
) {
    init {
        require(hour in 0..23) { "hour must be 0..23, got $hour" }
        require(dayOfWeek in 1..7) { "dayOfWeek must be 1..7, got $dayOfWeek" }
    }

    companion object {
        /**
         * Builds a context, reading hour and day of week from [wallTime] in [zone]. [wallTime]
         * differs from [timestamp] only when the ordering clock was moved forward (see Fermata).
         */
        fun at(
            timestamp: Long,
            zone: ZoneId = ZoneId.systemDefault(),
            sessionId: String? = null,
            previousTrackId: String? = null,
            source: Source = Source.UNKNOWN,
            wallTime: Long = timestamp,
            replaces: EventId? = null,
        ): EventContext {
            val local = Instant.ofEpochMilli(wallTime).atZone(zone)
            return EventContext(timestamp, local.hour, local.dayOfWeek.value, sessionId, previousTrackId, source, replaces)
        }
    }
}

/** What happened. Everything in Minim's library is one of these. */
sealed interface EventBody {
    /** The stable type name written to the log. */
    val type: String

    /** A song was played; [listenedMs] is the time actually heard. */
    data class Play(val track: TrackRef, val listenedMs: Long) : EventBody {
        override val type: String get() = PLAY
    }

    /** A song was skipped at [positionMs]. */
    data class Skip(val track: TrackRef, val positionMs: Long) : EventBody {
        override val type: String get() = SKIP
    }

    /** A song was queued by hand: a strong "I want this". */
    data class Queued(val track: TrackRef) : EventBody {
        override val type: String get() = QUEUED
    }

    data class Like(val subject: Subject) : EventBody {
        override val type: String get() = LIKE
    }

    data class Unlike(val subject: Subject) : EventBody {
        override val type: String get() = UNLIKE
    }

    data class NotInterested(val subject: Subject) : EventBody {
        override val type: String get() = NOT_INTERESTED
    }

    /** Undoes [NotInterested]. */
    data class Interested(val subject: Subject) : EventBody {
        override val type: String get() = INTERESTED
    }

    data class PlaylistCreate(val playlistId: String, val name: String) : EventBody {
        override val type: String get() = PLAYLIST_CREATE
    }

    data class PlaylistRename(val playlistId: String, val name: String) : EventBody {
        override val type: String get() = PLAYLIST_RENAME
    }

    data class PlaylistDelete(val playlistId: String) : EventBody {
        override val type: String get() = PLAYLIST_DELETE
    }

    /** Adds [track] at [index], or at the end when [index] is null or past the end. */
    data class PlaylistAdd(val playlistId: String, val track: TrackRef, val index: Int? = null) : EventBody {
        override val type: String get() = PLAYLIST_ADD
    }

    data class PlaylistRemove(val playlistId: String, val trackId: String) : EventBody {
        override val type: String get() = PLAYLIST_REMOVE
    }

    /** Moves [trackId] to [toIndex] (clamped to the list). */
    data class PlaylistMove(val playlistId: String, val trackId: String, val toIndex: Int) : EventBody {
        override val type: String get() = PLAYLIST_MOVE
    }

    /** A tape (a YouTube video in Minim's tapes) was played at [speedPercent]. */
    data class TapePlay(val tape: TrackRef, val listenedMs: Long, val speedPercent: Int = 100) : EventBody {
        override val type: String get() = TAPE_PLAY
    }

    data class TasteEdit(val kind: TasteEditKind, val subject: Subject) : EventBody {
        override val type: String get() = TASTE_EDIT
    }

    /**
     * An event this version does not understand (written by a newer version). Kept verbatim, so
     * an older phone passes it on intact instead of dropping it. [json] is the raw body object.
     */
    data class Unknown(override val type: String, val json: String) : EventBody

    companion object {
        const val PLAY = "play"
        const val SKIP = "skip"
        const val QUEUED = "queued"
        const val LIKE = "like"
        const val UNLIKE = "unlike"
        const val NOT_INTERESTED = "not_interested"
        const val INTERESTED = "interested"
        const val PLAYLIST_CREATE = "playlist_create"
        const val PLAYLIST_RENAME = "playlist_rename"
        const val PLAYLIST_DELETE = "playlist_delete"
        const val PLAYLIST_ADD = "playlist_add"
        const val PLAYLIST_REMOVE = "playlist_remove"
        const val PLAYLIST_MOVE = "playlist_move"
        const val TAPE_PLAY = "tape_play"
        const val TASTE_EDIT = "taste_edit"
    }
}

/** One entry of the append-only log. */
data class Event(val id: EventId, val ctx: EventContext, val body: EventBody) {
    val timestamp: Long get() = ctx.timestamp
    val type: String get() = body.type

    companion object {
        /**
         * The canonical total order of the log: timestamp, then device id, then counter. Every
         * projection replays events in this order, which is what makes conflicting writes resolve
         * last-writer-wins with a deterministic tiebreak, on every device, whatever the merge order.
         */
        val ORDER: Comparator<Event> =
            compareBy<Event> { it.ctx.timestamp }.thenBy { it.id.device }.thenBy { it.id.counter }
    }
}
