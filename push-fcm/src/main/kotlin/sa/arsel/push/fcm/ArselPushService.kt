package sa.arsel.push.fcm

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import sa.arsel.core.Arsel

/**
 * Drop-in FCM service. The only Firebase-touching class in the SDK: it converts Firebase types
 * into the Firebase-free core API. Auto-registered via the library manifest.
 *
 * `onMessageReceived` runs on a background binder thread, so the synchronous render inside
 * `Arsel.handlePushData` is safe (never the main thread). Every entry point is wrapped
 * so the SDK can never crash the host.
 */
public class ArselPushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        runCatching { Arsel.setPushToken(token) }
            .onFailure { Log.w(TAG, "onNewToken handling failed", it) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // handlePushData returns false if it isn't an Arsel push — host's own service (if any) handles it.
        runCatching { Arsel.handlePushData(applicationContext, message.data) }
            .onFailure { Log.w(TAG, "onMessageReceived handling failed", it) }
    }

    /**
     * FCM dropped this device's queued backlog. The messages are unrecoverable, but the callback
     * itself is information: it is one of the few signals separating an app the OEM keeps killing
     * from an actual delivery fault, and discarding it makes the two look identical in support.
     */
    override fun onDeletedMessages() {
        runCatching { Arsel.handleDeletedMessages() }
            .onFailure { Log.w(TAG, "onDeletedMessages handling failed", it) }
    }

    private companion object {
        const val TAG = "Arsel"
    }
}
