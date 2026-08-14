package tech.idct.whaaack.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

data class Player(
    val userId: String,
    val email: String?,
    val displayName: String,
    val provider: String,
) {
    val initial: String get() = displayName.trim().firstOrNull()?.uppercase() ?: "?"
    val isGoogle: Boolean get() = provider.equals("google", ignoreCase = true)
}

/**
 * Human-readable failures, so the UI can show the design's error banners rather than a
 * raw HTTP message.
 */
sealed class AuthError(val title: String, val body: String) {
    data object EmailTaken : AuthError(
        "That email is already registered",
        "Sign in with your password, or use Continue with Google if that's how you joined.",
    )

    data object WrongPassword : AuthError(
        "Wrong email or password",
        "Check both, or reset your password below.",
    )

    data object NameTaken : AuthError(
        "Display name unavailable",
        "Someone on the leaderboard already uses it.",
    )

    data object WeakPassword : AuthError(
        "Password too weak",
        "Use at least 8 characters and one number.",
    )

    data object BadEmail : AuthError(
        "Check that email address",
        "It's missing an @ or a domain.",
    )

    data object NotConfirmed : AuthError(
        "Confirm your email first",
        "We sent you a link when you signed up — open it, then sign in.",
    )

    data object NameCooldown : AuthError(
        "Too soon to rename",
        "You can change your display name once every 30 days.",
    )

    data object Offline : AuthError(
        "No connection",
        "Whaaack! couldn't reach the server. Your casual runs still work offline.",
    )

    class Unexpected(body: String) : AuthError("Something went wrong", body)
}

class AuthResultException(val error: AuthError) : Exception(error.title)

class AuthRepository(private val client: SupabaseClient) {

    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    val isConfigured: Boolean get() = client.isConfigured

    /**
     * Settles signed-in vs signed-out from the persisted session alone, and returns whether
     * one was adopted.
     *
     * Deliberately a local read. The launch screen has to choose between the ranked buttons
     * and the signed-out ones, and resolving that through a profile round trip meant showing
     * the wrong pair for as long as the network took. [refreshProfile] catches the details
     * up afterwards. Safe to call when signed out.
     */
    suspend fun restore(): Boolean {
        if (!client.isConfigured) return false
        val session = client.currentSession() ?: return false
        _player.value = session.toPlayer()
        return true
    }

    /**
     * Replaces the session-derived player with the profiles row.
     *
     * Only an outright rejection signs the player out: a request that merely fails means the
     * cached name is stale, not that the account is gone, so a launch with no connection
     * keeps the session instead of silently demoting the player to signed-out.
     */
    suspend fun refreshProfile() {
        val session = client.currentSession() ?: return
        try {
            loadProfile(session)
        } catch (e: SupabaseClient.SupabaseException) {
            // A 401 here has already survived one refresh attempt inside the client, so the
            // session cannot be recovered.
            if (e.status == 401) {
                client.clearSession()
                _player.value = null
            }
        } catch (_: java.io.IOException) {
            // Offline; keep what the persisted session told us.
            return
        }
        // A refresh token the server rejects makes the client drop the session mid-request,
        // and the retry then goes out with the anon key — which RLS answers with an empty
        // row set rather than a 401. Without this the player would look signed in with no
        // session behind them.
        if (client.currentSession() == null) _player.value = null
    }

    suspend fun signUpWithEmail(email: String, password: String, displayName: String) {
        require(client.isConfigured) { "Supabase is not configured" }
        validate(email, password)

        val payload = buildJsonObject {
            put("email", JsonPrimitive(email.trim()))
            put("password", JsonPrimitive(password))
            put(
                "data",
                buildJsonObject { put("display_name", JsonPrimitive(displayName.trim())) },
            )
        }
        val raw = call { client.request("POST", "/auth/v1/signup", payload) }

        // With email confirmation on, signup returns a user but no session.
        val session = Session.fromTokenResponse(client.json, raw)
        if (session != null) {
            client.saveSession(session)
            loadProfile(session)
        }
    }

    suspend fun signInWithEmail(email: String, password: String) {
        require(client.isConfigured) { "Supabase is not configured" }
        val payload = buildJsonObject {
            put("email", JsonPrimitive(email.trim()))
            put("password", JsonPrimitive(password))
        }
        val raw = call { client.request("POST", "/auth/v1/token?grant_type=password", payload) }
        val session = Session.fromTokenResponse(client.json, raw)
            ?: throw AuthResultException(AuthError.Unexpected("No session returned."))
        client.saveSession(session)
        loadProfile(session)
    }

    /**
     * Exchanges a Google ID token from Credential Manager for a Supabase session.
     * [nonce] must be the raw (un-hashed) value that was hashed into the Google request.
     */
    suspend fun signInWithGoogle(idToken: String, nonce: String?) {
        require(client.isConfigured) { "Supabase is not configured" }
        val payload = buildJsonObject {
            put("provider", JsonPrimitive("google"))
            put("id_token", JsonPrimitive(idToken))
            if (nonce != null) put("nonce", JsonPrimitive(nonce))
        }
        val raw = call { client.request("POST", "/auth/v1/token?grant_type=id_token", payload) }
        val session = Session.fromTokenResponse(client.json, raw)
            ?: throw AuthResultException(AuthError.Unexpected("No session returned."))
        client.saveSession(session)
        loadProfile(session)
    }

    suspend fun sendPasswordReset(email: String) {
        require(client.isConfigured) { "Supabase is not configured" }
        val payload = buildJsonObject { put("email", JsonPrimitive(email.trim())) }
        call {
            client.request(
                "POST",
                "/auth/v1/recover?redirect_to=whaaack://auth",
                payload,
            )
        }
    }

    suspend fun signOut() {
        runCatching { client.request("POST", "/auth/v1/logout", authorized = true) }
        client.clearSession()
        _player.value = null
    }

    suspend fun updateDisplayName(newName: String) {
        val session = client.currentSession() ?: return
        val payload = buildJsonObject { put("display_name", JsonPrimitive(newName.trim())) }
        call {
            client.request(
                "PATCH",
                "/rest/v1/profiles?id=eq.${session.userId}",
                payload,
                authorized = true,
                extraHeaders = mapOf("Prefer" to "return=representation"),
            )
        }
        _player.value = _player.value?.copy(displayName = newName.trim())
        // Keep the launch-time cache in step, or the next start shows the old name until
        // the profile request lands.
        client.cacheDisplayName(newName.trim())
    }

    suspend fun updateEmail(newEmail: String) {
        val payload = buildJsonObject { put("email", JsonPrimitive(newEmail.trim())) }
        call { client.request("PUT", "/auth/v1/user", payload, authorized = true) }
    }

    suspend fun updatePassword(newPassword: String) {
        if (!isStrongPassword(newPassword)) throw AuthResultException(AuthError.WeakPassword)
        val payload = buildJsonObject { put("password", JsonPrimitive(newPassword)) }
        call { client.request("PUT", "/auth/v1/user", payload, authorized = true) }
    }

    suspend fun deleteAccount() {
        call { client.request("POST", "/rest/v1/rpc/delete_my_account", authorized = true) }
        client.clearSession()
        _player.value = null
    }

    // ---- internals -----------------------------------------------------------------

    private suspend fun loadProfile(session: Session) {
        val raw = client.request(
            "GET",
            "/rest/v1/profiles?id=eq.${session.userId}&select=display_name,provider",
            authorized = true,
        )
        val row = client.json.parseToJsonElement(raw).jsonArray.firstOrNull()?.jsonObject
        val player = session.toPlayer(
            // The signup trigger always writes one, but fall back rather than crash.
            displayName = row?.str("display_name"),
            provider = row?.str("provider"),
        )
        _player.value = player
        // Cache it so the next launch can name the player before the network answers.
        if (player.displayName != session.displayName) {
            client.cacheDisplayName(player.displayName)
        }
    }

    /**
     * The player as the session alone describes them, with [displayName] and [provider]
     * overriding the cached values when the profiles row has answered.
     */
    private fun Session.toPlayer(displayName: String? = null, provider: String? = null) = Player(
        userId = userId,
        email = email,
        displayName = displayName ?: this.displayName ?: email?.substringBefore('@')
            ?: "Player",
        provider = provider ?: this.provider,
    )

    private fun validate(email: String, password: String) {
        if (!email.contains('@') || email.substringAfter('@').length < 3) {
            throw AuthResultException(AuthError.BadEmail)
        }
        if (!isStrongPassword(password)) throw AuthResultException(AuthError.WeakPassword)
    }

    private fun isStrongPassword(password: String) =
        password.length >= 8 && password.any { it.isDigit() }

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: SupabaseClient.SupabaseException) {
        throw AuthResultException(translate(e))
    } catch (e: java.io.IOException) {
        throw AuthResultException(AuthError.Offline)
    }

    private fun translate(e: SupabaseClient.SupabaseException): AuthError {
        val text = e.message.lowercase()
        val code = e.errorCode?.lowercase().orEmpty()
        return when {
            code == "user_already_exists" || text.contains("already registered") ||
                text.contains("already been registered") -> AuthError.EmailTaken

            code == "email_not_confirmed" || text.contains("not confirmed") ->
                AuthError.NotConfirmed

            code == "invalid_credentials" || text.contains("invalid login") ->
                AuthError.WrongPassword

            code == "weak_password" || text.contains("password should be") ->
                AuthError.WeakPassword

            text.contains("display_name_cooldown") -> AuthError.NameCooldown

            // 23505 is Postgres' unique-violation; here it can only be the display name.
            code == "23505" || text.contains("duplicate key") -> AuthError.NameTaken

            text.contains("invalid email") || text.contains("unable to validate email") ->
                AuthError.BadEmail

            else -> AuthError.Unexpected(e.message)
        }
    }
}
