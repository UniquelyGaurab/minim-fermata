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

    /**
     * What projections replay: [canonical], minus every event whose id another event names in
     * [EventContext.replaces]. The replaced events stay in the log (merges stay a plain union);
     * they are only left out of the library, stats and taste. Order-independent, so every device
     * that holds the same events agrees on what counts.
     */
    fun effective(events: Iterable<Event>): List<Event> {
        val log = canonical(events)
        val replaced = log.mapNotNullTo(HashSet()) { it.ctx.replaces }
        val clearedBefore = clearedBefore(log)
        if (replaced.isEmpty() && clearedBefore == null) return log
        return log.filter { it.id !in replaced && !isCleared(it, clearedBefore) }
    }

    /** The newest "clear listening history" point in [events], or null when there is none. */
    fun clearedBefore(events: Iterable<Event>): Long? {
        var best: Long? = null
        for (e in events) {
            val b = e.body as? EventBody.HistoryClear ?: continue
            if (best == null || b.before > best) best = b.before
        }
        return best
    }

    /** Whether [event] is listening history from before the clear point [clearedBefore]. */
    fun isCleared(event: Event, clearedBefore: Long?): Boolean =
        clearedBefore != null && event.ctx.timestamp < clearedBefore && EventBody.isHistory(event.body.type)

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
