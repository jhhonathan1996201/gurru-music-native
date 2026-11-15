package com.gurru.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gurru.music.BuildConfig
import com.gurru.music.player.PlayerViewModel
import com.gurru.music.ui.theme.GurruBackground
import com.gurru.music.ui.theme.LocalGurruTheme

/* Ajustes: color del tema (7 temas neón, se aplican al instante en
   toda la app) y créditos. */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val theme = LocalGurruTheme.current
    val currentTheme by viewModel.theme.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(GurruBackground)
            .padding(horizontal = 20.dp)
    ) {
        item {
            Text(
                text = "Ajustes",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 20.dp, bottom = 16.dp)
            )
        }
        item {
            Text(
                text = "Color del tema",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = theme.soft,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        // 7 temas en cuadrícula de 2 columnas para verlos todos de una vez
        items(viewModel.themes.chunked(2), key = { it.firstOrNull()?.name ?: "" }) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (t in row) {
                    val active = t.name == currentTheme.name
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (active) t.accent.copy(alpha = 0.18f)
                                else Color.White.copy(alpha = 0.06f)
                            )
                            .border(
                                width = if (active) 2.dp else 1.dp,
                                color = if (active) t.accent
                                else Color.White.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { viewModel.selectTheme(t.name) }
                            .padding(vertical = 14.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(t.accent, CircleShape)
                            ) {
                                if (active) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Tema activo",
                                        tint = GurruBackground,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Text(
                                text = t.label,
                                fontSize = 14.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active) t.accent else Color.White,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
                // La última fila tiene un solo tema: rellena el hueco
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        item {
            Text(
                text = "Letras",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = theme.soft,
                modifier = Modifier.padding(top = 28.dp, bottom = 8.dp)
            )
        }
        item {
            // Las letras se guardan dentro de la app para verlas sin
            // internet. Al descargar una canción su letra se guarda sola;
            // aquí se bajan las de toda la Biblioteca de una vez.
            val batchRunning by playerViewModel.lyricsBatchRunning.collectAsState()
            val batchDone by playerViewModel.lyricsBatchDone.collectAsState()
            val batchTotal by playerViewModel.lyricsBatchTotal.collectAsState()
            val batchFound by playerViewModel.lyricsBatchFound.collectAsState()
            val batchFinished by playerViewModel.lyricsBatchFinished.collectAsState()
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Las letras se guardan dentro de la app para verlas sin internet. Al descargar una canción, su letra se guarda sola.",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(theme.accent.copy(alpha = 0.18f))
                        .clickable(enabled = !batchRunning) { playerViewModel.downloadLibraryLyrics() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (batchRunning) "Descargando letras… $batchDone/$batchTotal"
                        else "Descargar letras de la Biblioteca",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.accent
                    )
                }
                if (batchRunning) {
                    Text(
                        text = "Guardadas: $batchFound de $batchDone revisadas",
                        fontSize = 13.sp,
                        color = theme.soft,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else if (batchFinished) {
                    Text(
                        text = "Listo: $batchFound letras guardadas de $batchTotal canciones.",
                        fontSize = 13.sp,
                        color = theme.soft,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
        item {
            Text(
                text = "Créditos",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = theme.soft,
                modifier = Modifier.padding(top = 28.dp, bottom = 10.dp)
            )
            Text(
                text = "Desarrollador: Jonathan Aguilar Lopez",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Gurru Music · Reproductor de música",
                fontSize = 14.sp,
                color = theme.soft,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "Powered by Muse",
                fontSize = 12.sp,
                color = theme.soft.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        item {
            // TODO (Fase 2): aquí irán el formato (MP3/M4A) y la calidad
            // de descarga (Baja/Media/Alta), cuando lleguen las descargas.
            Text(
                text = "Versión ${BuildConfig.VERSION_NAME}",
                fontSize = 12.sp,
                color = theme.soft.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 32.dp, bottom = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
