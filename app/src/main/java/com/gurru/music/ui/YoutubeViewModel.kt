package com.gurru.music.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gurru.music.data.TrackOrigin
import com.gurru.music.data.YoutubeRepository
import com.gurru.music.data.YoutubeSearchResult
import com.gurru.music.player.PlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* Estado de preparación de una canción de YouTube. */
sealed class PrepareState {
    object Idle      : PrepareState()
    object Preparing : PrepareState()
    data class Error(val message: String, val result: YoutubeSearchResult) : PrepareState()
}

/* ViewModel de la pestaña YouTube. Gestiona búsqueda, reproducción,
   radio automática y persistencia de la última búsqueda. */
class YoutubeViewModel(application: Application) : AndroidViewModel(application) {

    private val _query   = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<YoutubeSearchResult>>(emptyList())
    val results: StateFlow<List<YoutubeSearchResult>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _prepareState = MutableStateFlow<PrepareState>(PrepareState.Idle)
    val prepareState: StateFlow<PrepareState> = _prepareState.asStateFlow()

    private val _recents = MutableStateFlow<List<YoutubeSearchResult>>(emptyList())
    val recents: StateFlow<List<YoutubeSearchResult>> = _recents.asStateFlow()

    // Video ID del track que ya generó radio (para no pedirlo dos veces)
    private var lastRadioId: String? = null

    init {
        YoutubeRepository.init(application)

        // Restaurar la última búsqueda y los recientes al inicio
        viewModelScope.launch {
            val (q, r) = YoutubeRepository.getLastSearch()
            _query.value   = q
            _results.value = r
            _recents.value = YoutubeRepository.getRecents()
        }

        // Observar cambios de canción: lanzar radio y precargar siguiente
        viewModelScope.launch {
            var lastYtId: String? = null
            PlayerManager.currentTrack.collect { track ->
                if (track?.origin == TrackOrigin.YOUTUBE && track.youtubeId != null) {
                    if (track.youtubeId != lastYtId) {
                        lastYtId = track.youtubeId
                        startRadio(track.youtubeId)
                    }
                }
            }
        }
    }

    // ── Búsqueda ───────────────────────────────────────────────────────────────

    fun setQuery(q: String) {
        _query.value = q
        // Si se borra el texto, también se borran los resultados para que
        // no queden resultados viejos con el buscador vacío
        if (q.isBlank()) _results.value = emptyList()
    }

    fun search() {
        val q = _query.value.trim()
        if (q.isBlank() || _searching.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _searching.value = true
            try { _results.value = YoutubeRepository.search(q) }
            catch (_: Exception) { /* mantener resultados anteriores si falla la red */ }
            finally { _searching.value = false }
        }
    }

    // ── Reproducción ───────────────────────────────────────────────────────────

    /* Pone a sonar una canción desde la lista de búsqueda.
       Muestra "Preparando…" mientras obtiene la URL del stream y la
       guarda en Recientes solo si llega de la lista de resultados.
       IMPORTANTE: la URL se extrae en IO pero PlayerManager se llama en Main
       porque ExoPlayer solo acepta operaciones desde el hilo principal. */
    fun play(result: YoutubeSearchResult, fromSearch: Boolean = true, onOpenPlayer: () -> Unit) {
        viewModelScope.launch {                            // Main por defecto
            _prepareState.value = PrepareState.Preparing
            try {
                // Extracción de URL → IO (red + parsing)
                val url = withContext(Dispatchers.IO) {
                    YoutubeRepository.getStreamUrl(result.videoId)
                }
                val track = result.toTrack(url)

                // PlayerManager y onOpenPlayer → Main (ExoPlayer lo exige)
                PlayerManager.playQueue(listOf(track), 0)

                if (fromSearch) {
                    withContext(Dispatchers.IO) {
                        YoutubeRepository.addRecent(result)
                    }
                    _recents.value = YoutubeRepository.getRecents()
                }
                _prepareState.value = PrepareState.Idle
                onOpenPlayer()
            } catch (e: Exception) {
                _prepareState.value = PrepareState.Error(
                    message = e.message ?: "Error desconocido",
                    result  = result
                )
            }
        }
    }

    fun retry(result: YoutubeSearchResult, onOpenPlayer: () -> Unit) {
        _prepareState.value = PrepareState.Idle
        play(result, fromSearch = true, onOpenPlayer = onOpenPlayer)
    }

    fun dismissError() { _prepareState.value = PrepareState.Idle }

    /* Borra la búsqueda actual (texto y resultados) y la última búsqueda
       guardada, para que no vuelva al reabrir la app. */
    fun clearSearch() {
        _query.value = ""
        _results.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            YoutubeRepository.clearLastSearch()
        }
    }

    // ── Radio ──────────────────────────────────────────────────────────────────

    /* Cuando empieza a sonar un track de YouTube, busca videos relacionados
       y los va agregando a la cola uno por uno conforme se resuelven sus
       URLs. La precarga del primero hace que abra casi al instante.
       Cada URL se extrae en IO; appendToQueue solo actualiza el StateFlow
       (hilo-seguro) y si la cola ya terminó, retoma en Main. */
    private fun startRadio(videoId: String) {
        if (videoId == lastRadioId) return
        lastRadioId = videoId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val related = YoutubeRepository.getRelated(videoId)
                if (related.isEmpty()) return@launch

                // Precargar la primera en background (el usuario ya está oyendo la actual)
                related.firstOrNull()?.let { YoutubeRepository.prefetch(it.videoId) }

                // Resolver y agregar cada track relacionado a la cola
                related.take(6).forEach { result ->
                    launch(Dispatchers.IO) {
                        try {
                            val url   = YoutubeRepository.getStreamUrl(result.videoId)
                            val track = result.toTrack(url)
                            PlayerManager.appendToQueue(listOf(track))
                        } catch (_: Exception) { /* omitir este track si falla */ }
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
