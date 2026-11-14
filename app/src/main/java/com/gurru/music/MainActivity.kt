package com.gurru.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gurru.music.player.PlayerViewModel
import com.gurru.music.ui.ComingSoonScreen
import com.gurru.music.ui.LibraryScreen
import com.gurru.music.ui.MixesScreen
import com.gurru.music.ui.PlayerScreen
import com.gurru.music.ui.SettingsScreen
import com.gurru.music.ui.SettingsViewModel
import com.gurru.music.ui.components.BottomBar
import com.gurru.music.ui.components.BottomSection
import com.gurru.music.ui.components.MiniPlayer
import com.gurru.music.ui.theme.GurruBackground
import com.gurru.music.ui.theme.LocalGurruTheme

/* Gurru Music nativa — Fase 1: Biblioteca local + reproductor.
   Una sola Activity con Navigation Compose. La Biblioteca es la
   pantalla principal; el reproductor se abre al tocar una canción y
   el mini reproductor flotante aparece cuando algo suena (salvo en el
   reproductor mismo). La barra inferior de 5 secciones está siempre
   visible; Ajustes ya tiene su pantalla (temas y créditos) y las
   demás secciones que aún no existen van a "Próximamente". */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GurruApp() }
    }
}

@Composable
fun GurruApp() {
    val playerViewModel: PlayerViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()
    val currentTheme by settingsViewModel.theme.collectAsState()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()

    val selectedSection = when {
        route == "library" || route == "player" -> BottomSection.LIBRARY
        route == "settings" -> BottomSection.SETTINGS
        route == "mixes" -> BottomSection.MIXES

        route?.startsWith("coming/") == true -> when (backStackEntry?.arguments?.getString("section")) {
            "Descargas" -> BottomSection.DOWNLOADS
            "YouTube" -> BottomSection.YOUTUBE
            "Mezclas" -> BottomSection.MIXES
            else -> BottomSection.LIBRARY
        }
        else -> BottomSection.LIBRARY
    }

    /* El tema de color guardado se provee a toda la app: al cambiarlo
       en Ajustes, la barra, el mini reproductor y todas las pantallas
       cambian de color al instante, sin reiniciar. */
    CompositionLocalProvider(LocalGurruTheme provides currentTheme) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GurruBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = "library",
                modifier = Modifier.weight(1f)
            ) {
                composable("library") {
                    LibraryScreen(
                        viewModel = playerViewModel,
                        onOpenPlayer = { navController.navigate("player") }
                    )
                }
                composable("player") {
                    PlayerScreen(viewModel = playerViewModel)
                }composable("settings") {
                    SettingsScreen(viewModel = settingsViewModel)
}
composable("mixes") {
    MixesScreen(
        viewModel = playerViewModel,
        onOpenPlayer = { navController.navigate("player") }
    )
}
                }
                composable(
                    "coming/{section}",
                    arguments = listOf(navArgument("section") { type = NavType.StringType })
                ) { entry ->
                    ComingSoonScreen(title = entry.arguments?.getString("section") ?: "")
                }
            }
            val track = currentTrack
            if (track != null && route != "player") {
                MiniPlayer(
                    track = track,
                    isPlaying = isPlaying,
                    onTogglePlay = { playerViewModel.togglePlay() },
                    onNext = { playerViewModel.next() },
                    onOpenPlayer = { navController.navigate("player") }
                )
            }
            BottomBar(
                selected = selectedSection,
                onSelect = { section ->
                    when (section) {
                        BottomSection.LIBRARY -> navController.navigate("library") {
                            popUpTo("library") { inclusive = false }
                            launchSingleTop = true
                        }
                       BottomSection.SETTINGS -> navController.navigate("settings") {
    popUpTo("library") { inclusive = false }
    launchSingleTop = true
}
BottomSection.MIXES -> navController.navigate("mixes") {
    popUpTo("library") { inclusive = false }
    launchSingleTop = true
}
else -> navController.navigate("coming/${section.label}") {

                        }
                        else -> navController.navigate("coming/${section.label}") {
                            popUpTo("library") { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                }
            )
        }
    }
    }
}
