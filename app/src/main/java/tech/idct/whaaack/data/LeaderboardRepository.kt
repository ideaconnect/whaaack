package tech.idct.whaaack.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

enum class BoardScope(val wire: String, val label: String) {
    ALL_TIME("all_time", "Global all-time"),
    WEEKLY("weekly", "This week"),
}

data class BoardRow(
    val rank: Int,
    val userId: String,
    val displayName: String,
    val millis: Int,
    val achievedAt: String?,
) {
    val isTopThree: Boolean get() = rank <= 3
}

data class Standing(val rank: Int, val millis: Int, val totalPlayers: Int)

class LeaderboardRepository(private val client: SupabaseClient) {

    val isConfigured: Boolean get() = client.isConfigured

    suspend fun board(scope: BoardScope, limit: Int = 50): List<BoardRow> {
        if (!client.isConfigured) return emptyList()
        val payload = buildJsonObject {
            put("p_scope", JsonPrimitive(scope.wire))
            put("p_limit", JsonPrimitive(limit))
        }
        val raw = client.request("POST", "/rest/v1/rpc/leaderboard", payload)
        return client.json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            val row = element.jsonObject
            BoardRow(
                rank = row["rank"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null,
                userId = row.str("user_id").orEmpty(),
                displayName = row.str("display_name").orEmpty(),
                millis = row["millis"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                achievedAt = row.str("achieved_at"),
            )
        }
    }

    /** The signed-in player's own position, which may fall outside the visible page. */
    suspend fun myStanding(scope: BoardScope): Standing? {
        if (!client.isConfigured) return null
        if (client.currentSession() == null) return null
        val payload = buildJsonObject { put("p_scope", JsonPrimitive(scope.wire)) }
        val raw = client.request("POST", "/rest/v1/rpc/my_standing", payload, authorized = true)
        val row = client.json.parseToJsonElement(raw).jsonArray.firstOrNull()?.jsonObject
            ?: return null
        return Standing(
            rank = row["rank"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return null,
            millis = row["millis"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            totalPlayers = row["total_players"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
        )
    }

    /** Records a ranked run. No-ops when the player is not signed in. */
    suspend fun submit(millis: Long, hits: Int, topSpeed: Int): Boolean {
        if (!client.isConfigured) return false
        if (client.currentSession() == null) return false
        val payload = buildJsonObject {
            put("millis", JsonPrimitive(millis.coerceIn(0, 86_400_000).toInt()))
            put("hits", JsonPrimitive(hits))
            put("top_speed", JsonPrimitive(topSpeed))
        }
        return runCatching {
            client.request(
                "POST",
                "/rest/v1/scores",
                payload,
                authorized = true,
                extraHeaders = mapOf("Prefer" to "return=minimal"),
            )
        }.isSuccess
    }

    companion object {
        /** 128940 -> "128,940", matching the design's score formatting. */
        fun formatScore(value: Long): String = String.format(Locale.US, "%,d", value)

        fun formatScore(value: Int): String = formatScore(value.toLong())
    }
}
