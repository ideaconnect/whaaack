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
            // A rejected refresh token means the session is gone for good.
            if (e.status in 400..403) sessions.clear()
            return null
        }
        val session = Session.fromTokenResponse(json, raw) ?: return null
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

    suspend fun clearSession() = sessions.clear()
}
