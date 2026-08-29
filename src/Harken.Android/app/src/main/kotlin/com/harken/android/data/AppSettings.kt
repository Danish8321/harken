package com.harken.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.harken.android.ui.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "harken_settings")

// ADR-0011: on-device transcription is now the default, zero-backend path; Azure Batch
// (ADR-0010) stays an opt-in, backend-mediated alternative.
enum class TranscriptionProviderChoice { WhisperLocal, AzureBatch }

// Replaces MAUI's Preferences-backed AppSettings (src/Harken.Mobile/Services/AppSettings.cs)
// with the native equivalent — same two values, same default backend URL chosen for this
// dev environment's USB reverse-tunnel setup (docs/onboarding.md §5b).
class AppSettings(private val context: Context) {

    private object Keys {
        val BaseUrl = stringPreferencesKey("base_url")
        val OnboardingComplete = booleanPreferencesKey("onboarding_complete")
        val ThemeMode = stringPreferencesKey("theme_mode")
        val DynamicColor = booleanPreferencesKey("dynamic_color")
        val TranscriptionProvider = stringPreferencesKey("transcription_provider")
    }

    val baseUrl: Flow<String> = context.dataStore.data.map {
        it[Keys.BaseUrl] ?: DefaultBaseUrl
    }

    val onboardingComplete: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.OnboardingComplete] ?: false
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map {
        it[Keys.ThemeMode]?.let { name -> ThemeMode.entries.find { m -> m.name == name } } ?: ThemeMode.System
    }

    val dynamicColor: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.DynamicColor] ?: false
    }

    val transcriptionProvider: Flow<TranscriptionProviderChoice> = context.dataStore.data.map {
        it[Keys.TranscriptionProvider]?.let { name ->
            TranscriptionProviderChoice.entries.find { p -> p.name == name }
        } ?: TranscriptionProviderChoice.WhisperLocal
    }

    suspend fun setBaseUrl(value: String) {
        context.dataStore.edit { it[Keys.BaseUrl] = value }
    }

    suspend fun setOnboardingComplete(value: Boolean) {
        context.dataStore.edit { it[Keys.OnboardingComplete] = value }
    }

    suspend fun setThemeMode(value: ThemeMode) {
        context.dataStore.edit { it[Keys.ThemeMode] = value.name }
    }

    suspend fun setDynamicColor(value: Boolean) {
        context.dataStore.edit { it[Keys.DynamicColor] = value }
    }

    suspend fun setTranscriptionProvider(value: TranscriptionProviderChoice) {
        context.dataStore.edit { it[Keys.TranscriptionProvider] = value.name }
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
