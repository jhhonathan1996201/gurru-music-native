package com.gurru.music.player

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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
   Envuelve el ExoPlayer compartido en un ForwardingPlayer para anunciar
   siempre los comandos de Siguiente/Anterior (ya que ExoPlayer solo
   carga 1 pista a la vez mientras PlayerManager gestiona la cola). */
@OptIn(UnstableApi::class)
class MusicService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        PlayerManager.init(applicationContext)
        val forwardingPlayer = object : ForwardingPlayer(PlayerManager.player) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun hasNextMediaItem(): Boolean = true

            override fun hasPreviousMediaItem(): Boolean = true

            override fun seekToNext() {
                PlayerManager.next()
            }

            override fun seekToNextMediaItem() {
                PlayerManager.next()
            }

            override fun seekToPrevious() {
                PlayerManager.prev()
            }

            override fun seekToPreviousMediaItem() {
                PlayerManager.prev()
            }
        }

        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(ACTION_STOP, Bundle.EMPTY))
                    .build()
                val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .setAvailablePlayerCommands(playerCommands)
                    .build()
            }

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
        val builtSession = MediaSession.Builder(this, forwardingPlayer)
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
