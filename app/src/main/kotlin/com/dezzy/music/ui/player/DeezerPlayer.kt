package com.dezzy.music.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.media3.common.Player
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.extensions.metadata
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.extensions.toggleRepeatMode
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.ui.component.BottomSheetState
import com.dezzy.music.ui.component.CastButton
import com.dezzy.music.ui.component.ConnectDeviceBottomSheet
import com.dezzy.music.ui.component.Lyrics
import com.dezzy.music.ui.component.PlayerSliderTrack
import com.dezzy.music.ui.component.ShareBottomSheet
import com.dezzy.music.constants.SliderStyle
import com.dezzy.music.constants.SliderStyleKey
import com.dezzy.music.lyrics.LyricsTranslationHelper
import com.dezzy.music.utils.makeTimeString
import com.dezzy.music.utils.rememberEnumPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deezer-style "Now Playing", faithful to the real app:
 *
 *  - FLAT pastel background: the cover's hue clamped to a light, soft band (like the real
 *    Deezer app — always bright, always black content on top). By design it is NOT wired to
 *    [PlayerBackgroundStyleKey] — the background cannot be personalized.
 *  - Cover carousel: the previous/next queue items peek at both edges; swiping changes track.
 *  - "HQ" badge + white "Letras" pill overlaid on the artwork.
 *  - share / add / heart row, times above a thin slider, centered title + "Artist - Album".
 *  - Controls: repeat · previous · plain play/pause · next · shuffle.
 *  - Bottom: output device (speaker) · "Iniciar mix de la canción" pill · queue.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DeezerPlayer(
    state: BottomSheetState,
    navController: NavController,
    positionState: State<Long>,
    durationState: State<Long>,
    sliderPosition: Long?,
    onSliderPositionChange: (Long?) -> Unit,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,

    onSleepTimerClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    onVisualizerClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)
    val queueTitle by playerConnection.queueTitle.collectAsState()
    val queueWindows by playerConnection.queueWindows.collectAsState()
    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsState()
    // Optimista: ver PlayerConnection.currentSongLiked.
    val currentSongLiked by playerConnection.currentSongLiked.collectAsState()
    val lyricsEntity by playerConnection.currentLyrics.collectAsState(initial = null)

    val position by positionState
    val duration by durationState
    val currentPos = sliderPosition ?: position

    val context = LocalContext.current
    var showShareSheet by remember { mutableStateOf(false) }
    var showDevicePicker by remember { mutableStateOf(false) }

    // Bloquear orientación horizontal para este reproductor DeezerPlayer (forzar modo vertical/portrait)
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        val originalOrientation = activity?.requestedOrientation ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = originalOrientation
        }
    }

    // Global slider style + canvas support (shared pipeline with DezzyPlayer/Thumbnail).
    val sliderStyle by rememberEnumPreference(SliderStyleKey, SliderStyle.Standard)
    val canvasArtwork = rememberPlaybackCanvas(
        mediaId = mediaMetadata?.id,
        title = mediaMetadata?.title,
        artist = mediaMetadata?.artists?.firstOrNull()?.name,
        albumTitle = mediaMetadata?.album?.title,
        durationMs = duration,
    )

    // ---- Fondo plano fijo: tinte pastel claro derivado de la portada ----
    // Deezer no pinta el color dominante tal cual: conserva el matiz (hue) de la
    // portada pero lo lleva a una banda clara y suave (pastel), de modo que el
    // fondo siempre queda luminoso y el contenido negro es legible sobre
    // cualquier carátula. Clampeamos en HSL; las portadas grises/monocromas
    // quedan neutras en vez de recibir un tinte inventado.
    val defaultBg = Color(0xFFE9E7EE)
    var rawBg by remember { mutableStateOf(defaultBg) }
    LaunchedEffect(mediaMetadata?.id) {
        val url = mediaMetadata?.thumbnailUrl ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(120, 120)
                    .allowHardware(false)
                    .build()
                context.imageLoader.execute(request).image?.toBitmap()
            }.getOrNull()
        } ?: return@LaunchedEffect
        val palette = withContext(Dispatchers.Default) {
            Palette.from(bitmap).maximumColorCount(12).generate()
        }
        val seed = palette.lightVibrantSwatch?.rgb
            ?: palette.vibrantSwatch?.rgb
            ?: palette.lightMutedSwatch?.rgb
            ?: palette.getDominantColor(defaultBg.toArgb())
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(seed, hsl)
        if (hsl[1] < 0.08f) {
            hsl[1] = 0.04f
        } else {
            hsl[1] = hsl[1].coerceIn(0.30f, 0.50f)
        }
        hsl[2] = hsl[2].coerceIn(0.70f, 0.82f)
        rawBg = Color(ColorUtils.HSLToColor(hsl))
    }
    val bgColor by animateColorAsState(rawBg, tween(600), label = "deezerBg")
    // Con la banda pastel el fondo siempre es claro → contenido negro, como Deezer.
    val onColor = if (bgColor.luminance() > 0.5f) Color.Black else Color.White
    // Variante oscura del mismo matiz para la vista de letras a pantalla completa,
    // donde el texto es blanco y un fondo pastel no funcionaría.
    val lyricsBg = remember(rawBg) {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(rawBg.toArgb(), hsl)
        hsl[1] = (hsl[1] * 0.9f).coerceAtMost(0.5f)
        hsl[2] = 0.16f
        Color(ColorUtils.HSLToColor(hsl))
    }

    // ---- Cover carousel synced with the queue ----
    val pagerState = rememberPagerState(
        initialPage = currentWindowIndex.coerceAtLeast(0),
        pageCount = { queueWindows.size.coerceAtLeast(1) }
    )
    // Follow the player: when the track changes (next/prev/auto-advance), scroll the pager.
    LaunchedEffect(currentWindowIndex, queueWindows.size) {
        if (currentWindowIndex in queueWindows.indices &&
            pagerState.currentPage != currentWindowIndex &&
            !pagerState.isScrollInProgress
        ) {
            pagerState.animateScrollToPage(currentWindowIndex)
        }
    }
    // Follow the user: when a swipe settles on a neighbor, play that queue item.
    LaunchedEffect(pagerState.settledPage) {
        val settled = pagerState.settledPage
        if (settled in queueWindows.indices && settled != currentWindowIndex) {
            playerConnection.player.seekToDefaultPosition(queueWindows[settled].firstPeriodIndex)
            playerConnection.player.playWhenReady = true
        }
    }

    // Sigue la fuente global elegida en la app (p. ej. Dancing Script): el estilo
    // Deezer ya no fija la sans del sistema.
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current
    ) {
        val isLandscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(bgColor)
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---------- Top bar ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { state.collapseSoft() }) {
                    Icon(
                        painter = painterResource(R.drawable.expand_more),
                        contentDescription = "Cerrar",
                        tint = onColor,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Playlist",
                        color = onColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal
                    )
                    Text(
                        text = queueTitle ?: (mediaMetadata?.album?.title ?: ""),
                        color = onColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onVisualizerClick) {
                        Icon(
                            painter = painterResource(R.drawable.auto_awesome),
                            contentDescription = "Visualizador",
                            tint = onColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = "Más",
                            tint = onColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Column: Cover carousel (scaled to screen height)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            pageSpacing = 10.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) { page ->
                            val window = queueWindows.getOrNull(page)
                            val art = window?.mediaItem?.metadata?.thumbnailUrl
                                ?: mediaMetadata?.thumbnailUrl
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight(0.85f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.15f))
                            ) {
                                AsyncImage(
                                    model = art,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (page == pagerState.currentPage && canvasArtwork != null) {
                                    val (canvasPrimary, canvasFallback) = canvasArtwork.playbackUrls()
                                    if (canvasPrimary != null || canvasFallback != null) {
                                        CanvasArtworkPlayer(
                                            primaryUrl = canvasPrimary,
                                            fallbackUrl = canvasFallback,
                                            isPlaying = isPlaying,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Right Column: Info, Slider, Controls & Actions
                    Column(
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = mediaMetadata?.title ?: "",
                            color = onColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee()
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${mediaMetadata?.artists?.joinToString { it.name } ?: ""} — ${mediaMetadata?.album?.title ?: ""}",
                            color = onColor.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(Modifier.height(8.dp))

                        // Share / Video / Heart
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { showShareSheet = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.share_network),
                                    contentDescription = "Compartir",
                                    tint = onColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val videoModeActive by playerConnection.videoModeActive.collectAsState()
                                val videoModeLoading by playerConnection.videoModeLoading.collectAsState()
                                IconButton(
                                    onClick = {
                                        if (videoModeActive) {
                                            playerConnection.disableVideoMode()
                                        } else if (!videoModeLoading) {
                                            playerConnection.enableVideoMode()
                                        }
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.slow_motion_video),
                                        contentDescription = "Cambiar a video",
                                        tint = if (videoModeActive) MaterialTheme.colorScheme.primary else onColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                IconButton(onClick = { playerConnection.toggleLike() }) {
                                    Icon(
                                        painter = painterResource(
                                            if (currentSongLiked) R.drawable.favorite else R.drawable.favorite_border
                                        ),
                                        contentDescription = "Me gusta",
                                        tint = onColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }

                        // Times & Slider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(makeTimeString(currentPos), color = onColor, fontSize = 13.sp)
                            Text(makeTimeString(duration), color = onColor, fontSize = 13.sp)
                        }
                        val sliderColors = SliderDefaults.colors(
                            thumbColor = onColor,
                            activeTrackColor = onColor,
                            inactiveTrackColor = onColor.copy(alpha = 0.25f)
                        )
                        val pendingSeekPos = remember { longArrayOf(-1L) }
                        if (sliderStyle == SliderStyle.Standard) {
                            Slider(
                                value = currentPos.toFloat(),
                                valueRange = 0f..maxOf(duration.toFloat(), 1f),
                                onValueChange = {
                                    pendingSeekPos[0] = it.toLong()
                                    onSliderPositionChange(it.toLong())
                                },
                                onValueChangeFinished = {
                                    if (pendingSeekPos[0] >= 0L) {
                                        playerConnection.player.seekTo(pendingSeekPos[0])
                                    }
                                    pendingSeekPos[0] = -1L
                                    onSliderPositionChange(null)
                                },
                                colors = sliderColors,
                                thumb = {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(onColor)
                                    )
                                },
                                track = { sliderState ->
                                    PlayerSliderTrack(
                                        sliderState = sliderState,
                                        colors = sliderColors,
                                        trackHeight = 3.dp
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            StyledPlaybackSlider(
                                sliderStyle = sliderStyle,
                                value = currentPos.toFloat(),
                                valueRange = 0f..maxOf(duration.toFloat(), 1f),
                                onValueChange = {
                                    pendingSeekPos[0] = it.toLong()
                                    onSliderPositionChange(it.toLong())
                                },
                                onValueChangeFinished = {
                                    if (pendingSeekPos[0] >= 0L) {
                                        playerConnection.player.seekTo(pendingSeekPos[0])
                                    }
                                    pendingSeekPos[0] = -1L
                                    onSliderPositionChange(null)
                                },
                                activeColor = onColor,
                                isPlaying = isPlaying,
                                songKey = mediaMetadata?.id ?: "",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        // Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled }) {
                                Icon(
                                    painter = painterResource(if (shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle),
                                    contentDescription = "Aleatorio",
                                    tint = onColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            IconButton(onClick = { playerConnection.seekToPrevious() }) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_previous),
                                    contentDescription = "Anterior",
                                    tint = onColor,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(onColor)
                                    .clickable { playerConnection.player.togglePlayPause() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                                    contentDescription = "Play/Pausa",
                                    tint = bgColor,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            IconButton(onClick = { playerConnection.seekToNext() }) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_next),
                                    contentDescription = "Siguiente",
                                    tint = onColor,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            IconButton(onClick = { playerConnection.player.toggleRepeatMode() }) {
                                Icon(
                                    painter = painterResource(
                                        when (repeatMode) {
                                            androidx.media3.common.Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                                            androidx.media3.common.Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
                                            else -> R.drawable.repeat
                                        }
                                    ),
                                    contentDescription = "Repetir",
                                    tint = onColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                // ---------- Cover carousel (neighbors peek at both edges) ----------
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    HorizontalPager(
                        state = pagerState,
                        contentPadding = PaddingValues(horizontal = 34.dp),
                        pageSpacing = 14.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) { page ->
                        val window = queueWindows.getOrNull(page)
                        val art = window?.mediaItem?.metadata?.thumbnailUrl
                            ?: mediaMetadata?.thumbnailUrl
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.15f))
                        ) {
                            AsyncImage(
                                model = art,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Animated canvas over the current cover (cropped to the square slot).
                            if (page == pagerState.currentPage && canvasArtwork != null) {
                                val (canvasPrimary, canvasFallback) = canvasArtwork.playbackUrls()
                                if (canvasPrimary != null || canvasFallback != null) {
                                    CanvasArtworkPlayer(
                                        primaryUrl = canvasPrimary,
                                        fallbackUrl = canvasFallback,
                                        isPlaying = isPlaying,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            if (page == pagerState.currentPage) {
                                // "HQ" quality badge (bottom-left), like Deezer.
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(16.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF3A3A3A).copy(alpha = 0.85f))
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "HQ",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                // White "Letras" pill (bottom-right), like Deezer.
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(16.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White)
                                        .clickable { onLyricsClick() }
                                        .padding(horizontal = 14.dp, vertical = 9.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.mic),
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(7.dp))
                                    Text(
                                        text = "Letras",
                                        color = Color.Black,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---------- share · ⊕ · heart ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showShareSheet = true }) {
                    Icon(
                        painter = painterResource(R.drawable.share_network),
                        contentDescription = "Compartir",
                        tint = onColor,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Cambiar a modo video: el antiguo "+" duplicaba el menú de los tres puntos.
                    val videoModeActive by playerConnection.videoModeActive.collectAsState()
                    val videoModeLoading by playerConnection.videoModeLoading.collectAsState()
                    IconButton(
                        onClick = {
                            if (videoModeActive) {
                                playerConnection.disableVideoMode()
                            } else if (!videoModeLoading) {
                                playerConnection.enableVideoMode()
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.slow_motion_video),
                            contentDescription = "Cambiar a video",
                            tint = if (videoModeActive) MaterialTheme.colorScheme.primary else onColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = { playerConnection.toggleLike() }) {
                        Icon(
                            painter = painterResource(
                                if (currentSongLiked) R.drawable.favorite else R.drawable.favorite_border
                            ),
                            contentDescription = "Me gusta",
                            tint = onColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            // ---------- Times above the thin slider ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(makeTimeString(currentPos), color = onColor, fontSize = 15.sp)
                Text(makeTimeString(duration), color = onColor, fontSize = 15.sp)
            }
            val sliderColors = SliderDefaults.colors(
                thumbColor = onColor,
                activeTrackColor = onColor,
                inactiveTrackColor = onColor.copy(alpha = 0.25f)
            )
            // Tap-to-seek: en un toque rápido, onValueChangeFinished puede correr
            // ANTES de la recomposición que actualiza currentPos, así que el seek
            // usaba el valor viejo (no hacía nada). El destino se guarda fuera del
            // estado de Compose para que el toque en cualquier punto de la barra
            // salte directo ahí.
            val pendingSeekPos = remember { longArrayOf(-1L) }
            if (sliderStyle == SliderStyle.Standard) {
                // Deezer-authentic thin bar when the global style is the default.
                Slider(
                    value = currentPos.toFloat(),
                    valueRange = 0f..maxOf(duration.toFloat(), 1f),
                    onValueChange = {
                        pendingSeekPos[0] = it.toLong()
                        onSliderPositionChange(it.toLong())
                    },
                    onValueChangeFinished = {
                        if (pendingSeekPos[0] >= 0L) {
                            playerConnection.player.seekTo(pendingSeekPos[0])
                        }
                        pendingSeekPos[0] = -1L
                        onSliderPositionChange(null)
                    },
                    colors = sliderColors,
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(15.dp)
                                .clip(CircleShape)
                                .background(onColor)
                        )
                    },
                    track = { sliderState ->
                        PlayerSliderTrack(
                            sliderState = sliderState,
                            colors = sliderColors,
                            trackHeight = 3.dp
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                )
            } else {
                StyledPlaybackSlider(
                    sliderStyle = sliderStyle,
                    value = currentPos.toFloat(),
                    valueRange = 0f..maxOf(duration.toFloat(), 1f),
                    onValueChange = {
                        pendingSeekPos[0] = it.toLong()
                        onSliderPositionChange(it.toLong())
                    },
                    onValueChangeFinished = {
                        if (pendingSeekPos[0] >= 0L) {
                            playerConnection.player.seekTo(pendingSeekPos[0])
                        }
                        pendingSeekPos[0] = -1L
                        onSliderPositionChange(null)
                    },
                    activeColor = onColor,
                    isPlaying = isPlaying,
                    songKey = mediaMetadata?.id ?: "",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            // ---------- Title + "Artist - Album", centered ----------
            Text(
                text = mediaMetadata?.title ?: "",
                color = onColor,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(6.dp))
            val artistLine = buildString {
                append(mediaMetadata?.artists?.joinToString { it.name } ?: "")
                mediaMetadata?.album?.title?.takeIf { it.isNotBlank() }?.let { album ->
                    if (isNotEmpty()) append(" - ")
                    append(album)
                }
            }
            Text(
                text = artistLine,
                color = onColor.copy(alpha = 0.85f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clickable {
                        mediaMetadata?.artists?.firstOrNull { it.id != null }?.id?.let {
                            state.collapseSoft(); navController.navigate("artist/$it")
                        }
                    }
            )

            Spacer(Modifier.height(20.dp))

            // ---------- Controls: repeat · prev · play (plain) · next · shuffle ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { playerConnection.player.toggleRepeatMode() }) {
                    Icon(
                        painter = painterResource(
                            if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.repeat_one
                            else R.drawable.repeat
                        ),
                        contentDescription = "Repetir",
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) onColor
                               else onColor.copy(alpha = 0.85f),
                        modifier = Modifier.size(26.dp)
                    )
                }
                IconButton(onClick = { playerConnection.seekToPrevious() }) {
                    Icon(
                        painter = painterResource(R.drawable.deezer_previous),
                        contentDescription = "Anterior",
                        tint = onColor,
                        modifier = Modifier.size(40.dp)
                    )
                }
                // Plain oversized glyph — Deezer has no circle behind play/pause.
                IconButton(
                    onClick = { playerConnection.player.togglePlayPause() },
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                        contentDescription = "Reproducir/Pausar",
                        tint = onColor,
                        modifier = Modifier.size(52.dp)
                    )
                }
                IconButton(onClick = { playerConnection.seekToNext() }) {
                    Icon(
                        painter = painterResource(R.drawable.deezer_next),
                        contentDescription = "Siguiente",
                        tint = onColor,
                        modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(onClick = {
                    playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled
                }) {
                    Icon(
                        painter = painterResource(R.drawable.shuffle),
                        contentDescription = "Aleatorio",
                        tint = if (shuffleModeEnabled) onColor else onColor.copy(alpha = 0.85f),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // ---------- Bottom: speaker · "Iniciar mix" pill · queue ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Output device — Spotify Connect / Liner / Cast picker under speaker glyph
                IconButton(onClick = { showDevicePicker = true }) {
                    Icon(
                        painter = painterResource(R.drawable.speaker),
                        contentDescription = "Dispositivos conectados",
                        tint = onColor,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = onColor.copy(alpha = 0.10f),
                    modifier = Modifier.clickable {
                        mediaMetadata?.let {
                            playerConnection.playQueue(YouTubeQueue.radio(it))
                        }
                    }
                ) {
                    Text(
                        text = "Iniciar mix de la canción",
                        color = onColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 13.dp)
                    )
                }
                IconButton(onClick = onQueueClick) {
                    Icon(
                        painter = painterResource(R.drawable.list),
                        contentDescription = "Cola",
                        tint = onColor,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        if (showShareSheet) {
            ShareBottomSheet(
                title = mediaMetadata?.title ?: "",
                subtitle = mediaMetadata?.artists?.joinToString { it.name } ?: "",
                thumbnailUrl = mediaMetadata?.thumbnailUrl,
                shareLink = "https://music.youtube.com/watch?v=${mediaMetadata?.id ?: ""}",
                lyrics = lyricsEntity?.lyrics,
                mediaId = mediaMetadata?.id,
                onDismiss = { showShareSheet = false }
            )
        }

        if (showDevicePicker) {
            ConnectDeviceBottomSheet(
                onDismissRequest = { showDevicePicker = false }
            )
        }
    }
}

