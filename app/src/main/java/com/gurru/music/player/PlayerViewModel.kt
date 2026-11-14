package com.gurru.music.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gurru.music.data.MediaStoreScanner
import com.gurru.music.data.PlayCountRepository
import com.gurru.music.data.Track
import com.gurru.music.data.TrackOrigin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/* Estado que comparten la Biblioteca y el Reproductor: la lista de
   canciones locales escaneadas, la búsqueda, el permiso de audio y todo
   el estado del reproductor (que vive en PlayerManager). */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = MediaStoreScanner(application.applicationContext)
    private val playCounts = PlayCountRepository(application.applicationContext)


    val queue = PlayerManager.queue
    val currentTrack = PlayerManager.currentTrack
    val currentIndex = PlayerManager.currentIndex
    val isPlaying = PlayerManager.isPlaying
    val positionMs = PlayerManager.positionMs
    val durationMs = PlayerManager.durationMs
    val shuffle = PlayerManager.shuffle
    val repeatMode = PlayerManager.repeatMode

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _hasPermission = MutableStateFlow(scanner.hasPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    init {
    if (_hasPermission.value) scanLibrary()
    // Cada vez que empieza una canción local distinta, suma 1 a su
    // contador (base de "Mezclas para ti"). El guardado evita contar
    // dos veces la misma canción si el estado se reemite.
    viewModelScope.launch {
        var lastCountedId: String? = null
        currentTrack.collect { track ->
            if (track != null && track.origin == TrackOrigin.LOCAL && track.id != lastCountedId) {
                lastCountedId = track.id
                playCounts.increment(track.id)
            }
        }
    }
}


    fun checkPermission() {
        _hasPermission.value = scanner.hasPermission()
        if (_hasPermission.value && _tracks.value.isEmpty()) scanLibrary()
    }

    fun onPermissionResult(granted: Boolean) {
        _hasPermission.value = granted
        if (granted) scanLibrary()
    }

    fun requiredPermission(): String = scanner.requiredPermission()

    fun setQuery(q: String) {
        _query.value = q
    }

    fun scanLibrary() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            try {
                _tracks.value = scanner.scan()
            } catch (_: Exception) {
                _tracks.value = emptyList()
            } finally {
                _scanning.value = false
            }
        }
    }

    fun playQueue(tracks: List<Track>, index: Int) = PlayerManager.playQueue(tracks, index)
    fun playAt(index: Int) = PlayerManager.playAt(index)
    fun togglePlay() = PlayerManager.togglePlay()
    fun next() = PlayerManager.next()
    fun prev() = PlayerManager.prev()
    fun seekTo(positionMs: Long) = PlayerManager.seekTo(positionMs)
    fun toggleShuffle() = PlayerManager.toggleShuffle()
    fun cycleRepeat() = PlayerManager.cycleRepeat()
}
