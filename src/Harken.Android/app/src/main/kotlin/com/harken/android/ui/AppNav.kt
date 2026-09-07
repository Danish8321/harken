package com.harken.android.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.harken.android.HarkenApplication
import com.harken.android.R
import com.harken.android.recording.RecordingState
import com.harken.android.ui.theme.HarkenMotion
import com.harken.android.ui.theme.LocalProtoColors
import com.harken.android.ui.theme.LocalReducedMotion
import com.harken.android.ui.theme.ProtoBodyFont
import com.harken.android.ui.theme.ProtoHeadingFont
import com.harken.android.ui.theme.sharedAxisEnter
import com.harken.android.ui.theme.sharedAxisExit
import java.util.UUID

// Routes renamed with the screens: "capture" -> "record", "recordings" -> "library".
// A tab labelled "Recordings" sitting next to a tab that records was the single most
// confusing thing in the old navigation.
object Routes {
    const val ONBOARDING = "onboarding"
    const val RECORD = "record"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
}

private data class Tab(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
)

private val tabs =
    listOf(
        Tab(Routes.RECORD, R.string.nav_record, Icons.Filled.Mic),
        Tab(Routes.LIBRARY, R.string.nav_library, Icons.Filled.LibraryMusic),
        Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Tune),
    )

/**
 * Where a destination sits left-to-right, which is what decides the slide direction.
 *
 * ONBOARDING is not a tab and returns -1 deliberately: it sits before all three, so
 * finishing it slides forward into RECORD like any other rightward move.
 */
private fun tabOrder(route: String?): Int = tabs.indexOfFirst { it.route == route }

@Composable
fun AppNav() {
    val context = LocalContext.current
    val settings = remember(context) { (context.applicationContext as HarkenApplication).container.settings }
    val onboardingComplete by settings.onboardingComplete.collectAsStateWithLifecycle(initialValue = null)

    // Wait for the real DataStore value before picking a start destination — defaulting
    // to RECORD would flash past onboarding for a first-time user on a slow read. Render
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
            // The tab transition lives here, on the graph, because the graph is the only
            // thing that knows both the destination being left and the one being entered.
            // It used to live in an AnimatedContent inside each tab's own pane, where it
            // could not: that lambda ignored its target state, so both halves rendered the
            // same screen and a tab change slid a pane against itself (ARC-020).
            val reduced = LocalReducedMotion.current
            val fade = HarkenMotion.effectsDefault<Float>()
            val slide = HarkenMotion.spatialDefault<androidx.compose.ui.unit.IntOffset>()

            fun AnimatedContentTransitionScope<NavBackStackEntry>.movingRight(): Boolean =
                tabOrder(targetState.destination.route) > tabOrder(initialState.destination.route)

            NavHost(
                navController = navController,
                startDestination = if (onboardingComplete == true) Routes.RECORD else Routes.ONBOARDING,
                enterTransition = { sharedAxisEnter(reduced, movingRight(), fade, slide, offsetDivisor = 4) },
                exitTransition = { sharedAxisExit(reduced, movingRight(), fade, slide, offsetDivisor = 4) },
                popEnterTransition = { sharedAxisEnter(reduced, movingRight(), fade, slide, offsetDivisor = 4) },
                popExitTransition = { sharedAxisExit(reduced, movingRight(), fade, slide, offsetDivisor = 4) },
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(onFinished = {
                        navController.navigate(Routes.RECORD) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                    })
                }
                composable(Routes.RECORD) { MainHost(navController) { open -> RecordScreen(onOpenSession = { open(it, null) }) } }
                composable(Routes.LIBRARY) {
                    MainHost(navController) { open ->
                        LibraryScreen(onOpenSession = open, onGoToRecord = { navController.navigate(Routes.RECORD) })
                    }
                }
                composable(Routes.SETTINGS) { MainHost(navController) { SettingsScreen() } }
            }
        }
    }
}

/** Themed hold-frame shown while the onboarding flag is still reading from DataStore. */
@Composable
private fun SplashPlaceholder() {
    val c = LocalProtoColors.current
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
    content: @Composable (onOpenSession: (sessionId: UUID, focusSegmentId: UUID?) -> Unit) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // The segment travels with the session id: a search result opens the transcript at the
    // line that matched, and everything else opens it at the top.
    var openSession by remember { mutableStateOf<OpenSession?>(null) }

    val c = LocalProtoColors.current
    val isRecording by RecordingState.isRecording.collectAsStateWithLifecycle()

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
        // No transition here: the shared-axis slide is the NavHost's, since only the
        // graph knows which screen is being left for which.
        Box(Modifier.padding(padding)) { content { id, segmentId -> openSession = OpenSession(id, segmentId) } }
    }

    openSession?.let { target ->
        SessionSheet(
            sessionId = target.sessionId,
            focusSegmentId = target.focusSegmentId,
            onDismiss = { openSession = null },
        )
    }
}

/** Which recording the sheet is showing, and which line of it to open at. */
private data class OpenSession(val sessionId: UUID, val focusSegmentId: UUID?)

// A floating pill instead of Material's edge-to-edge NavigationBar (UI-021) — inset from
// the screen edges and elevated on the surface color, adapted from the floating-nav
// pattern (Pinterest et al.) rather than copied: labels stay always-visible per tab
// (dropped there, kept here) since three single-word labels cost little width and remove
// any ambiguity the icon-only version would have on LIBRARY/SETTINGS.
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
                // Live pill rides along on the RECORD tab's icon whenever a capture is
                // active and the user isn't already looking at the live view — the real
                // record button subsumes it there, so a second live dot on top of the tab
                // would be redundant rather than reassuring.
                val showLiveDot = tab.route == Routes.RECORD && isRecording && !selected
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
                        style =
                            androidx.compose.ui.text.TextStyle(
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            ),
                    )
                }
            }
        }
    }
}
