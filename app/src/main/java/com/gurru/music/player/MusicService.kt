package com.gurru.music.player

import android.os.Bundle
import androidx.media3.common.Player
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
            /* En Media3 1.4.1 el Callback NO tiene onSkipToNext /
               onSkipToPrevious / onSeekTo / onStop: todos los comandos
               del reproductor llegan por onPlayerCommandRequest. Si se
               devuelve algo distinto de RESULT_SUCCESS, la sesión NO
               ejecuta el comando en el reproductor (verificado en el
               código fuente de media3-session 1.4.1: MediaSessionStub,
               "Don't run rejected command"). Por eso los comandos de
               siguiente/anterior/play-pausa/detener se delegan a
               PlayerManager (aleatorio, repetir, reinicio de 3 s) y se
               devuelve RESULT_INFO_SKIPPED; el resto (buscar/seek, que
               la sesión ejecuta sola en el reproductor compartido con
               la posición que trae el comando) devuelve SUCCESS. */
            @Suppress("DEPRECATION")
            override fun onPlayerCommandRequest(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                @Player.Command playerCommand: Int
            ): Int {
                when (playerCommand) {
                    Player.COMMAND_PLAY_PAUSE -> {
                        PlayerManager.togglePlay()
                        return SessionResult.RESULT_INFO_SKIPPED
                    }
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                        PlayerManager.next()
                        return SessionResult.RESULT_INFO_SKIPPED
                    }
                    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                        PlayerManager.prev()
                        return SessionResult.RESULT_INFO_SKIPPED
                    }
                    Player.COMMAND_STOP -> {
                        PlayerManager.stop()
                        return SessionResult.RESULT_INFO_SKIPPED
                    }
                    else -> return SessionResult.RESULT_SUCCESS
                }
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
        val builtSession = MediaSession.Builder(this, PlayerManager.player)
            .setCallback(callback)
            .build()
        session = builtSession
        /* Botón "Detener" en la notificación (la MediaSession de
           Media3 ya trae anterior / play-pausa / siguiente). */
        builtSession.setCustomLayout(
            listOf(
                CommandButton.Builder()
                    .setDisplayName("Detener")
                    .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
                    .setSessionCommand(SessionCommand(ACTION_STOP, Bundle.EMPTY))
                    .build()
            )
        )
        /* Registrar la sesión en el servicio nada más crearla: sin
           esto el gestor de notificaciones no arranca, nunca se llama
           a startForeground() y el sistema cierra la app a los ~28 s
           con ForegroundServiceDidNotStartInTimeException (así lo
           mostró el registro de fallos del 13-nov-2026 23:30). Con la
           sesión registrada, la notificación aparece y el servicio
           pasa a primer plano en cuanto suena la música. */
        addSession(builtSession)
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
