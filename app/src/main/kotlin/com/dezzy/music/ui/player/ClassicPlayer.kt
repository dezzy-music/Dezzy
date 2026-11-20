/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.player

import android.content.Intent
import android.content.res.Configuration
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.media3.common.Player
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dezzy.music.LocalDatabase
import com.dezzy.music.LocalDownloadUtil
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.connect.ConnectState
import com.dezzy.music.connect.LinerConnectManager
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.extensions.toggleRepeatMode
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.models.MediaMetadata
import com.dezzy.music.playback.ExoDownloadService
import com.dezzy.music.ui.component.BottomSheetState
import com.dezzy.music.ui.component.ConnectDeviceBottomSheet
import com.dezzy.music.ui.component.ShareBottomSheet
import com.dezzy.music.ui.menu.AddToPlaylistDialog
import com.dezzy.music.ui.menu.EqualizerDialog
import com.dezzy.music.ui.menu.TempoPitchDialog
import com.dezzy.music.utils.makeTimeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reproductor «Clásico» (fase 2 del rediseño, Boceto A: reproductor M3 enfocado).
 *
 * Una sola pantalla limpia con roles Material 3 (se adapta solo al tema claro y
 * oscuro): barra superior con contraer / origen / Más, carátula grande con
 * canvas, título con «Me gusta», barra de progreso, controles con Play/Pausa de
 * 80 dp y cuatro acciones con etiqueta (Letras, Dispositivos, Temporizador y
 * Cola). El resto de opciones vive en la hoja «Más», ordenada por grupos, que
 * al final abre el menú completo de siempre para no perder ninguna función.
 *
 * El fondo es un degradado suave del color de la carátula hacia `surface`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassicPlayer(
    state: BottomSheetState,
    navController: NavController,
    positionState: State<Long>,
    durationState: State<Long>,
    sliderPosition: Long?,
    onSliderPositionChange: (Long?) -> Unit,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    sleepTimerEnabled: Boolean,
    sleepTimerTimeLeft: Long,
    onMenuClick: () -> Unit,
    onVisualizerClick: () -> Unit,
    modifier: Modifier = Modifier,
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

    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // ---- Dispositivo activo (Liner Connect) ----
    val linerManager = remember { LinerConnectManager.getInstance(context) }
    val linerConnState by linerManager.connectionState.collectAsState()
    val isConnectedToDesktop = linerConnState == ConnectState.CONNECTED
    var showDevicePicker by remember { mutableStateOf(false) }
    var showDjSheet by remember { mutableStateOf(false) }

    // ---- Canvas de la canción sobre la carátula ----
    val canvasArtwork = rememberPlaybackCanvas(
        mediaId = mediaMetadata?.id,
        title = mediaMetadata?.title,
        artist = mediaMetadata?.artists?.firstOrNull()?.name,
        albumTitle = mediaMetadata?.album?.title,
        durationMs = duration,
    )
    val (canvasPrimary, canvasFallback) = canvasArtwork?.playbackUrls() ?: (null to null)
    val hasCanvas = canvasPrimary != null || canvasFallback != null

    // ---- Color de la carátula para el degradado del fondo ----
    var targetAccent by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(mediaMetadata?.id) {
        val thumbnailUrl = mediaMetadata?.thumbnailUrl ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(thumbnailUrl)
                .size(100, 100)
                .allowHardware(false)
                .build()
            val bitmap = runCatching { context.imageLoader.execute(request) }.getOrNull()
                ?.image?.toBitmap() ?: return@withContext
            val palette = withContext(Dispatchers.Default) {
                Palette.from(bitmap).maximumColorCount(16).generate()
            }
            val rgb = palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb ?: return@withContext
            withContext(Dispatchers.Main) { targetAccent = Color(rgb) }
        }
    }
    val accent by animateColorAsState(
        targetValue = targetAccent ?: colorScheme.primary,
        animationSpec = tween(600),
        label = "classicAccent",
    )
    val isDarkSurface = colorScheme.surface.luminance() < 0.5f
    val topTint = if (isDarkSurface) 0.30f else 0.34f
    val background = Brush.verticalGradient(
        0f to lerp(colorScheme.surface, accent, topTint),
        0.45f to lerp(colorScheme.surface, accent, topTint * 0.35f),
        0.8f to colorScheme.surface,
    )

    // ---- Bloques reutilizados en vertical y horizontal ----
    val topBar: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { state.collapseSoft() }) {
                Icon(
                    painter = painterResource(R.drawable.expand_more),
                    contentDescription = "Contraer reproductor",
                    tint = colorScheme.onSurface,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "REPRODUCIENDO DESDE",
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = queueTitle ?: "Tu música",
                    style = MaterialTheme.typography.titleSmall,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            IconButton(onClick = { showDjSheet = true }) {
                Icon(
                    painter = painterResource(R.drawable.graphic_eq),
                    contentDescription = "DJ Dezzy",
                    tint = colorScheme.onSurface,
                )
            }
            IconButton(onClick = onMenuClick) {
                Icon(
                    painter = painterResource(R.drawable.more_vert),
                    contentDescription = "Más opciones",
                    tint = colorScheme.onSurface,
                )
            }
        }
    }

    val cover: @Composable (Modifier) -> Unit = { coverModifier ->
        Box(modifier = coverModifier, contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .shadow(12.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(colorScheme.surfaceContainerHighest)
                    // Deslizar la carátula a los lados cambia de canción.
                    .pointerInput(playerConnection) {
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
                    },
            ) {
                AsyncImage(
                    model = mediaMetadata?.thumbnailUrl,
                    contentDescription = mediaMetadata?.title?.let { "Carátula de $it" },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
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
    }

    val controls: @Composable ColumnScope.() -> Unit = {
        // Título + artista + «Me gusta».
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 32.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mediaMetadata?.title ?: "",
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = mediaMetadata?.artists?.joinToString { it.name } ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(
                        enabled = mediaMetadata?.artists?.firstOrNull()?.id != null,
                        role = Role.Button,
                    ) {
                        mediaMetadata?.artists?.firstOrNull()?.id?.let {
                            state.collapseSoft()
                            navController.navigate("artist/$it")
                        }
                    },
                )
            }
            IconButton(onClick = { playerConnection.toggleLike() }) {
                Icon(
                    painter = painterResource(
                        if (currentSongLiked) R.drawable.favorite else R.drawable.favorite_border
                    ),
                    contentDescription = if (currentSongLiked) "Quitar de Me gusta" else "Me gusta",
                    tint = if (currentSongLiked) colorScheme.primary else colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Barra de progreso dinámica: toma el color de la carátula y, mientras suena,
        // la parte recorrida ondula suavemente (se aplana al pausar).
        val sliderMax = maxOf(duration.toFloat(), 1f)
        val activeTrack = remember(accent, isDarkSurface) {
            when {
                isDarkSurface && accent.luminance() < 0.22f -> lerp(accent, Color.White, 0.45f)
                !isDarkSurface && accent.luminance() > 0.55f -> lerp(accent, Color.Black, 0.4f)
                else -> accent
            }
        }
        val inactiveTrack = lerp(colorScheme.surfaceContainerHighest, activeTrack, 0.18f)
        val waveAmplitude by animateFloatAsState(
            targetValue = if (isPlaying && sliderPosition == null) 1f else 0f,
            animationSpec = tween(450),
            label = "classicWaveAmp",
        )
        val wavePhase by rememberInfiniteTransition(label = "classicWave").animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
            label = "classicWavePhase",
        )
        Slider(
            value = currentPos.toFloat().coerceIn(0f, sliderMax),
            valueRange = 0f..sliderMax,
            onValueChange = { onSliderPositionChange(it.toLong()) },
            onValueChangeFinished = {
                playerConnection.player.seekTo(currentPos)
                onSliderPositionChange(null)
            },
            colors = SliderDefaults.colors(
                thumbColor = activeTrack,
                activeTrackColor = activeTrack,
                inactiveTrackColor = inactiveTrack,
            ),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(activeTrack),
                )
            },
            track = { sliderState ->
                val range = sliderState.valueRange.endInclusive - sliderState.valueRange.start
                val fraction = if (range > 0f) {
                    ((sliderState.value - sliderState.valueRange.start) / range).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp),
                ) {
                    val stroke = 4.dp.toPx()
                    val midY = size.height / 2f
                    val splitX = size.width * fraction
                    // Resto de la canción: línea recta.
                    if (splitX < size.width) {
                        drawLine(
                            color = inactiveTrack,
                            start = Offset(splitX, midY),
                            end = Offset(size.width, midY),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                    // Parte recorrida: onda con el color de la carátula.
                    if (splitX > 0f) {
                        val amp = 3.dp.toPx() * waveAmplitude
                        if (amp < 0.5f) {
                            drawLine(
                                color = activeTrack,
                                start = Offset(0f, midY),
                                end = Offset(splitX, midY),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round,
                            )
                        } else {
                            val wavelength = 28.dp.toPx()
                            val path = Path()
                            var x = 0f
                            path.moveTo(0f, midY + amp * sin(wavePhase))
                            while (x < splitX) {
                                x = minOf(x + 2f, splitX)
                                path.lineTo(x, midY + amp * sin(x / wavelength * 2f * PI.toFloat() + wavePhase))
                            }
                            drawPath(
                                path = path,
                                color = activeTrack,
                                style = Stroke(width = stroke, cap = StrokeCap.Round),
                            )
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .height(28.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(makeTimeString(currentPos), style = MaterialTheme.typography.labelMedium, color = colorScheme.onSurfaceVariant)
            Text(makeTimeString(duration), style = MaterialTheme.typography.labelMedium, color = colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(8.dp))

        // Controles: aleatorio, anterior, Play/Pausa (80 dp), siguiente, repetir.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled }) {
                Icon(
                    painter = painterResource(R.drawable.shuffle),
                    contentDescription = if (shuffleModeEnabled) "Aleatorio activado" else "Aleatorio desactivado",
                    tint = if (shuffleModeEnabled) colorScheme.primary else colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { playerConnection.seekToPrevious() },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.skip_previous),
                    contentDescription = "Anterior",
                    tint = colorScheme.onSurface,
                    modifier = Modifier.size(36.dp),
                )
            }
            // Reproduciendo: cuadrado redondeado; en pausa: círculo.
            val playCorner by animateDpAsState(
                targetValue = if (isPlaying) 28.dp else 40.dp,
                label = "classicPlayCorner",
            )
            Surface(
                onClick = { playerConnection.player.togglePlayPause() },
                shape = RoundedCornerShape(playCorner),
                color = colorScheme.primary,
                contentColor = colorScheme.onPrimary,
                modifier = Modifier.size(80.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                        contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            IconButton(
                onClick = { playerConnection.seekToNext() },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.skip_next),
                    contentDescription = "Siguiente",
                    tint = colorScheme.onSurface,
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = { playerConnection.player.toggleRepeatMode() }) {
                Icon(
                    painter = painterResource(
                        if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.repeat_one else R.drawable.repeat
                    ),
                    contentDescription = when (repeatMode) {
                        Player.REPEAT_MODE_ONE -> "Repetir una"
                        Player.REPEAT_MODE_ALL -> "Repetir todo"
                        else -> "Repetir desactivado"
                    },
                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) colorScheme.primary else colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Cuatro acciones con etiqueta.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            ClassicActionButton(
                iconRes = R.drawable.lyrics,
                label = "Letras",
                onClick = onLyricsClick,
                modifier = Modifier.weight(1f),
            )
            ClassicActionButton(
                iconRes = R.drawable.speaker,
                label = "Dispositivos",
                active = isConnectedToDesktop,
                onClick = { showDevicePicker = true },
                modifier = Modifier.weight(1f),
            )
            ClassicActionButton(
                iconRes = R.drawable.bedtime,
                label = if (sleepTimerEnabled && sleepTimerTimeLeft > 0) makeTimeString(sleepTimerTimeLeft) else "Temporizador",
                active = sleepTimerEnabled,
                onClick = onSleepTimerClick,
                modifier = Modifier.weight(1f),
            )
            ClassicActionButton(
                iconRes = R.drawable.queue_music,
                label = "Cola",
                onClick = onQueueClick,
                modifier = Modifier.weight(1f),
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background),
    ) {
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                cover(
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
                ) {
                    topBar()
                    controls()
                    Spacer(Modifier.height(16.dp))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                topBar()
                // La carátula ocupa el espacio libre y se encoge en pantallas
                // pequeñas en lugar de empujar los controles fuera.
                cover(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 12.dp),
                )
                Spacer(Modifier.height(8.dp))
                controls()
            }
        }
    }

    if (showDevicePicker) {
        ConnectDeviceBottomSheet(
            onDismissRequest = { showDevicePicker = false },
            onOpenListenTogether = {
                showDevicePicker = false
                state.collapseSoft()
                navController.navigate("settings/music_together")
            },
        )
    }

    if (showDjSheet) {
        DJDezzyPlayerSheet(
            onDismiss = { showDjSheet = false },
            onOpenFullScreen = {
                showDjSheet = false
                state.collapseSoft()
                navController.navigate("dj_dezzy")
            },
        )
    }
}

/** Acción inferior con icono en pastilla (indicador `secondaryContainer` si está activa) y etiqueta de 12 sp. */
@Composable
private fun ClassicActionButton(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 32.dp)
                .clip(CircleShape)
                .background(if (active) colorScheme.secondaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (active) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (active) colorScheme.onSurface else colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
