package com.example.arselsample

import android.app.Application
import sa.arsel.core.Arsel
import sa.arsel.core.ArselConfig
import sa.arsel.core.log.LogLevel

/** Initializes the SDK exactly as a client integrator would: one call in `onCreate()`. */
class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        Arsel.initialize(
            this,
            ArselConfig.Builder(
                clientKey = BuildConfig.ARSEL_CLIENT_KEY,
                baseUrl = BuildConfig.ARSEL_BASE_URL,
            )
                .defaultChannel(DEFAULT_CHANNEL_ID, "Arsel Notifications")
                // A real app ships its own white-on-transparent status-bar icon.
                .smallIcon(android.R.drawable.ic_dialog_email)
                // Verbose so the whole flow is visible in Logcat under the tag `Arsel`.
                .logLevel(LogLevel.VERBOSE)
                .build(),
        )
        SdkEventLog.log("Arsel.initialize(clientKey=${BuildConfig.ARSEL_CLIENT_KEY})")
    }

    companion object {
        const val DEFAULT_CHANNEL_ID = "arsel_default"
    }
}
