package tech.idct.whaaack.data

import java.net.URLDecoder

/**
 * What a `whaaack://auth#…` callback turned out to be.
 *
 * Supabase answers a password-reset or confirmation link in one of two shapes, and the second
 * one used to be dropped on the floor: a link that has expired, been used already, or been
 * opened after a newer one was requested comes back carrying no tokens at all, only
 * `error`, `error_code` and `error_description`. The app read `access_token`, found nothing,
 * and returned — so the player tapped the link in their inbox, watched Whaaack! come to the
 * front, and was shown absolutely nothing. There is no way to tell from the app that the link
 * was the problem, and the natural conclusion is to tap the same dead link again.
 */
sealed interface AuthLink {

    /**
     * Tokens to adopt. [type] is GoTrue's own `type` — `recovery`, `signup`, `email_change` —
     * which decides where the player lands afterwards.
     */
    data class Tokens(
        val accessToken: String,
        val refreshToken: String,
        val expiresInSeconds: Long,
        val type: String?,
    ) : AuthLink

    /** The link cannot be used. [message] is written to be shown to the player as it is. */
    data class Failed(val message: String) : AuthLink

    /** Nothing addressed to auth in it at all. Not a failure; there is simply nothing to do. */
    data object Ignored : AuthLink
}

/**
 * Reads the fragment of a `whaaack://auth` link. Pure, so the cases that matter can be
 * tested without an Activity, a network or a Supabase project.
 *
 * The tokens ride in the URL *fragment* rather than the query, which is deliberate on
 * Supabase's part: a fragment is never sent to a server, so a redirect chain cannot leak
 * them.
 */
internal fun parseAuthFragment(fragment: String?): AuthLink {
    if (fragment.isNullOrBlank()) return AuthLink.Ignored

    val params = fragment
        .split('&')
        .mapNotNull { part ->
            val idx = part.indexOf('=')
            // Only a leading `=` disqualifies a pair; one *inside* a value is ordinary, and
            // base64 padding puts them there.
            if (idx <= 0) null else decode(part.substring(0, idx)) to decode(part.substring(idx + 1))
        }
        .toMap()

    if (params.isEmpty()) return AuthLink.Ignored

    val access = params["access_token"]?.takeIf { it.isNotBlank() }
    val refresh = params["refresh_token"]?.takeIf { it.isNotBlank() }
    if (access != null && refresh != null) {
        return AuthLink.Tokens(
            accessToken = access,
            refreshToken = refresh,
            // A missing or unparseable expires_in is not worth refusing a valid session over.
            // An hour is GoTrue's own default, and the client refreshes ahead of expiry anyway.
            expiresInSeconds = params["expires_in"]?.toLongOrNull() ?: 3600L,
            type = params["type"]?.takeIf { it.isNotBlank() },
        )
    }

    val code = params["error_code"]?.takeIf { it.isNotBlank() }
    val description = params["error_description"]?.takeIf { it.isNotBlank() }
    val error = params["error"]?.takeIf { it.isNotBlank() }
    if (code == null && description == null && error == null && access == null && refresh == null) {
        // A `whaaack://auth` link with a fragment that is about something else entirely.
        return AuthLink.Ignored
    }

    return AuthLink.Failed(failureMessage(code, description))
}

/**
 * The server's own words where they are usable, ours where they are not.
 *
 * `otp_expired` is by far the most common of these and the only one with a specific way out,
 * so it gets copy that names it: the same link cannot be re-opened, a new one has to be
 * requested, and the *newest* email is the one that works — requesting a second reset
 * invalidates the first, which is its own way of arriving here.
 */
private fun failureMessage(code: String?, description: String?): String {
    val expired = code.equals("otp_expired", ignoreCase = true) ||
        description?.contains("expired", ignoreCase = true) == true
    return when {
        expired ->
            "That link has expired or was already used. Ask for a new one, and open the " +
                "newest email — an older link stops working as soon as you request another."

        description != null -> "$description — ask for a new link, or sign in with your password."

        else -> "That link couldn't be used. Ask for a new one, or sign in with your password."
    }
}

/**
 * Fragment values arrive percent-encoded — `error_description` in particular reads as
 * gibberish without this, and `+` for a space is exactly how Supabase sends it. Safe for the
 * tokens too: they are base64url, whose alphabet contains neither `%` nor `+`.
 */
private fun decode(value: String): String =
    runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
