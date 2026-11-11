package com.minim.fermata.library

import com.minim.fermata.log.Source
import com.minim.fermata.log.TrackRef

data class LikedTrack(val track: TrackRef, val likedAt: Long)

data class LikedArtist(val name: String, val likedAt: Long)

data class Playlist(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** In playlist order. */
    val tracks: List<TrackRef>,
)

data class PlayRecord(
    val track: TrackRef,
    val playedAt: Long,
    val listenedMs: Long,
    val source: Source,
    val sessionId: String?,
)

data class TapeRecord(
    val tape: TrackRef,
    val playedAt: Long,
    val listenedMs: Long,
    val speedPercent: Int,
)

/**
 * Minim's library as a projection of the log: what the old tables held, rebuilt from events.
 * Lists that have a natural "newest first" order (likes, history) are newest first.
 */
data class LibraryState(
    /** Newest known metadata per track id. */
    val tracks: Map<String, TrackRef>,
    val likedTracks: List<LikedTrack>,
    val likedArtists: List<LikedArtist>,
    /** In creation order. */
    val playlists: List<Playlist>,
    /** Counted plays (heard long enough to count), newest first. */
    val history: List<PlayRecord>,
    val tapeHistory: List<TapeRecord>,
    val notInterestedTracks: List<TrackRef>,
    val notInterestedArtists: List<String>,
    /** Counted plays per track id. */
    val playCounts: Map<String, Int>,
    /** Total time heard per track id, counted or not. */
    val listenedMs: Map<String, Long>,
    /** How many events were applied. */
    val eventCount: Long,
) {
    private val likedIds by lazy { likedTracks.mapTo(HashSet()) { it.track.id } }

    fun isLiked(trackId: String): Boolean = trackId in likedIds

    fun playlist(id: String): Playlist? = playlists.firstOrNull { it.id == id }

    val totalListenedMs: Long get() = listenedMs.values.sum()

    companion object {
        val EMPTY = LibraryState(
            emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyMap(), emptyMap(), 0,
        )
    }
}
