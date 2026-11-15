package com.gurru.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gurru.music.R
import com.gurru.music.data.DownloadStatus
import com.gurru.music.data.DownloadTask
import com.gurru.music.ui.theme.GurruBackground
import com.gurru.music.ui.theme.GurruRowBackground
import com.gurru.music.ui.theme.LocalGurruTheme

/* Pantalla de Descargas. Muestra el progreso en porcentaje de las canciones
   que se están bajando de YouTube y la lista de canciones ya guardadas
   listas para reproducir sin conexión. */
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalGurruTheme.current
    val downloads by viewModel.downloads.collectAsState()

    val active = downloads.filter {
        it.status == DownloadStatus.DOWNLOADING ||
        it.status == DownloadStatus.PREPARING ||
        it.status == DownloadStatus.QUEUED
    }
    val completed = downloads.filter { it.status == DownloadStatus.COMPLETED }
    val failed = downloads.filter { it.status == DownloadStatus.FAILED }

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(GurruBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Título de la pestaña
            Text(
                text = "Descargas",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
            )

            if (downloads.isEmpty()) {
                // Estado vacío
                EmptyDownloadsView(accentColor = theme.accent, softColor = theme.soft)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // ── En curso ──────────────────────────────────────────────
                    if (active.isNotEmpty()) {
                        item {
                            Text(
                                text = "En curso (${active.size})",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = theme.accent,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(active, key = { "active-${it.videoId}" }) { task ->
                            ActiveDownloadRow(
                                task     = task,
                                onCancel = { viewModel.cancelDownload(task.videoId) }
                            )
                        }
                    }

                    // ── Fallidas ──────────────────────────────────────────────
                    if (failed.isNotEmpty()) {
                        item {
                            Text(
                                text = "Errores (${failed.size})",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFFF4D6D),
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                            )
                        }
                        items(failed, key = { "failed-${it.videoId}" }) { task ->
                            FailedDownloadRow(
                                task     = task,
                                onRetry  = { viewModel.retryDownload(task) },
                                onDelete = { viewModel.deleteDownload(task.videoId) }
                            )
                        }
                    }

                    // ── Completadas ───────────────────────────────────────────
                    if (completed.isNotEmpty()) {
                        item {
                            Text(
                                text = "Guardadas en el teléfono (${completed.size})",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                            )
                        }
                        items(completed, key = { "done-${it.videoId}" }) { task ->
                            CompletedDownloadRow(
                                task     = task,
                                onPlay   = { viewModel.playDownloaded(task, onOpenPlayer) },
                                onDelete = { viewModel.deleteDownload(task.videoId) }
                            )
                        }
                    }

                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}

// ── Fila de descarga activa con porcentaje y barra ─────────────────────────────

@Composable
private fun ActiveDownloadRow(
    task: DownloadTask,
    onCancel: () -> Unit
) {
    val theme = LocalGurruTheme.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(GurruRowBackground)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = task.thumbnailUrl ?: R.drawable.logo,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholder = painterResource(R.drawable.logo),
            error = painterResource(R.drawable.logo),
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, theme.accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = task.artist,
                fontSize = 12.sp,
                color = theme.soft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Barra de progreso y texto de porcentaje
            val progressFraction = (task.progress / 100f).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = theme.accent,
                trackColor = Color.White.copy(alpha = 0.15f)
            )

            Spacer(modifier = Modifier.height(4.dp))

            val statusText = when (task.status) {
                DownloadStatus.PREPARING -> "Preparando audio…"
                DownloadStatus.DOWNLOADING -> {
                    val speedText = if (task.speedBytesPerSec >= 1024 * 1024) {
                        "%.1f MB/s".format(task.speedBytesPerSec / 1024.0 / 1024.0)
                    } else {
                        "${task.speedBytesPerSec / 1024} KB/s"
                    }
                    "Descargando: ${task.progress}% · $speedText"
                }
                else -> "En cola…"
            }

            Text(
                text = statusText,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = theme.accent
            )
        }

        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancelar",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── Fila de descarga completada ────────────────────────────────────────────────

@Composable
private fun CompletedDownloadRow(
    task: DownloadTask,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val theme = LocalGurruTheme.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(GurruRowBackground)
            .clickable { onPlay() }
            .padding(vertical = 8.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = task.thumbnailUrl ?: R.drawable.logo,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholder = painterResource(R.drawable.logo),
            error = painterResource(R.drawable.logo),
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(8.dp))
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${task.artist} · ${formatDuration(task.durationSec)}",
                fontSize = 12.sp,
                color = theme.soft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onPlay) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Reproducir",
                tint = theme.accent,
                modifier = Modifier.size(26.dp)
            )
        }

        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Eliminar",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── Fila de descarga fallida ───────────────────────────────────────────────────

@Composable
private fun FailedDownloadRow(
    task: DownloadTask,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(GurruRowBackground)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = task.error ?: "Error desconocido",
                fontSize = 11.5.sp,
                color = Color(0xFFFF4D6D),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onRetry) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Reintentar",
                tint = Color.White
            )
        }

        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Descartar",
                tint = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

// ── Estado vacío ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyDownloadsView(accentColor: Color, softColor: Color) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                tint = accentColor.copy(alpha = 0.6f),
                modifier = Modifier.size(72.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Sin descargas",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Busca canciones en la pestaña de YouTube y pulsa el botón de descarga para guardarlas y escucharlas sin conexión.",
                fontSize = 13.5.sp,
                color = softColor,
                lineHeight = 18.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
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
