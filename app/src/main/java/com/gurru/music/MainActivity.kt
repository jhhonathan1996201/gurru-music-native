package com.gurru.music

import android.net.Uri
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
import com.gurru.music.ui.ArtistScreen
import com.gurru.music.ui.ArtistViewModel
import com.gurru.music.ui.ComingSoonScreen
import com.gurru.music.ui.DownloadsScreen
import com.gurru.music.ui.DownloadsViewModel
import com.gurru.music.ui.LibraryScreen
import com.gurru.music.ui.MixEditorScreen
import com.gurru.music.ui.MixesScreen
import com.gurru.music.ui.PlayerScreen
import com.gurru.music.ui.SettingsScreen
import com.gurru.music.ui.SettingsViewModel
import com.gurru.music.ui.UserMixesViewModel
import com.gurru.music.ui.YoutubeScreen
import com.gurru.music.ui.YoutubeViewModel
import com.gurru.music.ui.components.BottomBar
import com.gurru.music.ui.components.BottomSection
import com.gurru.music.ui.components.MiniPlayer
import com.gurru.music.ui.theme.GurruBackground
import com.gurru.music.ui.theme.LocalGurruTheme

/* Gurru Music nativa — Fase 1: Biblioteca local + reproductor.
   Fase 3: YouTube online.
   Una sola Activity con Navigation Compose. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GurruApp() }
    }
}

@Composable
fun GurruApp() {
    val playerViewModel: PlayerViewModel   = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()
    val youtubeViewModel: YoutubeViewModel = viewModel()   // Fase 3
    val downloadsViewModel: DownloadsViewModel = viewModel() // Descargas
    val artistViewModel: ArtistViewModel = viewModel()       // Fase 4
    val userMixesViewModel: UserMixesViewModel = viewModel() // Mezclas de usuario (auto-DJ)

    val currentTheme by settingsViewModel.theme.collectAsState()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val isPlaying    by playerViewModel.isPlaying.collectAsState()

    val selectedSection = when {
        route == "library" || route == "player" -> BottomSection.LIBRARY
        route == "settings"                     -> BottomSection.SETTINGS
        route == "mixes" || route == "mixEditor" || route?.startsWith("mixEditor/") == true -> BottomSection.MIXES
        route == "youtube"                      -> BottomSection.YOUTUBE   // Fase 3
        route == "downloads"                    -> BottomSection.DOWNLOADS // Descargas
        route?.startsWith("artist/") == true    -> BottomSection.YOUTUBE   // Fase 4

        route?.startsWith("coming/") == true -> when (
            backStackEntry?.arguments?.getString("section")
        ) {
            "Mezclas"   -> BottomSection.MIXES
            else        -> BottomSection.LIBRARY
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
                    navController    = navController,
                    startDestination = "library",
                    modifier         = Modifier.weight(1f)
                ) {
                    composable("library") {
                        LibraryScreen(
                            viewModel    = playerViewModel,
                            onOpenPlayer = { navController.navigate("player") }
                        )
                    }
                    composable("player") {
                        PlayerScreen(
                            viewModel = playerViewModel,
                            downloadsViewModel = downloadsViewModel
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            playerViewModel = playerViewModel
                        )
                    }
                    composable("mixes") {
                        MixesScreen(
                            viewModel      = playerViewModel,
                            mixesViewModel = userMixesViewModel,
                            onOpenPlayer   = { navController.navigate("player") },
                            onCreateMix    = { navController.navigate("mixEditor") },
                            onEditMix      = { id -> navController.navigate("mixEditor/$id") }
                        )
                    }
                    // ── Editor de mezclas (auto-DJ) ──────────────────────
                    composable("mixEditor") {
                        MixEditorScreen(
                            playerViewModel = playerViewModel,
                            mixesViewModel  = userMixesViewModel,
                            mixId           = null,
                            onDone          = { navController.popBackStack() },
                            onBack          = { navController.popBackStack() }
                        )
                    }
                    composable(
                        "mixEditor/{mixId}",
                        arguments = listOf(navArgument("mixId") { type = NavType.StringType })
                    ) { entry ->
                        MixEditorScreen(
                            playerViewModel = playerViewModel,
                            mixesViewModel  = userMixesViewModel,
                            mixId           = entry.arguments?.getString("mixId"),
                            onDone          = { navController.popBackStack() },
                            onBack          = { navController.popBackStack() }
                        )
                    }
                    // ── Fase 3: YouTube online ──────────────────────────────
                    composable("youtube") {
                        YoutubeScreen(
                            viewModel          = youtubeViewModel,
                            downloadsViewModel = downloadsViewModel,
                            onOpenPlayer       = { navController.navigate("player") },
                            onOpenArtist       = { artist ->
                                navController.navigate("artist/${Uri.encode(artist)}")
                            }
                        )
                    }
                    // ── Fase 4: página del artista ───────────────────────
                    composable(
                        "artist/{artistName}",
                        arguments = listOf(navArgument("artistName") { type = NavType.StringType })
                    ) { entry ->
                        val raw = entry.arguments?.getString("artistName") ?: ""
                        ArtistScreen(
                            artistName = Uri.decode(raw),
                            artistViewModel = artistViewModel,
                            youtubeViewModel = youtubeViewModel,
                            downloadsViewModel = downloadsViewModel,
                            onOpenPlayer = { navController.navigate("player") },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    // ── Pestaña Descargas ────────────────────────────────────
                    composable("downloads") {
                        DownloadsScreen(
                            viewModel    = downloadsViewModel,
                            onOpenPlayer = { navController.navigate("player") }
                        )
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
                        track         = track,
                        isPlaying     = isPlaying,
                        onTogglePlay  = { playerViewModel.togglePlay() },
                        onNext        = { playerViewModel.next() },
                        onOpenPlayer  = { navController.navigate("player") }
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
                            // ── Pestaña Descargas ──────────────────────────
                            BottomSection.DOWNLOADS -> navController.navigate("downloads") {
                                popUpTo("library") { inclusive = false }
                                launchSingleTop = true
                            }
                            // ── Fase 3: ya no va a "Próximamente" ──────────
                            BottomSection.YOUTUBE -> navController.navigate("youtube") {
                                popUpTo("library") { inclusive = false }
                                launchSingleTop = true
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
