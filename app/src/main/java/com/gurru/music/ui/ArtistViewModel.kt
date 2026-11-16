package com.gurru.music.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gurru.music.data.ArtistAlbum
import com.gurru.music.data.ArtistCardData
import com.gurru.music.data.ArtistRepository
import com.gurru.music.data.Track
import com.gurru.music.data.TrackOrigin
import com.gurru.music.data.YoutubeRepository
import com.gurru.music.data.YoutubeSearchResult
import com.gurru.music.player.PlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ViewModel de la página del artista (Fase 4). Carga el top 10 y los
   álbumes de un artista, y las canciones de un álbum al tocarlo.
   Reproduce con PlayerManager, el mismo reproductor que usa
   YoutubeViewModel, sin duplicar el cableado de ExoPlayer. */
class ArtistViewModel(application: Application) : AndroidViewModel(application) {

    private val _artistName = MutableStateFlow("")
    val artistName: StateFlow<String> = _artistName.asStateFlow()

    private val _artistCard = MutableStateFlow<ArtistCardData?>(null)
    val artistCard: StateFlow<ArtistCardData?> = _artistCard.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _topSongs = MutableStateFlow<List<YoutubeSearchResult>>(emptyList())
    val topSongs: StateFlow<List<YoutubeSearchResult>> = _topSongs.asStateFlow()

    private val _albums = MutableStateFlow<List<ArtistAlbum>>(emptyList())
    val albums: StateFlow<List<ArtistAlbum>> = _albums.asStateFlow()

    private val _selectedAlbum = MutableStateFlow<ArtistAlbum?>(null)
    val selectedAlbum: StateFlow<ArtistAlbum?> = _selectedAlbum.asStateFlow()

    private val _albumTracks = MutableStateFlow<List<YoutubeSearchResult>>(emptyList())
    val albumTracks: StateFlow<List<YoutubeSearchResult>> = _albumTracks.asStateFlow()

    private val _loadingAlbum = MutableStateFlow(false)
    val loadingAlbum: StateFlow<Boolean> = _loadingAlbum.asStateFlow()

    private var loadedArtist = ""

    init {
        YoutubeRepository.init(application)
    }

    /* Carga el top 10, los álbumes y la información del artista. */
    fun load(artist: String) {
        val name = artist.trim()
        if (name.isEmpty()) return
        if (name == loadedArtist && _topSongs.value.isNotEmpty()) return
        loadedArtist = name
        _artistName.value = name
        _selectedAlbum.value = null
        _albumTracks.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            try {
                val card = ArtistRepository.getArtistCard(name)
                _artistCard.value = card
                val songs = ArtistRepository.getTopSongs(name)
                val albums = ArtistRepository.getAlbums(name)
                _topSongs.value = songs
                _albums.value = albums
            } catch (_: Exception) { }
            finally { _loading.value = false }
        }
    }

    /* Al tocar un álbum, carga sus canciones (de iTunes, resueltas en
       YouTube Music) y las muestra en la pantalla. */
    fun selectAlbum(album: ArtistAlbum) {
        _selectedAlbum.value = album
        _albumTracks.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            _loadingAlbum.value = true
            try {
                _albumTracks.value = ArtistRepository.getAlbumTracks(album, _artistName.value)
            } catch (_: Exception) { }
            finally { _loadingAlbum.value = false }
        }
    }

    fun clearSelectedAlbum() {
        _selectedAlbum.value = null
        _albumTracks.value = emptyList()
    }

    /* Reproduce una canción de un álbum y carga TODO el álbum en la cola
       "A continuación" del reproductor. La canción seleccionada empieza de inmediato
       y las demás canciones del álbum quedan en la cola listas para reproducirse. */
    fun playAlbumTrack(
        album: ArtistAlbum,
        albumTracks: List<YoutubeSearchResult>,
        selectedIndex: Int,
        onOpenPlayer: () -> Unit
    ) {
        if (albumTracks.isEmpty() || selectedIndex !in albumTracks.indices) return
        viewModelScope.launch {
            try {
                // 1. Crear la lista completa de Tracks para la cola
                val fullQueue = albumTracks.mapIndexed { _, res ->
                    Track(
                        id = "youtube-${res.videoId}",
                        title = res.title,
                        artist = if (res.artist.isNotBlank() && res.artist != "Desconocido") res.artist else album.artistName,
                        album = album.name,
                        durationSec = res.durationSec,
                        artUri = res.thumbnailUrl ?: album.artworkUrl,
                        audioUri = "", // Se resuelve al reproducir
                        folder = "",
                        origin = TrackOrigin.YOUTUBE,
                        youtubeId = res.videoId
                    )
                }

                // 2. Extraer URL de la canción seleccionada para arrancar al instante
                val targetRes = albumTracks[selectedIndex]
                val streamUrl = withContext(Dispatchers.IO) {
                    YoutubeRepository.getStreamUrl(targetRes.videoId)
                }
                val readyQueue = fullQueue.toMutableList().also {
                    it[selectedIndex] = it[selectedIndex].copy(audioUri = streamUrl)
                }

                // 3. Pasar toda la cola a PlayerManager y abrir el reproductor
                PlayerManager.playQueue(readyQueue, selectedIndex)
                onOpenPlayer()

                // 4. Precargar en background las siguientes canciones del álbum
                withContext(Dispatchers.IO) {
                    val nextIndices = (selectedIndex + 1 until minOf(selectedIndex + 4, albumTracks.size))
                    for (i in nextIndices) {
                        try {
                            YoutubeRepository.prefetch(albumTracks[i].videoId)
                        } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) {
                // Fallback: reproducir al menos la canción pulsada
                try {
                    val targetRes = albumTracks[selectedIndex]
                    val url = withContext(Dispatchers.IO) {
                        YoutubeRepository.getStreamUrl(targetRes.videoId)
                    }
                    PlayerManager.playQueue(listOf(targetRes.toTrack(url)), 0)
                    onOpenPlayer()
                } catch (_: Exception) { }
            }
        }
    }

    /* Reproduce todo el top 10: carga las 10 canciones en la cola y pone
       a sonar la primera al instante sin esperar a resolver todas. */
    fun playAll(onOpenPlayer: () -> Unit) {
        val songs = _topSongs.value
        if (songs.isEmpty()) return
        viewModelScope.launch {
            try {
                val fullQueue = songs.map { res ->
                    Track(
                        id = "youtube-${res.videoId}",
                        title = res.title,
                        artist = res.artist.ifBlank { _artistName.value },
                        album = "",
                        durationSec = res.durationSec,
                        artUri = res.thumbnailUrl,
                        audioUri = "",
                        folder = "",
                        origin = TrackOrigin.YOUTUBE,
                        youtubeId = res.videoId
                    )
                }
                val firstUrl = withContext(Dispatchers.IO) {
                    YoutubeRepository.getStreamUrl(songs[0].videoId)
                }
                val readyQueue = fullQueue.toMutableList().also {
                    it[0] = it[0].copy(audioUri = firstUrl)
                }
                PlayerManager.playQueue(readyQueue, 0)
                onOpenPlayer()

                // Precargar la segunda
                if (songs.size > 1) {
                    withContext(Dispatchers.IO) {
                        try { YoutubeRepository.prefetch(songs[1].videoId) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) { }
        }
    }
}
