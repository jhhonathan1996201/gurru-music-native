package com.gurru.music.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.gurru.music.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/* Modo de repetición (ciclo: apagado -> todo -> una -> apagado). */
enum class RepeatMode { OFF, ALL, ONE }

/* Dueño único del ExoPlayer. Lo comparten la interfaz (PlayerViewModel)
   y el servicio en segundo plano (MusicService), así la música sigue
   sonando al salir de la app o apagar la pantalla. La cola es la lista
   visible actual en el orden mostrado: al tocar una canción suena desde
   ahí y al terminar avanza sola a la siguiente.
   El reproductor carga UNA sola canción a la vez (no la lista completa
   como playlist): así el avance al terminar lo decide siempre este
   manager (aleatorio, repetir todo, repetir una) en vez de que
   ExoPlayer avance solo en orden e ignore el aleatorio y el repetir
   una. Cada canción que termina llega a STATE_ENDED y onTrackEnded()
   elige la siguiente. */
object PlayerManager {

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var player: ExoPlayer
        private set

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) onTrackEnded()
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            _isPlaying.value = playing
        }
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(appContext)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(listener)
        /* La posición no llega sola: se lee cada medio segundo mientras
           suena, para mover la barra de progreso. Si leerla fallara por
           algo, no debe cerrar la app: se ignora y se sigue. */
        scope.launch {
            while (isActive) {
                try {
                    if (::player.isInitialized && player.isPlaying) {
                        _positionMs.value = player.currentPosition.coerceAtLeast(0L)
                        val d = player.duration
                        if (d != C.TIME_UNSET) _durationMs.value = d.coerceAtLeast(0L)
                    }
                } catch (_: Exception) {
                }
                delay(500)
            }
        }
    }

    /* Reproduce la lista desde la posición indicada. */
    fun playQueue(tracks: List<Track>, index: Int) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        _queue.value = tracks
        loadAt(index)
    }

    /* Salta a una canción de la cola actual (desde la cola "A
       continuación" o desde los botones anterior/siguiente). */
    fun playAt(index: Int) {
        if (index !in _queue.value.indices) return
        loadAt(index)
    }

    /* Agrega canciones al final de la cola actual sin interrumpir la
       reproducción. Lo usa la radio de YouTube para extender la cola
       conforme van resolviéndose las URLs de los tracks relacionados. */
    fun appendToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        _queue.value = _queue.value + tracks
    }

    /* Carga UNA sola canción en el reproductor y la pone a sonar. */
    private fun loadAt(index: Int) {
        val track = _queue.value[index]
        player.setMediaItem(track.toMediaItem())
        player.prepare()
        _currentIndex.value = index
        _currentTrack.value = track
        _durationMs.value = (track.durationSec * 1000).toLong()
        _positionMs.value = 0L
        startServiceIfNeeded()
        player.play()
    }

    fun togglePlay() {
        if (_queue.value.isEmpty()) return
        if (player.isPlaying) {
            player.pause()
        } else {
            /* Al terminar la última canción el reproductor queda en
               estado ENDED y play() solo no lo reinicia: hay que
               volver al inicio primero. */
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0)
            }
            startServiceIfNeeded()
            player.play()
        }
    }

    fun next() {
        val q = _queue.value
        if (q.isEmpty()) return
        val cur = _currentIndex.value
        if (_shuffle.value && q.size > 1) {
            var next = cur
            while (next == cur) next = (0 until q.size).random()
            playAt(next)
            return
        }
        val next = cur + 1
        if (next < q.size) {
            playAt(next)
        } else if (_repeatMode.value == RepeatMode.ALL) {
            playAt(0)
        } else {
            /* Fin de la cola: se queda en la última, en pausa. */
            player.pause()
        }
    }

    fun prev() {
        if (_queue.value.isEmpty()) return
        /* Como en la app actual: si lleva más de 3 s, anterior
           reinicia la canción en vez de ir a la anterior. */
        if (player.currentPosition > 3000) {
            player.seekTo(0)
            _positionMs.value = 0L
            return
        }
        val cur = _currentIndex.value
        if (cur > 0) playAt(cur - 1) else player.seekTo(0)
    }

    fun seekTo(positionMs: Long) {
        val pos = positionMs.coerceIn(0L, _durationMs.value)
        player.seekTo(pos)
        _positionMs.value = pos
    }

    fun toggleShuffle() {
        _shuffle.value = !_shuffle.value
    }

    fun cycleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    /* Detiene todo y apaga el servicio (botón "Detener" de la
       notificación). */
    fun stop() {
        player.stop()
        player.clearMediaItems()
        _queue.value = emptyList()
        _currentIndex.value = -1
        _currentTrack.value = null
        _isPlaying.value = false
        _positionMs.value = 0L
        _durationMs.value = 0L
        try {
            appContext.stopService(Intent(appContext, MusicService::class.java))
        } catch (_: Exception) {
        }
        serviceStarted = false
    }

    private fun onTrackEnded() {
        if (_repeatMode.value == RepeatMode.ONE) {
            player.seekTo(0)
            player.play()
            return
        }
        next()
    }

    /* El servicio en primer plano es lo que mantiene la música sonando
       fuera de la app; se arranca una sola vez al empezar a sonar, no
       en cada cambio de canción (arrancarlo de nuevo en segundo plano
       es lo que podía cerrar la app al saltar canciones). */
    private var serviceStarted = false

    private fun startServiceIfNeeded() {
        if (serviceStarted) return
        try {
            appContext.startForegroundService(Intent(appContext, MusicService::class.java))
            serviceStarted = true
        } catch (_: Exception) {
        }
    }

    private fun Track.toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(artUri?.let { Uri.parse(it) })
            .build()
        return MediaItem.Builder()
            .setUri(audioUri)
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }
}
