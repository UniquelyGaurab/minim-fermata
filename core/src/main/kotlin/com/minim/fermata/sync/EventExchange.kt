package com.minim.fermata.sync

import com.minim.fermata.Fermata
import com.minim.fermata.backup.BackupArchive
import com.minim.fermata.backup.CompactCodec
import com.minim.fermata.log.Event
import com.minim.fermata.log.EventId

/**
 * What a phone has, in a few hundred bytes: its version vector (the highest counter it holds from
 * each device) and a digest of the imported old plays it holds, which have no counters. The
 * first thing two phones swap before exchanging events.
 */
data class SyncState(
    val deviceId: String,
    val heads: Map<String, Long>,
    val legacyCount: Long,
    val legacyDigest: String,
) {
    /** A few lines of plain text: `fermata-sync 1`, `device <id>`, `legacy <count> <digest>`, then `head <device> <counter>` per device. */
    fun encode(): ByteArray = buildString {
        append("fermata-sync 1\n")
        append("device ").append(deviceId).append('\n')
        append("legacy ").append(legacyCount).append(' ').append(legacyDigest).append('\n')
        for ((d, c) in heads.toSortedMap()) append("head ").append(d).append(' ').append(c).append('\n')
    }.toByteArray(Charsets.UTF_8)

    companion object {
        /** Reads [encode]'s text. Throws [IllegalArgumentException] on anything else. */
        fun decode(bytes: ByteArray): SyncState {
            val lines = bytes.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
            require(lines.firstOrNull() == "fermata-sync 1") { "not a sync state, or a newer one" }
            var device: String? = null
            var legacy: Pair<Long, String>? = null
            val heads = LinkedHashMap<String, Long>()
            for (line in lines.drop(1)) {
                val f = line.split(' ')
                when (f[0]) {
                    "device" -> device = f.getOrNull(1)
                    "legacy" -> legacy = f[1].toLong() to f[2]
                    "head" -> heads[f[1]] = f[2].toLong().also { require(it >= 0) { "bad counter" } }
                    else -> Unit // a newer field: ignored
                }
            }
            val (count, digest) = requireNotNull(legacy) { "no legacy line" }
            return SyncState(requireNotNull(device) { "no device line" }, heads, count, digest)
        }
    }
}

/**
 * Event exchange between two phones, for a direct sync (Wi-Fi sync in Minim 6.3; nothing in 6.2
 * calls it yet). The same pieces the backup folder uses, without the folder:
 *
 * 1. each side sends [state];
 * 2. each side sends [missingFor] the other's state, packed with [pack] (the backup body codec:
 *    about 17 to 20 bytes an event);
 * 3. each side [receive]s what came in. Merging is by event id, so a repeated or interrupted
 *    exchange adds nothing twice and can simply be run again.
 *
 * Imported old plays (no counters) are sent only when the two digests differ.
 */
class EventExchange(private val fermata: Fermata) {

    /** This phone's version vector and legacy digest. */
    fun state(): SyncState = fermata.transaction {
        val heads = fermata.loggedHeads()
        val legacy = legacyEvents(heads)
        SyncState(fermata.deviceId, heads, legacy.size.toLong(), BackupArchive.legacyDigest(legacy))
    }

    /** The events a phone in state [peer] lacks, oldest first. */
    fun missingFor(peer: SyncState): List<Event> = fermata.transaction {
        val out = ArrayList(fermata.deviceEventsAfter(peer.heads))
        val heads = fermata.loggedHeads()
        val legacy = legacyEvents(heads)
        if (legacy.isNotEmpty() && BackupArchive.legacyDigest(legacy) != peer.legacyDigest) out += legacy
        out.sortedWith(Event.ORDER)
    }

    /** Merges events from another phone. Returns how many were new. */
    fun receive(events: List<Event>): Int = fermata.merge(events)

    fun pack(events: List<Event>): ByteArray = CompactCodec.pack(events.sortedWith(Event.ORDER))

    fun unpack(bytes: ByteArray): List<Event> = CompactCodec.unpack(bytes)

    // Past every counter we hold, only the legacy events are left.
    private fun legacyEvents(heads: Map<String, Long>): List<Event> =
        fermata.eventsAfter(heads).filter { it.id.device == EventId.LEGACY_DEVICE }
}
