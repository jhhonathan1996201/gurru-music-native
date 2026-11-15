package com.gurru.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gurru.music.R
import com.gurru.music.data.Mix
import com.gurru.music.data.PlayCountRepository
import com.gurru.music.data.Shortcut
import com.gurru.music.data.buildMixes
import com.gurru.music.data.buildShortcuts
import com.gurru.music.player.PlayerViewModel
import com.gurru.music.ui.theme.GurruBackground
import com.gurru.music.ui.theme.GurruRowBackground
import com.gurru.music.ui.theme.LocalGurruTheme

/* "Mezclas para ti": las mezclas se arman con tu biblioteca local y el
   contador de reproducciones (ver data/Mixes.kt). Tocar una mezcla o un
   acceso directo suena su cola en el reproductor. */
@Composable
fun MixesScreen(
    viewModel: PlayerViewModel,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalGurruTheme.current
    val context = LocalContext.current
    val tracks by viewModel.tracks.collectAsState()
    val playCounts = remember { PlayCountRepository(context) }
    val counts by playCounts.counts.collectAsState(initial = emptyMap())

    val mixes = remember(tracks, counts) { buildMixes(tracks, counts) }
    val shortcuts = remember(tracks, counts) { buildShortcuts(tracks, counts) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(GurruBackground)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                Text(
                    text = "Mezclas para ti",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                if (mixes.isNotEmpty()) {
                    Text(
                        text = "${mixes.size} ${if (mixes.size == 1) "mezcla" else "mezclas"}",
                        color = theme.soft,
                        fontSize = 14.sp
                    )
                }
            }
        }
        if (mixes.isEmpty()) {
            item {
                Text(
                    text = "Escucha algunas canciones y aquí aparecerán tus mezclas: lo más de tus artistas, tus álbumes y una mezcla sorpresa.",
                    color = theme.soft,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            if (shortcuts.isNotEmpty()) {
                item {
                    Text(
                        text = "Accesos directos",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(shortcuts) { shortcut ->
                            ShortcutCard(
                                shortcut = shortcut,
                                onClick = {
                                    viewModel.playQueue(shortcut.tracks, 0)
                                    onOpenPlayer()
                                }
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    text = "Mezclas",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            items(mixes.chunked(2)) { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    for (mix in row) {
                        MixCard(
                            mix = mix,
                            onClick = {
                                viewModel.playQueue(mix.tracks, 0)
                                onOpenPlayer()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun MixCard(mix: Mix, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalGurruTheme.current
    val cover = mix.tracks.firstOrNull { it.artUri != null }?.artUri
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(GurruRowBackground)
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        AsyncImage(
            model = cover ?: R.drawable.logo,
            contentDescription = mix.name,
            placeholder = painterResource(R.drawable.logo),
            error = painterResource(R.drawable.logo),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = mix.name,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = mix.desc,
            color = theme.soft,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ShortcutCard(shortcut: Shortcut, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(88.dp)
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = shortcut.artUri ?: R.drawable.logo,
            contentDescription = shortcut.name,
            placeholder = painterResource(R.drawable.logo),
            error = painterResource(R.drawable.logo),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = shortcut.name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
