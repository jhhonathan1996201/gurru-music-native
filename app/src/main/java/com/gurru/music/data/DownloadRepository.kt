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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicLong

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

    // El User-Agent debe coincidir con el cliente que obtuvo la URL del
    // stream (VISIONOS / Safari), no con Chrome de escritorio: si YouTube
    // ve un UA distinto al del cliente que pidió el audio, puede limitar
    // la velocidad de descarga (los ~33 KB/s que se ven en pantalla).
    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

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


    // ── Descarga en paralelo por rangos ──────────────────────────────────
    // YouTube limita cada conexión a ~33 KB/s. Descargar el archivo en 4
    // partes a la vez (cada una por su propia conexión) suma las
    // velocidades (~130 KB/s). Además cada parte se reanuda si se corta
    // la conexión (por ejemplo, al salir de la app y volver), porque el
    // archivo de la parte queda guardado y solo falta lo que no se bajó.

    private const val DOWNLOAD_PARTS = 4
    private const val PART_MAX_RETRIES = 3

    private class RangeNotSupportedException : Exception("El servidor no soporta descarga por rangos")

    // Tamaño total del archivo (bytes). Solo se leen los encabezados de
    // una petición GET. Devuelve -1 si no se pudo saber.
    private fun getContentLength(downloadUrl: String): Long {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(downloadUrl)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", "https://www.youtube.com/")
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return -1L
            val len = conn.contentLengthLong
            if (len > 0) len else -1L
        } catch (_: Exception) {
            -1L
        } finally {
            try { conn?.inputStream?.close() } catch (_: Exception) { }
            conn?.disconnect()
        }
    }

    // Descarga un rango [start, end] a partFile. Si partFile ya tiene
    // bytes (de un intento anterior), reanuda desde donde quedó.
    // Reintenta hasta PART_MAX_RETRIES veces si la conexión se corta.
    private suspend fun downloadRangeWithRetry(
        downloadUrl: String,
        start: Long,
        end: Long,
        partFile: File,
        downloadedBytes: AtomicLong,
        onProgress: () -> Unit
    ) {
        val expected = end - start + 1
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < PART_MAX_RETRIES) {
            try {
                val already = if (partFile.exists()) partFile.length() else 0L
                if (already >= expected) return
                val rangeStart = start + already
                val url = URL(downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Referer", "https://www.youtube.com/")
                conn.setRequestProperty("Accept", "*/*")
                conn.setRequestProperty("Connection", "keep-alive")
                conn.setRequestProperty("Accept-Encoding", "identity")
                conn.setRequestProperty("Range", "bytes=$rangeStart-$end")
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code == 200) {
                    // El servidor ignoró el Range y manda el archivo
                    // completo: no sirve para descargar por partes.
                    conn.disconnect()
                    throw RangeNotSupportedException()
                }
                if (code != 206) {
                    conn.disconnect()
                    throw Exception("Error de conexión al descargar: HTTP $code")
                }
                val input = conn.inputStream
                val out = FileOutputStream(partFile, true) // append = reanudar
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                try {
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        downloadedBytes.addAndGet(bytesRead.toLong())
                        onProgress()
                    }
                } finally {
                    try { out.close() } catch (_: Exception) { }
                    try { input.close() } catch (_: Exception) { }
                    conn.disconnect()
                }
                if (partFile.length() == expected) return
                lastError = Exception("Parte incompleta al descargar")
                attempt++
            } catch (e: RangeNotSupportedException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                attempt++
                if (attempt < PART_MAX_RETRIES) delay(1000L * attempt)
            }
        }
        throw lastError ?: Exception("Fallo al descargar parte")
    }

    // Descarga el audio en DOWNLOAD_PARTS partes en paralelo y lo guarda
    // en la Biblioteca. Devuelve true si se completó (ya quedó
    // finalizado), false si el servidor no soporta rangos (para que el
    // llamador use la descarga simple). Lanza excepción si falló de verdad.
    private suspend fun tryDownloadInParallel(
        task: DownloadTask,
        downloadUrl: String,
        totalBytes: Long
    ): Boolean {
        var outputStream: OutputStream? = null
        var targetUri: Uri? = null
        var targetFile: File? = null
        val tempDir = File(appCtx.cacheDir, "gurru_dl_${task.videoId}").apply { mkdirs() }
        try {
            val partSize = totalBytes / DOWNLOAD_PARTS
            val downloadedBytes = AtomicLong(0L)
            for (p in 0 until DOWNLOAD_PARTS) {
                val f = File(tempDir, "part$p")
                if (f.exists()) downloadedBytes.addAndGet(f.length())
            }
            val downloadStartMs = System.currentTimeMillis()
            val lastReportedProgress = AtomicLong(-1)
            val lastReportTime = AtomicLong(0L)

            fun reportProgress() {
                val done = downloadedBytes.get()
                val currentProgress = ((done * 100) / totalBytes).toInt().coerceIn(0, 100)
                val now = System.currentTimeMillis()
                val prevProgress = lastReportedProgress.get()
                val prevTime = lastReportTime.get()
                if (currentProgress == 100 || (currentProgress >= prevProgress + 2 && now - prevTime > 250)) {
                    if (lastReportedProgress.compareAndSet(prevProgress, currentProgress.toLong())) {
                        lastReportTime.set(now)
                        val elapsedSec = ((now - downloadStartMs) / 1000.0).coerceAtLeast(0.5)
                        val speed = (done / elapsedSec).toLong()
                        updateTask(task.videoId) {
                            it.copy(
                                progress = currentProgress,
                                bytesDownloaded = done,
                                totalBytes = totalBytes,
                                speedBytesPerSec = speed
                            )
                        }
                    }
                }
            }

            coroutineScope {
                val jobs = (0 until DOWNLOAD_PARTS).map { part ->
                    val start = part * partSize
                    val end = if (part == DOWNLOAD_PARTS - 1) totalBytes - 1 else (part + 1) * partSize - 1
                    async {
                        downloadRangeWithRetry(downloadUrl, start, end, File(tempDir, "part$part"), downloadedBytes, ::reportProgress)
                    }
                }
                jobs.awaitAll()
            }

            for (part in 0 until DOWNLOAD_PARTS) {
                val start = part * partSize
                val end = if (part == DOWNLOAD_PARTS - 1) totalBytes - 1 else (part + 1) * partSize - 1
                val expected = end - start + 1
                val f = File(tempDir, "part$part")
                if (!f.exists() || f.length() != expected) {
                    throw Exception("Parte $part incompleta al descargar")
                }
            }

            val safeArtist = sanitizeFileName(task.artist.ifBlank { "Desconocido" })
            val safeTitle = sanitizeFileName(task.title.ifBlank { "Audio" })
            val fileName = "$safeArtist - $safeTitle.m4a"
            val created = createAudioOutputFile(fileName, task.title, task.artist)
            outputStream = created.first
            targetUri = created.second
            targetFile = created.third
            if (outputStream == null) {
                throw Exception("No se pudo crear el archivo en el almacenamiento del teléfono")
            }
            for (part in 0 until DOWNLOAD_PARTS) {
                File(tempDir, "part$part").inputStream().use { input ->
                    input.copyTo(outputStream)
                }
            }
            outputStream.flush()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && targetUri != null) {
                val completeValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                appCtx.contentResolver.update(targetUri, completeValues, null, null)
            }
            targetFile?.let { f ->
                MediaScannerConnection.scanFile(appCtx, arrayOf(f.absolutePath), arrayOf("audio/mp4"), null)
            }
            val finalTask = task.copy(
                progress = 100,
                status = DownloadStatus.COMPLETED,
                localUri = targetUri?.toString(),
                localPath = targetFile?.absolutePath
            )
            updateTask(task.videoId) { finalTask }
            saveDownloads(_downloads.value)
            tempDir.deleteRecursively()
            return true
        } catch (e: RangeNotSupportedException) {
            try { tempDir.deleteRecursively() } catch (_: Exception) { }
            try { outputStream?.close() } catch (_: Exception) { }
            cleanupPartial(targetUri, targetFile)
            return false
        } catch (e: CancellationException) {
            try { outputStream?.close() } catch (_: Exception) { }
            cleanupPartial(targetUri, targetFile)
            throw e
        } catch (e: Exception) {
            try { outputStream?.close() } catch (_: Exception) { }
            cleanupPartial(targetUri, targetFile)
            throw e
        }
    }

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

            // NUEVO: intentar primero la descarga en paralelo por rangos
            // (4 conexiones a la vez, ~130 KB/s en vez de ~33 KB/s, y cada
            // parte se reanuda si se corta al salir de la app). Si el
            // servidor no soporta rangos, devuelve false y se sigue con
            // la descarga simple de abajo.
            val totalBytesForParallel = getContentLength(downloadUrl)
            if (totalBytesForParallel > 512 * 1024) {
                if (tryDownloadInParallel(task, downloadUrl, totalBytesForParallel)) {
                    return@withContext
                }
            }

            // 2. Conectar al stream
            updateTask(task.videoId) { it.copy(status = DownloadStatus.DOWNLOADING) }
            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout    = 20_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", "https://www.youtube.com/")
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
