package tech.idct.whaaack.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `whaaack://auth#…` callback, and in particular the half of it that used to be dropped.
 *
 * A password-reset link answers in one of two shapes. The app only ever read the first, so an
 * expired or already-used link — which carries an error and no tokens — brought Whaaack! to
 * the front and then did nothing at all, leaving the player to conclude the app was broken and
 * tap the same dead link again.
 */
class AuthLinkTest {

    @Test
    fun `a recovery link is adopted, type and all`() {
        val link = parseAuthFragment(
            "access_token=abc.def.ghi&expires_in=3600&refresh_token=r3fr3sh&token_type=bearer&type=recovery",
        )
        val tokens = link as AuthLink.Tokens
        assertEquals("abc.def.ghi", tokens.accessToken)
        assertEquals("r3fr3sh", tokens.refreshToken)
        assertEquals(3600L, tokens.expiresInSeconds)
        assertEquals("recovery", tokens.type)
    }

    @Test
    fun `an expired link says so instead of saying nothing`() {
        // Exactly what Supabase redirects with when a reset link has aged out or been
        // superseded: no tokens at all, and `+` for the spaces.
        val link = parseAuthFragment(
            "error=access_denied&error_code=otp_expired" +
                "&error_description=Email+link+is+invalid+or+has+expired",
        )
        val failed = link as AuthLink.Failed
        val message = failed.message.lowercase()
        assertTrue("should name the cause: $message", "expired" in message)
        assertTrue("should say what to do: $message", "new one" in message)
        assertTrue("should point at the newest email: $message", "newest" in message)
    }

    @Test
    fun `an error we have no copy for keeps the server's own words`() {
        val failed = parseAuthFragment(
            "error=server_error&error_description=Database+error+saving+new+user",
        ) as AuthLink.Failed
        assertTrue(failed.message.startsWith("Database error saving new user"))
    }

    @Test
    fun `an error with no description still tells the player something`() {
        val failed = parseAuthFragment("error=access_denied") as AuthLink.Failed
        assertTrue(failed.message.isNotBlank())
        assertTrue("sign in with your password" in failed.message)
    }

    @Test
    fun `half a link is a failure, not a silent no-op`() {
        // A token pair with one half missing cannot produce a session, and returning early
        // is the same dead end as the expired case.
        assertTrue(parseAuthFragment("access_token=abc&type=recovery") is AuthLink.Failed)
        assertTrue(parseAuthFragment("refresh_token=r3fr3sh") is AuthLink.Failed)
        assertTrue(parseAuthFragment("access_token=&refresh_token=r3fr3sh") is AuthLink.Failed)
    }

    @Test
    fun `nothing addressed to auth is ignored rather than reported`() {
        // Not every `whaaack://` link is a failure; one with nothing in it is simply nothing.
        assertEquals(AuthLink.Ignored, parseAuthFragment(null))
        assertEquals(AuthLink.Ignored, parseAuthFragment(""))
        assertEquals(AuthLink.Ignored, parseAuthFragment("   "))
        assertEquals(AuthLink.Ignored, parseAuthFragment("=novalue&&"))
        assertEquals(AuthLink.Ignored, parseAuthFragment("utm_source=newsletter"))
    }

    @Test
    fun `a missing expires_in falls back to GoTrue's own default`() {
        val tokens = parseAuthFragment("access_token=a&refresh_token=b") as AuthLink.Tokens
        assertEquals(3600L, tokens.expiresInSeconds)
        assertEquals(null, tokens.type)

        val garbled = parseAuthFragment(
            "access_token=a&refresh_token=b&expires_in=soon",
        ) as AuthLink.Tokens
        assertEquals(3600L, garbled.expiresInSeconds)
    }

    @Test
    fun `an equals sign inside a value belongs to the value`() {
        // Base64 padding puts them there, and splitting on every `=` would truncate the token.
        val tokens = parseAuthFragment(
            "access_token=header.payload.sig==&refresh_token=r3==",
        ) as AuthLink.Tokens
        assertEquals("header.payload.sig==", tokens.accessToken)
        assertEquals("r3==", tokens.refreshToken)
    }

    @Test
    fun `percent-encoding is undone before anything reads a value`() {
        val failed = parseAuthFragment(
            "error_description=Token%20has%20expired%20or%20is%20invalid",
        ) as AuthLink.Failed
        assertTrue("expired" in failed.message)
        assertTrue("%20" !in failed.message)
    }
}
