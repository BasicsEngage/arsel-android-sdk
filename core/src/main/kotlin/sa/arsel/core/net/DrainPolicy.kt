package sa.arsel.core.net

/**
 * The two "give up" rules of the queue drain, kept apart from [PushSyncWorker] so they can be
 * asserted without instantiating a WorkManager worker.
 *
 * Both bounds exist because the queue is durable: without them a device that was offline for a
 * fortnight would, on reconnect, replay a fortnight of stale engagement into the analytics
 * pipeline, and a permanently undeliverable queue would hold a wakeup slot forever.
 */
internal object DrainPolicy {
    /**
     * Requests older than this are dropped unsent. Well beyond any realistic offline stretch, and
     * short enough that what does arrive is still worth bucketing.
     */
    const val MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

    /**
     * WorkManager's exponential backoff reaches multi-hour waits long before this, so a chain this
     * deep is a queue nothing can deliver. The requests stay on disk — only the retry chain is
     * abandoned, and the next enqueue schedules a fresh drain.
     */
    const val MAX_RUN_ATTEMPTS: Int = 8

    fun isExpired(
        createdAtMs: Long,
        nowMs: Long,
    ): Boolean = nowMs - createdAtMs > MAX_AGE_MS

    fun hasExhaustedAttempts(runAttemptCount: Int): Boolean = runAttemptCount >= MAX_RUN_ATTEMPTS
}
