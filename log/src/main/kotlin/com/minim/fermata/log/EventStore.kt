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

    /**
     * The highest counter per device among the events in the store now, without the legacy
     * pseudo-device: what a backup's manifest records. Unlike [heads], counters kept only so they
     * are never handed out again (from deleted or rolled-back events) are left out.
     */
    fun loggedHeads(): Map<String, Long> = EventLog.heads(all())

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

    /**
     * Runs [block] in one store transaction: everything [block] writes to the store (and, for a
     * store kept inside an app's own database, to the app's tables) commits together or not at
     * all. Nested calls join the outer transaction. The default just runs [block], for stores that
     * cannot undo a write; [InMemoryEventStore] puts itself back if [block] throws.
     */
    fun <T> inTransaction(block: () -> T): T = block()

    /** Events a peer with version vector [heads] has not seen. */
    fun after(heads: Map<String, Long>): List<Event> = EventLog.after(all(), heads)
}
