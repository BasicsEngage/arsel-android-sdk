package sa.arsel.core.notification

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import sa.arsel.core.Arsel

/**
 * Trampoline-safe tap target (Android 12+ forbids a Service/Receiver → Activity hop).
 * No UI: fires the single correct engagement, routes to the deep link (or launcher),
 * then finishes.
 */
public class NotificationTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Crash isolation: a third-party SDK must never crash the host. Logged, never swallowed
        // silently — a tap that routes nowhere is otherwise invisible to the integrator.
        runCatching {
            Arsel.handleNotificationOpen(intent)
            dismissForActionTap()
            route(intent.getStringExtra(Notifications.EXTRA_DEEP_LINK))
        }.onFailure { Log.w(TAG, "notification tap handling failed", it) }
        finish()
    }

    /**
     * `setAutoCancel` only covers the content intent, so an action-button tap would otherwise leave
     * the notification sitting in the shade after the user has already acted on it.
     */
    private fun dismissForActionTap() {
        if (intent.getStringExtra(Notifications.EXTRA_ACTION_ID).isNullOrBlank()) return
        val notificationId = intent.getIntExtra(Notifications.EXTRA_NOTIFICATION_ID, NO_ID)
        if (notificationId == NO_ID) return
        NotificationManagerCompat.from(this).cancel(notificationId)
    }

    private fun route(deepLink: String?) {
        val target =
            deepLink?.takeIf { it.isNotBlank() }?.let { link ->
                Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                    setPackage(packageName) // constrain to the host app (no arbitrary external app)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }.takeIf { it.resolveActivity(packageManager) != null }
            } ?: packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (target == null) {
            Log.w(TAG, "no deep-link target and no launcher activity — nothing to open")
            return
        }
        startActivity(target)
    }

    private companion object {
        const val TAG = "Arsel"
        const val NO_ID = -1
    }
}
