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

class AppSettings(private val context: Context) {

    private object Keys {
        val OnboardingComplete = booleanPreferencesKey("onboarding_complete")
        val ThemeMode = stringPreferencesKey("theme_mode")
        val DynamicColor = booleanPreferencesKey("dynamic_color")
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

    suspend fun setOnboardingComplete(value: Boolean) {
        context.dataStore.edit { it[Keys.OnboardingComplete] = value }
    }

    suspend fun setThemeMode(value: ThemeMode) {
        context.dataStore.edit { it[Keys.ThemeMode] = value.name }
    }

    suspend fun setDynamicColor(value: Boolean) {
        context.dataStore.edit { it[Keys.DynamicColor] = value }
    }
}
