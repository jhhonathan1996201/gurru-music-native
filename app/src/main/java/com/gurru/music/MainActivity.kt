package com.gurru.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* Gurru Music nativa — Fase 0: esqueleto. Una sola pantalla con el nombre
   de la app y el texto "Biblioteca vacía". Nada más: las pantallas y la
   lógica llegan en las fases siguientes. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GurruMusicScreen()
        }
    }
}

private val GurruBackground = Color(0xFF04163C)
private val GurruAccent = Color(0xFF22D3EE)

@Composable
fun GurruMusicScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GurruBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Gurru Music",
                color = GurruAccent,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Biblioteca vacía",
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
