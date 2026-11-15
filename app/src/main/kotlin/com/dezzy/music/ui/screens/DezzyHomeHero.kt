/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Hero de la home (Boceto A, fase 1): tarjeta "Sigue donde ibas" con la última
 * escucha (y su canvas animado sobre la carátula) y carrusel "Tus mixes" con los
 * mixes automáticos por género.
 */

package com.dezzy.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.dezzy.music.ui.player.CanvasArtworkPlayer
import com.dezzy.music.ui.player.playbackUrls
import com.dezzy.music.ui.player.rememberPlaybackCanvas
import com.dezzy.music.viewmodels.GenreMix

/**
 * "Sigue donde ibas": la última canción escuchada (si no es la que ya suena).
 * Los parámetros de saludo y mixes se conservan por compatibilidad; el saludo
 * vive ahora en [DezzyHomeTopBar] y los mixes en [DezzyMixesCarousel].
 */
@Suppress("UNUSED_PARAMETER")
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

    val event = lastEvent ?: return
    val resumeSong = event.song.takeIf { it.id != mediaMetadata?.id } ?: return
    ResumeCard(
        song = resumeSong,
        listenedMs = event.event.playTime,
        onClick = {
            playerConnection.playQueue(YouTubeQueue.radio(resumeSong.toMediaMetadata()))
        },
    )
}

// ── Tarjeta de reanudar ─────────────────────────────────────────────────────

@Composable
private fun ResumeCard(
    song: Song,
    listenedMs: Long,
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
    val durationMs = song.song.duration * 1000L
    val progress = if (durationMs > 0) (listenedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    ElevatedCard(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Sigue donde ibas",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Carátula con el canvas animado encima (si la canción tiene uno).
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
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
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.song.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = song.artists.joinToString { it.name },
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (progress > 0f) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (progress > 0f) {
                        "Escuchaste ${formatClock(listenedMs)} de ${formatClock(durationMs)}"
                    } else {
                        "Tu última escucha"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = onClick) {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Reanudar")
                }
            }
        }
    }
}

private fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

// ── Carrusel "Tus mixes" ────────────────────────────────────────────────────

/** Mixes automáticos por género en un carrusel horizontal de mosaicos 140 dp. */
@Composable
fun DezzyMixesCarousel(
    genreMixes: List<GenreMix>,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    if (genreMixes.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        HomeSectionHeader(title = "Tus mixes")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(genreMixes, key = { it.key }) { mix ->
                MixTile(
                    mix = mix,
                    onClick = {
                        playerConnection.playQueue(
                            ListQueue(
                                title = "Mix ${mix.name} · Dezzy",
                                items = mix.songs.shuffled().map { it.toMediaItem() },
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun MixTile(
    mix: GenreMix,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        // Collage 2×2 con las carátulas más escuchadas del género
        val covers = mix.songs.take(4).map { it.song.thumbnailUrl }
        Column(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            repeat(2) { row ->
                Row(modifier = Modifier.weight(1f)) {
                    repeat(2) { col ->
                        AsyncImage(
                            model = covers.getOrNull(row * 2 + col) ?: covers.firstOrNull(),
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
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Mix ${mix.name}",
            style = MaterialTheme.typography.titleSmall,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${mix.songs.size} canciones",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
    }
}
