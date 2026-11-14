package com.gurru.music.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/* Colores neón de Gurru Music, los mismos de la app actual. El fondo
   (#04163C) y las píldoras oscuras NO cambian con el tema; solo
   cambian accent, accent2 y soft. El tema por defecto es azul. */
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

/* Un tema de color: nombre interno (el que se guarda, igual que en la
   app actual: "azul", "rojo"...), nombre visible y los colores que
   cambian con el tema. softBlue es un tono claro de la familia del
   acento (el *-300 de Tailwind, como el #7DD3FC del tema azul) y se usa
   para bordes y líneas suaves. */
data class GurruTheme(
    val name: String,
    val label: String,
    val accent: Color,
    val accent2: Color,
    val soft: Color,
    val softBlue: Color
)

/* Los 7 temas neón, con los colores EXACTOS de la app actual
   (html[data-accent] en www/index.html). */
val GurruThemes = listOf(
    GurruTheme("azul", "Azul", GurruAccent, GurruAccent2, GurruSoft, GurruSoftBlue),
    GurruTheme("rojo", "Rojo", Color(0xFFFB4D6D), Color(0xFFBE123C), Color(0xFFFECDD3), Color(0xFFFDA4AF)),
    GurruTheme("naranja", "Naranja", Color(0xFFFB923C), Color(0xFFEA580C), Color(0xFFFED7AA), Color(0xFFFDBA74)),
    GurruTheme("verde", "Verde", Color(0xFF34D399), Color(0xFF059669), Color(0xFFA7F3D0), Color(0xFF6EE7B7)),
    GurruTheme("rosa", "Rosa", Color(0xFFF472B6), Color(0xFFDB2777), Color(0xFFFBCFE8), Color(0xFFF9A8D4)),
    GurruTheme("violeta", "Violeta", Color(0xFFA78BFA), Color(0xFF7C3AED), Color(0xFFDDD6FE), Color(0xFFC4B5FD)),
    GurruTheme("dorado", "Dorado", Color(0xFFFBBF24), Color(0xFFD97706), Color(0xFFFDE68A), Color(0xFFFCD34D))
)

/* Busca un tema por su nombre guardado; si no existe, azul. */
fun themeByName(name: String): GurruTheme =
    GurruThemes.firstOrNull { it.name == name } ?: GurruThemes[0]

/* El tema actual de toda la app. MainActivity lo provee con el tema
   guardado; por defecto es azul. */
val LocalGurruTheme = compositionLocalOf { themeByName("azul") }
