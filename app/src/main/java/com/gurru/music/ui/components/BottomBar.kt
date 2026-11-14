package com.gurru.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gurru.music.ui.theme.LocalGurruTheme
import com.gurru.music.ui.theme.gurruPillBrush

/* Las 5 secciones de la barra inferior, igual que en la app actual.
   En Fase 1 solo Biblioteca tiene contenido; las demás van a una
   pantalla "Próximamente" hasta sus fases. */
enum class BottomSection(val label: String, val icon: ImageVector) {
    DOWNLOADS("Descargas", Icons.Default.Download),
    LIBRARY("Biblioteca", Icons.Default.LibraryMusic),
    YOUTUBE("YouTube", Icons.Default.PlayCircleFilled),
    MIXES("Mezclas", Icons.Default.AutoAwesome),
    SETTINGS("Ajustes", Icons.Default.Settings)
}

@Composable
fun BottomBar(
    selected: BottomSection,
    onSelect: (BottomSection) -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalGurruTheme.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .shadow(8.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(gurruPillBrush())
            .border(1.dp, theme.softBlue.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            for (section in BottomSection.entries) {
                val active = section == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(section) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    /* Línea cian arriba de la sección activa, como en
                       la app actual. */
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (active) theme.accent else Color.Transparent)
                    )
                    Icon(
                        imageVector = section.icon,
                        contentDescription = section.label,
                        tint = when {
                            section == BottomSection.YOUTUBE -> Color(0xFFFF4D6D)
                            active -> theme.accent
                            else -> theme.soft.copy(alpha = 0.75f)
                        },
                        modifier = Modifier
                            .size(24.dp)
                            .padding(top = 7.dp)
                    )
                    Text(
                        text = section.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (active) theme.accent else theme.soft.copy(alpha = 0.75f),
                        maxLines = 1,
                        modifier = Modifier.padding(top = 3.dp, bottom = 10.dp)
                    )
                }
            }
        }
    }
}
