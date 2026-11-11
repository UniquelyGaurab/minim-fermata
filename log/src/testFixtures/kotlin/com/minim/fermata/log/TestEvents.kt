package com.minim.fermata.log

import java.time.ZoneOffset

/** Builds events for tests: one instance per fake device, counters assigned in order. */
class TestDevice(val id: String) {
    private var counter = 0L

    fun event(
        timestamp: Long,
        body: EventBody,
        session: String? = null,
        previous: String? = null,
        source: Source = Source.UNKNOWN,
    ): Event = Event(
        EventId(id, ++counter),
        EventContext.at(timestamp, ZoneOffset.UTC, session, previous, source),
        body,
    )
}

fun track(id: String, artist: String = "Artist $id", durationMs: Long = 200_000): TrackRef =
    TrackRef(id = id, title = "Song $id", artist = artist, durationMs = durationMs)

const val MINUTE = 60_000L
const val HOUR = 60 * MINUTE
const val DAY = 24 * HOUR

/** 2026-11-11T00:00:00Z, a fixed origin for tests. */
const val T0 = 1_794_355_200_000L
