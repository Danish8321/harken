package com.harken.android.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.harken.android.data.AppSettings
import java.util.UUID

private object Routes {
    const val Onboarding = "onboarding"
    const val Capture = "capture"
    const val Settings = "settings"
    const val Recordings = "recordings"
    const val SessionDetail = "recordings/{sessionId}"
    fun sessionDetail(id: UUID) = "recordings/$id"
}

private data class BottomDestination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val bottomDestinations = listOf(
    BottomDestination(Routes.Capture, "Record", Icons.Filled.Mic),
    BottomDestination(Routes.Recordings, "Recordings", Icons.AutoMirrored.Filled.List),
    BottomDestination(Routes.Settings, "Settings", Icons.Filled.Settings),
)

@Composable
fun AppNav() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val onboardingComplete by settings.onboardingComplete.collectAsState(initial = null)

    // Wait for the real DataStore value before picking a start destination — defaulting
    // to Capture would flash past onboarding for a first-time user on a slow read.
    if (onboardingComplete == null) return

    val navController = rememberNavController()
    val startDestination = if (onboardingComplete == true) Routes.Capture else Routes.Onboarding

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.Onboarding) {
            OnboardingScreen(onFinished = {
                navController.navigate(Routes.Capture) {
                    popUpTo(Routes.Onboarding) { inclusive = true }
                }
            })
        }
        composable(Routes.Capture) {
            MainHost(navController) {
                CaptureScreen(onOpenSession = { id -> navController.navigate(Routes.sessionDetail(id)) })
            }
        }
        composable(Routes.Settings) { MainHost(navController) { SettingsScreen() } }
        composable(Routes.Recordings) {
            MainHost(navController) {
                RecordingListScreen(onOpenSession = { id ->
                    navController.navigate(Routes.sessionDetail(id))
                })
            }
        }
        composable(
            Routes.SessionDetail,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = UUID.fromString(backStackEntry.arguments?.getString("sessionId"))
            SessionDetailScreen(sessionId = sessionId, onBack = { navController.popBackStack() })
        }
    }
}

// Shared Scaffold + bottom nav for the three primary tabs (Record, Recordings, Settings)
// so switching between them is a single tap rather than hunting through top-bar icons.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainHost(navController: NavHostController, content: @Composable () -> Unit) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Active tab gets a pill-shaped tinted background behind its icon (Organic spec).
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                bottomDestinations.forEach { destination ->
                    val selected = currentRoute == destination.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
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
        Box(modifier = androidx.compose.ui.Modifier.padding(padding)) {
            Crossfade(targetState = currentRoute, label = "tab-crossfade") {
                content()
            }
        }
    }
}
