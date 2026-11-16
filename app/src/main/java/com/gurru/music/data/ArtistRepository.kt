package com.gurru.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/* Normaliza un nombre (artista o título) para comparar: minúsculas,
   sin acentos, sin signos y con espacios simples. Así "Belanova" ==
   "Belanova" y "Los Ángeles Azules" == "Los Angeles Azules". */
fun normalizeForMatch(text: String): String {
    val noAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
    return noAccents.lowercase()
        .replace("[^a-z0-9 ]".toRegex(), " ")
        .split(" ").filter { it.isNotBlank() }.joinToString(" ")
}

/* Álbum de un artista (de iTunes). */
data class ArtistAlbum(
    val collectionId: Long,
    val name: String,
    val artistName: String,
    val artworkUrl: String?,
    val year: Int,
    val trackCount: Int
)

/* Fase 4: página del artista.
   - Top 10: canciones del artista desde YouTube Music (InnerTube, igual
     que la búsqueda), verificando que cada resultado sea del artista
     pedido por el nombre del artista, no por el canal que subió el video.
   - Álbumes: de la API de iTunes (sin llave).
   - Canciones de un álbum: de iTunes lookup, resueltas en YouTube Music
     buscando "<artista> <canción>" (como en la app de Capacitor). */
object ArtistRepository {

    /* Top 10 oficial del artista: obtiene las canciones más escuchadas de YouTube Music
       (emisora algorítmica y estantería de canciones más escuchadas) y complementa con iTunes. */
    suspend fun getTopSongs(artistName: String): List<YoutubeSearchResult> = withContext(Dispatchers.IO) {
        val name = artistName.trim()
        if (name.isEmpty()) return@withContext emptyList()
        YoutubeRepository.getArtistTopSongs(name)
    }

    /* Datos oficiales del artista (foto HD, oyentes mensuales/suscriptores, browseId). */
    suspend fun getArtistCard(artistName: String): ArtistCardData? = withContext(Dispatchers.IO) {
        val name = artistName.trim()
        if (name.isEmpty()) return@withContext null
        YoutubeRepository.getArtistInfo(name)
    }

    /* Obtiene el top de canciones de iTunes (ordenadas por popularidad) y resuelve
       cada una en YouTube Music para tener su videoId y stream directo. */
    suspend fun getTopSongsFromItunes(artistName: String): List<YoutubeSearchResult> = withContext(Dispatchers.IO) {
        val name = artistName.trim()
        if (name.isEmpty()) return@withContext emptyList()
        val titles = try {
            val term = URLEncoder.encode(name, "UTF-8")
            val urlStr = "https://itunes.apple.com/search?term=$term&entity=song&limit=15&country=US&lang=es"
            val json = JSONObject(getHttp(urlStr))
            val arr = json.optJSONArray("results") ?: return@withContext emptyList()
            val normName = normalizeForMatch(name)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val artist = o.optString("artistName", "")
                val a = normalizeForMatch(artist)
                if (a.isBlank()) continue
                if (a != normName && !a.contains(normName) && !normName.contains(a)) continue
                val t = o.optString("trackName", "").trim()
                if (t.isNotBlank() && !list.contains(t)) list.add(t)
            }
            list.take(10)
        } catch (_: Exception) {
            emptyList()
        }

        if (titles.isEmpty()) return@withContext emptyList()
        coroutineScope {
            val jobs = titles.map { title ->
                async { resolveAlbumTrack(title, name) }
            }
            jobs.awaitAll().filterNotNull()
        }
    }

    /* Álbumes del artista desde iTunes Search API (sin llave).
       Filtra por el nombre del artista porque iTunes también devuelve
       coincidencias por el nombre de la canción o del álbum. */
    suspend fun getAlbums(artistName: String): List<ArtistAlbum> = withContext(Dispatchers.IO) {
        val name = artistName.trim()
        if (name.isEmpty()) return@withContext emptyList()
        try {
            val term = URLEncoder.encode(name, "UTF-8")
            val urlStr = "https://itunes.apple.com/search?term=$term&entity=album&limit=20&country=US&lang=es"
            val json = JSONObject(getHttp(urlStr))
            val arr = json.optJSONArray("results") ?: return@withContext emptyList()
            val normName = normalizeForMatch(name)
            val albums = mutableListOf<ArtistAlbum>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val artist = o.optString("artistName", "")
                val a = normalizeForMatch(artist)
                if (a.isBlank()) continue
                if (a != normName && !a.contains(normName) && !normName.contains(a)) continue
                val collectionId = o.optLong("collectionId", 0L)
                if (collectionId == 0L) continue
                val collName = o.optString("collectionName", "").trim()
                if (collName.isBlank()) continue
                val art100 = o.optString("artworkUrl100", "")
                val artwork = if (art100.isBlank()) null
                    else art100.replace("100x100bb", "600x600bb")
                val release = o.optString("releaseDate", "")
                val year = release.take(4).toIntOrNull() ?: 0
                val trackCount = o.optInt("trackCount", 0)
                albums.add(ArtistAlbum(collectionId, collName, artist, artwork, year, trackCount))
            }
            // Quitar duplicados por nombre (ediciones del mismo álbum)
            albums.distinctBy { normalizeForMatch(it.name) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /* Canciones de un álbum: primero las pide a iTunes lookup y luego
       resuelve cada una en YouTube Music buscando "<artista> <canción>".
       Las búsquedas se hacen en paralelo y se devuelve en el orden del álbum. */
    suspend fun getAlbumTracks(album: ArtistAlbum, artistName: String): List<YoutubeSearchResult> = withContext(Dispatchers.IO) {
        val titles = try {
            val urlStr = "https://itunes.apple.com/lookup?id=${album.collectionId}&entity=song"
            val json = JSONObject(getHttp(urlStr))
            val arr = json.optJSONArray("results") ?: return@withContext emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("wrapperType") != "track") continue
                if (o.optString("kind") != "song") continue
                val t = o.optString("trackName", "").trim()
                if (t.isNotBlank()) list.add(t)
            }
            list
        } catch (_: Exception) {
            return@withContext emptyList()
        }
        if (titles.isEmpty()) return@withContext emptyList()
        coroutineScope {
            val jobs = titles.map { title ->
                async { resolveAlbumTrack(title, artistName) }
            }
            jobs.awaitAll().filterNotNull()
        }
    }

    /* Resuelve una canción de un álbum en YouTube Music. Prefiere el
       resultado cuyo título coincide y es del artista pedido; si no hay
       coincidencia de título, uno del artista; si no, el primero. */
    private suspend fun resolveAlbumTrack(title: String, artistName: String): YoutubeSearchResult? {
        return try {
            val results = YoutubeRepository.searchSongs("$artistName $title")
            if (results.isEmpty()) return null
            val normTitle = normalizeForMatch(title)
            val normArtist = normalizeForMatch(artistName)
            results.firstOrNull { r ->
                val t = normalizeForMatch(r.title)
                val a = normalizeForMatch(r.artist)
                (t == normTitle || t.contains(normTitle) || normTitle.contains(t)) &&
                    (a == normArtist || a.contains(normArtist))
            } ?: results.firstOrNull { r ->
                val a = normalizeForMatch(r.artist)
                a == normArtist || a.contains(normArtist)
            } ?: results.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /* GET simple a una URL (para iTunes). Devuelve el cuerpo como texto. */
    private fun getHttp(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "GurruMusic/1.0 (Android)")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
