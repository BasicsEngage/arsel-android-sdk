package sa.arsel.core.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sa.arsel.core.log.ArselLog
import sa.arsel.core.model.EngagementRecord

/**
 * Coalesces engagement signals into the batch envelope `POST …/push/engagements` expects.
 *
 * A campaign send raises DELIVERED and then DISPLAYED for the same message milliseconds apart, and
 * one HTTP request per signal doubles the request count for no information. Taps and dismissals are
 * exempt ([EngagementRecord.isUrgent]): they carry user-visible latency and are raised from components
 * that may not outlive the coalescing window.
 */
internal class EngagementBatcher(
    private val scope: CoroutineScope,
    private val log: ArselLog,
    private val onBatch: (List<EngagementRecord>) -> Unit,
) {
    private val lock = Any()
    private val pending = mutableListOf<EngagementRecord>()
    private var flushJob: Job? = null

    fun add(record: EngagementRecord) {
        val batch =
            synchronized(lock) {
                pending += record
                if (record.isUrgent || pending.size >= MAX_BATCH) {
                    takeLocked()
                } else {
                    scheduleLocked()
                    null
                }
            }
        batch?.let(::deliver)
    }

    fun flushNow() {
        synchronized(lock) { takeLocked() }?.let(::deliver)
    }

    private fun takeLocked(): List<EngagementRecord>? {
        flushJob?.cancel()
        flushJob = null
        if (pending.isEmpty()) return null
        val batch = pending.toList()
        pending.clear()
        return batch
    }

    private fun scheduleLocked() {
        if (flushJob?.isActive == true) return
        flushJob =
            scope.launch {
                delay(COALESCE_DELAY_MS)
                // Clears its own handle rather than going through takeLocked(): cancelling the job we
                // are currently running in is legal but reads as a bug every time someone re-reads it.
                val batch =
                    synchronized(lock) {
                        flushJob = null
                        pending.toList().also { pending.clear() }
                    }
                if (batch.isNotEmpty()) deliver(batch)
            }
    }

    private fun deliver(batch: List<EngagementRecord>) {
        runCatching { onBatch(batch) }
            .onFailure { log.w("engagement batch could not be enqueued (${batch.size} events)", it) }
    }

    internal companion object {
        /** The backend's batch ceiling, shared with the body builder that has to honour it. */
        const val MAX_BATCH = PushBodies.ENGAGEMENT_BATCH_MAX

        /**
         * Long enough to absorb the DELIVERED/DISPLAYED pair and a redelivery burst, far short of
         * the ~20s Android allows a message-handling service before it kills the process.
         */
        const val COALESCE_DELAY_MS = 1_500L
    }
}
