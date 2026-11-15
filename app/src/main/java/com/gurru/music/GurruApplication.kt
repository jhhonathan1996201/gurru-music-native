package com.gurru.music

import android.app.Application
import android.content.ContentValues
import android.provider.MediaStore
import com.gurru.music.data.DownloadRepository
import com.gurru.music.data.YoutubeRepository
import com.gurru.music.player.PlayerManager
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/* La app arranca el reproductor compartido al inicio, para que exista
   antes que cualquier pantalla o que el servicio en segundo plano.
   Además, si la app se cierra sola, guarda el motivo en
   Documents/Gurru Music/gurru-crash-log.txt, para saber por qué y
   arreglarlo de verdad en vez de adivinar. */
class GurruApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashLog()
        PlayerManager.init(this)
        YoutubeRepository.init(this)   // Fase 3: YouTube online
        DownloadRepository.init(this)  // Descargas online
    }

    private fun installCrashLog() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = StringWriter().apply {
                    throwable.printStackTrace(PrintWriter(this))
                }.toString()
                val text = "Gurru Music - fallo ${Date()}\nHilo: ${thread.name}\n$trace\n"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "gurru-crash-log.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/Gurru Music")
                }
                val uri = contentResolver.insert(
                    MediaStore.Files.getContentUri("external"), values
                )
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out ->
                        out.write(text.toByteArray())
                    }
                }
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
