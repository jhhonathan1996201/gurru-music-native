package com.gurru.music.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/* Guarda el tema de color elegido (la clave "gurru-accent" es la misma
   que usa la app actual, para que el nombre no cambie). El tema se
   aplica en toda la app al instante porque MainActivity lo lee como
   StateFlow y lo provee con un CompositionLocal. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "gurru-settings")

class ThemeRepository(private val context: Context) {

    private val themeKey = stringPreferencesKey("gurru-accent")

    val themeName: Flow<String> = context.settingsDataStore.data
        .map { prefs -> prefs[themeKey] ?: "azul" }

    suspend fun setThemeName(name: String) {
        context.settingsDataStore.edit { prefs -> prefs[themeKey] = name }
    }
}
