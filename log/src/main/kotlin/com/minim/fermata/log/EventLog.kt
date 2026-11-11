package com.minim.fermata.log

/**
 * Merging logs. A merge is the union of the inputs with duplicate ids dropped, in canonical
 * [Event.ORDER]. That makes it commutative (a + b == b + a), associative and idempotent
 * (a + a == a), so devices converge whatever order they exchange events in.
 */
object EventLog {
    // The trailing toString() tiebreak only matters for a corrupt log where one id carries two
    // different events: the copy kept is still the same on every device.
    private val canonical: Comparator<Event> = Event.ORDER.thenBy { it.toString() }

    fun merge(vararg logs: Iterable<Event>): List<Event> = canonical(logs.flatMap { it })

    /** Sorts into canonical order and drops duplicate ids. */
    fun canonical(events: Iterable<Event>): List<Event> = events.sortedWith(canonical).distinctBy { it.id }

    /** The highest counter seen per device (a version vector). The legacy pseudo-device is left out. */
    fun heads(events: Iterable<Event>): Map<String, Long> {
        val heads = HashMap<String, Long>()
        for (e in events) {
            if (e.id.device == EventId.LEGACY_DEVICE) continue
            val current = heads[e.id.device]
            if (current == null || e.id.counter > current) heads[e.id.device] = e.id.counter
        }
        return heads
    }

    /**
     * The events a peer whose version vector is [heads] has not seen yet. Legacy events have
     * hashed counters, so they are always included; the receiver drops the ones it already has.
     */
    fun after(events: Iterable<Event>, heads: Map<String, Long>): List<Event> = events.filter {
        it.id.device == EventId.LEGACY_DEVICE || it.id.counter > (heads[it.id.device] ?: 0L)
    }
}
