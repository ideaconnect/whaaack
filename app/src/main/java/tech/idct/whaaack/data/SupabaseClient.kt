package tech.idct.whaaack.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal Supabase client: just the GoTrue and PostgREST calls this game makes.
 *
 * Written by hand rather than pulled from a SDK so the app carries one small HTTP
 * dependency instead of a Ktor stack, and so token refresh is explicit and testable.
 */
class SupabaseClient(
    private val baseUrl: String,
    private val anonKey: String,
    private val sessions: SessionStore,
) {
    class SupabaseException(
        val status: Int,
        val errorCode: String?,
        override val message: String,
    ) : IOException(message)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        // The one that actually bounds the wait. connect/read/write are each per-socket-op,
        // so a trickling connection or a captive portal that answers a byte at a time can
        // keep a single call alive indefinitely without ever tripping them — and the sign-in
        // button sits on "Working…" for as long as that lasts, with no cancel affordance and
        // no way out but the back gesture, which navigates away while the call is still in
        // flight. callTimeout bounds a whole call — DNS, connect, TLS, write, read and any
        // redirects — so no single request can outlive it. It expires as an
        // InterruptedIOException, which AuthRepository already maps to AuthError.Offline.
        // (Each attempt gets its own budget, so a 401 refresh-and-retry can take 2x this.)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val refreshLock = Mutex()

    val isConfigured: Boolean get() = baseUrl.isNotBlank() && anonKey.isNotBlank()

    // ---- request plumbing ---------------------------------------------------------

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw toException(response.code, body)
            body
        }
    }

    private fun toException(status: Int, body: String): SupabaseException {
        // GoTrue and PostgREST disagree on the error envelope; try both shapes.
        val parsed = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        fun field(vararg names: String): String? =
            names.firstNotNullOfOrNull { name -> parsed?.str(name) }
        val code = field("error_code", "code", "error")
        val message = field("msg", "message", "error_description", "hint")
            ?: body.ifBlank { "Request failed ($status)" }
        return SupabaseException(status, code, message)
    }

    private fun builder(path: String, authorized: Boolean, token: String?): Request.Builder {
        val b = Request.Builder()
            .url("$baseUrl$path")
            .header("apikey", anonKey)
            .header("Content-Type", "application/json")
        val bearer = if (authorized) token else null
        b.header("Authorization", "Bearer ${bearer ?: anonKey}")
        return b
    }

    /** POST/PATCH/GET helper that transparently refreshes an expired access token once. */
    suspend fun request(
        method: String,
        path: String,
        body: JsonElement? = null,
        authorized: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        var token = if (authorized) validAccessToken() else null
        // An authorized request with no token must fail as what it is, not fall through to
        // the anon key. The fallback looked harmless when RLS answered anon writes with an
        // empty row set, but the column grants hardening turned those into 42501 "permission
        // denied for table …" — a message about our schema shown to a player whose actual
        // problem is that their session died mid-call (refresh refused, store cleared while
        // this request was being built). The one caller that wants best-effort — signOut's
        // logout — already swallows this. `session_missing` maps to AuthError.SessionExpired.
        if (authorized && token == null) {
            throw SupabaseException(
                status = 401,
                errorCode = "session_missing",
                message = "Signed out: there is no session behind this request.",
            )
        }

        suspend fun attempt(withToken: String?): String {
            val req = builder(path, authorized, withToken).apply {
                extraHeaders.forEach { (k, v) -> header(k, v) }
                val payload = body?.let { json.encodeToString(JsonElement.serializer(), it) }
                when (method) {
                    "GET" -> get()
                    "DELETE" -> delete()
                    else -> method(method, (payload ?: "{}").toRequestBody(jsonMedia))
                }
            }.build()
            return execute(req)
        }

        return try {
            attempt(token)
        } catch (e: SupabaseException) {
            // A 401 against a token we believed was current means the server disagrees with
            // our expiry arithmetic: the device clock moved, the project's JWT secret was
            // rotated, or the session was ended elsewhere. The refresh has to be *forced*
            // here — asking politely returns the very token that was just rejected, because
            // by the stored expiry it is still fresh, and the retry is then a guaranteed
            // second 401. AuthRepository reads that as a session beyond saving and signs the
            // player out, having never once offered the refresh token that would have
            // rescued it.
            if (authorized && token != null && e.status == 401) {
                token = refreshSession(rejected = token)?.accessToken ?: throw e
                attempt(token)
            } else {
                throw e
            }
        }
    }

    // ---- session -------------------------------------------------------------------

    /** Returns a token that is valid now, refreshing ahead of expiry when needed. */
    private suspend fun validAccessToken(): String? {
        val session = sessions.current() ?: return null
        val skewMs = 60_000L
        if (session.expiresAtMs - skewMs > System.currentTimeMillis()) return session.accessToken
        return refreshSession()?.accessToken
    }

    /**
     * Mints a new access token from the refresh token.
     *
     * [rejected] is the access token the server just answered 401 to, and it switches this
     * from "refresh if it looks expired" to "refresh because the server said no" — the stored
     * expiry cannot be trusted in that case, since disagreeing with it is the whole reason we
     * are here.
     *
     * Both forms still have to survive another caller refreshing while this one waited on the
     * lock; they just ask a different question about it. The proactive path asks whether the
     * stored token is now inside its lifetime, the reactive one whether it is a *different*
     * token from the one that failed — a token that has already been replaced is worth
     * retrying with, and re-spending the refresh token on it is not.
     *
     * Returns null only when there is nothing to refresh: no session, a refresh token the
     * server rejected outright (which also clears the store), or a store that was signed out
     * or replaced while the refresh was on the wire — the rotated tokens then belong to a
     * session nobody holds any more, and are dropped rather than saved. A failure that says
     * nothing about the token — an outage, a rate limit — is thrown instead, so no caller
     * can mistake it for a dead session.
     */
    suspend fun refreshSession(rejected: String? = null): Session? = refreshLock.withLock {
        val existing = sessions.current() ?: return null
        if (rejected != null) {
            if (existing.accessToken != rejected) return existing
        } else if (existing.expiresAtMs - 60_000L > System.currentTimeMillis()) {
            return existing
        }

        val payload = buildJsonObject { put("refresh_token", JsonPrimitive(existing.refreshToken)) }
        val raw = try {
            request("POST", "/auth/v1/token?grant_type=refresh_token", payload)
        } catch (e: SupabaseException) {
            // A definitive rejection — 400..403 — means the refresh token itself is dead and
            // the session is gone for good. Anything else (a 5xx outage, a 429 from the token
            // endpoint's rate limit) says nothing about the refresh token, so it must
            // propagate rather than read as "no session": null here made the 401 path above
            // rethrow the original 401, which refreshProfile takes as proof the session is
            // unrecoverable and *clears it* — a momentary server hiccup turned into a
            // permanent sign-out. Null also feeds the anon-key fallback, whose empty-rowset
            // "success" is the ghost the account changes were just cured of.
            if (e.status in 400..403) {
                sessions.clear()
                return null
            }
            throw e
        }
        // A 2xx carrying nothing usable is not an answer about the refresh token either, so
        // it takes the same road as an outage. Returning null here was the last way into the
        // failure the range above was narrowed to close: `request` would rethrow the original
        // 401, `refreshProfile` would read that as a session beyond saving, and a player whose
        // refresh token was perfectly good would be signed out for keeps — this time over a
        // truncated or proxy-mangled body rather than a server error. `execute` hands a 204 or
        // an empty body back as "", which parses to null, so this is not a hypothetical shape.
        // The store is deliberately left alone: nothing here says the refresh token is spent.
        val parsed = Session.fromTokenResponse(json, raw)
            ?: throw SupabaseException(
                status = 502,
                errorCode = null,
                message = "The sign-in service returned a response with no session in it.",
            )
        // Two corrections before the rotated tokens are persisted, both protecting state the
        // token endpoint knows nothing about:
        //
        // The display name: fromTokenResponse reads user_metadata, but the profiles table is
        // the authority — renames PATCH only profiles, and the signup trigger de-duplicates —
        // so user_metadata routinely disagrees with the name on the board. Persisting the
        // parsed session as-is regressed the cached name on every routine hourly refresh,
        // which is the exact clobber the single-key saveDisplayName exists to prevent.
        //
        // The store itself: a player can sign out while this refresh is on the wire, and a
        // save landing after that clear writes revoked tokens back into an empty store —
        // never resurrect a session that was signed out while the request was in flight.
        // The refresh token is the identity check: this save is only valid as the successor
        // of the exact session that was spent to mint it.
        val current = sessions.current()
        if (current?.refreshToken != existing.refreshToken) return null
        val session = parsed.copy(displayName = current?.displayName ?: parsed.displayName)
        sessions.save(session)
        return session
    }

    /**
     * A single authorized GET against a token that is not (yet) the stored session.
     *
     * The auth deep link needs this: it arrives holding tokens but no idea whose account
     * they belong to, and a [Session] with an empty user id is worse than none — every
     * later profile call becomes `?id=eq.`, which PostgREST answers with a 400 rather than
     * the 401 that would sign the player out again.
     */
    suspend fun getAs(path: String, accessToken: String): String =
        execute(builder(path, authorized = true, token = accessToken).get().build())

    suspend fun currentSession(): Session? = sessions.current()

    suspend fun saveSession(session: Session) = sessions.save(session)

    suspend fun cacheDisplayName(name: String) = sessions.saveDisplayName(name)

    /**
     * Opens the window in which a `whaaack://auth` callback carrying tokens is believable,
     * starting now. Called by the three flows that ask GoTrue to send such a link.
     */
    suspend fun expectAuthCallback() =
        sessions.expectAuthCallback(System.currentTimeMillis() + AUTH_CALLBACK_WINDOW_MS)

    /** True while a link this device asked for could still legitimately arrive. */
    suspend fun authCallbackExpected(): Boolean =
        System.currentTimeMillis() < sessions.authCallbackExpectedUntil()

    suspend fun forgetAuthCallback() = sessions.expectAuthCallback(0L)

    suspend fun clearSession() = sessions.clear()

    private companion object {
        /**
         * Comfortably longer than the links themselves live — `otp_expiry` is 1800s — so the
         * window never expires before the only thing that can use it does, and clock skew
         * between the device and GoTrue cannot turn a good link into a refused one.
         */
        const val AUTH_CALLBACK_WINDOW_MS = 2 * 60 * 60 * 1000L
    }
}
