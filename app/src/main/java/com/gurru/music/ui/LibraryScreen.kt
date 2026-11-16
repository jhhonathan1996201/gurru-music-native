package com.gurru.music.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gurru.music.R
import com.gurru.music.data.Track
import com.gurru.music.player.PlayerViewModel
import com.gurru.music.ui.theme.GurruBackground
import com.gurru.music.ui.theme.GurruRowBackground
import com.gurru.music.ui.theme.LocalGurruTheme

/* Sub-pestañas de la Biblioteca, igual que en la app actual. En Fase 1
   las listas de reproducción todavía no existen: la pestaña Listas
   muestra un texto y llegan en una fase posterior. */
enum class LibTab(val label: String) {
    TRACKS("Pistas"),
    ALBUMS("Álbumes"),
    PLAYLISTS("Listas"),
    ARTISTS("Artistas"),
    FOLDERS("Carpetas")
}

private data class DrillGroup(val tab: LibTab, val name: String, val tracks: List<Track>)

private data class Group(val name: String, val tracks: List<Track>)

@Composable
fun LibraryScreen(
    viewModel: PlayerViewModel,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalGurruTheme.current
    val context = LocalContext.current
    val tracks by viewModel.tracks.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val hasPermission by viewModel.hasPermission.collectAsState()
    val query by viewModel.query.collectAsState()
    var tab by remember { mutableStateOf(LibTab.TRACKS) }
    var drill by remember { mutableStateOf<DrillGroup?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onPermissionResult(granted) }

    LaunchedEffect(Unit) { viewModel.checkPermission() }

    /* Atrás: primero cierra el grupo abierto; en la Biblioteca
       principal minimiza la app en vez de salir (como en la app
       actual, para que la música siga sonando). */
    val activity = context as? Activity
    BackHandler(enabled = drill != null) { drill = null }
    BackHandler(enabled = drill == null) { activity?.moveTaskToBack(true) }

    val filtered = remember(tracks, query) {
        if (query.isBlank()) {
            tracks
        } else {
            val q = query.trim().lowercase()
            tracks.filter {
                it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(GurruBackground)
            .padding(horizontal = 12.dp)
    ) {
        /* Logo + búsqueda, como en la app actual. */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = "Gurru Music",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .shadow(12.dp, RoundedCornerShape(12.dp))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(GurruRowBackground)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = theme.soft,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Buscar",
                            color = theme.soft.copy(alpha = 0.7f),
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { viewModel.setQuery(it) },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 15.sp
                        ),
                        cursorBrush = SolidColor(theme.accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.setQuery("") },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Borrar",
                            tint = theme.soft,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        /* Sub-pestañas, con su línea inferior, como en la app actual. */
        Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                for (t in LibTab.entries) {
                    val active = t == tab
                    Column(
                        modifier = Modifier
                            .clickable { tab = t; drill = null }
                            .padding(horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = t.label,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (active) Color.White else theme.soft,
                            modifier = Modifier.padding(top = 8.dp, bottom = 9.dp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .background(if (active) theme.accent else Color.Transparent)
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(theme.softBlue.copy(alpha = 0.18f))
            )
        }

        when {
            !hasPermission -> {
                PermissionBox(onRequest = { launcher.launch(viewModel.requiredPermission()) })
            }
            scanning && tracks.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Buscando tu música en el teléfono…", color = theme.soft, fontSize = 15.sp)
                }
            }
            tracks.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Biblioteca vacía", color = Color.White, fontSize = 18.sp)
                }
            }
            drill != null -> {
                DrillList(
                    group = drill!!,
                    viewModel = viewModel,
                    onBack = { drill = null },
                    onOpenPlayer = onOpenPlayer
                )
            }
            tab == LibTab.TRACKS -> {
                TrackList(tracks = filtered, viewModel = viewModel, onOpenPlayer = onOpenPlayer)
            }
            tab == LibTab.PLAYLISTS -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Aún no hay listas",
                        color = theme.soft,
                        fontSize = 15.sp
                    )
                }
            }
            else -> {
                val groups = remember(filtered, tab) {
                    when (tab) {
                        LibTab.ALBUMS -> groupBy(filtered) { if (it.album.isBlank()) "Sin álbum" else it.album }
                        LibTab.ARTISTS -> groupBy(filtered) { it.artist }
                        LibTab.FOLDERS -> groupBy(filtered) { it.folder }
                        else -> emptyList()
                    }
                }
                GroupList(
                    groups = groups,
                    onGroup = { drill = DrillGroup(tab, it.name, it.tracks) }
                )
            }
        }
    }
}

private fun groupBy(tracks: List<Track>, key: (Track) -> String): List<Group> =
    tracks.groupBy(key)
        .map { (name, list) -> Group(name, list) }
        .sortedBy { it.name.lowercase() }

@Composable
private fun PermissionBox(onRequest: () -> Unit) {
    val theme = LocalGurruTheme.current
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "Gurru Music necesita permiso para ver tu música",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(
                containerColor = theme.accent,
                contentColor = GurruBackground
            )
        ) {
            Text("Permitir acceso a la música", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    viewModel: PlayerViewModel,
    onOpenPlayer: () -> Unit
) {
    val currentTrack by viewModel.currentTrack.collectAsState()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(tracks, key = { it.id }) { track ->
            TrackRow(
                track = track,
                playing = track.id == currentTrack?.id,
                onClick = {
                    val idx = tracks.indexOf(track)
                    viewModel.playQueue(tracks, idx)
                    onOpenPlayer()
                }
            )
        }
    }
}

/* Fila de canción: portada de 52px, título y artista · álbum, como en
   la app actual. La que suena lleva el título en cian y un aro en la
   portada. */
@Composable
fun TrackRow(track: Track, playing: Boolean, onClick: () -> Unit) {
    val theme = LocalGurruTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = track.artUri ?: R.drawable.logo,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholder = painterResource(R.drawable.logo),
            error = painterResource(R.drawable.logo),
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(GurruRowBackground)
                .then(
                    if (playing) Modifier.border(1.5.dp, theme.accent, RoundedCornerShape(8.dp))
                    else Modifier
                )
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        ) {
            Text(
                text = track.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (playing) theme.accent else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (track.album.isBlank()) track.artist else "${track.artist} · ${track.album}",
                fontSize = 12.5.sp,
                color = theme.soft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GroupList(groups: List<Group>, onGroup: (Group) -> Unit) {
    val theme = LocalGurruTheme.current
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(groups, key = { it.name }) { group ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onGroup(group) }
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = group.tracks.firstOrNull()?.artUri ?: R.drawable.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(R.drawable.logo),
                    error = painterResource(R.drawable.logo),
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(GurruRowBackground)
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                ) {
                    Text(
                        text = group.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${group.tracks.size} canciones",
                        fontSize = 12.5.sp,
                        color = theme.soft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(text = "›", fontSize = 18.sp, color = theme.soft)
            }
        }
    }
}

@Composable
private fun DrillList(
    group: DrillGroup,
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    val theme = LocalGurruTheme.current
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${group.tracks.size} canciones",
                    fontSize = 12.5.sp,
                    color = theme.soft
                )
            }
            IconButton(
                onClick = {
                    viewModel.playQueue(group.tracks, 0)
                    onOpenPlayer()
                },
                modifier = Modifier
                    .size(52.dp)
                    .background(theme.accent, CircleShape)
                    .shadow(16.dp, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Reproducir todo",
                    tint = GurruBackground,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        TrackList(tracks = group.tracks, viewModel = viewModel, onOpenPlayer = onOpenPlayer)
    }
}
