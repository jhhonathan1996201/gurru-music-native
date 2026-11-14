package com.gurru.music.data

/* Canción de la biblioteca. En Fase 1 solo hay música local; los demás
   orígenes (YouTube, descargas) llegan en fases siguientes. */
enum class TrackOrigin { LOCAL }

data class Track(
    val id: String,            // "local-<MediaStore id>"
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Double,   // segundos (MediaStore guarda milisegundos)
    val artUri: String?,       // portada del álbum (content://...albumart/<id>), o null
    val audioUri: String,      // content:// URI del archivo, lista para ExoPlayer
    val folder: String,        // carpeta padre del archivo (pestaña Carpetas)
    val origin: TrackOrigin = TrackOrigin.LOCAL
)
