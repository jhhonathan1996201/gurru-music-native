package com.gurru.music.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.gurru.music.ui.theme.GurruAccent
import com.gurru.music.ui.theme.GurruBackground
import com.gurru.music.ui.theme.GurruRowBackground
import com.gurru.music.ui.theme.GurruSoft

/* Reproductor: portada grande, título, artista, álbum, barra de
   progreso con seek, controles (anterior, play/pausa, siguiente,
   aleatorio, repetir) y la cola "A continuación" con la canción que
   suena marcada; tocar una de la cola salta a ella. */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val track by viewModel.currentTrack.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val shuffle by viewModel.shuffle.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()

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
                .padding(top = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = track?.artUri ?: R.drawable.logo,
                contentDescription = "Portada",
                contentScale = ContentScale.Crop,
                placeholder = painterResource(R.drawable.logo),
                error = painterResource(R.drawable.logo),
                modifier = Modifier
                    .then(
                        if (queueExpanded) Modifier.size(260.dp)
                        else Modifier.fillMaxWidth().aspectRatio(1f)
                    )
                    .clip(RoundedCornerShape(12.dp))
                    .background(GurruRowBackground)
                    .shadow(12.dp, RoundedCornerShape(12.dp))
            )
        }

        Text(
            text = track?.artist ?: "Sin música todavía",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = GurruSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
        )
        Text(
            text = track?.title ?: "Elige una canción",
            fontSize = 20.sp,
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
            color = GurruSoft,
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
            Text(text = formatDuration(positionMs), fontSize = 12.sp, color = GurruSoft)
            Spacer(modifier = Modifier.weight(1f))
            Text(text = formatDuration(durationMs), fontSize = 12.sp, color = GurruSoft)
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
                thumbColor = GurruAccent,
                activeTrackColor = GurruAccent,
                inactiveTrackColor = GurruSoft.copy(alpha = 0.3f)
            ),
            modifier = Modifier.fillMaxWidth()
        )

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
                    tint = if (shuffle) GurruAccent else Color.White,
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
                    .background(GurruAccent, CircleShape)
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
                        tint = if (repeatMode == RepeatMode.OFF) Color.White else GurruAccent,
                        modifier = Modifier.size(26.dp)
                    )
                }
                if (repeatMode == RepeatMode.ONE) {
                    Text(
                        text = "1",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = GurruAccent,
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
                color = GurruSoft
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (queueExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (queueExpanded) "Ocultar cola" else "Mostrar cola",
                tint = GurruSoft,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = number.toString(),
            fontSize = 13.sp,
            color = if (playing) GurruAccent else GurruSoft,
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
                    if (playing) Modifier.border(1.5.dp, GurruAccent, RoundedCornerShape(8.dp))
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
                color = if (playing) GurruAccent else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                fontSize = 12.sp,
                color = GurruSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
