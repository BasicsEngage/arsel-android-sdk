package sa.arsel.push.fcm

import android.content.Context
import android.util.Log
import androidx.startup.Initializer
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import sa.arsel.core.Arsel

/** True if this FCM message is an Arsel push — for hosts multiplexing their own service. */
public val RemoteMessage.isArselMessage: Boolean
    get() = Arsel.isArselData(this.data)

/** Bridges Firebase token retrieval into the Firebase-free core facade. */
internal object FcmBridgeImpl : Arsel.FcmBridge {
    private const val TAG = "Arsel"

    override fun requestCurrentToken() {
        // Logged, never swallowed: a silent failure here is a device that can never register.
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> token?.let { Arsel.setPushToken(it) } }
                .addOnFailureListener { Log.w(TAG, "FCM token fetch failed", it) }
        }.onFailure { Log.w(TAG, "FCM token request failed", it) }
    }
}

/**
 * App Startup initializer — sets [Arsel.fcmBridge] before Application.onCreate, so that
 * [Arsel.initialize] can fetch the current FCM token (covers app restarts where onNewToken
 * does not fire). Registered via the library manifest's InitializationProvider meta-data.
 */
public class ArselFcmInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        Arsel.fcmBridge = FcmBridgeImpl
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
