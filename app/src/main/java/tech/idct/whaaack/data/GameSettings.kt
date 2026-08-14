package tech.idct.whaaack.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Preferences(
    val sound: Boolean = true,
    val music: Boolean = true,
    val haptics: Boolean = true,
    val parallax: Boolean = true,
    /** Best casual score, so players without an account still have something to beat. */
    val localBestMillis: Long = 0L,
)

private val Context.settingsDataStore by preferencesDataStore(name = "whaaack_settings")

class GameSettings(private val context: Context) {

    private val keySound = booleanPreferencesKey("sound")
    private val keyMusic = booleanPreferencesKey("music")
    private val keyHaptics = booleanPreferencesKey("haptics")
    private val keyParallax = booleanPreferencesKey("parallax")
    private val keyBest = longPreferencesKey("local_best")

    val flow: Flow<Preferences> = context.settingsDataStore.data.map { prefs ->
        Preferences(
            sound = prefs[keySound] ?: true,
            music = prefs[keyMusic] ?: true,
            haptics = prefs[keyHaptics] ?: true,
            parallax = prefs[keyParallax] ?: true,
            localBestMillis = prefs[keyBest] ?: 0L,
        )
    }

    suspend fun setSound(value: Boolean) = put(keySound, value)

    suspend fun setMusic(value: Boolean) = put(keyMusic, value)

    suspend fun setHaptics(value: Boolean) = put(keyHaptics, value)

    suspend fun setParallax(value: Boolean) = put(keyParallax, value)

    suspend fun recordLocalBest(millis: Long) {
        context.settingsDataStore.edit { prefs ->
            val previous = prefs[keyBest] ?: 0L
            if (millis > previous) prefs[keyBest] = millis
        }
    }

    /**
     * Drops the stored best. Deleting an account promises "we keep nothing", and the local
     * copy is the last place a deleted player's score would otherwise survive.
     */
    suspend fun clearLocalBest() {
        context.settingsDataStore.edit { it.remove(keyBest) }
    }

    private suspend fun put(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        context.settingsDataStore.edit { it[key] = value }
    }
}
