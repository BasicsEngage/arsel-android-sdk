package sa.arsel.core.internal

import sa.arsel.core.log.ArselLog
import sa.arsel.core.model.AuthMode
import sa.arsel.core.model.QueuedRequest
import sa.arsel.core.net.RequestQueue
import sa.arsel.core.state.StateManager
import sa.arsel.core.store.ArselStore
import java.util.UUID

/**
 * The events API. Separate from [PushController] on purpose: it targets a different route with a
 * different credential, and it works with no push token, no device secret and no notification
 * permission. Nothing here may depend on the device being registered for push.
 *
 * Events ride the same durable [RequestQueue] as engagements, so they inherit process-death survival
 * and WorkManager's backoff for free.
 *
 * One request per event, even though `/v1/events/send` also takes a batch: each request carries its
 * own persisted idempotency key, and the queue already coalesces the *delivery* — a burst of tracks
 * costs one drain, not one wake-up each.
 */
internal class EventController(
    private val state: StateManager,
    private val store: ArselStore,
    /** [RequestQueue.enqueue]. Taken as a function so the JVM tests need no Android Context. */
    private val enqueue: (QueuedRequest) -> Unit,
    private val log: ArselLog,
    /**
     * Notified after an event is durably queued. This is the only place that sees every event
     * exactly once — downstream of the blank and reserved-prefix rejects, downstream of the trim,
     * and never on a retry, because retries live entirely in the drain re-reading the store.
     */
    private val onEvent: ((String, Map<String, Any?>, Long) -> Unit)? = null,
) {
    fun track(
        name: String,
        properties: Map<String, Any?> = emptyMap(),
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            log.w("track() called with a blank event name — ignoring")
            return
        }
        if (trimmed.startsWith(EventBodies.RESERVED_PREFIX)) {
            log.w("'${EventBodies.RESERVED_PREFIX}' is reserved for the SDK — ignoring '$trimmed'")
            return
        }
        enqueue(trimmed, properties, timestampMs)
    }

    /** SDK-emitted events, exempt from the reserved-prefix check that guards [track]. */
    fun trackReserved(
        name: String,
        properties: Map<String, Any?> = emptyMap(),
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        enqueue(name, properties, timestampMs)
    }

    private fun enqueue(
        name: String,
        properties: Map<String, Any?>,
        timestampMs: Long,
    ) {
        val body =
            EventBodies.event(
                name = name,
                properties = properties,
                anonymousId = state.anonymousId,
                externalId = store.externalId,
                email = store.identifiedEmail,
                phoneNumber = store.identifiedPhone,
                timestampMs = timestampMs,
            )

        // No dedupe key: every event is its own fact. A shared key would let a later event evict an
        // earlier one that is still the only copy of something the customer will bill on.
        enqueue(
            QueuedRequest(
                id = UUID.randomUUID().toString(),
                path = ArselStore.EVENTS_PATH,
                body = body.toString(),
                dedupeKey = null,
                authMode = AuthMode.CLIENT_KEY,
                // Enqueue time, never the event's own timestamp: the drain's age policy measures
                // queue residence, and a backdated session_end would otherwise arrive pre-expired.
                createdAtMs = System.currentTimeMillis(),
            ),
        )
        log.d("event '$name' enqueued")
        // Last: an observer must never see an event that failed to queue. Failures here are the
        // observer's own problem and must not fail the enqueue that already succeeded.
        onEvent?.let { notify ->
            runCatching { notify(name, properties, timestampMs) }
                .onFailure { log.w("event observer failed", it) }
        }
    }
}
