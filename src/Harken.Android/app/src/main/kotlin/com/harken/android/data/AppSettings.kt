package com.harken.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "harken_settings")

// Replaces MAUI's Preferences-backed AppSettings (src/Harken.Mobile/Services/AppSettings.cs)
// with the native equivalent — same two values, same default backend URL chosen for this
// dev environment's USB reverse-tunnel setup (docs/onboarding.md §5b).
class AppSettings(private val context: Context) {

    private object Keys {
        val BaseUrl = stringPreferencesKey("base_url")
        val OnboardingComplete = booleanPreferencesKey("onboarding_complete")
    }

    val baseUrl: Flow<String> = context.dataStore.data.map {
        it[Keys.BaseUrl] ?: DefaultBaseUrl
    }

    val onboardingComplete: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.OnboardingComplete] ?: false
    }

    suspend fun setBaseUrl(value: String) {
        context.dataStore.edit { it[Keys.BaseUrl] = value }
    }

    suspend fun setOnboardingComplete(value: Boolean) {
        context.dataStore.edit { it[Keys.OnboardingComplete] = value }
    }

    companion object {
        const val DefaultBaseUrl = "http://localhost:5057"

        fun isValid(url: String): Boolean {
            if (url.isBlank()) return false
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            return uri.isAbsolute && (uri.scheme == "http" || uri.scheme == "https")
        }
    }
}
