package com.gurru.music.player

import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/* Servicio en primer plano (Media3) que publica la sesión de Gurru
   Music: notificación con controles, pantalla de bloqueo y Bluetooth,
   y la música sigue sonando con la pantalla apagada o fuera de la app.
   No reproduce por sí mismo: el audio lo lleva el ExoPlayer compartido
   de PlayerManager. Las acciones de la notificación (anterior,
   play/pausa, siguiente, detener) llegan aquí y se delegan a
   PlayerManager, para que el comportamiento sea el mismo que en la
   interfaz (aleatorio, repetir, reiniciar si lleva más de 3 s). */
class MusicService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        PlayerManager.init(applicationContext)
        val callback = object : MediaSession.Callback {
            override fun onSkipToNext(session: MediaSession, controller: MediaSession.ControllerInfo) {
                PlayerManager.next()
            }

            override fun onSkipToPrevious(session: MediaSession, controller: MediaSession.ControllerInfo) {
                PlayerManager.prev()
            }

            override fun onSeekTo(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                positionMs: Long
            ) {
                PlayerManager.seekTo(positionMs)
            }

            override fun onStop(session: MediaSession, controller: MediaSession.ControllerInfo) {
                PlayerManager.stop()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                if (customCommand.customAction == ACTION_STOP) {
                    PlayerManager.stop()
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }
        session = MediaSession.Builder(this, PlayerManager.player)
            .setCallback(callback)
            .build()
        /* Botón "Detener" en la notificación (la MediaSession de
           Media3 ya trae anterior / play-pausa / siguiente). */
        session?.setCustomLayout(
            listOf(
                CommandButton.Builder()
                    .setDisplayName("Detener")
                    .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
                    .setSessionCommand(SessionCommand(ACTION_STOP, Bundle.EMPTY))
                    .build()
            )
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.gurru.music.STOP"
    }
}
