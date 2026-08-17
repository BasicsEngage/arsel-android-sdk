package sa.arsel.core.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sa.arsel.core.log.ArselLog
import sa.arsel.core.log.LogLevel
import sa.arsel.core.model.EngagementEvent
import sa.arsel.core.model.EngagementRecord

/**
 * Coalescing rules for engagement signals.
 *
 * The batcher is the only thing standing between one campaign send and two HTTP requests per
 * message, but it must not apply to the signals raised from components that may not outlive the
 * window — a trampoline Activity and a broadcast receiver both die within milliseconds.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle(), for the coalescing window
class EngagementBatcherTest {
    @Test
    fun `delivery signals coalesce into one batch`() =
        runTest {
            val batches = mutableListOf<List<EngagementRecord>>()
            val batcher = batcher(this, batches)

            batcher.add(record(EngagementEvent.DELIVERED))
            batcher.add(record(EngagementEvent.DISPLAYED))
            assertEquals("nothing may be sent before the window closes", 0, batches.size)

            advanceUntilIdle()

            assertEquals(1, batches.size)
            assertEquals(
                listOf(EngagementEvent.DELIVERED, EngagementEvent.DISPLAYED),
                batches.single().map { it.event },
            )
        }

    @Test
    fun `a tap is sent immediately rather than waiting out the window`() =
        runTest {
            val batches = mutableListOf<List<EngagementRecord>>()
            val batcher = batcher(this, batches)

            batcher.add(record(EngagementEvent.OPENED))

            assertEquals(1, batches.size)
        }

    @Test
    fun `a dismissal is sent immediately`() =
        runTest {
            val batches = mutableListOf<List<EngagementRecord>>()
            val batcher = batcher(this, batches)

            batcher.add(record(EngagementEvent.DISMISSED))

            assertEquals(1, batches.size)
        }

    @Test
    fun `an urgent signal carries the coalescing signals waiting behind it`() =
        runTest {
            val batches = mutableListOf<List<EngagementRecord>>()
            val batcher = batcher(this, batches)

            batcher.add(record(EngagementEvent.DELIVERED))
            batcher.add(record(EngagementEvent.CLICKED))

            assertEquals(1, batches.size)
            assertEquals(2, batches.single().size)

            // …and the cancelled window must not then fire a second, empty batch.
            advanceUntilIdle()
            assertEquals(1, batches.size)
        }

    @Test
    fun `a batch never exceeds the backend's ceiling`() =
        runTest {
            val batches = mutableListOf<List<EngagementRecord>>()
            val batcher = batcher(this, batches)

            repeat(EngagementBatcher.MAX_BATCH) { batcher.add(record(EngagementEvent.DELIVERED)) }

            assertEquals(1, batches.size)
            assertEquals(EngagementBatcher.MAX_BATCH, batches.single().size)
            assertEquals(PushBodies.ENGAGEMENT_BATCH_MAX, EngagementBatcher.MAX_BATCH)
        }

    @Test
    fun `an overflow past the ceiling is carried in the next batch`() =
        runTest {
            val batches = mutableListOf<List<EngagementRecord>>()
            val batcher = batcher(this, batches)

            repeat(EngagementBatcher.MAX_BATCH + 2) { batcher.add(record(EngagementEvent.DELIVERED)) }
            advanceUntilIdle()

            assertEquals(2, batches.size)
            assertEquals(EngagementBatcher.MAX_BATCH, batches[0].size)
            assertEquals(2, batches[1].size)
        }

    @Test
    fun `flushNow sends what is waiting without advancing the clock`() =
        runTest {
            val batches = mutableListOf<List<EngagementRecord>>()
            val batcher = batcher(this, batches)

            batcher.add(record(EngagementEvent.DELIVERED))
            batcher.flushNow()

            assertEquals(1, batches.size)
        }

    @Test
    fun `flushNow on an empty batcher sends nothing`() =
        runTest {
            val batches = mutableListOf<List<EngagementRecord>>()
            val batcher = batcher(this, batches)

            batcher.flushNow()

            assertTrue(batches.isEmpty())
        }

    @Test
    fun `a batch that cannot be enqueued never reaches the caller`() =
        runTest {
            // add() is called from a notification tap on the main thread. Anything thrown here would
            // crash the host app inside a trampoline Activity.
            val batcher = EngagementBatcher(this, ArselLog(LogLevel.NONE)) { error("queue is full") }

            batcher.add(record(EngagementEvent.OPENED))
        }

    // --- helpers --------------------------------------------------------------

    private fun batcher(
        scope: CoroutineScope,
        sink: MutableList<List<EngagementRecord>>,
    ) = EngagementBatcher(scope, ArselLog(LogLevel.NONE)) { sink += it }

    private fun record(event: EngagementEvent) =
        EngagementRecord(
            messageId = MESSAGE_ID,
            event = event,
            timestampMs = FIXED_TIME_MS,
            signature = null,
            signatureKeyId = null,
            actionId = null,
            deepLink = null,
            suppressionReason = null,
        )

    private companion object {
        const val MESSAGE_ID = "0195c7ac-1111-5222-8333-444455556666"

        /** 2026-08-07T12:00:00Z. */
        const val FIXED_TIME_MS = 1_786_104_000_000L
    }
}
