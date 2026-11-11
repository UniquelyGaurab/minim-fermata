package com.minim.fermata.log

/**
 * Where the log lives. The JVM modules only know this interface; the `android` module implements
 * it on SQLite and [InMemoryEventStore] serves tests and tools.
 *
 * Implementations must be safe to call from several threads and must ignore an event whose id is
 * already stored (that is what makes re-importing a backup or a sync folder harmless).
 */
interface EventStore {
    /** Stores [event] unless its id is already present. Returns true when it was new. */
    fun append(event: Event): Boolean

    /**
     * Stores every new event; returns how many were new. Stores that persist must do this all or
     * nothing (one transaction): restore and merge rely on a failure leaving the log unchanged.
     */
    fun appendAll(events: Iterable<Event>): Int = events.count { append(it) }

    /** Every event, in canonical [Event.ORDER]. */
    fun all(): List<Event>

    fun contains(id: EventId): Boolean

    fun count(): Long

    /** The highest counter stored per device, without the legacy pseudo-device. */
    fun heads(): Map<String, Long>

    /** The newest timestamp stored, or null when the store is empty. */
    fun maxTimestamp(): Long?

    /**
     * Deletes the events with these ids; returns how many were there. Only "clear listening
     * history" deletes anything (see [removeHistoryBefore]); the log is otherwise append-only.
     */
    fun removeAll(ids: Collection<EventId>): Int =
        throw UnsupportedOperationException("${this::class.simpleName} cannot delete events")

    /** Deletes the listening events ([EventBody.isHistory]) older than [before]; returns how many. */
    fun removeHistoryBefore(before: Long): Int =
        removeAll(all().filter { EventLog.isCleared(it, before) }.map { it.id })

    /** The newest [EventBody.HistoryClear] point stored, or null. */
    fun clearedBefore(): Long? = EventLog.clearedBefore(all())

    /** Events a peer with version vector [heads] has not seen. */
    fun after(heads: Map<String, Long>): List<Event> = EventLog.after(all(), heads)
}
