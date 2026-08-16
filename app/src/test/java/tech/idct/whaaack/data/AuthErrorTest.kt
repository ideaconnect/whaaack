package tech.idct.whaaack.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's answer, turned into something a player can act on.
 *
 * This mapping is the whole reason a returning player could not change their password: GoTrue
 * refuses a password change on a session older than a day with `reauthentication_needed`, and
 * with no case for it that surfaced as "Something went wrong" — on the one screen the player
 * had just been told to use, with no hint that signing out and back in would fix it.
 */
class AuthErrorTest {

    private fun map(code: String?, message: String = "") = translateAuthError(code, message)

    @Test
    fun `a stale session is named, not swallowed into Something went wrong`() {
        // Exactly what the live project returns — captured from it, not invented:
        // HTTP 400, error_code reauthentication_needed, "Password update requires reauthentication".
        assertEquals(
            AuthError.NeedsRecentSignIn,
            map("reauthentication_needed", "Password update requires reauthentication"),
        )
        // And by message alone, in case the code is ever absent.
        assertEquals(
            AuthError.NeedsRecentSignIn,
            map(null, "Password update requires reauthentication"),
        )
    }

    @Test
    fun `the stale-session copy tells the player what to actually do`() {
        // A message that names a problem without naming the way out is not much better than
        // the generic one it replaced. Signing out mints a fresh session, which is the fix.
        val body = AuthError.NeedsRecentSignIn.body.lowercase()
        assertTrue("copy should mention signing out: $body", "log out" in body || "sign out" in body)
        assertTrue("copy should mention signing back in: $body", "sign back in" in body)
    }

    @Test
    fun `the codes the server actually sends each land somewhere useful`() {
        assertEquals(AuthError.EmailTaken, map("user_already_exists"))
        assertEquals(AuthError.NotConfirmed, map("email_not_confirmed"))
        assertEquals(AuthError.WrongPassword, map("invalid_credentials"))
        assertEquals(AuthError.WeakPassword, map("weak_password"))
        assertEquals(AuthError.NameTaken, map("23505"))
        assertEquals(AuthError.NameCooldown, map(null, "display_name_cooldown"))
    }

    @Test
    fun `matching is case-insensitive on both the code and the message`() {
        assertEquals(AuthError.NeedsRecentSignIn, map("REAUTHENTICATION_NEEDED"))
        assertEquals(AuthError.NeedsRecentSignIn, map(null, "Password Update Requires Reauthentication"))
        assertEquals(AuthError.EmailTaken, map(null, "User Already Registered"))
    }

    @Test
    fun `anything unrecognised keeps the server's own words rather than inventing any`() {
        val e = map("some_future_code", "The orchard is closed on Tuesdays")
        assertTrue(e is AuthError.Unexpected)
        assertEquals("The orchard is closed on Tuesdays", e.body)
    }

    @Test
    fun `re-using the current password is named, not reported as too weak`() {
        // GoTrue's message — "New password should be different from the old password." —
        // contains the exact substring the weak-password mapping matches on, so without a
        // branch of its own this told a player their perfectly strong password was weak.
        assertEquals(AuthError.SamePassword, map("same_password"))
        assertEquals(
            AuthError.SamePassword,
            map(null, "New password should be different from the old password."),
        )
        // And the weak-password mapping still catches its own message.
        assertEquals(AuthError.WeakPassword, map(null, "Password should be at least 8 characters"))
    }

    @Test
    fun `a session that died mid-call is a sign-out, not a schema complaint`() {
        // Minted by SupabaseClient when the store empties while a request is in flight.
        // Before the mapping, this surfaced as raw "permission denied for table profiles".
        assertEquals(AuthError.SessionExpired, map("session_missing"))
    }
}
