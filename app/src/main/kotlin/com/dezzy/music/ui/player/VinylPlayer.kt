/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Dezzy Style: tornamesa de vinilo (inspirada en MDVinyl, superándola):
 *  - Tema y textura del vinilo DINÁMICOS desde la paleta de la carátula
 *    (además de los temas fijos y el shuffle por canción).
 *  - Gestos sobre el vinilo: tocar = play/pausa, deslizar horizontal =
 *    canción anterior/siguiente, mantener presionado = guardar el vinilo
 *    con su carátula como imagen en Imágenes/Dezzy Vinyl.
 *  - Velocidad de giro configurable. El disco y el brazo se animan con la
 *    reproducción; el splatter es determinista por canción.
 */

package com.dezzy.music.ui.player

import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.constants.DezzyVinylSpeedKey
import com.dezzy.music.constants.DezzyVinylStyleKey
import com.dezzy.music.constants.DezzyVinylThemeKey
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.ui.component.BottomSheetState
import com.dezzy.music.utils.makeTimeString
import com.dezzy.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.random.Random

// ── Temas de color fijos (como los de MDVinyl); "dynamic" se genera aparte ──

private data class VinylTheme(
    val key: String,
    val background: Color,
    val content: Color,
    val subtle: Color,
    val button: Color,
)

private val VinylThemes = listOf(
    VinylTheme("cream", Color(0xFFEFECE3), Color(0xFF141414), Color(0xFF8C8A82), Color(0xFFF8F6EF)),
    VinylTheme("orange", Color(0xFFF0A422), Color(0xFF1A1206), Color(0xFF6E5312), Color(0xFFF6BC55)),
    VinylTheme("gray", Color(0xFF555555), Color(0xFFF2F2F2), Color(0xFFBBBBBB), Color(0xFF676767)),
    VinylTheme("silver", Color(0xFFC9CBCD), Color(0xFF17181A), Color(0xFF5F6265), Color(0xFFDCDEE0)),
    VinylTheme("black", Color(0xFF151515), Color(0xFFF2F2F2), Color(0xFF9A9A9A), Color(0xFF242424)),
)

// ── Estilos de vinilo fijos; "dynamic" se tiñe con la carátula ──

private data class VinylStyleSpec(
    val key: String,
    val base: Color,
    val groove: Color,
    val splat: Color,
    val splatter: Boolean,
)

private val VinylStyles = listOf(
    VinylStyleSpec("classic", Color(0xFF17171A), Color(0xFFFFFFFF).copy(alpha = 0.06f), Color.Transparent, false),
    VinylStyleSpec("gray", Color(0xFF9B9B9B), Color(0xFF000000).copy(alpha = 0.09f), Color.Transparent, false),
    VinylStyleSpec("splatter_light", Color(0xFFE9EDE7), Color(0xFF000000).copy(alpha = 0.05f), Color(0xFF14140F), true),
    VinylStyleSpec("splatter_gray", Color(0xFF8E8E8E), Color(0xFF000000).copy(alpha = 0.07f), Color(0xFFF4F4F4), true),
)

private data class Splat(
    val angle: Float,
    val startFrac: Float,
    val lenFrac: Float,
    val widthPx: Float,
    val alpha: Float,
)

/** Splatter determinista por canción: la misma canción siempre "mancha" igual. */
private fun generateSplats(seed: Int, spec: VinylStyleSpec, count: Int = 150): List<Splat> {
    if (!spec.splatter) return emptyList()
    val rnd = Random(seed)
    return List(count) {
        Splat(
            angle = rnd.nextFloat() * 360f,
            startFrac = 0.30f + rnd.nextFloat() * 0.60f,
            lenFrac = 0.04f + rnd.nextFloat() * 0.28f,
            widthPx = 2f + rnd.nextFloat() * 9f,
            alpha = 0.5f + rnd.nextFloat() * 0.5f,
        )
    }
}

private val VinylSpeeds = listOf(
    "slow" to ("Lenta" to 0.55f),
    "normal" to ("Normal" to 1f),
    "fast" to ("Rápida" to 1.7f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VinylPlayer(
    state: BottomSheetState,
    navController: NavController,
    positionState: State<Long>,
    durationState: State<Long>,
    sliderPosition: Long?,
    onSliderPositionChange: (Long?) -> Unit,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,

    onSleepTimerClick: () -> Unit = {},
    onVisualizerClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)
    // Optimista: ver PlayerConnection.currentSongLiked.
    val currentSongLiked by playerConnection.currentSongLiked.collectAsState()

    val position by positionState
    val duration by durationState

    // ── El brazo (varita) ES la barra de reproducción: su ángulo mapea la
    //    posición y arrastrarlo hace seek en tiempo real (como MDVinyl) ──
    val armProgress = if (duration > 0L) {
        ((sliderPosition ?: position).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val armSeekHolder = remember { longArrayOf(-1L) }
    val onArmScrub: (Float) -> Unit = { p ->
        if (duration > 0L) {
            val pos = (p * duration).toLong()
            armSeekHolder[0] = pos
            onSliderPositionChange(pos)
        }
    }
    val onArmScrubEnd: () -> Unit = {
        if (armSeekHolder[0] >= 0L) {
            playerConnection.player.seekTo(armSeekHolder[0])
        }
        armSeekHolder[0] = -1L
        onSliderPositionChange(null)
    }

    var themeKey by rememberPreference(DezzyVinylThemeKey, "dynamic")
    var styleKey by rememberPreference(DezzyVinylStyleKey, "dynamic")
    var speedKey by rememberPreference(DezzyVinylSpeedKey, "normal")

    // ── Color semilla de la carátula (para tema y vinilo dinámicos) ──
    var coverColor by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(mediaMetadata?.thumbnailUrl) {
        val url = mediaMetadata?.thumbnailUrl ?: run { coverColor = null; return@LaunchedEffect }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(120, 120)
                        .allowHardware(false)
                        .build(),
                ).image?.toBitmap()
            }.getOrNull()
        } ?: return@LaunchedEffect
        val palette = withContext(Dispatchers.Default) {
            Palette.from(bitmap).maximumColorCount(12).generate()
        }
        val seed = palette.vibrantSwatch?.rgb
            ?: palette.lightVibrantSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
        coverColor = seed?.let { Color(it) }
    }

    // Tema dinámico: matiz de la carátula en banda clara y suave.
    val dynamicTheme = remember(coverColor) {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL((coverColor ?: Color(0xFFB9B4A6)).toArgb(), hsl)
        val h = hsl[0]
        val s = minOf(hsl[1], 0.38f)
        fun tone(sat: Float, light: Float) = Color(ColorUtils.HSLToColor(floatArrayOf(h, sat, light)))
        VinylTheme(
            key = "dynamic",
            background = tone(s, 0.86f),
            content = tone(minOf(s + 0.10f, 0.5f), 0.13f),
            subtle = tone(s, 0.44f),
            button = tone(s * 0.8f, 0.94f),
        )
    }
    val theme = if (themeKey == "dynamic") {
        dynamicTheme
    } else {
        VinylThemes.firstOrNull { it.key == themeKey } ?: dynamicTheme
    }

    // Vinilo dinámico: mármol teñido con el color de la carátula (como MDVinyl).
    val dynamicSpec = remember(coverColor) {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL((coverColor ?: Color(0xFF8FA3B8)).toArgb(), hsl)
        val s = minOf(hsl[1], 0.48f)
        VinylStyleSpec(
            key = "dynamic",
            base = Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, 0.78f))),
            groove = Color.Black.copy(alpha = 0.06f),
            splat = Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], minOf(s + 0.18f, 0.62f), 0.38f))),
            splatter = true,
        )
    }
    val resolvedStyle = remember(styleKey, mediaMetadata?.id, dynamicSpec) {
        when (styleKey) {
            "dynamic" -> dynamicSpec
            "shuffle" -> VinylStyles[abs(mediaMetadata?.id.hashCode()) % VinylStyles.size]
            else -> VinylStyles.firstOrNull { it.key == styleKey } ?: dynamicSpec
        }
    }

    // Canvas de la canción para el botón de Video inteligente
    val canvasArtwork = rememberPlaybackCanvas(
        mediaId = mediaMetadata?.id,
        title = mediaMetadata?.title,
        artist = mediaMetadata?.artists?.firstOrNull()?.name,
        albumTitle = mediaMetadata?.album?.title,
        durationMs = duration,
    )

    // ── Rotación del disco (solo avanza mientras suena; velocidad configurable) ──
    val speedMultiplier = VinylSpeeds.firstOrNull { it.first == speedKey }?.second?.second ?: 1f
    var discRotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying, speedMultiplier) {
        if (!isPlaying) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    discRotation =
                        (discRotation + (now - last) / 1_000_000_000f * 108f * speedMultiplier) % 360f
                }
                last = now
            }
        }
    }

    // ── Gestos MDVinyl sobre el vinilo ──
    val onSaveVinyl: () -> Unit = {
        val spec = resolvedStyle
        val seed = mediaMetadata?.id.hashCode()
        val thumb = mediaMetadata?.thumbnailUrl
        val songTitle = mediaMetadata?.title ?: "vinyl"
        scope.launch {
            Toast.makeText(context, "Guardando vinilo…", Toast.LENGTH_SHORT).show()
            val saved = saveVinylToGallery(context, spec, seed, thumb, songTitle)
            Toast.makeText(
                context,
                if (saved) "Vinilo guardado en Imágenes/Dezzy Vinyl ✓" else "No se pudo guardar el vinilo",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    var showStyleSheet by remember { mutableStateOf(false) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Compartir directo (tarjeta Canción/Letra/Canvas)
    var showShareSheet by remember { mutableStateOf(false) }
    val shareLyricsEntity by playerConnection.currentLyrics.collectAsState(initial = null)
    if (showShareSheet) {
        com.dezzy.music.ui.component.ShareBottomSheet(
            title = mediaMetadata?.title ?: "",
            subtitle = mediaMetadata?.artists?.joinToString { it.name } ?: "",
            thumbnailUrl = mediaMetadata?.thumbnailUrl,
            shareLink = "https://music.youtube.com/watch?v=${mediaMetadata?.id ?: ""}",
            lyrics = shareLyricsEntity?.lyrics,
            mediaId = mediaMetadata?.id,
            onDismiss = { showShareSheet = false }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background),
    ) {
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.5f)
                        .fillMaxSize(),
                ) {
                    // Maqueta MDVinyl horizontal: funda con la carátula y el
                    // disco asomando detrás, con su etiqueta impresa.
                    SleeveTurntableSection(
                        thumbnailUrl = mediaMetadata?.thumbnailUrl,
                        title = mediaMetadata?.title.orEmpty(),
                        artist = mediaMetadata?.artists?.joinToString { it.name }.orEmpty(),
                        seed = mediaMetadata?.id.hashCode(),
                        spec = resolvedStyle,
                        isPlaying = isPlaying,
                        rotation = { discRotation },
                        theme = theme,
                        armProgress = armProgress,
                        onArmScrub = onArmScrub,
                        onArmScrubEnd = onArmScrubEnd,
                        onTogglePlay = { playerConnection.player.togglePlayPause() },
                        onPrevious = { playerConnection.seekToPrevious() },
                        onNext = { playerConnection.seekToNext() },
                        onSaveVinyl = onSaveVinyl,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 24.dp),
                ) {
                    InfoAndControls(
                        theme = theme,
                        title = mediaMetadata?.title.orEmpty(),
                        artist = mediaMetadata?.artists?.joinToString { it.name }.orEmpty(),
                        isPlaying = isPlaying,
                        liked = currentSongLiked,
                        position = position,
                        duration = duration,
                        sliderPosition = sliderPosition,
                        onTogglePlay = { playerConnection.player.togglePlayPause() },
                        onPrevious = { playerConnection.seekToPrevious() },
                        onNext = { playerConnection.seekToNext() },
                        onToggleLike = { playerConnection.toggleLike() },
                        onLyricsClick = onLyricsClick,
                        onShareClick = { showShareSheet = true },
                        onVisualizerClick = onVisualizerClick,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                // ── Barra superior: colapsar · video inteligente · cola/ajustes ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundIconButton(
                        iconRes = R.drawable.expand_more,
                        theme = theme,
                        onClick = state::collapseSoft,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    SongVideoToggle(
                        playerConnection = playerConnection,
                        canvas = canvasArtwork,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    RoundIconButton(
                        iconRes = R.drawable.list,
                        theme = theme,
                        onClick = onQueueClick,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    RoundIconButton(
                        iconRes = R.drawable.settings,
                        theme = theme,
                        onClick = { showStyleSheet = true },
                    )
                }

                // ── Tornamesa ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    TurntableSection(
                        thumbnailUrl = mediaMetadata?.thumbnailUrl,
                        seed = mediaMetadata?.id.hashCode(),
                        spec = resolvedStyle,
                        isPlaying = isPlaying,
                        rotation = { discRotation },
                        theme = theme,
                        armProgress = armProgress,
                        onArmScrub = onArmScrub,
                        onArmScrubEnd = onArmScrubEnd,
                        onTogglePlay = { playerConnection.player.togglePlayPause() },
                        onPrevious = { playerConnection.seekToPrevious() },
                        onNext = { playerConnection.seekToNext() },
                        onSaveVinyl = onSaveVinyl,
                    )
                }

                InfoAndControls(
                    theme = theme,
                    title = mediaMetadata?.title.orEmpty(),
                    artist = mediaMetadata?.artists?.joinToString { it.name }.orEmpty(),
                    isPlaying = isPlaying,
                    liked = currentSongLiked,
                    position = position,
                    duration = duration,
                    sliderPosition = sliderPosition,
                    onTogglePlay = { playerConnection.player.togglePlayPause() },
                    onPrevious = { playerConnection.seekToPrevious() },
                    onNext = { playerConnection.seekToNext() },
                    onToggleLike = { playerConnection.toggleLike() },
                    onLyricsClick = onLyricsClick,
                    onShareClick = { showShareSheet = true },
                    onVisualizerClick = onVisualizerClick,
                )
            }
        }

        // Overlay de video (YouTube) — se dibuja por encima de todo el estilo.
        VideoModeOverlay(
            playerConnection = playerConnection,
            modifier = Modifier.fillMaxSize(),
            showFloatingToggle = false,
            onCollapse = state::collapseSoft,
            onQueueClick = onQueueClick,
        )
    }

    // ── Hoja de estilo: Theme + Vinyl + Velocidad ──
    if (showStyleSheet) {
        ModalBottomSheet(
            onDismissRequest = { showStyleSheet = false },
            containerColor = Color(0xFF17171A),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = "Theme",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Tema dinámico (de la carátula) primero
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        dynamicTheme.background,
                                        coverColor ?: dynamicTheme.button,
                                    ),
                                ),
                            )
                            .border(
                                width = if (themeKey == "dynamic") 3.dp else 1.dp,
                                color = if (themeKey == "dynamic") Color.White else Color.White.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(16.dp),
                            )
                            .clickable { themeKey = "dynamic" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.palette),
                            contentDescription = "Tema dinámico",
                            tint = dynamicTheme.content,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    VinylThemes.forEach { t ->
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(t.background)
                                .border(
                                    width = if (t.key == themeKey) 3.dp else 1.dp,
                                    color = if (t.key == themeKey) Color.White else Color.White.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(16.dp),
                                )
                                .clickable { themeKey = t.key },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(26.dp))
                Text(
                    text = "Vinyl",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Vinilo dinámico (teñido con la carátula) primero
                    MiniDiscPreview(
                        spec = dynamicSpec,
                        selected = styleKey == "dynamic",
                        modifier = Modifier
                            .size(54.dp)
                            .clickable { styleKey = "dynamic" },
                    )
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .border(
                                width = if (styleKey == "shuffle") 3.dp else 1.5.dp,
                                color = if (styleKey == "shuffle") Color.White else Color.White.copy(alpha = 0.4f),
                                shape = CircleShape,
                            )
                            .clickable { styleKey = "shuffle" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.shuffle),
                            contentDescription = "Vinilo aleatorio",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    VinylStyles.forEach { spec ->
                        MiniDiscPreview(
                            spec = spec,
                            selected = styleKey == spec.key,
                            modifier = Modifier
                                .size(54.dp)
                                .clickable { styleKey = spec.key },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(26.dp))
                Text(
                    text = "Velocidad",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VinylSpeeds.forEach { (key, labelSpeed) ->
                        FilterChip(
                            selected = speedKey == key,
                            onClick = { speedKey = key },
                            label = { Text(labelSpeed.first) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = "Toca el vinilo para pausar · desliza para cambiar de canción · mantén presionado para guardarlo como imagen",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(10.dp))
                TextButton(onClick = {
                    showStyleSheet = false
                    onSleepTimerClick()
                }) {
                    Text("Temporizador de sueño", color = Color.White.copy(alpha = 0.8f))
                }
            }
        }
    }
}

// ── Tornamesa: disco (con gestos) + brazo ────────────────────────────────────

@Composable
private fun TurntableSection(
    thumbnailUrl: String?,
    seed: Int,
    spec: VinylStyleSpec,
    isPlaying: Boolean,
    rotation: () -> Float,
    theme: VinylTheme,
    armProgress: Float,
    onArmScrub: (Float) -> Unit,
    onArmScrubEnd: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSaveVinyl: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Los gestos van en este Box exterior (NO rota): dentro del disco
        // giratorio las coordenadas del drag quedarían rotadas.
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .aspectRatio(1f)
                .align(Alignment.CenterStart)
                .offset(x = (-18).dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTogglePlay() },
                        onLongPress = { onSaveVinyl() },
                    )
                }
                .pointerInput(Unit) {
                    val threshold = 56.dp.toPx()
                    var dragTotal = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragTotal = 0f },
                        onDragEnd = {
                            when {
                                dragTotal <= -threshold -> onNext()
                                dragTotal >= threshold -> onPrevious()
                            }
                        },
                    ) { change, dragAmount ->
                        dragTotal += dragAmount
                        change.consume()
                    }
                },
        ) {
            VinylDisc(
                thumbnailUrl = thumbnailUrl,
                seed = seed,
                spec = spec,
                rotation = rotation,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ToneArm(
            isPlaying = isPlaying,
            progress = armProgress,
            onScrub = onArmScrub,
            onScrubEnd = onArmScrubEnd,
            armColor = theme.button,
            outline = theme.content.copy(alpha = 0.18f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 6.dp, top = 4.dp)
                .fillMaxWidth(0.30f)
                .aspectRatio(0.34f),
        )
    }
}

// ── Tornamesa horizontal (réplica MDVinyl): funda + disco asomando + brazo ──
//
// La carátula del álbum va impresa en la FUNDA cuadrada; el disco sale por su
// derecha con una etiqueta de vinilo real (título/artista impresos, que giran
// con el disco). El brazo a la derecha es la barra de reproducción.

@Composable
private fun SleeveTurntableSection(
    thumbnailUrl: String?,
    title: String,
    artist: String,
    seed: Int,
    spec: VinylStyleSpec,
    isPlaying: Boolean,
    rotation: () -> Float,
    theme: VinylTheme,
    armProgress: Float,
    onArmScrub: (Float) -> Unit,
    onArmScrubEnd: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSaveVinyl: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Limitado por alto Y por ancho: funda + disco asomando caben siempre
        // sin invadir la columna de controles (funda·(1 + 0.60·0.93) ≈ 1.56×).
        val sleeveX = 20.dp
        val sleeveSide = minOf(maxHeight * 0.86f, (maxWidth - sleeveX) / 1.56f)
        val discSize = sleeveSide * 0.93f
        // El disco queda medio metido en la funda, como recién sacado.
        val discX = sleeveX + sleeveSide * 0.60f
        val discTop = (maxHeight - discSize) / 2

        // Disco (detrás de la funda), con los gestos MDVinyl
        Box(
            modifier = Modifier
                .size(discSize)
                .offset(x = discX, y = discTop)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTogglePlay() },
                        onLongPress = { onSaveVinyl() },
                    )
                }
                .pointerInput(Unit) {
                    val threshold = 56.dp.toPx()
                    var dragTotal = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragTotal = 0f },
                        onDragEnd = {
                            when {
                                dragTotal <= -threshold -> onNext()
                                dragTotal >= threshold -> onPrevious()
                            }
                        },
                    ) { change, dragAmount ->
                        dragTotal += dragAmount
                        change.consume()
                    }
                },
        ) {
            VinylDisc(
                thumbnailUrl = null,
                seed = seed,
                spec = spec,
                rotation = rotation,
                labelText = title to artist,
                labelBg = theme.button,
                labelFg = theme.content,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Funda: la carátula del álbum como portada cuadrada con sombra
        Box(
            modifier = Modifier
                .size(sleeveSide)
                .align(Alignment.CenterStart)
                .offset(x = sleeveX)
                .shadow(14.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White),
        ) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        ToneArm(
            isPlaying = isPlaying,
            progress = armProgress,
            onScrub = onArmScrub,
            onScrubEnd = onArmScrubEnd,
            armColor = theme.button,
            outline = theme.content.copy(alpha = 0.18f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 8.dp, top = 4.dp)
                .fillMaxHeight(0.95f)
                .aspectRatio(0.34f),
        )
    }
}

@Composable
private fun VinylDisc(
    thumbnailUrl: String?,
    seed: Int,
    spec: VinylStyleSpec,
    rotation: () -> Float,
    modifier: Modifier = Modifier,
    labelText: Pair<String, String>? = null, // (título, artista): etiqueta impresa en vez de carátula
    labelBg: Color = Color.White,
    labelFg: Color = Color(0xFF141414),
) {
    val splats = remember(seed, spec.key, spec.base) { generateSplats(seed, spec) }

    Box(
        modifier = modifier.graphicsLayer { rotationZ = rotation() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val c = center

            drawCircle(spec.base, radius, c)
            // Sombreado radial sutil (relieve del vinilo)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.08f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.10f),
                    ),
                    center = c,
                    radius = radius,
                ),
                radius = radius,
                center = c,
            )
            // Surcos
            val labelR = radius * 0.30f
            val grooves = 30
            for (i in 0..grooves) {
                val r = labelR + (radius * 0.94f - labelR) * i / grooves.toFloat()
                drawCircle(spec.groove, r, c, style = Stroke(width = 1.2f))
            }
            // Splatter
            for (s in splats) {
                rotate(s.angle, c) {
                    val y0 = c.y - radius * s.startFrac
                    val y1 = c.y - radius * (s.startFrac + s.lenFrac).coerceAtMost(0.96f)
                    drawLine(
                        color = spec.splat.copy(alpha = s.alpha),
                        start = Offset(c.x, y0),
                        end = Offset(c.x, y1),
                        strokeWidth = s.widthPx,
                        cap = StrokeCap.Round,
                    )
                }
            }
            // Borde exterior
            drawCircle(Color.Black.copy(alpha = 0.25f), radius, c, style = Stroke(width = 2f))
        }

        // Etiqueta central: carátula (vertical) o etiqueta impresa de vinilo
        // real con título/artista (horizontal, donde la carátula va en la funda).
        Box(
            modifier = Modifier
                .fillMaxSize(0.42f)
                .clip(CircleShape)
                .background(labelBg),
            contentAlignment = Alignment.Center,
        ) {
            if (labelText == null) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val (title, artist) = labelText
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    drawIntoCanvas { c ->
                        val n = c.nativeCanvas
                        val titlePaint = android.text.TextPaint().apply {
                            isAntiAlias = true
                            color = labelFg.toArgb()
                            textSize = r * 0.22f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                        val artistPaint = android.text.TextPaint().apply {
                            isAntiAlias = true
                            color = labelFg.copy(alpha = 0.75f).toArgb()
                            textSize = r * 0.16f
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                        val t = android.text.TextUtils.ellipsize(
                            title, titlePaint, r * 1.55f, android.text.TextUtils.TruncateAt.END,
                        ).toString()
                        val a = android.text.TextUtils.ellipsize(
                            artist, artistPaint, r * 1.45f, android.text.TextUtils.TruncateAt.END,
                        ).toString()
                        n.drawText(t, cx, cy - r * 0.10f, titlePaint)
                        n.drawText(a, cx, cy + r * 0.34f, artistPaint)

                        // Texto curvo en el borde, como una etiqueta de vinilo real
                        val rimPaint = android.text.TextPaint().apply {
                            isAntiAlias = true
                            color = labelFg.copy(alpha = 0.45f).toArgb()
                            textSize = r * 0.095f
                            letterSpacing = 0.18f
                        }
                        val rimR = r * 0.82f
                        val circumference = (2.0 * Math.PI * rimR).toFloat()
                        // Arco superior (path CW: las 12 en punto quedan a ¾ del recorrido)
                        val topText = "DEZZY RECORDS"
                        val topPath = android.graphics.Path().apply {
                            addCircle(cx, cy, rimR, android.graphics.Path.Direction.CW)
                        }
                        n.drawTextOnPath(
                            topText, topPath,
                            circumference * 0.75f - rimPaint.measureText(topText) / 2f, 0f, rimPaint,
                        )
                        // Arco inferior legible (path CCW: las 6 en punto a ¾ del recorrido)
                        val bottomText = "33⅓ RPM · STEREO"
                        val bottomPath = android.graphics.Path().apply {
                            addCircle(cx, cy, rimR, android.graphics.Path.Direction.CCW)
                        }
                        n.drawTextOnPath(
                            bottomText, bottomPath,
                            circumference * 0.75f - rimPaint.measureText(bottomText) / 2f, 0f, rimPaint,
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize(0.42f)
                .border(3.dp, Color.White.copy(alpha = 0.9f), CircleShape),
        )
        // Agujero central
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(spec.base),
        )
    }
}

@Composable
private fun ToneArm(
    isPlaying: Boolean,
    progress: Float,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    armColor: Color,
    outline: Color,
    modifier: Modifier = Modifier,
) {
    // El brazo ES la barra de reproducción: a mayor progreso, más se adentra
    // la aguja hacia el centro del disco. Arrastrarlo verticalmente hace seek.
    val armMin = 8f   // inicio de la canción: aguja en el borde exterior
    val armMax = 30f  // final: aguja cerca de la etiqueta central
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val shown = (if (dragging) dragProgress else progress).coerceIn(0f, 1f)
    val target = armMin + (armMax - armMin) * shown
    // Animado en reproducción normal; instantáneo mientras arrastras (sigue el dedo).
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(if (isPlaying) 500 else 250),
        label = "toneArmAngle",
    )
    val angle = if (dragging) target else animated
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            // Recorrido del dedo para barrer toda la canción (≈1.5× el alto del
            // brazo): al ser el ÚNICO control de posición debe sentirse directo.
            val rangePx = size.height * 1.5f
            detectVerticalDragGestures(
                onDragStart = {
                    dragging = true
                    dragProgress = progress.coerceIn(0f, 1f)
                },
                onDragEnd = { dragging = false; onScrubEnd() },
                onDragCancel = { dragging = false; onScrubEnd() },
            ) { change, dy ->
                dragProgress = (dragProgress + dy / rangePx).coerceIn(0f, 1f)
                onScrub(dragProgress)
                change.consume()
            }
        },
    ) {
        val pivot = Offset(size.width * 0.52f, size.height * 0.14f)

        // Aro del pivote
        drawCircle(outline, radius = size.width * 0.46f, center = pivot, style = Stroke(width = 2f))

        rotate(angle, pivot) {
            val armEndY = size.height * 0.90f
            // Vástago plateado con brillo
            drawLine(
                color = Color(0xFF9EA3A8),
                start = pivot,
                end = Offset(pivot.x, armEndY),
                strokeWidth = size.width * 0.06f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.55f),
                start = Offset(pivot.x - size.width * 0.012f, pivot.y + size.height * 0.02f),
                end = Offset(pivot.x - size.width * 0.012f, armEndY),
                strokeWidth = size.width * 0.018f,
                cap = StrokeCap.Round,
            )
            // Cabezal (contrapeso) en el pivote
            drawRoundRect(
                color = armColor,
                topLeft = Offset(pivot.x - size.width * 0.17f, pivot.y - size.height * 0.055f),
                size = Size(size.width * 0.34f, size.height * 0.115f),
                cornerRadius = CornerRadius(size.width * 0.055f),
            )
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.10f),
                topLeft = Offset(pivot.x - size.width * 0.17f, pivot.y - size.height * 0.055f),
                size = Size(size.width * 0.34f, size.height * 0.115f),
                cornerRadius = CornerRadius(size.width * 0.055f),
                style = Stroke(width = 1.5f),
            )
            // Cápsula en la punta
            drawRoundRect(
                color = armColor,
                topLeft = Offset(pivot.x - size.width * 0.115f, armEndY - size.height * 0.015f),
                size = Size(size.width * 0.23f, size.height * 0.075f),
                cornerRadius = CornerRadius(size.width * 0.045f),
            )
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.10f),
                topLeft = Offset(pivot.x - size.width * 0.115f, armEndY - size.height * 0.015f),
                size = Size(size.width * 0.23f, size.height * 0.075f),
                cornerRadius = CornerRadius(size.width * 0.045f),
                style = Stroke(width = 1.5f),
            )
        }
    }
}

// ── Info + controles ─────────────────────────────────────────────────────────

@Composable
private fun InfoAndControls(
    theme: VinylTheme,
    title: String,
    artist: String,
    isPlaying: Boolean,
    liked: Boolean,
    position: Long,
    duration: Long,
    sliderPosition: Long?,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleLike: () -> Unit,
    onLyricsClick: () -> Unit,

    onShareClick: () -> Unit = {},
    onVisualizerClick: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Título y artista (alineados a la izquierda, como MDVinyl)
        Column(modifier = Modifier.padding(horizontal = 28.dp)) {
            Text(
                text = title,
                color = theme.content,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = artist,
                color = theme.subtle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // SIN barra deslizante: el brazo del vinilo ES el control de posición
        // (arrástralo sobre el disco). Solo se muestran los tiempos.
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(makeTimeString(sliderPosition ?: position), color = theme.subtle, fontSize = 12.sp)
            Text(makeTimeString(duration), color = theme.subtle, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Botones pill "físicos": PLAY a la izquierda, prev/next a la derecha
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            verticalAlignment = Alignment.Top,
        ) {
            PillButton(
                onClick = onTogglePlay,
                fill = theme.button,
                width = 110.dp,
            ) {
                Text(
                    text = if (isPlaying) "PAUSE" else "PLAY",
                    color = theme.content,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            PillButton(onClick = onPrevious, fill = theme.button, width = 96.dp) {
                Icon(
                    painter = painterResource(R.drawable.deezer_previous),
                    contentDescription = "Anterior",
                    tint = theme.content,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(18.dp))
            PillButton(onClick = onNext, fill = theme.button, width = 96.dp) {
                Icon(
                    painter = painterResource(R.drawable.deezer_next),
                    contentDescription = "Siguiente",
                    tint = theme.content,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Fila inferior: me gusta · visualizador · compartir · letras
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RoundIconButton(
                iconRes = if (liked) R.drawable.favorite else R.drawable.favorite_border,
                theme = theme,
                onClick = onToggleLike,
            )
            RoundIconButton(
                iconRes = R.drawable.auto_awesome,
                theme = theme,
                onClick = onVisualizerClick,
            )
            RoundIconButton(
                iconRes = R.drawable.share,
                theme = theme,
                onClick = onShareClick,
            )
            RoundIconButton(
                iconRes = R.drawable.lyrics,
                theme = theme,
                onClick = onLyricsClick,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ── Piezas pequeñas ──────────────────────────────────────────────────────────

@Composable
private fun PillButton(
    onClick: () -> Unit,
    fill: Color,
    width: Dp,
    label: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = fill,
            shadowElevation = 8.dp,
            modifier = Modifier.size(width, 44.dp),
        ) {}
        Spacer(modifier = Modifier.height(8.dp))
        label()
    }
}

@Composable
private fun RoundIconButton(
    iconRes: Int,
    theme: VinylTheme,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = theme.button,
        shadowElevation = 6.dp,
        modifier = Modifier.size(46.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = theme.content,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun MiniDiscPreview(
    spec: VinylStyleSpec,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val splats = remember(spec.key, spec.base) {
        generateSplats(spec.key.hashCode(), spec, count = 40)
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val c = center
            drawCircle(spec.base, radius, c)
            drawCircle(spec.groove, radius * 0.7f, c, style = Stroke(width = 1f))
            drawCircle(spec.groove, radius * 0.5f, c, style = Stroke(width = 1f))
            for (s in splats) {
                rotate(s.angle, c) {
                    drawLine(
                        color = spec.splat.copy(alpha = s.alpha),
                        start = Offset(c.x, c.y - radius * s.startFrac),
                        end = Offset(c.x, c.y - radius * (s.startFrac + s.lenFrac).coerceAtMost(0.95f)),
                        strokeWidth = s.widthPx * 0.4f,
                        cap = StrokeCap.Round,
                    )
                }
            }
            drawCircle(Color.White.copy(alpha = 0.9f), radius * 0.22f, c)
            drawCircle(spec.base, radius * 0.05f, c)
            if (selected) {
                drawCircle(Color.White, radius, c, style = Stroke(width = 3.dp.toPx()))
            }
        }
    }
}

// ── Guardar el vinilo como imagen (long-press, como MDVinyl) ─────────────────

/**
 * Renderiza el vinilo actual (textura + splatter + carátula como etiqueta) en
 * un PNG de 1024px y lo guarda en Imágenes/Dezzy Vinyl vía MediaStore.
 */
private suspend fun saveVinylToGallery(
    context: Context,
    spec: VinylStyleSpec,
    seed: Int,
    thumbnailUrl: String?,
    title: String,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val sizePx = 768
        val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val cx = sizePx / 2f
        val r = sizePx / 2f

        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        fill.color = spec.base.toArgb()
        canvas.drawCircle(cx, cx, r, fill)

        // Surcos
        val groovePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2.4f
            color = spec.groove.toArgb()
        }
        val labelR = r * 0.30f
        for (i in 0..30) {
            canvas.drawCircle(cx, cx, labelR + (r * 0.94f - labelR) * i / 30f, groovePaint)
        }

        // Splatter (mismo seed que en pantalla → mismo vinilo)
        val splatPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        val pxScale = sizePx / 420f
        generateSplats(seed, spec).forEach { s ->
            splatPaint.color = spec.splat.copy(alpha = s.alpha).toArgb()
            splatPaint.strokeWidth = s.widthPx * pxScale
            canvas.save()
            canvas.rotate(s.angle, cx, cx)
            canvas.drawLine(
                cx, cx - r * s.startFrac,
                cx, cx - r * (s.startFrac + s.lenFrac).coerceAtMost(0.96f),
                splatPaint,
            )
            canvas.restore()
        }

        // Etiqueta central: la carátula recortada en círculo sobre anillo blanco
        val labelRadius = r * 0.42f
        fill.color = android.graphics.Color.WHITE
        canvas.drawCircle(cx, cx, labelRadius + 6f, fill)
        val cover = thumbnailUrl?.let { url ->
            runCatching {
                context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(400, 400)
                        .allowHardware(false)
                        .build(),
                ).image?.toBitmap()
            }.getOrNull()
        }
        if (cover != null) {
            val path = android.graphics.Path().apply {
                addCircle(cx, cx, labelRadius, android.graphics.Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(path)
            val dst = android.graphics.RectF(cx - labelRadius, cx - labelRadius, cx + labelRadius, cx + labelRadius)
            canvas.drawBitmap(cover, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        }
        // Agujero central
        fill.color = spec.base.toArgb()
        canvas.drawCircle(cx, cx, r * 0.018f, fill)

        // Guardar en la galería
        val safeName = title.replace(Regex("[^\\w\\s-]"), "").trim().ifBlank { "vinyl" }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "vinyl_${safeName}_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Dezzy Vinyl")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore rechazó la inserción")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        } ?: error("No se pudo abrir el stream de salida")
        
        bmp.recycle()
        cover?.recycle()
        
        true
    }.getOrElse {
        timber.log.Timber.e(it, "No se pudo guardar el vinilo")
        false
    }
}

