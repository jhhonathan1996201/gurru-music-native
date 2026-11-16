package com.gurru.music.data

/* Resultado de búsqueda de YouTube, antes de resolver la URL del stream.
   Se guarda en DataStore para restaurar la última búsqueda. */
data class YoutubeSearchResult(
    val videoId: String,
    val title: String,
    val artist: String,     // artista real: de metadatos, no el canal
    val durationSec: Double,
    val thumbnailUrl: String?
) {
    /* Convierte este resultado en un Track reproducible, una vez que ya
       tenemos la URL del stream (obtenida por YoutubeRepository). */
    fun toTrack(streamUrl: String) = Track(
        id          = "youtube-$videoId",
        title       = title,
        artist      = artist,
        album       = "",
        durationSec = durationSec,
        artUri      = thumbnailUrl,
        audioUri    = streamUrl,
        folder      = "",
        origin      = TrackOrigin.YOUTUBE,
        youtubeId   = videoId
    )
}

/* Información del artista para la tarjeta de búsqueda y la cabecera de la página de artista. */
data class ArtistCardData(
    val name: String,
    val imageUrl: String? = null,
    val subtitle: String? = null,
    val browseId: String? = null
)
