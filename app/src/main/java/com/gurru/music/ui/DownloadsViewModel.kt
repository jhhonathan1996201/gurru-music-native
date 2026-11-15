package com.gurru.music.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.gurru.music.data.DownloadRepository
import com.gurru.music.data.DownloadTask
import com.gurru.music.data.Track
import com.gurru.music.data.TrackOrigin
import com.gurru.music.data.YoutubeSearchResult
import com.gurru.music.player.PlayerManager
import kotlinx.coroutines.flow.StateFlow

/* ViewModel de la pestaña de Descargas.
   Maneja el estado de las descargas en curso y completadas,
   y permite reproducir las canciones ya descargadas. */
class DownloadsViewModel(application: Application) : AndroidViewModel(application) {

    val downloads: StateFlow<List<DownloadTask>> = DownloadRepository.downloads

    init {
        DownloadRepository.init(application)
    }

    fun startDownload(result: YoutubeSearchResult) {
        DownloadRepository.startDownload(result)
    }

    fun cancelDownload(videoId: String) {
        DownloadRepository.cancelDownload(videoId)
    }

    fun deleteDownload(videoId: String) {
        DownloadRepository.deleteDownload(videoId)
    }

    fun retryDownload(task: DownloadTask) {
        DownloadRepository.retryDownload(task)
    }

    /* Pone a sonar una canción descargada directamente en el reproductor nativo */
    fun playDownloaded(task: DownloadTask, onOpenPlayer: () -> Unit) {
        val uri = task.localUri ?: task.localPath ?: return
        val track = Track(
            id          = "download-${task.videoId}",
            title       = task.title,
            artist      = task.artist,
            album       = "Gurru Music",
            durationSec = task.durationSec,
            artUri      = task.thumbnailUrl,
            audioUri    = uri,
            folder      = "Gurru Music",
            origin      = TrackOrigin.LOCAL
        )
        PlayerManager.playQueue(listOf(track), 0)
        onOpenPlayer()
    }
}
