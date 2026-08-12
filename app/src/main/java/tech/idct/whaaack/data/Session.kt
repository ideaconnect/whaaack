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
) {
    companion object {
        fun fromTokenResponse(json: Json, raw: String): Session? = runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val access = root.str("access_token") ?: return@runCatching null
            val refresh = root.str("refresh_token") ?: return@runCatching null
            val expiresIn = root.str("expires_in")?.toLongOrNull() ?: 3600L
            val user = root["user"]?.jsonObject
            Session(
                accessToken = access,
                refreshToken = refresh,
                expiresAtMs = System.currentTimeMillis() + expiresIn * 1000,
                userId = user?.str("id").orEmpty(),
                email = user?.str("email"),
                provider = user?.get("app_metadata")?.jsonObject?.str("provider") ?: "email",
            )
        }.getOrNull()
    }
}

/** Reads a JSON string field, treating an explicit null as absent. */
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonElement.str(key: String): String? = (this as? JsonObject)?.str(key)

private val Context.sessionDataStore by preferencesDataStore(name = "whaaack_session")

/** Persists the signed-in session across launches. */
class SessionStore(private val context: Context) {

    private val keyAccess = stringPreferencesKey("access_token")
    private val keyRefresh = stringPreferencesKey("refresh_token")
    private val keyExpires = longPreferencesKey("expires_at")
    private val keyUserId = stringPreferencesKey("user_id")
    private val keyEmail = stringPreferencesKey("email")
    private val keyProvider = stringPreferencesKey("provider")

    suspend fun current(): Session? {
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
        )
    }

    suspend fun save(session: Session) {
        context.sessionDataStore.edit { prefs ->
            prefs[keyAccess] = session.accessToken
            prefs[keyRefresh] = session.refreshToken
            prefs[keyExpires] = session.expiresAtMs
            prefs[keyUserId] = session.userId
            val email = session.email
            if (email != null) prefs[keyEmail] = email else prefs.remove(keyEmail)
            prefs[keyProvider] = session.provider
        }
    }

    suspend fun clear() {
        context.sessionDataStore.edit { it.clear() }
    }
}
