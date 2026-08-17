package com.example.arselsample

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import sa.arsel.core.Arsel
import com.google.firebase.messaging.FirebaseMessaging

/**
 * End-to-end harness for the Arsel SDK.
 *
 * Everything on screen is read back through the SDK's own public surface — `getInstallationId()`
 * and `diagnostics()` — so what you see here is exactly what an integrator can see in the field.
 * There is no privileged back channel, and adding one would make this a worse test of the SDK.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var identityView: TextView
    private lateinit var anonymousView: TextView
    private lateinit var tokenView: TextView
    private lateinit var diagnosticsView: TextView
    private lateinit var logView: TextView
    private lateinit var eventNameInput: EditText
    private lateinit var propKeyInput: EditText
    private lateinit var propValueInput: EditText
    private lateinit var externalIdInput: EditText

    private val handler = Handler(Looper.getMainLooper())
    private var fcmToken: String? = null

    /** Last diagnostics rendering, so the poll only writes a log line when something moved. */
    private var lastDiagnostics: String? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            SdkEventLog.log("POST_NOTIFICATIONS -> ${if (granted) "GRANTED" else "DENIED"}")
            // The SDK polls permission on foreground anyway; this just makes the harness immediate.
            Arsel.registerNow()
        }

    private val poll = object : Runnable {
        override fun run() {
            refreshDiagnostics()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        loadFcmToken()
    }

    override fun onResume() {
        super.onResume()
        SdkEventLog.onChange = { runOnUiThread { logView.text = SdkEventLog.snapshot() } }
        logView.text = SdkEventLog.snapshot()
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        SdkEventLog.onChange = null
        handler.removeCallbacks(poll)
    }

    // --- UI ------------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PADDING, PADDING, PADDING, PADDING)
        }

        root.addView(heading("Arsel SDK — test harness"))

        // Which backend this build talks to. Two flavors install side by side, so a screenshot or a
        // debugging session is ambiguous without it.
        root.addView(label("Backend"))
        root.addView(
            mono(selectable = false).apply {
                text = "${BuildConfig.ARSEL_BASE_URL}\n${BuildConfig.ARSEL_CLIENT_KEY.take(KEY_PREVIEW)}…"
            },
        )

        root.addView(label("Installation ID — names the DEVICE, survives logout"))
        identityView = mono(selectable = true).also { root.addView(it) }

        root.addView(label("Anonymous ID — names the PERSON, rotated by reset()"))
        anonymousView = mono(selectable = true).also { root.addView(it) }

        root.addView(sectionRule())
        root.addView(heading("Events"))
        root.addView(
            note(
                "Events need no push token, no notification permission and no Firebase. " +
                    "Track something before identifying and watch it follow you across the merge.",
            ),
        )

        eventNameInput = field("product.viewed").also { root.addView(it) }
        propKeyInput = field("sku").also { root.addView(it) }
        propValueInput = field("A-1023").also { root.addView(it) }
        root.addView(button("track()", ::trackEvent))

        root.addView(sectionRule())
        root.addView(heading("Identity"))
        root.addView(
            note(
                "identify() merges everything tracked anonymously onto the contact it resolves to. " +
                    "The push subscription follows on its own — registration carries the anonymous " +
                    "id, so the device lands on the same contact its events do.",
            ),
        )

        root.addView(label("identify(externalId) — client-asserted, merges the anonymous history"))
        externalIdInput = field("user_10294").also { root.addView(it) }
        root.addView(button("identify(externalId = …)", ::identifyWithExternalId))
        root.addView(
            button("Run the merge proof (track → identify → track)", ::runMergeProof),
        )

        root.addView(label("FCM registration token"))
        tokenView = mono(selectable = true).apply { text = "(loading…)" }.also { root.addView(it) }
        root.addView(
            button("Copy FCM token") {
                copy("fcm_token", fcmToken, "FCM token")
            },
        )

        root.addView(sectionRule())
        root.addView(label("Actions"))
        root.addView(button("registerNow()") { run("registerNow()") { Arsel.registerNow() } })
        root.addView(button("flushNow()") { run("flushNow()") { Arsel.flushNow() } })
        root.addView(
            note(
                "Never press this and everything above still works. That is the point: on a fresh " +
                    "install, track() and identify() build a contact with a real event history " +
                    "while enablementStatus stays NOT_DETERMINED and no subscription row exists.",
            ),
        )
        root.addView(
            button("Request notification permission (Android 13+)") {
                run("requestNotificationPermission()") {
                    Arsel.requestNotificationPermission(permissionLauncher)
                }
            },
        )
        root.addView(button("reset() — logout, stays subscribed", ::resetIdentity))
        root.addView(button("optOut() — DURABLE, not resurrectable", ::optOut))

        root.addView(label("Diagnostics"))
        diagnosticsView = mono(selectable = true).also { root.addView(it) }
        root.addView(button("Copy diagnostics") { copy("diagnostics", diagnosticsText(), "Diagnostics") })

        root.addView(label("SDK event log (newest first)"))
        logView = mono(selectable = true).also { root.addView(it) }
        root.addView(button("Clear log") { SdkEventLog.clear() })

        return ScrollView(this).apply { addView(root) }
    }

    // --- actions --------------------------------------------------------------

    private fun trackEvent() {
        val name = eventNameInput.text.toString().trim()
        if (name.isEmpty()) {
            toast("Give the event a name first")
            return
        }
        val properties = propertyPair()
        run("track(\"$name\", $properties)") { Arsel.track(name, properties) }
    }

    /** One optional key/value pair — enough to prove properties reach the wire intact. */
    private fun propertyPair(): Map<String, Any?> {
        val key = propKeyInput.text.toString().trim()
        val value = propValueInput.text.toString().trim()
        if (key.isEmpty() || value.isEmpty()) return emptyMap()
        // Numbers stay numbers: a property that arrives as "149.99" fails a numeric event schema
        // on the backend, and that is a real integration mistake worth being able to reproduce.
        return mapOf(key to (value.toLongOrNull() ?: value.toDoubleOrNull() ?: value))
    }

    private fun identifyWithExternalId() {
        val externalId = externalIdInput.text.toString().trim()
        if (externalId.isEmpty()) {
            toast("Enter an external ID first")
            return
        }
        run("identify(externalId = \"$externalId\")") {
            Arsel.identify(externalId = externalId)
        }
    }

    /**
     * The end-to-end proof that an anonymous history survives identification.
     *
     * Tracks under the anonymous id, identifies, then tracks again. On the backend both events must
     * end up on ONE contact — the anonymous one merged into the identified one. The anonymous id
     * shown before and after is the same, which is what makes the merge observable from here: the
     * SDK does not rotate it on identify, only on `reset()`.
     */
    private fun runMergeProof() {
        val externalId = externalIdInput.text.toString().trim()
        if (externalId.isEmpty()) {
            toast("Enter an external ID first")
            return
        }
        val anonymousBefore = Arsel.getAnonymousId()
        SdkEventLog.log("MERGE PROOF — anonymous id before: ${anonymousBefore ?: "(none)"}")

        run("track(\"merge.before_identify\")") {
            Arsel.track("merge.before_identify", mapOf("stage" to "anonymous"))
        }
        run("identify(externalId = \"$externalId\")") {
            Arsel.identify(externalId = externalId)
        }
        run("track(\"merge.after_identify\")") {
            Arsel.track("merge.after_identify", mapOf("stage" to "identified"))
        }
        run("flushNow()") { Arsel.flushNow() }

        SdkEventLog.log(
            "MERGE PROOF — both events sent. On the backend, contact external_id=$externalId " +
                "should now hold BOTH, and the anonymous contact should be gone.",
        )
    }

    private fun resetIdentity() {
        run("reset()") { Arsel.reset() }
    }

    private fun optOut() {
        // Deliberately not behind a confirmation dialog: the point of the harness is to be able to
        // reach this state and observe that a later register does NOT resurrect the subscription.
        run("optOut()") { Arsel.optOut() }
    }

    private inline fun run(what: String, block: () -> Unit) {
        SdkEventLog.log("-> $what")
        block()
        refreshDiagnostics()
    }

    // --- reads ----------------------------------------------------------------

    private fun loadFcmToken() {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                fcmToken = token
                tokenView.text = token ?: "(null)"
                SdkEventLog.log("FCM token acquired (${token?.length ?: 0} chars)")
            }
            .addOnFailureListener { error ->
                tokenView.text = "error: ${error.message}"
                SdkEventLog.log("FCM token FAILED: ${error.message}")
            }
    }

    private fun refreshDiagnostics() {
        identityView.text = Arsel.getInstallationId() ?: "(not initialized)"
        anonymousView.text = Arsel.getAnonymousId() ?: "(not initialized)"
        val rendered = diagnosticsText()
        diagnosticsView.text = rendered
        if (rendered != lastDiagnostics) {
            // Only the fields that move on their own are worth a timeline entry; dumping the whole
            // snapshot on every poll would bury the one line that matters.
            lastDiagnostics = rendered
            Arsel.diagnostics()?.let {
                SdkEventLog.log(
                    "diagnostics: registered=${it.isRegistered} " +
                        "asserted=${it.hasAssertedIdentity} secret=${it.hasDeviceSecret} " +
                        "queue=${it.queueDepth} events=${it.eventQueueDepth} " +
                        "last=${it.lastResponseCode} ${it.lastResponsePath ?: "-"}",
                )
            }
        }
    }

    private fun diagnosticsText(): String =
        Arsel.diagnostics()?.toString() ?: "(SDK not initialized)"

    // --- view helpers ---------------------------------------------------------

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTypeface(null, Typeface.BOLD)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTypeface(null, Typeface.BOLD)
        setPadding(0, PADDING, 0, PADDING / 4)
    }

    /** Small explanatory text. The harness teaches as much as it tests. */
    private fun note(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, 0, 0, PADDING / 3)
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine()
    }

    private fun sectionRule() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, RULE_HEIGHT)
            .apply { topMargin = PADDING; bottomMargin = PADDING / 2 }
        setBackgroundColor(RULE_COLOR)
    }

    private fun mono(selectable: Boolean) = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextIsSelectable(selectable)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener { onClick() }
    }

    private fun copy(key: String, value: String?, what: String) {
        if (value.isNullOrEmpty()) {
            toast("$what is not available yet")
            return
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(key, value))
        toast("$what copied")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PADDING = 48
        const val POLL_INTERVAL_MS = 1_000L
        const val KEY_PREVIEW = 12
        const val RULE_HEIGHT = 2
        const val RULE_COLOR = 0xFF444444.toInt()
    }
}
