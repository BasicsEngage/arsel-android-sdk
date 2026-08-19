package sa.arsel.core.inapp

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import sa.arsel.core.log.ArselLog

/**
 * Draws an in-app message into the Activity currently on screen.
 *
 * Views are built in code rather than inflated from XML on purpose: a library that ships layout
 * resources collides with the host app's resource names and forces every integrator to carry them.
 * A handful of `View` constructions costs less than that.
 *
 * The message is attached to the Activity's own `android.R.id.content`, not a new Window, so it
 * inherits that Activity's lifecycle — it cannot outlive the screen it was shown on, and there is
 * no window token to leak.
 */
internal class InAppPresenter(
    private val controller: InAppController,
    private val activityProvider: () -> Activity?,
    private val log: ArselLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val main = Handler(Looper.getMainLooper())

    fun present(message: InAppMessage) {
        val delayMs = message.delaySeconds * MILLIS_PER_SECOND
        if (delayMs <= 0L) {
            main.post { show(message) }
            return
        }
        main.postDelayed({ show(message) }, delayMs)
    }

    private fun show(message: InAppMessage) {
        val activity = activityProvider()
        // Backgrounded during the delay window, or a host with no Activity at all. Abandoned
        // silently: no beacon and no counter, because a message nobody saw is not an impression
        // and recording one corrupts every rate in the channel.
        if (activity == null || activity.isFinishing) {
            controller.releaseActive()
            return
        }
        val root = runCatching { activity.findViewById<ViewGroup>(android.R.id.content) }.getOrNull()
        if (root == null) {
            controller.releaseActive()
            return
        }
        runCatching { render(activity, root, message) }
            .onFailure {
                log.w("in-app render failed", it)
                controller.releaseActive()
            }
    }

    private fun render(
        activity: Activity,
        root: ViewGroup,
        message: InAppMessage,
    ) {
        val shownAtMs = clock()
        val density = activity.resources.displayMetrics.density
        val scrimmed = message.layout == LAYOUT_MODAL || message.layout == LAYOUT_FULLSCREEN

        val overlay = FrameLayout(activity)
        if (scrimmed) {
            overlay.setBackgroundColor(SCRIM_COLOR)
            overlay.isClickable = true
        }

        val panelColor = parseColor(message.backgroundColor) ?: Color.WHITE
        val panel = buildPanel(activity, message, density, panelColor)
        overlay.addView(panel, panelLayout(message, density))

        var closed = false
        val close = { reportDismiss: Boolean ->
            if (!closed) {
                closed = true
                runCatching { root.removeView(overlay) }
                controller.releaseActive()
                if (reportDismiss) {
                    controller.recordDismiss(message, (clock() - shownAtMs) / MILLIS_PER_SECOND)
                }
            }
        }

        if (message.showCloseButton) {
            panel.addView(closeButton(activity, density, parseColor(message.textColor) ?: contrastTo(panelColor)) { close(true) })
            // Dismissable by the scrim only when the author allowed a close affordance; otherwise a
            // stray tap destroys a message they meant to be deliberate.
            if (scrimmed) overlay.setOnClickListener { close(true) }
        }
        addButtons(activity, panel, message, density) { button ->
            if (button.action != ACTION_DISMISS) controller.recordClick(message, button.buttonId)
            close(button.action == ACTION_DISMISS)
            performAction(activity, button)
        }

        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        // Reported once the view is actually attached, never at build time.
        overlay.post { controller.recordImpression(message, message.triggerEventName) }
    }

    private fun buildPanel(
        activity: Activity,
        message: InAppMessage,
        density: Float,
        panelColor: Int,
    ): LinearLayout {
        val textColor = parseColor(message.textColor) ?: contrastTo(panelColor)
        val padding = dp(PADDING_DP, density)

        val panel = LinearLayout(activity)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(padding, padding, padding, padding)
        panel.background =
            GradientDrawable().apply {
                setColor(panelColor)
                cornerRadius = dp(CORNER_DP, density).toFloat()
            }
        // Swallows taps, so one landing on the panel never reaches the dismissing scrim behind it.
        panel.isClickable = true

        if (message.layout != LAYOUT_IMAGE_ONLY) {
            panel.addView(label(activity, message.headline, HEADLINE_SP, textColor, bold = true))
            if (message.body.isNotEmpty()) {
                val body = label(activity, message.body, BODY_SP, textColor, bold = false)
                body.setPadding(0, dp(GAP_DP, density), 0, 0)
                panel.addView(body)
            }
        }
        return panel
    }

    /** Text is always set from a value, never from markup: the content is org-authored and renders inside the customer's app. */
    private fun label(
        activity: Activity,
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean,
    ): TextView {
        val view = TextView(activity)
        view.text = value
        view.setTextColor(color)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) view.setTypeface(view.typeface, Typeface.BOLD)
        return view
    }

    private fun panelLayout(
        message: InAppMessage,
        density: Float,
    ): FrameLayout.LayoutParams {
        val margin = dp(MARGIN_DP, density)
        val gravity =
            when (message.layout) {
                LAYOUT_BANNER_TOP -> Gravity.TOP
                LAYOUT_BANNER_BOTTOM -> Gravity.BOTTOM
                else -> Gravity.CENTER
            }
        val height = if (message.layout == LAYOUT_FULLSCREEN) MATCH else WRAP
        val params = FrameLayout.LayoutParams(MATCH, height, gravity)
        params.setMargins(margin, margin, margin, margin)
        return params
    }

    private fun addButtons(
        activity: Activity,
        panel: LinearLayout,
        message: InAppMessage,
        density: Float,
        onClick: (InAppButton) -> Unit,
    ) {
        if (message.buttons.isEmpty()) return
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(GAP_DP, density), 0, 0)
        for (button in message.buttons) {
            val view = Button(activity)
            view.text = button.label
            view.minHeight = dp(MIN_TAP_TARGET_DP, density)
            view.setOnClickListener { onClick(button) }
            val params = LinearLayout.LayoutParams(0, WRAP, 1f)
            params.marginStart = dp(GAP_DP, density)
            row.addView(view, params)
        }
        panel.addView(row)
    }

    private fun closeButton(
        activity: Activity,
        density: Float,
        color: Int,
        onClose: () -> Unit,
    ): Button {
        val view = Button(activity)
        view.text = CLOSE_GLYPH
        view.contentDescription = CLOSE_LABEL
        view.setTextColor(color)
        view.minWidth = dp(MIN_TAP_TARGET_DP, density)
        view.minHeight = dp(MIN_TAP_TARGET_DP, density)
        view.setOnClickListener { onClose() }
        return view
    }

    /**
     * A deep link or URL leaves the app, so the click beacon is already queued by the caller before
     * this runs — the queue is persisted and survives the process going away.
     */
    private fun performAction(
        activity: Activity,
        button: InAppButton,
    ) {
        val value = button.value
        when (button.action) {
            ACTION_DEEP_LINK, ACTION_URL -> {
                if (value.isNullOrEmpty()) return
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
                    .onFailure { log.w("in-app: nothing on this device handles $value", it) }
            }
            ACTION_CUSTOM_EVENT ->
                if (!value.isNullOrEmpty()) {
                    controller.observe(TRIGGER_CUSTOM_EVENT, value, emptyMap())
                }
            else -> Unit
        }
    }

    private fun parseColor(hex: String?): Int? {
        if (hex.isNullOrEmpty()) return null
        return runCatching { Color.parseColor(hex) }.getOrNull()
    }

    /**
     * Supplies the readable half of a colour pair when an author set only the background —
     * otherwise a white-on-white message reports a perfectly healthy impression nobody could read.
     */
    private fun contrastTo(background: Int): Int {
        val luminance =
            (
                RED_WEIGHT * Color.red(background) +
                    GREEN_WEIGHT * Color.green(background) +
                    BLUE_WEIGHT * Color.blue(background)
            ) / MAX_CHANNEL
        return if (luminance > LIGHT_THRESHOLD) Color.BLACK else Color.WHITE
    }

    private fun dp(
        value: Int,
        density: Float,
    ): Int = (value * density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val MILLIS_PER_SECOND = 1000L

        /** ~45% black. Dark enough to read against, light enough to show the app behind it. */
        const val SCRIM_COLOR = 0x73000000
        const val PADDING_DP = 20
        const val MARGIN_DP = 16
        const val GAP_DP = 8
        const val CORNER_DP = 12

        /** The Material minimum touch target; anything smaller fails an accessibility scan. */
        const val MIN_TAP_TARGET_DP = 48
        const val HEADLINE_SP = 18f
        const val BODY_SP = 15f
        const val CLOSE_GLYPH = "×"
        const val CLOSE_LABEL = "Close"
        const val RED_WEIGHT = 0.2126
        const val GREEN_WEIGHT = 0.7152
        const val BLUE_WEIGHT = 0.0722
        const val MAX_CHANNEL = 255.0
        const val LIGHT_THRESHOLD = 0.6
    }
}
