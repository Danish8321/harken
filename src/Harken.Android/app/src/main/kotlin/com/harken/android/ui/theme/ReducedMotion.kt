package com.harken.android.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the user has asked the system to remove animations.
 *
 * Provided by [HarkenTheme]. Read it directly only where an animation cannot be
 * expressed as a [HarkenMotion] spec — an infinite loop, or an enter/exit transition
 * that has to be dropped rather than shortened. Everything driven by a HarkenMotion
 * spec already snaps on its own.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Reads `Settings.Global.ANIMATOR_DURATION_SCALE`, the value behind Accessibility →
 * "Remove animations" and the developer-options animation scales. Zero means the user
 * wants no animation.
 *
 * Android exposes no reduced-motion callback, so this observes the setting URI: the
 * toggle takes effect while the app is in the background and the app must not need a
 * relaunch to honour it.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var reduced by remember { mutableStateOf(animatorScaleIsZero(context)) }

    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = animatorScaleIsZero(context)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    return reduced
}

private fun animatorScaleIsZero(context: android.content.Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
