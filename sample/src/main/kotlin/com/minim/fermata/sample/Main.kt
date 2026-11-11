package com.minim.fermata.sample

import com.minim.fermata.Fermata
import com.minim.fermata.log.DeviceIds
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.InMemoryEventStore
import com.minim.fermata.log.Source
import com.minim.fermata.log.Subject
import com.minim.fermata.log.TrackRef
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** A tiny walk-through: record some listening, rank a radio, back up, restore on a "new phone". */
fun main() {
    val phone = Fermata(InMemoryEventStore(), DeviceIds.random())
    val songs = listOf(
        TrackRef("kJQP7kiw5Fk", "Despacito", "Luis Fonsi", 229_000),
        TrackRef("JGwWNGJdvx8", "Shape of You", "Ed Sheeran", 263_000),
        TrackRef("fJ9rUzIMcZQ", "Bohemian Rhapsody", "Queen", 355_000),
    )

    phone.record(EventBody.Play(songs[2], 355_000), Source.SEARCH)
    phone.record(EventBody.Like(Subject.Track(songs[2])))
    phone.record(EventBody.Skip(songs[0], 6_000), Source.RADIO)
    phone.record(EventBody.PlaylistCreate("road", "Road trip"))
    phone.record(EventBody.PlaylistAdd("road", songs[2]))
    phone.record(EventBody.PlaylistAdd("road", songs[1]))

    println("Liked: " + phone.library().likedTracks.joinToString { it.track.title })
    println("Road trip: " + phone.library().playlist("road")!!.tracks.joinToString { it.title })
    println("Confidence: %.3f".format(phone.confidence()))
    println("Radio as ranked (unchanged at low confidence):")
    phone.rank(songs).forEach { println("  ${it.track.title}  ${it.reason ?: ""}") }

    val backup = ByteArrayOutputStream()
    val manifest = phone.export(backup, appVersion = "sample")
    println("Backup: ${manifest.eventCount} events, ${backup.size()} bytes")

    val newPhone = Fermata(InMemoryEventStore(), DeviceIds.random())
    val result = newPhone.import(ByteArrayInputStream(backup.toByteArray()))
    println("Restored ${result.added} events; libraries match: ${newPhone.library() == phone.library()}")
}
