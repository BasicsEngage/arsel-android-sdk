package sa.arsel.core.internal

/**
 * Client-side shape checks for [sa.arsel.core.Arsel.identify].
 *
 * A malformed identifier must be rejected *before* it is stored: identifiers ride every later
 * event, so a stored bad value turns each of them into a permanent 400 and the user's entire
 * history from that point is dropped — unfixable from a shipped APK.
 */
internal object Identifiers {
    /** E.164, as the backend validates it. */
    private val PHONE = Regex("""^\+[1-9]\d{6,14}$""")

    /** Shape only — real validation is the backend's job; this catches "clearly not an email". */
    private val EMAIL = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")

    fun isValidEmail(value: String): Boolean = EMAIL.matches(value)

    fun isValidPhone(value: String): Boolean = PHONE.matches(value)
}
