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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/* DataStore privado de YouTube: persiste última búsqueda y recientes. */
private val Context.youtubeDs by preferencesDataStore(name = "gurru_youtube")

/* Repositorio singleton para YouTube Music.
   Usa la API interna de YouTube (InnerTube) con cliente VISIONOS para
   la reproducción directa sin necesidad de API keys ni librerías pesadas.
   
   - Búsqueda: YouTube Music InnerTube (WEB_REMIX) para canciones con artista real y carátulas HD.
   - Stream de audio: InnerTube Player con cliente VISIONOS (Apple Vision Pro) para URLs directas de audio.
   - Radio infinita: Endpoint Next de YouTube Music con la emisora automática (RDAMVM<videoId>).
   - Persistencia: DataStore Preferences.
*/
object YoutubeRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /* Cache en memoria de URLs de stream: videoId → URL de audio */
    private val streamCache = ConcurrentHashMap<String, String>()

    /* Cache de visitorData de YouTube */
    @Volatile
    private var cachedVisitorData: String? = null

    private lateinit var appCtx: Context

    private val KEY_LAST_QUERY   = stringPreferencesKey("yt_last_query")
    private val KEY_LAST_RESULTS = stringPreferencesKey("yt_last_results")
    private val KEY_RECENTS      = stringPreferencesKey("yt_recents")

    private const val USER_AGENT_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    private const val USER_AGENT_VISIONOS =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    // ── Inicialización ─────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (::appCtx.isInitialized) return
        appCtx = context.applicationContext

        // Precargar visitorData en segundo plano al arrancar la app
        scope.launch {
            try {
                ensureVisitorData()
            } catch (_: Exception) {
                // Si no hay red al inicio, se reintentará en la primera búsqueda
            }
        }
    }

    // ── Búsqueda en YouTube Music ──────────────────────────────────────────────

    /* Busca en YouTube Music usando InnerTube API (WEB_REMIX).
       Filtra por canciones para obtener el artista real, álbum, duración y portada HD. */
    suspend fun search(query: String): List<YoutubeSearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        val results = executeMusicSearch(trimmed, withSongsFilter = true).ifEmpty {
            executeMusicSearch(trimmed, withSongsFilter = false)
        }

        // Persistir última búsqueda en DataStore
        try {
            appCtx.youtubeDs.edit { prefs ->
                prefs[KEY_LAST_QUERY]   = trimmed
                prefs[KEY_LAST_RESULTS] = resultsToJson(results)
            }
        } catch (_: Exception) { }

        results
    }

    private fun executeMusicSearch(query: String, withSongsFilter: Boolean): List<YoutubeSearchResult> {
        return try {
            val endpoint = "https://music.youtube.com/youtubei/v1/search"
            val visitor = cachedVisitorData ?: fetchVisitorData()

            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20241104.01.00")
                        put("hl", "es")
                        put("gl", "US")
                        if (!visitor.isNullOrBlank()) {
                            put("visitorData", visitor)
                        }
                    })
                })
                put("query", query)
                if (withSongsFilter) {
                    // Filtro oficial de YouTube Music para "Canciones"
                    put("params", "Eg-KAQwIARAAGAAgACgAMABqChAEEAMQCRAFEAo%3D")
                }
            }

            val jsonResponse = postJson(
                urlStr = endpoint,
                jsonBody = body.toString(),
                userAgent = USER_AGENT_WEB,
                headers = mapOf(
                    "Origin" to "https://music.youtube.com",
                    "Referer" to "https://music.youtube.com/"
                )
            )

            parseMusicSearchResults(JSONObject(jsonResponse))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseMusicSearchResults(root: JSONObject): List<YoutubeSearchResult> {
        val results = mutableListOf<YoutubeSearchResult>()
        try {
            val tabs = root.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs") ?: return emptyList()

            val tabRenderer = tabs.optJSONObject(0)?.optJSONObject("tabRenderer")
            val sections = tabRenderer?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: return emptyList()

            for (i in 0 until sections.length()) {
                val section = sections.getJSONObject(i)
                val shelf = section.optJSONObject("musicShelfRenderer") ?: continue
                val items = shelf.optJSONArray("contents") ?: continue

                for (j in 0 until items.length()) {
                    val item = items.getJSONObject(j).optJSONObject("musicResponsiveListItemRenderer") ?: continue

                    // 1. VideoId
                    val videoId = extractVideoIdFromMusicItem(item) ?: continue

                    // 2. Título (flexColumns[0])
                    val title = extractTextFromFlexColumn(item, 0) ?: continue

                    // 3. Artista real y Duración (flexColumns[1])
                    val (artist, durationSec) = extractArtistAndDuration(item)

                    // 4. Portada en alta resolución
                    val thumbUrl = extractThumbnailUrl(item)

                    results.add(
                        YoutubeSearchResult(
                            videoId = videoId,
                            title = title,
                            artist = artist,
                            durationSec = durationSec,
                            thumbnailUrl = thumbUrl
                        )
                    )
                }
            }
        } catch (_: Exception) { }
        return results
    }

    // ── URL del Stream de Audio (InnerTube Player con VISIONOS) ────────────────

    /* Extrae la URL de stream de audio directa usando el cliente VISIONOS.
       Este cliente no requiere resolución de JS ni tokens BotGuard complejos. */
    suspend fun getStreamUrl(videoId: String): String = withContext(Dispatchers.IO) {
        streamCache[videoId]?.let { return@withContext it }

        // Intentar primero con VISIONOS (cliente Apple Vision Pro)
        var url = extractStreamWithVisionOS(videoId)

        // Si no devuelve formato directo, intentar con cliente fallback ANDROID_VR
        if (url == null) {
            url = extractStreamWithAndroidVR(videoId)
        }

        if (url != null) {
            streamCache[videoId] = url
            return@withContext url
        }

        throw Exception("No se pudo obtener el audio de esta canción. YouTube restringió la reproducción.")
    }

    private fun extractStreamWithVisionOS(videoId: String): String? {
        return try {
            val visitor = ensureVisitorData()
            val endpoint = "https://www.youtube.com/youtubei/v1/player"

            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "VISIONOS")
                        put("clientVersion", "1.02")
                        put("deviceMake", "Apple")
                        put("deviceModel", "RealityDevice17,1")
                        put("userAgent", USER_AGENT_VISIONOS)
                        put("osName", "visionOS")
                        put("osVersion", "26.5.23O471")
                        put("hl", "es")
                        put("gl", "US")
                        if (visitor.isNotBlank()) {
                            put("visitorData", visitor)
                        }
                    })
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val headers = mapOf(
                "X-YouTube-Client-Name" to "101",
                "X-YouTube-Client-Version" to "1.02",
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/"
            )

            val jsonResponse = postJson(endpoint, body.toString(), USER_AGENT_VISIONOS, headers)
            pickBestAudioUrl(JSONObject(jsonResponse))
        } catch (_: Exception) {
            null
        }
    }

    private fun extractStreamWithAndroidVR(videoId: String): String? {
        return try {
            val visitor = cachedVisitorData ?: ""
            val endpoint = "https://www.youtube.com/youtubei/v1/player"

            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_VR")
                        put("clientVersion", "1.61.48")
                        put("deviceMake", "Oculus")
                        put("deviceModel", "Quest 3")
                        put("osName", "Android")
                        put("osVersion", "12")
                        put("hl", "es")
                        put("gl", "US")
                        if (visitor.isNotBlank()) {
                            put("visitorData", visitor)
                        }
                    })
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val headers = mapOf(
                "X-YouTube-Client-Name" to "28",
                "X-YouTube-Client-Version" to "1.61.48"
            )

            val jsonResponse = postJson(endpoint, body.toString(), USER_AGENT_WEB, headers)
            pickBestAudioUrl(JSONObject(jsonResponse))
        } catch (_: Exception) {
            null
        }
    }

    private fun pickBestAudioUrl(root: JSONObject): String? {
        val streamingData = root.optJSONObject("streamingData") ?: return null

        // 1. Revisar formatos adaptativos (solo audio)
        val adaptive = streamingData.optJSONArray("adaptiveFormats")
        if (adaptive != null) {
            val audioCandidates = mutableListOf<AudioCandidate>()
            for (i in 0 until adaptive.length()) {
                val f = adaptive.getJSONObject(i)
                val mime = f.optString("mimeType")
                if (!mime.startsWith("audio/")) continue

                val directUrl = f.optString("url")
                if (directUrl.isNullOrBlank()) continue

                val bitrate = f.optInt("bitrate", 0)
                val itag = f.optInt("itag", 0)
                audioCandidates.add(AudioCandidate(directUrl, bitrate, itag, mime))
            }

            // Preferir itag 140 (AAC 128kbps) o el de mayor bitrate
            val best = audioCandidates.firstOrNull { it.itag == 140 }
                ?: audioCandidates.maxByOrNull { it.bitrate }

            if (best != null) return best.url
        }

        // 2. Si no hay adaptativos, buscar en formatos combinados (progressive)
        val progressive = streamingData.optJSONArray("formats")
        if (progressive != null) {
            for (i in 0 until progressive.length()) {
                val f = progressive.getJSONObject(i)
                val directUrl = f.optString("url")
                if (!directUrl.isNullOrBlank()) return directUrl
            }
        }

        // 3. Revisar HLS manifest si está presente (ExoPlayer lo reproduce con media3-exoplayer-hls)
        val hlsUrl = streamingData.optString("hlsManifestUrl")
        if (!hlsUrl.isNullOrBlank()) return hlsUrl

        return null
    }

    private data class AudioCandidate(val url: String, val bitrate: Int, val itag: Int, val mime: String)

    /* Precarga la URL del stream de la siguiente canción en segundo plano */
    fun prefetch(videoId: String) {
        if (streamCache.containsKey(videoId)) return
        scope.launch {
            try { getStreamUrl(videoId) } catch (_: Exception) { }
        }
    }

    // ── Radio (Sugerencias Automáticas / RDAMVM) ───────────────────────────────

    /* Obtiene canciones sugeridas (radio) para el video actual usando la emisora
       automática de YouTube Music (playlist RDAMVM<videoId>). Alterna canciones
       del mismo artista y de artistas similares. */
    suspend fun getRelated(videoId: String): List<YoutubeSearchResult> = withContext(Dispatchers.IO) {
        try {
            val endpoint = "https://music.youtube.com/youtubei/v1/next"
            val visitor = cachedVisitorData ?: fetchVisitorData()

            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20241104.01.00")
                        put("hl", "es")
                        put("gl", "US")
                        if (!visitor.isNullOrBlank()) {
                            put("visitorData", visitor)
                        }
                    })
                })
                put("playlistId", "RDAMVM$videoId")
                put("isAudioOnly", true)
            }

            val jsonResponse = postJson(
                urlStr = endpoint,
                jsonBody = body.toString(),
                userAgent = USER_AGENT_WEB,
                headers = mapOf(
                    "Origin" to "https://music.youtube.com",
                    "Referer" to "https://music.youtube.com/"
                )
            )

            parseRadioResults(JSONObject(jsonResponse), excludeVideoId = videoId)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseRadioResults(root: JSONObject, excludeVideoId: String): List<YoutubeSearchResult> {
        val results = mutableListOf<YoutubeSearchResult>()
        try {
            val tabs = root.optJSONObject("contents")
                ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
                ?.optJSONObject("tabbedRenderer")
                ?.optJSONObject("watchNextTabbedResultsRenderer")
                ?.optJSONArray("tabs") ?: return emptyList()

            val tab = tabs.optJSONObject(0)?.optJSONObject("tabRenderer")
            val queue = tab?.optJSONObject("content")
                ?.optJSONObject("musicQueueRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("playlistPanelRenderer")
                ?.optJSONArray("contents") ?: return emptyList()

            for (i in 0 until queue.length()) {
                val item = queue.getJSONObject(i).optJSONObject("playlistPanelVideoRenderer") ?: continue

                val id = item.optString("videoId")
                if (id.isBlank() || id == excludeVideoId) continue

                val title = item.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                val artist = item.optJSONObject("longBylineText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                val durStr = item.optJSONObject("lengthText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                val durationSec = parseDurationToSeconds(durStr)

                // Thumbnail
                val thumbs = item.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                val thumbUrl = if (thumbs != null && thumbs.length() > 0) {
                    val rawUrl = thumbs.getJSONObject(thumbs.length() - 1).optString("url")
                    optimizeThumbnailUrl(rawUrl)
                } else null

                results.add(
                    YoutubeSearchResult(
                        videoId = id,
                        title = title,
                        artist = artist,
                        durationSec = durationSec,
                        thumbnailUrl = thumbUrl
                    )
                )
            }
        } catch (_: Exception) { }
        return results
    }

    // ── Recientes ──────────────────────────────────────────────────────────────

    /* Guarda en Recientes solo las canciones reproducidas desde la lista de búsqueda */
    suspend fun addRecent(result: YoutubeSearchResult) {
        val current = getRecents().toMutableList()
        current.removeAll { it.videoId == result.videoId }
        current.add(0, result)
        try {
            appCtx.youtubeDs.edit { prefs ->
                prefs[KEY_RECENTS] = resultsToJson(current.take(30))
            }
        } catch (_: Exception) { }
    }

    suspend fun getRecents(): List<YoutubeSearchResult> {
        val json = try {
            appCtx.youtubeDs.data.first()[KEY_RECENTS]
        } catch (_: Exception) { null } ?: return emptyList()
        return resultsFromJson(json)
    }

    // ── Persistencia de última búsqueda ────────────────────────────────────────

    suspend fun getLastSearch(): Pair<String, List<YoutubeSearchResult>> {
        val prefs = try {
            appCtx.youtubeDs.data.first()
        } catch (_: Exception) { null } ?: return "" to emptyList()

        val query = prefs[KEY_LAST_QUERY] ?: ""
        val resultsJson = prefs[KEY_LAST_RESULTS] ?: "[]"
        return query to resultsFromJson(resultsJson)
    }

    // ── Visitor Data (YouTube Token) ───────────────────────────────────────────

    private fun ensureVisitorData(): String {
        cachedVisitorData?.let { if (it.isNotBlank()) return it }
        val fresh = fetchVisitorData()
        if (fresh.isNotBlank()) {
            cachedVisitorData = fresh
            return fresh
        }
        return ""
    }

    private fun fetchVisitorData(): String {
        return try {
            val url = URL("https://www.youtube.com/sw.js_data")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", USER_AGENT_WEB)

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val rawJson = if (text.startsWith(")]}'")) text.substring(5) else text
            val arr = JSONArray(rawJson)
            val visitor = arr.getJSONArray(0).getJSONArray(2).getJSONArray(0).getJSONArray(0).getString(13)
            cachedVisitorData = visitor
            visitor
        } catch (_: Exception) {
            ""
        }
    }

    // ── Helpers de Extracción y Parsing ────────────────────────────────────────

    private fun extractVideoIdFromMusicItem(item: JSONObject): String? {
        item.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }?.let { return it }

        item.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")?.takeIf { it.isNotBlank() }?.let { return it }

        val cols = item.optJSONArray("flexColumns") ?: return null
        for (i in 0 until cols.length()) {
            val textObj = cols.getJSONObject(i).optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
            val runs = textObj?.optJSONArray("runs") ?: continue
            for (r in 0 until runs.length()) {
                val vid = runs.getJSONObject(r).optJSONObject("navigationEndpoint")
                    ?.optJSONObject("watchEndpoint")?.optString("videoId")
                if (!vid.isNullOrBlank()) return vid
            }
        }
        return null
    }

    private fun extractTextFromFlexColumn(item: JSONObject, colIndex: Int): String? {
        val cols = item.optJSONArray("flexColumns") ?: return null
        if (colIndex >= cols.length()) return null
        val runs = cols.getJSONObject(colIndex)
            .optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs") ?: return null
        if (runs.length() == 0) return null
        return runs.getJSONObject(0).optString("text").takeIf { it.isNotBlank() }
    }

    private fun extractArtistAndDuration(item: JSONObject): Pair<String, Double> {
        val cols = item.optJSONArray("flexColumns") ?: return "Desconocido" to 0.0
        if (cols.length() < 2) return "Desconocido" to 0.0

        val runs = cols.getJSONObject(1)
            .optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs") ?: return "Desconocido" to 0.0

        var artist = ""
        var durationSec = 0.0

        for (i in 0 until runs.length()) {
            val run = runs.getJSONObject(i)
            val text = run.optString("text").trim()
            if (text == "•" || text.isEmpty()) continue

            // Si es un formato de tiempo (ej. 3:45 o 1:02:15)
            if (text.contains(":") && text.all { it.isDigit() || it == ':' }) {
                durationSec = parseDurationToSeconds(text)
            } else if (artist.isEmpty()) {
                // El primer texto que no es separador es el artista
                artist = text
            }
        }

        return (if (artist.isBlank()) "Desconocido" else artist) to durationSec
    }

    private fun extractThumbnailUrl(item: JSONObject): String? {
        val thumbs = item.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails") ?: return null
        if (thumbs.length() == 0) return null
        val raw = thumbs.getJSONObject(thumbs.length() - 1).optString("url")
        return optimizeThumbnailUrl(raw)
    }

    private fun optimizeThumbnailUrl(url: String): String {
        if (url.isBlank()) return url
        // Mejorar resolución de la imagen de YouTube Music
        return if (url.contains("=w") && url.contains("-h")) {
            url.replaceFirst(Regex("=w\\d+-h\\d+.*"), "=w544-h544-l90-rj")
        } else {
            url
        }
    }

    private fun parseDurationToSeconds(timeStr: String): Double {
        if (timeStr.isBlank()) return 0.0
        val parts = timeStr.trim().split(":")
        var seconds = 0.0
        try {
            when (parts.size) {
                1 -> seconds = parts[0].toDouble()
                2 -> seconds = (parts[0].toInt() * 60 + parts[1].toInt()).toDouble()
                3 -> seconds = (parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()).toDouble()
            }
        } catch (_: Exception) { }
        return seconds
    }

    // ── Cliente HTTP Nativo (POST JSON) ───────────────────────────────────────

    private fun postJson(
        urlStr: String,
        jsonBody: String,
        userAgent: String,
        headers: Map<String, String> = emptyMap()
    ): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.doOutput = true
            conn.doInput = true

            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("User-Agent", userAgent)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ── Serialización JSON para DataStore ──────────────────────────────────────

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
