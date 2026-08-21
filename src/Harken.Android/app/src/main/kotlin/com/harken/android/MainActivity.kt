package com.harken.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.harken.android.ui.AppNav
import com.harken.android.ui.theme.HarkenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HarkenTheme {
                AppNav()
            }
        }
    }
}
