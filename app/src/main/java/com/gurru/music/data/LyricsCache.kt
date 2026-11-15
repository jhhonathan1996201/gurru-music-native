package com.gurru.music.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/* Caché de letras en el almacenamiento interno de la app
   (filesDir/lyrics/), para que las letras se vean sin internet.
   Cada letra se guarda como un JSON indexado por artista + título.
   Se llena sola: al ver una letra, al terminar una descarga y al
   bajar las letras de la Biblioteca desde Ajustes. */
object LyricsCache {

    private var lyricsDir: File? = null

    fun init(context: Context) {
        if (lyricsDir != null) return
        lyricsDir = File(context.applicationContext.filesDir, "lyrics").apply { mkdirs() }
    }

    /* Clave estable para una canción: MD5 de "artista|título" en
       minúsculas. Así el nombre del archivo nunca tiene caracteres
       raros y dos canciones distintas no chocan. */
    fun keyFor(artist: String, title: String): String {
        val raw = "${artist.lowercase().trim()}|${title.lowercase().trim()}"
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun get(artist: String, title: String): LyricsData? {
        val dir = lyricsDir ?: return null
        return try {
            val file = File(dir, "${keyFor(artist, title)}.json")
            if (!file.exists()) return null
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val lines = mutableListOf<LyricLine>()
            val arr = obj.optJSONArray("lines") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                lines.add(LyricLine(timeMs = o.optLong("t", -1L), text = o.optString("x", "")))
            }
            if (lines.isEmpty()) return null
            LyricsData(
                title = obj.optString("title", title),
                artist = obj.optString("artist", artist),
                lines = lines,
                isSynced = obj.optBoolean("isSynced", false),
                source = obj.optString("source", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    /* Guarda la letra indexada por el artista y título de la pista
       (los mismos que usa get), para que siempre se encuentre. */
    fun save(artist: String, title: String, lyrics: LyricsData) {
        val dir = lyricsDir ?: return
        try {
            val obj = JSONObject().apply {
                put("title", lyrics.title)
                put("artist", lyrics.artist)
                put("source", lyrics.source)
                put("isSynced", lyrics.isSynced)
                val arr = JSONArray()
                for (line in lyrics.lines) {
                    arr.put(JSONObject().apply {
                        put("t", line.timeMs)
                        put("x", line.text)
                    })
                }
                put("lines", arr)
            }
            val file = File(dir, "${keyFor(artist, title)}.json")
            file.writeText(obj.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    fun has(artist: String, title: String): Boolean {
        val dir = lyricsDir ?: return false
        return File(dir, "${keyFor(artist, title)}.json").exists()
    }

    fun count(): Int {
        val dir = lyricsDir ?: return 0
        return try {
            dir.listFiles()?.count { it.name.endsWith(".json") } ?: 0
        } catch (_: Exception) {
            0
        }
    }
}
