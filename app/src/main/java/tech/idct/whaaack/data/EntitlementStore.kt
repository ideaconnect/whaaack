package tech.idct.whaaack.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What the player has paid for.
 *
 * Its own DataStore file, deliberately — not a corner of `whaaack_settings`. A future
 * "reset settings" must not be able to take a purchase with it, and nothing about an
 * entitlement belongs next to a volume toggle.
 *
 * Not listed in `backup_rules.xml` or `data_extraction_rules.xml` either. Both are
 * exhaustive `<include>` lists, so a new store is excluded automatically, which is the
 * right outcome twice over: a restored backup must not clone a purchase onto a second
 * device, and its absence costs a real owner nothing because Play re-grants on the first
 * successful query.
 */
data class Entitlements(
    /** True once Play has confirmed the purchase, and thereafter until Play denies it. */
    val adsRemoved: Boolean = false,
    /**
     * When Play last answered OK with the product owned. Diagnostics and support only —
     * this must never appear on the right-hand side of a revoke condition. See
     * [tech.idct.whaaack.billing.BillingManager] for why the grace period is unbounded.
     */
    val verifiedOwnedAtMs: Long = 0L,
    /**
     * Consecutive verified-online passes that found the product absent. A downgrade needs
     * two, and it is persisted so those two may fall in different sessions.
     */
    val notOwnedStreak: Int = 0,
    /** Last check outcome, so Settings can explain a restore that found nothing. */
    val lastCheckAtMs: Long = 0L,
    val lastCheckCode: Int = 0,
    /** Kept so support can look an order up; never used for gating. */
    val orderId: String? = null,
)

private val Context.entitlementDataStore by preferencesDataStore(name = "whaaack_entitlements")

class EntitlementStore(private val context: Context) {

    private val keyAdsRemoved = booleanPreferencesKey("ads_removed")
    private val keyVerifiedAt = longPreferencesKey("ads_removed_verified_at")
    private val keyStreak = intPreferencesKey("not_owned_streak")
    private val keyCheckAt = longPreferencesKey("last_check_at")
    private val keyCheckCode = intPreferencesKey("last_check_code")
    private val keyOrderId = stringPreferencesKey("order_id")

    val flow: Flow<Entitlements> = context.entitlementDataStore.data.map { prefs ->
        Entitlements(
            adsRemoved = prefs[keyAdsRemoved] ?: false,
            verifiedOwnedAtMs = prefs[keyVerifiedAt] ?: 0L,
            notOwnedStreak = prefs[keyStreak] ?: 0,
            lastCheckAtMs = prefs[keyCheckAt] ?: 0L,
            lastCheckCode = prefs[keyCheckCode] ?: 0,
            orderId = prefs[keyOrderId],
        )
    }

    suspend fun current(): Entitlements = flow.first()

    suspend fun grant(nowMs: Long, orderId: String?) {
        context.entitlementDataStore.edit { prefs ->
            prefs[keyAdsRemoved] = true
            prefs[keyVerifiedAt] = nowMs
            prefs[keyStreak] = 0
            if (orderId != null) prefs[keyOrderId] = orderId
        }
    }

    suspend fun revoke() {
        context.entitlementDataStore.edit { prefs ->
            prefs[keyAdsRemoved] = false
            prefs[keyVerifiedAt] = 0L
            prefs[keyStreak] = 0
            prefs.remove(keyOrderId)
        }
    }

    /** Records a verified-online pass that found nothing, and returns the new streak. */
    suspend fun bumpNotOwnedStreak(): Int {
        var streak = 0
        context.entitlementDataStore.edit { prefs ->
            streak = (prefs[keyStreak] ?: 0) + 1
            prefs[keyStreak] = streak
        }
        return streak
    }

    suspend fun clearNotOwnedStreak() {
        context.entitlementDataStore.edit { it[keyStreak] = 0 }
    }

    suspend fun recordCheck(nowMs: Long, responseCode: Int) {
        context.entitlementDataStore.edit { prefs ->
            prefs[keyCheckAt] = nowMs
            prefs[keyCheckCode] = responseCode
        }
    }
}
