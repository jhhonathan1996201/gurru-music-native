package com.gurru.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import com.gurru.music.ui.theme.GurruBackground
import com.gurru.music.ui.theme.LocalGurruTheme

/* Ajustes: color del tema (7 temas neón, se aplican al instante en
   toda la app) y créditos. */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
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
        items(viewModel.themes, key = { it.name }) { t ->
            val active = t.name == currentTheme.name
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { viewModel.selectTheme(t.name) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .background(t.accent, CircleShape)
                        .then(
                            if (active) Modifier.border(2.dp, Color.White, CircleShape)
                            else Modifier
                        )
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
                    fontSize = 16.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) t.accent else Color.White,
                    modifier = Modifier.padding(start = 14.dp)
                )
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
