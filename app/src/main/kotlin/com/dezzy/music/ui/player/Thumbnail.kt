/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */





package com.dezzy.music.ui.player

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.canvas.models.CanvasArtwork
import com.dezzy.music.constants.PlayerBackgroundStyle
import com.dezzy.music.constants.PlayerBackgroundStyleKey
import com.dezzy.music.constants.PlayerDesignStyle
import com.dezzy.music.constants.PlayerDesignStyleKey
import com.dezzy.music.constants.PlayerHorizontalPadding
import com.dezzy.music.constants.SeekExtraSeconds
import com.dezzy.music.constants.SwipeThumbnailKey
import com.dezzy.music.constants.DezzyCanvasKey
import com.dezzy.music.constants.MaxCanvasCacheSizeKey
import com.dezzy.music.constants.ThumbnailCornerRadiusKey
import com.dezzy.music.constants.CropThumbnailToSquareKey
import com.dezzy.music.constants.HidePlayerThumbnailKey
import com.dezzy.music.constants.RotatingThumbnailKey
import com.dezzy.music.extensions.metadata
import com.dezzy.music.extensions.toMediaItem
import com.dezzy.music.ui.utils.highRes
import com.dezzy.music.utils.rememberEnumPreference
import com.dezzy.music.utils.rememberPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.abs
import androidx.compose.ui.platform.LocalView
import android.content.Context
import android.view.HapticFeedbackConstants

object CanvasArtworkPlaybackCache {
    private const val defaultMaxSize = -1
    private const val PERSIST_FILE = "canvas_artwork_cache.json"
    private const val PERSIST_DEBOUNCE_MS = 2_000L

    private val map = LinkedHashMap<String, CanvasArtwork>(defaultMaxSize, 0.75f, true)
    @Volatile private var maxSize = defaultMaxSize
    @Volatile private var cacheFile: File? = null

    private val persistScope = CoroutineScope(Dispatchers.IO)
    private var persistJob: Job? = null

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val mapSerializer = MapSerializer(String.serializer(), CanvasArtwork.serializer())

    fun init(context: Context) {
        cacheFile = File(context.filesDir, PERSIST_FILE)
        loadFromDisk()
    }

    @Synchronized
    fun get(mediaId: String): CanvasArtwork? {
        if (maxSize == 0) return null
        return map[mediaId]
    }

    @Synchronized
    fun put(mediaId: String, artwork: CanvasArtwork) {
        val limit = maxSize
        if (limit == 0) return
        if (mediaId.isBlank()) return
        map[mediaId] = artwork
        if (limit > 0) {
            while (map.size > limit) {
                val it = map.entries.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
        }
        schedulePersist()
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun clear() {
        map.clear()
        schedulePersist()
    }

    @Synchronized
    fun setMaxSize(value: Int) {
        maxSize = value
        if (maxSize == 0) {
            map.clear()
            schedulePersist()
            return
        }
        if (maxSize < 0) return
        var evicted = false
        while (map.size > maxSize) {
            val it = map.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
                evicted = true
            } else {
                break
            }
        }
        if (evicted) schedulePersist()
    }

    @Synchronized
    private fun loadFromDisk() {
        val file = cacheFile ?: return
        if (!file.exists()) return
        try {
            val raw = file.readText()
            if (raw.isBlank()) return
            val restored = json.decodeFromString(mapSerializer, raw)
            map.clear()
            map.putAll(restored)
            while (maxSize > 0 && map.size > maxSize) {
                val it = map.entries.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                } else {
                    break
                }
            }
            Timber.d("Canvas cache restored: ${map.size} entries from disk")
        } catch (e: Exception) {
            Timber.e(e, "Failed to restore canvas cache from disk")
            runCatching { file.delete() }
        }
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = persistScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            writeToDisk()
        }
    }

    private fun writeToDisk() {
        val file = cacheFile ?: return
        try {
            val snapshot: Map<String, CanvasArtwork>
            synchronized(this@CanvasArtworkPlaybackCache) {
                snapshot = LinkedHashMap(map)
            }
            val raw = json.encodeToString(mapSerializer, snapshot)
            file.writeText(raw)
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist canvas cache to disk")
        }
    }
}

private data class ThumbnailPage(
    val slotKey: String,
    val windowIndex: Int,
    val mediaItem: MediaItem,
)

@SuppressLint("LocalContextGetResourceValueCall")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Thumbnail(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
    isPlayerExpanded: Boolean = true, // Add parameter to control swipe based on player state
    onTap: (() -> Unit)? = null,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current

    // States
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val error by playerConnection.error.collectAsState()
    val queueTitle by playerConnection.queueTitle.collectAsState()

    val swipeThumbnail by rememberPreference(SwipeThumbnailKey, true)

    val view = LocalView.current

    val hidePlayerThumbnail by rememberPreference(HidePlayerThumbnailKey, false)
    // Canvas in classic styles (V1–V6): honors the legacy DezzyCanvas toggle OR the
    // unified CanvasMode selector (APPLE by default) used by the standalone players, so
    // canvases show up in every player style with one setting.
    val legacyCanvasEnabled by rememberPreference(DezzyCanvasKey, false)
    val canvasMode by rememberEnumPreference(
        com.dezzy.music.constants.CanvasModeKey,
        defaultValue = com.dezzy.music.constants.CanvasMode.APPLE_WITH_FALLBACK,
    )
    val archiveTuneCanvasEnabled = legacyCanvasEnabled ||
        canvasMode != com.dezzy.music.constants.CanvasMode.NONE
    val playerDesignStyle by rememberEnumPreference(
        key = PlayerDesignStyleKey,
        defaultValue = PlayerDesignStyle.V4,
    )
    val (maxCanvasCacheSize, _) = rememberPreference(
        key = MaxCanvasCacheSizeKey,
        defaultValue = -1,
    )
    val (thumbnailCornerRadius, _) = rememberPreference(
        key = ThumbnailCornerRadiusKey,
        defaultValue = 16f
    )
    val cropThumbnailToSquare by rememberPreference(CropThumbnailToSquareKey, false)
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()

    // Player background style for consistent theming
    val playerBackground by rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = PlayerBackgroundStyle.DEFAULT
    )

    val isPremiumPlayer = playerDesignStyle == PlayerDesignStyle.APPLE_MUSIC || playerDesignStyle == PlayerDesignStyle.LIVE_MESH
    val textBackgroundColor = if (isPremiumPlayer) Color.White else when (playerBackground) {
        PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.onBackground
        PlayerBackgroundStyle.BLUR -> Color.White
        PlayerBackgroundStyle.GRADIENT -> Color.White
        PlayerBackgroundStyle.COLORING -> Color.White
        PlayerBackgroundStyle.BLUR_GRADIENT -> Color.White
        PlayerBackgroundStyle.GLOW -> Color.White
        PlayerBackgroundStyle.GLOW_ANIMATED -> Color.White
        PlayerBackgroundStyle.CUSTOM -> Color.White
    }

    LaunchedEffect(maxCanvasCacheSize) {
        CanvasArtworkPlaybackCache.setMaxSize(maxCanvasCacheSize)
    }

    // Grid state
    val thumbnailLazyGridState = rememberLazyGridState()

    // Create a playlist using correct shuffle-aware logic
    val timeline = playerConnection.player.currentTimeline
    val currentIndex = playerConnection.player.currentMediaItemIndex
    val shuffleModeEnabled = playerConnection.player.shuffleModeEnabled
    val previousWindowIndex = if (swipeThumbnail && !timeline.isEmpty) {
        timeline.getPreviousWindowIndex(
            currentIndex,
            Player.REPEAT_MODE_OFF,
            shuffleModeEnabled
        )
    } else {
        C.INDEX_UNSET
    }
    val previousMediaMetadata = if (previousWindowIndex != C.INDEX_UNSET) {
        try {
            playerConnection.player.getMediaItemAt(previousWindowIndex)
        } catch (e: Exception) { null }
    } else null

    val nextWindowIndex = if (swipeThumbnail && !timeline.isEmpty) {
        timeline.getNextWindowIndex(
            currentIndex,
            Player.REPEAT_MODE_OFF,
            shuffleModeEnabled
        )
    } else {
        C.INDEX_UNSET
    }
    val nextMediaMetadata = if (nextWindowIndex != C.INDEX_UNSET) {
        try {
            playerConnection.player.getMediaItemAt(nextWindowIndex)
        } catch (e: Exception) { null }
    } else null

    val currentMediaItem = remember(mediaMetadata) {
        // Fallback to player's current item if mediaMetadata is null,
        // but prefer mediaMetadata for immediate updates during crossfade.
        val metadata = mediaMetadata
        if (metadata != null) {
            // Use extension to convert metadata to a proper MediaItem with all fields (uri, artwork, tag)
            metadata.toMediaItem()
        } else {
            try {
                playerConnection.player.currentMediaItem
            } catch (e: Exception) { null }
        }
    }

    val thumbnailPages = buildList {
        if (previousMediaMetadata != null) {
            add(ThumbnailPage(slotKey = "previous", windowIndex = previousWindowIndex, mediaItem = previousMediaMetadata))
        }
        if (currentMediaItem != null) {
            add(ThumbnailPage(slotKey = "current", windowIndex = currentIndex, mediaItem = currentMediaItem))
        }
        if (nextMediaMetadata != null) {
            add(ThumbnailPage(slotKey = "next", windowIndex = nextWindowIndex, mediaItem = nextMediaMetadata))
        }
    }
    val currentMediaIndex = thumbnailPages.indexOfFirst { it.slotKey == "current" }

    // OuterTune Snap behavior
    val horizontalLazyGridItemWidthFactor = 1f
    val thumbnailSnapLayoutInfoProvider = remember(thumbnailLazyGridState) {
        SnapLayoutInfoProvider(
            lazyGridState = thumbnailLazyGridState,
            positionInLayout = { layoutSize, itemSize ->
                (layoutSize * horizontalLazyGridItemWidthFactor / 2f - itemSize / 2f)
            },
            velocityThreshold = 500f
        )
    }

    // Current item tracking
    val currentItem by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemIndex } }
    val itemScrollOffset by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemScrollOffset } }

    // Handle swipe to change song
    LaunchedEffect(itemScrollOffset) {
        if (!thumbnailLazyGridState.isScrollInProgress || !swipeThumbnail || itemScrollOffset != 0 || currentMediaIndex < 0) return@LaunchedEffect

        if (currentItem > currentMediaIndex && canSkipNext) {
            playerConnection.player.seekToNext()
            if (com.dezzy.music.ui.screens.settings.DiscordPresenceManager.isRunning()) {
                try { com.dezzy.music.ui.screens.settings.DiscordPresenceManager.restart() } catch (_: Exception) {}
            }
        } else if (currentItem < currentMediaIndex && canSkipPrevious) {
            playerConnection.player.seekToPreviousMediaItem()
            if (com.dezzy.music.ui.screens.settings.DiscordPresenceManager.isRunning()) {
                try { com.dezzy.music.ui.screens.settings.DiscordPresenceManager.restart() } catch (_: Exception) {}
            }
        }
    }

    // Update position when song changes
    LaunchedEffect(mediaMetadata, currentMediaItem?.mediaId, canSkipPrevious, canSkipNext) {
        val index = maxOf(0, currentMediaIndex)
        if (index >= 0 && index < thumbnailPages.size) {
            try {
                thumbnailLazyGridState.animateScrollToItem(index)
            } catch (e: Exception) {
                thumbnailLazyGridState.scrollToItem(index)
            }
        }
    }

    LaunchedEffect(playerConnection.player.currentMediaItemIndex, currentMediaItem?.mediaId) {
        val index = currentMediaIndex
        if (index >= 0 && index != currentItem) {
            thumbnailLazyGridState.scrollToItem(index)
        }
    }

    // Seek on double tap
    var showSeekEffect by remember { mutableStateOf(false) }
    var seekDirection by remember { mutableStateOf("") }
    val layoutDirection = LocalLayoutDirection.current

    Box(modifier = modifier) {
        // Error view
        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .padding(32.dp)
                .align(Alignment.Center),
        ) {
            error?.let { playbackError ->
                PlaybackError(
                    error = playbackError,
                    mediaId = currentMediaItem?.mediaId,
                    retry = {
                        playerConnection.player.prepare()
                        playerConnection.player.play()
                    },
                )
            }
        }

        // Main thumbnail view
        AnimatedVisibility(
            visible = error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Now Playing header
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.now_playing),
                        style = MaterialTheme.typography.titleMedium,
                        color = textBackgroundColor
                    )
                    // Show album title or queue title
                    val playingFrom = queueTitle ?: mediaMetadata?.album?.title
                    if (!playingFrom.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = playingFrom,
                            style = MaterialTheme.typography.titleMedium,
                            color = textBackgroundColor.copy(alpha = 0.8f),
                            maxLines = 1,
                            modifier = Modifier.basicMarquee()
                        )
                    }
                }

                // Canción/Video pill (YouTube-Music-style) for the classic styles + Expressive,
                // anchored between the header and the artwork — never over the status bar.
                if (playerDesignStyle == PlayerDesignStyle.V1 ||
                    playerDesignStyle == PlayerDesignStyle.V2 ||
                    playerDesignStyle == PlayerDesignStyle.V3 ||
                    playerDesignStyle == PlayerDesignStyle.V4 ||
                    playerDesignStyle == PlayerDesignStyle.V6
                ) {
                    // Canvas de la canción actual para el botón de Video inteligente
                    val toggleCanvas = rememberPlaybackCanvas(
                        mediaId = mediaMetadata?.id,
                        title = mediaMetadata?.title,
                        artist = mediaMetadata?.artists?.firstOrNull()?.name,
                        albumTitle = mediaMetadata?.album?.title,
                        durationMs = playerConnection.player.duration.coerceAtLeast(0L),
                    )
                    SongVideoToggle(
                        playerConnection = playerConnection,
                        canvas = toggleCanvas,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Thumbnail content
                BoxWithConstraints(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    val horizontalLazyGridItemWidth = maxWidth * horizontalLazyGridItemWidthFactor
                    val containerMaxWidth = maxWidth
                    val containerMaxHeight = maxHeight

                    LazyHorizontalGrid(
                        state = thumbnailLazyGridState,
                        rows = GridCells.Fixed(1),
                        flingBehavior = rememberSnapFlingBehavior(thumbnailSnapLayoutInfoProvider),
                        userScrollEnabled = swipeThumbnail && isPlayerExpanded, // Only allow swipe when player is expanded
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = thumbnailPages,
                            key = { page ->
                                "${page.slotKey}:${page.windowIndex}:${page.mediaItem.mediaId.ifEmpty { "unknown" }}"
                            },
                            contentType = { "thumbnailPage" },
                        ) { page ->
                            val item = page.mediaItem
                            val rotatingThumbnail by rememberPreference(RotatingThumbnailKey, defaultValue = false)
                            val isCurrentItem = item.mediaId == currentMediaItem?.mediaId
                            val infiniteTransition = rememberInfiniteTransition(label = "ThumbnailRotation")
                            val rotation by infiniteTransition.animateFloat(
                                initialValue = 0f,
                                targetValue = if (isPlaying && rotatingThumbnail && isCurrentItem) 360f else 0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(20000, easing = LinearEasing),
                                    repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                                ),
                                label = "Rotation"
                            )
                            val incrementalSeekSkipEnabled by rememberPreference(SeekExtraSeconds, defaultValue = false)
                            var skipMultiplier by remember { mutableStateOf(1) }
                            var lastTapTime by remember { mutableLongStateOf(0L) }
                            val itemMetadata = remember(item) { item.metadata }
                            val storefront =
                                remember {
                                    val country = Locale.getDefault().country
                                    if (country.length == 2) country.lowercase(Locale.ROOT) else "us"
                                }
                            val shouldAnimateCanvas =
                                archiveTuneCanvasEnabled &&
                                        playerDesignStyle != PlayerDesignStyle.V7 &&
                                        item.mediaId.isNotBlank() &&
                                        item.mediaId == currentMediaItem?.mediaId
                            // Keyed by (id, canvasMode): switching the canvas source clears the
                            // shown artwork and resets the in-flight flag (a cancelled fetch of
                            // the other source used to leave it stuck, blocking the new fetch).
                            var canvasArtwork by remember(item.mediaId, canvasMode) { mutableStateOf<CanvasArtwork?>(null) }
                            var canvasFetchedAtMs by remember(item.mediaId, canvasMode) { mutableLongStateOf(0L) }
                            var canvasFetchInFlight by remember(item.mediaId, canvasMode) { mutableStateOf(false) }

                            LaunchedEffect(shouldAnimateCanvas) {
                                if (!shouldAnimateCanvas) {
                                    canvasArtwork = null
                                    canvasFetchedAtMs = 0L
                                    canvasFetchInFlight = false
                                }
                            }

                            LaunchedEffect(shouldAnimateCanvas, item.mediaId, canvasMode) {
                                if (!shouldAnimateCanvas) return@LaunchedEffect
                                val expectedCanvasSource =
                                    if (canvasMode == com.dezzy.music.constants.CanvasMode.SPOTIFY || canvasMode == com.dezzy.music.constants.CanvasMode.SPOTIFY_WITH_FALLBACK) {
                                        CanvasArtwork.SOURCE_SPOTIFY
                                    } else {
                                        CanvasArtwork.SOURCE_APPLE
                                    }

                                // ── 1. Check in-memory/disk cache (includes entries saved by DezzyPlayer),
                                //       accepting only entries that match the selected canvas source ──
                                CanvasArtworkPlaybackCache.get(item.mediaId)?.let { cached ->
                                    if (cached.isLocalFile || cached.source == expectedCanvasSource) {
                                        canvasArtwork = cached
                                        canvasFetchedAtMs = System.currentTimeMillis()
                                        canvasFetchInFlight = false
                                        return@LaunchedEffect
                                    }
                                    // Cached canvas is from the other source — refetch below.
                                }

                                // ── 2. Check local downloaded canvas file before hitting the network ──
                                val localCanvasFile = java.io.File(context.filesDir, "canvas/${item.mediaId}.mp4")
                                if (localCanvasFile.exists() && localCanvasFile.length() > 0) {
                                    val localPath = "file://${localCanvasFile.absolutePath}"
                                    val localArtwork = com.dezzy.music.canvas.models.CanvasArtwork(
                                        name = itemMetadata?.title ?: item.mediaMetadata.title?.toString(),
                                        artist = itemMetadata?.artists?.firstOrNull()?.name
                                            ?: item.mediaMetadata.artist?.toString(),
                                        animated = localPath,
                                        animatedVertical = localPath,
                                        videoUrl = localPath,
                                        videoUrlVertical = localPath
                                    )
                                    canvasArtwork = localArtwork
                                    canvasFetchedAtMs = System.currentTimeMillis()
                                    canvasFetchInFlight = false
                                    // Save to cache so next time we skip even the file check
                                    CanvasArtworkPlaybackCache.put(item.mediaId, localArtwork)
                                    return@LaunchedEffect
                                }

                                // ── 3. Fetch remotely ──
                                val songTitleRaw =
                                    itemMetadata?.title
                                        ?.takeIf { it.isNotBlank() }
                                        ?: item.mediaMetadata.title?.toString()
                                        ?: return@LaunchedEffect

                                val artistNameRaw =
                                    itemMetadata?.artists?.firstOrNull()?.name
                                        ?.takeIf { it.isNotBlank() }
                                        ?: item.mediaMetadata.artist?.toString()
                                        ?: item.mediaMetadata.subtitle?.toString()
                                        ?: ""

                                val now = System.currentTimeMillis()
                                if (canvasFetchInFlight) return@LaunchedEffect
                                canvasFetchInFlight = true

                                val fetched =
                                    withContext(Dispatchers.IO) {
                                        fetchCanvasArtworkForPlayback(
                                            songTitleRaw = songTitleRaw,
                                            artistNameRaw = artistNameRaw,
                                            storefront = storefront,
                                            requireVertical = false,
                                            albumTitle = itemMetadata?.album?.title,
                                            durationSeconds = item.mediaMetadata.durationMs
                                                ?.takeIf { it > 0L }
                                                ?.let { (it / 1000).toInt() },
                                            // Respeta la fuente elegida: antes siempre pedía la
                                            // cadena de Apple aunque el usuario tuviera Spotify.
                                            spotifyCanvasEnabled =
                                                canvasMode == com.dezzy.music.constants.CanvasMode.SPOTIFY || canvasMode == com.dezzy.music.constants.CanvasMode.SPOTIFY_WITH_FALLBACK,
                                            spotifyTrackId = com.dezzy.music.spotify.SpotifyMatchFileCache
                                                .getMatchByYouTubeId(item.mediaId)?.spotifyId,
                                            allowFallback = canvasMode == com.dezzy.music.constants.CanvasMode.APPLE_WITH_FALLBACK || canvasMode == com.dezzy.music.constants.CanvasMode.SPOTIFY_WITH_FALLBACK,
                                        )
                                    }
                                canvasArtwork = fetched as CanvasArtwork?
                                canvasFetchedAtMs = now
                                if (fetched != null) {
                                    CanvasArtworkPlaybackCache.put(item.mediaId, fetched)
                                }
                                canvasFetchInFlight = false
                            }

                            Box(
                                modifier = Modifier
                                    .width(horizontalLazyGridItemWidth)
                                    .fillMaxSize()
                                    .padding(horizontal = PlayerHorizontalPadding)
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onTap = { onTap?.invoke() },
                                            onDoubleTap = { offset ->
                                                val currentPosition = playerConnection.player.currentPosition
                                                val duration = playerConnection.player.duration

                                                val now = System.currentTimeMillis()
                                                if (incrementalSeekSkipEnabled && now - lastTapTime < 1000) {
                                                    skipMultiplier++
                                                } else {
                                                    skipMultiplier = 1
                                                }
                                                lastTapTime = now

                                                val skipAmount = 5000 * skipMultiplier

                                                if ((layoutDirection == LayoutDirection.Ltr && offset.x < size.width / 2) ||
                                                    (layoutDirection == LayoutDirection.Rtl && offset.x > size.width / 2)
                                                ) {
                                                    playerConnection.player.seekTo(
                                                        (currentPosition - skipAmount).coerceAtLeast(0)
                                                    )
                                                    seekDirection =
                                                        context.getString(R.string.seek_backward_dynamic, skipAmount / 1000)
                                                } else {
                                                    playerConnection.player.seekTo(
                                                        (currentPosition + skipAmount).coerceAtMost(duration)
                                                    )
                                                    seekDirection = context.getString(R.string.seek_forward_dynamic, skipAmount / 1000)
                                                }
                                                // If a user double-tap skip lands on a new media item, restart presence manager to pick up artwork quickly
                                                if (com.dezzy.music.ui.screens.settings.DiscordPresenceManager.isRunning()) {
                                                    try { com.dezzy.music.ui.screens.settings.DiscordPresenceManager.restart() } catch (_: Exception) {}
                                                }

                                                showSeekEffect = true
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(minOf(containerMaxWidth, containerMaxHeight) - (PlayerHorizontalPadding * 2))
                                        .graphicsLayer {
                                            rotationZ = rotation
                                        }
                                        .clip(
                                            if (rotatingThumbnail) {
                                                MaterialShapes.Clover8Leaf.toShape()
                                            } else {
                                                RoundedCornerShape(thumbnailCornerRadius.dp)
                                            }
                                        )
                                        .graphicsLayer {
                                            rotationZ = -rotation
                                        }
                                ) {
                                    if (hidePlayerThumbnail) {
                                        // Show app logo when thumbnail is hidden
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.star),
                                                contentDescription = stringResource(R.string.hide_player_thumbnail),
                                                tint = textBackgroundColor.copy(alpha = 0.7f),
                                                modifier = Modifier.size(120.dp)
                                            )
                                        }
                                    } else {
                                        val primaryCanvasUrl = canvasArtwork?.animatedVertical ?: canvasArtwork?.animated
                                        val fallbackCanvasUrl = canvasArtwork?.videoUrlVertical ?: canvasArtwork?.videoUrl

                                        val shouldCropArtwork =
                                            cropThumbnailToSquare &&
                                                    playerDesignStyle != PlayerDesignStyle.V7

                                        AsyncImage(
                                            model = item.metadata?.thumbnailUrl?.highRes()
                                                ?: item.mediaMetadata.artworkUri?.toString(),
                                            contentDescription = null,
                                            contentScale = ContentScale.FillBounds,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .let { if (shouldCropArtwork) it.aspectRatio(1f) else it }
                                                .graphicsLayer(
                                                    renderEffect = BlurEffect(radiusX = 60f, radiusY = 60f),
                                                    alpha = 0.6f
                                                )
                                        )

                                        AsyncImage(
                                            model = item.metadata?.thumbnailUrl?.highRes()
                                                ?: item.mediaMetadata.artworkUri?.toString(),
                                            contentDescription = null,
                                            contentScale = if (shouldCropArtwork) ContentScale.Crop else ContentScale.Fit,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .let { if (shouldCropArtwork) it.aspectRatio(1f) else it }
                                        )

                                        if (shouldAnimateCanvas && (!primaryCanvasUrl.isNullOrBlank() || !fallbackCanvasUrl.isNullOrBlank())) {
                                            CanvasArtworkPlayer(
                                                primaryUrl = primaryCanvasUrl,
                                                fallbackUrl = fallbackCanvasUrl,
                                                isPlaying = isPlaying,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Seek effect
        LaunchedEffect(showSeekEffect) {
            if (showSeekEffect) {
                delay(1000)
                showSeekEffect = false
            }
        }

        AnimatedVisibility(
            visible = showSeekEffect,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text(
                text = seekDirection,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            )
        }
    }
}


/*
 * Copyright (C) OuterTune Project
 * Custom SnapLayoutInfoProvider idea belongs to OuterTune
 */

// SnapLayoutInfoProvider
@ExperimentalFoundationApi
fun SnapLayoutInfoProvider(
    lazyGridState: LazyGridState,
    positionInLayout: (layoutSize: Float, itemSize: Float) -> Float = { layoutSize, itemSize ->
        (layoutSize / 2f - itemSize / 2f)
    },
    velocityThreshold: Float = 1000f,
): SnapLayoutInfoProvider = object : SnapLayoutInfoProvider {
    private val layoutInfo: LazyGridLayoutInfo
        get() = lazyGridState.layoutInfo

    override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float = 0f
    override fun calculateSnapOffset(velocity: Float): Float {
        val bounds = calculateSnappingOffsetBounds()

        // Only snap when velocity exceeds threshold
        if (abs(velocity) < velocityThreshold) {
            if (abs(bounds.start) < abs(bounds.endInclusive))
                return bounds.start

            return bounds.endInclusive
        }

        return when {
            velocity < 0 -> bounds.start
            velocity > 0 -> bounds.endInclusive
            else -> 0f
        }
    }

    fun calculateSnappingOffsetBounds(): ClosedFloatingPointRange<Float> {
        var lowerBoundOffset = Float.NEGATIVE_INFINITY
        var upperBoundOffset = Float.POSITIVE_INFINITY

        layoutInfo.visibleItemsInfo.fastForEach { item ->
            val offset = calculateDistanceToDesiredSnapPosition(layoutInfo, item, positionInLayout)

            // Find item that is closest to the center
            if (offset <= 0 && offset > lowerBoundOffset) {
                lowerBoundOffset = offset
            }

            // Find item that is closest to center, but after it
            if (offset >= 0 && offset < upperBoundOffset) {
                upperBoundOffset = offset
            }
        }

        return lowerBoundOffset.rangeTo(upperBoundOffset)
    }
}

fun calculateDistanceToDesiredSnapPosition(
    layoutInfo: LazyGridLayoutInfo,
    item: LazyGridItemInfo,
    positionInLayout: (layoutSize: Float, itemSize: Float) -> Float,
): Float {
    val containerSize =
        layoutInfo.singleAxisViewportSize - layoutInfo.beforeContentPadding - layoutInfo.afterContentPadding

    val desiredDistance = positionInLayout(containerSize.toFloat(), item.size.width.toFloat())
    val itemCurrentPosition = item.offset.x.toFloat()

    return itemCurrentPosition - desiredDistance
}

private val LazyGridLayoutInfo.singleAxisViewportSize: Int
    get() = if (orientation == Orientation.Vertical) viewportSize.height else viewportSize.width