package com.minim.fermata.log

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The unique id of an event: the device that wrote it plus that device's own counter (1, 2, 3...).
 *
 * Two devices never write the same id, so merging logs is a plain union with duplicates dropped
 * by id. Written as `device:counter`.
 */
data class EventId(val device: String, val counter: Long) : Comparable<EventId> {
    init {
        require(device.isNotBlank()) { "device id must not be blank" }
        require(counter >= 1) { "counter starts at 1, got $counter" }
    }

    override fun compareTo(other: EventId): Int =
        compareValuesBy(this, other, EventId::device, EventId::counter)

    override fun toString(): String = "$device:$counter"

    companion object {
        /**
         * The pseudo-device used for history imported from Minim's old tables (and from the
         * Firestore copy). Its counters are hashes, not a sequence; see [legacy].
         */
        const val LEGACY_DEVICE = "legacy"

        fun parse(text: String): EventId {
            val cut = text.lastIndexOf(':')
            require(cut > 0 && cut < text.length - 1) { "not an event id: $text" }
            return EventId(text.substring(0, cut), text.substring(cut + 1).toLong())
        }

        /**
         * A deterministic id for an imported legacy record, derived from a stable key such as
         * `play|<event_id>` or `like|<track_id>|<liked_at>`. Two phones importing the same synced
         * history produce the same ids, so a later merge does not count anything twice.
         */
        fun legacy(key: String): EventId {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (digest[i].toLong() and 0xFF)
            val counter = value and Long.MAX_VALUE
            return EventId(LEGACY_DEVICE, if (counter == 0L) 1L else counter)
        }
    }
}

/** Device ids: random, stable per install, never derived from hardware or accounts. */
object DeviceIds {
    private val random = SecureRandom()

    /** 16 lowercase hex characters, for example `3f9a0c5e1b7d2468`. */
    fun random(): String {
        val bytes = ByteArray(8).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
