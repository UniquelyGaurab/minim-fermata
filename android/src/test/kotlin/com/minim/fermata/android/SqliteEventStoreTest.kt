package com.minim.fermata.android

import androidx.test.core.app.ApplicationProvider
import com.minim.fermata.Fermata
import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventContext
import com.minim.fermata.log.EventId
import com.minim.fermata.log.InMemoryEventStore
import com.minim.fermata.log.Subject
import com.minim.fermata.log.TrackRef
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SqliteEventStoreTest {
    private val store = SqliteEventStore(ApplicationProvider.getApplicationContext(), name = null)

    private fun event(device: String, counter: Long, ts: Long, id: String = "t$counter") =
        Event(EventId(device, counter), EventContext.at(ts), EventBody.Play(TrackRef(id, "Song", "Artist", 200_000), 60_000))

    @After
    fun close() = store.close()

    @Test
    fun behavesLikeTheInMemoryStore() {
        val events = listOf(
            event("b", 1, 1_000),
            event("a", 1, 1_000),
            event("a", 2, 500),
            Event(EventId.legacy("x"), EventContext.at(10), EventBody.Like(Subject.Artist("Queen"))),
        )
        val reference = InMemoryEventStore(events)
        assertNull(store.maxTimestamp())
        assertEquals(4, store.appendAll(events))
        assertFalse(store.append(events[0]))
        assertEquals(0, store.appendAll(events))
        assertEquals(reference.all(), store.all())
        assertEquals(reference.heads(), store.heads())
        assertEquals(4L, store.count())
        assertEquals(1_000L, store.maxTimestamp())
        assertTrue(store.contains(EventId("a", 2)))
        assertEquals(reference.after(mapOf("a" to 1L)), store.after(mapOf("a" to 1L)))
        assertEquals(reference.after(emptyMap()), store.after(emptyMap()))
    }

    @Test
    fun drivesFermata() {
        val fermata = Fermata(store, "phone")
        fermata.record(EventBody.Like(Subject.Track(TrackRef("A"))))
        fermata.record(EventBody.PlaylistCreate("p", "Mix"))
        fermata.record(EventBody.PlaylistAdd("p", TrackRef("A")))
        assertTrue(fermata.library().isLiked("A"))
        assertEquals(listOf("A"), Fermata(store, "phone").library().playlist("p")!!.tracks.map { it.id })
        assertEquals(mapOf("phone" to 3L), store.heads())
    }
}
