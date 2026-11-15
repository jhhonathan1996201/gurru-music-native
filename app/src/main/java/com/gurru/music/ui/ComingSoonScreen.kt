package com.gurru.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gurru.music.ui.theme.GurruBackground
import com.gurru.music.ui.theme.LocalGurruTheme

/* Marcador de posición para las secciones que llegan en fases
   posteriores (Descargas, YouTube, Mezclas, Ajustes). */
@Composable
fun ComingSoonScreen(title: String, modifier: Modifier = Modifier) {
    val theme = LocalGurruTheme.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(GurruBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Próximamente",
                color = theme.soft,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
