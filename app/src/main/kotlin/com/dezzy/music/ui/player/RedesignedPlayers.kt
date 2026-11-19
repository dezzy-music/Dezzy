@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.player

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
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
import com.dezzy.music.connect.ConnectState
import com.dezzy.music.connect.LinerConnectManager
import com.dezzy.music.constants.PlayerDesignStyle
import com.dezzy.music.constants.SliderStyle
import com.dezzy.music.constants.SliderStyleKey
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.extensions.toggleRepeatMode
import com.dezzy.music.models.MediaMetadata
import com.dezzy.music.playback.PlayerConnection
import com.dezzy.music.ui.component.BottomSheetState
import com.dezzy.music.ui.component.ConnectDeviceBottomSheet
import com.dezzy.music.ui.component.DevicesButton
import com.dezzy.music.ui.component.LocalSliderTrackBase
import com.dezzy.music.ui.component.rememberCastingRouteName
import com.dezzy.music.ui.utils.highRes
import com.dezzy.music.utils.AudioSpectrumEngine
import com.dezzy.music.utils.makeTimeString
import com.dezzy.music.utils.rememberEnumPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/*
 * Rediseño de cuatro estilos del reproductor (bocetos aprobados el 2026-11-19,
 * design/dezzy-android-reproductor-estilos-nuevos-bocetos.html):
 *
 *  - Cinemático (V4) = B «Póster»: carátula/canvas a sangre con zoom lento y grano.
 *  - Expresivo  (V6) = C «Tipografía viva»: título gigante que late con el audio.
 *  - Moderno    (V2) = A «Cristal»: fondo desenfocado y panel de cristal.
 *  - Mínimo     (V3) = A «Papel»: fondo plano, letra fina, iconos que se encienden.
 *
 * Reglas comunes: la barra de progreso es la que el usuario eligió en Apariencia
 * (SliderStyle) y aquí sólo se le cambian los colores; los cuatro funcionan en
 * claro y oscuro; no hay botón de Cast, sólo el de dispositivos.
 */

/** Estilos que dibuja este archivo (Player.kt los enruta aquí). */
internal val RedesignedPlayerStyles = setOf(
    PlayerDesignStyle.V2,
    PlayerDesignStyle.V3,
    PlayerDesignStyle.V4,
    PlayerDesignStyle.V6,
)

/**
 * Letra estrecha para los títulos del Póster y de Tipografía viva. El boceto usa
 * Roboto Flex con el eje `wdth` bajo; la app no trae ese archivo variable, así que
 * se usa Oswald (estrecha y variable en peso), que ya está en res/font.
 */
private val NarrowDisplayFamily = FontFamily(
    Font(R.font.oswald, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.oswald, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.oswald, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

@Composable
fun RedesignedStylePlayer(
    style: PlayerDesignStyle,
    state: BottomSheetState,
    navController: NavController,
    positionState: State<Long>,
    durationState: State<Long>,
    sliderPosition: Long?,
    onSliderPositionChange: (Long?) -> Unit,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onMenuClick: () -> Unit,
    onVisualizerClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = remember(state, navController, onQueueClick, onLyricsClick, onMenuClick, onVisualizerClick) {
        StyledPlayerActions(state, navController, onQueueClick, onLyricsClick, onMenuClick, onVisualizerClick)
    }
    val seek = StyledSeek(
        position = positionState.value,
        duration = durationState.value,
        sliderPosition = sliderPosition,
        onSliderPositionChange = onSliderPositionChange,
    )
    when (style) {
        PlayerDesignStyle.V4 -> CinematicPosterPlayer(actions, seek, modifier)
        PlayerDesignStyle.V6 -> ExpressiveTypePlayer(actions, seek, modifier)
        PlayerDesignStyle.V2 -> ModernGlassPlayer(actions, seek, modifier)
        PlayerDesignStyle.V3 -> MinimalPaperPlayer(actions, seek, modifier)
        else -> Unit
    }
}

// ─────────────────────────────── Piezas comunes ───────────────────────────────

private class StyledPlayerActions(
    val state: BottomSheetState,
    val navController: NavController,
    val onQueueClick: () -> Unit,
    val onLyricsClick: () -> Unit,
    val onMenuClick: () -> Unit,
    val onVisualizerClick: () -> Unit,
) {
    fun collapse() {
        state.collapseSoft()
    }

    fun openListenTogether() {
        state.collapseSoft()
        navController.navigate("settings/music_together")
    }

    fun openArtist(metadata: MediaMetadata?) {
        val id = metadata?.artists?.firstOrNull { it.id != null }?.id ?: return
        state.collapseSoft()
        navController.navigate("artist/$id")
    }

    fun openAlbum(metadata: MediaMetadata?) {
        val id = metadata?.album?.id ?: return
        state.collapseSoft()
        navController.navigate("album/$id")
    }
}

@Immutable
private data class StyledSeek(
    val position: Long,
    val duration: Long,
    val sliderPosition: Long?,
    val onSliderPositionChange: (Long?) -> Unit,
)

/** Tres tonos de la carátula (vivo, oscuro y claro), animados al cambiar de canción. */
@Immutable
private data class ArtTones(val vibrant: Color, val dark: Color, val light: Color)

@Composable
private fun rememberArtTones(thumbnailUrl: String?, fallback: Color): ArtTones {
    val context = LocalContext.current
    var target by remember { mutableStateOf<ArtTones?>(null) }
    LaunchedEffect(thumbnailUrl) {
        val url = thumbnailUrl ?: return@LaunchedEffect
        val tones = withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(128, 128)
                .allowHardware(false)
                .build()
            val bitmap = runCatching { context.imageLoader.execute(request) }.getOrNull()
                ?.image?.toBitmap() ?: return@withContext null
            val palette = Palette.from(bitmap).maximumColorCount(16).generate()
            val base = palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb ?: return@withContext null
            ArtTones(
                vibrant = Color(base),
                dark = Color(palette.darkVibrantSwatch?.rgb ?: palette.darkMutedSwatch?.rgb ?: base),
                light = Color(palette.lightVibrantSwatch?.rgb ?: palette.lightMutedSwatch?.rgb ?: base),
            )
        }
        if (tones != null) target = tones
    }
    val vibrant by animateColorAsState(target?.vibrant ?: fallback, tween(600), label = "artVibrant")
    val dark by animateColorAsState(target?.dark ?: fallback, tween(600), label = "artDark")
    val light by animateColorAsState(target?.light ?: fallback, tween(600), label = "artLight")
    return ArtTones(vibrant, dark, light)
}

/** Claro u oscuro según el tema de la app (respeta el ajuste de modo oscuro). */
@Composable
private fun isDarkPlayer(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

@Composable
private fun rememberCanvasUrls(metadata: MediaMetadata?, durationMs: Long): Pair<String?, String?> {
    val artwork = rememberPlaybackCanvas(
        mediaId = metadata?.id,
        title = metadata?.title,
        artist = metadata?.artists?.firstOrNull()?.name,
        albumTitle = metadata?.album?.title,
        durationMs = durationMs,
    )
    return artwork?.playbackUrls() ?: (null to null)
}

/** Carátula con el canvas de la canción encima cuando lo hay. */
@Composable
private fun StyledArtwork(
    metadata: MediaMetadata?,
    canvas: Pair<String?, String?>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        AsyncImage(
            model = metadata?.thumbnailUrl?.highRes(),
            contentDescription = metadata?.title?.let { "Carátula de $it" },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (canvas.first != null || canvas.second != null) {
            CanvasArtworkPlayer(
                primaryUrl = canvas.first,
                fallbackUrl = canvas.second,
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Deslizar a los lados cambia de canción (igual que en Clásico). */
private fun Modifier.swipeToSkip(playerConnection: PlayerConnection): Modifier =
    pointerInput(playerConnection) {
        val threshold = 96.dp.toPx()
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                when {
                    total <= -threshold -> playerConnection.seekToNext()
                    total >= threshold -> playerConnection.seekToPrevious()
                }
                total = 0f
            },
            onDragCancel = { total = 0f },
        ) { change, dragAmount ->
            change.consume()
            total += dragAmount
        }
    }

private fun PlayerConnection.playOrReplay() {
    if (player.playbackState == Player.STATE_ENDED) {
        player.seekTo(0, 0)
        player.playWhenReady = true
    } else {
        player.togglePlayPause()
    }
}

private fun playIcon(isPlaying: Boolean, playbackState: Int): Int = when {
    playbackState == Player.STATE_ENDED -> R.drawable.replay
    isPlaying -> R.drawable.pause
    else -> R.drawable.play
}

private fun repeatIcon(repeatMode: Int): Int =
    if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.repeat_one else R.drawable.repeat

private fun repeatDescription(repeatMode: Int): String = when (repeatMode) {
    Player.REPEAT_MODE_ONE -> "Repetir una"
    Player.REPEAT_MODE_ALL -> "Repetir todo"
    else -> "Repetir desactivado"
}

/**
 * Barra de progreso: SIEMPRE la [SliderStyle] elegida en Apariencia (mismo componente
 * [StyledPlaybackSlider]); cada estilo sólo le pasa sus colores. [trackBase] tiñe la
 * pista inactiva para que se vea también sobre fondos claros.
 */
@Composable
private fun StyledSeekBar(
    seek: StyledSeek,
    mediaId: String?,
    isPlaying: Boolean,
    activeColor: Color,
    trackBase: Color,
    timeColor: Color,
    modifier: Modifier = Modifier,
    timeStyle: TextStyle = MaterialTheme.typography.labelMedium,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val sliderStyle by rememberEnumPreference(SliderStyleKey, SliderStyle.Standard)
    val currentPos = seek.sliderPosition ?: seek.position
    val latestPos by rememberUpdatedState(currentPos)
    val duration = seek.duration
    val sliderMax = maxOf(duration.toFloat(), 1f)
    Column(modifier = modifier) {
        CompositionLocalProvider(LocalSliderTrackBase provides trackBase) {
            StyledPlaybackSlider(
                sliderStyle = sliderStyle,
                value = currentPos.toFloat().coerceIn(0f, sliderMax),
                valueRange = 0f..sliderMax,
                onValueChange = { seek.onSliderPositionChange(it.toLong()) },
                onValueChangeFinished = {
                    playerConnection.player.seekTo(latestPos)
                    seek.onSliderPositionChange(null)
                },
                activeColor = activeColor,
                isPlaying = isPlaying,
                songKey = mediaId.orEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(makeTimeString(currentPos), style = timeStyle, color = timeColor)
            Text(
                text = if (duration > 0L && duration != C.TIME_UNSET) makeTimeString(duration) else "",
                style = timeStyle,
                color = timeColor,
            )
        }
    }
}

@Composable
private fun StyledIcon(
    iconRes: Int,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            tint = if (enabled) tint else tint.copy(alpha = tint.alpha * 0.4f),
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Alterna el videoclip (sustituye a la píldora Canción/Video de la carátula antigua). */
@Composable
private fun VideoToggle(tint: Color, activeTint: Color, iconSize: Dp = 24.dp) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val active by playerConnection.videoModeActive.collectAsState()
    val loading by playerConnection.videoModeLoading.collectAsState()
    IconButton(
        onClick = {
            if (active) playerConnection.disableVideoMode()
            else if (!loading) playerConnection.enableVideoMode()
        },
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(iconSize - 4.dp),
                color = tint,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.slow_motion_video),
                contentDescription = if (active) "Volver a la canción" else "Ver video",
                tint = if (active) activeTint else tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** Texto que baja de tamaño (hasta [minSize]) mientras no quepa en [maxLines]. */
@Composable
private fun AutoShrinkText(
    text: String,
    maxSize: TextUnit,
    minSize: TextUnit,
    maxLines: Int,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    lineHeightRatio: Float = 0.92f,
) {
    var size by remember(text, maxSize) { mutableStateOf(maxSize) }
    var ready by remember(text, maxSize) { mutableStateOf(false) }
    Text(
        text = text,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = style.copy(fontSize = size, lineHeight = size * lineHeightRatio),
        onTextLayout = { result ->
            if (result.hasVisualOverflow && size.value > minSize.value) {
                size = (size.value * 0.9f).coerceAtLeast(minSize.value).sp
            } else {
                ready = true
            }
        },
        modifier = modifier.drawWithContent { if (ready) drawContent() },
    )
}

private fun codecLabel(mimeType: String?): String? {
    val codec = mimeType?.substringAfter("/")?.uppercase() ?: return null
    return when {
        codec.contains("FLAC") || codec.contains("ALAC") -> "FLAC"
        codec.contains("OPUS") -> "OPUS"
        codec.contains("MP4A") || codec.contains("AAC") -> "AAC"
        codec.contains("VORBIS") -> "Vorbis"
        codec.contains("MPEG") || codec.contains("MP3") -> "MP3"
        else -> codec.takeIf { it.isNotBlank() }
    }
}

/** Disposición horizontal común: carátula a la izquierda y controles con scroll a la derecha. */
@Composable
private fun LandscapeSplit(
    art: @Composable (Modifier) -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        art(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(24.dp),
        )
        Column(
            modifier = Modifier
                .weight(1.2f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            content = controls,
        )
    }
}

// ─────────────────────────── Cinemático · B «Póster» ───────────────────────────

@Composable
private fun CinematicPosterPlayer(
    actions: StyledPlayerActions,
    seek: StyledSeek,
    modifier: Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val metadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val playbackState by playerConnection.playbackState.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val shuffle by playerConnection.shuffleModeEnabled.collectAsState()
    val liked by playerConnection.currentSongLiked.collectAsState()
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val dark = isDarkPlayer()
    // En claro el degradado termina en blanco y la letra pasa a tinta.
    val base = if (dark) Color.Black else Color.White
    val ink = if (dark) Color.White else Color(0xFF111111)
    val inkDim = ink.copy(alpha = 0.66f)
    val tones = rememberArtTones(metadata?.thumbnailUrl, MaterialTheme.colorScheme.primary)
    val creditColor = if (dark) lerp(tones.light, Color(0xFFFFE9B8), 0.6f) else lerp(tones.dark, Color.Black, 0.25f)
    val canvas = rememberCanvasUrls(metadata, seek.duration)

    // Zoom lento (Ken Burns): se lee en la fase de dibujo para no recomponer cada frame.
    val kenBurns = rememberInfiniteTransition(label = "posterZoom")
    val zoom = kenBurns.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(18_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "posterZoomValue",
    )

    val year = currentSong?.song?.year ?: currentSong?.album?.year
    val artistName = metadata?.artists?.joinToString { it.name }.orEmpty()

    val topBar: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StyledIcon(R.drawable.expand_more, "Contraer reproductor", ink, actions::collapse)
            Spacer(Modifier.weight(1f))
            VideoToggle(tint = ink, activeTint = creditColor)
            DevicesButton(tint = ink, activeTint = creditColor, onOpenListenTogether = actions::openListenTogether)
            StyledIcon(R.drawable.more_vert, "Más opciones", ink, actions.onMenuClick)
        }
    }

    val info: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            if (artistName.isNotBlank()) {
                Text(
                    text = "$artistName presenta".uppercase(),
                    fontFamily = NarrowDisplayFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    letterSpacing = 4.sp,
                    color = creditColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(role = Role.Button) { actions.openArtist(metadata) },
                )
                Spacer(Modifier.height(6.dp))
            }
            AutoShrinkText(
                text = metadata?.title.orEmpty().uppercase(),
                maxSize = 52.sp,
                minSize = 26.sp,
                maxLines = 3,
                style = TextStyle(
                    fontFamily = NarrowDisplayFamily,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                ),
                color = ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { actions.openAlbum(metadata) },
            )
            Spacer(Modifier.height(10.dp))
            val credits = listOfNotNull(
                metadata?.album?.title?.takeIf { it.isNotBlank() },
                year?.toString(),
                seek.duration.takeIf { it > 0L && it != C.TIME_UNSET }?.let { makeTimeString(it) },
            ).joinToString(" · ")
            if (credits.isNotBlank()) {
                Text(
                    text = credits.uppercase(),
                    fontFamily = NarrowDisplayFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    letterSpacing = 3.sp,
                    color = inkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(18.dp))
            StyledSeekBar(
                seek = seek,
                mediaId = metadata?.id,
                isPlaying = isPlaying,
                activeColor = ink,
                trackBase = ink,
                timeColor = inkDim,
            )
        }
    }

    val controls: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StyledIcon(
                R.drawable.shuffle,
                if (shuffle) "Aleatorio activado" else "Aleatorio desactivado",
                if (shuffle) ink else ink.copy(alpha = 0.5f),
                { playerConnection.player.shuffleModeEnabled = !shuffle },
                iconSize = 22.dp,
            )
            StyledIcon(R.drawable.skip_previous, "Anterior", ink, playerConnection::seekToPrevious, Modifier.size(56.dp), 36.dp)
            // Play blanco sólido (negro en claro): el único elemento con relleno.
            Surface(
                onClick = { playerConnection.playOrReplay() },
                shape = CircleShape,
                color = ink,
                contentColor = base,
                modifier = Modifier.size(70.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (playbackState == Player.STATE_BUFFERING) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), color = base, strokeWidth = 3.dp)
                    } else {
                        Icon(
                            painter = painterResource(playIcon(isPlaying, playbackState)),
                            contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
            StyledIcon(R.drawable.skip_next, "Siguiente", ink, playerConnection::seekToNext, Modifier.size(56.dp), 36.dp)
            StyledIcon(
                repeatIcon(repeatMode),
                repeatDescription(repeatMode),
                if (repeatMode != Player.REPEAT_MODE_OFF) ink else ink.copy(alpha = 0.5f),
                { playerConnection.player.toggleRepeatMode() },
                iconSize = 22.dp,
            )
        }
    }

    val secondary: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            StyledIcon(
                if (liked) R.drawable.favorite else R.drawable.favorite_border,
                if (liked) "Quitar de Me gusta" else "Me gusta",
                if (liked) creditColor else inkDim,
                playerConnection::toggleLike,
                iconSize = 22.dp,
            )
            StyledIcon(R.drawable.lyrics, "Letras", inkDim, actions.onLyricsClick, iconSize = 22.dp)
            StyledIcon(R.drawable.auto_awesome, "Visualizador", inkDim, actions.onVisualizerClick, iconSize = 22.dp)
            StyledIcon(R.drawable.queue_music, "Cola", inkDim, actions.onQueueClick, iconSize = 22.dp)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(base),
    ) {
        // Carátula / canvas a sangre con zoom lento.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(if (isLandscape) 1f else 0.72f)
                .clipToBounds()
                .swipeToSkip(playerConnection),
        ) {
            StyledArtwork(
                metadata = metadata,
                canvas = canvas,
                isPlaying = isPlaying,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom.value
                        scaleY = zoom.value
                    },
            )
        }
        FilmGrain(
            color = if (dark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.05f),
            modifier = Modifier.fillMaxSize(),
        )
        // Degradado inferior hacia negro (o blanco en claro) y velo superior para la barra de estado.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (isLandscape) {
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.3f to Color.Transparent,
                            0.55f to base.copy(alpha = 0.9f),
                            1f to base,
                        )
                    } else {
                        Brush.verticalGradient(
                            0f to base.copy(alpha = if (dark) 0.6f else 0.55f),
                            0.2f to Color.Transparent,
                            0.4f to Color.Transparent,
                            0.7f to base.copy(alpha = 0.92f),
                            1f to base,
                        )
                    },
                ),
        )

        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                Spacer(Modifier.weight(1f))
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    topBar()
                    info()
                    Spacer(Modifier.height(8.dp))
                    controls()
                    Spacer(Modifier.height(8.dp))
                    secondary()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(bottom = 8.dp),
            ) {
                topBar()
                Spacer(Modifier.weight(1f))
                info()
                Spacer(Modifier.height(8.dp))
                controls()
                Spacer(Modifier.height(14.dp))
                secondary()
            }
        }
    }
}

/** Grano de película sutil: puntos aleatorios que saltan ~12 veces por segundo. */
@Composable
private fun FilmGrain(color: Color, modifier: Modifier = Modifier) {
    val points = remember { List(900) { Offset(Random.nextFloat(), Random.nextFloat()) } }
    val transition = rememberInfiniteTransition(label = "filmGrain")
    val frameValue = transition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(tween(660, easing = LinearEasing)),
        label = "filmGrainFrame",
    )
    // Sólo se redibuja cuando cambia el fotograma entero, no a 60 fps.
    val frame by remember { derivedStateOf { frameValue.value.toInt() } }
    Canvas(modifier = modifier) {
        val f = frame
        val shiftX = (f * 37 % 97) / 97f
        val shiftY = (f * 53 % 89) / 89f
        drawPoints(
            points = points.map {
                Offset(((it.x + shiftX) % 1f) * size.width, ((it.y + shiftY) % 1f) * size.height)
            },
            pointMode = PointMode.Points,
            color = color,
            strokeWidth = 1.4.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

// ─────────────────────── Expresivo · C «Tipografía viva» ───────────────────────

@Composable
private fun ExpressiveTypePlayer(
    actions: StyledPlayerActions,
    seek: StyledSeek,
    modifier: Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val metadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val playbackState by playerConnection.playbackState.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val shuffle by playerConnection.shuffleModeEnabled.collectAsState()
    val liked by playerConnection.currentSongLiked.collectAsState()
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)
    val currentFormat by playerConnection.currentFormat.collectAsState(initial = null)
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val dark = isDarkPlayer()
    val tones = rememberArtTones(metadata?.thumbnailUrl, MaterialTheme.colorScheme.primary)
    // Oscuro: fondo profundo del color de la carátula y acento pastel (como el boceto).
    // Claro: tono claro de la carátula y el título en su color fuerte.
    val background = if (dark) lerp(Color(0xFF0B0F10), tones.dark, 0.45f) else lerp(Color.White, tones.light, 0.32f)
    val accent = if (dark) lerp(tones.light, Color.White, 0.25f) else lerp(tones.vibrant, Color.Black, 0.38f)
    val onAccent = if (dark) background else Color.White
    val dim = if (dark) Color.White.copy(alpha = 0.72f) else lerp(accent, Color.Black, 0.35f).copy(alpha = 0.8f)
    val canvas = rememberCanvasUrls(metadata, seek.duration)

    // El ancho del título late con la energía de los graves.
    val energy = rememberAudioEnergy(active = actions.state.isExpanded, isPlaying = isPlaying)

    val year = currentSong?.song?.year ?: currentSong?.album?.year
    val quality = codecLabel(currentFormat?.mimeType)

    val pill: @Composable (Modifier) -> Unit = { pillModifier ->
        StyledArtwork(
            metadata = metadata,
            canvas = canvas,
            isPlaying = isPlaying,
            modifier = pillModifier
                .clip(RoundedCornerShape(percent = 50))
                .background(accent.copy(alpha = 0.15f))
                .swipeToSkip(playerConnection),
        )
    }

    val topBar: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StyledIcon(R.drawable.expand_more, "Contraer reproductor", dim, actions::collapse)
            Spacer(Modifier.weight(1f))
            VideoToggle(tint = dim, activeTint = accent)
            StyledIcon(R.drawable.more_vert, "Más opciones", dim, actions.onMenuClick)
        }
    }

    val nowInfo: @Composable ColumnScope.() -> Unit = {
        Text(
            text = "AHORA",
            fontSize = 12.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
        Spacer(Modifier.height(4.dp))
        metadata?.album?.title?.takeIf { it.isNotBlank() }?.let { album ->
            Text(
                text = album,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                color = dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(role = Role.Button) { actions.openAlbum(metadata) },
            )
        }
        val details = listOfNotNull(year?.toString(), quality).joinToString(" · ")
        if (details.isNotBlank()) {
            Text(text = details, fontSize = 15.sp, lineHeight = 19.sp, color = dim, maxLines = 1)
        }
    }

    val title: @Composable () -> Unit = {
        AutoShrinkText(
            text = metadata?.title.orEmpty().uppercase(),
            maxSize = 72.sp,
            minSize = 34.sp,
            maxLines = 3,
            style = TextStyle(
                fontFamily = NarrowDisplayFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.5).sp,
            ),
            color = accent,
            lineHeightRatio = 0.86f,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                // Se lee en dibujo: late sin recomponer. Nunca pasa del ancho real (máx. 1).
                .graphicsLayer {
                    scaleX = 0.9f + 0.1f * energy.value
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
        )
        Text(
            text = metadata?.artists?.joinToString { it.name }.orEmpty(),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 20.dp, end = 20.dp, top = 4.dp)
                .clickable(role = Role.Button) { actions.openArtist(metadata) },
        )
    }

    val transport: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            StyledSeekBar(
                seek = seek,
                mediaId = metadata?.id,
                isPlaying = isPlaying,
                activeColor = accent,
                trackBase = accent,
                timeColor = dim,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Play ancho: ocupa todo el espacio libre.
                Surface(
                    onClick = { playerConnection.playOrReplay() },
                    shape = RoundedCornerShape(36.dp),
                    color = accent,
                    contentColor = onAccent,
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (playbackState == Player.STATE_BUFFERING) {
                            CircularProgressIndicator(modifier = Modifier.size(36.dp), color = onAccent, strokeWidth = 3.dp)
                        } else {
                            Icon(
                                painter = painterResource(playIcon(isPlaying, playbackState)),
                                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                modifier = Modifier.size(44.dp),
                            )
                        }
                    }
                }
                ExpressiveSquareButton(R.drawable.skip_previous, "Anterior", accent, playerConnection::seekToPrevious)
                ExpressiveSquareButton(R.drawable.skip_next, "Siguiente", accent, playerConnection::seekToNext)
            }
        }
    }

    val secondary: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val small = Modifier.size(44.dp)
            StyledIcon(
                R.drawable.shuffle,
                if (shuffle) "Aleatorio activado" else "Aleatorio desactivado",
                if (shuffle) accent else accent.copy(alpha = 0.5f),
                { playerConnection.player.shuffleModeEnabled = !shuffle },
                small,
                22.dp,
            )
            StyledIcon(
                if (liked) R.drawable.favorite else R.drawable.favorite_border,
                if (liked) "Quitar de Me gusta" else "Me gusta",
                accent,
                playerConnection::toggleLike,
                small,
                22.dp,
            )
            StyledIcon(R.drawable.lyrics, "Letras", accent, actions.onLyricsClick, small, 22.dp)
            StyledIcon(R.drawable.auto_awesome, "Visualizador", accent, actions.onVisualizerClick, small, 22.dp)
            DevicesButton(
                tint = accent,
                iconSize = 22.dp,
                onOpenListenTogether = actions::openListenTogether,
                modifier = small,
            )
            StyledIcon(R.drawable.queue_music, "Cola", accent, actions.onQueueClick, small, 22.dp)
            StyledIcon(
                repeatIcon(repeatMode),
                repeatDescription(repeatMode),
                if (repeatMode != Player.REPEAT_MODE_OFF) accent else accent.copy(alpha = 0.5f),
                { playerConnection.player.toggleRepeatMode() },
                small,
                22.dp,
            )
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(background),
    ) {
        if (isLandscape) {
            LandscapeSplit(
                art = { artModifier ->
                    Box(artModifier, contentAlignment = Alignment.Center) {
                        pill(
                            Modifier
                                .fillMaxHeight()
                                .aspectRatio(0.57f),
                        )
                    }
                },
            ) {
                topBar()
                Column(modifier = Modifier.padding(horizontal = 20.dp), content = nowInfo)
                Spacer(Modifier.height(8.dp))
                title()
                Spacer(Modifier.height(16.dp))
                transport()
                Spacer(Modifier.height(10.dp))
                secondary()
            }
        } else {
            // La píldora se encoge en pantallas bajas para no empujar los controles fuera.
            val pillHeight = (maxHeight * 0.26f).coerceIn(140.dp, 210.dp)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(bottom = 8.dp),
            ) {
                topBar()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    pill(Modifier.size(width = pillHeight * 0.57f, height = pillHeight))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = 6.dp),
                        content = nowInfo,
                    )
                }
                Spacer(Modifier.height(22.dp))
                title()
                Spacer(Modifier.weight(1f))
                transport()
                Spacer(Modifier.height(16.dp))
                secondary()
            }
        }
    }
}

@Composable
private fun ExpressiveSquareButton(iconRes: Int, description: String, accent: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = accent.copy(alpha = 0.18f),
        contentColor = accent,
        modifier = Modifier.size(72.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = description,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/**
 * Energía de los graves 0..1 con el mismo motor de espectro del visualizador
 * ([AudioSpectrumEngine]). Sólo se usa si el permiso de micrófono YA está concedido
 * (aquí no se pide); sin permiso, un latido suave a ~108 ppm mientras suena.
 */
@Composable
private fun rememberAudioEnergy(active: Boolean, isPlaying: Boolean): State<Float> {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val energy = remember { mutableFloatStateOf(0f) }
    val hasPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val engine = remember { AudioSpectrumEngine() }
    var running by remember { mutableStateOf(false) }

    DisposableEffect(active, hasPermission) {
        if (active && hasPermission && playerConnection != null) {
            val sessionId = runCatching { playerConnection.player.audioSessionId }.getOrDefault(0)
            running = sessionId > 0 && engine.start(sessionId)
        }
        onDispose {
            engine.release()
            running = false
        }
    }

    LaunchedEffect(active, isPlaying, running) {
        if (!active) {
            energy.floatValue = 0f
            return@LaunchedEffect
        }
        var time = 0f
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = now
                time += dt
                val target = when {
                    !isPlaying -> 0f
                    running -> {
                        var sum = 0f
                        for (i in 0 until 6) sum += engine.bands[i]
                        (sum / 6f * 1.6f).coerceIn(0f, 1f)
                    }
                    else -> (0.5f + 0.5f * sin(time * 2f * PI.toFloat() * 1.8f)) * 0.6f
                }
                // Ataque rápido y caída suave, como un golpe de bombo.
                val k = if (target > energy.floatValue) 0.45f else 0.08f
                energy.floatValue += (target - energy.floatValue) * k
            }
        }
    }
    return energy
}

// ───────────────────────────── Moderno · A «Cristal» ─────────────────────────────

@Composable
private fun ModernGlassPlayer(
    actions: StyledPlayerActions,
    seek: StyledSeek,
    modifier: Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val metadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val playbackState by playerConnection.playbackState.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val shuffle by playerConnection.shuffleModeEnabled.collectAsState()
    val liked by playerConnection.currentSongLiked.collectAsState()
    val queueTitle by playerConnection.queueTitle.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val dark = isDarkPlayer()
    val tones = rememberArtTones(metadata?.thumbnailUrl, MaterialTheme.colorScheme.primary)
    // Cristal oscuro ↔ blanco translúcido.
    val ink = if (dark) Color.White else Color(0xFF15141A)
    val inkDim = ink.copy(alpha = 0.7f)
    val onInk = if (dark) Color(0xFF111111) else Color.White
    val glass = if (dark) Color.White.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.6f)
    val glassBorder = if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.85f)
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val canvas = rememberCanvasUrls(metadata, seek.duration)
    val artShape = RoundedCornerShape(24.dp)

    val artwork: @Composable (Modifier) -> Unit = { boxModifier ->
        Box(boxModifier, contentAlignment = Alignment.Center) {
            StyledArtwork(
                metadata = metadata,
                canvas = canvas,
                isPlaying = isPlaying,
                modifier = Modifier
                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    // Sombra del color de la carátula, no gris.
                    .shadow(36.dp, artShape, ambientColor = tones.vibrant, spotColor = tones.vibrant)
                    .clip(artShape)
                    .swipeToSkip(playerConnection),
            )
        }
    }

    val topBar: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StyledIcon(R.drawable.expand_more, "Contraer reproductor", ink, actions::collapse)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("De la lista", fontSize = 12.sp, lineHeight = 15.sp, color = inkDim, maxLines = 1)
                Text(
                    text = queueTitle ?: "Tu música",
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            VideoToggle(tint = ink, activeTint = tones.light)
            StyledIcon(R.drawable.more_vert, "Más opciones", ink, actions.onMenuClick)
        }
    }

    val panel: @Composable (Modifier) -> Unit = { panelModifier ->
        val shape = RoundedCornerShape(28.dp)
        Column(
            modifier = panelModifier
                .clip(shape)
                .background(glass)
                .border(1.dp, glassBorder, shape)
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = metadata?.title.orEmpty(),
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(role = Role.Button) { actions.openAlbum(metadata) },
                    )
                    Text(
                        text = metadata?.artists?.joinToString { it.name }.orEmpty(),
                        fontSize = 15.sp,
                        color = inkDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(role = Role.Button) { actions.openArtist(metadata) },
                    )
                }
                StyledIcon(
                    if (liked) R.drawable.favorite else R.drawable.favorite_border,
                    if (liked) "Quitar de Me gusta" else "Me gusta",
                    if (liked) lerp(tones.vibrant, ink, 0.15f) else ink,
                    playerConnection::toggleLike,
                    iconSize = 26.dp,
                )
            }
            Spacer(Modifier.height(12.dp))
            StyledSeekBar(
                seek = seek,
                mediaId = metadata?.id,
                isPlaying = isPlaying,
                activeColor = ink,
                trackBase = ink,
                timeColor = inkDim,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StyledIcon(
                    R.drawable.shuffle,
                    if (shuffle) "Aleatorio activado" else "Aleatorio desactivado",
                    if (shuffle) ink else inkDim.copy(alpha = 0.55f),
                    { playerConnection.player.shuffleModeEnabled = !shuffle },
                    iconSize = 22.dp,
                )
                StyledIcon(R.drawable.skip_previous, "Anterior", ink, playerConnection::seekToPrevious, Modifier.size(56.dp), 36.dp)
                Surface(
                    onClick = { playerConnection.playOrReplay() },
                    shape = CircleShape,
                    color = ink,
                    contentColor = onInk,
                    modifier = Modifier.size(64.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (playbackState == Player.STATE_BUFFERING) {
                            CircularProgressIndicator(modifier = Modifier.size(30.dp), color = onInk, strokeWidth = 3.dp)
                        } else {
                            Icon(
                                painter = painterResource(playIcon(isPlaying, playbackState)),
                                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                }
                StyledIcon(R.drawable.skip_next, "Siguiente", ink, playerConnection::seekToNext, Modifier.size(56.dp), 36.dp)
                StyledIcon(
                    repeatIcon(repeatMode),
                    repeatDescription(repeatMode),
                    if (repeatMode != Player.REPEAT_MODE_OFF) ink else inkDim.copy(alpha = 0.55f),
                    { playerConnection.player.toggleRepeatMode() },
                    iconSize = 22.dp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DevicesPill(
                    contentColor = inkDim,
                    activeColor = ink,
                    containerColor = ink.copy(alpha = 0.1f),
                    onOpenListenTogether = actions::openListenTogether,
                )
                Spacer(Modifier.weight(1f))
                StyledIcon(R.drawable.lyrics, "Letras", inkDim, actions.onLyricsClick, Modifier.size(44.dp), 22.dp)
                StyledIcon(R.drawable.auto_awesome, "Visualizador", inkDim, actions.onVisualizerClick, Modifier.size(44.dp), 22.dp)
                StyledIcon(R.drawable.queue_music, "Cola", inkDim, actions.onQueueClick, Modifier.size(44.dp), 22.dp)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (dark) Color(0xFF0D0D12) else Color(0xFFF3F2F6)),
    ) {
        // Fondo: la carátula muy desenfocada (Android 12+). Antes, un velo con su color.
        AsyncImage(
            model = metadata?.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.3f
                    scaleY = 1.3f
                }
                .then(if (canBlur) Modifier.blur(60.dp) else Modifier)
                .alpha(if (canBlur) 0.7f else 0.3f),
        )
        if (!canBlur) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(tones.vibrant.copy(alpha = 0.45f), Color.Transparent))),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (dark) Color(0xFF0A080E).copy(alpha = 0.35f) else Color.White.copy(alpha = 0.4f)),
        )

        if (isLandscape) {
            LandscapeSplit(art = artwork) {
                topBar()
                Spacer(Modifier.height(8.dp))
                panel(Modifier.padding(horizontal = 14.dp).fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(bottom = 8.dp),
            ) {
                topBar()
                artwork(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 20.dp),
                )
                panel(Modifier.padding(horizontal = 14.dp).fillMaxWidth())
            }
        }
    }
}

/** Botón de dispositivos con texto: dice dónde suena («Este teléfono», el Chromecast o el PC). */
@Composable
private fun DevicesPill(
    contentColor: Color,
    activeColor: Color,
    containerColor: Color,
    onOpenListenTogether: () -> Unit,
) {
    val context = LocalContext.current
    val castName = rememberCastingRouteName()
    val linerManager = remember { LinerConnectManager.getInstance(context) }
    val linerState by linerManager.connectionState.collectAsState()
    val linerName by linerManager.connectedDeviceName.collectAsState()
    val desktopName = linerName?.takeIf { linerState == ConnectState.CONNECTED && it.isNotBlank() }
    val label = castName ?: desktopName ?: "Este teléfono"
    val active = castName != null || desktopName != null
    var showSheet by remember { mutableStateOf(false) }

    Surface(
        onClick = { showSheet = true },
        shape = CircleShape,
        color = containerColor,
        contentColor = if (active) activeColor else contentColor,
        modifier = Modifier.semantics { contentDescription = "Conectar a un dispositivo. Suena en $label" },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .padding(start = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(if (castName != null) R.drawable.cast_connected else R.drawable.devices),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
        }
    }

    if (showSheet) {
        ConnectDeviceBottomSheet(
            onDismissRequest = { showSheet = false },
            onOpenListenTogether = {
                showSheet = false
                onOpenListenTogether()
            },
        )
    }
}

// ────────────────────────────── Mínimo · A «Papel» ──────────────────────────────

@Composable
private fun MinimalPaperPlayer(
    actions: StyledPlayerActions,
    seek: StyledSeek,
    modifier: Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val metadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val playbackState by playerConnection.playbackState.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val shuffle by playerConnection.shuffleModeEnabled.collectAsState()
    val liked by playerConnection.currentSongLiked.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val dark = isDarkPlayer()
    // Claro = crema; oscuro = gris casi negro. Sin degradados ni sombras.
    val paper = if (dark) Color(0xFF161616) else Color(0xFFF6F3EE)
    val ink = if (dark) Color(0xFFECE9E4) else Color(0xFF141414)
    // Los iconos de control se dibujan algo más claros para acercarse al peso fino del boceto.
    val controlInk = ink.copy(alpha = 0.85f)
    val canvas = rememberCanvasUrls(metadata, seek.duration)

    // Los iconos secundarios están atenuados y se encienden unos segundos al tocar la pantalla.
    var touchTick by remember { mutableIntStateOf(0) }
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(touchTick) {
        if (touchTick > 0) {
            lit = true
            delay(4_000)
            lit = false
        }
    }
    val secondaryAlpha by animateFloatAsState(
        targetValue = if (lit) 1f else 0.35f,
        animationSpec = tween(if (lit) 150 else 600),
        label = "paperSecondaryAlpha",
    )

    val topBar: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StyledIcon(R.drawable.expand_more, "Contraer reproductor", controlInk, actions::collapse)
            Spacer(Modifier.weight(1f))
            VideoToggle(tint = controlInk, activeTint = ink)
            DevicesButton(tint = controlInk, activeTint = ink, onOpenListenTogether = actions::openListenTogether)
            StyledIcon(R.drawable.more_vert, "Más opciones", controlInk, actions.onMenuClick)
        }
    }

    val cover: @Composable (Modifier) -> Unit = { coverModifier ->
        StyledArtwork(
            metadata = metadata,
            canvas = canvas,
            isPlaying = isPlaying,
            modifier = coverModifier
                .clip(RoundedCornerShape(4.dp))
                .background(ink.copy(alpha = 0.06f))
                .swipeToSkip(playerConnection),
        )
    }

    val info: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = metadata?.title.orEmpty(),
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Light,
                color = ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.clickable(role = Role.Button) { actions.openAlbum(metadata) },
            )
            Text(
                text = metadata?.artists?.joinToString { it.name }.orEmpty(),
                fontSize = 14.sp,
                color = ink.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable(role = Role.Button) { actions.openArtist(metadata) },
            )
        }
    }

    val transport: @Composable () -> Unit = {
        StyledSeekBar(
            seek = seek,
            mediaId = metadata?.id,
            isPlaying = isPlaying,
            activeColor = ink,
            trackBase = ink,
            timeColor = ink.copy(alpha = 0.45f),
            timeStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            modifier = Modifier.padding(horizontal = 48.dp),
        )
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StyledIcon(R.drawable.skip_previous, "Anterior", controlInk, playerConnection::seekToPrevious, Modifier.size(52.dp), 28.dp)
            IconButton(onClick = { playerConnection.playOrReplay() }, modifier = Modifier.size(72.dp)) {
                if (playbackState == Player.STATE_BUFFERING) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), color = controlInk, strokeWidth = 1.5.dp)
                } else {
                    Icon(
                        painter = painterResource(playIcon(isPlaying, playbackState)),
                        contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                        tint = controlInk,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
            StyledIcon(R.drawable.skip_next, "Siguiente", controlInk, playerConnection::seekToNext, Modifier.size(52.dp), 28.dp)
        }
    }

    val secondary: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .alpha(secondaryAlpha),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StyledIcon(
                R.drawable.shuffle,
                if (shuffle) "Aleatorio activado" else "Aleatorio desactivado",
                if (shuffle) ink else ink.copy(alpha = 0.5f),
                { playerConnection.player.shuffleModeEnabled = !shuffle },
                iconSize = 20.dp,
            )
            StyledIcon(
                if (liked) R.drawable.favorite else R.drawable.favorite_border,
                if (liked) "Quitar de Me gusta" else "Me gusta",
                ink,
                playerConnection::toggleLike,
                iconSize = 20.dp,
            )
            StyledIcon(R.drawable.lyrics, "Letras", ink, actions.onLyricsClick, iconSize = 20.dp)
            StyledIcon(R.drawable.auto_awesome, "Visualizador", ink, actions.onVisualizerClick, iconSize = 20.dp)
            StyledIcon(R.drawable.queue_music, "Cola", ink, actions.onQueueClick, iconSize = 20.dp)
            StyledIcon(
                repeatIcon(repeatMode),
                repeatDescription(repeatMode),
                if (repeatMode != Player.REPEAT_MODE_OFF) ink else ink.copy(alpha = 0.5f),
                { playerConnection.player.toggleRepeatMode() },
                iconSize = 20.dp,
            )
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(paper)
            // Cualquier toque (sin consumirlo) enciende los iconos secundarios.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touchTick++
                }
            },
    ) {
        if (isLandscape) {
            LandscapeSplit(
                art = { artModifier ->
                    Box(artModifier, contentAlignment = Alignment.Center) {
                        cover(Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true))
                    }
                },
            ) {
                topBar()
                Spacer(Modifier.height(8.dp))
                info()
                Spacer(Modifier.height(24.dp))
                transport()
                Spacer(Modifier.height(16.dp))
                secondary()
            }
        } else {
            // Carátula mediana: 240 dp en un teléfono normal, menos si la pantalla es baja.
            val coverSize = minOf(240.dp, maxWidth - 96.dp, maxHeight * 0.34f)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                topBar()
                Spacer(Modifier.weight(0.9f))
                cover(Modifier.size(coverSize))
                Spacer(Modifier.weight(0.7f))
                info()
                Spacer(Modifier.height(36.dp))
                transport()
                Spacer(Modifier.weight(1f))
                secondary()
            }
        }
    }
}
