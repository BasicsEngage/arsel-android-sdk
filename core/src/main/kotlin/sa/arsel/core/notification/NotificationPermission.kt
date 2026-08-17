package sa.arsel.core.notification

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import sa.arsel.core.model.PushEnablementStatus

/**
 * POST_NOTIFICATIONS helper (Android 13+). Uses the modern ActivityResult API (plan §0.1 #4) —
 * the host registers a launcher and passes it in; we never use the legacy onRequestPermissionsResult.
 */
public object NotificationPermission {
    // Not `const` — a Java static-final (POST_NOTIFICATIONS) isn't a Kotlin compile-time constant.
    // InlinedApi: the API-33 constant is a String inlined at compile time, so naming it below 33 is
    // harmless; every *use* of it is behind a TIRAMISU check.
    @SuppressLint("InlinedApi")
    public val PERMISSION: String = Manifest.permission.POST_NOTIFICATIONS

    public fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * The device's `enablementStatus` for the backend.
     *
     * `areNotificationsEnabled()` is checked first and on every API level: an app switched off in
     * system settings is undeliverable regardless of the runtime grant, and below API 33 it is the
     * only way to opt out at all.
     *
     * [hasRequested] is what separates NOT_DETERMINED from DENIED. The OS cannot tell them apart —
     * `checkSelfPermission` returns DENIED for both — and `shouldShowRequestPermissionRationale`
     * needs an Activity and lies after a permanent refusal, so the SDK records that it prompted
     * instead of trying to infer it.
     */
    public fun currentStatus(
        context: Context,
        hasRequested: Boolean,
    ): PushEnablementStatus =
        when {
            !areNotificationsEnabled(context) -> PushEnablementStatus.DENIED
            isGranted(context) -> PushEnablementStatus.AUTHORIZED
            hasRequested -> PushEnablementStatus.DENIED
            else -> PushEnablementStatus.NOT_DETERMINED
        }

    /**
     * App-level switch. Broader than [isGranted] and false on a user who turned notifications off in
     * settings, which the permission check alone never sees.
     */
    public fun areNotificationsEnabled(context: Context): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(false)

    /**
     * Launch the runtime request if needed. [launcher] must be created by the host via
     * `registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> ... }`.
     */
    public fun request(
        context: Context,
        launcher: ActivityResultLauncher<String>,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isGranted(context)) {
            launcher.launch(PERMISSION)
        }
    }
}
