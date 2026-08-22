package com.harken.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.harken.android.data.AppSettings
import com.harken.android.ui.AppNav
import com.harken.android.ui.ThemeMode
import com.harken.android.ui.theme.HarkenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings = remember { AppSettings(this) }
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.System)
            val dynamicColor by settings.dynamicColor.collectAsState(initial = false)
            val darkTheme = when (themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            HarkenTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                AppNav()
            }
        }
    }
}
