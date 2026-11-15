package com.gurru.music.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/* Cuántas veces ha sonado cada canción local. Es la base de
   "Mezclas para ti" y de "Accesos directos": las mezclas se arman
   con los artistas y álbumes que más escuchas. Se guarda como un
   JSON {"local-123": 5, ...} con la misma clave que la app actual
   ("gurru-plays-v1"). */
private val Context.playsDataStore: DataStore<Preferences> by preferencesDataStore(name = "gurru-plays")

class PlayCountRepository(private val context: Context) {

    private val countsKey = stringPreferencesKey("gurru-plays-v1")

    /* El mapa completo id -> reproducciones. Empieza vacío. */
    val counts: Flow<Map<String, Int>> = context.playsDataStore.data
        .map { prefs -> parseCounts(prefs[countsKey]) }

    /* Suma 1 a la canción indicada (solo se llama para canciones locales). */
    suspend fun increment(trackId: String) {
        context.playsDataStore.edit { prefs ->
            val current = parseCounts(prefs[countsKey]).toMutableMap()
            current[trackId] = (current[trackId] ?: 0) + 1
            prefs[countsKey] = JSONObject(current).toString()
        }
    }

    /* Lee el mapa una sola vez (útil para armar las mezclas sin observar). */
    suspend fun getCountsOnce(): Map<String, Int> = counts.first()

    private fun parseCounts(json: String?): Map<String, Int> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val result = mutableMapOf<String, Int>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = obj.optInt(key, 0)
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
