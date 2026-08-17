package sa.arsel.core.internal

import org.json.JSONArray
import org.json.JSONObject
import sa.arsel.core.model.DeviceSnapshot
import sa.arsel.core.model.EngagementRecord
import sa.arsel.core.model.PushPlatform
import sa.arsel.core.model.PushVendor

/**
 * Pure construction of the four public request bodies.
 *
 * Separated from [PushController] because these are the one part of the SDK that must match the
 * backend DTOs byte for byte — `forbidNonWhitelisted` turns a stray field into a 400 that fails the
 * whole call — and because keeping them free of [android.content.Context] is what lets the JVM unit
 * tests assert the shapes field by field without an emulator.
 *
 * Every field name below is a `class-validator` property on the corresponding DTO; the KDoc names
 * the DTO so a backend change has an obvious counterpart here.
 */
internal object PushBodies {
    /**
     * `RegisterPushSubscriptionDto`.
     *
     * @param anonymousId the SDK's person-shaped id, which is what binds the subscription to the
     *   contact its events already resolve to.
     */
    fun register(
        installationId: String,
        deviceToken: String,
        anonymousId: String,
        device: DeviceSnapshot,
    ): JSONObject {
        val body =
            JSONObject()
                .put(FIELD_INSTALLATION_ID, installationId)
                .put(FIELD_PLATFORM, PushPlatform.ANDROID.wire)
                .put(FIELD_VENDOR, PushVendor.FCM.wire)
                .put(FIELD_DEVICE_TOKEN, deviceToken)
                .put(FIELD_ANONYMOUS_ID, anonymousId)
                .put(FIELD_ENABLEMENT_STATUS, device.enablementStatus.wire)
        // put(String, Any?) removes the key on a null value, so an absent fact stays absent rather
        // than arriving as a JSON null the DTO's @IsString would reject.
        body.put(FIELD_APP_VERSION, device.appVersion)
            .put(FIELD_OS_VERSION, device.osVersion)
            .put(FIELD_DEVICE_MODEL, device.deviceModel)
            .put(FIELD_DEVICE_MANUFACTURER, device.deviceManufacturer)
            .put(FIELD_DEVICE_TIMEZONE, device.deviceTimezone)
            .put(FIELD_DEVICE_LOCALE, device.deviceLocale)
        return body
    }

    /**
     * `PushDeviceStateDto`.
     *
     * `deviceModel` / `deviceManufacturer` are part of the registration fingerprint but absent from
     * this body — and absent from the DTO — because they come from `Build` constants baked into the
     * ROM and cannot change for an installation.
     */
    fun state(
        installationId: String,
        deviceToken: String,
        device: DeviceSnapshot,
    ): JSONObject =
        JSONObject()
            .put(FIELD_INSTALLATION_ID, installationId)
            .put(FIELD_DEVICE_TOKEN, deviceToken)
            .put(FIELD_ENABLEMENT_STATUS, device.enablementStatus.wire)
            .put(FIELD_APP_VERSION, device.appVersion)
            .put(FIELD_OS_VERSION, device.osVersion)
            .put(FIELD_DEVICE_TIMEZONE, device.deviceTimezone)
            .put(FIELD_DEVICE_LOCALE, device.deviceLocale)

    /** `UnsubscribePushSubscriptionDto`. */
    fun unsubscribe(installationId: String): JSONObject = JSONObject().put(FIELD_INSTALLATION_ID, installationId)

    /** `PushEngagementBatchDto`. The caller is responsible for the [ENGAGEMENT_BATCH_MAX] ceiling. */
    fun engagements(
        installationId: String,
        records: List<EngagementRecord>,
    ): JSONObject {
        val events = JSONArray()
        records.forEach { events.put(it.toJson()) }
        return JSONObject()
            .put(FIELD_INSTALLATION_ID, installationId)
            .put(FIELD_EVENTS, events)
    }

    /** `PUSH_ENGAGEMENT_BATCH_MAX` on the backend. Raising it there is safe; lowering it is not. */
    const val ENGAGEMENT_BATCH_MAX: Int = 50

    const val FIELD_INSTALLATION_ID: String = "installationId"
    const val FIELD_ANONYMOUS_ID: String = "anonymousId"
    const val FIELD_PLATFORM: String = "platform"
    const val FIELD_VENDOR: String = "vendor"
    const val FIELD_DEVICE_TOKEN: String = "deviceToken"
    const val FIELD_ENABLEMENT_STATUS: String = "enablementStatus"
    const val FIELD_APP_VERSION: String = "appVersion"
    const val FIELD_OS_VERSION: String = "osVersion"
    const val FIELD_DEVICE_MODEL: String = "deviceModel"
    const val FIELD_DEVICE_MANUFACTURER: String = "deviceManufacturer"
    const val FIELD_DEVICE_TIMEZONE: String = "deviceTimezone"
    const val FIELD_DEVICE_LOCALE: String = "deviceLocale"
    const val FIELD_EVENTS: String = "events"
}
