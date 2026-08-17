package sa.arsel.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests for the inbound FCM data payload.
 *
 * [GoldenEnvelopes] carries the exact `data` maps the backend emits, transcribed from
 * `push-envelope.builder.spec.ts.snap`. Everything else here is behaviour the wire contract
 * requires but no single snapshot happens to exercise.
 */
class ArselPushMessageTest {
    @Test
    fun `claims an envelope on arsel_v`() {
        assertTrue(ArselPushMessage.isArselData(mapOf("arsel_v" to "1")))
    }

    @Test
    fun `claims an envelope on arsel_mid when the version key is absent`() {
        assertTrue(ArselPushMessage.isArselData(mapOf("arsel_mid" to "m-1")))
    }

    @Test
    fun `does not claim a host app's own data message`() {
        val data = mapOf("title" to "Host push", "body" to "not ours", "order_id" to "42")

        assertFalse(ArselPushMessage.isArselData(data))
        assertNull(ArselPushMessage.fromData(data))
    }

    @Test
    fun `does not claim an envelope whose marker keys are blank`() {
        assertFalse(ArselPushMessage.isArselData(mapOf("arsel_v" to "", "arsel_mid" to "")))
    }

    @Test
    fun `returns null for a claimed envelope carrying no message id`() {
        // The backend's credential probe is exactly this: arsel_v and nothing else. It is a real
        // send to a real token, so the SDK must recognise it and decline to render it.
        assertNull(ArselPushMessage.fromData(GoldenEnvelopes.CREDENTIAL_PROBE))
    }

    @Test
    fun `reads every arsel key from a full envelope`() {
        val msg = requireNotNull(ArselPushMessage.fromData(GoldenEnvelopes.FULL))

        assertEquals("1", msg.wireVersion)
        assertEquals(GoldenEnvelopes.MESSAGE_ID, msg.messageId)
        assertEquals(GoldenEnvelopes.SIGNATURE, msg.signature)
        assertEquals("v1", msg.keyId)
        assertEquals("Your order shipped", msg.title)
        assertEquals("Track it in the app.", msg.body)
        assertEquals("https://cdn.example.com/hero.png", msg.imageUrl)
        assertEquals("myapp://orders/42", msg.deepLink)
        assertEquals("orders", msg.channelId)
        assertEquals("campaign-42", msg.collapseId)
    }

    @Test
    fun `parses arsel_actions including a button with a null deep link`() {
        val msg = requireNotNull(ArselPushMessage.fromData(GoldenEnvelopes.ANDROID_SDK_WITH_ACTIONS))

        assertEquals(2, msg.actions.size)
        assertEquals("track", msg.actions[0].actionId)
        assertEquals("Track", msg.actions[0].label)
        assertEquals("myapp://track", msg.actions[0].deepLink)
        assertEquals("help", msg.actions[1].actionId)
        assertEquals("Help", msg.actions[1].label)
        // The backend serialises an absent deep link as JSON null, and `optString` renders that as
        // the literal string "null" — the exact bug the parser's isNull() guard exists for.
        assertNull(msg.actions[1].deepLink)
    }

    @Test
    fun `degrades to no buttons when arsel_actions is malformed`() {
        val msg =
            requireNotNull(
                ArselPushMessage.fromData(GoldenEnvelopes.ANDROID_SDK_BASIC + ("arsel_actions" to "{oops")),
            )

        assertEquals(emptyList<ArselPushAction>(), msg.actions)
        assertEquals("Your order shipped", msg.title)
    }

    @Test
    fun `retains the customer's own data payload as hostData`() {
        val msg = requireNotNull(ArselPushMessage.fromData(GoldenEnvelopes.ANDROID_SDK_WITH_ACTIONS))

        assertEquals(mapOf("order_id" to "42"), msg.hostData)
    }

    @Test
    fun `host keys never shadow arsel keys`() {
        // A dataPayload key that merely looks like ours must not reach hostData, and must not be
        // able to overwrite a parsed field either.
        val data =
            GoldenEnvelopes.ANDROID_SDK_BASIC +
                mapOf(
                    "arsel_title" to "Your order shipped",
                    "title" to "host title",
                    "messageId" to "host-message-id",
                )
        val msg = requireNotNull(ArselPushMessage.fromData(data))

        assertEquals("Your order shipped", msg.title)
        assertEquals(GoldenEnvelopes.MESSAGE_ID, msg.messageId)
        assertEquals(mapOf("title" to "host title", "messageId" to "host-message-id"), msg.hostData)
        assertTrue(msg.hostData.keys.none { it.startsWith("arsel_") })
    }

    @Test
    fun `parses the golden SDK-equipped Android envelope`() {
        val msg = requireNotNull(ArselPushMessage.fromData(GoldenEnvelopes.ANDROID_SDK_BASIC))

        assertEquals(GoldenEnvelopes.MESSAGE_ID, msg.messageId)
        assertEquals("Your order shipped", msg.title)
        assertEquals("Track it in the app.", msg.body)
        assertNull(msg.imageUrl)
        assertNull(msg.deepLink)
        assertNull(msg.channelId)
        assertNull(msg.collapseId)
        assertEquals(emptyList<ArselPushAction>(), msg.actions)
        assertEquals(emptyMap<String, String>(), msg.hostData)
    }

    @Test
    fun `parses the golden Path-A envelope that carries identity only`() {
        // A device without the SDK gets a notification block and identity-only data. If the SDK is
        // installed later it must still be able to claim and attribute such a message.
        val msg = requireNotNull(ArselPushMessage.fromData(GoldenEnvelopes.ANDROID_PATH_A))

        assertEquals(GoldenEnvelopes.MESSAGE_ID, msg.messageId)
        assertEquals("campaign-42", msg.collapseId)
        assertNull(msg.title)
        assertNull(msg.body)
    }

    @Test
    fun `parses the golden SDK-equipped iOS envelope`() {
        val msg = requireNotNull(ArselPushMessage.fromData(GoldenEnvelopes.IOS_SDK))

        assertEquals("myapp://orders/42", msg.deepLink)
        assertEquals("Your order shipped", msg.title)
    }

    @Test
    fun `toString carries no customer content`() {
        val msg = requireNotNull(ArselPushMessage.fromData(GoldenEnvelopes.FULL))
        val rendered = msg.toString()

        assertFalse(rendered.contains("Your order shipped"))
        assertFalse(rendered.contains("Track it in the app."))
        assertFalse(rendered.contains("myapp://orders/42"))
    }
}
