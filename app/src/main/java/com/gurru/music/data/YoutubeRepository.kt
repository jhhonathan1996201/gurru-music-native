package com.gurru.music.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList.YouTube
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/* DataStore privado de YouTube: persiste última búsqueda y recientes. */
private val Context.youtubeDs by preferencesDataStore(name = "gurru_youtube")

/* Repositorio singleton para todo lo relacionado con YouTube.
   Usa NewPipe Extractor para buscar y obtener URLs de audio sin API key.
   Las URLs de stream duran ~6 h; se guardan en caché de memoria y se
   re-extraen si caducan. */
object YoutubeRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /* Cache de URLs de stream: videoId → URL del stream de audio. */
    private val streamCache = ConcurrentHashMap<String, String>()

    private lateinit var appCtx: Context

    private val KEY_LAST_QUERY   = stringPreferencesKey("yt_last_query")
    private val KEY_LAST_RESULTS = stringPreferencesKey("yt_last_results")
    private val KEY_RECENTS      = stringPreferencesKey("yt_recents")

    // ── Inicialización ─────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (::appCtx.isInitialized) return
        appCtx = context.applicationContext
        NewPipe.init(GurruDownloader())
    }

    // ── Búsqueda ───────────────────────────────────────────────────────────────

    /* Busca en YouTube y devuelve resultados de video (sin lives).
       Persiste la búsqueda en DataStore para restaurarla al volver. */
    suspend fun search(query: String): List<YoutubeSearchResult> {
        val handler = YouTube.searchQHFactory.fromQuery(
            query,
            listOf("videos"),
            ""
        )
        val info = SearchInfo.getInfo(YouTube, handler)
        val results = info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filter { it.duration >= 0 }          // descartar lives (dur = -1)
            .map { it.toSearchResult() }

        // Persistir para restaurar al volver a la pestaña
        appCtx.youtubeDs.edit { prefs ->
            prefs[KEY_LAST_QUERY]   = query
            prefs[KEY_LAST_RESULTS] = resultsToJson(results)
        }
        return results
    }

    // ── URL del stream ─────────────────────────────────────────────────────────

    /* Devuelve la URL del stream de audio de mejor calidad disponible.
       Primero revisa la caché de memoria; si no está, la extrae de YouTube. */
    suspend fun getStreamUrl(videoId: String): String {
        streamCache[videoId]?.let { return it }

        val info = StreamInfo.getInfo(
            YouTube,
            "https://www.youtube.com/watch?v=$videoId"
        )
        val best: AudioStream = info.audioStreams
            .filter { it.content.isNotBlank() }
            .maxByOrNull { it.averageBitrate }
            ?: throw Exception("No se encontró audio para este video")

        return best.content.also { streamCache[videoId] = it }
    }

    /* Lanza en background la extracción de la URL de un video para que
       esté lista antes de que el usuario la necesite. */
    fun prefetch(videoId: String) {
        if (streamCache.containsKey(videoId)) return
        scope.launch {
            try { getStreamUrl(videoId) } catch (_: Exception) { /* se reintenta al reproducir */ }
        }
    }

    // ── Radio ──────────────────────────────────────────────────────────────────

    /* Devuelve videos relacionados del video dado, para armar la cola de radio.
       Usa los relatedItems que YouTube devuelve con el stream info. */
    suspend fun getRelated(videoId: String): List<YoutubeSearchResult> = try {
        val info = StreamInfo.getInfo(
            YouTube,
            "https://www.youtube.com/watch?v=$videoId"
        )
        info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filter { it.duration >= 0 }
            .map { it.toSearchResult() }
    } catch (_: Exception) { emptyList() }

    // ── Recientes ──────────────────────────────────────────────────────────────

    /* Agrega una canción al historial de recientes (solo desde búsqueda,
       no desde radio). Mantiene las últimas 30. */
    suspend fun addRecent(result: YoutubeSearchResult) {
        val current = getRecents().toMutableList()
        current.removeAll { it.videoId == result.videoId }
        current.add(0, result)
        appCtx.youtubeDs.edit { prefs ->
            prefs[KEY_RECENTS] = resultsToJson(current.take(30))
        }
    }

    suspend fun getRecents(): List<YoutubeSearchResult> {
        val json = appCtx.youtubeDs.data.first()[KEY_RECENTS] ?: return emptyList()
        return resultsFromJson(json)
    }

    // ── Persistencia de última búsqueda ────────────────────────────────────────

    suspend fun getLastSearch(): Pair<String, List<YoutubeSearchResult>> {
        val prefs = appCtx.youtubeDs.data.first()
        return (prefs[KEY_LAST_QUERY] ?: "") to
               resultsFromJson(prefs[KEY_LAST_RESULTS] ?: "[]")
    }

    // ── Artista real ───────────────────────────────────────────────────────────

    /* Extrae el artista real del video, en orden de confianza:
       1. Tag "artist:" de los metadatos del video.
       2. Canal limpio: quita " - Topic" (canales automáticos de YouTube Music)
          y "VEVO".
       3. Heurística en el título: "Artista - Canción".
       Devuelve el canal limpio como último recurso. */
    fun parseArtist(title: String, uploaderName: String, tags: List<String>): String {
        // 1. Tags de metadatos
        tags.firstOrNull { it.startsWith("artist:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // 2. Canal limpio
        val cleaned = uploaderName
            .removeSuffix(" - Topic")
            .removeSuffix("VEVO")
            .trim()

        // 3. Título "Artista - Canción"
        val dash = title.indexOf(" - ")
        if (dash in 1 until (title.length / 2)) {
            val candidate = title.substring(0, dash).trim()
            if (candidate.length < 50 && !candidate.contains("official", ignoreCase = true)) {
                return candidate
            }
        }

        return cleaned.ifBlank { uploaderName }
    }

    // ── Helpers privados ───────────────────────────────────────────────────────

    private fun StreamInfoItem.toSearchResult(): YoutubeSearchResult {
        val videoId = extractVideoId(url)
        val thumbUrl = thumbnails.maxByOrNull { it.height }?.url
        val uploader = uploaderName ?: ""
        return YoutubeSearchResult(
            videoId     = videoId,
            title       = name,
            artist      = parseArtist(name, uploader, emptyList()),
            durationSec = duration.toDouble().coerceAtLeast(0.0),
            thumbnailUrl = thumbUrl
        )
    }

    private fun extractVideoId(url: String): String {
        Regex("[?&]v=([^&]+)").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("/shorts/([^/?&]+)").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("youtu\\.be/([^/?&]+)").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        return url.substringAfterLast("/").substringBefore("?")
    }

    // ── Serialización JSON (sin dependencia extra) ─────────────────────────────

    private fun resultsToJson(results: List<YoutubeSearchResult>): String {
        val array = JSONArray()
        results.forEach { r ->
            array.put(JSONObject().apply {
                put("videoId",     r.videoId)
                put("title",       r.title)
                put("artist",      r.artist)
                put("durationSec", r.durationSec)
                put("thumbUrl",    r.thumbnailUrl ?: "")
            })
        }
        return array.toString()
    }

    private fun resultsFromJson(json: String): List<YoutubeSearchResult> = try {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            YoutubeSearchResult(
                videoId      = o.getString("videoId"),
                title        = o.getString("title"),
                artist       = o.getString("artist"),
                durationSec  = o.getDouble("durationSec"),
                thumbnailUrl = o.getString("thumbUrl").takeIf { it.isNotEmpty() }
            )
        }
    } catch (_: Exception) { emptyList() }
}

// ── Downloader de NewPipe ──────────────────────────────────────────────────────

/* Implementación del cliente HTTP que necesita NewPipe Extractor para hacer
   sus peticiones. Usa HttpURLConnection (ya viene en Android, sin dependencia
   extra). El User-Agent imita Firefox para que YouTube devuelva los datos. */
class GurruDownloader : Downloader() {

    companion object {
        private const val UA =
            "Mozilla/5.0 (Android 14; Mobile; rv:109.0) Gecko/118.0 Firefox/118.0"
    }

    override fun execute(request: Request): Response {
        val conn = URL(request.url()).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout    = 15_000
            conn.setRequestProperty("User-Agent", UA)

            // Cabeceras extra que NewPipe quiera enviar
            request.headers().forEach { (key, values) ->
                values.forEach { v -> conn.setRequestProperty(key, v) }
            }

            // POST (p.ej. para algunas peticiones internas de YouTube)
            val body = request.dataToSend()
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }

            val code = conn.responseCode
            val msg  = try { conn.responseMessage } catch (_: Exception) { null }

            val responseBody = try {
                conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } catch (_: Exception) {
                try { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() }
                catch (_: Exception) { null } ?: ""
            }

            val headers = linkedMapOf<String, List<String>>()
            conn.headerFields?.forEach { (k, v) -> if (k != null) headers[k] = v }

            Response(code, msg, headers, responseBody, request.url())
        } finally {
            conn.disconnect()
        }
    }
}
