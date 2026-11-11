package com.minim.fermata.library

import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventLog
import com.minim.fermata.log.Subject
import com.minim.fermata.log.TrackRef

/**
 * Folds events into a [LibraryState]. Events must arrive in canonical [Event.ORDER]; that is what
 * gives last-writer-wins for likes, "not interested" and playlist edits.
 *
 * Use [LibraryProjection.build] for a whole log. Use [apply] directly to keep a live projection
 * up to date as new local events arrive; when an event would sort before [lastApplied] (a merge
 * brought in older events), [apply] returns false and the caller rebuilds instead.
 *
 * Playlist rules:
 * - a track is in a playlist at most once (as in Minim); adding it again is a no-op
 * - an index past the end appends; a move clamps to the list
 * - a deleted playlist id is a tombstone: later adds or renames to it are ignored
 * - an add to a playlist whose create has not been seen yet creates it with an empty name, and
 *   the create fills the name in when it arrives
 */
class LibraryProjector(private val countedPlayMs: Long = DEFAULT_COUNTED_PLAY_MS) {
    private class MutablePlaylist(val id: String, var name: String, val createdAt: Long) {
        var updatedAt: Long = createdAt
        val trackIds = ArrayList<String>()
        var named = false
    }

    private val tracks = HashMap<String, TrackRef>()
    private val liked = LinkedHashMap<String, Long>()
    private val likedArtists = LinkedHashMap<String, LikedArtist>()
    private val notInterestedTracks = LinkedHashMap<String, Long>()
    private val notInterestedArtists = LinkedHashMap<String, String>()
    private val playlists = LinkedHashMap<String, MutablePlaylist>()
    private val deletedPlaylists = HashSet<String>()
    private val history = ArrayList<PlayRecord>()
    private val tapeHistory = ArrayList<TapeRecord>()
    private val playCounts = HashMap<String, Int>()
    private val listened = HashMap<String, Long>()
    private var applied = 0L

    var lastApplied: Event? = null
        private set

    /** Applies [event]. Returns false (and changes nothing) if it sorts before [lastApplied]. */
    fun apply(event: Event): Boolean {
        val last = lastApplied
        if (last != null && Event.ORDER.compare(event, last) < 0) return false
        if (last != null && last.id == event.id) return true
        lastApplied = event
        applied++
        val t = event.timestamp
        when (val b = event.body) {
            is EventBody.Play -> {
                remember(b.track)
                listened.merge(b.track.id, b.listenedMs, Long::plus)
                if (isCounted(b.track, b.listenedMs)) {
                    playCounts.merge(b.track.id, 1, Int::plus)
                    history.add(PlayRecord(b.track, t, b.listenedMs, event.ctx.source, event.ctx.sessionId))
                }
            }
            is EventBody.Skip -> remember(b.track)
            is EventBody.Queued -> remember(b.track)
            is EventBody.Like -> when (val s = b.subject) {
                is Subject.Track -> {
                    remember(s.track)
                    liked.putIfAbsent(s.track.id, t)
                }
                is Subject.Artist -> likedArtists.putIfAbsent(s.key, LikedArtist(s.name.trim(), t))
            }
            is EventBody.Unlike -> when (val s = b.subject) {
                is Subject.Track -> liked.remove(s.track.id)
                is Subject.Artist -> likedArtists.remove(s.key)
            }
            is EventBody.NotInterested -> when (val s = b.subject) {
                is Subject.Track -> {
                    remember(s.track)
                    notInterestedTracks.putIfAbsent(s.track.id, t)
                }
                is Subject.Artist -> notInterestedArtists.putIfAbsent(s.key, s.name.trim())
            }
            is EventBody.Interested -> when (val s = b.subject) {
                is Subject.Track -> notInterestedTracks.remove(s.track.id)
                is Subject.Artist -> notInterestedArtists.remove(s.key)
            }
            is EventBody.PlaylistCreate -> {
                if (b.playlistId !in deletedPlaylists) {
                    val p = playlists.getOrPut(b.playlistId) { MutablePlaylist(b.playlistId, b.name, t) }
                    if (!p.named) {
                        p.name = b.name
                        p.named = true
                    }
                }
            }
            is EventBody.PlaylistRename -> playlist(b.playlistId, t)?.let {
                it.name = b.name
                it.named = true
                it.updatedAt = t
            }
            is EventBody.PlaylistDelete -> {
                playlists.remove(b.playlistId)
                deletedPlaylists.add(b.playlistId)
            }
            is EventBody.PlaylistAdd -> {
                remember(b.track)
                playlist(b.playlistId, t)?.let { p ->
                    if (b.track.id !in p.trackIds) {
                        val index = b.index
                        if (index == null || index >= p.trackIds.size) p.trackIds.add(b.track.id)
                        else p.trackIds.add(index.coerceAtLeast(0), b.track.id)
                        p.updatedAt = t
                    }
                }
            }
            is EventBody.PlaylistRemove -> playlists[b.playlistId]?.let { p ->
                if (p.trackIds.remove(b.trackId)) p.updatedAt = t
            }
            is EventBody.PlaylistMove -> playlists[b.playlistId]?.let { p ->
                if (p.trackIds.remove(b.trackId)) {
                    p.trackIds.add(b.toIndex.coerceIn(0, p.trackIds.size), b.trackId)
                    p.updatedAt = t
                }
            }
            is EventBody.TapePlay -> tapeHistory.add(TapeRecord(b.tape, t, b.listenedMs, b.speedPercent))
            is EventBody.TasteEdit -> Unit // taste only, the library is unchanged
            is EventBody.Unknown -> Unit
        }
        return true
    }

    fun snapshot(): LibraryState {
        fun meta(t: TrackRef) = tracks[t.id] ?: t
        fun meta(id: String) = tracks[id] ?: TrackRef(id)
        return LibraryState(
            tracks = HashMap(tracks),
            likedTracks = liked.entries.map { LikedTrack(meta(it.key), it.value) }.asReversed().toList(),
            likedArtists = likedArtists.values.toList().asReversed().toList(),
            playlists = playlists.values.map { p ->
                Playlist(p.id, p.name, p.createdAt, p.updatedAt, p.trackIds.map(::meta))
            },
            history = history.asReversed().map { it.copy(track = meta(it.track)) },
            tapeHistory = tapeHistory.asReversed().toList(),
            notInterestedTracks = notInterestedTracks.keys.map(::meta),
            notInterestedArtists = notInterestedArtists.values.toList(),
            playCounts = HashMap(playCounts),
            listenedMs = HashMap(listened),
            eventCount = applied,
        )
    }

    private fun isCounted(track: TrackRef, listenedMs: Long): Boolean {
        val threshold = if (track.durationMs > 0) minOf(countedPlayMs, track.durationMs / 2) else countedPlayMs
        return listenedMs >= threshold
    }

    private fun playlist(id: String, t: Long): MutablePlaylist? {
        if (id in deletedPlaylists) return null
        return playlists.getOrPut(id) { MutablePlaylist(id, "", t) }
    }

    /** Keeps the newest non-empty metadata per track. */
    private fun remember(track: TrackRef) {
        val old = tracks[track.id]
        tracks[track.id] = if (old == null) track else TrackRef(
            id = track.id,
            title = track.title.ifEmpty { old.title },
            artist = track.artist.ifEmpty { old.artist },
            durationMs = if (track.durationMs > 0) track.durationMs else old.durationMs,
            thumbnailUrl = track.thumbnailUrl.ifEmpty { old.thumbnailUrl },
        )
    }

    companion object {
        /** Minim counts a play once 30 seconds were heard (or half of a shorter song). */
        const val DEFAULT_COUNTED_PLAY_MS = 30_000L
    }
}

object LibraryProjection {
    /** Builds the library from a whole log, in any order: it is sorted and deduplicated first. */
    fun build(events: Iterable<Event>, countedPlayMs: Long = LibraryProjector.DEFAULT_COUNTED_PLAY_MS): LibraryState {
        val projector = LibraryProjector(countedPlayMs)
        for (e in EventLog.effective(events)) projector.apply(e)
        return projector.snapshot()
    }
}
