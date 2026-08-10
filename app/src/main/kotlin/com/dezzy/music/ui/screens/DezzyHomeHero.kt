/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Hero de la home: saludo personalizado con racha y minutos del día,
 * tarjeta "Sigue donde ibas" con el canvas animado de la última canción,
 * accesos rápidos y mixes automáticos por género en cuadrícula bento.
 */

package com.dezzy.music.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.db.entities.EventWithSong
import com.dezzy.music.db.entities.Song
import com.dezzy.music.extensions.toMediaItem
import com.dezzy.music.models.toMediaMetadata
import com.dezzy.music.playback.queues.ListQueue
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.ui.component.NavigationTitle
import com.dezzy.music.ui.player.CanvasArtworkPlayer
import com.dezzy.music.ui.player.playbackUrls
import com.dezzy.music.ui.player.rememberPlaybackCanvas
import com.dezzy.music.viewmodels.GenreMix
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun DezzyHomeHero(
    accountName: String,
    streakDays: Int,
    todayMinutes: Int,
    lastEvent: EventWithSong?,
    genreMixes: List<GenreMix>,
    quickPicks: List<Song>?,
    navController: NavController,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        // ── Saludo según la hora + fecha, racha y minutos de hoy ──
        val greeting = remember {
            when (LocalTime.now().hour) {
                in 6..11 -> "Buenos días"
                in 12..19 -> "Buenas tardes"
                else -> "Buenas noches"
            }
        }
        val name = accountName.takeIf { it.isNotBlank() && it != "Guest" }
        val subLine = remember(streakDays, todayMinutes) {
            val locale = Locale.getDefault()
            val today = LocalDate.now()
            buildString {
                append(today.dayOfWeek.getDisplayName(TextStyle.FULL, locale))
                append(" ").append(today.dayOfMonth)
                append(" de ").append(today.month.getDisplayName(TextStyle.FULL, locale))
                if (streakDays >= 2) append(" · racha de $streakDays días")
                if (todayMinutes > 0) append(" · $todayMinutes min hoy")
            }
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                text = if (name != null) "$greeting, $name" else greeting,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── "Sigue donde ibas": última escucha con su canvas animado ──
        val resumeSong = lastEvent?.song?.takeIf { it.id != mediaMetadata?.id }
        if (resumeSong != null) {
            ResumeCard(
                song = resumeSong,
                onClick = {
                    playerConnection.playQueue(YouTubeQueue.radio(resumeSong.toMediaMetadata()))
                },
            )
        }

        // ── Accesos rápidos ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickPill(
                iconRes = R.drawable.shuffle,
                label = "Aleatorio",
                modifier = Modifier.weight(1f),
            ) {
                quickPicks?.takeIf { it.isNotEmpty() }?.let { picks ->
                    playerConnection.playQueue(
                        ListQueue(
                            title = "Aleatorio · Dezzy",
                            items = picks.shuffled().map { it.toMediaItem() },
                        ),
                    )
                }
            }
            QuickPill(
                iconRes = R.drawable.favorite,
                label = "Me gusta",
                modifier = Modifier.weight(1f),
            ) { navController.navigate("auto_playlist/liked") }
            QuickPill(
                iconRes = R.drawable.download,
                label = "Descargas",
                modifier = Modifier.weight(1f),
            ) { navController.navigate("auto_playlist/downloaded") }
        }

        // ── Mixes por género en bento ──
        if (genreMixes.isNotEmpty()) {
            NavigationTitle(title = "Mixes para ti")
            val playMix: (GenreMix) -> Unit = { mix ->
                playerConnection.playQueue(
                    ListQueue(
                        title = "Mix ${mix.name} · Dezzy",
                        items = mix.songs.shuffled().map { it.toMediaItem() },
                    ),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(196.dp)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BigMixCard(
                    mix = genreMixes[0],
                    modifier = Modifier
                        .weight(1.25f)
                        .fillMaxSize(),
                    onClick = { playMix(genreMixes[0]) },
                )
                if (genreMixes.size > 1) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        genreMixes.drop(1).take(2).forEach { mix ->
                            SmallMixCard(
                                mix = mix,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                onClick = { playMix(mix) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Tarjeta de reanudar con canvas de fondo ──────────────────────────────────

@Composable
private fun ResumeCard(
    song: Song,
    onClick: () -> Unit,
) {
    val canvas = rememberPlaybackCanvas(
        mediaId = song.id,
        title = song.song.title,
        artist = song.artists.firstOrNull()?.name,
        albumTitle = song.album?.title,
        durationMs = song.song.duration * 1000L,
    )
    val urls = canvas?.playbackUrls()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(150.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() },
    ) {
        // Base: carátula a todo lo ancho; el canvas (si existe) se dibuja encima.
        AsyncImage(
            model = song.song.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (urls != null && (urls.first != null || urls.second != null)) {
            CanvasArtworkPlayer(
                primaryUrl = urls.first,
                fallbackUrl = urls.second,
                isPlaying = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.10f),
                            Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SIGUE DONDE IBAS",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                )
                Text(
                    text = song.song.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artists.joinToString { it.name },
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Surface(
                shape = CircleShape,
                color = Color.White,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = "Reanudar",
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

// ── Accesos rápidos ──────────────────────────────────────────────────────────

@Composable
private fun QuickPill(
    iconRes: Int,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = modifier.height(40.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

// ── Tarjetas bento de mixes por género ───────────────────────────────────────

@Composable
private fun BigMixCard(
    mix: GenreMix,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() },
    ) {
        // Collage 2×2 con las carátulas más escuchadas del género
        val covers = mix.songs.take(4).map { it.song.thumbnailUrl }
        Column(modifier = Modifier.fillMaxSize()) {
            repeat(2) { row ->
                Row(modifier = Modifier.weight(1f)) {
                    repeat(2) { col ->
                        AsyncImage(
                            model = covers.getOrNull(row * 2 + col),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize(),
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                        startY = 120f,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp),
        ) {
            Text(
                text = "Mix ${mix.name}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${mix.songs.size} canciones · tu género top",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SmallMixCard(
    mix: GenreMix,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() },
    ) {
        AsyncImage(
            model = mix.songs.firstOrNull()?.song?.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
        ) {
            Text(
                text = "Mix ${mix.name}",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${mix.songs.size} canciones",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp,
            )
        }
    }
}
