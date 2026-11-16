package com.gurru.music.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.gurru.music.data.Track
import com.gurru.music.data.TrackOrigin
import com.gurru.music.data.YoutubeRepository
import com.gurru.music.data.normalizeForMatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/* Modo de repetición (ciclo: apagado -> todo -> una -> apagado). */
enum class RepeatMode { OFF, ALL, ONE }

/* Dueño único de los ExoPlayer. Lo comparten la interfaz (PlayerViewModel)
   y el servicio en segundo plano (MusicService), así la música sigue
   sonando al salir de la app o apagar la pantalla. La cola es la lista
   visible actual en el orden mostrado: al tocar una canción suena desde
   ahí y al terminar avanza sola a la siguiente.
   El reproductor carga UNA sola canción a la vez (no la lista completa
   como playlist): así el avance al terminar lo decide siempre este
   manager (aleatorio, repetir todo, repetir una) en vez de que
   ExoPlayer avance solo en orden e ignore el aleatorio y el repetir
   una. Cada canción que termina llega a STATE_ENDED y onTrackEnded()
   elige la siguiente.
   Auto-DJ Fase 1 (mezclas de usuario, solo locales): hay DOS
   reproductores. En una mezcla, cuando falta el crossfade para que
   termine la canción, el segundo reproductor ya arranca la siguiente
   en silencio y los volúmenes se cruzan (el que sale baja con coseno
   y el que entra sube con seno), así no hay silencio entre canciones.
   Los dos se turnan (ping-pong) y `activePlayer` apunta siempre al
   que lleva la canción actual; la sesión (notificación) lo sigue con
   onActivePlayerChanged. El tempo se ajusta con PlaybackParameters
   (velocidad con pitch en 1.0: Sonic estira el tiempo sin cambiar
   el tono) y se aplica a los dos para que el que entra ya venga al
   tempo. */
object PlayerManager {

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var player: ExoPlayer
        private set

    /* Segundo reproductor, solo para el crossfade de las mezclas. */
    private lateinit var crossfadePlayer: ExoPlayer

    /* El reproductor que lleva la canción actual. En modo normal es
       `player`; en una mezcla se turna con `crossfadePlayer`. */
    private lateinit var activePlayer: ExoPlayer

    private val standbyPlayer: ExoPlayer
        get() = if (activePlayer === player) crossfadePlayer else player

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

    /* ── Auto-DJ Fase 1 ───────────────────────────────────────────── */
    private val _mixMode = MutableStateFlow(false)
    val mixMode: StateFlow<Boolean> = _mixMode.asStateFlow()

    /* Tempo del DJ (0.80–1.20, 1.0 = normal). */
    private val _tempo = MutableStateFlow(1.0f)
    val tempo: StateFlow<Float> = _tempo.asStateFlow()

    private val _crossfadeSec = MutableStateFlow(8)
    val crossfadeSec: StateFlow<Int> = _crossfadeSec.asStateFlow()

    private var crossfadeJob: Job? = null
    private var crossfadeInProgress = false
    private var fadingOutPlayer: ExoPlayer? = null

    /* MusicService lo pone para que la sesión (notificación) siga al
       reproductor que suena cuando el crossfade cambia el principal. */
    var onActivePlayerChanged: ((ExoPlayer) -> Unit)? = null

    /* Cada reproductor tiene su propio escucha, pero solo valen los
       eventos del que suena como principal: el que está saliendo en
       el crossfade también llega a ENDED y no debe avanzar la cola. */
    private fun makeListener(forPlayer: ExoPlayer) = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED && forPlayer === activePlayer) onTrackEnded()
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            if (forPlayer === activePlayer) _isPlaying.value = playing
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
        crossfadePlayer = ExoPlayer.Builder(appContext)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        activePlayer = player
        player.addListener(makeListener(player))
        crossfadePlayer.addListener(makeListener(crossfadePlayer))
        /* La posición no llega sola: se lee cada medio segundo mientras
           suena, para mover la barra de progreso. Si leerla fallara por
           algo, no debe cerrar la app: se ignora y se sigue. En una
           mezcla, aquí también se revisa si ya toca el crossfade. */
        scope.launch {
            while (isActive) {
                try {
                    if (::player.isInitialized && activePlayer.isPlaying) {
                        _positionMs.value = activePlayer.currentPosition.coerceAtLeast(0L)
                        val d = activePlayer.duration
                        if (d != C.TIME_UNSET) _durationMs.value = d.coerceAtLeast(0L)
                        maybeStartCrossfade()
                    }
                } catch (_: Exception) {
                }
                delay(500)
            }
        }
    }

    /* Reproduce la lista desde la posición indicada (modo normal, sin
       crossfade: si venía sonando una mezcla, se sale de ella). */
    fun playQueue(tracks: List<Track>, index: Int) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        exitMixMode()
        _queue.value = tracks
        loadAt(index)
    }

    /* Reproduce una mezcla creada por el usuario con crossfade y tempo
       (auto-DJ Fase 1, solo canciones locales). */
    fun playUserMix(tracks: List<Track>, crossfadeSec: Int) {
        if (tracks.isEmpty()) return
        exitMixMode()
        _mixMode.value = true
        _crossfadeSec.value = crossfadeSec.coerceIn(3, 15)
        _queue.value = tracks
        loadAt(0)
    }

    /* Tempo del DJ (0.80–1.20): acelera o frena sin cambiar el tono
       (ExoPlayer/Sonic estira el tiempo con el pitch en 1.0). Se aplica
       a los dos reproductores para que el que entra ya venga al tempo. */
    fun setTempo(speed: Float) {
        val s = speed.coerceIn(0.8f, 1.2f)
        _tempo.value = s
        val params = PlaybackParameters(s, 1.0f)
        try {
            player.setPlaybackParameters(params)
        } catch (_: Exception) {
        }
        try {
            if (::crossfadePlayer.isInitialized) crossfadePlayer.setPlaybackParameters(params)
        } catch (_: Exception) {
        }
    }

    /* Salta a una canción de la cola actual (desde la cola "A
       continuación" o desde los botones anterior/siguiente). */
    fun playAt(index: Int) {
        if (index !in _queue.value.indices) return
        loadAt(index)
    }

    private val queueLock = Any()

    /* Agrega canciones al final de la cola actual sin interrumpir la
       reproducción y evitando duplicados por ID, youtubeId o título+artista. */
    fun appendToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        synchronized(queueLock) {
            val current = _queue.value
            val existingIds = current.mapTo(HashSet()) { it.id }
            val existingYtIds = current.mapNotNullTo(HashSet()) { it.youtubeId }
            val existingTitles = current.mapTo(HashSet()) {
                "${normalizeForMatch(it.artist)}|${normalizeForMatch(it.title)}"
            }

            val unique = tracks.filter { t ->
                val key = "${normalizeForMatch(t.artist)}|${normalizeForMatch(t.title)}"
                t.id !in existingIds &&
                    (t.youtubeId == null || t.youtubeId !in existingYtIds) &&
                    key !in existingTitles
            }
            if (unique.isNotEmpty()) {
                _queue.value = current + unique
            }
        }
    }

    /* El índice que sigue en la cola, respetando aleatorio y repetir.
       null = no hay siguiente (fin de la cola sin repetir todo). */
    private fun computeNextIndex(): Int? {
        val q = _queue.value
        if (q.isEmpty()) return null
        val cur = _currentIndex.value
        if (_shuffle.value && q.size > 1) {
            var next = cur
            while (next == cur) next = (0 until q.size).random()
            return next
        }
        val next = cur + 1
        return when {
            next < q.size -> next
            _repeatMode.value == RepeatMode.ALL -> 0
            else -> null
        }
    }

    /* Si en una mezcla ya falta el crossfade para que termine la
       canción y hay una siguiente, se arranca el crossfade. No aplica
       con repetir una (la canción se repite sola) ni si la canción es
       más corta que el crossfade. */
    private fun maybeStartCrossfade() {
        if (!_mixMode.value || crossfadeInProgress) return
        if (_repeatMode.value == RepeatMode.ONE) return
        val dur = _durationMs.value
        val crossMs = _crossfadeSec.value * 1000L
        if (dur <= crossMs + 2000L) return
        if (computeNextIndex() == null) return
        if (_positionMs.value < dur - crossMs) return
        startCrossfade()
    }

    /* Arranca la siguiente canción en el reproductor que está libre,
       en silencio, y cruza los volúmenes. Desde aquí la canción nueva
       es la actual: la interfaz, la barra y la sesión la siguen a ella. */
    private fun startCrossfade() {
        val nextIndex = computeNextIndex() ?: return
        val nextTrack = _queue.value.getOrNull(nextIndex) ?: return
        if (nextTrack.audioUri.isBlank()) return
        val incoming = standbyPlayer
        val outgoing = activePlayer
        crossfadeInProgress = true
        fadingOutPlayer = outgoing
        try {
            incoming.volume = 0f
            incoming.setPlaybackParameters(PlaybackParameters(_tempo.value, 1.0f))
            incoming.setMediaItem(nextTrack.toMediaItem())
            incoming.prepare()
            startServiceIfNeeded()
            incoming.play()
        } catch (_: Exception) {
            crossfadeInProgress = false
            fadingOutPlayer = null
            return
        }
        _currentIndex.value = nextIndex
        _currentTrack.value = nextTrack
        _durationMs.value = (nextTrack.durationSec * 1000).toLong()
        _positionMs.value = 0L
        activePlayer = incoming
        onActivePlayerChanged?.invoke(incoming)
        /* Fundido de potencia igual: el que sale baja con coseno y el
           que entra sube con seno, para que el volumen total no caiga
           a la mitad del cruce. */
        val crossMs = _crossfadeSec.value * 1000L
        crossfadeJob = scope.launch {
            val steps = 40
            val stepMs = (crossMs / steps).coerceAtLeast(10L)
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                try {
                    outgoing.volume = cos(t * PI.toFloat() / 2f)
                    incoming.volume = sin(t * PI.toFloat() / 2f)
                } catch (_: Exception) {
                }
                if (i < steps) delay(stepMs)
            }
            try {
                outgoing.stop()
                outgoing.clearMediaItems()
                outgoing.volume = 1f
            } catch (_: Exception) {
            }
            crossfadeInProgress = false
            fadingOutPlayer = null
        }
    }

    /* Cancela un crossfade en curso (al saltar de canción a mano):
       se apaga el reproductor que estaba entrando/saliendo y el
       principal vuelve a volumen completo. */
    private fun cancelCrossfade() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        if (crossfadeInProgress) {
            try {
                standbyPlayer.stop()
                standbyPlayer.clearMediaItems()
                standbyPlayer.volume = 1f
            } catch (_: Exception) {
            }
            try {
                activePlayer.volume = 1f
            } catch (_: Exception) {
            }
            crossfadeInProgress = false
            fadingOutPlayer = null
        }
    }

    /* Sale del modo mezcla y deja todo como en la reproducción normal:
       el principal vuelve a ser `player`, el tempo vuelve a 1.0 y la
       sesión vuelve a seguir a `player`. */
    private fun exitMixMode() {
        cancelCrossfade()
        val wasMix = _mixMode.value
        _mixMode.value = false
        try {
            crossfadePlayer.stop()
            crossfadePlayer.clearMediaItems()
            crossfadePlayer.volume = 1f
            player.volume = 1f
        } catch (_: Exception) {
        }
        if (activePlayer !== player) {
            activePlayer = player
            onActivePlayerChanged?.invoke(player)
        }
        if (wasMix) {
            _tempo.value = 1.0f
            val normal = PlaybackParameters(1.0f, 1.0f)
            try {
                player.setPlaybackParameters(normal)
            } catch (_: Exception) {
            }
            try {
                crossfadePlayer.setPlaybackParameters(normal)
            } catch (_: Exception) {
            }
        }
    }

    /* Carga UNA sola canción en el reproductor principal y la pone a
       sonar. Si es una canción de YouTube que todavía no tiene resuelta
       su URL de audio (proviene de un álbum o cola), obtiene la URL de
       stream de forma asíncrona antes de preparar ExoPlayer. */
    private fun loadAt(index: Int) {
        val q = _queue.value
        if (index !in q.indices) return
        val track = q[index]
        cancelCrossfade()

        _currentIndex.value = index
        _currentTrack.value = track
        _durationMs.value = (track.durationSec * 1000).toLong()
        _positionMs.value = 0L

        if (track.origin == TrackOrigin.YOUTUBE && track.audioUri.isBlank() && track.youtubeId != null) {
            scope.launch {
                try {
                    val streamUrl = withContext(Dispatchers.IO) {
                        YoutubeRepository.getStreamUrl(track.youtubeId)
                    }
                    val updated = track.copy(audioUri = streamUrl)
                    val newQueue = _queue.value.toMutableList()
                    if (index in newQueue.indices) {
                        newQueue[index] = updated
                        _queue.value = newQueue
                    }
                    if (_currentIndex.value == index) {
                        _currentTrack.value = updated
                        activePlayer.setMediaItem(updated.toMediaItem())
                        activePlayer.prepare()
                        startServiceIfNeeded()
                        activePlayer.play()
                    }
                    val nextTrack = _queue.value.getOrNull(index + 1)
                    if (nextTrack?.origin == TrackOrigin.YOUTUBE && nextTrack.youtubeId != null) {
                        launch(Dispatchers.IO) {
                            try { YoutubeRepository.prefetch(nextTrack.youtubeId) } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    next()
                }
            }
            return
        }

        activePlayer.setMediaItem(track.toMediaItem())
        activePlayer.prepare()
        startServiceIfNeeded()
        activePlayer.play()

        val nextTrack = q.getOrNull(index + 1)
        if (nextTrack?.origin == TrackOrigin.YOUTUBE && nextTrack.youtubeId != null) {
            scope.launch(Dispatchers.IO) {
                try { YoutubeRepository.prefetch(nextTrack.youtubeId) } catch (_: Exception) {}
            }
        }
    }

    fun togglePlay() {
        if (_queue.value.isEmpty()) return
        if (activePlayer.isPlaying) {
            activePlayer.pause()
            /* Si se pausa en pleno crossfade, el que está saliendo
               también se pausa para que no siga sonando solo. */
            fadingOutPlayer?.let { try { it.pause() } catch (_: Exception) { } }
        } else {
            /* Al terminar la última canción el reproductor queda en
               estado ENDED y play() solo no lo reinicia: hay que
               volver al inicio primero. */
            if (activePlayer.playbackState == Player.STATE_ENDED) {
                activePlayer.seekTo(0)
            }
            startServiceIfNeeded()
            activePlayer.play()
            fadingOutPlayer?.let { try { it.play() } catch (_: Exception) { } }
        }
    }

    fun next() {
        val q = _queue.value
        if (q.isEmpty()) return
        val nextIndex = computeNextIndex()
        if (nextIndex != null) {
            playAt(nextIndex)
        } else {
            /* Fin de la cola: se queda en la última, en pausa. */
            activePlayer.pause()
            fadingOutPlayer?.let { try { it.pause() } catch (_: Exception) { } }
        }
    }

    fun prev() {
        if (_queue.value.isEmpty()) return
        /* Como en la app actual: si lleva más de 3 s, anterior
           reinicia la canción en vez de ir a la anterior. */
        if (activePlayer.currentPosition > 3000) {
            activePlayer.seekTo(0)
            _positionMs.value = 0L
            return
        }
        val cur = _currentIndex.value
        if (cur > 0) playAt(cur - 1) else activePlayer.seekTo(0)
    }

    fun seekTo(positionMs: Long) {
        val pos = positionMs.coerceIn(0L, _durationMs.value)
        activePlayer.seekTo(pos)
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
        cancelCrossfade()
        _mixMode.value = false
        _tempo.value = 1.0f
        try {
            crossfadePlayer.stop()
            crossfadePlayer.clearMediaItems()
            crossfadePlayer.volume = 1f
        } catch (_: Exception) {
        }
        try {
            player.setPlaybackParameters(PlaybackParameters(1.0f, 1.0f))
            crossfadePlayer.setPlaybackParameters(PlaybackParameters(1.0f, 1.0f))
        } catch (_: Exception) {
        }
        player.stop()
        player.clearMediaItems()
        player.volume = 1f
        activePlayer = player
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
            activePlayer.seekTo(0)
            activePlayer.play()
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
