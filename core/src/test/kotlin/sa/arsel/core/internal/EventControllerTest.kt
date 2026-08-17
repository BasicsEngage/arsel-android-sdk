package sa.arsel.core.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sa.arsel.core.log.ArselLog
import sa.arsel.core.log.LogLevel
import sa.arsel.core.model.AuthMode
import sa.arsel.core.model.QueuedRequest
import sa.arsel.core.state.StateManager
import sa.arsel.core.store.ArselStore
import sa.arsel.core.testing.FakeSharedPreferences

class EventControllerTest {
    private lateinit var store: ArselStore
    private lateinit var queued: MutableList<QueuedRequest>
    private lateinit var controller: EventController

    @Before
    fun setUp() {
        store = ArselStore(FakeSharedPreferences())
        queued = mutableListOf()
        controller =
            EventController(
                StateManager(store),
                store,
                { queued.add(it) },
                ArselLog(LogLevel.NONE),
            )
    }

    private fun bodyOf(index: Int = 0) = JSONObject(queued[index].body)

    @Test
    fun `events authenticate with the client key, not the device secret`() {
        // The whole point of the events API: a handset that never granted notification permission has no
        // device secret, and its events must still be delivered.
        controller.track("product.viewed")

        assertEquals(AuthMode.CLIENT_KEY, queued.single().authMode)
    }

    @Test
    fun `events go to the org-free ingest route`() {
        controller.track("product.viewed")

        assertEquals(ArselStore.EVENTS_PATH, queued.single().path)
    }

    @Test
    fun `events carry no dedupe key`() {
        // A shared key would let a later event evict an earlier one that is still the only copy of
        // something the customer bills on.
        controller.track("a")
        controller.track("b")

        assertTrue(queued.all { it.dedupeKey == null })
        assertEquals(2, queued.size)
    }

    @Test
    fun `a track before identify carries only the anonymous id`() {
        controller.track("product.viewed")

        val body = bodyOf()
        assertEquals(store.anonymousId, body.getString("anonymous_id"))
        assertTrue(body.isNull("external_id") || !body.has("external_id"))
    }

    @Test
    fun `an asserted identity rides every later event`() {
        StateManager(store).setAssertedIdentity("u_1", "a@b.com", null)

        controller.track("product.viewed")

        val body = bodyOf()
        assertEquals("u_1", body.getString("external_id"))
        assertEquals("a@b.com", body.getString("email"))
        // Still present — it is what the backend merges from.
        assertEquals(store.anonymousId, body.getString("anonymous_id"))
    }

    @Test
    fun `the reserved namespace is refused to hosts`() {
        controller.track("${EventBodies.RESERVED_PREFIX}session_start")

        assertTrue(queued.isEmpty())
    }

    @Test
    fun `the SDK can still emit reserved events`() {
        controller.trackReserved(EventBodies.EVENT_SESSION_START)

        assertEquals(EventBodies.EVENT_SESSION_START, bodyOf().getString("event"))
    }

    @Test
    fun `a blank name is refused rather than sent`() {
        controller.track("   ")

        assertTrue(queued.isEmpty())
    }

    @Test
    fun `names are trimmed so a stray space is not a second event`() {
        controller.track("  product.viewed  ")

        assertEquals("product.viewed", bodyOf().getString("event"))
    }

    @Test
    fun `createdAtMs is the enqueue time, never the event's own timestamp`() {
        // A backdated session_end (its timestamp is the backgrounding moment, discovered days
        // later) must not arrive pre-expired against the drain's 7-day age policy.
        val before = System.currentTimeMillis()
        controller.trackReserved(
            EventBodies.EVENT_SESSION_END,
            timestampMs = before - 30L * 24 * 60 * 60 * 1000,
        )
        val after = System.currentTimeMillis()

        val createdAt = queued.single().createdAtMs
        assertTrue("createdAtMs must be enqueue time", createdAt in before..after)
        // The event's own timestamp still rides in the body.
        assertEquals(
            EventBodies.iso8601(before - 30L * 24 * 60 * 60 * 1000),
            bodyOf().getString("timestamp"),
        )
    }

    @Test
    fun `rotating the anonymous id detaches later events from the previous user`() {
        controller.track("before.logout")
        val before = bodyOf().getString("anonymous_id")

        store.clearIdentity()
        controller.track("after.logout")

        val after = bodyOf(1).getString("anonymous_id")
        assertTrue("anonymous id must change on logout", before != after)
        // And the previous user's identifiers must not ride along.
        assertNull(store.externalId)
    }
}
