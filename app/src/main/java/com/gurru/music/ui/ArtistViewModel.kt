package com.gurru.music.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gurru.music.data.ArtistAlbum
import com.gurru.music.data.ArtistRepository
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
        // YoutubeRepository ya se inicializa en YoutubeViewModel, pero por
        // si esta pantalla se abre primero, asegurarse de que esté listo.
        YoutubeRepository.init(application)
    }

    /* Carga el top 10 y los álbumes del artista. Si ya se cargó este
       mismo artista y tiene canciones, no vuelve a cargar. */
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

    /* Reproduce todo el top 10: resuelve las URLs en paralelo y pone
       la cola en el reproductor. Las URLs se resuelven en IO y
       PlayerManager se llama en Main, como en YoutubeViewModel. */
    fun playAll(onOpenPlayer: () -> Unit) {
        val songs = _topSongs.value
        if (songs.isEmpty()) return
        viewModelScope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) {
                    songs.map { result ->
                        async {
                            try {
                                val url = YoutubeRepository.getStreamUrl(result.videoId)
                                result.toTrack(url)
                            } catch (_: Exception) { null }
                        }
                    }.awaitAll().filterNotNull()
                }
                if (tracks.isNotEmpty()) {
                    PlayerManager.playQueue(tracks, 0)
                    onOpenPlayer()
                }
            } catch (_: Exception) { }
        }
    }
}
