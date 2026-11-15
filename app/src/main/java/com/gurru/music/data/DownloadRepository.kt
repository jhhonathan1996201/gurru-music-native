package com.gurru.music.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private val Context.downloadsDs by preferencesDataStore(name = "gurru_downloads")

/* Repositorio singleton que gestiona la cola y la descarga en segundo plano
   de canciones de YouTube hacia el almacenamiento local (MediaStore / Music).
   Mantiene el porcentaje en tiempo real y persiste las descargas completadas. */
object DownloadRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appCtx: Context

    private val _downloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloads: StateFlow<List<DownloadTask>> = _downloads.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val KEY_DOWNLOADS_HISTORY = stringPreferencesKey("completed_downloads_history")

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    fun init(context: Context) {
        if (::appCtx.isInitialized) return
        appCtx = context.applicationContext

        // Cargar descargas previas guardadas en DataStore
        scope.launch {
            val saved = loadSavedDownloads()
            _downloads.value = saved
        }
    }

    // ── Iniciar Descarga ───────────────────────────────────────────────────────

    fun startDownload(result: YoutubeSearchResult) {
        val currentList = _downloads.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.videoId == result.videoId }

        // Si ya está descargando o en cola, no duplicar
        if (existingIndex >= 0) {
            val existing = currentList[existingIndex]
            if (existing.status == DownloadStatus.DOWNLOADING || existing.status == DownloadStatus.PREPARING) {
                return
            }
        }

        val initialTask = DownloadTask(
            videoId         = result.videoId,
            title           = result.title,
            artist          = result.artist,
            durationSec     = result.durationSec,
            thumbnailUrl    = result.thumbnailUrl,
            progress        = 0,
            status          = DownloadStatus.PREPARING
        )

        if (existingIndex >= 0) {
            currentList[existingIndex] = initialTask
        } else {
            currentList.add(0, initialTask)
        }
        _downloads.value = currentList

        // Lanzar trabajo de descarga en segundo plano
        val job = scope.launch {
            executeDownload(initialTask)
        }
        activeJobs[result.videoId] = job
    }

    // ── Cancelar Descarga ──────────────────────────────────────────────────────

    fun cancelDownload(videoId: String) {
        activeJobs[videoId]?.cancel()
        activeJobs.remove(videoId)

        val updated = _downloads.value.map { task ->
            if (task.videoId == videoId && (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PREPARING)) {
                task.copy(status = DownloadStatus.CANCELLED)
            } else task
        }
        _downloads.value = updated
    }

    // ── Eliminar Descarga ──────────────────────────────────────────────────────

    fun deleteDownload(videoId: String) {
        cancelDownload(videoId)

        val task = _downloads.value.firstOrNull { it.videoId == videoId }
        task?.localUri?.let { uriStr ->
            try {
                appCtx.contentResolver.delete(Uri.parse(uriStr), null, null)
            } catch (_: Exception) { }
        }
        task?.localPath?.let { path ->
            try {
                File(path).delete()
            } catch (_: Exception) { }
        }

        val updated = _downloads.value.filterNot { it.videoId == videoId }
        _downloads.value = updated
        scope.launch { saveDownloads(updated) }
    }

    // ── Reintentar Descarga ───────────────────────────────────────────────────

    fun retryDownload(task: DownloadTask) {
        startDownload(
            YoutubeSearchResult(
                videoId      = task.videoId,
                title        = task.title,
                artist       = task.artist,
                durationSec  = task.durationSec,
                thumbnailUrl = task.thumbnailUrl
            )
        )
    }

    // ── Proceso de Descarga ────────────────────────────────────────────────────

    private suspend fun executeDownload(task: DownloadTask) = withContext(Dispatchers.IO) {
        var outputStream: OutputStream? = null
        var targetUri: Uri? = null
        var targetFile: File? = null

        try {
            // 1. Obtener URL de stream de audio directa
            updateTask(task.videoId) { it.copy(status = DownloadStatus.PREPARING) }
            val streamUrl = YoutubeRepository.getStreamUrl(task.videoId)

            // ratebypass=yes le pide al servidor de YouTube que NO limite
            // la velocidad de descarga (sin esto, el servidor la reduce y
            // la descarga tarda mucho más que la reproducción, que solo
            // necesita velocidad de tiempo real)
            val downloadUrl = if (streamUrl.contains("ratebypass=")) streamUrl
            else streamUrl + if (streamUrl.contains("?")) "&ratebypass=yes" else "?ratebypass=yes"

            // 2. Conectar al stream
            updateTask(task.videoId) { it.copy(status = DownloadStatus.DOWNLOADING) }
            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout    = 20_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Connection", "keep-alive")
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.instanceFollowRedirects = true

            val code = conn.responseCode
            if (code !in 200..299) {
                throw Exception("Error de conexión al descargar: HTTP $code")
            }

            val totalBytes = conn.contentLengthLong.coerceAtLeast(1L)
            val inputStream: InputStream = conn.inputStream

            // 3. Crear destino en MediaStore / Carpeta Music
            val safeArtist = sanitizeFileName(task.artist.ifBlank { "Desconocido" })
            val safeTitle = sanitizeFileName(task.title.ifBlank { "Audio" })
            val fileName = "$safeArtist - $safeTitle.m4a"

            val created = createAudioOutputFile(fileName, task.title, task.artist)
            outputStream = created.first
            targetUri    = created.second
            targetFile   = created.third

            if (outputStream == null) {
                throw Exception("No se pudo crear el archivo en el almacenamiento del teléfono")
            }

            // 4. Copiar bytes con buffer ampliado de 64KB y reporte eficiente
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            var downloadedBytes = 0L
            var lastReportedProgress = -1
            var lastReportTime = 0L
            val downloadStartMs = System.currentTimeMillis()

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                val currentProgress = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                val now = System.currentTimeMillis()

                if ((currentProgress >= lastReportedProgress + 2 && now - lastReportTime > 250) || currentProgress == 100) {
                    lastReportedProgress = currentProgress
                    lastReportTime = now
                    // Velocidad media desde que empezó la descarga (para ver
                    // en pantalla si YouTube la está limitando)
                    val elapsedSec = ((now - downloadStartMs) / 1000.0).coerceAtLeast(0.5)
                    val speed = (downloadedBytes / elapsedSec).toLong()
                    updateTask(task.videoId) {
                        it.copy(
                            progress = currentProgress,
                            bytesDownloaded = downloadedBytes,
                            totalBytes = totalBytes,
                            speedBytesPerSec = speed
                        )
                    }
                }
            }

            outputStream.flush()

            // 5. Finalizar archivo en MediaStore
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && targetUri != null) {
                val completeValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                appCtx.contentResolver.update(targetUri, completeValues, null, null)
            }

            // Notificar al escáner del sistema para que aparezca en la Biblioteca
            targetFile?.let { f ->
                MediaScannerConnection.scanFile(appCtx, arrayOf(f.absolutePath), arrayOf("audio/mp4"), null)
            }

            // 6. Marcar como completada y persistir
            val finalTask = task.copy(
                progress     = 100,
                status       = DownloadStatus.COMPLETED,
                localUri     = targetUri?.toString(),
                localPath    = targetFile?.absolutePath
            )

            updateTask(task.videoId) { finalTask }
            saveDownloads(_downloads.value)

        } catch (e: CancellationException) {
            // Cancelada por el usuario: limpiar archivo parcial
            cleanupPartial(targetUri, targetFile)
            updateTask(task.videoId) { it.copy(status = DownloadStatus.CANCELLED) }
        } catch (e: Exception) {
            cleanupPartial(targetUri, targetFile)
            updateTask(task.videoId) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    error  = e.localizedMessage ?: "Fallo al descargar canción"
                )
            }
        } finally {
            try { outputStream?.close() } catch (_: Exception) { }
            activeJobs.remove(task.videoId)
        }
    }

    private fun createAudioOutputFile(
        fileName: String,
        title: String,
        artist: String
    ): Triple<OutputStream?, Uri?, File?> {
        val resolver = appCtx.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM, "Gurru Music")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Gurru Music")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                val out = resolver.openOutputStream(uri)
                return Triple(out, uri, null)
            }
        }

        // Fallback para Android < 10 o si falla MediaStore
        try {
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val appMusicDir = File(musicDir, "Gurru Music").apply { mkdirs() }
            val file = File(appMusicDir, fileName)
            val out = FileOutputStream(file)
            val fileUri = Uri.fromFile(file)
            return Triple(out, fileUri, file)
        } catch (_: Exception) {
            // Último recurso: almacenamiento de archivos de la app
            val appDir = appCtx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: appCtx.filesDir
            val file = File(appDir, fileName)
            val out = FileOutputStream(file)
            return Triple(out, Uri.fromFile(file), file)
        }
    }

    private fun cleanupPartial(uri: Uri?, file: File?) {
        try {
            if (uri != null) appCtx.contentResolver.delete(uri, null, null)
            file?.delete()
        } catch (_: Exception) { }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    private fun updateTask(videoId: String, block: (DownloadTask) -> DownloadTask) {
        val list = _downloads.value.map {
            if (it.videoId == videoId) block(it) else it
        }
        _downloads.value = list
    }

    // ── Persistencia DataStore ─────────────────────────────────────────────────

    private suspend fun saveDownloads(tasks: List<DownloadTask>) {
        try {
            val completedOnly = tasks.filter { it.status == DownloadStatus.COMPLETED }
            val array = JSONArray()
            for (t in completedOnly) {
                array.put(JSONObject().apply {
                    put("videoId",      t.videoId)
                    put("title",        t.title)
                    put("artist",       t.artist)
                    put("durationSec",  t.durationSec)
                    put("thumbnailUrl", t.thumbnailUrl ?: "")
                    put("localUri",     t.localUri ?: "")
                    put("localPath",    t.localPath ?: "")
                })
            }
            appCtx.downloadsDs.edit { prefs ->
                prefs[KEY_DOWNLOADS_HISTORY] = array.toString()
            }
        } catch (_: Exception) { }
    }

    private suspend fun loadSavedDownloads(): List<DownloadTask> {
        return try {
            val json = appCtx.downloadsDs.data.first()[KEY_DOWNLOADS_HISTORY] ?: return emptyList()
            val array = JSONArray(json)
            val list = mutableListOf<DownloadTask>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    DownloadTask(
                        videoId         = obj.getString("videoId"),
                        title           = obj.getString("title"),
                        artist          = obj.getString("artist"),
                        durationSec     = obj.getDouble("durationSec"),
                        thumbnailUrl    = obj.getString("thumbnailUrl").takeIf { it.isNotEmpty() },
                        progress        = 100,
                        status          = DownloadStatus.COMPLETED,
                        localUri        = obj.getString("localUri").takeIf { it.isNotEmpty() },
                        localPath       = obj.getString("localPath").takeIf { it.isNotEmpty() }
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
