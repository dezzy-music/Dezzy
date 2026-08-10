package com.arturo254.opentune.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import com.arturo254.opentune.constants.PlayerBackgroundStyle
import com.arturo254.opentune.constants.PlayerBackgroundStyleKey
import com.arturo254.opentune.constants.PlayerDesignStyle
import com.arturo254.opentune.constants.ShowCodecOnPlayerKey
import com.arturo254.opentune.constants.PlayerCustomImageUriKey
import com.arturo254.opentune.constants.PlayerCustomBlurKey
import com.arturo254.opentune.constants.PlayerCustomContrastKey
import com.arturo254.opentune.constants.PlayerCustomBrightnessKey
import com.arturo254.opentune.constants.DisableBlurKey
import com.arturo254.opentune.constants.BlurRadiusKey
import com.arturo254.opentune.constants.SliderStyle
import com.arturo254.opentune.constants.SliderStyleKey
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.navigation.NavController
import com.arturo254.opentune.ui.component.PlayerSliderTrack
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.arturo254.opentune.LocalPlayerConnection
import com.arturo254.opentune.R
import com.arturo254.opentune.canvas.models.CanvasArtwork
import com.arturo254.opentune.extensions.togglePlayPause
import com.arturo254.opentune.extensions.toggleRepeatMode
import com.arturo254.opentune.ui.component.BottomSheetState
import com.arturo254.opentune.ui.component.LocalBottomSheetPageState
import com.arturo254.opentune.ui.component.LocalMenuState
import com.arturo254.opentune.ui.menu.PlayerMenu
import com.arturo254.opentune.ui.utils.ShowMediaInfo
import com.arturo254.opentune.utils.makeTimeString
import com.arturo254.opentune.constants.CanvasModeKey
import com.arturo254.opentune.constants.CanvasMode
import com.arturo254.opentune.utils.rememberPreference
import com.arturo254.opentune.utils.rememberEnumPreference
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DezzyPlayer(
    state: BottomSheetState,
    navController: NavController,
    positionState: State<Long>,
    durationState: State<Long>,
    sliderPosition: Long?,
    onSliderPositionChange: (Long?) -> Unit,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    playerStyle: PlayerDesignStyle,
    modifier: Modifier = Modifier,
    collapsedBound: androidx.compose.ui.unit.Dp = 0.dp,
    gradientColors: List<Color> = emptyList(),
    onSleepTimerClick: () -> Unit = {},
    onVisualizerClick: () -> Unit = {},
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)
    val currentFormat by playerConnection.currentFormat.collectAsState(initial = null)
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()

    // Compartir directo desde el player (tarjeta Canción/Letra/Canvas)
    var showShareSheet by remember { mutableStateOf(false) }
    val shareLyricsEntity by playerConnection.currentLyrics.collectAsState(initial = null)
    if (showShareSheet) {
        com.arturo254.opentune.ui.component.ShareBottomSheet(
            title = mediaMetadata?.title ?: "",
            subtitle = mediaMetadata?.artists?.joinToString { it.name } ?: "",
            thumbnailUrl = mediaMetadata?.thumbnailUrl,
            shareLink = "https://music.youtube.com/watch?v=${mediaMetadata?.id ?: ""}",
            lyrics = shareLyricsEntity?.lyrics,
            mediaId = mediaMetadata?.id,
            onDismiss = { showShareSheet = false }
        )
    }

    val position by positionState
    val duration by durationState

    val menuState = LocalMenuState.current
    val bottomSheetPageState = LocalBottomSheetPageState.current
    val context = LocalContext.current

    val showCodecOnPlayer by rememberPreference(
        key = ShowCodecOnPlayerKey,
        defaultValue = true
    )
    val sliderStyle by rememberEnumPreference(SliderStyleKey, SliderStyle.Standard)
    val canvasMode by rememberEnumPreference(CanvasModeKey, defaultValue = CanvasMode.APPLE_WITH_FALLBACK)
    // Derive individual flags from the unified mode selector
    val canvasActiveForApple = canvasMode == CanvasMode.APPLE || canvasMode == CanvasMode.APPLE_WITH_FALLBACK
    val canvasActiveForSpotify = canvasMode == CanvasMode.SPOTIFY || canvasMode == CanvasMode.SPOTIFY_WITH_FALLBACK
    val anyCanvasActive = canvasMode != CanvasMode.NONE
    val videoModeActive by playerConnection.videoModeActive.collectAsState()
    val videoPlayer by playerConnection.videoPlayerInstance.collectAsState()
    
    val playerBackground by rememberEnumPreference(PlayerBackgroundStyleKey, PlayerBackgroundStyle.DEFAULT)
    val (playerCustomImageUri) = rememberPreference(PlayerCustomImageUriKey, "")
    val (playerCustomBlur) = rememberPreference(PlayerCustomBlurKey, 0f)
    val (playerCustomContrast) = rememberPreference(PlayerCustomContrastKey, 1f)
    val (playerCustomBrightness) = rememberPreference(PlayerCustomBrightnessKey, 1f)
    val (disableBlur) = rememberPreference(DisableBlurKey, false)
    val (blurRadius) = rememberPreference(BlurRadiusKey, 36f)
    // Keyed by (id, canvasMode): switching the canvas source clears the shown artwork and
    // resets the in-flight flag (a cancelled fetch of the other source used to leave it
    // stuck at true, silently blocking the new fetch).
    var canvasArtwork by remember(mediaMetadata?.id, canvasMode) {
        mutableStateOf<CanvasArtwork?>(null)
    }
    var canvasFetchInFlight by remember(mediaMetadata?.id, canvasMode) {
        mutableStateOf(false)
    }
    var showVolumeDialog by remember { mutableStateOf(false) }
    val storefront = remember {
        val country = Locale.getDefault().country
        if (country.length == 2) country.lowercase(Locale.ROOT) else "us"
    }

    LaunchedEffect(mediaMetadata?.id, canvasMode) {
        val id = mediaMetadata?.id
        if (id.isNullOrBlank() || !anyCanvasActive) {
            canvasArtwork = null
            return@LaunchedEffect
        }

        // ── 1. Check if a local downloaded Canvas file exists for this song ID ──
        val localCanvasFile = java.io.File(context.filesDir, "canvas/$id.mp4")
        val hasLocalCanvas = localCanvasFile.exists() && localCanvasFile.length() > 0

        // ── 2. Check the in-memory/disk cache for an online entry (HLS/animated URL) ──
        val cachedOnline = CanvasArtworkPlaybackCache.get(id)
        val expectedSource =
            if (canvasActiveForSpotify) CanvasArtwork.SOURCE_SPOTIFY else CanvasArtwork.SOURCE_APPLE

        // Prefer the online cache entry only when it matches the SELECTED source — a cached
        // Spotify canvas must not keep showing after the user switches to Apple (and vice versa).
        if (cachedOnline != null &&
            (!cachedOnline.animated.isNullOrBlank() || !cachedOnline.animatedVertical.isNullOrBlank()) &&
            (cachedOnline.isLocalFile || cachedOnline.source == expectedSource)
        ) {
            canvasArtwork = cachedOnline
            canvasFetchInFlight = false
            return@LaunchedEffect
        }

        if (hasLocalCanvas) {
            val localPath = "file://${localCanvasFile.absolutePath}"
            val localArtwork = CanvasArtwork(
                name = mediaMetadata?.title,
                artist = mediaMetadata?.artists?.firstOrNull()?.name,
                animated = localPath,
                animatedVertical = localPath,
                videoUrl = localPath,
                videoUrlVertical = localPath
            )
            canvasArtwork = localArtwork
            // Persist local entry to cache so Thumbnail.kt doesn't make a redundant network request
            CanvasArtworkPlaybackCache.put(id, localArtwork)
            canvasFetchInFlight = false
            // When online: launch background fetch to upgrade cache with remote URL for future sessions
            val connectivityManager = context.getSystemService(android.net.ConnectivityManager::class.java)
            val isOnline = connectivityManager?.activeNetwork != null
            if (isOnline && !canvasActiveForSpotify) {
                val songTitleRaw = mediaMetadata?.title ?: return@LaunchedEffect
                val artistNameRaw = mediaMetadata?.artists?.firstOrNull()?.name ?: ""
                withContext(Dispatchers.IO) {
                    runCatching {
                        val logFile = java.io.File(context.cacheDir, "spotify_canvas.log")
                        val remote = fetchCanvasArtworkForPlayback(
                            songTitleRaw = songTitleRaw,
                            artistNameRaw = artistNameRaw,
                            storefront = storefront,
                            requireVertical = false,
                            albumTitle = mediaMetadata?.album?.title,
                            durationSeconds = duration.takeIf { it > 0 }?.let { (it / 1000).toInt() },
                            spotifyCanvasEnabled = false,
                            spotifyTrackId = com.arturo254.opentune.spotify.SpotifyMatchFileCache
                                .getMatchByYouTubeId(id)?.spotifyId,
                            logFilePath = logFile.absolutePath
                        )
                        if (remote != null) {
                            CanvasArtworkPlaybackCache.put(id, remote)
                        }
                    }
                }
            }
            return@LaunchedEffect
        }

        // ── 3. No local file and no cache entry → fetch remotely ──
        if (canvasFetchInFlight) return@LaunchedEffect
        canvasFetchInFlight = true

        val songTitleRaw = mediaMetadata?.title ?: run {
            canvasFetchInFlight = false
            return@LaunchedEffect
        }
        val artistNameRaw = mediaMetadata?.artists?.firstOrNull()?.name ?: ""

        val fetched = withContext(Dispatchers.IO) {
            val logFile = java.io.File(context.cacheDir, "spotify_canvas.log")
            fetchCanvasArtworkForPlayback(
                songTitleRaw = songTitleRaw,
                artistNameRaw = artistNameRaw,
                storefront = storefront,
                requireVertical = false,
                albumTitle = mediaMetadata?.album?.title,
                durationSeconds = duration.takeIf { it > 0 }?.let { (it / 1000).toInt() },
                spotifyCanvasEnabled = canvasActiveForSpotify,
                spotifyTrackId = com.arturo254.opentune.spotify.SpotifyMatchFileCache
                    .getMatchByYouTubeId(id)?.spotifyId,
                logFilePath = logFile.absolutePath
            )
        }
        canvasArtwork = fetched
        if (fetched != null) {
            CanvasArtworkPlaybackCache.put(id, fetched)
        }
        canvasFetchInFlight = false
    }

    val primaryCanvasUrl = canvasArtwork?.animatedVertical ?: canvasArtwork?.animated
    val fallbackCanvasUrl = canvasArtwork?.videoUrlVertical ?: canvasArtwork?.videoUrl
    val hasCanvas = !primaryCanvasUrl.isNullOrBlank() || !fallbackCanvasUrl.isNullOrBlank()

    // ─── Apple-style play/pause scale & shadow animation ────────────────────
    val artworkScale by animateFloatAsState(
        targetValue   = if (isPlaying) 1.00f else 0.88f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness     = Spring.StiffnessLow
        ),
        label = "dezzy_artworkScale",
    )
    val artworkShadowElevation by animateDpAsState(
        targetValue   = if (isPlaying) 20.dp else 4.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness     = Spring.StiffnessLow
        ),
        label = "dezzy_artworkShadow",
    )

    // ─── Liked state ────────────────────────────────────────────────────────
    // Optimista (se rellena al instante al tocar); ver PlayerConnection.
    val liked by playerConnection.currentSongLiked.collectAsState()

    // ─── Quality label ───────────────────────────────────────────────────────
    val qualityLabel = remember(currentFormat) {
        buildString {
            currentFormat?.mimeType?.substringAfterLast('/')
                ?.uppercase()
                ?.let { append(it); append(" • ") }
            currentFormat?.bitrate?.let { br ->
                append("${br / 1000} kbps")
            }
        }.ifBlank { null }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val bottomBgColor = remember(gradientColors) {
        gradientColors.firstOrNull() ?: Color(0xFF1C1C1E)
    }

    // ─── Root Box: layers stacked fullscreen ─────────────────────────────────
    Box(
        modifier = modifier.fillMaxSize(),
    ) {

        // Layer 1 — background:
        if (playerStyle == PlayerDesignStyle.APPLE_MUSIC) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // 1. Highly blurred background
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(mediaMetadata?.thumbnailUrl)
                        .size(128, 128)
                        .allowHardware(false)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(150.dp)
                )

                // 2. Clean artwork/video in top 65% with vertical fade
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.65f)
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.00f to Color.Black,
                                        0.75f to Color.Black,
                                        0.92f to Color.Black.copy(alpha = 0.4f),
                                        1.00f to Color.Transparent,
                                    )
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        }
                ) {
                    if (videoModeActive && videoPlayer != null) {
                        AndroidView(
                            factory = { ctx ->
                                androidx.media3.ui.PlayerView(ctx).apply {
                                    useController = false
                                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            },
                            update = { view ->
                                view.player = videoPlayer
                                view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(mediaMetadata?.thumbnailUrl)
                                .size(coil3.size.Size.ORIGINAL)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        if (anyCanvasActive && hasCanvas) {
                            CanvasArtworkPlayer(
                                primaryUrl  = primaryCanvasUrl,
                                fallbackUrl = fallbackCanvasUrl,
                                isPlaying   = isPlaying,
                                modifier    = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                // 3. Dark gradient overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.05f),
                                    Color.Black.copy(alpha = 0.4f)
                                )
                            )
                        )
                )
            }
        } else if (playerStyle == PlayerDesignStyle.LIVE_MESH) {
            val infiniteTransition = rememberInfiniteTransition(label = "liveMeshRotation")
            
            val anchorRotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(80000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "anchorRotation"
            )
            
            val fastRotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(40000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "fastRotation"
            )
            
            val slowRotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(60000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "slowRotation"
            )

            val isGradientBased = playerBackground == PlayerBackgroundStyle.GRADIENT ||
                    playerBackground == PlayerBackgroundStyle.COLORING ||
                    playerBackground == PlayerBackgroundStyle.GLOW

            val imageUrl = when (playerBackground) {
                PlayerBackgroundStyle.CUSTOM -> playerCustomImageUri
                else -> mediaMetadata?.thumbnailUrl
            }

            if (isGradientBased || imageUrl.isNullOrBlank()) {
                val colors = if (gradientColors.size >= 2) {
                    gradientColors
                } else if (gradientColors.isNotEmpty()) {
                    listOf(gradientColors[0], gradientColors[0])
                } else {
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.7f
                            scaleY = 1.7f
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(100.dp)
                            .graphicsLayer { rotationZ = anchorRotation }
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(colors[0].copy(alpha = 0.8f), Color.Transparent),
                                    radius = 800f
                                )
                            )
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(120.dp)
                            .graphicsLayer { 
                                rotationZ = fastRotation
                                alpha = 0.6f
                            }
                            .background(
                                Brush.radialGradient(
                                    colors = listOf((colors.getOrNull(1) ?: colors[0]).copy(alpha = 0.8f), Color.Transparent),
                                    radius = 600f
                                )
                            )
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(120.dp)
                            .graphicsLayer { 
                                rotationZ = slowRotation
                                alpha = 0.5f
                            }
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(colors.last().copy(alpha = 0.8f), Color.Transparent),
                                    radius = 700f
                                )
                            )
                    )
                    
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.2f))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.25f)
                                    )
                                )
                            )
                    )
                }
            } else {
                AnimatedContent(
                    targetState = imageUrl,
                    transitionSpec = {
                        fadeIn(tween(1500)).togetherWith(fadeOut(tween(1500)))
                    },
                    label = "liveMeshBackground"
                ) { targetUrl ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.7f
                                scaleY = 1.7f
                            }
                    ) {
                        val colorFilter = if (playerBackground == PlayerBackgroundStyle.CUSTOM) {
                            val contrastVal = playerCustomContrast
                            val brightnessVal = playerCustomBrightness
                            val t = (1f - contrastVal) * 128f + (brightnessVal - 1f) * 255f
                            val matrixArray = floatArrayOf(
                                contrastVal, 0f, 0f, 0f, t,
                                0f, contrastVal, 0f, 0f, t,
                                0f, 0f, contrastVal, 0f, t,
                                0f, 0f, 0f, 1f, 0f,
                            )
                            ColorFilter.colorMatrix(ColorMatrix(matrixArray))
                        } else {
                            val matrix = remember { 
                                val m = ColorMatrix()
                                m.setToSaturation(1.8f) 
                                m
                            }
                            ColorFilter.colorMatrix(matrix)
                        }

                        val baseBlur = 100.dp + (if (playerBackground == PlayerBackgroundStyle.CUSTOM) playerCustomBlur.dp else 0.dp)

                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(if (playerBackground == PlayerBackgroundStyle.CUSTOM) android.net.Uri.parse(targetUrl) else targetUrl)
                                .size(128, 128) 
                                .allowHardware(false)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            colorFilter = colorFilter,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(baseBlur)
                                .graphicsLayer { rotationZ = anchorRotation }
                        )

                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(if (playerBackground == PlayerBackgroundStyle.CUSTOM) android.net.Uri.parse(targetUrl) else targetUrl)
                                .size(128, 128) 
                                .allowHardware(false)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            colorFilter = colorFilter,
                            alignment = Alignment.TopStart,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(baseBlur + 20.dp)
                                .graphicsLayer { 
                                    rotationZ = fastRotation
                                    alpha = 0.6f
                                }
                        )

                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(if (playerBackground == PlayerBackgroundStyle.CUSTOM) android.net.Uri.parse(targetUrl) else targetUrl)
                                .size(128, 128) 
                                .allowHardware(false)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            colorFilter = colorFilter,
                            alignment = Alignment.BottomEnd,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(baseBlur + 20.dp)
                                .graphicsLayer { 
                                    rotationZ = slowRotation
                                    alpha = 0.5f
                                }
                        )
                        
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.2f))
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.25f)
                                        )
                                    )
                                )
                        )
                    }
                }
            }
        } else if (isLandscape) {
            // Landscape background: Highly blurred backdrop
            if (hasCanvas) {
                CanvasArtworkPlayer(
                    primaryUrl  = primaryCanvasUrl,
                    fallbackUrl = fallbackCanvasUrl,
                    isPlaying   = isPlaying,
                    modifier    = Modifier.fillMaxSize().blur(80.dp),
                )
            } else {
                AnimatedContent(
                    targetState = mediaMetadata?.thumbnailUrl,
                    transitionSpec = {
                        fadeIn(tween(700)) togetherWith fadeOut(tween(700))
                    },
                    label = "dezzy_artTransition",
                ) { thumbUrl ->
                    AsyncImage(
                        model = thumbUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(80.dp),
                    )
                }
            }

            // Layer 2 — dark vignette gradients + dimming overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.00f to Color.Black.copy(alpha = 0.25f),
                                0.40f to Color.Transparent,
                                1.00f to Color.Black.copy(alpha = 0.65f),
                            ),
                        ),
                    ),
            )
        } else {
            // Portrait background: Solid very dark baseline, then blurred backdrop with low opacity for subtle glow
            val baseColor = gradientColors.firstOrNull() ?: Color.Black
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .background(baseColor.copy(alpha = 0.15f))
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.40f }
            ) {
                if (hasCanvas) {
                    CanvasArtworkPlayer(
                        primaryUrl  = primaryCanvasUrl,
                        fallbackUrl = fallbackCanvasUrl,
                        isPlaying   = isPlaying,
                        modifier    = Modifier.fillMaxSize().blur(70.dp),
                    )
                } else {
                    AnimatedContent(
                        targetState = mediaMetadata?.thumbnailUrl,
                        transitionSpec = {
                            fadeIn(tween(700)) togetherWith fadeOut(tween(700))
                        },
                        label = "dezzy_artTransition",
                    ) { thumbUrl ->
                        AsyncImage(
                            model = thumbUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(70.dp),
                        )
                    }
                }
            }

            // Dark vignette gradients + dimming overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.00f to Color.Black.copy(alpha = 0.20f),
                                0.35f to Color.Transparent,
                                0.80f to Color.Black.copy(alpha = 0.60f),
                                1.00f to Color.Black.copy(alpha = 0.95f),
                            ),
                        ),
                    ),
            )
        }

        // Layer 3 — UI controls
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(bottom = if (isLandscape) 0.dp else collapsedBound),
        ) {

            if (isLandscape) {
                // ── Top bar: collapse arrow only in landscape ─────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { state.collapseSoft() }) {
                        Icon(
                            painter = painterResource(R.drawable.expand_more),
                            contentDescription = "Cerrar",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    com.arturo254.opentune.ui.component.CastButton(
                        tint = Color.White.copy(alpha = 0.8f),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { showShareSheet = true }) {
                        Icon(
                            painter = painterResource(R.drawable.share),
                            contentDescription = "Compartir",
                            tint = Color.White.copy(alpha = 0.85f),
                        )
                    }
                    SongVideoToggle(playerConnection = playerConnection, canvas = canvasArtwork)
                    IconButton(onClick = onVisualizerClick) {
                        Icon(
                            painter = painterResource(R.drawable.auto_awesome),
                            contentDescription = "Visualizador",
                            tint = Color.White.copy(alpha = 0.85f),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            } else {
                // Spacer to balance the top area status bar
                Spacer(modifier = Modifier.height(24.dp))
                // Pill Canción/Video flanqueado por compartir y visualizador —
                // presente en todos los estilos de este player (Apple Music incluido).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { showShareSheet = true }) {
                        Icon(
                            painter = painterResource(R.drawable.share),
                            contentDescription = "Compartir",
                            tint = Color.White.copy(alpha = 0.85f),
                        )
                    }
                    SongVideoToggle(playerConnection = playerConnection, canvas = canvasArtwork)
                    IconButton(onClick = onVisualizerClick) {
                        Icon(
                            painter = painterResource(R.drawable.auto_awesome),
                            contentDescription = "Visualizador",
                            tint = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left Column: Floating breathing artwork card / Canvas
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (videoModeActive && videoPlayer != null) {
                            AndroidView(
                                factory = { ctx ->
                                    androidx.media3.ui.PlayerView(ctx).apply {
                                        useController = false
                                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    }
                                },
                                update = { view ->
                                    view.player = videoPlayer
                                    view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                },
                                modifier = Modifier
                                    .fillMaxHeight(0.85f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(16.dp))
                            )
                        } else {
                            AsyncImage(
                                model = mediaMetadata?.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxHeight(0.85f)
                                    .aspectRatio(1f)
                                    .graphicsLayer {
                                        scaleX = artworkScale
                                        scaleY = artworkScale
                                    }
                                    .shadow(artworkShadowElevation, RoundedCornerShape(16.dp))
                                    .clip(RoundedCornerShape(16.dp))
                            )
                            if (anyCanvasActive && hasCanvas) {
                                CanvasArtworkPlayer(
                                    primaryUrl  = primaryCanvasUrl,
                                    fallbackUrl = fallbackCanvasUrl,
                                    isPlaying   = isPlaying,
                                    modifier    = Modifier
                                        .fillMaxHeight(0.85f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                )
                            }
                        }
                    }

                    // Right Column: Controls and details
                    Column(
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // Title + artist + action buttons row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = mediaMetadata?.title ?: "—",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.basicMarquee(),
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = mediaMetadata?.artists?.joinToString { it.name } ?: "—",
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontSize = 14.sp,
                                    fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable {
                                        mediaMetadata?.artists?.firstOrNull()?.id?.let { artistId ->
                                            if (artistId.isNotBlank()) {
                                                navController.navigate("artist/$artistId")
                                                state.collapseSoft()
                                            }
                                        }
                                    }
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        mediaMetadata?.let { meta ->
                                            menuState.show {
                                                PlayerMenu(
                                                    mediaMetadata = meta,
                                                    navController = navController,
                                                    playerBottomSheetState = state,
                                                    onShowDetailsDialog = {
                                                        bottomSheetPageState.show {
                                                            ShowMediaInfo(meta.id)
                                                        }
                                                    },
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_vert),
                                        contentDescription = "Más opciones",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                                IconButton(onClick = { playerConnection.toggleLike() }) {
                                    Icon(
                                        painter = painterResource(
                                            if (liked) R.drawable.favorite else R.drawable.favorite_border,
                                        ),
                                        contentDescription = "Me gusta",
                                        tint = if (liked) Color(0xFFFF3B5C) else Color.White,
                                        modifier = Modifier.size(26.dp),
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Seek slider.
                        // Tap-to-seek: el destino se guarda fuera del estado de Compose
                        // porque en un toque rápido onValueChangeFinished corre antes de
                        // la recomposición que actualiza sliderPosition.
                        val safeDuration = if (duration <= 0L) 0f else duration.toFloat()
                        val safeValue = (sliderPosition ?: position).toFloat().coerceIn(0f, maxOf(0f, safeDuration))
                        val pendingSeekPos = remember { longArrayOf(-1L) }
                        StyledPlaybackSlider(
                            sliderStyle = sliderStyle,
                            value = safeValue,
                            valueRange = 0f..maxOf(1f, safeDuration),
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
                            activeColor = Color.White,
                            isPlaying = isPlaying,
                            songKey = mediaMetadata?.id ?: "",
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Time labels + quality center
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = makeTimeString(sliderPosition ?: position),
                                color = Color.White.copy(alpha = 0.7f),
                                fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                                fontSize = 11.sp,
                            )
                            if (showCodecOnPlayer && qualityLabel != null) {
                                Text(
                                    text = qualityLabel,
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                                    fontSize = 10.sp,
                                )
                            }
                            Text(
                                text = makeTimeString(duration),
                                color = Color.White.copy(alpha = 0.7f),
                                fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                                fontSize = 11.sp,
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Transport controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = {
                                    playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.shuffle),
                                    contentDescription = "Aleatorio",
                                    tint = if (shuffleModeEnabled) Color.White else Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }

                            IconButton(onClick = { playerConnection.seekToPrevious() }) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_previous),
                                    contentDescription = "Anterior",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp),
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .shadow(6.dp, CircleShape)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(bounded = true),
                                    ) {
                                        playerConnection.player.togglePlayPause()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (isPlaying) R.drawable.pause else R.drawable.play,
                                    ),
                                    contentDescription = "Play/Pausa",
                                    tint = Color.Black,
                                    modifier = Modifier.size(28.dp),
                                )
                            }

                            IconButton(onClick = { playerConnection.seekToNext() }) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_next),
                                    contentDescription = "Siguiente",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp),
                                )
                            }

                            IconButton(onClick = { playerConnection.player.toggleRepeatMode() }) {
                                Icon(
                                    painter = painterResource(
                                        when (repeatMode) {
                                            Player.REPEAT_MODE_ONE -> R.drawable.repeat_one
                                            else -> R.drawable.repeat
                                        }
                                    ),
                                    contentDescription = "Repetir",
                                    tint = if (repeatMode != Player.REPEAT_MODE_OFF)
                                        Color.White else Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Bottom action row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onQueueClick) {
                                Icon(
                                    painter = painterResource(R.drawable.list),
                                    contentDescription = "Cola",
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }

                            IconButton(onClick = { /* sleep timer */ }) {
                                Icon(
                                    painter = painterResource(R.drawable.bedtime),
                                    contentDescription = "Temporizador",
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }

                            IconButton(onClick = onLyricsClick) {
                                Icon(
                                    painter = painterResource(R.drawable.lyrics_bubble),
                                    contentDescription = "Letras",
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                // ── Middle: Floating breathing artwork card ──
                if (playerStyle != PlayerDesignStyle.APPLE_MUSIC) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (hasCanvas) {
                            CanvasArtworkPlayer(
                                primaryUrl  = primaryCanvasUrl,
                                fallbackUrl = fallbackCanvasUrl,
                                isPlaying   = isPlaying,
                                modifier    = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .graphicsLayer {
                                        scaleX = artworkScale
                                        scaleY = artworkScale
                                    }
                                    .shadow(artworkShadowElevation, RoundedCornerShape(16.dp))
                                    .clip(RoundedCornerShape(16.dp))
                            )
                        } else {
                            AsyncImage(
                                model = mediaMetadata?.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .graphicsLayer {
                                        scaleX = artworkScale
                                        scaleY = artworkScale
                                    }
                                    .shadow(artworkShadowElevation, RoundedCornerShape(16.dp))
                                    .clip(RoundedCornerShape(16.dp))
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                // ── Bottom controls section ──────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                ) {

                    // Title + artist + action buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Title & artist (left)
                        Column(
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = mediaMetadata?.title ?: "—",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.basicMarquee(),
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = mediaMetadata?.artists?.joinToString { it.name } ?: "—",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 16.sp,
                                fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    mediaMetadata?.artists?.firstOrNull()?.id?.let { artistId ->
                                        if (artistId.isNotBlank()) {
                                            navController.navigate("artist/$artistId")
                                            state.collapseSoft()
                                        }
                                    }
                                }
                            )
                        }

                        // Menu + like buttons (right, with circular translucent background)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            com.arturo254.opentune.ui.component.CastButton(
                                tint = Color.White,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.15f)),
                            )

                            // More options button
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .clickable {
                                        mediaMetadata?.let { meta ->
                                            menuState.show {
                                                PlayerMenu(
                                                    mediaMetadata = meta,
                                                    navController = navController,
                                                    playerBottomSheetState = state,
                                                    onShowDetailsDialog = {
                                                        bottomSheetPageState.show {
                                                            ShowMediaInfo(meta.id)
                                                        }
                                                    },
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.more_vert),
                                    contentDescription = "Más opciones",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }

                            // Like button
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .clickable { playerConnection.toggleLike() },
                                contentAlignment = Alignment.Center
                            ) {
                                val heartScale by animateFloatAsState(
                                    targetValue = if (liked) 1.25f else 1f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    ),
                                    label = "heartScale"
                                )
                                Icon(
                                    painter = painterResource(
                                        if (liked) R.drawable.favorite else R.drawable.favorite_border,
                                    ),
                                    contentDescription = "Me gusta",
                                    tint = if (liked) Color(0xFFFF3B5C) else Color.White,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .graphicsLayer {
                                            scaleX = heartScale
                                            scaleY = heartScale
                                        },
                                )
                            }
                        }
                    }

                    val spacer1 = if (playerStyle == PlayerDesignStyle.APPLE_MUSIC) 28.dp else 18.dp
                    Spacer(modifier = Modifier.height(spacer1))

                    // ── Seek slider ────────────────────────────────────────────
                    val safeDuration = if (duration <= 0L) 0f else duration.toFloat()
                    val safeValue = (sliderPosition ?: position).toFloat().coerceIn(0f, maxOf(0f, safeDuration))
                    StyledPlaybackSlider(
                        sliderStyle = sliderStyle,
                        value = safeValue,
                        valueRange = 0f..maxOf(1f, safeDuration),
                        onValueChange = { onSliderPositionChange(it.toLong()) },
                        onValueChangeFinished = {
                            sliderPosition?.let { playerConnection.player.seekTo(it) }
                            onSliderPositionChange(null)
                        },
                        activeColor = Color.White,
                        isPlaying = isPlaying,
                        songKey = mediaMetadata?.id ?: "",
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // ── Time labels + quality center ───────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 0.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = makeTimeString(sliderPosition ?: position),
                            color = Color.White.copy(alpha = 0.7f),
                            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                            fontSize = 12.sp,
                        )
                        if (showCodecOnPlayer && qualityLabel != null) {
                            Text(
                                text = qualityLabel,
                                color = Color.White.copy(alpha = 0.55f),
                                fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                                fontSize = 11.sp,
                            )
                        }
                        Text(
                            text = makeTimeString(duration),
                            color = Color.White.copy(alpha = 0.7f),
                            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                            fontSize = 12.sp,
                        )
                    }

                    val spacer2 = if (playerStyle == PlayerDesignStyle.APPLE_MUSIC) 24.dp else 16.dp
                    Spacer(modifier = Modifier.height(spacer2))

                    // ── Transport controls (Only 3 big buttons) ───────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Rewind
                        IconButton(
                            onClick = { playerConnection.seekToPrevious() },
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.skip_previous),
                                contentDescription = "Anterior",
                                tint = Color.White,
                                modifier = Modifier.size(44.dp),
                            )
                        }

                        // Play/Pause (Plain icon, no circular background)
                        IconButton(
                            onClick = { playerConnection.player.togglePlayPause() },
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (isPlaying) R.drawable.pause else R.drawable.play,
                                ),
                                contentDescription = "Play/Pausa",
                                tint = Color.White,
                                modifier = Modifier.size(56.dp),
                            )
                        }

                        // Fast Forward
                        IconButton(
                            onClick = { playerConnection.seekToNext() },
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.skip_next),
                                contentDescription = "Siguiente",
                                tint = Color.White,
                                modifier = Modifier.size(44.dp),
                            )
                        }
                    }

                    val spacer3 = if (playerStyle == PlayerDesignStyle.APPLE_MUSIC) 32.dp else 20.dp
                    Spacer(modifier = Modifier.height(spacer3))

                    // ── Bottom action row ──────────────────────────────────────────
                    if (playerStyle == PlayerDesignStyle.APPLE_MUSIC) {
                        // ── Apple Music Bottom action row (4 separate buttons spaced evenly) ──
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 1. Lyrics
                            IconButton(
                                onClick = onLyricsClick,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.lyrics_bubble),
                                    contentDescription = "Letras",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp),
                                )
                            }

                            // 2. Volume Dialog
                            IconButton(
                                onClick = { showVolumeDialog = true },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.volume_up),
                                    contentDescription = "Volumen",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp),
                                )
                            }

                            // 3. Sleep timer
                            IconButton(
                                onClick = onSleepTimerClick,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.bedtime),
                                    contentDescription = "Temporizador",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp),
                                )
                            }

                            // 4. Queue list
                            IconButton(
                                onClick = onQueueClick,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.list),
                                    contentDescription = "Cola",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                        }
                    } else {
                        // ── Default Bottom action row ──
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Queue list
                            IconButton(onClick = onQueueClick) {
                                Icon(
                                    painter = painterResource(R.drawable.list),
                                    contentDescription = "Cola",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp),
                                )
                            }

                            // Center items (Pill split in two)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(0.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Volume Dialog (Left Half)
                                Box(
                                    modifier = Modifier
                                        .size(width = 64.dp, height = 48.dp)
                                        .clip(
                                            RoundedCornerShape(
                                                topStart = 24.dp,
                                                bottomStart = 24.dp,
                                                topEnd = 0.dp,
                                                bottomEnd = 0.dp
                                            )
                                        )
                                        .background(Color.White.copy(alpha = 0.15f))
                                        .clickable { showVolumeDialog = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.volume_up),
                                        contentDescription = "Volumen",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
        
                                // Vertical Separator
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(48.dp)
                                        .background(Color.White.copy(alpha = 0.10f))
                                )

                                // Sleep timer (Right Half)
                                Box(
                                    modifier = Modifier
                                        .size(width = 64.dp, height = 48.dp)
                                        .clip(
                                            RoundedCornerShape(
                                                topStart = 0.dp,
                                                bottomStart = 0.dp,
                                                topEnd = 24.dp,
                                                bottomEnd = 24.dp
                                            )
                                        )
                                        .background(Color.White.copy(alpha = 0.15f))
                                        .clickable { onSleepTimerClick() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.bedtime),
                                        contentDescription = "Temporizador",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }

                            // Lyrics
                            IconButton(onClick = onLyricsClick) {
                                Icon(
                                    painter = painterResource(R.drawable.lyrics_bubble),
                                    contentDescription = "Letras",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                        }
                    }

                    val spacer4 = if (playerStyle == PlayerDesignStyle.APPLE_MUSIC) 16.dp else 8.dp
                    Spacer(modifier = Modifier.height(spacer4))
                }
            }
        }
    }

    if (showVolumeDialog) {
        val playerVolumeState = playerConnection.service.playerVolume.collectAsState()
        val safeVolume = playerVolumeState.value.coerceIn(0f, 1f)
        var previousVolume by remember { mutableFloatStateOf(0.5f) }
        val isMuted = safeVolume == 0f

        AlertDialog(
            onDismissRequest = { showVolumeDialog = false },
            icon = {
                Icon(
                    painter = painterResource(
                        if (isMuted) R.drawable.volume_off else R.drawable.volume_up
                    ),
                    contentDescription = null
                )
            },
            title = { Text(stringResource(R.string.volume)) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${(safeVolume * 100).roundToInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = {
                                if (isMuted) {
                                    playerConnection.service.playerVolume.value =
                                        if (previousVolume > 0f) previousVolume else 0.5f
                                } else {
                                    previousVolume = safeVolume
                                    playerConnection.service.playerVolume.value = 0f
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (isMuted) R.drawable.volume_off else R.drawable.volume_off
                                ),
                                contentDescription = null
                            )
                        }

                        Slider(
                            value = safeVolume,
                            onValueChange = { playerConnection.service.playerVolume.value = it },
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = {
                                playerConnection.service.playerVolume.value = 1f
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.volume_up),
                                contentDescription = null
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showVolumeDialog = false }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}
