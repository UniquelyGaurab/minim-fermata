package com.minim.fermata.log

import java.util.TreeSet

/** An [EventStore] in memory, for tests, tools and the sample. */
class InMemoryEventStore(initial: Iterable<Event> = emptyList()) : EventStore {
    private val lock = Any()
    private val byId = HashMap<EventId, Event>()
    private val ordered = TreeSet(Event.ORDER)
    private val heads = HashMap<String, Long>()

    init {
        appendAll(initial)
    }

    override fun append(event: Event): Boolean = synchronized(lock) {
        if (byId.containsKey(event.id)) return false
        byId[event.id] = event
        ordered.add(event)
        if (event.id.device != EventId.LEGACY_DEVICE) {
            val current = heads[event.id.device]
            if (current == null || event.id.counter > current) heads[event.id.device] = event.id.counter
        }
        true
    }

    override fun appendAll(events: Iterable<Event>): Int = synchronized(lock) { events.count { append(it) } }

    override fun all(): List<Event> = synchronized(lock) { ArrayList(ordered) }

    override fun contains(id: EventId): Boolean = synchronized(lock) { byId.containsKey(id) }

    override fun count(): Long = synchronized(lock) { byId.size.toLong() }

    override fun heads(): Map<String, Long> = synchronized(lock) { HashMap(heads) }

    /** Heads stay where they were: a counter once used is never handed out again. */
    override fun removeAll(ids: Collection<EventId>): Int = synchronized(lock) {
        var removed = 0
        for (id in ids) {
            val e = byId.remove(id) ?: continue
            ordered.remove(e)
            removed++
        }
        removed
    }

    private var depth = 0

    /** All or nothing like a database transaction: if [block] throws, the store is put back. */
    override fun <T> inTransaction(block: () -> T): T = synchronized(lock) {
        val saved = if (depth == 0) Triple(HashMap(byId), TreeSet(ordered), HashMap(heads)) else null
        depth++
        try {
            block()
        } catch (t: Throwable) {
            if (saved != null) {
                byId.clear(); byId.putAll(saved.first)
                ordered.clear(); ordered.addAll(saved.second)
                // heads keep any counter used meanwhile: a counter is never handed out twice
                for ((d, c) in saved.third) if ((heads[d] ?: Long.MIN_VALUE) < c) heads[d] = c
            }
            throw t
        } finally {
            depth--
        }
    }

    override fun maxTimestamp(): Long? = synchronized(lock) { if (ordered.isEmpty()) null else ordered.last().timestamp }
}
