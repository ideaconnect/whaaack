package tech.idct.whaaack.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
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

    /**
     * Minted from a Play Games identity rather than signed up for. The address on such an
     * account is a derived identifier at an unroutable domain, not a mailbox — so nothing may
     * offer to mail it, and "your email" must never be shown as though it were one.
     */
    val isPlayGames: Boolean get() = provider.equals("play_games", ignoreCase = true)

    /**
     * Whether this account has a password at all. Both federated kinds are held by the
     * provider, and offering "Change password" for one is a row that can only ever fail —
     * which is exactly what Settings used to do, gated on Google alone.
     */
    val hasPassword: Boolean get() = !isGoogle && !isPlayGames
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

    /**
     * The name breaks `display_name_length` or `display_name_shape`. Raised by [DisplayName]
     * before the request goes out, because the same rejection arriving from Postgres is a
     * 23514 carrying our constraint's name and nothing a player can act on — and the mapping
     * below has no branch for it, so it reached the rename sheet verbatim.
     */
    class NameInvalid(body: String) : AuthError("Check that display name", body)

    /**
     * A rename that addressed no row: the account has no `profiles` row to change.
     *
     * The same shape of bug as [SessionExpired] and worth naming for the same reason. PostgREST
     * answers a PATCH matching nothing with 200 and an empty array, not an error, so the call
     * returned cleanly, the ViewModel counted it a success and toasted "Display name updated"
     * over a leaderboard that still shows the old name — every time, for ever. The copy does
     * not promise a fix, because there isn't one the player can perform; it only stops the app
     * claiming the change happened.
     */
    data object ProfileMissing : AuthError(
        "Couldn't save that name",
        "We couldn't find your player profile, so nothing was changed. Try again in a moment.",
    )

    data object WeakPassword : AuthError(
        "Password too weak",
        "Use at least 8 characters and one number.",
    )

    /**
     * GoTrue's `same_password`: the "new" password is the current one. Its message — "New
     * password should be different from the old password." — contains the substring the
     * weak-password mapping matches on, so without a branch of its own it was reported as
     * "Password too weak" to a player whose password met every rule.
     */
    data object SamePassword : AuthError(
        "That's already your password",
        "The new password must be different from your current one.",
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

    /**
     * There is no session behind this request at all — the refresh token was rejected and the
     * client dropped it, or the player signed out in another window of the same process.
     *
     * Distinct from [NeedsRecentSignIn], which is a session that exists and is merely too old
     * for one particular operation. Naming it matters because the alternative was worse than a
     * bad message: an account change with no session used to return without doing anything and
     * without saying anything, so [tech.idct.whaaack.WhaaackViewModel] counted it a success and
     * toasted "Display name updated" over a server that had never been asked.
     */
    data object SessionExpired : AuthError(
        "You're signed out",
        "Your session ended. Sign in again, then make the change.",
    )

    /**
     * GoTrue's `secure_password_change` refuses a password change on a session older than a
     * day unless it carries a reauthentication nonce. The app sends no nonce, so this is what
     * a returning player gets — and the copy has to be actionable, because signing out and
     * back in mints a fresh session and makes the change work immediately.
     */
    data object NeedsRecentSignIn : AuthError(
        "Sign in again first",
        "For your security this change needs a fresh sign-in. Log out, sign back in, and try again.",
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
        // Checked here rather than left to the trigger, which sanitises instead of refusing:
        // a name of nothing but non-ASCII sails through signup and lands as "Player", and a
        // player who typed one deserves to be told before the account exists rather than to
        // discover it on the leaderboard. Uniqueness is still the trigger's to resolve.
        DisplayName.validate(displayName)?.let { throw AuthResultException(it) }

        val payload = buildJsonObject {
            put("email", JsonPrimitive(email.trim()))
            put("password", JsonPrimitive(password))
            put(
                "data",
                buildJsonObject {
                    put("display_name", JsonPrimitive(DisplayName.normalize(displayName)))
                },
            )
        }
        val raw = call { client.request("POST", "/auth/v1/signup", payload) }

        // With email confirmation on, signup returns a user but no session.
        val session = Session.fromTokenResponse(client.json, raw)
        if (session != null) {
            adoptSession(session)
            return
        }

        // No session, so this is either "confirm your inbox" or GoTrue's enumeration
        // shield. With confirmations on, signing up an address that already has a confirmed
        // account does NOT return the 422 the EmailTaken mapping waits for — that would
        // confirm the address exists to anyone who asks. It returns 200 with a fake,
        // obfuscated user whose `identities` array is empty, and sends no mail. Reading that
        // as "confirmation required" told the player to watch an inbox nothing was ever
        // going to arrive in. The empty array is GoTrue's own documented tell, and we are
        // allowed to use it where an attacker is not: this caller just proved they can send
        // *us* the address either way.
        val identities = runCatching {
            (client.json.parseToJsonElement(raw) as? JsonObject)?.get("identities")?.jsonArray
        }.getOrNull()
        if (identities != null && identities.isEmpty()) {
            throw AuthResultException(AuthError.EmailTaken)
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
        adoptSession(session)
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
        adoptSession(session)
    }

    /**
     * Exchanges a Play Games server auth code for a session, creating the account the first
     * time a player does this.
     *
     * The code goes to an Edge Function rather than to GoTrue, because Supabase has no grant
     * for "a Play Games player": proving the code belongs to somebody needs the web client's
     * secret, and the function is the only place that can hold one. What comes back is an
     * ordinary GoTrue token response, so it is parsed by the same [Session.fromTokenResponse]
     * as every other grant — see supabase/functions/play-games-auth.
     */
    suspend fun signInWithPlayGames(serverAuthCode: String) {
        require(client.isConfigured) { "Supabase is not configured" }
        val payload = buildJsonObject { put("code", JsonPrimitive(serverAuthCode)) }
        val raw = call { client.request("POST", "/functions/v1/play-games-auth", payload) }
        val session = Session.fromTokenResponse(client.json, raw)
            ?: throw AuthResultException(AuthError.Unexpected("No session returned."))
        adoptSession(session)
    }

    /**
     * The moment a sign-in is *true* is the moment the session is saved — everything after
     * that is decoration, and must not be allowed to contradict it.
     *
     * The previous shape — save, then let [loadProfile]'s network round trip throw out of the
     * sign-in — reported "sign-in failed" over a session already persisted to disk. The player
     * was told nothing happened; the next cold start silently signed them in to the account
     * they were told was never created, and in between, the standing fetch could stamp that
     * account's rank into a UI claiming nobody was signed in. Worst on the Play Games path,
     * where the failed-looking attempt *minted* an account the player has no idea exists.
     *
     * So: publish the session-derived player immediately — exactly what [restore] does on
     * every launch — and let the profile round trip be the best-effort catch-up it already is
     * everywhere else ([refreshProfile] treats its failure as "stale name", not "no account").
     */
    private suspend fun adoptSession(session: Session) {
        client.saveSession(session)
        _player.value = session.toPlayer()
        runCatching { loadProfile(session) }
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

    /**
     * Renames the player, or explains why not.
     *
     * Unlike signup, this never picks a different name than the one asked for. A player typing
     * into the rename sheet has a specific name in mind, and handing them "Zenek2" because
     * "Zenek" was gone would be answering a question they did not ask — so a collision comes
     * back as [AuthError.NameTaken] and they choose again. The automatic numbering belongs to
     * `handle_new_user`, where nobody is at the keyboard to be asked.
     */
    suspend fun updateDisplayName(newName: String) {
        val session = requireSession()
        // Before the request, not after: the database's own rejection of a malformed name is a
        // 23514 naming a check constraint, which is not something to show anybody.
        DisplayName.validate(newName)?.let { throw AuthResultException(it) }
        val name = DisplayName.normalize(newName)
        val payload = buildJsonObject { put("display_name", JsonPrimitive(name)) }
        val raw = call {
            client.request(
                "PATCH",
                "/rest/v1/profiles?id=eq.${session.userId}",
                payload,
                authorized = true,
                extraHeaders = mapOf("Prefer" to "return=representation"),
            )
        }

        // `return=representation` was already being asked for and the answer already being
        // thrown away, which is what let this fail in silence: PostgREST reports a PATCH that
        // matched no rows as 200 with `[]`, not as an error, so an account with no profiles
        // row — the old signup trigger's last resort could leave one, and so can any account
        // predating the trigger — took the rename, said "Display name updated", and kept the
        // name it had. Reading the row back is the whole check.
        //
        // Only a body that parses as an *empty array* is that failure. A body that does not
        // parse as an array at all is a different and much weaker claim — a 204 from a
        // PostgREST that ignored the Prefer header, a proxy rewriting the response — and
        // reading it as "nothing was changed" would invent the opposite lie: a rename that
        // worked, reported as a failure, with the player renaming again over a name already
        // taken by themselves.
        val rows = runCatching { client.json.parseToJsonElement(raw).jsonArray }.getOrNull()
        if (rows != null && rows.isEmpty()) throw AuthResultException(AuthError.ProfileMissing)

        // The stored name rather than the requested one. They are the same today — nothing
        // server-side rewrites a rename — but the row that came back is the fact, and the
        // cache below is what the next cold start believes before the network answers.
        val stored = rows?.firstOrNull()?.jsonObject?.str("display_name") ?: name
        _player.value = _player.value?.copy(displayName = stored)
        // Keep the launch-time cache in step, or the next start shows the old name until
        // the profile request lands.
        client.cacheDisplayName(stored)
    }

    suspend fun updateEmail(newEmail: String) {
        requireSession()
        val payload = buildJsonObject { put("email", JsonPrimitive(newEmail.trim())) }
        call { client.request("PUT", "/auth/v1/user", payload, authorized = true) }
    }

    suspend fun updatePassword(newPassword: String) {
        if (!isStrongPassword(newPassword)) throw AuthResultException(AuthError.WeakPassword)
        requireSession()
        val payload = buildJsonObject { put("password", JsonPrimitive(newPassword)) }
        call { client.request("PUT", "/auth/v1/user", payload, authorized = true) }
    }

    suspend fun deleteAccount() {
        requireSession()
        call { client.request("POST", "/rest/v1/rpc/delete_my_account", authorized = true) }
        client.clearSession()
        _player.value = null
    }

    // ---- internals -----------------------------------------------------------------

    /**
     * The session every account change needs, or a refusal the player can act on.
     *
     * Not a formality. [SupabaseClient] falls back to the anon key when an authorized request
     * has no token, which PostgREST answers with an empty row set rather than a 401 — so a
     * signed-out PATCH reads as a perfectly successful change of nothing. The one case that
     * did check simply returned, which the ViewModel counted as a success and reported as one.
     *
     * Signing the player out here as well as refusing is the honest half: if the session is
     * gone, the account row on screen is describing somebody who is no longer signed in.
     */
    private suspend fun requireSession(): Session {
        val session = client.currentSession()
        if (session == null) {
            _player.value = null
            throw AuthResultException(AuthError.SessionExpired)
        }
        return session
    }

    private suspend fun loadProfile(session: Session) {
        val raw = client.request(
            "GET",
            "/rest/v1/profiles?id=eq.${session.userId}&select=display_name,provider",
            authorized = true,
        )
        // The account may have changed while that request was in flight: a deep link can
        // adopt a new session concurrently with the launch restore's profile refresh, and
        // the *authorization* above follows the store (a bearer for the new account, a path
        // still naming the old one — RLS answers that with an empty row set). Publishing the
        // stale identity here would leave the UI showing one account while every authorized
        // write acts as another. If this session is no longer the stored one, the answer is
        // about somebody who is no longer signed in; drop it.
        if (client.currentSession()?.userId != session.userId) return
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
        // The session died *during* the call — refresh refused, store cleared. The account
        // row on screen is now describing somebody who is no longer signed in; the same
        // honesty requireSession applies at entry has to apply here, or the UI keeps a
        // ghost player over an empty session store.
        if (e.errorCode == "session_missing") _player.value = null
        throw AuthResultException(translate(e))
    } catch (e: java.io.IOException) {
        throw AuthResultException(AuthError.Offline)
    }

    private fun translate(e: SupabaseClient.SupabaseException): AuthError =
        translateAuthError(e.errorCode, e.message)
}

/**
 * The server's answer, turned into something a player can read and act on.
 *
 * A free function rather than a method so it can be tested on the JVM: [AuthRepository] needs
 * a [SupabaseClient], which needs a DataStore, which needs Android — and this mapping is
 * exactly the part that was wrong (a `reauthentication_needed` rejection surfaced as
 * "Something went wrong" on the one screen the player had just been told to use).
 */
internal fun translateAuthError(errorCode: String?, message: String): AuthError {
    val text = message.lowercase()
    val code = errorCode?.lowercase().orEmpty()
    return when {
        code == "user_already_exists" || text.contains("already registered") ||
            text.contains("already been registered") -> AuthError.EmailTaken

        code == "email_not_confirmed" || text.contains("not confirmed") ->
            AuthError.NotConfirmed

        code == "invalid_credentials" || text.contains("invalid login") ->
            AuthError.WrongPassword

        // Before the weak-password match, whose "password should be" substring this
        // message also contains.
        code == "same_password" || text.contains("should be different") ->
            AuthError.SamePassword

        code == "weak_password" || text.contains("password should be") ->
            AuthError.WeakPassword

        // Minted by SupabaseClient when a session died mid-call — the refresh was refused
        // and the store cleared while a request was in flight. The purpose-built copy, not
        // a raw "permission denied".
        code == "session_missing" -> AuthError.SessionExpired

        code == "reauthentication_needed" || text.contains("requires reauthentication") ->
            AuthError.NeedsRecentSignIn

        text.contains("display_name_cooldown") -> AuthError.NameCooldown

        // 23505 is Postgres' unique-violation; here it can only be the display name.
        code == "23505" || text.contains("duplicate key") -> AuthError.NameTaken

        text.contains("invalid email") || text.contains("unable to validate email") ->
            AuthError.BadEmail

        else -> AuthError.Unexpected(message)
    }
}
