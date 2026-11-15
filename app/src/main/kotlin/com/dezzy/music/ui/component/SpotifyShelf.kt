/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.component

import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dezzy.music.LocalDatabase
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.spotify.SpotifyFeatures
import com.dezzy.music.spotify.SpotifyMapper
import com.dezzy.music.spotify.SpotifyTrack
import kotlinx.coroutines.launch

/**
 * Reproduce canciones de Spotify: busca su equivalente en YouTube Music y
 * arranca una radio a partir de él. [loadingId] es la canción que se está
 * resolviendo (para mostrar un indicador en su tarjeta).
 */
class SpotifyTrackPlayer internal constructor(
    private val loadingIdState: androidx.compose.runtime.MutableState<String?>,
    private val onPlay: (SpotifyTrack) -> Unit,
) {
    val loadingId: String? get() = loadingIdState.value
    fun play(track: SpotifyTrack) = onPlay(track)
}

@Composable
fun rememberSpotifyTrackPlayer(): SpotifyTrackPlayer {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val loadingId = remember { mutableStateOf<String?>(null) }
    return remember(playerConnection) {
        SpotifyTrackPlayer(loadingId) { track ->
            if (loadingId.value != null) return@SpotifyTrackPlayer
            loadingId.value = track.id
            scope.launch {
                val metadata = SpotifyFeatures.resolve(context, database, track)
                loadingId.value = null
                if (metadata != null && playerConnection != null) {
                    playerConnection.playQueue(YouTubeQueue.radio(metadata))
                } else {
                    Toast.makeText(
                        context,
                        "No se encontró \"${track.name}\" en YouTube Music",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
}

private fun SpotifyTrack.artistNames() = artists.joinToString { it.name }

/** Título + fila horizontal de portadas. No dibuja nada si [tracks] está vacía. */
@Composable
fun SpotifyTrackShelf(
    title: String,
    tracks: List<SpotifyTrack>,
    player: SpotifyTrackPlayer,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    if (tracks.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        NavigationTitle(title = title, label = "Spotify", subtitle = subtitle)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(tracks, key = { it.id }) { track ->
                SpotifyTrackCard(
                    track = track,
                    loading = player.loadingId == track.id,
                    onClick = { player.play(track) },
                )
            }
        }
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun SpotifyTrackCard(
    track: SpotifyTrack,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(bottom = 6.dp),
    ) {
        SpotifyCover(track, loading, Modifier.size(140.dp), RoundedCornerShape(12.dp))
        Spacer(Modifier.size(6.dp))
        Text(
            text = track.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = track.artistNames(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Fila vertical (para resultados de búsqueda). */
@Composable
fun SpotifyTrackRow(
    track: SpotifyTrack,
    player: SpotifyTrackPlayer,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable { player.play(track) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SpotifyCover(track, player.loadingId == track.id, Modifier.size(48.dp), RoundedCornerShape(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = (if (track.explicit) "E · " else "") + track.artistNames() +
                    (track.album?.name?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SpotifyCover(
    track: SpotifyTrack,
    loading: Boolean,
    modifier: Modifier,
    shape: RoundedCornerShape,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            model = SpotifyMapper.getTrackThumbnail(track),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (loading) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
