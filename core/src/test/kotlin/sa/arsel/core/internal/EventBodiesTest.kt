package sa.arsel.core.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * The body has to match `IngestEventDto` exactly — `forbidNonWhitelisted` turns a stray field into
 * a 400 that loses the event silently, which is the failure this suite exists to prevent.
 */
class EventBodiesTest {
    private fun body(
        name: String = "product.viewed",
        properties: Map<String, Any?> = emptyMap(),
        anonymousId: String = "anon-1",
        externalId: String? = null,
        email: String? = null,
        phoneNumber: String? = null,
        timestampMs: Long = NOW_MS,
    ) = EventBodies.event(name, properties, anonymousId, externalId, email, phoneNumber, timestampMs)

    @Test
    fun `carries the anonymous id even when an external id is present`() {
        // Both, always: the anonymous id is what the backend merges FROM. Dropping it once the user
        // is known would strand every event tracked before they logged in.
        val json = body(externalId = "u_1")

        assertEquals("anon-1", json.getString("anonymous_id"))
        assertEquals("u_1", json.getString("external_id"))
    }

    @Test
    fun `omits unset identifiers rather than sending nulls`() {
        val json = body()

        assertFalse(json.has("external_id"))
        assertFalse(json.has("email"))
        assertFalse(json.has("phone_number"))
    }

    @Test
    fun `sends an empty data object rather than omitting it`() {
        // `data` is @IsObject and required; an absent key is a 400.
        assertTrue(body().has("data"))
        assertEquals(0, body().getJSONObject("data").length())
    }

    @Test
    fun `keeps scalar property types intact`() {
        val json =
            body(
                properties = mapOf("sku" to "A-1", "total" to 149.99, "count" to 3, "vip" to true),
            ).getJSONObject("data")

        assertEquals("A-1", json.getString("sku"))
        assertEquals(149.99, json.getDouble("total"), 0.001)
        assertEquals(3, json.getInt("count"))
        assertTrue(json.getBoolean("vip"))
    }

    @Test
    fun `stringifies values the wire cannot carry instead of dropping them`() {
        val json = body(properties = mapOf("ids" to listOf(1, 2))).getJSONObject("data")

        assertTrue(json.getString("ids").contains("1"))
    }

    @Test
    fun `drops null values and blank keys`() {
        val json =
            body(properties = mapOf("gone" to null, "" to "x", "kept" to "y"))
                .getJSONObject("data")

        assertFalse(json.has("gone"))
        assertFalse(json.has(""))
        assertEquals(1, json.length())
    }

    @Test
    fun `truncates identifiers to the DTO's limits`() {
        val json =
            body(
                name = "n".repeat(200),
                anonymousId = "a".repeat(200),
                externalId = "e".repeat(400),
            )

        assertEquals(EventBodies.MAX_EVENT_NAME, json.getString("event").length)
        assertEquals(EventBodies.MAX_ANONYMOUS_ID, json.getString("anonymous_id").length)
        assertEquals(EventBodies.MAX_EXTERNAL_ID, json.getString("external_id").length)
    }

    @Test
    fun `emits UTC ISO-8601 regardless of the device locale and timezone`() {
        // A Hijri or Thai-Buddhist default calendar would otherwise put a year in the string that
        // @IsISO8601 rejects — on exactly the handsets this SDK's first customer ships to.
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist"))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Bangkok"))

            assertEquals("2026-08-07T12:00:00.000Z", EventBodies.iso8601(NOW_MS))
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }

    private companion object {
        /** 2026-08-07T12:00:00Z. */
        const val NOW_MS = 1_786_104_000_000L
    }
}
