package com.gurru.music.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* Escaneo de la música del teléfono (MediaStore). Traducción casi línea
   por línea de LocalMusicPlugin.java de la app Capacitor: misma
   proyección, mismo filtro (IS_MUSIC != 0 o duración >= 60 s), mismo
   descarte de audios de WhatsApp (nombres aud/ptt o ruta con whatsapp)
   y misma carpeta padre. La única diferencia: devuelve List<Track> por
   corrutina en vez de un objeto JSON, y la URI se usa directa con
   ExoPlayer (ya no hace falta convertFileSrc). */
class MediaStoreScanner(private val context: Context) {

    /** Permiso de lectura de audio según la versión de Android. */
    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission()) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun scan(): List<Track> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA
        )
        /* Música marcada como tal, o audios largos aunque no estén
           marcados (algunos MP3 no traen IS_MUSIC). Los tonos y sonidos
           cortos quedan fuera por duración. */
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 OR " +
            MediaStore.Audio.Media.DURATION + " >= 60000"
        val songs = ArrayList<Track>()
        val cursor = context.contentResolver.query(
            collection, projection, selection, null,
            MediaStore.Audio.Media.TITLE + " ASC"
        )
        cursor?.use {
            val iId = it.getColumnIndex(MediaStore.Audio.Media._ID)
            val iTitle = it.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val iArtist = it.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val iAlbum = it.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = it.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val iDur = it.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val iName = it.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val iPath = it.getColumnIndex(MediaStore.Audio.Media.DATA)
            while (it.moveToNext()) {
                val id = if (iId >= 0) it.getLong(iId) else -1L
                if (id < 0) continue
                var title = if (iTitle >= 0) it.getString(iTitle) else null
                val name = if (iName >= 0) it.getString(iName) else null
                /* Fuera los audios de WhatsApp: sus archivos empiezan
                   con "aud" (AUD-...opus) o "ptt" y viven en la carpeta
                   de WhatsApp. No son música aunque duren más de un
                   minuto. */
                val path = if (iPath >= 0) it.getString(iPath) else null
                val nameLow = name?.lowercase() ?: ""
                val pathLow = path?.lowercase() ?: ""
                if (nameLow.startsWith("aud") || nameLow.startsWith("ptt") ||
                    pathLow.contains("whatsapp")
                ) continue
                if (title.isNullOrBlank()) {
                    title = name?.replace(Regex("\\.[^.]+$"), "") ?: "Canción"
                }
                var artist = if (iArtist >= 0) it.getString(iArtist) else null
                if (artist.isNullOrBlank() || artist == "<unknown>") {
                    artist = "Artista desconocido"
                }
                /* Carpeta: el nombre de la carpeta que contiene el
                   archivo (ej. "Espinoza Paz", "Music", "Download").
                   Sin ruta, "Música". */
                var folder = "Música"
                if (path != null) {
                    val lastSlash = path.lastIndexOf('/')
                    if (lastSlash > 0) {
                        val parent = path.substring(0, lastSlash)
                        val prevSlash = parent.lastIndexOf('/')
                        folder = if (prevSlash >= 0) parent.substring(prevSlash + 1) else parent
                        if (folder.isEmpty()) folder = "Música"
                    }
                }
                val album = if (iAlbum >= 0) it.getString(iAlbum) ?: "" else ""
                val albumId = if (iAlbumId >= 0) it.getLong(iAlbumId) else -1L
                val durMs = if (iDur >= 0) it.getLong(iDur) else 0L
                val artUri = if (albumId > 0) {
                    ContentUris.withAppendedId(
                        android.net.Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    ).toString()
                } else null
                songs.add(
                    Track(
                        id = "local-$id",
                        title = title,
                        artist = artist,
                        album = album,
                        durationSec = durMs / 1000.0,
                        artUri = artUri,
                        audioUri = ContentUris.withAppendedId(collection, id).toString(),
                        folder = folder
                    )
                )
            }
        }
        songs
    }
}
