package sa.arsel.core.model

/**
 * The exact `data` maps the backend emits, transcribed verbatim from the frozen Jest snapshot
 * `New-Backend/src/core/v1/channels/push/providers/__snapshots__/push-envelope.builder.spec.ts.snap`.
 *
 * These are the bytes that actually land on a handset. Asserting the parser against a payload
 * someone typed from memory is how a wire mismatch survives a full test suite — so nothing here
 * should be "tidied up". If the snapshot changes, re-transcribe; do not adjust the parser to match
 * a fixture that was edited for convenience.
 */
internal object GoldenEnvelopes {
    const val MESSAGE_ID: String = "0195c7ac-1111-5222-8333-444455556666"
    const val SIGNATURE: String = "sIgNaTuReSiGnAtUrE22"

    /** `buildCredentialProbeEnvelope is a validate_only send that carries no content`. */
    val CREDENTIAL_PROBE: Map<String, String> =
        mapOf(
            "arsel_v" to "1",
        )

    /** `buildFcmEnvelope builds a data-only envelope for an SDK-equipped Android device`. */
    val ANDROID_SDK_BASIC: Map<String, String> =
        mapOf(
            "arsel_body" to "Track it in the app.",
            "arsel_kid" to "v1",
            "arsel_mid" to MESSAGE_ID,
            "arsel_sig" to SIGNATURE,
            "arsel_title" to "Your order shipped",
            "arsel_v" to "1",
        )

    /** `buildFcmEnvelope builds a notification envelope for a Path-A Android device`. */
    val ANDROID_PATH_A: Map<String, String> =
        mapOf(
            "arsel_collapse_id" to "campaign-42",
            "arsel_kid" to "v1",
            "arsel_mid" to MESSAGE_ID,
            "arsel_sig" to SIGNATURE,
            "arsel_v" to "1",
        )

    /** `buildFcmEnvelope builds an apns alert block for an SDK-equipped iOS device`. */
    val IOS_SDK: Map<String, String> =
        mapOf(
            "arsel_body" to "Track it in the app.",
            "arsel_deep_link" to "myapp://orders/42",
            "arsel_kid" to "v1",
            "arsel_mid" to MESSAGE_ID,
            "arsel_sig" to SIGNATURE,
            "arsel_title" to "Your order shipped",
            "arsel_v" to "1",
        )

    /** `buildFcmEnvelope carries action buttons and custom data on an SDK envelope`. */
    val ANDROID_SDK_WITH_ACTIONS: Map<String, String> =
        mapOf(
            "arsel_actions" to
                """[{"actionId":"track","label":"Track","deepLink":"myapp://track"},""" +
                """{"actionId":"help","label":"Help","deepLink":null}]""",
            "arsel_body" to "Track it in the app.",
            "arsel_collapse_id" to "campaign-42",
            "arsel_kid" to "v1",
            "arsel_mid" to MESSAGE_ID,
            "arsel_sig" to SIGNATURE,
            "arsel_title" to "Your order shipped",
            "arsel_v" to "1",
            "order_id" to "42",
        )

    /**
     * Every content key at once. Not a snapshot itself — the builder emits `arsel_image` and
     * `arsel_channel_id` only when the campaign sets them, and no single snapshotted payload sets
     * all of them — but each key is spelled exactly as `PUSH_DATA_KEYS` defines it.
     */
    val FULL: Map<String, String> =
        ANDROID_SDK_WITH_ACTIONS +
            mapOf(
                "arsel_image" to "https://cdn.example.com/hero.png",
                "arsel_deep_link" to "myapp://orders/42",
                "arsel_channel_id" to "orders",
            )
}
