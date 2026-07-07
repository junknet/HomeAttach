package com.homeattach.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.homeattach.app.data.SettingsStore
import com.homeattach.app.ui.SessionListScreen
import com.homeattach.app.ui.SettingsScreen
import com.homeattach.app.ui.TerminalScreen
import com.homeattach.app.ui.theme.HomeAttachTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HomeAttachTheme {
                // Dark root so any one-frame relayout gap (keyboard resize, terminal reflow) shows
                // the terminal color, not a light flash. The list/settings paint their own bg on top.
                Surface(modifier = Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color(0xFF0A0B10)) {
                    HomeAttachApp()
                }
            }
        }
    }
}

private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_SESSIONS = "sessions"
private const val ROUTE_TERMINAL = "terminal/{name}?label={label}"

/**
 * Navigation shell. The session list is the app; Settings is a plain pushed screen (gear icon
 * in the list's top bar), and the Terminal is pushed on top of the list. No drawer - two
 * destinations don't need one, and nothing may compete with the terminal for edge gestures.
 * First run (nothing configured) starts on Settings with no back target.
 */
@Composable
fun HomeAttachApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val startDestination = if (settingsStore.isConfigured()) ROUTE_SESSIONS else ROUTE_SETTINGS

    // Instant transitions: the default crossfade alpha-blends the light list against the dark
    // terminal, producing a one-frame washed-out flash on enter/session-switch. A hard cut avoids it.
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(ROUTE_SETTINGS) {
            val canGoBack = navController.previousBackStackEntry != null
            SettingsScreen(
                settingsStore = settingsStore,
                onSaved = {
                    navController.navigate(ROUTE_SESSIONS) {
                        popUpTo(ROUTE_SETTINGS) { inclusive = true }
                    }
                },
                onBack = if (canGoBack) {
                    { navController.popBackStack() }
                } else {
                    null
                },
            )
        }
        composable(ROUTE_SESSIONS) {
            SessionListScreen(
                settingsStore = settingsStore,
                onOpenSession = { name, label ->
                    navController.navigate("terminal/${Uri.encode(name)}?label=${Uri.encode(label)}")
                },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
            )
        }
        composable(
            route = ROUTE_TERMINAL,
            arguments = listOf(
                navArgument("name") { type = NavType.StringType },
                navArgument("label") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val name = entry.arguments?.getString("name") ?: ""
            val label = entry.arguments?.getString("label").orEmpty()
            TerminalScreen(
                sessionName = name,
                sessionLabel = label.ifBlank { name },
                settingsStore = settingsStore,
                onBack = { navController.popBackStack() },
                onNavigateToSession = { nextName, nextLabel ->
                    navController.navigate("terminal/${Uri.encode(nextName)}?label=${Uri.encode(nextLabel)}") {
                        popUpTo(ROUTE_TERMINAL) { inclusive = true }
                    }
                },
            )
        }
    }
}
