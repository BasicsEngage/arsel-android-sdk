package sa.arsel.core.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import sa.arsel.core.Arsel

/**
 * Delivery target for `setDeleteIntent` — the only way Android reports a swipe-away.
 *
 * A dismissal without an open is the clearest "seen and rejected" signal the platform emits, and it
 * is the difference between a message that failed to land and one the user actively declined.
 */
public class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // The engagement is only enqueued here — the actual HTTP call is WorkManager's, so this stays
        // well inside the ~10s a receiver is allowed.
        runCatching { Arsel.handleNotificationDismiss(intent) }
            .onFailure { Log.w(TAG, "notification dismiss handling failed", it) }
    }

    private companion object {
        const val TAG = "Arsel"
    }
}
