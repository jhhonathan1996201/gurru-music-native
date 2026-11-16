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
import org.json.JSONArray
import org.json.JSONObject

/* Mezclas creadas por el usuario (auto-DJ Fase 1): se guardan como
   JSON en DataStore, una lista de {id, name, trackIds[], crossfadeSec}.
   Mismo patrón que PlayCountRepository (gurru-plays). */
private val Context.mixesDataStore: DataStore<Preferences> by preferencesDataStore(name = "gurru-mixes")

class UserMixRepository(private val context: Context) {

    private val mixesKey = stringPreferencesKey("gurru-user-mixes-v1")

    /* La lista completa de mezclas guardadas. Empieza vacía. */
    val mixes: Flow<List<UserMix>> = context.mixesDataStore.data
        .map { prefs -> parseMixes(prefs[mixesKey]) }

    /* Guarda (o actualiza si ya existe el mismo id) una mezcla. */
    suspend fun saveMix(mix: UserMix) {
        context.mixesDataStore.edit { prefs ->
            val current = parseMixes(prefs[mixesKey]).toMutableList()
            val idx = current.indexOfFirst { it.id == mix.id }
            if (idx >= 0) current[idx] = mix else current.add(mix)
            prefs[mixesKey] = serializeMixes(current)
        }
    }

    /* Borra una mezcla por su id. */
    suspend fun deleteMix(id: String) {
        context.mixesDataStore.edit { prefs ->
            val current = parseMixes(prefs[mixesKey]).filter { it.id != id }
            prefs[mixesKey] = serializeMixes(current)
        }
    }

    /* Lee las mezclas una sola vez (útil sin observar el flujo). */
    suspend fun getMixesOnce(): List<UserMix> = mixes.first()

    private fun parseMixes(json: String?): List<UserMix> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val result = mutableListOf<UserMix>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                if (id.isBlank()) continue
                val ids = mutableListOf<String>()
                val idsArr = obj.optJSONArray("trackIds")
                if (idsArr != null) {
                    for (j in 0 until idsArr.length()) {
                        val t = idsArr.optString(j, "")
                        if (t.isNotBlank()) ids.add(t)
                    }
                }
                result.add(
                    UserMix(
                        id = id,
                        name = obj.optString("name", "Mi mezcla").ifBlank { "Mi mezcla" },
                        trackIds = ids,
                        crossfadeSec = obj.optInt("crossfadeSec", 8).coerceIn(3, 15)
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializeMixes(mixes: List<UserMix>): String {
        val arr = JSONArray()
        for (m in mixes) {
            val obj = JSONObject()
            obj.put("id", m.id)
            obj.put("name", m.name)
            val ids = JSONArray()
            for (id in m.trackIds) ids.put(id)
            obj.put("trackIds", ids)
            obj.put("crossfadeSec", m.crossfadeSec)
            arr.put(obj)
        }
        return arr.toString()
    }
}
