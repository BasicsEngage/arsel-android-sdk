package com.example.arselsample

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A bounded, timestamped record of what the harness asked the SDK to do and what visibly changed
 * afterwards.
 *
 * The SDK exposes no event callback by design — it must never call back into a host that may be
 * mid-teardown — so "what the SDK did" is observed the same way an integrator observes it in the
 * field: through [sa.arsel.core.Arsel.diagnostics]. This class is what turns that
 * snapshot into a timeline.
 */
object SdkEventLog {

    private const val MAX_LINES = 60

    private val lines = ArrayDeque<String>()
    private val lock = Any()

    /** Set by the UI while it is resumed. Always invoked on the thread that called [log]. */
    @Volatile
    var onChange: (() -> Unit)? = null

    fun log(message: String) {
        synchronized(lock) {
            lines.addLast("${timestamp()}  $message")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        onChange?.invoke()
    }

    /** Newest first — the line you want is the one that just happened. */
    fun snapshot(): String = synchronized(lock) {
        if (lines.isEmpty()) "(nothing yet)" else lines.reversed().joinToString("\n")
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
        onChange?.invoke()
    }

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
}
