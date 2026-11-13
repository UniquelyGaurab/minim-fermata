package com.minim.fermata.sync

import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventContext
import com.minim.fermata.log.EventId
import com.minim.fermata.log.Source
import com.minim.fermata.log.Subject
import com.minim.fermata.log.TrackRef
import java.time.Instant
import java.time.ZoneId
import java.util.Random
import java.util.UUID

/**
 * A generated listening history shaped like a real Minim 6.1.1 log, for the size and speed
 * benchmarks. It follows what Minim actually records (see Minim's FermataBridge, LibrarySync and
 * FermataLogger):
 *
 * - **The 5.10 import**, once: old `plays` rows as legacy Play events with the song's full info
 *   (title, artist, length, thumbnail), Minim 5.9.0's typed rows (plays, skips, queues) with UUID
 *   sessions, likes and playlists, and one `history.minim.write` per day of old history holding
 *   that day's plays, song info and listening time, plus a `minim.write` per like and playlist.
 * - **Every song since**: one typed Play (or Skip) when the next song starts, a
 *   `history.minim.write` when the play counts at 30 s (the play and the song's info), and a
 *   `history.minim.write` each time the listening time is saved, every 5 s while it plays (the
 *   song's listening time for the day, when it was last played, and the song's info again).
 * - Now and then a like (typed Like + `minim.write`), a queue add or a "not interested".
 *
 * App writes are stored as Minim stores them: org.json text, so `/` is written `\/` and the
 * song-info separator U+001F as `\u001f`. Thumbnails are YouTube Music's: mostly long
 * `lh3.googleusercontent.com` links, some `i.ytimg.com` ones with their query strings.
 */
object RealisticHistory {
    const val DEVICE = "d3f9a0c5e1b7d"
    private val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    private const val DAY = 86_400_000L

    /** Roughly [target] events (it stops at the first song that reaches it). */
    fun generate(target: Int, seed: Long = 7, device: String = DEVICE, start: Long = 1_793_000_000_000L): List<Event> =
        Gen(Random(seed), device, start, 5_000L).run(target, Int.MAX_VALUE)

    /**
     * The same listening as [generate] with the same [target] and [seed] (the same songs, plays,
     * skips, likes and hours), with listening time saved the 6.2.0 way: a checkpoint every
     * [saveEveryMs] while a song plays and once more when it ends, instead of every 5 s.
     */
    fun generateLean(target: Int, seed: Long = 7, saveEveryMs: Long = 60_000L, device: String = DEVICE, start: Long = 1_793_000_000_000L): List<Event> {
        val old = Gen(Random(seed), device, start, 5_000L)
        old.run(target, Int.MAX_VALUE)
        return Gen(Random(seed), device, start, saveEveryMs).run(target, old.songsPlayed, stopAt = Int.MAX_VALUE)
    }

    private class Song(val track: TrackRef, val meta: String)

    private class Gen(val rnd: Random, val device: String, val start: Long, val saveEveryMs: Long) {
        val out = ArrayList<Event>()
        var songsPlayed = 0
        var counter = 0L
        var session: String? = null
        var lastWall = Long.MIN_VALUE
        val songs = ArrayList<Song>()
        val words = listOf(
            "Tum", "Hi", "Ho", "Kesariya", "Raataan", "Lambiyan", "Apna", "Bana", "Le", "Channa", "Mereya",
            "Phir", "Kabhi", "Tera", "Mera", "Dil", "Ishq", "Love", "Night", "Blinding", "Lights", "Dreams",
            "Midnight", "City", "Shape", "Of", "You", "Perfect", "Heeriye", "Satranga", "Pasoori", "Maan",
            "Meri", "Jaan", "Kahani", "Suno", "Agar", "Tum", "Saath", "Ho", "Khairiyat", "Shayad", "Tujhe",
            "Kitna", "Chahne", "Lage", "Hum", "Baarishein", "Jhoome", "Jo", "Pathaan", "Kaise", "Hua",
        )
        val artists = listOf(
            "Arijit Singh", "Pritam", "Shreya Ghoshal", "A.R. Rahman", "Anuv Jain", "Jubin Nautiyal",
            "The Weeknd", "Ed Sheeran", "Taylor Swift", "Atif Aslam", "Prateek Kuhad", "Darshan Raval",
            "Sachet Tandon", "Parampara Tandon", "Ali Sethi", "Shae Gill", "Vishal Mishra", "KK",
            "Neha Kakkar", "Badshah", "Diljit Dosanjh", "AP Dhillon", "Coldplay", "Imagine Dragons",
        )
        val b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val sources = listOf(Source.RADIO, Source.RADIO, Source.RADIO, Source.LIBRARY, Source.SEARCH, Source.PLAYLIST, Source.DISCOVER)

        fun rs(n: Int, alphabet: String = b64) = buildString { repeat(n) { append(alphabet[rnd.nextInt(alphabet.length)]) } }

        fun newSong(): Song {
            val id = rs(11)
            val title = (1..(2 + rnd.nextInt(4))).joinToString(" ") { words[rnd.nextInt(words.size)] } +
                if (rnd.nextInt(5) == 0) " (From \"${words[rnd.nextInt(words.size)]} ${words[rnd.nextInt(words.size)]}\")" else ""
            val artist = if (rnd.nextInt(3) == 0) "${artists[rnd.nextInt(artists.size)]}, ${artists[rnd.nextInt(artists.size)]}"
            else artists[rnd.nextInt(artists.size)]
            val sec = 150L + rnd.nextInt(180)
            val thumb = if (rnd.nextInt(10) < 7) "https://lh3.googleusercontent.com/${rs(100 + rnd.nextInt(60))}=w120-h120-l90-rj"
            else "https://i.ytimg.com/vi/$id/sddefault.jpg?sqp=-oaymwEWCJADEOEBIAQqCghqEJQEGHgg6AJIWg&rs=AMzJL3${rs(28)}"
            val meta = listOf(title, artist, thumb, sec.toString()).joinToString("\u001f")
            return Song(TrackRef(id, title, artist, sec * 1000, thumb), meta)
        }

        /** Popular songs come up much more often, like a real library. */
        fun pick(): Song {
            if (songs.size < 30 || rnd.nextInt(100) < 12) songs.add(newSong()).also { return songs.last() }
            val i = (songs.size * Math.pow(rnd.nextDouble(), 2.2)).toInt().coerceIn(0, songs.size - 1)
            return songs[i]
        }

        fun ctx(ts: Long, session: String?, prev: String? = null, source: Source = Source.UNKNOWN, replaces: EventId? = null) =
            EventContext.at(ts, ZONE, session, prev, source, replaces = replaces)

        fun add(body: EventBody, ts: Long, session: String?, prev: String? = null, source: Source = Source.UNKNOWN, replaces: EventId? = null): Event {
            val e = Event(EventId(device, ++counter), ctx(ts, session, prev, source, replaces), body)
            out.add(e)
            return e
        }

        /** Fermata.record's own session rule, for app writes. */
        fun write(type: String, json: String, ts: Long) {
            if (session == null || ts - lastWall >= 30 * 60_000L) session = "$device.${counter + 1}"
            lastWall = ts
            add(EventBody.Unknown(type, json), ts, session)
        }

        // org.json text, as Minim's recordWrite makes it.
        fun q(s: String) = buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '/' -> append("\\/")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }

        fun day(ts: Long): Int {
            val d = Instant.ofEpochMilli(ts).atZone(ZONE).toLocalDate()
            return d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
        }

        fun hour(ts: Long) = Instant.ofEpochMilli(ts).atZone(ZONE).hour

        fun historyWrite(day: Int, p: Map<String, String>, s: Map<String, String>, l: Map<String, Long>, r: Map<String, Long>): String {
            val parts = ArrayList<String>()
            parts.add("\"d\":$day")
            if (p.isNotEmpty()) parts.add("\"p\":{" + p.entries.joinToString(",") { "${q(it.key)}:${q(it.value)}" } + "}")
            if (s.isNotEmpty()) parts.add("\"s\":{" + s.entries.joinToString(",") { "${q(it.key)}:${q(it.value)}" } + "}")
            if (l.isNotEmpty()) parts.add("\"l\":{${q(device)}:{" + l.entries.joinToString(",") { "${q(it.key)}:${it.value}" } + "}}")
            if (r.isNotEmpty()) parts.add("\"r\":{${q(device)}:{" + r.entries.joinToString(",") { "${q(it.key)}:${it.value}" } + "}}")
            return "{\"p\":${q("history/$day")},\"m\":true,\"d\":{${parts.joinToString(",")}}}"
        }

        // Minim's SyncIds.eventId
        fun syncEventId(ts: Long, song: String) = "${java.lang.Long.toString(ts, 36)}_$song"

        fun playCode(song: String, ts: Long, listened: Long, tracked: Boolean) =
            listOf(song, ts.toString(), listened.toString(), if (tracked) "1" else "0").joinToString("\u001f")

        fun run(target: Int, maxSongs: Int, stopAt: Int = target): List<Event> {
            val importAt = start
            // ---- the 5.10 import: ~90 days of old history, sized to the target ----
            val oldPlays = (target * 0.18).toInt()
            val oldDays = 90
            data class Old(val song: Song, val ts: Long, val listened: Long, val eid: String)
            val old = ArrayList<Old>()
            repeat(oldPlays) {
                val ts = importAt - oldDays * DAY + (rnd.nextDouble() * (oldDays - 1) * DAY).toLong()
                val s = pick()
                val listened = (s.track.durationMs * (0.5 + rnd.nextDouble() * 0.5)).toLong()
                old.add(Old(s, ts, listened, syncEventId(ts, s.track.id)))
            }
            old.sortBy { it.ts }
            val typedSince = importAt - 14 * DAY // Minim 5.9.0 kept typed rows for its last two weeks
            var uuid = UUID(rnd.nextLong(), rnd.nextLong()).toString()
            var lastTs = 0L
            var prev: String? = null
            for (o in old) {
                val legacy = EventId(EventId.LEGACY_DEVICE, rnd.nextLong() and Long.MAX_VALUE)
                val body = EventBody.Play(o.song.track, o.listened, countedAt = o.ts + 30_000, syncId = o.eid, estimated = rnd.nextInt(20) == 0)
                out.add(Event(legacy, ctx(o.ts, null, source = Source.IMPORT), body))
                if (o.ts >= typedSince) {
                    if (o.ts - lastTs > 30 * 60_000L) { uuid = UUID(rnd.nextLong(), rnd.nextLong()).toString(); prev = null }
                    val t = o.song.track.copy(sourceUrl = "")
                    add(EventBody.Play(t, o.listened, countedAt = o.ts + 30_000, syncId = o.eid), o.ts, uuid, prev, sources[rnd.nextInt(sources.size)], replaces = legacy)
                    if (rnd.nextInt(4) == 0) add(EventBody.Skip(pick().track, 2_000L + rnd.nextInt(20_000)), o.ts + 1, uuid, o.song.track.id, Source.RADIO)
                    if (rnd.nextInt(25) == 0) add(EventBody.Queued(pick().track), o.ts + 2, uuid, null, Source.SEARCH)
                    prev = o.song.track.id
                    lastTs = o.ts
                }
            }
            val liked = songs.shuffled(rnd).take((target * 0.012).toInt().coerceAtLeast(5))
            for ((i, s) in liked.withIndex()) add(EventBody.Like(Subject.Track(s.track)), importAt - (liked.size - i) * 3_600_000L, null, source = Source.IMPORT)
            val playlists = (1..3).map { "pl${rs(10)}" }
            for ((pi, pl) in playlists.withIndex()) {
                add(EventBody.PlaylistCreate(pl, listOf("Gym", "Late night", "Arijit forever")[pi]), importAt - 80 * DAY + pi, null, source = Source.IMPORT)
                repeat((target * 0.008).toInt().coerceAtLeast(5)) { k ->
                    add(EventBody.PlaylistAdd(pl, songs[rnd.nextInt(songs.size)].track), importAt - 80 * DAY + 1000L * k + pi, null, source = Source.IMPORT)
                }
            }
            // The app's own writes for the old history: one per day doc (all of its plays), likes, playlists.
            var t = importAt + 1000
            for ((d, plays) in old.groupBy { day(it.ts) }) {
                val p = LinkedHashMap<String, String>(); val s = LinkedHashMap<String, String>()
                val l = LinkedHashMap<String, Long>(); val r = LinkedHashMap<String, Long>()
                for (o in plays) {
                    p[o.eid] = playCode(o.song.track.id, o.ts, o.listened, true)
                    s[o.song.track.id] = o.song.meta
                    val k = "${hour(o.ts)}_${o.song.track.id}"
                    l[k] = (l[k] ?: 0L) + o.listened
                    r[o.song.track.id] = o.ts + o.listened
                }
                write("history.minim.write", historyWrite(d, p, s, l, r), t++)
            }
            for (s in liked) write("minim.write", "{\"p\":${q("likes/${s.track.id}")},\"m\":false,\"d\":{\"l\":true,\"at\":${importAt - 5000},\"m\":${q(s.meta)}}}", t++)
            for (pl in playlists) {
                val entries = (0 until 8).joinToString(",") { k -> val s = songs[rnd.nextInt(songs.size)]; "${q(s.track.id)}:${q("$k\u001f${importAt - 80 * DAY}\u001f0\u001f${s.meta}")}" }
                write("minim.write", "{\"p\":${q("playlists/$pl")},\"m\":true,\"d\":{\"n\":\"Gym\",\"c\":${importAt - 80 * DAY},\"h\":$importAt,\"b\":${importAt - 80 * DAY},\"del\":false,\"t\":{$entries}}}", t++)
            }

            // ---- Minim 5.10 to 6.1.1: every song since ----
            var now = importAt + 3_600_000L
            val listenToday = HashMap<String, Long>() // "hour_song" -> ms, reset each day
            var today = day(now)
            while (out.size < stopAt && songsPlayed < maxSongs) {
                // a listening session, 1 to 3 a day
                now += (2 + rnd.nextInt(10)) * 3_600_000L
                val sessionUuid = UUID(rnd.nextLong(), rnd.nextLong()).toString()
                var prevSong: String? = null
                repeat(4 + rnd.nextInt(14)) {
                    if (out.size >= stopAt || songsPlayed >= maxSongs) return@repeat
                    songsPlayed++
                    val s = pick()
                    val startAt = now
                    val skipped = rnd.nextInt(100) < 22
                    val heard = if (skipped) 2_000L + rnd.nextInt(25_000) else (s.track.durationMs * (0.6 + rnd.nextDouble() * 0.4)).toLong()
                    if (day(startAt) != today) { today = day(startAt); listenToday.clear() }
                    val key = "${hour(startAt)}_${s.track.id}"
                    val eid = syncEventId(startAt, s.track.id)
                    var saved = 0L
                    var counted = false
                    fun save(at: Long, ms: Long) {
                        if (!counted && at >= 30_000L) {
                            counted = true
                            write("history.minim.write", historyWrite(today, mapOf(eid to playCode(s.track.id, startAt, 30_000, true)), mapOf(s.track.id to s.meta), emptyMap(), emptyMap()), startAt + at)
                        }
                        saved += ms
                        listenToday[key] = (listenToday[key] ?: 0L) + ms
                        val l = listenToday.filterKeys { it.endsWith("_${s.track.id}") }
                        write("history.minim.write", historyWrite(today, emptyMap(), mapOf(s.track.id to s.meta), l, mapOf(s.track.id to startAt + at)), startAt + at)
                    }
                    if (saveEveryMs == 5_000L) {
                        // 6.1.1: every 5 s while it plays
                        var at = 5_000L
                        while (at <= heard) {
                            save(at, 5_000)
                            at += 5_000
                        }
                    } else {
                        // 6.2.0: a checkpoint every minute, and the rest when the song ends
                        var at = saveEveryMs
                        while (at < heard) {
                            save(at, saveEveryMs)
                            at += saveEveryMs
                        }
                        save(heard, heard - saved)
                    }
                    now = startAt + heard + 500
                    val src = sources[rnd.nextInt(sources.size)]
                    val t2 = s.track.copy(thumbnailUrl = "")
                    if (skipped) add(EventBody.Skip(t2, heard), now, sessionUuid, prevSong, src)
                    else add(EventBody.Play(t2, heard, countedAt = startAt + 30_000, syncId = eid), now, sessionUuid, prevSong, src)
                    if (!skipped) prevSong = s.track.id
                    when (rnd.nextInt(60)) {
                        0 -> {
                            add(EventBody.Like(Subject.Track(s.track)), now + 1, null)
                            write("minim.write", "{\"p\":${q("likes/${s.track.id}")},\"m\":false,\"d\":{\"l\":true,\"at\":${now + 1},\"m\":${q(s.meta)}}}", now + 2)
                        }
                        1, 2 -> add(EventBody.Queued(pick().track), now + 1, sessionUuid, null, Source.SEARCH)
                        3 -> {
                            add(EventBody.NotInterested(Subject.Track(s.track)), now + 1, null)
                            write("minim.write", "{\"p\":${q("hidden/${s.track.id}")},\"m\":false,\"d\":{\"at\":${now + 1},\"m\":${q(s.meta.substringBeforeLast('\u001f').substringBeforeLast('\u001f') + "\u001f\u001f0")}}}", now + 2)
                        }
                    }
                }
            }
            return out.sortedWith(Event.ORDER)
        }
    }
}
