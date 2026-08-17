package tech.idct.whaaack.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client half of the password policy, pinned against the server half.
 *
 * This rule used to live only here, so a direct POST to /auth/v1/signup with the publishable
 * key from the APK created accounts under GoTrue's own six-character floor with no character
 * requirement at all. supabase/config.toml now sets `minimum_password_length = 8` and
 * `password_requirements = "letters_digits"`, and the point of these cases is that the two
 * halves say the same thing: anything this function accepts, the server accepts, and anything
 * it refuses, the server would have refused too.
 *
 * The direction that matters is a client *looser* than the server — that is the one the player
 * feels, as a password the form just approved coming back rejected.
 */
class PasswordPolicyTest {

    @Test
    fun `eight characters with a letter and a digit is the floor`() {
        assertTrue(isStrongPassword("passw0rd"))
        assertTrue(isStrongPassword("Whaaack1"))
    }

    @Test
    fun `seven characters is short whatever it contains`() {
        assertFalse(isStrongPassword("passw0r"))
        assertFalse(isStrongPassword("Aa1"))
        assertFalse(isStrongPassword(""))
    }

    @Test
    fun `a digit is required`() {
        assertFalse(isStrongPassword("password"))
        assertFalse(isStrongPassword("aVeryLongButDigitlessOne"))
    }

    /**
     * The clause added alongside the server policy, and the only reason it exists: GoTrue's
     * `letters_digits` wants a letter too, so an all-digit password the old rule waved through
     * would have been accepted by the form and then refused by the server.
     */
    @Test
    fun `all digits is refused here because the server refuses it`() {
        assertFalse(isStrongPassword("12345678"))
        assertFalse(isStrongPassword("0000000000000000"))
    }

    @Test
    fun `length counts characters, not letters — symbols are allowed to make up the rest`() {
        assertTrue(isStrongPassword("a1!!!!!!"))
        assertTrue(isStrongPassword("correct horse battery staple 1"))
    }
}
