package com.gurru.music.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/* Colores neón de Gurru Music, los mismos de la app actual (tema "azul"
   por defecto): fondo azul oscuro #04163C, cian #22D3EE, azul #0284C7
   y azul suave #BAE6FD. */
val GurruBackground = Color(0xFF04163C)
val GurruAccent = Color(0xFF22D3EE)
val GurruAccent2 = Color(0xFF0284C7)
val GurruSoft = Color(0xFFBAE6FD)
val GurruSoftBlue = Color(0xFF7DD3FC)
val GurruPillTop = Color(0xFF021432)
val GurruPillBottom = Color(0xFF010A24)
val GurruRowBackground = Color(0x14FFFFFF)

/* Degradado oscuro de las píldoras flotantes (mini reproductor y barra
   inferior), igual que en la app actual. */
fun gurruPillBrush(): Brush = Brush.verticalGradient(listOf(GurruPillTop, GurruPillBottom))
