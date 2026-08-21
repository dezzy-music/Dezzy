/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dezzy.music.LocalDatabase
import com.dezzy.music.R
import com.dezzy.music.canvas.models.CanvasArtwork
import com.dezzy.music.constants.VideoResizeMode
import com.dezzy.music.constants.VideoResizeModeKey
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.models.toMediaMetadata
import com.dezzy.music.playback.PlayerConnection
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.ui.menu.AddToPlaylistDialog
import com.dezzy.music.ui.screens.CommentSheet
import com.dezzy.music.utils.makeTimeString
import com.dezzy.music.utils.rememberEnumPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-size overlay that shows the standalone video player (see MusicService's isolated
 * `videoPlayer`) when video mode is active, on top of whatever the current player design style
 * renders underneath — this is what lets video mode work with every skin (V4/V5/V7/etc.) without
 * threading a video surface through each one individually. Also owns the floating toggle button
 * (shown bottom-end when video mode is off) for the same reason: one self-contained component
 * instead of wiring a button into every style's own action row.
 */
@Composable
fun VideoModeOverlay(
    playerConnection: PlayerConnection,
    modifier: Modifier = Modifier,
    /** False on styles that surface their own toggle (the V1–V4 Canción/Video pill). */
    showFloatingToggle: Boolean = true,
    /** Baja el reproductor a mini-player (la flecha ⌄ de arriba a la izquierda). */
    onCollapse: () -> Unit = {},
    /** Abre la cola (el asa inferior con el nombre de la lista). */
    onQueueClick: () -> Unit = {},
) {
    val videoModeActive by playerConnection.videoModeActive.collectAsState()
    val videoModeLoading by playerConnection.videoModeLoading.collectAsState()
    val videoPlayer by playerConnection.videoPlayerInstance.collectAsState()
    val videoModeError by playerConnection.videoModeError.collectAsState()

    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = videoModeActive && videoPlayer != null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize(),
        ) {
            val player = videoPlayer
            if (player != null) {
                VideoModeScreen(
                    playerConnection = playerConnection,
                    videoPlayer = player,
                    onCollapse = onCollapse,
                    onQueueClick = onQueueClick,
                )
            }
        }

        if (!videoModeActive && showFloatingToggle) {
            Surface(
                onClick = { playerConnection.enableVideoMode() },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    if (videoModeLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.slow_motion_video),
                            contentDescription = "Ver video musical",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        videoModeError?.let { message ->
            VideoModeErrorSnack(message = message, onDismiss = { playerConnection.service.videoModeError.value = null })
        }
    }
}

/**
 * Pantalla de video al estilo YouTube Music: barra superior (bajar / selector
 * audio-video / cast / menú), el video en 16:9 arriba, y abajo el bloque de
 * información — título, artista, acciones (me gusta, comentarios, guardar),
 * barra de progreso, controles de transporte y el asa de la cola.
 *
 * Los controles se dibujan aquí en Compose en vez de usar el controlador propio
 * de `PlayerView` (que era el diseño anterior): el de media3 solo sabe de este
 * reproductor aislado, así que no podía ofrecer anterior/siguiente de la cola,
 * aleatorio, repetir ni "me gusta". Play/pausa y la barra actúan sobre el
 * reproductor de VIDEO; el resto, sobre la cola real de la app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoModeScreen(
    playerConnection: PlayerConnection,
    videoPlayer: androidx.media3.exoplayer.ExoPlayer,
    onCollapse: () -> Unit,
    onQueueClick: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()

    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val queueTitle by playerConnection.queueTitle.collectAsState()
    val liked by playerConnection.currentSongLiked.collectAsState()
    val shuffleEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val videoModeLoading by playerConnection.videoModeLoading.collectAsState()
    val resizeModePref by rememberEnumPreference(VideoResizeModeKey, VideoResizeMode.FIT)

    // El reproductor de video es independiente del de la app: su posición y su
    // estado de reproducción hay que leerlos de él directamente.
    var isVideoPlaying by remember(videoPlayer) { mutableStateOf(videoPlayer.playWhenReady) }
    var position by remember(videoPlayer) { mutableLongStateOf(videoPlayer.currentPosition) }
    var duration by remember(videoPlayer) { mutableLongStateOf(videoPlayer.duration.coerceAtLeast(0L)) }
    var sliderPosition by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(videoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isVideoPlaying = playing }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                isVideoPlaying = playWhenReady
            }
        }
        videoPlayer.addListener(listener)
        onDispose { videoPlayer.removeListener(listener) }
    }

    LaunchedEffect(videoPlayer) {
        while (true) {
            position = videoPlayer.currentPosition
            duration = videoPlayer.duration.takeIf { it > 0 } ?: 0L
            delay(500)
        }
    }

    // Al cambiar de canción el servicio apaga el modo video; si el salto lo pidió
    // el usuario desde AQUÍ, se vuelve a encender solo para no echarlo a la
    // pantalla de audio en cada "siguiente".
    var keepVideoOnTrackChange by remember { mutableStateOf(false) }
    val videoModeActive by playerConnection.videoModeActive.collectAsState()
    LaunchedEffect(mediaMetadata?.id, videoModeActive) {
        if (keepVideoOnTrackChange && !videoModeActive && mediaMetadata != null) {
            keepVideoOnTrackChange = false
            playerConnection.enableVideoMode()
        }
    }

    var showComments by remember { mutableStateOf(false) }
    var showRelated by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // ── Barra superior ────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCollapse) {
                    Icon(
                        painter = painterResource(R.drawable.expand_more),
                        contentDescription = "Minimizar",
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.weight(1f))
                AudioVideoIconPill(
                    videoSelected = true,
                    loading = videoModeLoading,
                    onSelectAudio = { playerConnection.disableVideoMode() },
                    onSelectVideo = {},
                )
                Spacer(Modifier.weight(1f))
                com.dezzy.music.ui.component.CastButton(tint = Color.White)
                Box {
                    IconButton(onClick = { showOverflow = true }) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = "Más opciones",
                            tint = Color.White,
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflow,
                        onDismissRequest = { showOverflow = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Videos similares") },
                            onClick = { showOverflow = false; showRelated = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Descargar video") },
                            onClick = {
                                showOverflow = false
                                downloadCurrentVideo(context, videoPlayer, mediaMetadata?.title)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Cerrar video") },
                            onClick = { showOverflow = false; playerConnection.disableVideoMode() },
                        )
                    }
                }
            }

            // ── Video ─────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            // El controlador propio de media3 se apaga: los controles
                            // son los de abajo, como en YouTube Music.
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    update = { view ->
                        view.player = videoPlayer
                        view.resizeMode = when (resizeModePref) {
                            VideoResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            VideoResizeMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            VideoResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Título + artista ──────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onQueueClick() }
                    .padding(start = 20.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = mediaMetadata?.title.orEmpty(),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .basicMarquee(),
                )
                Icon(
                    painter = painterResource(R.drawable.arrow_forward),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = mediaMetadata?.artists?.joinToString(", ") { it.name }.orEmpty(),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )

            Spacer(Modifier.height(14.dp))

            // ── Acciones: me gusta / comentarios / guardar ─────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LikeChip(
                    liked = liked,
                    onLike = { playerConnection.toggleLike() },
                    // "No me gusta" quita el me gusta: la app no guarda un voto
                    // negativo, así que fingirlo sería un botón que no hace nada.
                    onDislike = { if (liked) playerConnection.toggleLike() },
                )
                VideoActionChip(
                    iconRes = R.drawable.chat_bubble,
                    label = "Comentarios",
                    onClick = { showComments = true },
                )
                VideoActionChip(
                    iconRes = R.drawable.playlist_add,
                    label = "Guardar",
                    onClick = { showPlaylistDialog = true },
                )
            }

            Spacer(Modifier.height(18.dp))

            // ── Progreso ──────────────────────────────────────────────────
            val maxDuration = duration.coerceAtLeast(1L).toFloat()
            val currentPos = (sliderPosition ?: position).toFloat().coerceIn(0f, maxDuration)
            Slider(
                value = currentPos,
                valueRange = 0f..maxDuration,
                onValueChange = { sliderPosition = it.toLong() },
                onValueChangeFinished = {
                    sliderPosition?.let { videoPlayer.seekTo(it) }
                    sliderPosition = null
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.28f),
                ),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = makeTimeString((sliderPosition ?: position)),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
                Text(
                    text = makeTimeString(duration),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
            }

            Spacer(Modifier.height(10.dp))

            // ── Transporte ────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    playerConnection.player.shuffleModeEnabled = !shuffleEnabled
                }) {
                    Icon(
                        painter = painterResource(if (shuffleEnabled) R.drawable.shuffle_on else R.drawable.shuffle),
                        contentDescription = "Aleatorio",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(onClick = {
                    keepVideoOnTrackChange = true
                    playerConnection.seekToPrevious()
                }) {
                    Icon(
                        painter = painterResource(R.drawable.skip_previous),
                        contentDescription = "Anterior",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable {
                            videoPlayer.playWhenReady = !videoPlayer.playWhenReady
                            isVideoPlaying = videoPlayer.playWhenReady
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(if (isVideoPlaying) R.drawable.pause else R.drawable.play),
                        contentDescription = if (isVideoPlaying) "Pausar" else "Reproducir",
                        tint = Color.Black,
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = {
                    keepVideoOnTrackChange = true
                    playerConnection.seekToNext()
                }) {
                    Icon(
                        painter = painterResource(R.drawable.skip_next),
                        contentDescription = "Siguiente",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = {
                    playerConnection.player.repeatMode = when (repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }) {
                    Icon(
                        painter = painterResource(
                            when (repeatMode) {
                                Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                                Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
                                else -> R.drawable.repeat
                            }
                        ),
                        contentDescription = "Repetir",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Asa de la cola ────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onQueueClick() }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.45f)),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = queueTitle.orEmpty(),
                    color = Color.White,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    // ── Hojas y diálogos ─────────────────────────────────────────────────
    mediaMetadata?.let { metadata ->
        AddToPlaylistDialog(
            isVisible = showPlaylistDialog,
            onGetSong = { playlist ->
                database.transaction { insert(metadata) }
                coroutineScope.launch(Dispatchers.IO) {
                    playlist.playlist.browseId?.let { YouTube.addToPlaylist(it, metadata.id) }
                }
                listOf(metadata.id)
            },
            onDismiss = { showPlaylistDialog = false },
        )
    }

    if (showComments) {
        val videoId = mediaMetadata?.id
        ModalBottomSheet(
            onDismissRequest = { showComments = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            if (videoId != null) {
                CommentSheet(videoId = videoId, onDismiss = { showComments = false })
            }
        }
    }

    if (showRelated) {
        RelatedVideosSheet(
            playerConnection = playerConnection,
            currentVideoId = mediaMetadata?.id,
            onDismiss = { showRelated = false },
        )
    }
}

/** Píldora audio/video de la barra superior (auriculares ↔ video). */
@Composable
private fun AudioVideoIconPill(
    videoSelected: Boolean,
    loading: Boolean,
    onSelectAudio: () -> Unit,
    onSelectVideo: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.14f),
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillSegment(
                selected = !videoSelected,
                iconRes = R.drawable.headphones,
                contentDescription = "Solo audio",
                onClick = onSelectAudio,
            )
            if (loading) {
                Box(
                    modifier = Modifier.size(46.dp, 34.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                }
            } else {
                PillSegment(
                    selected = videoSelected,
                    iconRes = R.drawable.play,
                    contentDescription = "Video",
                    onClick = onSelectVideo,
                )
            }
        }
    }
}

@Composable
private fun PillSegment(
    selected: Boolean,
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) Color.White.copy(alpha = 0.28f) else Color.Transparent,
    ) {
        Box(
            modifier = Modifier.size(46.dp, 34.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Píldora "me gusta / no me gusta", como la de YouTube Music. */
@Composable
private fun LikeChip(
    liked: Boolean,
    onLike: () -> Unit,
    onDislike: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.12f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onLike)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.thumbup),
                    contentDescription = "Me gusta",
                    tint = if (liked) Color.White else Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(22.dp),
                )
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(22.dp)
                    .background(Color.White.copy(alpha = 0.22f)),
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = liked, onClick = onDislike)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .alpha(if (liked) 1f else 0.45f),
            ) {
                Icon(
                    painter = painterResource(R.drawable.thumbdown),
                    contentDescription = "No me gusta",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun VideoActionChip(iconRes: Int, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = label, color = Color.White, fontSize = 14.sp)
        }
    }
}

/**
 * YouTube-Music-style "Canción / Video" segmented pill shown above the artwork on the four
 * classic player styles (V1–V4). Tapping "Video" switches to the music-video stream via the
 * same video-mode pipeline the floating toggle uses; "Canción" returns to audio.
 */
@Composable
fun SongVideoToggle(
    playerConnection: PlayerConnection,
    modifier: Modifier = Modifier,
    /**
     * Canvas de la canción actual (Spotify/Apple), si existe. Sin uso desde que
     * se revirtió el selector video-vs-canvas (dejaba pantalla negra en algunos
     * dispositivos); se conserva por compatibilidad con los call sites.
     */
    canvas: CanvasArtwork? = null,
) {
    val videoModeActive by playerConnection.videoModeActive.collectAsState()
    val videoModeLoading by playerConnection.videoModeLoading.collectAsState()
    val songSelected = !videoModeActive

    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.45f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = { if (!songSelected) playerConnection.disableVideoMode() },
                shape = CircleShape,
                color = if (songSelected) Color.White else Color.Transparent,
            ) {
                Text(
                    text = "Canción",
                    color = if (songSelected) Color.Black else Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                )
            }
            Surface(
                onClick = { if (!videoModeLoading && songSelected) playerConnection.enableVideoMode() },
                shape = CircleShape,
                color = if (!songSelected) Color.White else Color.Transparent,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                ) {
                    if (videoModeLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Text(
                            text = "Video",
                            color = if (!songSelected) Color.Black else Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Videos similares (InnerTube next/related) en una hoja, en vez del panel fijo
 * que antes tapaba el video. Tocar uno salta la cola a esa canción y deja pedida
 * la reactivación del modo video.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelatedVideosSheet(
    playerConnection: PlayerConnection,
    currentVideoId: String?,
    onDismiss: () -> Unit,
) {
    var related by remember(currentVideoId) { mutableStateOf<List<SongItem>>(emptyList()) }
    var loading by remember(currentVideoId) { mutableStateOf(true) }

    LaunchedEffect(currentVideoId) {
        val id = currentVideoId
        if (id == null) { loading = false; return@LaunchedEffect }
        related = withContext(Dispatchers.IO) {
            runCatching {
                YouTube.next(WatchEndpoint(videoId = id))
                    .getOrNull()
                    ?.items
                    .orEmpty()
                    .filter { it.id != id }
                    .distinctBy { it.id }
                    .take(20)
            }.getOrDefault(emptyList())
        }
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = "Videos similares",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
            )
            when {
                loading -> Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                related.isEmpty() -> Text(
                    text = "No hay videos similares para esta canción.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )

                else -> LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                ) {
                    items(related, key = { it.id }) { item ->
                        Column(
                            modifier = Modifier
                                .width(150.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    onDismiss()
                                    playRelatedVideo(playerConnection, item)
                                },
                        ) {
                            coil3.compose.AsyncImage(
                                model = item.thumbnail,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(84.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                            Text(
                                text = item.title,
                                fontSize = 12.sp,
                                lineHeight = 15.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Descarga el stream de video actual a Películas/Dezzy con el DownloadManager. */
private fun downloadCurrentVideo(
    context: android.content.Context,
    videoPlayer: androidx.media3.exoplayer.ExoPlayer,
    title: String?,
) {
    val uri = videoPlayer.currentMediaItem?.localConfiguration?.uri
    if (uri == null || uri.scheme?.startsWith("http") != true) {
        android.widget.Toast.makeText(context, "Video aún no disponible", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val safeName = (title ?: "video")
        .replace(Regex("[^\\w\\s-]"), "").trim().ifBlank { "video" }
    runCatching {
        val dm = context.getSystemService(android.app.DownloadManager::class.java)
        val request = android.app.DownloadManager.Request(uri)
            .setTitle(safeName)
            .setMimeType("video/mp4")
            .setNotificationVisibility(
                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationInExternalPublicDir(
                android.os.Environment.DIRECTORY_MOVIES,
                "Dezzy/$safeName.mp4",
            )
        dm.enqueue(request)
    }.onSuccess {
        android.widget.Toast.makeText(context, "Descargando video en Películas/Dezzy…", android.widget.Toast.LENGTH_SHORT).show()
    }.onFailure {
        android.widget.Toast.makeText(context, "No se pudo iniciar la descarga", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Salta la cola a [item] (radio) y deja pedida la re-activación del modo video. */
private fun playRelatedVideo(
    playerConnection: PlayerConnection,
    item: SongItem,
) {
    playerConnection.service.pendingVideoModeMediaId = item.id
    playerConnection.playQueue(
        YouTubeQueue.radio(item.toMediaMetadata()),
    )
}

@Composable
private fun VideoModeErrorSnack(message: String, onDismiss: () -> Unit) {
    LaunchedEffect(message) {
        delay(3000)
        onDismiss()
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp),
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
