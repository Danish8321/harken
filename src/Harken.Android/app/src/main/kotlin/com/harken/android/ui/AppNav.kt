package com.harken.android.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harken.android.ui.theme.ProtoBodyFont
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
    val navController = rememberNavController()

    // A hard swap from the splash composable to NavHost read as an abrupt screen switch
    // even with the splash's own content fade — the ROOT changed, not just its content.
    // Crossfade holds both across a short overlap so the handoff itself is continuous.
    androidx.compose.animation.Crossfade(
        targetState = showSplash,
        animationSpec = HarkenMotion.effectsDefault(),
        label = "splashToApp",
    ) { splash ->
        if (splash) {
            SplashScreen(destinationIsRecord = onboardingComplete == true, onFinished = { showSplash = false })
        } else {
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
            FloatingTabBar(
                c = c,
                currentRoute = currentRoute,
                isRecording = isRecording,
                onSelect = { tab ->
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
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
            transitionSpec = com.harken.android.ui.theme.sharedAxisTransition(reduced, fade, slide, offsetDivisor = 4),
        ) {
            Box { content { id -> openSessionId = id } }
        }
    }

    openSessionId?.let { id ->
        SessionSheet(sessionId = id, onDismiss = { openSessionId = null })
    }
}

// A floating pill instead of Material's edge-to-edge NavigationBar (UI-021) — inset from
// the screen edges and elevated on the surface color, adapted from the floating-nav
// pattern (Pinterest et al.) rather than copied: labels stay always-visible per tab
// (dropped there, kept here) since three single-word labels cost little width and remove
// any ambiguity the icon-only version would have on Library/Settings.
@Composable
private fun FloatingTabBar(
    c: com.harken.android.ui.theme.ProtoColors,
    currentRoute: String?,
    isRecording: Boolean,
    onSelect: (Tab) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.navBg, RoundedCornerShape(32.dp))
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val selected = currentRoute == tab.route
                // Live pill rides along on the Record tab's icon whenever a capture is
                // active and the user isn't already looking at the live view — the real
                // record button subsumes it there, so a second live dot on top of the tab
                // would be redundant rather than reassuring.
                val showLiveDot = tab.route == Routes.Record && isRecording && !selected
                val itemBg by animateColorAsState(
                    if (selected) c.accent else androidx.compose.ui.graphics.Color.Transparent,
                    HarkenMotion.effectsFast(),
                    label = "tabItemBg",
                )
                val itemFg by animateColorAsState(
                    if (selected) c.onAccent else c.textSecondary,
                    HarkenMotion.effectsFast(),
                    label = "tabItemFg",
                )
                // selectable, not clickable: it carries Role.Tab and the selected state
                // into the semantics tree, which is what NavigationBarItem gave us for
                // free before this bar replaced it (UI-005). A bare clickable announced
                // these as unlabelled text and never said which tab you were on. The
                // 48dp floor is UI-004's — icon plus padding alone came to 40dp, and
                // these are the most-tapped controls in the app.
                Row(
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .selectable(
                            selected = selected,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            role = Role.Tab,
                        ) { if (!selected) onSelect(tab) }
                        .background(itemBg, RoundedCornerShape(24.dp))
                        .heightIn(min = 48.dp)
                        .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Icon(tab.icon, contentDescription = null, tint = itemFg, modifier = Modifier.size(20.dp))
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showLiveDot,
                            enter = scaleIn(HarkenMotion.spatialFast()) + fadeIn(HarkenMotion.effectsFast()),
                            exit = scaleOut(HarkenMotion.spatialFast()) + fadeOut(HarkenMotion.effectsFast()),
                            modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-2).dp),
                        ) {
                            // stateLive and accent are the same color since UI-020 (recording-live
                            // rides the resting brand accent) — one filled dot, not a two-layer ring.
                            Box(Modifier.size(8.dp).background(c.stateLive, CircleShape))
                        }
                    }
                    // Decorative: the visible label sits directly next to the icon and the
                    // Row's selectable already announces the tab, so a contentDescription
                    // here would make TalkBack read the tab name twice.
                    Text(
                        stringResource(tab.label),
                        color = itemFg,
                        fontFamily = ProtoBodyFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        lineHeight = 13.sp,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                        ),
                    )
                }
            }
        }
    }
}
