package sa.arsel.core.notification

import sa.arsel.core.model.SuppressionReason

/**
 * What actually happened to a notification we tried to post.
 *
 * `NotificationManagerCompat.notify()` returns Unit and silently no-ops when notifications are off
 * for the app or the channel, so "we called notify" is not "the user saw it". This is the value
 * that lets DISPLAYED and SUPPRESSED be different events rather than the same optimistic guess.
 */
internal class RenderResult private constructor(
    val posted: Boolean,
    val suppressionReason: SuppressionReason?,
) {
    companion object {
        val POSTED: RenderResult = RenderResult(posted = true, suppressionReason = null)

        /**
         * Nothing was posted and nothing was refused: the message carried no visible content.
         * Distinct from a suppression — the OS never entered into it, and there is no wire
         * `suppressionReason` to report.
         */
        val SKIPPED: RenderResult = RenderResult(posted = false, suppressionReason = null)

        fun suppressed(reason: SuppressionReason): RenderResult = RenderResult(posted = false, suppressionReason = reason)
    }
}
