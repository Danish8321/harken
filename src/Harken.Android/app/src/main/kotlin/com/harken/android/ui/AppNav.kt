package com.harken.android.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.harken.android.R
import com.harken.android.data.AppSettings
import com.harken.android.recording.RecordingState
import com.harken.android.ui.theme.HarkenMotion
import com.harken.android.ui.theme.LocalReducedMotion
import com.harken.android.ui.theme.ProtoHeadingFont
import com.harken.android.ui.theme.rememberProtoColors
import java.util.UUID

// Routes renamed with the screens: "capture" -> "record", "recordings" -> "library".
// A tab labelled "Recordings" sitting next to a tab that records was the single most
// confusing thing in the old navigation.
object Routes {
    const val Onboarding = "onboarding"
    const val Record = "record"
    const val Library = "library"
    const val Settings = "settings"
}

private data class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.Record, R.string.nav_record, Icons.Filled.Mic),
    Tab(Routes.Library, R.string.nav_library, Icons.Filled.LibraryMusic),
    Tab(Routes.Settings, R.string.nav_settings, Icons.Filled.Tune),
)

@Composable
fun AppNav() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val onboardingComplete by settings.onboardingComplete.collectAsState(initial = null)

    // Wait for the real DataStore value before picking a start destination — defaulting
    // to Record would flash past onboarding for a first-time user on a slow read. Render
    // a themed wordmark while waiting rather than nothing: returning early left the window
    // painting the bare themes.xml background, a white flash on a dark-theme device.
    if (onboardingComplete == null) {
        SplashPlaceholder()
        return
    }

    // The real, designed splash (UI-011) — shown once per process on every cold
    // launch, after the DataStore read above resolves. showSplash defaults true so a
    // return visit to this composable within the same process (e.g. system dark-mode
    // toggle recomposing AppNav) doesn't replay it.
    var showSplash by remember { mutableStateOf(true) }
    if (showSplash) {
        SplashScreen(destinationIsRecord = onboardingComplete == true, onFinished = { showSplash = false })
        return
    }

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = if (onboardingComplete == true) Routes.Record else Routes.Onboarding,
    ) {
        composable(Routes.Onboarding) {
            OnboardingScreen(onFinished = {
                navController.navigate(Routes.Record) { popUpTo(Routes.Onboarding) { inclusive = true } }
            })
        }
        composable(Routes.Record) { MainHost(navController) { open -> RecordScreen(onOpenSession = open) } }
        composable(Routes.Library) { MainHost(navController) { open -> LibraryScreen(onOpenSession = open) } }
        composable(Routes.Settings) { MainHost(navController) { SettingsScreen() } }
    }
}

/** Themed hold-frame shown while the onboarding flag is still reading from DataStore. */
@Composable
private fun SplashPlaceholder() {
    val c = rememberProtoColors()
    Box(
        Modifier.fillMaxSize().background(c.screenBg),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.record_wordmark), color = c.text, fontFamily = ProtoHeadingFont, fontSize = 28.sp)
    }
}

@Composable
private fun MainHost(
    navController: NavHostController,
    content: @Composable (onOpenSession: (UUID) -> Unit) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    var openSessionId by remember { mutableStateOf<UUID?>(null) }

    val c = rememberProtoColors()
    val isRecording by RecordingState.isRecording.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEach { tab ->
                    val selected = currentRoute == tab.route
                    // Live pill rides along on the Record tab's icon whenever a capture
                    // is active and the user isn't already looking at the live view —
                    // the real record button subsumes it there, so a second live dot on
                    // top of the tab would be redundant rather than reassuring.
                    val showLiveDot = tab.route == Routes.Record && isRecording && !selected
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        // Decorative: the visible label sits directly under the icon and
                        // NavigationBarItem already announces it, so a description here
                        // would make TalkBack read the tab name twice.
                        icon = {
                            Box {
                                Icon(tab.icon, contentDescription = null)
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = showLiveDot,
                                    enter = scaleIn(HarkenMotion.spatialFast()) + fadeIn(HarkenMotion.effectsFast()),
                                    exit = scaleOut(HarkenMotion.spatialFast()) + fadeOut(HarkenMotion.effectsFast()),
                                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-2).dp),
                                ) {
                                    Box(Modifier.size(8.dp).background(c.stateLive, CircleShape).padding(1.dp)) {
                                        Box(Modifier.fillMaxSize().background(c.accent, CircleShape))
                                    }
                                }
                            }
                        },
                        // labelMedium is now explicitly Figtree Bold. The old build left it
                        // un-overridden, which quietly rendered these three labels in Roboto
                        // while the rest of the app used Figtree.
                        label = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        // Shared-axis slide+fade, keyed on tab index rather than route — reading the
        // index lets the transition pick a consistent left/right direction that matches
        // the tapped tab's position in the bar, the same way it would for a ViewPager.
        // Not a plain Crossfade: the old build crossfaded on currentRoute, which meant the
        // pane faded on EVERY back-stack change, including opening the sheet — this only
        // fires when currentRoute actually changes, since that's what's keyed.
        val reduced = LocalReducedMotion.current
        val fade = HarkenMotion.effectsDefault<Float>()
        val slide = HarkenMotion.spatialDefault<androidx.compose.ui.unit.IntOffset>()
        val tabIndex = tabs.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
        AnimatedContent(
            targetState = tabIndex,
            label = "tab-switch",
            modifier = Modifier.padding(padding),
            transitionSpec = {
                if (reduced) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val forward = targetState > initialState
                    val enterOffset = if (forward) { w: Int -> w / 4 } else { w: Int -> -w / 4 }
                    val exitOffset = if (forward) { w: Int -> -w / 4 } else { w: Int -> w / 4 }
                    (slideInHorizontally(slide, enterOffset) + fadeIn(fade)) togetherWith
                        (slideOutHorizontally(slide, exitOffset) + fadeOut(fade))
                }
            },
        ) {
            Box { content { id -> openSessionId = id } }
        }
    }

    openSessionId?.let { id ->
        SessionSheet(sessionId = id, onDismiss = { openSessionId = null })
    }
}
