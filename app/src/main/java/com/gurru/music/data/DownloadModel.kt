package com.gurru.music.data

/* Estados del ciclo de vida de una descarga */
enum class DownloadStatus {
    QUEUED,      // En cola
    PREPARING,   // Obteniendo enlace directo de stream con InnerTube
    DOWNLOADING, // Descargando bytes con porcentaje en tiempo real
    COMPLETED,   // Completada y guardada en el almacenamiento del teléfono
    FAILED,      // Error en la descarga
    CANCELLED    // Cancelada por el usuario
}

/* Representa una tarea de descarga en la pestaña de Descargas */
data class DownloadTask(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSec: Double,
    val thumbnailUrl: String?,
    val progress: Int = 0,             // 0..100 %
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val localUri: String? = null,      // content:// o file://
    val localPath: String? = null,
    val error: String? = null
)
