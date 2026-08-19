package sa.arsel.core.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Fires [onForeground] when the app comes back to the foreground.
 *
 * This is the SDK's only mechanism for noticing that notifications were switched off: Android emits
 * no callback when a user revokes POST_NOTIFICATIONS or blocks a channel from system settings, so
 * the state can only ever be *polled*, and app foreground is the one moment where polling it is
 * both cheap and timely. Without it, a device that silently stopped showing notifications is
 * indistinguishable from an uninstall for the rest of its life.
 *
 * Uses `ActivityLifecycleCallbacks` rather than `ProcessLifecycleOwner` so the SDK does not force
 * `androidx.lifecycle:lifecycle-process` onto every host.
 */
internal class ForegroundWatcher(
    private val clock: () -> Long,
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit = {},
) : Application.ActivityLifecycleCallbacks {
    private val lock = Any()
    private var startedActivities = 0
    private var lastFiredAtMs = 0L

    /**
     * The Activity an in-app message can be drawn into.
     *
     * WEAK, and not negotiable: this watcher is held for the whole process lifetime by
     * `registerActivityLifecycleCallbacks`, so a strong field would pin every Activity it ever saw
     * and leak the view hierarchy with it.
     */
    private var current: WeakReference<Activity>? = null

    /** Null when nothing is resumed — backgrounded, or a host with no Activity at all. */
    val currentActivity: Activity?
        get() = synchronized(lock) { current?.get() }

    fun attach(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        val shouldFire =
            synchronized(lock) {
                startedActivities += 1
                val now = clock()
                // A configuration change tears the last activity down and brings a new one up, briefly
                // hitting zero; the interval floor is what keeps a rotation from reading as a foreground.
                val due = startedActivities == 1 && now - lastFiredAtMs >= MIN_INTERVAL_MS
                if (due) lastFiredAtMs = now
                due
            }
        if (shouldFire) onForeground()
    }

    /**
     * Fires on every drop to zero, including the momentary one a rotation causes. Debouncing it
     * here would need a timer; the consumer instead treats a short absence as the same session
     * (see [SessionTracker.SESSION_GAP_MS]), which needs no scheduling and survives process death.
     */
    override fun onActivityStopped(activity: Activity) {
        val wentAway =
            synchronized(lock) {
                if (startedActivities > 0) startedActivities -= 1
                startedActivities == 0
            }
        if (wentAway) onBackground()
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ): Unit = Unit

    // RESUMED, not STARTED: a started-but-not-resumed Activity sits behind a dialog or a
    // translucent Activity, and drawing into it puts the message underneath something.
    override fun onActivityResumed(activity: Activity) {
        synchronized(lock) { current = WeakReference(activity) }
    }

    override fun onActivityPaused(activity: Activity) {
        clearIfCurrent(activity)
    }

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ): Unit = Unit

    override fun onActivityDestroyed(activity: Activity) {
        clearIfCurrent(activity)
    }

    /**
     * Only clears when the departing Activity is the tracked one: A to B navigation resumes B
     * before it pauses A, so an unconditional clear would drop B moments after taking it.
     */
    private fun clearIfCurrent(activity: Activity) {
        synchronized(lock) {
            if (current?.get() === activity) current = null
        }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 30_000L
    }
}
