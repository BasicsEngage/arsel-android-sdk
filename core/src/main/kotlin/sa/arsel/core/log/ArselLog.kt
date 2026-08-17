package sa.arsel.core.log

import android.util.Log

public enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR, NONE }

/**
 * Tiny leveled logger. NEVER logs raw device/contact tokens — they are redacted
 * (security requirement).
 */
internal class ArselLog(private val level: LogLevel) {
    fun v(msg: String) = log(LogLevel.VERBOSE, msg, null)

    fun d(msg: String) = log(LogLevel.DEBUG, msg, null)

    fun i(msg: String) = log(LogLevel.INFO, msg, null)

    fun w(
        msg: String,
        t: Throwable? = null,
    ) = log(LogLevel.WARN, msg, t)

    fun e(
        msg: String,
        t: Throwable? = null,
    ) = log(LogLevel.ERROR, msg, t)

    private fun log(
        at: LogLevel,
        msg: String,
        t: Throwable?,
    ) {
        if (level == LogLevel.NONE || at.ordinal < level.ordinal) return
        when (at) {
            LogLevel.VERBOSE -> Log.v(TAG, msg)
            LogLevel.DEBUG -> Log.d(TAG, msg)
            LogLevel.INFO -> Log.i(TAG, msg)
            LogLevel.WARN -> Log.w(TAG, msg, t)
            LogLevel.ERROR -> Log.e(TAG, msg, t)
            LogLevel.NONE -> Unit
        }
    }

    companion object {
        private const val TAG = "Arsel"

        /** Redact a token for logs: keep a short prefix only. */
        fun redact(token: String?): String =
            when {
                token.isNullOrEmpty() -> "<none>"
                token.length <= 6 -> "******"
                else -> token.take(6) + "…(${token.length})"
            }
    }
}
