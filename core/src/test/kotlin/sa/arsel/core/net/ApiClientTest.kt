package sa.arsel.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Retry classification and `Retry-After` parsing.
 *
 * This is the decision that separates a device that eventually registers from one that gives up
 * forever, and both halves are pure — the socket work around them is not what breaks.
 */
class ApiClientTest {
    // --- success --------------------------------------------------------------

    @Test
    fun `every 2xx the push endpoints actually return is a success`() {
        // register -> 200 (idempotent re-register, so not 201), unsubscribe/state -> 200,
        // events -> 202 (enqueue-then-ack).
        assertEquals(ApiClient.Result.SUCCESS, ApiClient.classify(200, authenticated = false))
        assertEquals(ApiClient.Result.SUCCESS, ApiClient.classify(202, authenticated = true))
        assertEquals(ApiClient.Result.SUCCESS, ApiClient.classify(299, authenticated = true))
    }

    // --- retryable ------------------------------------------------------------

    @Test
    fun `server errors and throttling are retryable`() {
        assertEquals(ApiClient.Result.RETRYABLE, ApiClient.classify(408, authenticated = true))
        assertEquals(ApiClient.Result.RETRYABLE, ApiClient.classify(429, authenticated = true))
        assertEquals(ApiClient.Result.RETRYABLE, ApiClient.classify(500, authenticated = false))
        assertEquals(ApiClient.Result.RETRYABLE, ApiClient.classify(503, authenticated = false))
        assertEquals(ApiClient.Result.RETRYABLE, ApiClient.classify(599, authenticated = false))
    }

    @Test
    fun `a 404 on the unauthenticated register route is retryable, not fatal`() {
        // The resolver answers a bare 404 both for an unknown push key AND for an org whose push
        // channel is not switched on yet. The second is ordinary onboarding: a device that gave up
        // permanently here would never register once the customer finishes setup.
        assertEquals(ApiClient.Result.RETRYABLE, ApiClient.classify(404, authenticated = false))
    }

    @Test
    fun `a 429 on an authenticated route stays retryable rather than reading as a bad secret`() {
        assertEquals(ApiClient.Result.RETRYABLE, ApiClient.classify(429, authenticated = true))
    }

    // --- reauth ---------------------------------------------------------------

    @Test
    fun `rejected device auth forces a re-registration instead of an endless retry`() {
        // The device-auth failure is deliberately opaque server-side, so all three spellings mean
        // the same thing: this secret is no longer accepted. Only a fresh register mints another.
        assertEquals(ApiClient.Result.REAUTH, ApiClient.classify(401, authenticated = true))
        assertEquals(ApiClient.Result.REAUTH, ApiClient.classify(403, authenticated = true))
        assertEquals(ApiClient.Result.REAUTH, ApiClient.classify(404, authenticated = true))
    }

    // --- permanent ------------------------------------------------------------

    @Test
    fun `a rejected body is permanent so it cannot occupy the queue forever`() {
        // forbidNonWhitelisted turns a stray field into a 400. Retrying byte-identical JSON that
        // the DTO already refused only burns wakeups.
        assertEquals(ApiClient.Result.PERMANENT, ApiClient.classify(400, authenticated = false))
        assertEquals(ApiClient.Result.PERMANENT, ApiClient.classify(422, authenticated = true))
    }

    @Test
    fun `401 and 403 on an unauthenticated request are permanent`() {
        // Nothing to re-authenticate with: register carries no secret by construction.
        assertEquals(ApiClient.Result.PERMANENT, ApiClient.classify(401, authenticated = false))
        assertEquals(ApiClient.Result.PERMANENT, ApiClient.classify(403, authenticated = false))
    }

    // --- Retry-After ----------------------------------------------------------

    @Test
    fun `Retry-After delta-seconds is read as milliseconds`() {
        assertEquals(120_000L, ApiClient.parseRetryAfterMs("120", NOW_MS))
    }

    @Test
    fun `Retry-After accepts an HTTP-date and yields the remaining wait`() {
        // RFC 7231 allows either form and servers in the wild send both.
        assertEquals(60_000L, ApiClient.parseRetryAfterMs("Fri, 07 Aug 2026 12:01:00 GMT", NOW_MS))
    }

    @Test
    fun `a Retry-After already in the past is clamped to zero, never negative`() {
        assertEquals(0L, ApiClient.parseRetryAfterMs("Fri, 07 Aug 2026 11:00:00 GMT", NOW_MS))
        assertEquals(0L, ApiClient.parseRetryAfterMs("-30", NOW_MS))
    }

    @Test
    fun `an absent or unparseable Retry-After is simply no instruction`() {
        assertNull(ApiClient.parseRetryAfterMs(null, NOW_MS))
        assertNull(ApiClient.parseRetryAfterMs("   ", NOW_MS))
        assertNull(ApiClient.parseRetryAfterMs("soon", NOW_MS))
    }

    private companion object {
        /** 2026-08-07T12:00:00Z. */
        const val NOW_MS = 1_786_104_000_000L
    }
}
