package com.harken.android.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
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
            // Full-screen surface, so spatialSlow — Motion.kt's own rule is that speed
            // follows element size, and this is the largest thing that moves in the app.
            val slide = HarkenMotion.spatialSlow<IntOffset>()

            fun AnimatedContentTransitionScope<NavBackStackEntry>.movingRight(): Boolean =
                tabOrder(targetState.destination.route) > tabOrder(initialState.destination.route)

            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route
            val c = LocalProtoColors.current
            val isRecording by RecordingState.isRecording.collectAsStateWithLifecycle()
            // The segment travels with the session id: a search result opens the transcript
            // at the line that matched, and everything else opens it at the top.
            var openSession by remember { mutableStateOf<OpenSession?>(null) }

            // The tab bar is chrome, so it is hoisted above the graph rather than built
            // inside each destination (UI-043). Per-destination, it was carried by the
            // NavHost's own enter/exit transitions: the bar slid out from under the finger
            // that tapped it while a second copy slid in, and the selected-pill colour
            // spring never ran at all, because the incoming bar composed already selected.
            //
            // Onboarding is not a tab, so it gets no bar — and since the Scaffold supplies
            // the system-bar insets for every destination now, OnboardingScreen no longer
            // applies its own.
            Scaffold(
                // The session sheet composes in this window now rather than a Dialog of its
                // own (UI-043), which is what lets a shared element span a Library card into
                // it — but it also means it cannot trap focus the way that Dialog did. So the
                // content behind it leaves the accessibility tree explicitly instead of merely
                // being painted over: without this, TalkBack still walks the Library under the
                // scrim.
                modifier = if (openSession != null) Modifier.semantics { hideFromAccessibility() } else Modifier,
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    AnimatedVisibility(
                        visible = tabOrder(currentRoute) >= 0,
                        enter =
                            slideInVertically(HarkenMotion.spatialDefault()) { it } +
                                fadeIn(HarkenMotion.effectsDefault()),
                        exit =
                            slideOutVertically(HarkenMotion.spatialDefault()) { it } +
                                fadeOut(HarkenMotion.effectsDefault()),
                    ) {
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
                    }
                },
            ) { padding ->
                NavHost(
                    navController = navController,
                    startDestination = if (onboardingComplete == true) Routes.RECORD else Routes.ONBOARDING,
                    modifier = Modifier.padding(padding),
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
                    composable(Routes.RECORD) {
                        RecordScreen(onOpenSession = { openSession = OpenSession(it, null) })
                    }
                    composable(Routes.LIBRARY) {
                        LibraryScreen(
                            onOpenSession = { id, segmentId -> openSession = OpenSession(id, segmentId) },
                            onGoToRecord = { navController.navigate(Routes.RECORD) },
                        )
                    }
                    composable(Routes.SETTINGS) { SettingsScreen() }
                }
            }

            openSession?.let { target ->
                SessionSheet(
                    sessionId = target.sessionId,
                    focusSegmentId = target.focusSegmentId,
                    onDismiss = { openSession = null },
                )
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
        Text(stringResource(R.string.record_wordmark), color = c.text, style = MaterialTheme.typography.headlineSmall)
    }
}

/** Which recording the sheet is showing, and which line of it to open at. */
private data class OpenSession(
    val sessionId: UUID,
    val focusSegmentId: UUID?,
)

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
        // One indicator that travels, rather than three that cross-fade in place. The tabs
        // are equal-weight so a slot is always a third of the bar, which is what lets the
        // pill's position be a plain offset animation instead of per-item geometry.
        var barWidthPx by remember { mutableIntStateOf(0) }
        val slotWidthPx = if (barWidthPx > 0) barWidthPx / tabs.size else 0
        val selectedIndex = tabs.indexOfFirst { it.route == currentRoute }
        val indicatorPx by animateIntAsState(
            targetValue = if (selectedIndex >= 0) selectedIndex * slotWidthPx else 0,
            animationSpec = HarkenMotion.spatialDefault(),
            label = "tabIndicator",
        )

        Box(
            Modifier
                .fillMaxWidth()
                .background(c.navBg, RoundedCornerShape(32.dp))
                .padding(6.dp)
                // Intrinsic height so the pill can fill it: the bar is as tall as its
                // tallest tab, which grows with the user's font scale.
                .height(IntrinsicSize.Min)
                .onSizeChanged { barWidthPx = it.width },
        ) {
            if (selectedIndex >= 0 && slotWidthPx > 0) {
                Box(
                    Modifier
                        .offset { IntOffset(indicatorPx, 0) }
                        .width(with(LocalDensity.current) { slotWidthPx.toDp() })
                        .fillMaxHeight()
                        .background(c.accent, RoundedCornerShape(24.dp)),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val selected = currentRoute == tab.route
                    // Live pill rides along on the RECORD tab's icon whenever a capture is
                    // active and the user isn't already looking at the live view — the real
                    // record button subsumes it there, so a second live dot on top of the tab
                    // would be redundant rather than reassuring.
                    val showLiveDot = tab.route == Routes.RECORD && isRecording && !selected
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
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .selectable(
                                selected = selected,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = LocalIndication.current,
                                role = Role.Tab,
                            ) { if (!selected) onSelect(tab) }
                            .heightIn(min = 48.dp)
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
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
                            // includeFontPadding = false is load-bearing here, not cosmetic:
                            // the label sits inside a fixed-height pill next to its icon, and
                            // the default font padding pushed it off that centre line.
                            style =
                                MaterialTheme.typography.labelLarge.copy(
                                    lineHeight = 13.sp,
                                    platformStyle =
                                        androidx.compose.ui.text
                                            .PlatformTextStyle(includeFontPadding = false),
                                ),
                        )
                    }
                }
            }
        }
    }
}
