package sa.arsel.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import sa.arsel.core.log.ArselLog
import sa.arsel.core.log.LogLevel
import sa.arsel.core.model.AuthMode
import sa.arsel.core.model.QueuedRequest
import sa.arsel.core.store.ArselStore
import sa.arsel.core.testing.FakeSharedPreferences

/**
 * The drain's 2xx bookkeeping. The dangerous half is what it must NOT do: `/v1/events/send` acks
 * with `{id, status: "accepted"}`, and reading that as a registration response would overwrite the
 * subscription status on every delivered event.
 */
class PushSyncWorkerTest {
    private lateinit var store: ArselStore
    private val log = ArselLog(LogLevel.NONE)

    @Before
    fun setUp() {
        store = ArselStore(FakeSharedPreferences())
    }

    private fun request(
        authMode: AuthMode,
        commitHash: String? = null,
    ) = QueuedRequest(
        id = "r1",
        path = if (authMode == AuthMode.CLIENT_KEY) ArselStore.EVENTS_PATH else "/api/v1/orgs/pub_1/push/subscriptions",
        body = "{}",
        dedupeKey = null,
        authMode = authMode,
        createdAtMs = 0L,
        commitHash = commitHash,
    )

    @Test
    fun `a registration 2xx captures the secret, subscription id and status`() {
        PushSyncWorker.applyConfirmed(
            store,
            request(AuthMode.NONE, commitHash = "hash-abc"),
            """{"subscriptionId":"sub-1","status":"ACTIVE","deviceSecret":"dsk_secret"}""",
            log,
        )

        assertEquals("dsk_secret", store.deviceSecret)
        assertEquals("sub-1", store.subscriptionId)
        assertEquals("ACTIVE", store.subscriptionStatus)
        assertEquals("hash-abc", store.lastRegisteredHash)
    }

    @Test
    fun `an events-API 2xx never clobbers the subscription status`() {
        store.subscriptionStatus = "ACTIVE"
        store.subscriptionId = "sub-1"

        PushSyncWorker.applyConfirmed(
            store,
            request(AuthMode.CLIENT_KEY),
            """{"id":"evt-1","status":"accepted"}""",
            log,
        )

        assertEquals("ACTIVE", store.subscriptionStatus)
        assertEquals("sub-1", store.subscriptionId)
    }

    @Test
    fun `a device-authed 2xx does not read registration fields from the body`() {
        PushSyncWorker.applyConfirmed(
            store,
            request(AuthMode.DEVICE),
            """{"status":"ok","deviceSecret":"dsk_forged"}""",
            log,
        )

        assertNull(store.deviceSecret)
        assertNull(store.subscriptionStatus)
    }
}
