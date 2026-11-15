package com.gurru.music.data

/* Canción de la biblioteca.
   LOCAL  → música del teléfono (Fase 1).
   YOUTUBE → stream de YouTube (Fase 3). */
enum class TrackOrigin { LOCAL, YOUTUBE }

data class Track(
    val id: String,            // "local-<MediaStore id>" | "youtube-<videoId>"
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Double,   // segundos
    val artUri: String?,       // content://...albumart/<id> (local) | thumbnail HTTPS (youtube)
    val audioUri: String,      // content:// URI del archivo (local) | URL del stream (youtube)
    val folder: String,        // carpeta del archivo (local) | "" (youtube)
    val origin: TrackOrigin = TrackOrigin.LOCAL,
    val youtubeId: String? = null  // ID del video; solo cuando origin == YOUTUBE
)
