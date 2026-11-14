package com.gurru.music

import android.app.Application
import com.gurru.music.player.PlayerManager

/* La app arranca el reproductor compartido al inicio, para que exista
   antes que cualquier pantalla o que el servicio en segundo plano. */
class GurruApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PlayerManager.init(this)
    }
}
