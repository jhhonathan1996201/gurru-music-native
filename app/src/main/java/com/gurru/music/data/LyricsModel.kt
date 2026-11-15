package com.gurru.music.data

/* Representa una sola línea de la letra con su marca de tiempo en milisegundos */
data class LyricLine(
    val timeMs: Long,  // Tiempo en milisegundos (-1L si no tiene sincronización)
    val text: String   // Texto de la línea
)

/* Representa la letra completa de una canción */
data class LyricsData(
    val title: String,
    val artist: String,
    val lines: List<LyricLine>,
    val isSynced: Boolean,      // true si tiene marcas de tiempo interactivas (LRC)
    val source: String          // e.g. "LRCLIB (Sincronizada)", "YouTube Music (Musixmatch)"
)
