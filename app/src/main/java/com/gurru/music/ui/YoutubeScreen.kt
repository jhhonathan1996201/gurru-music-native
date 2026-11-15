package com.gurru.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.gurru.music.data.DownloadStatus
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gurru.music.R
import com.gurru.music.data.YoutubeSearchResult
import com.gurru.music.ui.theme.GurruBackground
import com.gurru.music.ui.theme.GurruRowBackground
import com.gurru.music.ui.theme.LocalGurruTheme

/* Pestaña YouTube: búsqueda de música en línea, radio automática,
   reproducción con estado "Preparando…" y botón de descarga por canción. */
@Composable
fun YoutubeScreen(
    viewModel: YoutubeViewModel,
    downloadsViewModel: DownloadsViewModel? = null,
    onOpenPlayer: () -> Unit,
    onOpenArtist: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val theme       = LocalGurruTheme.current
    val query       by viewModel.query.collectAsState()
    val results     by viewModel.results.collectAsState()
    val suggestedArtist by viewModel.suggestedArtist.collectAsState()
    val searching   by viewModel.searching.collectAsState()
    val prepState   by viewModel.prepareState.collectAsState()
    val recents     by viewModel.recents.collectAsState()

    val downloads by downloadsViewModel?.downloads?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val activeDownloads = remember(downloads) {
        downloads.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PREPARING }
            .associate { it.videoId to it.progress }
    }
    val completedIds = remember(downloads) {
        downloads.filter { it.status == DownloadStatus.COMPLETED }.map { it.videoId }.toSet()
    }

    // Atrás borra la búsqueda (si hay una activa) en vez de salir
    BackHandler(enabled = query.isNotBlank() || results.isNotEmpty()) {
        viewModel.clearSearch()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(GurruBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Cabecera: logo + buscador ───────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp),
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
                TextField(
                    value = query,
                    onValueChange = { viewModel.setQuery(it) },
                    placeholder = {
                        Text("Buscar en YouTube", color = Color.White.copy(alpha = 0.6f))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor   = GurruRowBackground,
                        unfocusedContainerColor = GurruRowBackground,
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor        = Color.White,
                        unfocusedTextColor      = Color.White,
                        cursorColor             = theme.accent
                    ),
                    shape    = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = { viewModel.search() }) {
                    if (searching) {
                        CircularProgressIndicator(
                            color     = theme.accent,
                            modifier  = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Buscar",
                            tint   = theme.accent,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            // ── Separador ──────────────────────────────────────────────────
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(theme.softBlue.copy(alpha = 0.18f))
            )

            // ── Lista de resultados y/o recientes ─────────────────────────
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
            ) {
                // ── Resultados de búsqueda ──────────────────────────────
                // Tarjeta del artista (Fase 4): arriba de las canciones
                // cuando la búsqueda es de un artista. Tocar abre su página.
                if (results.isNotEmpty() && suggestedArtist != null) {
                    item {
                        ArtistCard(
                            artistName = suggestedArtist!!,
                            onClick = { onOpenArtist(suggestedArtist!!) }
                        )
                    }
                }

                if (results.isNotEmpty()) {
                    items(results, key = { it.videoId }) { result ->
                        YoutubeTrackRow(
                            result           = result,
                            onClick          = {
                                viewModel.play(result, fromSearch = true, onOpenPlayer = onOpenPlayer)
                            },
                            onDownload       = downloadsViewModel?.let { vm -> { vm.startDownload(result) } },
                            downloadProgress = activeDownloads[result.videoId],
                            isDownloaded     = completedIds.contains(result.videoId)
                        )
                    }
                } else if (query.isBlank()) {
                    // ── Sin búsqueda activa: placeholder ───────────────
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Busca canciones, artistas o álbumes",
                                color = theme.soft.copy(alpha = 0.6f),
                                fontSize = 15.sp
                            )
                        }
                    }
                } else {
                    // ── Búsqueda sin resultados ────────────────────────
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Sin resultados para \"$query\"",
                                color = theme.soft.copy(alpha = 0.6f),
                                fontSize = 15.sp
                            )
                        }
                    }
                }

                // ── Recientes ───────────────────────────────────────────
                if (recents.isNotEmpty()) {
                    item {
                        Text(
                            text = "Escuchado recientemente",
                            color = theme.soft,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
                        )
                    }
                    items(recents, key = { "rec-${it.videoId}" }) { result ->
                        YoutubeTrackRow(
                            result           = result,
                            onClick          = {
                                // Los recientes NO se vuelven a agregar a recientes
                                viewModel.play(result, fromSearch = false, onOpenPlayer = onOpenPlayer)
                            },
                            onDownload       = downloadsViewModel?.let { vm -> { vm.startDownload(result) } },
                            downloadProgress = activeDownloads[result.videoId],
                            isDownloaded     = completedIds.contains(result.videoId)
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(12.dp)) }
            }
        }

        // ── Overlay: Preparando… ───────────────────────────────────────────
        if (prepState is PrepareState.Preparing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(GurruBackground.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color       = Color(0xFFFF4D6D),
                        strokeWidth = 3.dp,
                        modifier    = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text       = "Preparando…",
                        color      = Color.White,
                        fontSize   = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ── Overlay: Error ─────────────────────────────────────────────────
        if (prepState is PrepareState.Error) {
            val err = prepState as PrepareState.Error
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(GurruBackground.copy(alpha = 0.90f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(32.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(GurruRowBackground)
                        .padding(24.dp)
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint     = Color(0xFFFF4D6D),
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text       = "No se pudo preparar la canción",
                        color      = Color.White,
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text     = err.message,
                        color    = Color(0xFFFF4D6D),
                        fontSize = 13.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row {
                        OutlinedButton(
                            onClick = { viewModel.dismissError() },
                            border  = androidx.compose.foundation.BorderStroke(
                                1.dp, Color.White.copy(alpha = 0.4f)
                            )
                        ) {
                            Text("Cancelar", color = Color.White)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = { viewModel.retry(err.result, onOpenPlayer) },
                            colors  = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF4D6D),
                                contentColor   = Color.White
                            )
                        ) {
                            Text("Reintentar", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ── Fila de resultado de YouTube ──────────────────────────────────────────────

/* Muestra: thumbnail 52×52, título, artista · duración y botón de descarga.
   Es pública para reusarla en la página del artista (Fase 4). */
@Composable
fun YoutubeTrackRow(
    result: YoutubeSearchResult,
    onClick: () -> Unit,
    onDownload: (() -> Unit)? = null,
    downloadProgress: Int? = null,
    isDownloaded: Boolean = false
) {
    val theme = LocalGurruTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model               = result.thumbnailUrl ?: R.drawable.logo,
            contentDescription  = null,
            contentScale        = ContentScale.Crop,
            placeholder         = painterResource(R.drawable.logo),
            error               = painterResource(R.drawable.logo),
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(GurruRowBackground)
                .border(1.dp, Color(0xFFFF4D6D).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        ) {
            Text(
                text       = result.title,
                fontSize   = 15.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            Text(
                text     = "${result.artist} · ${formatDuration(result.durationSec)}",
                fontSize = 12.5.sp,
                color    = theme.soft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // ── Botón de descarga ──────────────────────────────────────────
        if (onDownload != null) {
            when {
                downloadProgress != null -> {
                    // Descargando: círculo de progreso con porcentaje
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { (downloadProgress / 100f).coerceIn(0f, 1f) },
                            color = theme.accent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "$downloadProgress",
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                isDownloaded -> {
                    // Ya descargada en el teléfono
                    Box(
                        modifier = Modifier
                            .size(38.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Descargada",
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                else -> {
                    // Lista para descargar
                    IconButton(
                        onClick = onDownload,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Descargar",
                            tint = theme.soft,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/* Tarjeta del artista (Fase 4): aparece arriba de los resultados
   cuando la búsqueda es de un artista. Muestra el nombre del artista
   (del campo artista de los resultados, no de una canción) y abre su
   página al tocarla. */
@Composable
private fun ArtistCard(
    artistName: String,
    onClick: () -> Unit
) {
    val theme = LocalGurruTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(GurruRowBackground)
            .border(1.dp, theme.accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(theme.accent.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = theme.accent,
                modifier = Modifier.size(30.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = artistName,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Artista · Ver top 10 y álbumes",
                fontSize = 12.5.sp,
                color = theme.soft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "Ver artista",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = theme.accent
        )
    }
}

private fun formatDuration(secs: Double): String {
    val total = secs.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0)
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else
        "$m:${s.toString().padStart(2, '0')}"
}
