package com.dezzy.music.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.dezzy.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.extensions.toggleRepeatMode
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.AlbumItem
import com.dezzy.music.innertube.models.ArtistItem
import com.dezzy.music.innertube.models.PlaylistItem
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.innertube.models.YTItem
import com.dezzy.music.innertube.pages.ArtistPage
import com.dezzy.music.lyrics.LyricsUtils.parseLyrics
import com.dezzy.music.models.toMediaMetadata
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.ui.component.BottomSheetState
import com.dezzy.music.ui.component.Lyrics
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.dezzy.music.constants.SliderStyle
import com.dezzy.music.constants.SliderStyleKey
import com.dezzy.music.ui.component.PlayerSliderTrack
import com.dezzy.music.ui.component.ShareBottomSheet
import com.dezzy.music.LocalDatabase
import com.dezzy.music.utils.ShareUtils
import com.dezzy.music.utils.SongCredits
import com.dezzy.music.utils.SongCreditsResolver
import com.dezzy.music.utils.makeTimeString
import com.dezzy.music.utils.rememberEnumPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SpotifyGreen = Color(0xFF1DB954)
private val SpotifyCardColor = Color(0xFF1F1F1F)

/** Color de las tarjetas del player Spotify, derivado de la paleta de la portada. */
private val LocalSpotifyCardColor = androidx.compose.runtime.compositionLocalOf { SpotifyCardColor }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SpotifyPlayer(
    state: BottomSheetState,
    navController: NavController,
    positionState: State<Long>,
    durationState: State<Long>,
    sliderPosition: Long?,
    onSliderPositionChange: (Long?) -> Unit,
    onQueueClick: () -> Unit,
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
    val currentSongLiked = currentSong?.song?.liked == true

    val position by positionState
    val duration by durationState
    val currentPos = sliderPosition ?: position

    val scrollState = rememberScrollState()
    val context = LocalContext.current

    val isLocalSong = mediaMetadata?.id?.startsWith("local_") == true

    // ---- Synced lyrics (live line + preview) ----
    val lyricsEntity by playerConnection.currentLyrics.collectAsState(initial = null)
    val lyricsText = remember(lyricsEntity) { lyricsEntity?.lyrics?.trim() }
    val lyricEntries = remember(lyricsText) {
        if (lyricsText != null && lyricsText != LYRICS_NOT_FOUND) {
            runCatching { parseLyrics(lyricsText) }.getOrNull()
        } else null
    }
    val isSynced = remember(lyricsText) {
        lyricsText != null && lyricsText != LYRICS_NOT_FOUND && lyricsText.startsWith("[")
    }
    val currentLineIndex = if (isSynced && lyricEntries != null && lyricEntries.isNotEmpty()) {
        lyricEntries.indexOfLast { it.time <= currentPos }.coerceAtLeast(0)
    } else -1
    val liveLyricLine = lyricEntries?.getOrNull(currentLineIndex)?.text?.takeIf { it.isNotBlank() }

    // ---- Artist page (About / Explore / Credits) ----
    val rawArtistId = mediaMetadata?.artists?.firstOrNull { it.id != null }?.id
    val primaryArtistName = mediaMetadata?.artists?.firstOrNull()?.name.orEmpty()
    var resolvedArtistId by remember(rawArtistId, primaryArtistName) { mutableStateOf(rawArtistId) }
    val primaryArtistId = resolvedArtistId ?: rawArtistId
    var artistPage by remember(primaryArtistId, primaryArtistName) { mutableStateOf<ArtistPage?>(null) }
    LaunchedEffect(rawArtistId, primaryArtistName) {
        val idToTry = rawArtistId ?: if (primaryArtistName.isNotBlank()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    YouTube.search(primaryArtistName, filter = com.dezzy.music.innertube.YouTube.SearchFilter.FILTER_ARTIST).getOrNull()
                        ?.items?.firstOrNull()?.id
                }.getOrNull()
            }
        } else null

        resolvedArtistId = idToTry
        if (idToTry != null) {
            withContext(Dispatchers.IO) {
                YouTube.artist(idToTry).onSuccess { page -> artistPage = page }
            }
        }
    }

    // ---- Créditos REALES (MusicBrainz: compositores y productores) ----
    var songCredits by remember(mediaMetadata?.id) { mutableStateOf<SongCredits?>(null) }
    LaunchedEffect(mediaMetadata?.id) {
        val meta = mediaMetadata ?: return@LaunchedEffect
        if (meta.id.startsWith("local_")) return@LaunchedEffect
        songCredits = SongCreditsResolver.resolve(
            context = context,
            mediaId = meta.id,
            title = meta.title,
            artist = meta.artists.firstOrNull()?.name.orEmpty(),
        )
    }


    // Canvas de la canción: decide si aplicamos el degradado VIVO de Spotify (solo
    // cuando NO hay canvas; las que tienen canvas se dejan exactamente como estaban).
    val canvasArtwork = rememberPlaybackCanvas(
        mediaId = mediaMetadata?.id,
        title = mediaMetadata?.title,
        artist = mediaMetadata?.artists?.firstOrNull()?.name,
        albumTitle = mediaMetadata?.album?.title,
        durationMs = duration,
    )
    val (canvasPrimary, canvasFallback) = canvasArtwork?.playbackUrls() ?: (null to null)
    val hasCanvas = canvasPrimary != null || canvasFallback != null

    // ---- Colores dinámicos de la carátula (como Spotify): fondo con la paleta y
    //      tarjetas de un tono distinto pero relacionado ----
    var dominantColor by remember { mutableStateOf(Color(0xFF1A1A1A)) }
    // Fondo inferior (al hacer scroll) y color de las tarjetas, ambos derivados
    // de la paleta pero más oscuros/desaturados; las tarjetas van un poco más
    // claras que el fondo para destacar sobre él.
    var bgBottomColor by remember { mutableStateOf(Color(0xFF121212)) }
    var cardColor by remember { mutableStateOf(Color(0xFF1F1F1F)) }
    val fallbackColor = Color(0xFF121212).toArgb()
    LaunchedEffect(mediaMetadata?.id, hasCanvas) {
        val thumbnailUrl = mediaMetadata?.thumbnailUrl ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(thumbnailUrl)
                .size(100, 100)
                .allowHardware(false)
                .build()
            val result = runCatching { context.imageLoader.execute(request) }.getOrNull()
            val bitmap = result?.image?.toBitmap()
            if (bitmap != null) {
                val palette = withContext(Dispatchers.Default) {
                    Palette.from(bitmap).maximumColorCount(16).generate()
                }
                val topBg: Int
                val bottomBg: Int
                val card: Int
                if (hasCanvas) {
                    // CON canvas: se deja EXACTAMENTE como estaba (dominante, apagado).
                    val dominant = palette.getDominantColor(fallbackColor)
                    val hsl = FloatArray(3)
                    androidx.core.graphics.ColorUtils.colorToHSL(dominant, hsl)
                    topBg = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hsl[0], (hsl[1] * 0.85f).coerceIn(0.15f, 0.6f), 0.30f))
                    bottomBg = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hsl[0], (hsl[1] * 0.5f).coerceIn(0.08f, 0.35f), 0.07f))
                    card = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hsl[0], (hsl[1] * 0.45f).coerceIn(0.06f, 0.30f), 0.155f))
                } else {
                    // SIN canvas: degradado VIVO como Spotify (color vibrante de la portada).
                    val base = palette.vibrantSwatch?.rgb
                        ?: palette.lightVibrantSwatch?.rgb
                        ?: palette.darkVibrantSwatch?.rgb
                        ?: palette.dominantSwatch?.rgb
                        ?: palette.mutedSwatch?.rgb
                        ?: fallbackColor
                    val hsl = FloatArray(3)
                    androidx.core.graphics.ColorUtils.colorToHSL(base, hsl)
                    val srcSat = hsl[1]
                    val topSat = if (srcSat < 0.12f) srcSat else (srcSat * 1.1f).coerceIn(0.42f, 0.90f)
                    topBg = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hsl[0], topSat, 0.42f))
                    bottomBg = androidx.core.graphics.ColorUtils.HSLToColor(
                        floatArrayOf(hsl[0], if (srcSat < 0.12f) srcSat else (topSat * 0.7f).coerceIn(0.12f, 0.6f), 0.08f)
                    )
                    card = androidx.core.graphics.ColorUtils.HSLToColor(
                        floatArrayOf(hsl[0], if (srcSat < 0.12f) srcSat else (topSat * 0.45f).coerceIn(0.06f, 0.35f), 0.16f)
                    )
                }
                withContext(Dispatchers.Main) {
                    dominantColor = Color(topBg)
                    bgBottomColor = Color(bottomBg)
                    cardColor = Color(card)
                }
            }
        }
    }

    var lyricsFullscreen by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }

    // Global slider style (canvas ya calculado arriba).
    val sliderStyle by rememberEnumPreference(SliderStyleKey, SliderStyle.Standard)

    val configuration = LocalConfiguration.current

    // Bloquear orientación horizontal para este reproductor SpotifyPlayer (forzar modo vertical/portrait)
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        val originalOrientation = activity?.requestedOrientation ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = originalOrientation
        }
    }

    val isLandscape = false

    // Show the compact sticky header once the user scrolls past the controls.
    val showMiniHeader by remember { derivedStateOf { scrollState.value > 700 } }

    // Follow the app's selected font (Type.kt applies FontFamilyKey to the Typography) so this
    // player matches the rest of the app instead of the platform default.
    val appFontFamily = MaterialTheme.typography.bodyLarge.fontFamily
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = appFontFamily),
        LocalSpotifyCardColor provides cardColor,
    ) {
    val screenHeightDp = configuration.screenHeightDp.dp
    var artworkDragTotal by remember { mutableStateOf(0f) }

    // El canvas de fondo se DESVANECE al hacer scroll (como Spotify): al bajar,
    // deja ver el fondo con la paleta y las tarjetas.
    val canvasFadePx = with(androidx.compose.ui.platform.LocalDensity.current) {
        (screenHeightDp * 0.5f).toPx()
    }
    val canvasAlpha by remember(canvasFadePx) {
        derivedStateOf { (1f - scrollState.value / canvasFadePx).coerceIn(0f, 1f) }
    }
    val swipeToChangeSong = Modifier.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { artworkDragTotal = 0f },
            onDragEnd = {
                when {
                    artworkDragTotal < -120f -> playerConnection.seekToNext()
                    artworkDragTotal > 120f -> playerConnection.seekToPrevious()
                }
                artworkDragTotal = 0f
            },
            onHorizontalDrag = { _, dragAmount -> artworkDragTotal += dragAmount },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                if (hasCanvas) {
                    // CON canvas: degradado simple como estaba (el canvas lo tapa).
                    Brush.verticalGradient(colors = listOf(dominantColor, bgBottomColor))
                } else {
                    // SIN canvas (como Spotify): el color vivo se sostiene arriba y
                    // luego cae a oscuro, en vez de un degradado lineal plano.
                    Brush.verticalGradient(
                        0.0f to dominantColor,
                        0.32f to dominantColor,
                        0.62f to androidx.compose.ui.graphics.lerp(dominantColor, bgBottomColor, 0.72f),
                        1.0f to bgBottomColor,
                    )
                }
            )
    ) {
        // ---------- Canvas a PANTALLA COMPLETA (como Spotify real) ----------
        // El video del canvas cubre toda la pantalla detrás de los controles, en
        // vez de quedar encajado en el recuadro de la carátula. Un scrim arriba y
        // abajo mantiene legibles la cabecera y los controles. Se desvanece al
        // hacer scroll (canvasAlpha) para dejar ver el fondo con la paleta.
        if (hasCanvas && !isLandscape) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = canvasAlpha }
                    .then(swipeToChangeSong)
            ) {
                AsyncImage(
                    model = mediaMetadata?.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                CanvasArtworkPlayer(
                    primaryUrl = canvasPrimary,
                    fallbackUrl = canvasFallback,
                    isPlaying = isPlaying,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.45f),
                                0.28f to Color.Transparent,
                                0.5f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.88f),
                            )
                        )
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---------- Header ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { state.collapseSoft() }) {
                    Icon(
                        painter = painterResource(R.drawable.expand_more),
                        contentDescription = "Cerrar",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "REPRODUCIENDO DESDE",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = queueTitle ?: (mediaMetadata?.album?.title ?: ""),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.dezzy.music.ui.component.CastButton(tint = Color.White)
                    IconButton(onClick = onVisualizerClick) {
                        Icon(
                            painter = painterResource(R.drawable.auto_awesome),
                            contentDescription = "Visualizador",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = "Más",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---------- Artwork/Controls Layout ----------
            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Column: Artwork / Canvas (fits screen height)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight(0.88f)
                                .aspectRatio(1f)
                                .shadow(elevation = 16.dp, shape = RoundedCornerShape(8.dp), clip = false)
                                .clip(RoundedCornerShape(8.dp))
                                .then(swipeToChangeSong)
                        ) {
                            AsyncImage(
                                model = mediaMetadata?.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (hasCanvas) {
                                CanvasArtworkPlayer(
                                    primaryUrl = canvasPrimary,
                                    fallbackUrl = canvasFallback,
                                    isPlaying = isPlaying,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }

                    // Right Column: Info + Controls + Actions
                    Column(
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Title + saved check + artist
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mediaMetadata?.title ?: "",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.basicMarquee()
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = mediaMetadata?.artists?.joinToString { it.name } ?: "",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable {
                                        (resolvedArtistId ?: primaryArtistName.takeIf { it.isNotBlank() })?.let {
                                            state.collapseSoft(); navController.navigate("artist/$it")
                                        }
                                    }
                                )
                            }
                            SavedCheck(currentSongLiked) { playerConnection.toggleLike() }
                        }

                        if (liveLyricLine != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = liveLyricLine,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { lyricsFullscreen = true }
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        // Slider
                        SpotifySeekBar(
                            position = currentPos,
                            duration = duration,
                            onSliderPositionChange = onSliderPositionChange,
                            onSeek = { playerConnection.player.seekTo(it) },
                            sliderStyle = sliderStyle,
                            isPlaying = isPlaying,
                            songKey = mediaMetadata?.id ?: "",
                        )

                        Spacer(Modifier.height(8.dp))

                        // Transport Controls
                        SpotifyControls(
                            isPlaying = isPlaying,
                            shuffleModeEnabled = shuffleModeEnabled,
                            repeatMode = repeatMode,
                            onShuffle = { playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled },
                            onPrevious = { playerConnection.seekToPrevious() },
                            onTogglePlay = { playerConnection.player.togglePlayPause() },
                            onNext = { playerConnection.seekToNext() },
                            onRepeat = { playerConnection.player.toggleRepeatMode() }
                        )

                        Spacer(Modifier.height(8.dp))

                        // Bottom actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            run {
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
                                        tint = if (videoModeActive) Color(0xFF1DB954) else Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onSleepTimerClick) {
                                    Icon(
                                        painter = painterResource(R.drawable.timer),
                                        contentDescription = "Temporizador",
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                IconButton(onClick = { showShareSheet = true }) {
                                    Icon(
                                        painter = painterResource(R.drawable.share),
                                        contentDescription = "Compartir",
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(onClick = onQueueClick) {
                                    Icon(
                                        painter = painterResource(R.drawable.queue_music),
                                        contentDescription = "Cola",
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // ---------- Artwork ----------
                if (hasCanvas && !isLandscape) {
                    Spacer(Modifier.height(screenHeightDp * 0.40f))
                } else {
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .shadow(
                                elevation = 24.dp,
                                shape = RoundedCornerShape(8.dp),
                                clip = false
                            )
                            .clip(RoundedCornerShape(8.dp))
                            .then(swipeToChangeSong)
                    ) {
                        AsyncImage(
                            model = mediaMetadata?.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        // En horizontal el canvas se muestra dentro del recuadro (sin fondo completo).
                        if (hasCanvas && isLandscape) {
                            CanvasArtworkPlayer(
                                primaryUrl = canvasPrimary,
                                fallbackUrl = canvasFallback,
                                isPlaying = isPlaying,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                // ---------- Live lyric line ----------
                if (liveLyricLine != null) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = liveLyricLine,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { lyricsFullscreen = true }
                    )
                }

                Spacer(Modifier.height(20.dp))

                // ---------- Title + saved check + artist ----------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mediaMetadata?.title ?: "",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = mediaMetadata?.artists?.joinToString { it.name } ?: "",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                (resolvedArtistId ?: primaryArtistName.takeIf { it.isNotBlank() })?.let {
                                    state.collapseSoft(); navController.navigate("artist/$it")
                                }
                            }
                        )
                    }
                    SavedCheck(currentSongLiked) { playerConnection.toggleLike() }
                }

                Spacer(Modifier.height(16.dp))

                // ---------- Slider + times ----------
                SpotifySeekBar(
                    position = currentPos,
                    duration = duration,
                    onSliderPositionChange = onSliderPositionChange,
                    onSeek = { playerConnection.player.seekTo(it) },
                    sliderStyle = sliderStyle,
                    isPlaying = isPlaying,
                    songKey = mediaMetadata?.id ?: "",
                )

                Spacer(Modifier.height(12.dp))

                // ---------- Controls ----------
                SpotifyControls(
                    isPlaying = isPlaying,
                    shuffleModeEnabled = shuffleModeEnabled,
                    repeatMode = repeatMode,
                    onShuffle = { playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled },
                    onPrevious = { playerConnection.seekToPrevious() },
                    onTogglePlay = { playerConnection.player.togglePlayPause() },
                    onNext = { playerConnection.seekToNext() },
                    onRepeat = { playerConnection.player.toggleRepeatMode() }
                )

                Spacer(Modifier.height(16.dp))

                // ---------- Bottom actions ----------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Cambiar a modo video: los audífonos duplicaban el CastButton de arriba.
                    run {
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
                                tint = if (videoModeActive) Color(0xFF1DB954) else Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onSleepTimerClick) {
                            Icon(
                                painter = painterResource(R.drawable.timer),
                                contentDescription = "Temporizador",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(onClick = { showShareSheet = true }) {
                            Icon(
                                painter = painterResource(R.drawable.share),
                                contentDescription = "Compartir",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = onQueueClick) {
                            Icon(
                                painter = painterResource(R.drawable.queue_music),
                                contentDescription = "Cola",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }

            // ---------- Cards ----------
            if (isLocalSong) {
                LocalSongCard()
            } else {
                // Lyrics preview
                if (lyricEntries != null && lyricEntries.any { it.text.isNotBlank() }) {
                    LyricsPreviewCard(
                        entries = lyricEntries.map { it.text },
                        synced = isSynced,
                        currentLineIndex = currentLineIndex,
                        onShowLyrics = { lyricsFullscreen = true }
                    )
                    Spacer(Modifier.height(16.dp))
                }

                // About the artist
                artistPage?.let { page ->
                    AboutArtistCard(
                        page = page,
                        onOpenArtist = {
                            state.collapseSoft()
                            navController.navigate("artist/${page.artist.id}")
                        }
                    )
                    Spacer(Modifier.height(16.dp))

                    // Explore
                    val exploreSection = page.sections.firstOrNull { it.items.isNotEmpty() }
                    if (exploreSection != null) {
                        ExploreCard(
                            title = "Explora ${page.artist.title}",
                            items = exploreSection.items,
                            currentId = mediaMetadata?.id,
                            onItemClick = { item ->
                                when (item) {
                                    is SongItem -> playerConnection.playQueue(
                                        YouTubeQueue(
                                            WatchEndpoint(videoId = item.id),
                                            item.toMediaMetadata()
                                        )
                                    )
                                    is AlbumItem -> {
                                        state.collapseSoft(); navController.navigate("album/${item.id}")
                                    }
                                    is ArtistItem -> {
                                        state.collapseSoft(); navController.navigate("artist/${item.id}")
                                    }
                                    is PlaylistItem -> {
                                        state.collapseSoft(); navController.navigate("online_playlist/${item.id}")
                                    }
                                }
                            }
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }

                // Credits: intérpretes del metadata + compositores/productores REALES
                val creditArtists = mediaMetadata?.artists?.filter { it.name.isNotBlank() }.orEmpty()
                if (creditArtists.isNotEmpty() || songCredits != null) {
                    CreditsCard(
                        songTitle = mediaMetadata?.title.orEmpty(),
                        artists = creditArtists.map { it.name to it.id },
                        credits = songCredits,
                        onArtistClick = { id ->
                            id?.let { state.collapseSoft(); navController.navigate("artist/$it") }
                        }
                    )
                    Spacer(Modifier.height(16.dp))
                }

                // SongDNA (estilo Spotify): las personas detrás de la canción +
                // explorar la combinación de artistas.
                if (primaryArtistId != null) {
                    SongDnaCard(
                        songTitle = mediaMetadata?.title.orEmpty(),
                        primaryArtistName = artistPage?.artist?.title
                            ?: mediaMetadata?.artists?.firstOrNull()?.name.orEmpty(),
                        primaryArtistThumbnail = artistPage?.artist?.thumbnail,
                        secondContributor = songCredits?.writers?.firstOrNull()
                            ?: songCredits?.producers?.firstOrNull(),
                        contributorCount = ((songCredits?.performers?.size ?: creditArtists.size) +
                            (songCredits?.writers?.size ?: 0) +
                            (songCredits?.producers?.size ?: 0)).coerceAtLeast(1),
                        cardColor = cardColor,
                        onExplore = {
                            state.collapseSoft()
                            navController.navigate("songdna/$primaryArtistId")
                        },
                    )
                }
            }

            Spacer(Modifier.height(48.dp))
        }

        // ---------- Sticky mini-header ----------
        AnimatedVisibility(
            visible = showMiniHeader,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(dominantColor.copy(alpha = 0.98f))
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mediaMetadata?.title ?: "",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = mediaMetadata?.artists?.joinToString { it.name } ?: "",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SavedCheck(currentSongLiked) { playerConnection.toggleLike() }
                IconButton(onClick = { playerConnection.player.togglePlayPause() }) {
                    Icon(
                        painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }

    if (lyricsFullscreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(colors = listOf(dominantColor, Color.Black)))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { lyricsFullscreen = false }) {
                        Icon(
                            painter = painterResource(R.drawable.expand_more),
                            contentDescription = "Cerrar letra",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = mediaMetadata?.title ?: "",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Lyrics(
                    lyricsSyncOffset = 0,
                    sliderPositionProvider = { sliderPosition },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // ---------- Share card (Spotify-style) ----------
    if (showShareSheet) {
        ShareBottomSheet(
            title = mediaMetadata?.title ?: "",
            subtitle = mediaMetadata?.artists?.joinToString { it.name } ?: "",
            thumbnailUrl = mediaMetadata?.thumbnailUrl,
            shareLink = "https://music.youtube.com/watch?v=${mediaMetadata?.id ?: ""}",
            lyrics = playerConnection.currentLyrics.collectAsState(initial = null).value?.lyrics,
            mediaId = mediaMetadata?.id,
            onDismiss = { showShareSheet = false }
        )
    }
    }
}

@Composable
private fun SavedCheck(liked: Boolean, onClick: () -> Unit) {
    if (liked) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(SpotifyGreen)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.check),
                contentDescription = "Guardado",
                tint = Color.Black,
                modifier = Modifier.size(16.dp)
            )
        }
    } else {
        IconButton(onClick = onClick) {
            Icon(
                painter = painterResource(R.drawable.favorite_border),
                contentDescription = "Guardar",
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpotifySeekBar(
    position: Long,
    duration: Long,
    onSliderPositionChange: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
    sliderStyle: SliderStyle = SliderStyle.Standard,
    isPlaying: Boolean = false,
    songKey: String = "",
) {
    // Spotify's bar: a thin (4dp) rounded track with a small round thumb.
    val sliderColors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        if (sliderStyle == SliderStyle.Standard) {
            // Barra fina auténtica de Spotify cuando el estilo global es el estándar.
            Slider(
                value = position.toFloat(),
                valueRange = 0f..maxOf(duration.toFloat(), 1f),
                onValueChange = { onSliderPositionChange(it.toLong()) },
                onValueChangeFinished = {
                    onSeek(position)
                    onSliderPositionChange(null)
                },
                colors = sliderColors,
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                },
                track = { sliderState ->
                    PlayerSliderTrack(
                        sliderState = sliderState,
                        colors = sliderColors,
                        trackHeight = 4.dp
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            StyledPlaybackSlider(
                sliderStyle = sliderStyle,
                value = position.toFloat(),
                valueRange = 0f..maxOf(duration.toFloat(), 1f),
                onValueChange = { onSliderPositionChange(it.toLong()) },
                onValueChangeFinished = {
                    onSeek(position)
                    onSliderPositionChange(null)
                },
                activeColor = Color.White,
                isPlaying = isPlaying,
                songKey = songKey,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(makeTimeString(position), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            Text(makeTimeString(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun SpotifyControls(
    isPlaying: Boolean,
    shuffleModeEnabled: Boolean,
    repeatMode: Int,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onShuffle) {
            Icon(
                painter = painterResource(R.drawable.shuffle),
                contentDescription = "Aleatorio",
                tint = if (shuffleModeEnabled) SpotifyGreen else Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        IconButton(onClick = onPrevious) {
            Icon(
                painter = painterResource(R.drawable.skip_previous),
                contentDescription = "Anterior",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(onClick = onTogglePlay),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                contentDescription = "Reproducir/Pausar",
                tint = Color.Black,
                modifier = Modifier.size(32.dp)
            )
        }
        IconButton(onClick = onNext) {
            Icon(
                painter = painterResource(R.drawable.skip_next),
                contentDescription = "Siguiente",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
        // Repeat: off → all → one, tinted green while active (faithful to Spotify).
        IconButton(onClick = onRepeat) {
            Icon(
                painter = painterResource(
                    if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.repeat_one
                    else R.drawable.repeat
                ),
                contentDescription = "Repetir",
                tint = if (repeatMode != Player.REPEAT_MODE_OFF) SpotifyGreen else Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun LyricsPreviewCard(
    entries: List<String>,
    synced: Boolean,
    currentLineIndex: Int,
    onShowLyrics: () -> Unit
) {
    // Pick a small window of lines: around the current line if synced, else the first lines.
    val nonBlank = entries.withIndex().filter { it.value.isNotBlank() }
    val window = if (synced && currentLineIndex >= 0) {
        nonBlank.filter { it.index in currentLineIndex..(currentLineIndex + 4) }
            .ifEmpty { nonBlank.take(5) }
    } else {
        nonBlank.take(5)
    }.map { it.value }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalSpotifyCardColor.current)
            .clickable(onClick = onShowLyrics)
            .padding(20.dp)
    ) {
        Text(
            text = "Muestra de la letra",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))
        window.forEachIndexed { i, line ->
            Text(
                text = line,
                color = if (i == 0) Color.White else Color.White.copy(alpha = 0.6f),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 28.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Surface(
            shape = CircleShape,
            color = Color.White,
            modifier = Modifier.clickable(onClick = onShowLyrics)
        ) {
            Text(
                text = "Mostrar la letra",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun AboutArtistCard(page: ArtistPage, onOpenArtist: () -> Unit) {
    val artist = page.artist
    var expanded by remember { mutableStateOf(false) }
    val listeners = artist.monthlyListenerCountText ?: artist.subscriberCountText

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalSpotifyCardColor.current)
            .clickable(onClick = onOpenArtist)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = artist.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            )
            Text(
                text = "Acerca del artista",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            )
        }
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = artist.title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onOpenArtist,
                    shape = CircleShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Seguir", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            if (listeners != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listeners,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp
                )
            }
            if (!page.description.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = page.description!!,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (!expanded) {
                    Text(
                        text = "ver más",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable { expanded = true }
                    )
                }
            }
        }
    }
}

@Composable
private fun ExploreCard(
    title: String,
    items: List<YTItem>,
    currentId: String?,
    onItemClick: (YTItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalSpotifyCardColor.current)
            .padding(vertical = 16.dp)
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(16.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items.take(12)) { item ->
                Box(
                    modifier = Modifier
                        .size(width = 130.dp, height = 190.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .clickable { onItemClick(item) }
                ) {
                    AsyncImage(
                        model = item.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                                )
                            )
                    )
                    Text(
                        text = item.title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CreditsCard(
    songTitle: String,
    artists: List<Pair<String, String?>>,
    credits: SongCredits?,
    onArtistClick: (String?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalSpotifyCardColor.current)
            .padding(20.dp)
    ) {
        Text(
            text = "Créditos",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        if (songTitle.isNotBlank()) {
            Text(
                text = songTitle,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(14.dp))

        // Intérpretes: los del metadata (clicables), como en Spotify
        artists.forEachIndexed { index, (name, id) ->
            CreditRow(
                name = name,
                role = if (index == 0) "Artista principal" else "Artista",
                clickable = id != null,
                onClick = { onArtistClick(id) },
            )
        }

        // Compositores y productores REALES (MusicBrainz)
        val artistNames = artists.map { it.first.lowercase() }.toSet()
        credits?.writers?.filter { it.lowercase() !in artistNames || credits.producers.isEmpty() }
            ?.forEach { writer ->
                CreditRow(name = writer, role = "Compositor", clickable = false, onClick = {})
            }
        credits?.producers?.forEach { producer ->
            CreditRow(name = producer, role = "Productor", clickable = false, onClick = {})
        }

        if (credits != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Fuente: ${credits.source}",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun CreditRow(
    name: String,
    role: String,
    clickable: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = role,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp
            )
        }
    }
}

// ── Tu ADN musical ───────────────────────────────────────────────────────────

/**
 * Tarjeta SongDNA (estilo Spotify): las personas detrás de la canción. Muestra
 * el artista principal + un segundo contribuidor (compositor/productor real de
 * los créditos), el número de contribuidores, y un botón "Explorar" que abre la
 * pantalla de combinación de artistas.
 */
@Composable
private fun SongDnaCard(
    songTitle: String,
    primaryArtistName: String,
    primaryArtistThumbnail: String?,
    secondContributor: String?,
    contributorCount: Int,
    cardColor: Color,
    onExplore: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardColor)
            .clickable(onClick = onExplore)
            .padding(20.dp)
    ) {
        // Cabecera: SongDNA + Beta
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.auto_awesome),
                contentDescription = null,
                tint = SpotifyGreen,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("SongDNA", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(SpotifyGreen)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text("Beta", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(18.dp))

        // Dos contribuidores: artista principal + compositor/productor
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            DnaContributor(
                thumbnail = primaryArtistThumbnail,
                name = primaryArtistName,
                role = "Artista principal",
                placeholderIcon = R.drawable.artist,
                modifier = Modifier.weight(1f),
            )
            DnaContributor(
                thumbnail = null,
                name = secondContributor ?: "—",
                role = if (contributorCount > 2) "Compositor y ${contributorCount - 2} más" else "Compositor",
                placeholderIcon = R.drawable.edit,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = songTitle,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$contributorCount contribuidores",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                )
            }
            OutlinedButton(
                onClick = onExplore,
                shape = CircleShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Text("Explorar", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Descubre a las personas detrás de la canción y combina artistas.",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun DnaContributor(
    thumbnail: String?,
    name: String,
    role: String,
    placeholderIcon: Int,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                Icon(
                    painter = painterResource(placeholderIcon),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = role,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


@Composable
private fun LocalSongCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(16.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.library_music),
                    contentDescription = null,
                    tint = SpotifyGreen,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Música Local", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "Esta pista se reproduce desde tu almacenamiento local.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp
            )
        }
    }
}
