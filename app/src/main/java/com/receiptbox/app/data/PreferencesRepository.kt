package com.receiptbox.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "receiptbox_prefs")

data class AppPreferences(
    val onboardingComplete: Boolean = false,
    val isProUnlocked: Boolean = false,
    val debugUnlock: Boolean = false,
    val freeReceiptsUsed: Int = 0
) {
    val hasPro: Boolean get() = isProUnlocked || debugUnlock
}

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
        val PRO = booleanPreferencesKey("is_pro_unlocked")
        val DEBUG = booleanPreferencesKey("debug_unlock")
        val FREE_USED = intPreferencesKey("free_receipts_used")
    }

    val preferences: Flow<AppPreferences> = context.dataStore.data.map { prefs ->
        AppPreferences(
            onboardingComplete = prefs[Keys.ONBOARDING] ?: false,
            isProUnlocked = prefs[Keys.PRO] ?: false,
            debugUnlock = prefs[Keys.DEBUG] ?: false,
            freeReceiptsUsed = prefs[Keys.FREE_USED] ?: 0
        )
    }

    suspend fun setOnboardingComplete(done: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING] = done }
    }

    suspend fun setProUnlocked(unlocked: Boolean) {
        context.dataStore.edit { it[Keys.PRO] = unlocked }
    }

    suspend fun setDebugUnlock(unlocked: Boolean) {
        context.dataStore.edit { it[Keys.DEBUG] = unlocked }
    }

    suspend fun incrementFreeReceiptsUsed() {
        context.dataStore.edit { prefs ->
            prefs[Keys.FREE_USED] = (prefs[Keys.FREE_USED] ?: 0) + 1
        }
    }
}
