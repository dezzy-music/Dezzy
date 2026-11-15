/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import com.dezzy.music.LocalDatabase
import com.dezzy.music.R
import com.dezzy.music.constants.VideoResizeMode
import com.dezzy.music.constants.VideoResizeModeKey
import com.dezzy.music.extensions.toMediaItem
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.models.toMediaMetadata
import com.dezzy.music.playback.PlayerConnection
import com.dezzy.music.ui.menu.AddToPlaylistDialog
import com.dezzy.music.utils.makeTimeString
import com.dezzy.music.utils.rememberEnumPreference
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private val FeedBackground = Color(0xFF0C0C0F)

/**
 * "Solo videos": el video fijo arriba y debajo una lista de videos recomendados para la
 * canción actual (radio + búsqueda de videos), con filtros. Al bajar, el video pasa a una
 * ventanita flotante; al llegar al final se cargan más. Tocar un video lo reproduce (radio
 * en modo video) y la lista se recalcula para el nuevo.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VideoFeedScreen(
    playerConnection: PlayerConnection,
    videoPlayer: ExoPlayer?,
    loading: Boolean,
    recommendations: VideoRecommendations,
    filter: VideoRecoFilter,
    artistName: String,
    onFilterChange: (VideoRecoFilter) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onQueueClick: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val resizeModePref by rememberEnumPreference(VideoResizeModeKey, VideoResizeMode.FIT)
    val playback = rememberVideoPlaybackState(videoPlayer)

    val listState = rememberLazyListState()
    var videoHeightPx by remember { mutableIntStateOf(0) }
    var floatingDismissed by remember { mutableStateOf(false) }
    val scrolledPastVideo by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                (videoHeightPx > 0 && listState.firstVisibleItemScrollOffset > videoHeightPx * 0.65f)
        }
    }
    LaunchedEffect(scrolledPastVideo) {
        if (!scrolledPastVideo) floatingDismissed = false
    }
    val floatingShown = scrolledPastVideo && !floatingDismissed

    // Carga más al acercarse al final.
    LaunchedEffect(listState, recommendations) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { recommendations.loadMore() }
    }

    // Al cambiar de video la lista se recalcula: vuelve arriba.
    LaunchedEffect(mediaMetadata?.id) {
        if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0)
    }

    var playlistTarget by remember { mutableStateOf<SongItem?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FeedBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // ── Barra superior ───────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = "Volver al video",
                        tint = Color.White,
                    )
                }
                Text(
                    text = "Solo videos",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                com.dezzy.music.ui.component.CastButton(tint = Color.White)
                IconButton(onClick = onQueueClick) {
                    Icon(
                        painter = painterResource(R.drawable.queue_music),
                        contentDescription = "Cola de reproducción",
                        tint = Color.White,
                    )
                }
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // ── Reproductor ──────────────────────────────────────────
                item(key = "player", contentType = "player") {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .background(Color.Black)
                                .onSizeChanged { videoHeightPx = it.height }
                                .clickable(enabled = videoPlayer != null) {
                                    videoPlayer?.let { it.playWhenReady = !it.playWhenReady }
                                },
                        ) {
                            VideoSurface(
                                player = videoPlayer,
                                active = !floatingShown,
                                resizeMode = resizeModePref.toPlayerViewMode(),
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (loading || playback.buffering) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.align(Alignment.Center).size(36.dp),
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = mediaMetadata?.title.orEmpty(),
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = mediaMetadata?.artists?.joinToString(", ") { it.name }.orEmpty(),
                                    color = Color.White.copy(alpha = 0.62f),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { videoPlayer?.let { it.playWhenReady = !it.playWhenReady } },
                                enabled = videoPlayer != null,
                            ) {
                                Icon(
                                    painter = painterResource(if (playback.isPlaying) R.drawable.pause else R.drawable.play),
                                    contentDescription = if (playback.isPlaying) "Pausar" else "Reproducir",
                                    tint = Color.White,
                                )
                            }
                            IconButton(onClick = { playerConnection.seekToNext() }) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_next),
                                    contentDescription = "Siguiente",
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                }

                // ── Filtros ──────────────────────────────────────────────
                stickyHeader(key = "filters", contentType = "filters") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(FeedBackground)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        VideoRecoFilter.entries.forEach { option ->
                            FeedFilterChip(
                                label = if (option == VideoRecoFilter.ARTIST && artistName.isNotBlank()) artistName else option.label,
                                selected = option == filter,
                                onClick = { onFilterChange(option) },
                            )
                        }
                    }
                }

                // ── Lista ────────────────────────────────────────────────
                when {
                    recommendations.loading -> items(3, key = { "placeholder$it" }, contentType = { "placeholder" }) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(alpha = 0.08f)),
                            )
                            Box(
                                Modifier
                                    .padding(top = 12.dp)
                                    .fillMaxWidth(0.7f)
                                    .height(14.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.08f)),
                            )
                        }
                    }

                    recommendations.items.isEmpty() -> item(key = "empty", contentType = "empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = if (recommendations.failed) "No se pudieron cargar los videos" else "No hay videos para este filtro",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (recommendations.failed) "Revisa la conexión e inténtalo de nuevo." else "Prueba con otro filtro.",
                                color = Color.White.copy(alpha = 0.62f),
                                fontSize = 14.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            if (recommendations.failed) {
                                TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                                    Text("Reintentar", color = Color.White)
                                }
                            }
                        }
                    }

                    else -> items(recommendations.items, key = { it.id }, contentType = { "video" }) { item ->
                        VideoFeedCard(
                            item = item,
                            onClick = {
                                playRelatedVideo(playerConnection, item)
                                scope.launch { listState.animateScrollToItem(0) }
                            },
                            onPlayNext = {
                                playerConnection.playNext(item.toMediaItem())
                                android.widget.Toast.makeText(context, "Se reproducirá a continuación", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            onAddToQueue = {
                                playerConnection.addToQueue(item.toMediaItem())
                                android.widget.Toast.makeText(context, "Añadido a la cola", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            onSave = { playlistTarget = item },
                        )
                    }
                }

                if (recommendations.loadingMore) {
                    item(key = "more", contentType = "more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }

        // ── Ventanita flotante ───────────────────────────────────────────
        AnimatedVisibility(
            visible = floatingShown,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 176.dp, height = 99.dp)
                    .shadow(10.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .clickable { scope.launch { listState.animateScrollToItem(0) } },
            ) {
                VideoSurface(
                    player = videoPlayer,
                    active = floatingShown,
                    resizeMode = resizeModePref.toPlayerViewMode(),
                    modifier = Modifier.fillMaxSize(),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp),
                ) {
                    FloatingWindowButton(
                        iconRes = if (playback.isPlaying) R.drawable.pause else R.drawable.play,
                        contentDescription = if (playback.isPlaying) "Pausar" else "Reproducir",
                        onClick = { videoPlayer?.let { it.playWhenReady = !it.playWhenReady } },
                    )
                    FloatingWindowButton(
                        iconRes = R.drawable.close,
                        contentDescription = "Cerrar ventana",
                        onClick = {
                            videoPlayer?.playWhenReady = false
                            floatingDismissed = true
                        },
                    )
                }
            }
        }
    }

    playlistTarget?.let { target ->
        AddToPlaylistDialog(
            isVisible = true,
            onGetSong = {
                val metadata = target.toMediaMetadata()
                database.transaction { insert(metadata) }
                listOf(metadata.id)
            },
            onDismiss = { playlistTarget = null },
        )
    }
}

@Composable
private fun FeedFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.12f),
        modifier = Modifier.heightIn(min = 36.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = label,
                color = if (selected) Color.Black else Color.White,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FloatingWindowButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Tarjeta grande 16:9 de la lista de "Solo videos". */
@Composable
private fun VideoFeedCard(
    item: SongItem,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onSave: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val artist = item.artists.joinToString(", ") { it.name }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        VideoThumbnail(item = item, corner = 14.dp)
        Row(
            modifier = Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            val initial = (artist.firstOrNull() ?: item.title.firstOrNull() ?: '♪').uppercaseChar()
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(avatarColor(artist.ifBlank { item.title })),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = initial.toString(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val duration = item.duration?.takeIf { it > 0 }?.let { makeTimeString(it * 1000L) }
                Text(
                    text = listOfNotNull(artist.takeIf { it.isNotBlank() }, duration).joinToString(" · "),
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert),
                        contentDescription = "Opciones del video",
                        tint = Color.White,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Reproducir siguiente") },
                        leadingIcon = { Icon(painterResource(R.drawable.skip_next), null) },
                        onClick = { menuOpen = false; onPlayNext() },
                    )
                    DropdownMenuItem(
                        text = { Text("Añadir a la cola") },
                        leadingIcon = { Icon(painterResource(R.drawable.queue_music), null) },
                        onClick = { menuOpen = false; onAddToQueue() },
                    )
                    DropdownMenuItem(
                        text = { Text("Guardar en playlist") },
                        leadingIcon = { Icon(painterResource(R.drawable.playlist_add), null) },
                        onClick = { menuOpen = false; onSave() },
                    )
                }
            }
        }
    }
}

private val AvatarPalette = listOf(
    Color(0xFF7C4DFF), Color(0xFF00897B), Color(0xFFD81B60), Color(0xFF3949AB),
    Color(0xFFF4511E), Color(0xFF6D4C41), Color(0xFF00838F), Color(0xFF8E24AA),
)

private fun avatarColor(seed: String): Color = AvatarPalette[(seed.hashCode() and 0x7fffffff) % AvatarPalette.size]
