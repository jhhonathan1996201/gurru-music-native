package com.gurru.music.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/* Repositorio singleton para obtener las letras de las canciones desde
   múltiples fuentes en orden de prioridad:
   
   1. LRCLIB (Directo / API Get): Letras sincronizadas línea por línea con marcas de tiempo (LRC).
   2. LRCLIB (Búsqueda abierta): Fallback si el nombre exacto varía.
   3. YouTube Music InnerTube: Letras oficiales de YouTube Music (Musixmatch / LyricFind).
   
   Muestra la fuente exacta de donde provino la letra y permite tocar
   cualquier línea para saltar a ese segundo exacto de la canción. */
object LyricsRepository {

    private val lyricsCache = ConcurrentHashMap<String, LyricsData>()
    private const val USER_AGENT = "GurruMusic/1.0 (Android)"

    /* El caché en disco vive en el almacenamiento interno de la app
       (filesDir/lyrics/). Se inicializa al arrancar la app. */
    fun init(context: Context) {
        LyricsCache.init(context)
    }

    /* Guarda el resultado en memoria Y en disco, para que la letra se
       vea sin internet la próxima vez. */
    private fun storeResult(cacheKey: String, track: Track, data: LyricsData) {
        lyricsCache[cacheKey] = data
        LyricsCache.save(track.artist, track.title, data)
    }

    suspend fun getLyrics(track: Track): LyricsData? = withContext(Dispatchers.IO) {
        val cacheKey = "${track.artist.lowercase()}-${track.title.lowercase()}".trim()
        lyricsCache[cacheKey]?.let { return@withContext it }

        // Caché en disco (sin internet): si ya se guardó antes, usarla.
        LyricsCache.get(track.artist, track.title)?.let { disk ->
            lyricsCache[cacheKey] = disk
            return@withContext disk
        }

        val cleanTitle = sanitizeTitle(track.title)
        val cleanArtist = sanitizeArtist(track.artist)

        // ── 1. Fuente 1: LRCLIB (Obtención directa sincronizada) ───────────────
        val lrclibDirect = fetchLrclibDirect(cleanTitle, cleanArtist, track.durationSec)
        if (lrclibDirect != null && lrclibDirect.isSynced && lrclibDirect.lines.isNotEmpty()) {
            storeResult(cacheKey, track, lrclibDirect)
            return@withContext lrclibDirect
        }

        // ── 2. Fuente 2: LRCLIB (Búsqueda abierta) ─────────────────────────────
        val lrclibSearch = fetchLrclibSearch("$cleanArtist $cleanTitle")
        if (lrclibSearch != null && lrclibSearch.isSynced && lrclibSearch.lines.isNotEmpty()) {
            storeResult(cacheKey, track, lrclibSearch)
            return@withContext lrclibSearch
        }

        // ── 3. Fuente 3: YouTube Music InnerTube ───────────────────────────────
        val ytMusicLyrics = fetchYouTubeMusicLyrics(track.youtubeId, cleanTitle, cleanArtist)
        if (ytMusicLyrics != null && ytMusicLyrics.lines.isNotEmpty()) {
            storeResult(cacheKey, track, ytMusicLyrics)
            return@withContext ytMusicLyrics
        }

        // ── 4. Fallback: Si LRCLIB encontró letra en texto plano (sin sync) ────
        val fallbackPlain = lrclibDirect ?: lrclibSearch
        if (fallbackPlain != null && fallbackPlain.lines.isNotEmpty()) {
            storeResult(cacheKey, track, fallbackPlain)
            return@withContext fallbackPlain
        }

        null
    }

    // ── LRCLIB API ─────────────────────────────────────────────────────────────

    private fun fetchLrclibDirect(title: String, artist: String, durationSec: Double): LyricsData? {
        return try {
            val encTitle = URLEncoder.encode(title, "UTF-8")
            val encArtist = URLEncoder.encode(artist, "UTF-8")
            val dur = durationSec.toInt()
            val urlStr = "https://lrclib.net/api/get?track_name=$encTitle&artist_name=$encArtist" +
                    if (dur > 0) "&duration=$dur" else ""

            val jsonStr = getHttp(urlStr) ?: return null
            val obj = JSONObject(jsonStr)

            val synced = obj.optString("syncedLyrics")
            val plain = obj.optString("plainLyrics")

            if (synced.isNotBlank()) {
                parseLrc(synced, source = "LRCLIB (Sincronizada)", title, artist)
            } else if (plain.isNotBlank()) {
                parsePlain(plain, source = "LRCLIB", title, artist)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchLrclibSearch(query: String): LyricsData? {
        return try {
            val encQuery = URLEncoder.encode(query, "UTF-8")
            val urlStr = "https://lrclib.net/api/search?q=$encQuery"

            val jsonStr = getHttp(urlStr) ?: return null
            val array = JSONArray(jsonStr)

            var bestSynced: LyricsData? = null
            var bestPlain: LyricsData? = null

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val synced = item.optString("syncedLyrics")
                val plain = item.optString("plainLyrics")
                val trackName = item.optString("trackName")
                val artistName = item.optString("artistName")

                if (synced.isNotBlank() && bestSynced == null) {
                    bestSynced = parseLrc(synced, source = "LRCLIB (Sincronizada)", trackName, artistName)
                    break
                } else if (plain.isNotBlank() && bestPlain == null) {
                    bestPlain = parsePlain(plain, source = "LRCLIB", trackName, artistName)
                }
            }

            bestSynced ?: bestPlain
        } catch (_: Exception) {
            null
        }
    }

    // ── YouTube Music InnerTube Lyrics ─────────────────────────────────────────

    private suspend fun fetchYouTubeMusicLyrics(youtubeId: String?, title: String, artist: String): LyricsData? {
        return try {
            var vid = youtubeId
            if (vid.isNullOrBlank()) {
                // Si la canción es local, buscarla primero en YouTube Music para obtener su videoId
                vid = findYouTubeIdForTrack(title, artist)
            }
            if (vid.isNullOrBlank()) return null

            // 1. Obtener el endpoint de la pestaña de Letra vía /next
            val nextBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20241104.01.00")
                        put("hl", "es")
                        put("gl", "US")
                    })
                })
                put("videoId", vid)
            }

            val nextRespStr = postJson("https://music.youtube.com/youtubei/v1/next", nextBody.toString())
            val nextJson = JSONObject(nextRespStr)

            val tabs = nextJson.optJSONObject("contents")
                ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
                ?.optJSONObject("tabbedRenderer")
                ?.optJSONObject("watchNextTabbedResultsRenderer")
                ?.optJSONArray("tabs") ?: return null

            var lyricsBrowseId: String? = null
            for (i in 0 until tabs.length()) {
                val tab = tabs.getJSONObject(i).optJSONObject("tabRenderer") ?: continue
                val tabTitle = tab.optString("title")
                val browseId = tab.optJSONObject("endpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")

                if ((tabTitle.contains("Letra", ignoreCase = true) || tabTitle.contains("Lyrics", ignoreCase = true)) && !browseId.isNullOrBlank()) {
                    lyricsBrowseId = browseId
                    break
                }
            }

            if (lyricsBrowseId.isNullOrBlank()) return null

            // 2. Consultar el contenido de la letra vía /browse
            val browseBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20241104.01.00")
                        put("hl", "es")
                        put("gl", "US")
                    })
                })
                put("browseId", lyricsBrowseId)
            }

            val browseRespStr = postJson("https://music.youtube.com/youtubei/v1/browse", browseBody.toString())
            val browseJson = JSONObject(browseRespStr)

            val shelf = browseJson.optJSONObject("contents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
                ?.optJSONObject(0)
                ?.optJSONObject("musicDescriptionShelfRenderer") ?: return null

            val textRuns = shelf.optJSONObject("description")?.optJSONArray("runs") ?: return null
            val lyricsText = textRuns.getJSONObject(0).optString("text")

            val footerRuns = shelf.optJSONObject("footer")?.optJSONArray("runs")
            val footer = if (footerRuns != null && footerRuns.length() > 0) {
                footerRuns.getJSONObject(0).optString("text")
            } else "Musixmatch"

            if (lyricsText.isNotBlank()) {
                parsePlain(lyricsText, source = "YouTube Music ($footer)", title, artist)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun findYouTubeIdForTrack(title: String, artist: String): String? {
        return try {
            val results = YoutubeRepository.search("$artist $title")
            results.firstOrNull()?.videoId
        } catch (_: Exception) {
            null
        }
    }

    // ── Parsers ────────────────────────────────────────────────────────────────

    private fun parseLrc(lrcText: String, source: String, title: String, artist: String): LyricsData {
        val regex = Regex("\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})\\](.*)")
        val lines = mutableListOf<LyricLine>()

        lrcText.lines().forEach { rawLine ->
            val trimmed = rawLine.trim()
            val match = regex.find(trimmed)
            if (match != null) {
                val (minStr, secStr, msStr, text) = match.destructured
                val min = minStr.toLongOrNull() ?: 0L
                val sec = secStr.toLongOrNull() ?: 0L
                val ms = if (msStr.length == 2) (msStr.toLongOrNull() ?: 0L) * 10 else (msStr.toLongOrNull() ?: 0L)
                val timeMs = min * 60_000L + sec * 1_000L + ms

                lines.add(LyricLine(timeMs = timeMs, text = text.trim()))
            }
        }

        return if (lines.isNotEmpty()) {
            LyricsData(
                title    = title,
                artist   = artist,
                lines    = lines.sortedBy { it.timeMs },
                isSynced = true,
                source   = source
            )
        } else {
            parsePlain(lrcText, source, title, artist)
        }
    }

    private fun parsePlain(plainText: String, source: String, title: String, artist: String): LyricsData {
        val lines = plainText.lines()
            .map { it.replace(Regex("\\[\\d{2}:\\d{2}[.:]\\d{2,3}\\]"), "").trim() }
            .filter { it.isNotBlank() }
            .map { LyricLine(timeMs = -1L, text = it) }

        return LyricsData(
            title    = title,
            artist   = artist,
            lines    = lines,
            isSynced = false,
            source   = source
        )
    }

    // ── Limpieza de nombres para maximizar coincidencias ───────────────────────

    private fun sanitizeTitle(title: String): String {
        return title
            .replace(Regex("(?i)\\s*\\(?(official\\s*(music)?\\s*video|video\\s*oficial|audio\\s*oficial|lyric\\s*video|letra|remaster(ed)?\\s*\\d*|audio)\\)?"), "")
            .replace(Regex("(?i)\\s*\\[.*?\\]"), "")
            .replace(Regex("(?i)\\s*\\(feat\\..*?\\)"), "")
            .replace(Regex("(?i)\\s*\\(ft\\..*?\\)"), "")
            .replace(Regex("(?i)\\s*ft\\..*"), "")
            .replace(Regex("(?i)\\s*feat\\..*"), "")
            .trim()
    }

    private fun sanitizeArtist(artist: String): String {
        return artist
            .replace(Regex("(?i)\\s*-\\s*topic"), "")
            .replace(Regex("(?i)\\s*vevo"), "")
            .trim()
    }

    // ── Cliente HTTP Interno ───────────────────────────────────────────────────

    private fun getHttp(urlStr: String): String? {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout    = 8_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun postJson(urlStr: String, jsonBody: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout    = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            conn.setRequestProperty("Origin", "https://music.youtube.com")
            conn.setRequestProperty("Referer", "https://music.youtube.com/")

            OutputStreamWriter(conn.outputStream, "UTF-8").use {
                it.write(jsonBody)
                it.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
