package com.gurru.music.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gurru.music.data.ThemeRepository
import com.gurru.music.ui.theme.GurruTheme
import com.gurru.music.ui.theme.GurruThemes
import com.gurru.music.ui.theme.themeByName
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/* Tema de color actual de la app y la lista de los 7 temas para la
   pantalla de Ajustes. El cambio se guarda en DataStore y se aplica
   al instante en toda la app (MainActivity provee el tema con un
   CompositionLocal). */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ThemeRepository(application.applicationContext)

    val themes: List<GurruTheme> = GurruThemes

    val theme: StateFlow<GurruTheme> = repository.themeName
        .map { themeByName(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), themeByName("azul"))

    fun selectTheme(name: String) {
        viewModelScope.launch {
            repository.setThemeName(name)
        }
    }
}
