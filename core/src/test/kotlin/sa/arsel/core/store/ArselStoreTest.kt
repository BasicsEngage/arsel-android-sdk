package sa.arsel.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sa.arsel.core.model.AuthMode
import sa.arsel.core.model.QueuedRequest
import sa.arsel.core.testing.FakeSharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The durable state the SDK cannot re-derive: the installation identity, the one-time device
 * secret, the message claim set and the request queue.
 *
 * Driven against the real [ArselStore] over an in-memory [FakeSharedPreferences] — the logic under
 * test is the read-modify-write sequencing, which a mocked store would define away.
 */
class ArselStoreTest {
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: ArselStore

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        store = ArselStore(prefs)
    }

    // --- device identity ------------------------------------------------------

    @Test
    fun `installation id is minted once and then stable`() {
        val first = store.installationId

        assertTrue(first.isNotBlank())
        assertEquals(first, store.installationId)
        // A second store over the same file is the cold-restart case.
        assertEquals(first, ArselStore(prefs).installationId)
    }

    @Test
    fun `installation id is the same value under concurrent first reads`() {
        // The getter mints on first read. Unguarded, a burst of binder threads (an FCM delivery and
        // a host call on the main thread) would hand two ids to two concurrent registrations.
        val threadCount = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        repeat(threadCount) {
            Thread {
                start.await()
                seen += store.installationId
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        assertEquals(1, seen.size)
    }

    @Test
    fun `device secret round-trips and can be cleared on a rejected auth`() {
        store.deviceSecret = "dsk_secret"
        assertEquals("dsk_secret", store.deviceSecret)

        store.deviceSecret = null
        assertNull(store.deviceSecret)
    }

    // --- request queue --------------------------------------------------------

    @Test
    fun `a dedupe key replaces the pending request that shares it`() {
        store.addRequest(request(id = "r1", dedupeKey = "register"))
        store.addRequest(request(id = "r2", dedupeKey = "register"))

        assertEquals(listOf("r2"), store.getRequests().map { it.id })
    }

    @Test
    fun `requests without a dedupe key accumulate`() {
        // Engagement batches carry no key on purpose: a shared one would let a later batch evict an
        // earlier one holding the only copy of those signals.
        store.addRequest(request(id = "b1", dedupeKey = null))
        store.addRequest(request(id = "b2", dedupeKey = null))
        store.addRequest(request(id = "b3", dedupeKey = null))

        assertEquals(listOf("b1", "b2", "b3"), store.getRequests().map { it.id })
    }

    @Test
    fun `the queue is capped and drops the oldest requests first`() {
        repeat(ArselStore.MAX_QUEUE + 5) { index -> store.addRequest(request(id = "r$index")) }

        val ids = store.getRequests().map { it.id }
        assertEquals(ArselStore.MAX_QUEUE, ids.size)
        assertEquals("r${ArselStore.MAX_QUEUE + 4}", ids.last())
        assertFalse(ids.contains("r0"))
    }

    @Test
    fun `removeRequests deletes only the ids it was given`() {
        store.addRequest(request(id = "r1"))
        store.addRequest(request(id = "r2"))
        store.addRequest(request(id = "r3"))

        store.removeRequests(setOf("r1", "r3"))

        assertEquals(listOf("r2"), store.getRequests().map { it.id })
    }

    @Test
    fun `a request enqueued during a drain survives that drain`() {
        // The exact interleave a whole-list write-back loses: the drain read the queue before the
        // engagement existed, so "what remains" would not contain it.
        store.addRequest(request(id = "in-flight"))
        val drained = store.getRequests().map { it.id }.toSet()

        store.addRequest(request(id = "enqueued-mid-drain", dedupeKey = null))
        store.removeRequests(drained)

        assertEquals(listOf("enqueued-mid-drain"), store.getRequests().map { it.id })
    }

    @Test
    fun `concurrent appends are not lost against a concurrent removal`() {
        val appended = 32
        store.addRequest(request(id = "drained"))
        val start = CountDownLatch(1)
        val done = CountDownLatch(appended + 1)

        repeat(appended) { index ->
            Thread {
                start.await()
                store.addRequest(request(id = "a$index", dedupeKey = null))
                done.countDown()
            }.start()
        }
        Thread {
            start.await()
            store.removeRequests(setOf("drained"))
            done.countDown()
        }.start()
        start.countDown()
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val ids = store.getRequests().map { it.id }
        assertEquals(appended, ids.size)
        assertFalse(ids.contains("drained"))
    }

    @Test
    fun `queued requests survive a process restart with their deferred bookkeeping`() {
        store.addRequest(
            request(id = "reg", dedupeKey = "register").copy(
                authMode = AuthMode.NONE,
                commitHash = "hash-abc",
            ),
        )

        val restored = ArselStore(prefs).getRequests().single()

        assertEquals("reg", restored.id)
        assertEquals("hash-abc", restored.commitHash)
        assertFalse(restored.requiresDeviceAuth)
        assertEquals(NOW_MS, restored.createdAtMs)
    }

    // --- message claims -------------------------------------------------------

    @Test
    fun `a message is claimed once and a redelivery is refused`() {
        assertTrue(store.claimMessage(MESSAGE_ID, NOW_MS))
        assertFalse(store.claimMessage(MESSAGE_ID, NOW_MS + 1))
    }

    @Test
    fun `a confirmed claim blocks redeliveries well past the pending window`() {
        store.claimMessage(MESSAGE_ID, NOW_MS)
        store.confirmMessage(MESSAGE_ID, NOW_MS)

        assertFalse(store.claimMessage(MESSAGE_ID, NOW_MS + ArselStore.PENDING_TTL_MS * 10))
    }

    @Test
    fun `a pending claim whose render died is reclaimable after the pending window`() {
        // The render process was killed mid-notify. FCM redelivers; refusing it would drop the
        // notification permanently.
        store.claimMessage(MESSAGE_ID, NOW_MS)

        assertFalse(store.claimMessage(MESSAGE_ID, NOW_MS + ArselStore.PENDING_TTL_MS))
        assertTrue(store.claimMessage(MESSAGE_ID, NOW_MS + ArselStore.PENDING_TTL_MS + 1))
    }

    @Test
    fun `releasing a claim lets the next redelivery render`() {
        store.claimMessage(MESSAGE_ID, NOW_MS)
        store.releaseMessage(MESSAGE_ID, NOW_MS)

        assertTrue(store.claimMessage(MESSAGE_ID, NOW_MS + 1))
    }

    @Test
    fun `confirming a message that was never claimed records nothing`() {
        store.confirmMessage(MESSAGE_ID, NOW_MS)

        assertTrue(store.claimMessage(MESSAGE_ID, NOW_MS + 1))
    }

    @Test
    fun `claims expire so a far-future redelivery is rendered rather than swallowed`() {
        store.claimMessage(MESSAGE_ID, NOW_MS)
        store.confirmMessage(MESSAGE_ID, NOW_MS)

        assertFalse(store.claimMessage(MESSAGE_ID, NOW_MS + ArselStore.SEEN_TTL_MS - 1))
        assertTrue(store.claimMessage(MESSAGE_ID, NOW_MS + ArselStore.SEEN_TTL_MS))
    }

    @Test
    fun `a message id containing the field separator round-trips`() {
        // Entries are encoded `id:timestamp:state` and parsed right-to-left precisely because a
        // backend message id is not guaranteed to be separator-free.
        val awkwardId = "campaign:42:contact:7"
        assertTrue(store.claimMessage(awkwardId, NOW_MS))
        store.confirmMessage(awkwardId, NOW_MS)

        assertFalse(store.claimMessage(awkwardId, NOW_MS + 1))
        assertTrue(store.claimMessage("campaign:42:contact:8", NOW_MS + 1))
    }

    @Test
    fun `the claim set is capped and forgets the oldest message first`() {
        repeat(ArselStore.MAX_SEEN + 1) { index ->
            store.claimMessage("m$index", NOW_MS)
            store.confirmMessage("m$index", NOW_MS)
        }

        assertTrue(store.claimMessage("m0", NOW_MS + 1))
        assertFalse(store.claimMessage("m${ArselStore.MAX_SEEN}", NOW_MS + 1))
    }

    // --- lifecycle ------------------------------------------------------------

    @Test
    fun `logout forgets the contact binding but keeps the installation and its secret`() {
        val installationId = store.installationId
        store.deviceSecret = "dsk_secret"
        store.lastRegisteredHash = "hash-abc"
        store.externalId = "u_A"
        val anonymousId = store.anonymousId

        store.clearIdentity()

        // The secret is minted exactly once. A logout that dropped it would leave the device unable
        // to unsubscribe or engagement for the rest of the install's life.
        assertEquals(installationId, store.installationId)
        assertEquals("dsk_secret", store.deviceSecret)
        assertNull(store.lastRegisteredHash)
        assertNull(store.externalId)
        assertNotEquals(anonymousId, store.anonymousId)
    }

    @Test
    fun `a full reset drops the identity and the queue but keeps the persisted config`() {
        store.baseUrl = "https://api.example.com"
        store.clientKey = "pub_live_123"
        val installationId = store.installationId
        store.deviceSecret = "dsk_secret"
        store.subscriptionId = "sub-1"
        store.addRequest(request(id = "r1"))
        store.claimMessage(MESSAGE_ID, NOW_MS)

        store.clearAll()

        assertNotNull(store.installationId)
        assertFalse(installationId == store.installationId)
        assertNull(store.deviceSecret)
        assertNull(store.subscriptionId)
        assertEquals(emptyList<QueuedRequest>(), store.getRequests())
        assertTrue(store.claimMessage(MESSAGE_ID, NOW_MS))
        // The worker rebuilds its API client from these after a cold restart.
        assertEquals("https://api.example.com", store.baseUrl)
        assertEquals("pub_live_123", store.clientKey)
    }

    // --- helpers --------------------------------------------------------------

    private fun request(
        id: String,
        dedupeKey: String? = "dedupe-$id",
    ) = QueuedRequest(
        id = id,
        path = "/api/v1/orgs/pub_live_123/push/subscriptions",
        body = """{"installationId":"$id"}""",
        dedupeKey = dedupeKey,
        authMode = AuthMode.DEVICE,
        createdAtMs = NOW_MS,
    )

    private companion object {
        const val MESSAGE_ID = "0195c7ac-1111-5222-8333-444455556666"

        /** 2026-08-07T12:00:00Z. */
        const val NOW_MS = 1_786_104_000_000L
        const val TIMEOUT_SECONDS = 10L
    }
}
