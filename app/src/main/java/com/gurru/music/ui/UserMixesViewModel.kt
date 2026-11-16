package com.gurru.music.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gurru.music.data.UserMix
import com.gurru.music.data.UserMixRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/* Mezclas creadas por el usuario (auto-DJ Fase 1): la lista guardada
   en DataStore y las operaciones para guardar y borrar. La usan la
   pestaña Mezclas (para mostrarlas y sonarlas) y el editor de mezclas
   (para crearlas y editarlas). */
class UserMixesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = UserMixRepository(application.applicationContext)

    private val _mixes = MutableStateFlow<List<UserMix>>(emptyList())
    val mixes: StateFlow<List<UserMix>> = _mixes.asStateFlow()

    init {
        viewModelScope.launch {
            repository.mixes.collect { _mixes.value = it }
        }
    }

    fun saveMix(mix: UserMix) {
        viewModelScope.launch {
            try {
                repository.saveMix(mix)
            } catch (_: Exception) {
            }
        }
    }

    fun deleteMix(id: String) {
        viewModelScope.launch {
            try {
                repository.deleteMix(id)
            } catch (_: Exception) {
            }
        }
    }
}
