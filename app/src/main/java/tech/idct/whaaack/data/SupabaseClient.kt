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
            if (authorized && e.status == 401) {
                token = refreshSession()?.accessToken ?: throw e
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

    suspend fun refreshSession(): Session? = refreshLock.withLock {
        val existing = sessions.current() ?: return null
        // Another caller may have refreshed while we waited on the lock.
        if (existing.expiresAtMs - 60_000L > System.currentTimeMillis()) return existing

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

    suspend fun currentSession(): Session? = sessions.current()

    suspend fun saveSession(session: Session) = sessions.save(session)

    suspend fun clearSession() = sessions.clear()
}
