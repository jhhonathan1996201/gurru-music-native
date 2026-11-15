package com.gurru.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.gurru.music.data.DownloadStatus
import com.gurru.music.data.LyricsData
import com.gurru.music.data.TrackOrigin
import com.gurru.music.data.YoutubeSearchResult
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gurru.music.R
import com.gurru.music.data.Track
import com.gurru.music.player.PlayerViewModel
import com.gurru.music.player.RepeatMode
import com.gurru.music.ui.theme.GurruBackground
import com.gurru.music.ui.theme.GurruRowBackground
import com.gurru.music.ui.theme.LocalGurruTheme

/* Reproductor: portada grande, título, artista, álbum, barra de
   progreso con seek, controles (anterior, play/pausa, siguiente,
   aleatorio, repetir) y la cola "A continuación" con la canción que
   suena marcada; tocar una de la cola salta a ella. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    downloadsViewModel: DownloadsViewModel? = null,
    modifier: Modifier = Modifier
) {
    val theme = LocalGurruTheme.current
    val track by viewModel.currentTrack.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val shuffle by viewModel.shuffle.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()

    val lyricsData by viewModel.lyricsData.collectAsState()
    val loadingLyrics by viewModel.loadingLyrics.collectAsState()
    var showLyrics by remember { mutableStateOf(false) }

    /* Descarga de la canción que suena (solo online): se observa la lista
       de descargas para mostrar Descargar / % / Descargada. */
    val downloads by downloadsViewModel?.downloads?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val isOnline = track?.origin == TrackOrigin.YOUTUBE && track?.youtubeId != null
    val currentDownload = remember(downloads, track?.youtubeId) {
        downloads.firstOrNull { it.videoId == track?.youtubeId }
    }
    val isDownloaded = currentDownload?.status == DownloadStatus.COMPLETED
    val downloadProgress = if (currentDownload?.status == DownloadStatus.DOWNLOADING || currentDownload?.status == DownloadStatus.PREPARING) currentDownload?.progress else null

    /* Posición del dedo mientras se arrastra la barra, para que la
       posición del reproductor no pelee con el arrastre. */
    var sliderValue by remember { mutableStateOf<Float?>(null) }
    /* La barra va de 0 a 1: se limita por si la duración que trae el
       archivo es más corta que la real y la posición la rebasara, que
       es lo que antes podía cerrar la app al cambiar de canción. */
    val progress = (if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f)
        .coerceIn(0f, 1f)

    /* "A continuación" empieza escondida, como en la app actual: se
       abre y se cierra tocando el encabezado, y al abrirse salta a la
       canción que suena para no dejarla a medias fuera de la vista.
       Con la cola oculta el reproductor llena toda la pantalla: la
       portada crece y el encabezado baja hasta abajo. */
    var queueExpanded by remember { mutableStateOf(false) }
    val queueListState = rememberLazyListState()
    LaunchedEffect(queueExpanded, currentIndex) {
        if (queueExpanded && currentIndex >= 0) {
            queueListState.scrollToItem(currentIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GurruBackground)
            .padding(horizontal = 20.dp)
    ) {
        /* Con la cola oculta el reproductor llena toda la pantalla: la
           portada crece a todo lo ancho y los espacios arriba y abajo
           centran la portada y los controles, para que no queden
           pegados arriba con el hueco en medio. */
        if (!queueExpanded) {
            Spacer(modifier = Modifier.weight(0.6f))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .animateContentSize(),
            contentAlignment = Alignment.Center
        ) {
            val viewModifier = Modifier
                .then(
                    if (queueExpanded) Modifier.size(260.dp)
                    else Modifier.fillMaxWidth().aspectRatio(1f)
                )

            if (showLyrics) {
                LyricsView(
                    lyrics     = lyricsData,
                    loading    = loadingLyrics,
                    positionMs = positionMs,
                    onSeek     = { viewModel.seekTo(it) },
                    onRetry    = { viewModel.loadLyricsForCurrentTrack() },
                    modifier   = viewModifier
                )
            } else {
                AnimatedContent(
                    targetState = track?.id,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(350)) + scaleIn(initialScale = 0.96f, animationSpec = tween(350))) togetherWith
                            (fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.96f, animationSpec = tween(200)))
                    },
                    label = "portada",
                    modifier = viewModifier
                ) { _ ->
                    AsyncImage(
                        model = track?.artUri ?: R.drawable.logo,
                        contentDescription = "Portada",
                        contentScale = ContentScale.Crop,
                        placeholder = painterResource(R.drawable.logo),
                        error = painterResource(R.drawable.logo),
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(GurruRowBackground)
                            .shadow(12.dp, RoundedCornerShape(12.dp))
                    )
                }
            }
        }

        Text(
            text = track?.artist ?: "Sin música todavía",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = theme.soft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
        )
        Text(
            text = track?.title ?: "Elige una canción",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = track?.album ?: "",
            fontSize = 13.sp,
            color = theme.soft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = formatDuration(positionMs), fontSize = 12.sp, color = theme.soft)
            Spacer(modifier = Modifier.weight(1f))
            Text(text = formatDuration(durationMs), fontSize = 12.sp, color = theme.soft)
        }
        Slider(
            value = sliderValue ?: progress,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                sliderValue?.let { viewModel.seekTo((it * durationMs.toFloat()).toLong()) }
                sliderValue = null
            },
            enabled = track != null,
            colors = SliderDefaults.colors(
                thumbColor = theme.accent,
                activeTrackColor = theme.accent,
                inactiveTrackColor = theme.soft.copy(alpha = 0.3f)
            ),
            /* Bolita: el pulgar es un círculo sólido del color del tema,
               como en la app anterior; antes era una rayita vertical. */
            thumb = {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(theme.accent, CircleShape)
                )
            },
            /* Pista limpia: rectángulo redondeado sin punto al final. */
            track = {
                val fraction = (sliderValue ?: progress).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(theme.soft.copy(alpha = 0.3f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(theme.accent)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        /* ── Acciones: Letras / Carátula + Descargar (online) ──
           El botón de letra está aquí, junto a los controles, para que
           se alcance fácil con el pulgar; antes estaba arriba, chico.
           El de descarga solo aparece cuando la canción es de YouTube. */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Botón Letras / Carátula
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (showLyrics) theme.accent else GurruRowBackground)
                    .clickable {
                        showLyrics = !showLyrics
                        if (showLyrics && lyricsData == null) {
                            viewModel.loadLyricsForCurrentTrack()
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (showLyrics) Icons.Default.Image else Icons.Default.Mic,
                    contentDescription = if (showLyrics) "Ver Carátula" else "Ver Letras",
                    tint = if (showLyrics) GurruBackground else theme.accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (showLyrics) "Carátula" else "Letras",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (showLyrics) GurruBackground else Color.White
                )
            }

            // Botón Descargar (solo canciones online de YouTube)
            if (isOnline) {
                Spacer(modifier = Modifier.width(10.dp))
                when {
                    downloadProgress != null -> {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(GurruRowBackground)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                progress = { (downloadProgress / 100f).coerceIn(0f, 1f) },
                                color = theme.accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Descargando $downloadProgress%",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                    isDownloaded -> {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(GurruRowBackground)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Descargada",
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Descargada",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                    else -> {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(GurruRowBackground)
                                .clickable {
                                    val t = track
                                    val vid = t?.youtubeId
                                    if (t != null && vid != null) {
                                        downloadsViewModel?.startDownload(
                                            YoutubeSearchResult(
                                                videoId = vid,
                                                title = t.title,
                                                artist = t.artist,
                                                durationSec = t.durationSec,
                                                thumbnailUrl = t.artUri
                                            )
                                        )
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Descargar",
                                tint = theme.accent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Descargar",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
        // Fuente de la letra, chica y centrada bajo los botones
        if (showLyrics && lyricsData != null) {
            Text(
                text = "Fuente: ${lyricsData?.source}",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = theme.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.toggleShuffle() }) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Aleatorio",
                    tint = if (shuffle) theme.accent else Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.prev() }, modifier = Modifier.size(56.dp)) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Anterior",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = { viewModel.togglePlay() },
                modifier = Modifier
                    .size(72.dp)
                    .background(theme.accent, CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                    tint = GurruBackground,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = { viewModel.next() }, modifier = Modifier.size(56.dp)) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Siguiente",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Box(contentAlignment = Alignment.BottomEnd) {
                IconButton(onClick = { viewModel.cycleRepeat() }) {
                    Icon(
                        imageVector = if (repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "Repetir",
                        tint = if (repeatMode == RepeatMode.OFF) Color.White else theme.accent,
                        modifier = Modifier.size(26.dp)
                    )
                }
                if (repeatMode == RepeatMode.ONE) {
                    Text(
                        text = "1",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.accent,
                        modifier = Modifier.padding(end = 6.dp, bottom = 4.dp)
                    )
                }
            }
        }

        if (!queueExpanded) {
            Spacer(modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { queueExpanded = !queueExpanded }
                .padding(top = 20.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "A continuación",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "  ${queue.size} canciones",
                fontSize = 12.sp,
                color = theme.soft
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (queueExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (queueExpanded) "Ocultar cola" else "Mostrar cola",
                tint = theme.soft,
                modifier = Modifier.size(24.dp)
            )
        }
        if (queueExpanded) {
            LazyColumn(state = queueListState, modifier = Modifier.weight(1f)) {
                itemsIndexed(queue, key = { _, t -> t.id }) { idx, t ->
                    QueueRow(
                        track = t,
                        number = idx + 1,
                        playing = idx == currentIndex,
                        onClick = { viewModel.playAt(idx) }
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(track: Track, number: Int, playing: Boolean, onClick: () -> Unit) {
    val theme = LocalGurruTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (playing) theme.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 5.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = number.toString(),
            fontSize = 13.sp,
            color = if (playing) theme.accent else theme.soft,
            modifier = Modifier.width(28.dp),
            textAlign = TextAlign.Center
        )
        AsyncImage(
            model = track.artUri ?: R.drawable.logo,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholder = painterResource(R.drawable.logo),
            error = painterResource(R.drawable.logo),
            modifier = Modifier
                .size(44.dp)
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
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (playing) theme.accent else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                fontSize = 12.sp,
                color = theme.soft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── Vista de Letras (Sincronizada / Karaoke y Tap-to-Seek) ────────────────────

@Composable
private fun LyricsView(
    lyrics: LyricsData?,
    loading: Boolean,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalGurruTheme.current

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(GurruRowBackground)
            .border(1.dp, theme.accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = theme.accent,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Buscando letra…", color = Color.White, fontSize = 14.sp)
                }
            }
            lyrics == null || lyrics.lines.isEmpty() -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "No se encontró la letra de esta canción",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(theme.accent)
                            .clickable { onRetry() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = GurruBackground,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Reintentar",
                            color = GurruBackground,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
            else -> {
                // Cálculo de la línea activa en base al tiempo actual
                val activeIndex = remember(positionMs, lyrics) {
                    if (lyrics.isSynced) {
                        lyrics.lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
                    } else -1
                }

                val listState = rememberLazyListState()

                // Auto-scroll suave hacia la línea activa
                LaunchedEffect(activeIndex) {
                    if (lyrics.isSynced && activeIndex >= 0 && !listState.isScrollInProgress) {
                        listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    if (lyrics.isSynced) {
                        Text(
                            text = "Toca cualquier línea para saltar a ese momento",
                            fontSize = 11.sp,
                            color = theme.soft.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        )
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(lyrics.lines) { index, line ->
                            val isActive = (index == activeIndex)

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = line.timeMs >= 0) {
                                        onSeek(line.timeMs)
                                    }
                                    .padding(vertical = if (isActive) 10.dp else 5.dp)
                            ) {
                                Text(
                                    text = line.text,
                                    fontSize = if (isActive) 20.sp else 16.sp,
                                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
                                    color = if (isActive) theme.accent else Color.White.copy(alpha = 0.40f),
                                    lineHeight = if (isActive) 26.sp else 22.sp
                                )
                            }
                        }
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}
