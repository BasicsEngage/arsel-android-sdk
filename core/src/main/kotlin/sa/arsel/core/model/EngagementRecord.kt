package sa.arsel.core.model

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One engagement signal, in the exact shape `POST …/push/engagements` accepts as a batch element.
 *
 * [signature] / [signatureKeyId] are echoed straight back from the payload: the backend binds a
 * engagement to the message it claims to be about by re-deriving the HMAC, so an engagement that drops them
 * is attributable only by a much weaker path.
 */
internal class EngagementRecord(
    val messageId: String,
    val event: EngagementEvent,
    val timestampMs: Long,
    val signature: String?,
    val signatureKeyId: String?,
    val actionId: String?,
    val deepLink: String?,
    val suppressionReason: SuppressionReason?,
) {
    /**
     * Whether this signal must go out on its own rather than wait for the coalescing window.
     *
     * Taps drive the "Push Opened" automation trigger, so latency there is customer-visible; taps
     * and dismissals also originate in short-lived components (a trampoline Activity, a broadcast
     * receiver) whose process may be gone before a delayed flush would ever run.
     */
    val isUrgent: Boolean
        get() =
            event == EngagementEvent.OPENED ||
                event == EngagementEvent.CLICKED ||
                event == EngagementEvent.DISMISSED

    fun toJson(): JSONObject {
        val json =
            JSONObject()
                .put(FIELD_MESSAGE_ID, messageId)
                .put(FIELD_EVENT_TYPE, event.wire)
                .put(FIELD_TIMESTAMP, iso8601(timestampMs))
        // put(String, Any?) with a null value removes the key, so absent fields simply stay absent.
        json.put(FIELD_SIGNATURE, signature)
        json.put(FIELD_SIGNATURE_KEY_ID, signatureKeyId)
        json.put(FIELD_ACTION_ID, actionId)
        // Truncated to the backend DTO's cap — over it is a 400 that drops the whole batch.
        json.put(FIELD_DEEP_LINK, deepLink?.take(MAX_DEEP_LINK))
        json.put(FIELD_SUPPRESSION_REASON, suppressionReason?.wire)
        return json
    }

    companion object {
        private const val FIELD_MESSAGE_ID = "messageId"
        private const val FIELD_EVENT_TYPE = "eventType"
        private const val FIELD_TIMESTAMP = "timestamp"
        private const val FIELD_SIGNATURE = "signature"
        private const val FIELD_SIGNATURE_KEY_ID = "signatureKeyId"
        private const val FIELD_ACTION_ID = "actionId"
        private const val FIELD_DEEP_LINK = "deepLink"
        private const val FIELD_SUPPRESSION_REASON = "suppressionReason"

        private const val ISO_8601_UTC = "yyyy-MM-dd'T'HH:mm:ss'Z'"

        /** `DEEP_LINK_MAX` on the engagement DTO. */
        internal const val MAX_DEEP_LINK = 2048

        fun of(
            msg: ArselPushMessage,
            event: EngagementEvent,
            suppressionReason: SuppressionReason? = null,
            nowMs: Long = System.currentTimeMillis(),
        ): EngagementRecord =
            EngagementRecord(
                messageId = msg.messageId,
                event = event,
                timestampMs = nowMs,
                signature = msg.signature,
                signatureKeyId = msg.keyId,
                actionId = null,
                deepLink = null,
                suppressionReason = suppressionReason,
            )

        /** Not cached: [SimpleDateFormat] is not thread-safe and engagements are raised from any thread. */
        private fun iso8601(ms: Long): String =
            SimpleDateFormat(ISO_8601_UTC, Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(ms))
    }
}
