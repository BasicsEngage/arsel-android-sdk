package sa.arsel.core.net

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject
import sa.arsel.core.Registry
import sa.arsel.core.log.ArselLog
import sa.arsel.core.model.AuthMode
import sa.arsel.core.model.QueuedRequest
import sa.arsel.core.store.ArselStore

/**
 * Drains the persisted request queue. Instantiated by WorkManager (so it runs even after the app
 * process was killed) and is independent of Registry for its own work — it rebuilds the [ApiClient]
 * from the config persisted in [ArselStore]. WorkManager's backoff handles retry timing; we only
 * signal retry/success.
 */
internal class PushSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = ArselStore(applicationContext)
        val baseUrl = store.baseUrl ?: return Result.success() // SDK not configured yet
        val log = ArselLog(store.logLevel)

        if (DrainPolicy.hasExhaustedAttempts(runAttemptCount)) {
            log.w("drain abandoned after $runAttemptCount attempts; queue kept for the next enqueue")
            return Result.failure()
        }

        val http = ApiClient(baseUrl, store.networkTimeoutMs, log)
        val requests = store.getRequests()
        if (requests.isEmpty()) return Result.success()

        val now = System.currentTimeMillis()
        // Ids to delete, never "what remains": anything enqueued while we were on the network must
        // survive the drain (see ArselStore.removeRequests).
        val settled = mutableSetOf<String>()
        var retry = false

        for (req in requests) {
            if (DrainPolicy.isExpired(req.createdAtMs, now)) {
                log.w("dropping request older than ${DrainPolicy.MAX_AGE_MS}ms: ${req.path}")
                settled.add(req.id)
                continue
            }

            val headers =
                when (req.authMode) {
                    AuthMode.NONE -> emptyMap()

                    AuthMode.DEVICE -> {
                        val secret = store.deviceSecret
                        if (secret == null) {
                            // Nothing to authenticate with yet; only a registration mints one. Keep it
                            // queued and make sure a registration is on its way, or this waits forever.
                            requestReregistration(log)
                            retry = true
                            continue
                        }
                        mapOf(ApiClient.HEADER_DEVICE_AUTH to secret)
                    }

                    // The publishable client key, known from config alone. Deliberately does NOT wait
                    // on registration: events must flow on a handset that never granted notification
                    // permission and so has no device secret at all.
                    AuthMode.CLIENT_KEY -> {
                        val clientKey = store.clientKey
                        if (clientKey.isNullOrBlank()) {
                            log.w("no client key configured — dropping event ${req.path}")
                            settled.add(req.id)
                            continue
                        }
                        mapOf(
                            ApiClient.HEADER_AUTHORIZATION to "${ApiClient.BEARER_PREFIX}$clientKey",
                            // The queue id doubles as the idempotency key: minted once at enqueue,
                            // persisted, so identical across every retry — closing the duplicate
                            // window between a server 2xx and the dequeue that never happened.
                            ApiClient.HEADER_IDEMPOTENCY_KEY to req.id,
                        )
                    }
                }

            val response = http.post(req.path, req.body, headers, req.requiresDeviceAuth)
            recordOutcome(store, req.path, response)
            when (response.result) {
                ApiClient.Result.SUCCESS -> {
                    applyConfirmed(store, req, response.body, log)
                    settled.add(req.id)
                }
                ApiClient.Result.PERMANENT -> settled.add(req.id)
                ApiClient.Result.REAUTH -> {
                    // The secret is no longer accepted. Drop it and invalidate the device
                    // fingerprint so the next registration mints a fresh one.
                    log.w("device auth rejected — clearing secret and forcing re-registration")
                    store.deviceSecret = null
                    store.lastRegisteredHash = null
                    requestReregistration(log)
                    retry = true
                }
                ApiClient.Result.RETRYABLE -> {
                    retry = true
                    // Stop the drain outright: WorkManager's backoff carries the wait, and
                    // continuing would let a later request overtake this one — the queue's
                    // oldest-first ordering guarantee, shared with the web SDK.
                    break
                }
            }
        }

        store.removeRequests(settled)
        return if (retry) Result.retry() else Result.success()
    }

    /**
     * Diagnostics only — `Arsel.diagnostics()` reads this, nothing branches on it. Written for
     * every outcome including success, because "the last call succeeded" is exactly the fact that
     * settles a "push isn't arriving" report in the field.
     */
    private fun recordOutcome(
        store: ArselStore,
        path: String,
        response: ApiClient.Response,
    ) {
        store.lastResponseCode = response.code
        store.lastResponsePath = path
        store.lastResponseAtMs = System.currentTimeMillis()
    }

    /**
     * Best-effort nudge. The drain often runs in a process where the host has already called
     * `initialize()`; when it has not, the next `initialize()` re-registers anyway because the
     * fingerprint was cleared.
     */
    private fun requestReregistration(log: ArselLog) {
        if (!Registry.initialized) return
        runCatching { Registry.controller.registerNow() }
            .onFailure { log.w("could not schedule re-registration", it) }
    }

    /** Static so the 2xx bookkeeping is assertable from a JVM test without a WorkManager worker. */
    internal companion object {
        /**
         * Registration bookkeeping is committed here and nowhere else. Committing it at enqueue
         * would leave the SDK believing it had registered after a request that never reached the
         * server, and every later change would then be compared against a fingerprint the backend
         * never saw.
         */
        fun applyConfirmed(
            store: ArselStore,
            req: QueuedRequest,
            body: String?,
            log: ArselLog,
        ) {
            req.commitHash?.let { store.lastRegisteredHash = it }
            // Only ever set: the binding lives on the backend, and a later anonymous
            // re-registration does not undo it. Clearing it is [ArselStore.clearIdentity]'s alone.
            // Registration is the only unauthenticated request, so AuthMode.NONE is what keys the
            // response capture. Reading every 2xx body would let an events-API ack — which also
            // carries a `status` field, spelling "accepted" — clobber the subscription status.
            if (req.authMode == AuthMode.NONE) captureRegistration(store, body, log)
        }

        /**
         * Registration returns `deviceSecret` exactly once, and only on the call that minted it —
         * missing it strands the installation without authentication forever.
         */
        private fun captureRegistration(
            store: ArselStore,
            body: String?,
            log: ArselLog,
        ) {
            if (body.isNullOrBlank()) return
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return
            json.readString(FIELD_SUBSCRIPTION_ID)?.let { store.subscriptionId = it }
            json.readString(FIELD_STATUS)?.let { store.subscriptionStatus = it }
            json.readString(FIELD_DEVICE_SECRET)?.let {
                store.deviceSecret = it
                log.d("device secret stored")
            }
        }

        /** `optString` renders a JSON null as the literal "null" on Android, so the check is required. */
        private fun JSONObject.readString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

        private const val FIELD_DEVICE_SECRET = "deviceSecret"
        private const val FIELD_SUBSCRIPTION_ID = "subscriptionId"
        private const val FIELD_STATUS = "status"
    }
}
