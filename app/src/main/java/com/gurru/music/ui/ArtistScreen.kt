package com.gurru.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gurru.music.R
import com.gurru.music.data.ArtistAlbum
import com.gurru.music.data.DownloadStatus
import com.gurru.music.ui.theme.GurruBackground
import com.gurru.music.ui.theme.GurruRowBackground
import com.gurru.music.ui.theme.LocalGurruTheme

/* Página del artista (Fase 4): top 10, álbumes y canciones de un álbum.
   Se abre desde la tarjeta de artista en la búsqueda de YouTube.
   Las filas de canciones reusan YoutubeTrackRow, igual que la búsqueda. */
@Composable
fun ArtistScreen(
    artistName: String,
    artistViewModel: ArtistViewModel,
    youtubeViewModel: YoutubeViewModel,
    downloadsViewModel: DownloadsViewModel? = null,
    onOpenPlayer: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalGurruTheme.current
    val name by artistViewModel.artistName.collectAsState()
    val loading by artistViewModel.loading.collectAsState()
    val topSongs by artistViewModel.topSongs.collectAsState()
    val albums by artistViewModel.albums.collectAsState()
    val selectedAlbum by artistViewModel.selectedAlbum.collectAsState()
    val albumTracks by artistViewModel.albumTracks.collectAsState()
    val loadingAlbum by artistViewModel.loadingAlbum.collectAsState()

    val downloads by downloadsViewModel?.downloads?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val activeDownloads = remember(downloads) {
        downloads.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PREPARING }
            .associate { it.videoId to it.progress }
    }
    val completedIds = remember(downloads) {
        downloads.filter { it.status == DownloadStatus.COMPLETED }.map { it.videoId }.toSet()
    }

    // Cargar el artista al entrar a la pantalla
    LaunchedEffect(artistName) {
        artistViewModel.load(artistName)
    }

    // Atrás: si hay un álbum abierto, vuelve a la lista de álbumes;
    // si no, vuelve a la búsqueda de YouTube
    BackHandler {
        if (selectedAlbum != null) artistViewModel.clearSelectedAlbum()
        else onBack()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(GurruBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Cabecera: atrás + nombre del artista ─────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (selectedAlbum != null) artistViewModel.clearSelectedAlbum()
                    else onBack()
                }) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Atrás",
                        tint = theme.accent,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Text(
                    text = name.ifBlank { artistName },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(theme.softBlue.copy(alpha = 0.18f))
            )

            if (loading) {
                // ── Cargando top 10 y álbumes ────────────────────────────
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = theme.accent,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Cargando artista…",
                            color = theme.soft,
                            fontSize = 14.sp
                        )
                    }
                }
            } else if (selectedAlbum != null) {
                // ── Canciones de un álbum ────────────────────────────────
                AlbumTracksList(
                    album = selectedAlbum!!,
                    tracks = albumTracks,
                    loading = loadingAlbum,
                    onPlay = { result ->
                        youtubeViewModel.play(result, fromSearch = false, onOpenPlayer = onOpenPlayer)
                    },
                    onDownload = downloadsViewModel?.let { vm -> { r -> vm.startDownload(r) } },
                    activeDownloads = activeDownloads,
                    completedIds = completedIds
                )
            } else {
                // ── Top 10 + Álbumes ─────────────────────────────────────
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp)
                ) {
                    // ── Top 10 ───────────────────────────────────────────
                    if (topSongs.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Top 10",
                                    color = theme.soft,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(
                                    modifier = Modifier.clickable {
                                        artistViewModel.playAll(onOpenPlayer)
                                    },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = theme.accent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        text = "Reproducir todo",
                                        color = theme.accent,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        items(topSongs, key = { it.videoId }) { result ->
                            YoutubeTrackRow(
                                result = result,
                                onClick = {
                                    youtubeViewModel.play(result, fromSearch = false, onOpenPlayer = onOpenPlayer)
                                },
                                onDownload = downloadsViewModel?.let { vm -> { vm.startDownload(result) } },
                                downloadProgress = activeDownloads[result.videoId],
                                isDownloaded = completedIds.contains(result.videoId)
                            )
                        }
                    } else {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No se encontraron canciones de este artista",
                                    color = theme.soft.copy(alpha = 0.6f),
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    // ── Álbumes ──────────────────────────────────────────
                    if (albums.isNotEmpty()) {
                        item {
                            Text(
                                text = "Álbumes",
                                color = theme.soft,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
                            )
                        }
                        items(albums.chunked(2)) { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                            ) {
                                row.forEach { album ->
                                    AlbumCell(
                                        album = album,
                                        onClick = { artistViewModel.selectAlbum(album) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (row.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(12.dp)) }
                }
            }
        }
    }
}

// ── Canciones de un álbum ────────────────────────────────────────────────────

@Composable
private fun AlbumTracksList(
    album: ArtistAlbum,
    tracks: List<com.gurru.music.data.YoutubeSearchResult>,
    loading: Boolean,
    onPlay: (com.gurru.music.data.YoutubeSearchResult) -> Unit,
    onDownload: ((com.gurru.music.data.YoutubeSearchResult) -> Unit)?,
    activeDownloads: Map<String, Int>,
    completedIds: Set<String>
) {
    val theme = LocalGurruTheme.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        // ── Portada y datos del álbum ────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = album.artworkUrl ?: R.drawable.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(GurruRowBackground)
                        .border(1.dp, theme.accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        text = album.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (album.year > 0) "${album.year} · ${album.trackCount} canciones"
                            else "${album.trackCount} canciones",
                        fontSize = 13.sp,
                        color = theme.soft
                    )
                }
            }
        }

        if (loading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = theme.accent,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        } else if (tracks.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No se pudieron cargar las canciones del álbum",
                        color = theme.soft.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            items(tracks, key = { it.videoId }) { result ->
                YoutubeTrackRow(
                    result = result,
                    onClick = { onPlay(result) },
                    onDownload = onDownload?.let { dl -> { dl(result) } },
                    downloadProgress = activeDownloads[result.videoId],
                    isDownloaded = completedIds.contains(result.videoId)
                )
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

// ── Celda de álbum (rejilla de 2 columnas) ──────────────────────────────────

@Composable
private fun AlbumCell(
    album: ArtistAlbum,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalGurruTheme.current
    Column(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = album.artworkUrl ?: R.drawable.logo,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(GurruRowBackground)
                .border(1.dp, theme.accent.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = album.name,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (album.year > 0) "${album.year}" else "",
            fontSize = 12.sp,
            color = theme.soft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
