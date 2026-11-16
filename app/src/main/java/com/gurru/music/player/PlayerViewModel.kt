package com.gurru.music.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gurru.music.data.LyricsCache
import com.gurru.music.data.LyricsData
import com.gurru.music.data.LyricsRepository
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
    val mixMode = PlayerManager.mixMode
    val tempo = PlayerManager.tempo

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _hasPermission = MutableStateFlow(scanner.hasPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    // ── Letras de canciones (multi-fuente y sincronizadas) ─────────────────────
    private val _lyricsData = MutableStateFlow<LyricsData?>(null)
    val lyricsData: StateFlow<LyricsData?> = _lyricsData.asStateFlow()

    private val _loadingLyrics = MutableStateFlow(false)
    val loadingLyrics: StateFlow<Boolean> = _loadingLyrics.asStateFlow()

    private var currentLyricsTrackId: String? = null

    // ── Descargar letras de toda la Biblioteca ──────────────────────────
    private val _lyricsBatchRunning = MutableStateFlow(false)
    val lyricsBatchRunning: StateFlow<Boolean> = _lyricsBatchRunning.asStateFlow()
    private val _lyricsBatchDone = MutableStateFlow(0)
    val lyricsBatchDone: StateFlow<Int> = _lyricsBatchDone.asStateFlow()
    private val _lyricsBatchTotal = MutableStateFlow(0)
    val lyricsBatchTotal: StateFlow<Int> = _lyricsBatchTotal.asStateFlow()
    private val _lyricsBatchFound = MutableStateFlow(0)
    val lyricsBatchFound: StateFlow<Int> = _lyricsBatchFound.asStateFlow()
    private val _lyricsBatchFinished = MutableStateFlow(false)
    val lyricsBatchFinished: StateFlow<Boolean> = _lyricsBatchFinished.asStateFlow()

    /* Baja las letras de todas las canciones locales de la Biblioteca y
       las guarda en el caché de la app (para verlas sin internet). Las
       que ya tienen letra guardada se saltan. Corre en segundo plano y
       reporta el avance para mostrarlo en Ajustes. */
    fun downloadLibraryLyrics() {
        if (_lyricsBatchRunning.value) return
        val localTracks = _tracks.value.filter { it.origin == TrackOrigin.LOCAL }
        if (localTracks.isEmpty()) return
        viewModelScope.launch {
            _lyricsBatchRunning.value = true
            _lyricsBatchFinished.value = false
            _lyricsBatchTotal.value = localTracks.size
            _lyricsBatchDone.value = 0
            _lyricsBatchFound.value = 0
            var found = 0
            for ((index, track) in localTracks.withIndex()) {
                try {
                    if (LyricsCache.has(track.artist, track.title)) {
                        found++
                    } else {
                        val data = LyricsRepository.getLyrics(track)
                        if (data != null) found++
                    }
                } catch (_: Exception) {
                }
                _lyricsBatchDone.value = index + 1
                _lyricsBatchFound.value = found
            }
            _lyricsBatchRunning.value = false
            _lyricsBatchFinished.value = true
        }
    }

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

                // Cargar letras automáticamente cuando cambia la pista
                if (track != null && track.id != currentLyricsTrackId) {
                    currentLyricsTrackId = track.id
                    _lyricsData.value = null
                    loadLyricsForCurrentTrack()
                } else if (track == null) {
                    currentLyricsTrackId = null
                    _lyricsData.value = null
                }
            }
        }
    }

    fun loadLyricsForCurrentTrack() {
        val track = currentTrack.value ?: return
        viewModelScope.launch {
            _loadingLyrics.value = true
            try {
                _lyricsData.value = LyricsRepository.getLyrics(track)
            } catch (_: Exception) {
                _lyricsData.value = null
            } finally {
                _loadingLyrics.value = false
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
    fun playUserMix(tracks: List<Track>, crossfadeSec: Int) = PlayerManager.playUserMix(tracks, crossfadeSec)
    fun setTempo(speed: Float) = PlayerManager.setTempo(speed)
}
