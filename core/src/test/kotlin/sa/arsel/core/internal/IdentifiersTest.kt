package sa.arsel.core.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stored bad identifier turns every subsequent event into a permanent 400, so the rejection has
 * to happen here, client-side, before anything is persisted.
 */
class IdentifiersTest {
    @Test
    fun `plausible emails pass`() {
        assertTrue(Identifiers.isValidEmail("sara@example.com"))
        assertTrue(Identifiers.isValidEmail("sara+tag@sub.example.co"))
    }

    @Test
    fun `things that are clearly not emails are rejected`() {
        assertFalse(Identifiers.isValidEmail("sara"))
        assertFalse(Identifiers.isValidEmail("sara@"))
        assertFalse(Identifiers.isValidEmail("@example.com"))
        assertFalse(Identifiers.isValidEmail("sara@example"))
        assertFalse(Identifiers.isValidEmail("sara @example.com"))
        assertFalse(Identifiers.isValidEmail("sara@@example.com"))
    }

    @Test
    fun `E164 phone numbers pass`() {
        assertTrue(Identifiers.isValidPhone("+966512345678"))
        assertTrue(Identifiers.isValidPhone("+201001234567"))
        assertTrue(Identifiers.isValidPhone("+14155552671"))
    }

    @Test
    fun `non-E164 phone shapes are rejected`() {
        assertFalse("no plus", Identifiers.isValidPhone("966512345678"))
        assertFalse("leading zero", Identifiers.isValidPhone("+0512345678"))
        assertFalse("local format", Identifiers.isValidPhone("0512345678"))
        assertFalse("spaces", Identifiers.isValidPhone("+966 51 234 5678"))
        assertFalse("too short", Identifiers.isValidPhone("+96651"))
        assertFalse("too long", Identifiers.isValidPhone("+9665123456789012"))
        assertFalse("letters", Identifiers.isValidPhone("+96651234abcd"))
    }
}
