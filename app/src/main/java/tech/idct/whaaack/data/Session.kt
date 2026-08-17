package tech.idct.whaaack.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val userId: String,
    val email: String?,
    val provider: String,
    /**
     * Last known profile name, cached here so a relaunch can name the player from disk
     * instead of waiting on the profiles round trip.
     */
    val displayName: String? = null,
) {
    companion object {
        fun fromTokenResponse(json: Json, raw: String): Session? = runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val access = root.str("access_token") ?: return@runCatching null
            val refresh = root.str("refresh_token") ?: return@runCatching null
            val expiresIn = root.str("expires_in")?.toLongOrNull() ?: 3600L
            val user = root["user"]?.jsonObject
            // No usable account id means no usable session. It used to fall through as an
            // empty string, and a Session with an empty user id is worse than none: every
            // later profile read and write becomes `?id=eq.`, which PostgREST answers with a
            // 400, and only a 401 signs the player out — so they stay signed in and
            // permanently broken. `getAs` exists to keep exactly this off the deep-link path;
            // the token grants deserve the same guard rather than a second cure.
            val userId = user?.str("id")?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val meta = user?.get("user_metadata")?.jsonObject
            Session(
                accessToken = access,
                refreshToken = refresh,
                expiresAtMs = System.currentTimeMillis() + expiresIn * 1000,
                userId = userId,
                email = user?.str("email"),
                provider = user?.get("app_metadata")?.jsonObject?.str("provider") ?: "email",
                // A starting point only; the profiles table is the authority and overwrites
                // this as soon as it answers. Google supplies `name` rather than our own key.
                displayName = meta?.str("display_name") ?: meta?.str("name"),
            )
        }.getOrNull()
    }
}

/** Reads a JSON string field, treating an explicit null as absent. */
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonElement.str(key: String): String? = (this as? JsonObject)?.str(key)

private val Context.sessionDataStore by preferencesDataStore(name = "whaaack_session")

/**
 * Persists the signed-in session across launches.
 *
 * An interface so [SupabaseClient]'s refresh arithmetic — the part of the login that has
 * actually been wrong — can be exercised on the JVM against an in-memory store; the
 * DataStore implementation needs Android.
 */
interface SessionStore {
    suspend fun current(): Session?
    suspend fun save(session: Session)

    /**
     * Updates only the cached name. A read-modify-write of the whole [Session] would race
     * the token refresh that runs on the same store and could put a rotated refresh token
     * back to its previous value.
     */
    suspend fun saveDisplayName(name: String)

    /**
     * Records that this device has just asked GoTrue to email an auth link, and until when a
     * `whaaack://auth` callback carrying tokens may therefore be believed. Pass 0 to forget.
     *
     * On disk rather than in memory because the wait is spent in another app: the player
     * leaves for their inbox and Android is free to kill the process before they tap.
     */
    suspend fun expectAuthCallback(untilMs: Long)

    /** The deadline set by [expectAuthCallback], or 0 if no link is outstanding. */
    suspend fun authCallbackExpectedUntil(): Long

    suspend fun clear()
}

/** The on-device [SessionStore], excluded from backup by construction (see backup_rules). */
class DataStoreSessionStore(private val context: Context) : SessionStore {

    private val keyAccess = stringPreferencesKey("access_token")
    private val keyRefresh = stringPreferencesKey("refresh_token")
    private val keyExpires = longPreferencesKey("expires_at")
    private val keyUserId = stringPreferencesKey("user_id")
    private val keyEmail = stringPreferencesKey("email")
    private val keyProvider = stringPreferencesKey("provider")
    private val keyDisplayName = stringPreferencesKey("display_name")
    private val keyCallbackUntil = longPreferencesKey("auth_callback_until")

    override suspend fun current(): Session? {
        val prefs = context.sessionDataStore.data.first()
        val access = prefs[keyAccess] ?: return null
        val refresh = prefs[keyRefresh] ?: return null
        return Session(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMs = prefs[keyExpires] ?: 0L,
            userId = prefs[keyUserId].orEmpty(),
            email = prefs[keyEmail],
            provider = prefs[keyProvider] ?: "email",
            displayName = prefs[keyDisplayName],
        )
    }

    override suspend fun save(session: Session) {
        context.sessionDataStore.edit { prefs ->
            prefs[keyAccess] = session.accessToken
            prefs[keyRefresh] = session.refreshToken
            prefs[keyExpires] = session.expiresAtMs
            prefs[keyUserId] = session.userId
            val email = session.email
            if (email != null) prefs[keyEmail] = email else prefs.remove(keyEmail)
            prefs[keyProvider] = session.provider
            val name = session.displayName
            if (name != null) prefs[keyDisplayName] = name else prefs.remove(keyDisplayName)
        }
    }

    override suspend fun saveDisplayName(name: String) {
        context.sessionDataStore.edit { prefs ->
            // Never resurrect a session that was signed out while the profile was in flight.
            if (prefs[keyAccess] != null) prefs[keyDisplayName] = name
        }
    }

    override suspend fun expectAuthCallback(untilMs: Long) {
        context.sessionDataStore.edit { prefs ->
            if (untilMs > 0L) prefs[keyCallbackUntil] = untilMs else prefs.remove(keyCallbackUntil)
        }
    }

    override suspend fun authCallbackExpectedUntil(): Long =
        context.sessionDataStore.data.first()[keyCallbackUntil] ?: 0L

    override suspend fun clear() {
        context.sessionDataStore.edit { it.clear() }
    }
}
