package app.iptvplayer.tv.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.iptvplayer.tv.ui.onboarding.SourceFormPlaceholderScreen
import app.iptvplayer.tv.ui.onboarding.SourceType
import app.iptvplayer.tv.ui.onboarding.SourceTypeScreen
import app.iptvplayer.tv.ui.onboarding.WelcomeScreen
import app.iptvplayer.tv.ui.shell.MainShell
import app.iptvplayer.tv.ui.theme.Tokens

/** Navigation routes. The shell has no persisted sources yet, so every launch starts at Welcome. */
object Routes {
    const val WELCOME = "welcome"
    const val SOURCE_TYPE = "source-type"
    const val SOURCE_FORM = "source-form/{type}"
    const val MAIN = "main"

    fun sourceForm(type: SourceType) = "source-form/${type.name}"
}

/**
 * Back behavior (DESIGN_SYSTEM.md §6, SPEC_REVIEW §3.6): Back pops onboarding screens; inside [MainShell] it moves focus
 * content → navigation rail → Home; Back on Home with focus in the rail leaves the app (Android TV convention).
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Routes.WELCOME,
        // motion.emphasized cross-fade (DESIGN_SYSTEM.md §3.5) instead of the 700 ms default; input is never blocked.
        enterTransition = { fadeIn(tween(Tokens.MOTION_EMPHASIZED_MS)) },
        exitTransition = { fadeOut(tween(Tokens.MOTION_EMPHASIZED_MS)) },
        popEnterTransition = { fadeIn(tween(Tokens.MOTION_EMPHASIZED_MS)) },
        popExitTransition = { fadeOut(tween(Tokens.MOTION_EMPHASIZED_MS)) },
    ) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onAddSource = { navController.navigate(Routes.SOURCE_TYPE) },
                onExplore = {
                    navController.navigate(Routes.MAIN) { popUpTo(Routes.WELCOME) { inclusive = true } }
                },
            )
        }
        composable(Routes.SOURCE_TYPE) {
            SourceTypeScreen(onSelect = { navController.navigate(Routes.sourceForm(it)) })
        }
        composable(Routes.SOURCE_FORM) { entry ->
            val type = entry.arguments?.getString("type")?.let { name -> SourceType.entries.firstOrNull { it.name == name } }
            SourceFormPlaceholderScreen(type = type ?: SourceType.XTREAM, onBack = { navController.popBackStack() })
        }
        composable(Routes.MAIN) {
            MainShell(onAddSource = { navController.navigate(Routes.SOURCE_TYPE) })
        }
    }
}
