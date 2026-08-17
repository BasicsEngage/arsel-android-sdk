package sa.arsel.core.model

import org.json.JSONArray

/**
 * Transport platform, as the backend's `PushPlatform` enum spells it. `fcm` is not a member —
 * FCM is the transport, `android` is the platform, and posting the former is a 400.
 */
public enum class PushPlatform(public val wire: String) {
    ANDROID("android"),
    IOS("ios"),
    WEB("web"),
}

/**
 * The transport carrying the message, as the backend's `PushVendor` enum spells it.
 *
 * Separate from [PushPlatform] because `platform` is the device family and cannot imply the
 * transport forever: once the direct APNs transport lands, a `platform = ios` row may hold either an FCM
 * or an APNs token. Android is always [FCM] — this SDK wraps Firebase and nothing else.
 */
public enum class PushVendor(public val wire: String) {
    FCM("fcm"),
    APNS("apns"),
    WEB_PUSH("web_push"),
}

/**
 * OS notification permission, as the backend's `PushEnablementStatus` enum spells it.
 *
 * A tri-state rather than a boolean because "never prompted" and "prompted and refused" are
 * different facts: the first is still winnable, the second is a durable opt-out. Registration is not
 * consent — a device registers before the prompt and while denied — so this travels separately, and
 * reporting [DENIED] is never an unsubscribe.
 *
 * [PROVISIONAL] and [UNAUTHORIZED] are iOS modes with no Android equivalent; they are part of the
 * shared vocabulary and this SDK never reports them.
 */
public enum class PushEnablementStatus(public val wire: String) {
    AUTHORIZED("AUTHORIZED"),
    DENIED("DENIED"),
    NOT_DETERMINED("NOT_DETERMINED"),
    PROVISIONAL("PROVISIONAL"),
    UNAUTHORIZED("UNAUTHORIZED"),
}

/**
 * Engagement signals reported to `POST …/push/engagements` (best-effort, non-billable).
 *
 * DELIVERED and DISPLAYED are deliberately distinct: `NotificationManagerCompat.notify()`
 * silently no-ops when POST_NOTIFICATIONS is denied or the channel is blocked, so "we received it"
 * is not "the user saw it". A message received but not shown is SUPPRESSED with a reason.
 * OPENED is a body tap; CLICKED is an action-button tap and carries its `actionId`.
 */
public enum class EngagementEvent(public val wire: String) {
    DELIVERED("delivered"),
    DISPLAYED("displayed"),
    SUPPRESSED("suppressed"),
    OPENED("opened"),
    CLICKED("clicked"),
    DISMISSED("dismissed"),
}

/** Why a received message was never posted to the shade. Required on [EngagementEvent.SUPPRESSED]. */
public enum class SuppressionReason(public val wire: String) {
    PERMISSION_DENIED("permission_denied"),
    CHANNEL_BLOCKED("channel_blocked"),
    APP_BLOCKED("app_blocked"),
}

/** One action button carried in `arsel_actions`. */
public class ArselPushAction(
    public val actionId: String,
    public val label: String,
    public val deepLink: String?,
)

/**
 * An Arsel push parsed from the FCM **data** payload. The backend sends data-only to
 * SDK-equipped Android apps, so every value arrives as a String.
 *
 * Not a `data class`: `copy()` and the `componentN()` operators are binary API, and the wire is
 * expected to grow fields — adding one to a data class breaks every host compiled against the old
 * constructor.
 */
public class ArselPushMessage(
    public val messageId: String,
    public val wireVersion: String?,
    public val signature: String?,
    public val keyId: String?,
    public val title: String?,
    public val body: String?,
    public val imageUrl: String?,
    public val deepLink: String?,
    public val channelId: String?,
    public val actions: List<ArselPushAction>,
    public val collapseId: String?,
    /** The customer's own `dataPayload` keys, verbatim. Never contains an `arsel_*` key. */
    public val hostData: Map<String, String>,
) {
    /** Deliberately free of title/body/deepLink: those are customer content and must not reach logcat. */
    override fun toString(): String = "ArselPushMessage(messageId=$messageId, v=$wireVersion, actions=${actions.size})"

    public companion object {
        private const val ARSEL_PREFIX = "arsel_"

        private const val KEY_VERSION = "arsel_v"
        private const val KEY_MESSAGE_ID = "arsel_mid"
        private const val KEY_SIGNATURE = "arsel_sig"
        private const val KEY_SIGNATURE_KEY_ID = "arsel_kid"
        private const val KEY_TITLE = "arsel_title"
        private const val KEY_BODY = "arsel_body"
        private const val KEY_IMAGE = "arsel_image"
        private const val KEY_DEEP_LINK = "arsel_deep_link"
        private const val KEY_CHANNEL_ID = "arsel_channel_id"
        private const val KEY_ACTIONS = "arsel_actions"
        private const val KEY_COLLAPSE_ID = "arsel_collapse_id"

        private const val JSON_ACTION_ID = "actionId"
        private const val JSON_LABEL = "label"
        private const val JSON_DEEP_LINK = "deepLink"

        /**
         * Cheap claim check for hosts multiplexing one `FirebaseMessagingService`. There is no
         * marker key on the wire: the envelope is identified by `arsel_v`, with `arsel_mid` as the
         * fallback for a future envelope that drops the version.
         */
        public fun isArselData(data: Map<String, String>): Boolean =
            !data[KEY_VERSION].isNullOrBlank() || !data[KEY_MESSAGE_ID].isNullOrBlank()

        /** Returns null when [data] is not an Arsel push, or carries no usable message id. */
        public fun fromData(data: Map<String, String>): ArselPushMessage? {
            if (!isArselData(data)) return null
            val id = data[KEY_MESSAGE_ID]?.takeIf { it.isNotBlank() } ?: return null
            return ArselPushMessage(
                messageId = id,
                wireVersion = data[KEY_VERSION],
                signature = data[KEY_SIGNATURE],
                keyId = data[KEY_SIGNATURE_KEY_ID],
                title = data[KEY_TITLE],
                body = data[KEY_BODY],
                imageUrl = data[KEY_IMAGE],
                deepLink = data[KEY_DEEP_LINK],
                channelId = data[KEY_CHANNEL_ID],
                actions = parseActions(data[KEY_ACTIONS]),
                collapseId = data[KEY_COLLAPSE_ID],
                // Prefix-filtered rather than key-listed, so a host key can never shadow an
                // `arsel_*` one no matter what the customer puts in their dataPayload.
                hostData = data.filterKeys { !it.startsWith(ARSEL_PREFIX) },
            )
        }

        /** Malformed JSON degrades to "no buttons" — a broken action list must never lose the push. */
        private fun parseActions(json: String?): List<ArselPushAction> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { index ->
                    val entry = array.optJSONObject(index) ?: return@mapNotNull null
                    val actionId = entry.readString(JSON_ACTION_ID) ?: return@mapNotNull null
                    ArselPushAction(
                        actionId = actionId,
                        label = entry.readString(JSON_LABEL).orEmpty(),
                        deepLink = entry.readString(JSON_DEEP_LINK),
                    )
                }
            }.getOrDefault(emptyList())
        }

        /**
         * `optString` renders a JSON null as the literal string "null" on Android, and the backend
         * emits `deepLink: null` for a button without one — so the null check cannot be skipped.
         */
        private fun org.json.JSONObject.readString(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}
