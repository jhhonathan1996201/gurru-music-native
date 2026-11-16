package com.gurru.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gurru.music.data.TrackOrigin
import com.gurru.music.data.UserMix
import com.gurru.music.player.PlayerViewModel
import com.gurru.music.ui.theme.GurruBackground
import com.gurru.music.ui.theme.GurruRowBackground
import com.gurru.music.ui.theme.LocalGurruTheme

/* Editor de mezclas (auto-DJ Fase 1): crear una mezcla nueva o editar
   una guardada. Se elige un nombre, la duración del crossfade y las
   canciones locales en el orden en que van a sonar; al guardar, la
   mezcla queda en la pestaña Mezclas y al tocarla suena con crossfade
   y control de tempo. mixId null = mezcla nueva. */
@Composable
fun MixEditorScreen(
    playerViewModel: PlayerViewModel,
    mixesViewModel: UserMixesViewModel,
    mixId: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalGurruTheme.current
    val tracks by playerViewModel.tracks.collectAsState()
    val mixes by mixesViewModel.mixes.collectAsState()
    val localTracks = remember(tracks) { tracks.filter { it.origin == TrackOrigin.LOCAL } }

    var name by remember { mutableStateOf("") }
    var crossfadeSec by remember { mutableStateOf(8) }
    var query by remember { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<String>() }
    var loaded by remember { mutableStateOf(false) }

    /* En edición, rellenar los campos con la mezcla guardada (una sola
       vez, cuando la lista de mezclas ya llegó). */
    LaunchedEffect(mixes, mixId) {
        if (!loaded && mixId != null) {
            val mix = mixes.firstOrNull { it.id == mixId }
            if (mix != null) {
                name = mix.name
                crossfadeSec = mix.crossfadeSec
                selectedIds.clear()
                selectedIds.addAll(mix.trackIds)
                loaded = true
            }
        }
    }

    val selectedTracks = remember(selectedIds.toList()) {
        selectedIds.mapNotNull { id -> localTracks.find { it.id == id } }
    }
    val filtered = remember(localTracks, query) {
        val q = query.trim().lowercase()
        val base = localTracks.filter { it.id !in selectedIds }
        if (q.isBlank()) base else base.filter {
            it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
        }
    }
    val canSave = name.trim().isNotBlank() && selectedTracks.size >= 2

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(GurruBackground)
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Atrás",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            Text(
                text = if (mixId == null) "Crear mezcla" else "Editar mezcla",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(text = "Nombre de la mezcla", color = theme.soft) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = theme.accent,
                unfocusedBorderColor = theme.soft.copy(alpha = 0.4f),
                cursorColor = theme.accent
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "Crossfade entre canciones",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, sec) in listOf("Corto" to 5, "Normal" to 8, "Largo" to 12)) {
                val active = crossfadeSec == sec
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (active) theme.accent else GurruRowBackground)
                        .clickable { crossfadeSec = sec }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$label · ${sec}s",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (active) GurruBackground else Color.White
                    )
                }
            }
        }

        Text(
            text = "En la mezcla (${selectedTracks.size})",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )
        if (selectedTracks.isEmpty()) {
            Text(
                text = "Todavía no hay canciones. Agrégalas abajo con el botón +.",
                color = theme.soft,
                fontSize = 14.sp
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
        ) {
            items(selectedTracks, key = { it.id }) { track ->
                val idx = selectedIds.indexOf(track.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(GurruRowBackground)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${idx + 1}.",
                        color = theme.accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(28.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.artist,
                            color = theme.soft,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = {
                            if (idx > 0) {
                                selectedIds.removeAt(idx)
                                selectedIds.add(idx - 1, track.id)
                            }
                        },
                        enabled = idx > 0,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Subir",
                            tint = if (idx > 0) Color.White else theme.soft.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            if (idx < selectedIds.size - 1) {
                                selectedIds.removeAt(idx)
                                selectedIds.add(idx + 1, track.id)
                            }
                        },
                        enabled = idx < selectedIds.size - 1,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Bajar",
                            tint = if (idx < selectedIds.size - 1) Color.White else theme.soft.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { selectedIds.remove(track.id) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Quitar",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        Text(
            text = "Agregar canciones",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(text = "Buscar en tu biblioteca", color = theme.soft) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = theme.accent,
                unfocusedBorderColor = theme.soft.copy(alpha = 0.4f),
                cursorColor = theme.accent
            ),
            modifier = Modifier.fillMaxWidth()
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(filtered.take(60), key = { it.id }) { track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.artist,
                            color = theme.soft,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = { selectedIds.add(track.id) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Agregar",
                            tint = theme.accent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        Button(
            onClick = {
                val mix = UserMix(
                    id = mixId ?: "mix-${System.currentTimeMillis()}",
                    name = name.trim(),
                    trackIds = selectedIds.toList(),
                    crossfadeSec = crossfadeSec
                )
                mixesViewModel.saveMix(mix)
                onDone()
            },
            enabled = canSave,
            colors = ButtonDefaults.buttonColors(
                containerColor = theme.accent,
                disabledContainerColor = theme.soft.copy(alpha = 0.3f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            Text(
                text = if (mixId == null) "Guardar mezcla" else "Guardar cambios",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = GurruBackground
            )
        }
    }
}
