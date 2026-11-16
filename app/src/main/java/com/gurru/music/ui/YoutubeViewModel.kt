package com.gurru.music.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gurru.music.data.ArtistCardData
import com.gurru.music.data.TrackOrigin
import com.gurru.music.data.YoutubeRepository
import com.gurru.music.data.normalizeForMatch
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

    /* Tarjeta del artista oficial para la búsqueda */
    private val _artistCard = MutableStateFlow<ArtistCardData?>(null)
    val artistCard: StateFlow<ArtistCardData?> = _artistCard.asStateFlow()

    /* Compatibilidad para nombre del artista sugerido */
    private val _suggestedArtist = MutableStateFlow<String?>(null)
    val suggestedArtist: StateFlow<String?> = _suggestedArtist.asStateFlow()

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
            val card = if (q.isNotBlank()) withContext(Dispatchers.IO) {
                YoutubeRepository.getArtistInfo(q)
            } ?: computeSuggestedArtistCard(r, q) else null
            _artistCard.value = card
            _suggestedArtist.value = card?.name
        }

        // Observar cambios de canción: lanzar radio solo si es una sola canción o cerca del final de la cola
        viewModelScope.launch {
            var lastYtId: String? = null
            PlayerManager.currentTrack.collect { track ->
                if (track?.origin == TrackOrigin.YOUTUBE && track.youtubeId != null) {
                    if (track.youtubeId != lastYtId) {
                        lastYtId = track.youtubeId
                        val qSize = PlayerManager.queue.value.size
                        val curIdx = PlayerManager.currentIndex.value
                        if (qSize <= 1 || curIdx >= qSize - 2) {
                            startRadio(track.youtubeId)
                        }
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
        if (q.isBlank()) {
            _results.value = emptyList()
            _artistCard.value = null
            _suggestedArtist.value = null
        }
    }

    fun search() {
        val q = _query.value.trim()
        if (q.isBlank() || _searching.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _searching.value = true
            try {
                val results = YoutubeRepository.search(q)
                val card = YoutubeRepository.getArtistInfo(q) ?: computeSuggestedArtistCard(results, q)
                _results.value = results
                _artistCard.value = card
                _suggestedArtist.value = card?.name
            }
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
        _artistCard.value = null
        _suggestedArtist.value = null
        viewModelScope.launch(Dispatchers.IO) {
            YoutubeRepository.clearLastSearch()
        }
    }

    private fun computeSuggestedArtistCard(results: List<YoutubeSearchResult>, query: String): ArtistCardData? {
        val name = computeSuggestedArtist(results, query) ?: return null
        val thumb = results.firstOrNull { it.artist == name }?.thumbnailUrl
        return ArtistCardData(
            name = name,
            imageUrl = thumb,
            subtitle = "Artista · Ver canciones y álbumes"
        )
    }

    /* Artista sugerido para la tarjeta "Ver artista": el artista de los
       resultados de búsqueda. Se resuelve por el campo artista (no por
       el nombre de una canción): si la búsqueda coincide con un artista,
       es ese; si no, el artista más frecuente entre los resultados. */
    private fun computeSuggestedArtist(results: List<YoutubeSearchResult>, query: String): String? {
        if (results.isEmpty()) return null
        val q = normalizeForMatch(query)
        val byArtist = results.groupBy { it.artist.trim() }.filterKeys { it.isNotBlank() }
        if (byArtist.isEmpty()) return null
        // 1. Un artista cuyo nombre coincide con la búsqueda
        val exact = byArtist.keys.firstOrNull { a ->
            val an = normalizeForMatch(a)
            an.isNotBlank() && (an == q || q.contains(an) || an.contains(q))
        }
        if (exact != null) return exact
        // 2. El artista más frecuente, solo si se repite o es el único
        val (top, list) = byArtist.maxByOrNull { it.value.size } ?: return null
        return if (list.size >= 2 || byArtist.size == 1) top else null
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
