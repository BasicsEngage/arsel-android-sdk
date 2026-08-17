package sa.arsel.core.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import sa.arsel.core.model.ArselPushMessage
import sa.arsel.core.model.DeviceSnapshot
import sa.arsel.core.model.EngagementEvent
import sa.arsel.core.model.EngagementRecord
import sa.arsel.core.model.PushEnablementStatus
import sa.arsel.core.model.PushPlatform
import sa.arsel.core.model.SuppressionReason

/**
 * Field-by-field checks against the backend DTOs.
 *
 * The DTOs run under `forbidNonWhitelisted: true`, so a field name that drifts is not a degraded
 * report — it is a 400 that fails the entire call. Asserting the exact key *set*, not just that the
 * expected keys are present, is what makes an accidental extra field fail here instead of in
 * production.
 */
class PushBodiesTest {
    private val device =
        DeviceSnapshot(
            appVersion = "1.4.2",
            osVersion = "14",
            deviceModel = "Pixel 8",
            deviceManufacturer = "Google",
            deviceTimezone = "Asia/Riyadh",
            deviceLocale = "ar-SA",
            enablementStatus = PushEnablementStatus.AUTHORIZED,
        )

    // --- RegisterPushSubscriptionDto -----------------------------------------

    @Test
    fun `register body carries exactly the fields the register DTO accepts`() {
        val body = PushBodies.register(INSTALLATION_ID, DEVICE_TOKEN, ANONYMOUS_ID, device)

        assertEquals(
            setOf(
                "installationId",
                "platform",
                "vendor",
                "deviceToken",
                "anonymousId",
                "enablementStatus",
                "appVersion",
                "osVersion",
                "deviceModel",
                "deviceManufacturer",
                "deviceTimezone",
                "deviceLocale",
            ),
            body.keys().asSequence().toSet(),
        )
        assertEquals(INSTALLATION_ID, body.getString("installationId"))
        assertEquals("android", body.getString("platform"))
        // `fcm` belongs here and never in `platform` — the backend enum rejects it there.
        assertEquals("fcm", body.getString("vendor"))
        assertEquals(DEVICE_TOKEN, body.getString("deviceToken"))
        // The device→contact binding: resolved server-side through the same identity ladder the
        // events API uses, so a subscription registered before identify() still lands on the
        // contact the events attach to.
        assertEquals(ANONYMOUS_ID, body.getString("anonymousId"))
        assertEquals("AUTHORIZED", body.getString("enablementStatus"))
        assertEquals("1.4.2", body.getString("appVersion"))
        assertEquals("14", body.getString("osVersion"))
        assertEquals("Pixel 8", body.getString("deviceModel"))
        assertEquals("Google", body.getString("deviceManufacturer"))
        assertEquals("Asia/Riyadh", body.getString("deviceTimezone"))
        assertEquals("ar-SA", body.getString("deviceLocale"))
    }

    @Test
    fun `register body posts the platform enum value the backend spells, never the transport`() {
        val body = PushBodies.register(INSTALLATION_ID, DEVICE_TOKEN, ANONYMOUS_ID, device)

        // "fcm" is the transport. PushPlatform on the backend has no such member, and posting it
        // is a 400 on every registration the SDK ever makes.
        assertEquals(PushPlatform.ANDROID.wire, body.getString("platform"))
        assertEquals("android", body.getString("platform"))
    }

    @Test
    fun `register body omits device facts the handset could not report`() {
        val unknown =
            DeviceSnapshot(
                appVersion = null,
                osVersion = null,
                deviceModel = null,
                deviceManufacturer = null,
                deviceTimezone = null,
                deviceLocale = null,
                enablementStatus = PushEnablementStatus.DENIED,
            )

        val body = PushBodies.register(INSTALLATION_ID, DEVICE_TOKEN, ANONYMOUS_ID, unknown)

        assertEquals(
            setOf("installationId", "platform", "vendor", "deviceToken", "anonymousId", "enablementStatus"),
            body.keys().asSequence().toSet(),
        )
        assertEquals("DENIED", body.getString("enablementStatus"))
    }

    // --- PushDeviceStateDto ---------------------------------------------------

    @Test
    fun `state body carries exactly the fields the device-state DTO accepts`() {
        val body = PushBodies.state(INSTALLATION_ID, DEVICE_TOKEN, device)

        assertEquals(
            setOf(
                "installationId",
                "deviceToken",
                "enablementStatus",
                "appVersion",
                "osVersion",
                "deviceTimezone",
                "deviceLocale",
            ),
            body.keys().asSequence().toSet(),
        )
    }

    @Test
    fun `state body omits the fields the device-state DTO has no property for`() {
        val body = PushBodies.state(INSTALLATION_ID, DEVICE_TOKEN, device)

        // deviceModel/deviceManufacturer are ROM constants and are not on the state DTO;
        // anonymousId has no field either, which is why identity changes go via register.
        assertFalse(body.has("deviceModel"))
        assertFalse(body.has("deviceManufacturer"))
        assertFalse(body.has("anonymousId"))
        assertFalse(body.has("platform"))
    }

    // --- UnsubscribePushSubscriptionDto --------------------------------------

    @Test
    fun `unsubscribe body carries only the installation id`() {
        val body = PushBodies.unsubscribe(INSTALLATION_ID)

        assertEquals(setOf("installationId"), body.keys().asSequence().toSet())
        assertEquals(INSTALLATION_ID, body.getString("installationId"))
    }

    // --- PushEngagementBatchDto ---------------------------------------------------

    @Test
    fun `engagement batch carries the installation id and one element per record`() {
        val body =
            PushBodies.engagements(
                INSTALLATION_ID,
                listOf(
                    record(EngagementEvent.DELIVERED),
                    record(EngagementEvent.DISPLAYED),
                ),
            )

        assertEquals(setOf("installationId", "events"), body.keys().asSequence().toSet())
        assertEquals(INSTALLATION_ID, body.getString("installationId"))
        assertEquals(2, body.getJSONArray("events").length())
    }

    @Test
    fun `engagement element carries exactly the fields the engagement DTO accepts`() {
        val body =
            PushBodies.engagements(
                INSTALLATION_ID,
                listOf(
                    EngagementRecord(
                        messageId = MESSAGE_ID,
                        event = EngagementEvent.CLICKED,
                        timestampMs = FIXED_TIME_MS,
                        signature = "sIgNaTuReSiGnAtUrE22",
                        signatureKeyId = "v1",
                        actionId = "track",
                        deepLink = "myapp://track",
                        suppressionReason = null,
                    ),
                ),
            )

        val event = body.getJSONArray("events").getJSONObject(0)
        assertEquals(
            setOf(
                "messageId",
                "eventType",
                "timestamp",
                "signature",
                "signatureKeyId",
                "actionId",
                "deepLink",
            ),
            event.keys().asSequence().toSet(),
        )
        assertEquals(MESSAGE_ID, event.getString("messageId"))
        assertEquals("clicked", event.getString("eventType"))
        assertEquals("track", event.getString("actionId"))
        assertEquals("myapp://track", event.getString("deepLink"))
    }

    @Test
    fun `engagement timestamp is ISO 8601 in UTC, which is what IsISO8601 accepts`() {
        val event = firstEvent(record(EngagementEvent.DELIVERED))

        assertEquals("2026-08-07T12:00:00Z", event.getString("timestamp"))
    }

    @Test
    fun `engagement omits every optional field the record does not carry`() {
        val event =
            firstEvent(
                EngagementRecord(
                    messageId = MESSAGE_ID,
                    event = EngagementEvent.OPENED,
                    timestampMs = FIXED_TIME_MS,
                    signature = null,
                    signatureKeyId = null,
                    actionId = null,
                    deepLink = null,
                    suppressionReason = null,
                ),
            )

        assertEquals(
            setOf("messageId", "eventType", "timestamp"),
            event.keys().asSequence().toSet(),
        )
    }

    @Test
    fun `engagement deepLink is truncated to the backend's cap rather than failing the batch`() {
        val event =
            firstEvent(
                EngagementRecord(
                    messageId = MESSAGE_ID,
                    event = EngagementEvent.OPENED,
                    timestampMs = FIXED_TIME_MS,
                    signature = null,
                    signatureKeyId = null,
                    actionId = null,
                    deepLink = "myapp://x/" + "a".repeat(EngagementRecord.MAX_DEEP_LINK),
                    suppressionReason = null,
                ),
            )

        assertEquals(EngagementRecord.MAX_DEEP_LINK, event.getString("deepLink").length)
    }

    @Test
    fun `suppressed engagement carries a reason from the backend's allowed list`() {
        val event =
            firstEvent(
                EngagementRecord.of(
                    msg =
                        requireNotNull(
                            ArselPushMessage.fromData(mapOf("arsel_v" to "1", "arsel_mid" to MESSAGE_ID)),
                        ),
                    event = EngagementEvent.SUPPRESSED,
                    suppressionReason = SuppressionReason.CHANNEL_BLOCKED,
                    nowMs = FIXED_TIME_MS,
                ),
            )

        assertEquals("suppressed", event.getString("eventType"))
        assertEquals("channel_blocked", event.getString("suppressionReason"))
    }

    @Test
    fun `every engagement event wire value is one the backend whitelists`() {
        // PUSH_ENGAGEMENT_EVENT_TYPES, verbatim. @IsIn rejects anything else.
        val backendTypes =
            setOf(
                "delivered",
                "displayed",
                "suppressed",
                "opened",
                "clicked",
                "dismissed",
            )

        assertEquals(backendTypes, EngagementEvent.entries.map { it.wire }.toSet())
    }

    @Test
    fun `every suppression reason wire value is one the backend whitelists`() {
        val backendReasons = setOf("permission_denied", "channel_blocked", "app_blocked")

        assertEquals(backendReasons, SuppressionReason.entries.map { it.wire }.toSet())
    }

    @Test
    fun `the batch ceiling matches the backend's`() {
        assertEquals(50, PushBodies.ENGAGEMENT_BATCH_MAX)
    }

    // --- helpers --------------------------------------------------------------

    private fun record(event: EngagementEvent) =
        EngagementRecord(
            messageId = MESSAGE_ID,
            event = event,
            timestampMs = FIXED_TIME_MS,
            signature = null,
            signatureKeyId = null,
            actionId = null,
            deepLink = null,
            suppressionReason = null,
        )

    private fun firstEvent(record: EngagementRecord): JSONObject =
        PushBodies.engagements(INSTALLATION_ID, listOf(record)).getJSONArray("events").getJSONObject(0)

    private companion object {
        const val INSTALLATION_ID = "8f0d9e2a-1c3b-4d5e-8f70-112233445566"
        const val ANONYMOUS_ID = "2c9e4a70-5566-4d5e-8f70-8f0d9e2a1c3b"
        const val DEVICE_TOKEN = "fcm-registration-token"
        const val MESSAGE_ID = "0195c7ac-1111-5222-8333-444455556666"

        /** 2026-08-07T12:00:00Z. */
        const val FIXED_TIME_MS = 1_786_104_000_000L
    }
}
