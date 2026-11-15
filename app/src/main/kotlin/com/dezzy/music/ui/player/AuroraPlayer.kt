/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import kotlin.math.pow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
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
import com.dezzy.music.connect.ConnectState
import com.dezzy.music.connect.LinerConnectManager
import com.dezzy.music.constants.AuroraColor1Key
import com.dezzy.music.constants.AuroraColor2Key
import com.dezzy.music.constants.AuroraColor3Key
import com.dezzy.music.constants.AuroraMode
import com.dezzy.music.constants.AuroraModeKey
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.extensions.toggleRepeatMode
import com.dezzy.music.ui.component.BottomSheetState
import com.dezzy.music.ui.component.ConnectDeviceBottomSheet
import com.dezzy.music.utils.makeTimeString
import com.dezzy.music.utils.rememberEnumPreference
import com.dezzy.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val AuroraGreen = Color(0xFF1DB954)
private val AuroraPink = Color(0xFFE91E63)

/** Colores por defecto de la aurora personalizada (violeta, azul, rosa). */
private val DefaultAuroraColor1 = Color(0xFF7C4DFF)
private val DefaultAuroraColor2 = Color(0xFF2979FF)
private val DefaultAuroraColor3 = Color(0xFFE91E63)

/** Paleta de colores para elegir la aurora personalizada. */
private val AuroraPresetColors = listOf(
    Color(0xFF7C4DFF), Color(0xFF2979FF), Color(0xFFE91E63),
    Color(0xFFFF1744), Color(0xFFFF9100), Color(0xFFFFEA00),
    Color(0xFF00E676), Color(0xFF00BCD4), Color(0xFF00E5FF),
    Color(0xFFAA00FF), Color(0xFFFF4081), Color(0xFFFFFFFF),
)

/**
 * Aurora: nuevo diseño de reproductor.
 *
 * La aurora de fondo tiene DOS modos:
 * - DINÁMICA: los colores salen de la paleta real de la carátula de la
 *   canción (Palette: vibrante, dominante y claro vibrante) y cambian con
 *   cada canción, con transición animada.
 * - PERSONALIZADA: el usuario elige los 3 colores de la aurora (se guardan
 *   en preferencias) desde la hoja de ajustes (botón de paleta).
 *
 * Además: carátula grande con sombra, pastilla Spotify Connect bien visible
 * (muestra en qué dispositivo suena y abre la hoja para cambiar de
 * dispositivo), barra de progreso, controles y acciones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuroraPlayer(
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
    val linerManager = remember { LinerConnectManager.getInstance(context) }
    val linerConnState by linerManager.connectionState.collectAsState()
    val linerDeviceName by linerManager.connectedDeviceName.collectAsState()
    val isConnectedToDesktop = linerConnState == ConnectState.CONNECTED
    val activeDeviceName = if (isConnectedToDesktop) {
        linerDeviceName ?: "Liner Desktop"
    } else {
        LinerConnectManager.getExactPhoneDeviceName(context)
    }
    var showDevicePicker by remember { mutableStateOf(false) }
    var showAuroraSettings by remember { mutableStateOf(false) }

    // ---- Modo aurora + colores personalizados (guardados) ----
    val (auroraMode, onAuroraModeChange) = rememberEnumPreference(AuroraModeKey, AuroraMode.DYNAMIC)
    val (customColor1, onCustomColor1Change) = rememberPreference(AuroraColor1Key, DefaultAuroraColor1.toArgb())
    val (customColor2, onCustomColor2Change) = rememberPreference(AuroraColor2Key, DefaultAuroraColor2.toArgb())
    val (customColor3, onCustomColor3Change) = rememberPreference(AuroraColor3Key, DefaultAuroraColor3.toArgb())

    // ---- Paleta dinámica de la carátula (3 colores para la aurora) ----
    var targetGlow1 by remember { mutableStateOf(DefaultAuroraColor1) }
    var targetGlow2 by remember { mutableStateOf(DefaultAuroraColor2) }
    var targetGlow3 by remember { mutableStateOf(DefaultAuroraColor3) }

    LaunchedEffect(mediaMetadata?.id) {
        val thumbnailUrl = mediaMetadata?.thumbnailUrl ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(thumbnailUrl)
                .size(100, 100)
                .allowHardware(false)
                .build()
            val result = runCatching { context.imageLoader.execute(request) }.getOrNull()
            val bitmap = result?.image?.toBitmap() ?: return@withContext
            val palette = withContext(Dispatchers.Default) {
                Palette.from(bitmap).maximumColorCount(16).generate()
            }
            // Tres colores distintos de la portada: vibrante, dominante y
            // claro vibrante (o apagado si no hay claro). Se ajustan a HSL
            // para que la aurora se vea viva pero no sobreexpuesta.
            val fallback = DefaultAuroraColor1.toArgb()
            val vibrant = palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb ?: fallback
            val dominant = palette.dominantSwatch?.rgb ?: vibrant
            val light = palette.lightVibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: palette.lightMutedSwatch?.rgb
                ?: dominant
            fun toGlowColor(rgb: Int, defaultLightness: Float): Color {
                val hsl = FloatArray(3)
                androidx.core.graphics.ColorUtils.colorToHSL(rgb, hsl)
                hsl[1] = hsl[1].coerceIn(0.45f, 0.85f)
                hsl[2] = hsl[2].coerceIn(0.30f, 0.60f)
                return Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
            }
            val glow1 = toGlowColor(vibrant, 0.5f)
            val glow2 = toGlowColor(dominant, 0.45f)
            val glow3 = toGlowColor(light, 0.55f)
            withContext(Dispatchers.Main) {
                targetGlow1 = glow1
                targetGlow2 = glow2
                targetGlow3 = glow3
            }
        }
    }

    // Colores finales de la aurora según el modo, con transición animada.
    val dynamicGlow1 by animateColorAsState(targetValue = targetGlow1, animationSpec = tween(600), label = "glow1")
    val dynamicGlow2 by animateColorAsState(targetValue = targetGlow2, animationSpec = tween(600), label = "glow2")
    val dynamicGlow3 by animateColorAsState(targetValue = targetGlow3, animationSpec = tween(600), label = "glow3")
    val glow1 = if (auroraMode == AuroraMode.DYNAMIC) dynamicGlow1 else Color(customColor1)
    val glow2 = if (auroraMode == AuroraMode.DYNAMIC) dynamicGlow2 else Color(customColor2)
    val glow3 = if (auroraMode == AuroraMode.DYNAMIC) dynamicGlow3 else Color(customColor3)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AuroraNight),
    ) {
        // Aurora boreal a pantalla completa: cortinas de luz animadas que se
        // desvanecen dentro del lienzo (sin bordes ni cortes duros).
        AuroraBorealisBackground(
            color1 = glow1,
            color2 = glow2,
            color3 = glow3,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                // Respeta barra de estado y de navegación: nada se mete bajo
                // los iconos del sistema arriba ni bajo los botones abajo.
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp)
                .padding(top = 4.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Barra superior: bajar, título de la cola, aurora, menú.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { state.collapseSoft() }) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = "Bajar",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = queueTitle ?: "Reproduciendo",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                IconButton(onClick = { showAuroraSettings = true }) {
                    Icon(
                        painter = painterResource(R.drawable.palette),
                        contentDescription = "Colores de la aurora",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(onClick = onMenuClick) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert),
                        contentDescription = "Menú",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // Carátula cuadrada que ocupa el espacio libre y se encoge en
            // pantallas pequeñas en lugar de empujar los controles fuera.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = mediaMetadata?.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .shadow(24.dp, RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp))
                        .clickable { onVisualizerClick() },
                )
            }

            // Título + artista + like.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mediaMetadata?.title ?: "",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = mediaMetadata?.artists?.joinToString { it.name } ?: "",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
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
                        contentDescription = "Me gusta",
                        tint = if (currentSongLiked) AuroraPink else Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Pastilla Spotify Connect: en qué dispositivo suena.
            Surface(
                onClick = { showDevicePicker = true },
                shape = RoundedCornerShape(16.dp),
                color = if (isConnectedToDesktop) AuroraGreen.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f),
                contentColor = if (isConnectedToDesktop) AuroraGreen else Color.White,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.speaker),
                        contentDescription = "Dispositivos",
                        tint = if (isConnectedToDesktop) AuroraGreen else Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = activeDeviceName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isConnectedToDesktop) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "• Sonando aquí",
                            fontSize = 12.sp,
                            color = AuroraGreen.copy(alpha = 0.8f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Barra de progreso: pista continua de 4 dp, sin hueco ni punto final.
            val sliderMax = maxOf(duration.toFloat(), 1f)
            Slider(
                value = currentPos.toFloat().coerceIn(0f, sliderMax),
                valueRange = 0f..sliderMax,
                onValueChange = { onSliderPositionChange(it.toLong()) },
                onValueChangeFinished = {
                    playerConnection.player.seekTo(currentPos)
                    onSliderPositionChange(null)
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                },
                track = { sliderState ->
                    val range = sliderState.valueRange.endInclusive - sliderState.valueRange.start
                    val fraction = if (range > 0f) {
                        ((sliderState.value - sliderState.valueRange.start) / range).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.25f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(4.dp)
                                .background(Color.White),
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(makeTimeString(currentPos), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                Text(makeTimeString(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }

            Spacer(Modifier.height(8.dp))

            // Controles.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.shuffle),
                        contentDescription = "Aleatorio",
                        tint = if (shuffleModeEnabled) AuroraGreen else Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(
                    onClick = { playerConnection.seekToPrevious() },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.skip_previous),
                        contentDescription = "Anterior",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
                // Play / pausa grande.
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable { playerConnection.player.togglePlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                        contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                        tint = Color.Black,
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(
                    onClick = { playerConnection.seekToNext() },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.skip_next),
                        contentDescription = "Siguiente",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = { playerConnection.player.toggleRepeatMode() }) {
                    Icon(
                        painter = painterResource(
                            if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.repeat_one else R.drawable.repeat
                        ),
                        contentDescription = "Repetir",
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) AuroraGreen else Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Acciones inferiores: letra, cola, dispositivos, temporizador.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onLyricsClick) {
                    Icon(
                        painter = painterResource(R.drawable.lyrics),
                        contentDescription = "Letra",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(onClick = onQueueClick) {
                    Icon(
                        painter = painterResource(R.drawable.queue_music),
                        contentDescription = "Cola",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(onClick = { showDevicePicker = true }) {
                    Icon(
                        painter = painterResource(R.drawable.speaker),
                        contentDescription = "Dispositivos",
                        tint = if (isConnectedToDesktop) AuroraGreen else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(onClick = onSleepTimerClick) {
                    Icon(
                        painter = painterResource(R.drawable.timer),
                        contentDescription = "Temporizador",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }

    if (showDevicePicker) {
        ConnectDeviceBottomSheet(
            onDismissRequest = { showDevicePicker = false }
        )
    }

    if (showAuroraSettings) {
        AuroraSettingsSheet(
            auroraMode = auroraMode,
            onAuroraModeChange = onAuroraModeChange,
            customColor1 = Color(customColor1),
            customColor2 = Color(customColor2),
            customColor3 = Color(customColor3),
            onCustomColor1Change = { onCustomColor1Change(it.toArgb()) },
            onCustomColor2Change = { onCustomColor2Change(it.toArgb()) },
            onCustomColor3Change = { onCustomColor3Change(it.toArgb()) },
            onDismiss = { showAuroraSettings = false },
        )
    }
}

/**
 * Hoja de ajustes de la aurora: dos modos y selección de colores.
 * - Dinámica: los colores salen de la carátula de cada canción.
 * - Personalizada: el usuario elige los 3 colores (se guardan).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuroraSettingsSheet(
    auroraMode: AuroraMode,
    onAuroraModeChange: (AuroraMode) -> Unit,
    customColor1: Color,
    customColor2: Color,
    customColor3: Color,
    onCustomColor1Change: (Color) -> Unit,
    onCustomColor2Change: (Color) -> Unit,
    onCustomColor3Change: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.palette),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Aurora de fondo",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = "Elige cómo se colorea la aurora detrás de la carátula.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            // Modo dinámico.
            AuroraModeRow(
                title = "Dinámica",
                subtitle = "Los colores salen de la carátula de cada canción",
                isSelected = auroraMode == AuroraMode.DYNAMIC,
                onClick = { onAuroraModeChange(AuroraMode.DYNAMIC) },
            )

            // Modo personalizado.
            AuroraModeRow(
                title = "Personalizada",
                subtitle = "Tú eliges los 3 colores de la aurora",
                isSelected = auroraMode == AuroraMode.CUSTOM,
                onClick = { onAuroraModeChange(AuroraMode.CUSTOM) },
            )

            // Selectores de color (solo en modo personalizado).
            if (auroraMode == AuroraMode.CUSTOM) {
                AuroraColorPickerRow(
                    label = "Luz 1",
                    selectedColor = customColor1,
                    onColorSelected = onCustomColor1Change,
                )
                AuroraColorPickerRow(
                    label = "Luz 2",
                    selectedColor = customColor2,
                    onColorSelected = onCustomColor2Change,
                )
                AuroraColorPickerRow(
                    label = "Luz 3",
                    selectedColor = customColor3,
                    onColorSelected = onCustomColor3Change,
                )

                // Vista previa de la aurora con los colores elegidos.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(customColor1, customColor2, customColor3),
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Así se ve tu aurora",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun AuroraModeRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (isSelected) {
                Icon(
                    painter = painterResource(R.drawable.check),
                    contentDescription = "Seleccionado",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun AuroraColorPickerRow(
    label: String,
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(AuroraPresetColors) { color ->
                val isSelected = color.toArgb() == selectedColor.toArgb()
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (isSelected) 2.dp else 0.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = CircleShape,
                        )
                        .clickable { onColorSelected(color) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            painter = painterResource(R.drawable.check),
                            contentDescription = "Seleccionado",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

private val AuroraNight = Color(0xFF03050B)

/** Parámetros de una cortina de aurora (todas las medidas son fracciones de la pantalla). */
private class AuroraCurtain(
    val color: Color,
    /** Altura del borde inferior brillante en el centro de la pantalla. */
    val baseY: Float,
    /** Inclinación del borde de izquierda a derecha. */
    val slope: Float,
    /** Amplitud y frecuencia de la ondulación del borde. */
    val waveAmp: Float,
    val waveFreq: Float,
    /** Longitud máxima de los rayos hacia arriba. */
    val rayHeight: Float,
    val seed: Float,
    val intensity: Float,
)

/**
 * Paisaje nocturno con aurora boreal, dinámico con los 3 colores recibidos
 * (de la carátula o elegidos por el usuario).
 *
 * Tres capas:
 * 1. Cielo nocturno + estrellas (nítido).
 * 2. Aurora: cortinas formadas por rayos verticales que nacen de un borde
 *    inferior brillante y se apagan hacia arriba, más un brillo ambiental.
 *    Esta capa se difumina (API 31+) para que los rayos se fundan como en
 *    una aurora real. Todos los degradados terminan en transparente, así que
 *    no hay cortes duros en ningún borde.
 * 3. Silueta de montañas con un leve reflejo del color de la aurora y un
 *    velo oscuro inferior para que los controles se lean bien.
 */
@Composable
private fun AuroraBorealisBackground(
    color1: Color,
    color2: Color,
    color3: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "aurora")
    // Deriva lenta de la forma de las cortinas.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 36_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "auroraPhase",
    )
    // Parpadeo suave de los rayos.
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "auroraShimmer",
    )
    val stars = remember {
        val rnd = Random(7)
        List(60) { Triple(rnd.nextFloat(), rnd.nextFloat() * 0.5f, 0.2f + rnd.nextFloat() * 0.8f) }
    }

    Box(modifier = modifier) {
        // 1. Cielo + estrellas.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color(0xFF060B18),
                    0.55f to Color(0xFF05070F),
                    1f to AuroraNight,
                ),
            )
            stars.forEach { (x, y, a) ->
                drawCircle(
                    color = Color.White.copy(alpha = (a * 0.5f).coerceIn(0f, 1f)),
                    radius = 0.8f + a * 1.4f,
                    center = Offset(x * size.width, y * size.height),
                )
            }
        }

        // 2. Aurora (capa difuminada).
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .blur(8.dp, BlurredEdgeTreatment.Unbounded),
        ) {
            val curtains = listOf(
                // Cortina de fondo: ancha y tenue.
                AuroraCurtain(color2, baseY = 0.42f, slope = 0.12f, waveAmp = 0.05f, waveFreq = 1.2f, rayHeight = 0.30f, seed = 0f, intensity = 0.75f),
                // Cortina principal: la más brillante, cruza en diagonal.
                AuroraCurtain(color1, baseY = 0.56f, slope = -0.14f, waveAmp = 0.07f, waveFreq = 1.7f, rayHeight = 0.34f, seed = 1.7f, intensity = 1f),
                // Cortina baja: rayos cortos sobre el horizonte.
                AuroraCurtain(color3, baseY = 0.70f, slope = 0.06f, waveAmp = 0.04f, waveFreq = 2.3f, rayHeight = 0.20f, seed = 3.4f, intensity = 0.8f),
            )
            curtains.forEach { drawAuroraCurtain(it, phase, shimmer) }
        }

        // 3. Montañas + velo inferior.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawAuroraLandscape(color1, color2)
        }
    }
}

/** Una cortina: brillo ambiental + rayos verticales sobre un borde ondulado. */
private fun DrawScope.drawAuroraCurtain(curtain: AuroraCurtain, phase: Float, shimmer: Float) {
    val w = size.width
    val h = size.height
    val pi = PI.toFloat()

    fun baseAt(t: Float): Float =
        h * (curtain.baseY + curtain.slope * (t - 0.5f)) +
            h * curtain.waveAmp * sin(t * curtain.waveFreq * pi + phase + curtain.seed) +
            h * curtain.waveAmp * 0.4f * sin(t * curtain.waveFreq * 2.3f * pi - phase * 1.4f + curtain.seed * 2f)

    // Envolvente: la cortina se apaga suavemente hacia los lados.
    fun envelope(t: Float): Float = sin(pi * t).coerceAtLeast(0f).pow(0.6f)

    // Brillo ambiental: halos circulares completos a lo largo del borde,
    // cada uno se desvanece a transparente dentro de su propio radio.
    val glowRadius = h * 0.16f
    for (k in 0..4) {
        val t = 0.1f + k * 0.2f
        val center = Offset(w * t, baseAt(t) - h * 0.05f)
        val a = (0.16f * curtain.intensity * envelope(t)).coerceIn(0f, 1f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(curtain.color.copy(alpha = a), Color.Transparent),
                center = center,
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = center,
            blendMode = BlendMode.Plus,
        )
    }

    // Rayos: borde inferior brillante (casi blanco) que sube y se apaga.
    val edgeColor = lerp(curtain.color, Color.White, 0.35f)
    val rays = 96
    val rayWidth = w / rays * 1.8f
    for (i in 0 until rays) {
        val t = (i + 0.5f) / rays
        val x = w * t
        val base = baseAt(t)
        val variation = 0.5f + 0.5f * sin(t * 37f + curtain.seed * 5f) * sin(t * 13f + shimmer + curtain.seed)
        val flicker = 0.65f + 0.35f * sin(t * 61f + shimmer * 2f + curtain.seed * 3f)
        val alpha = (curtain.intensity * flicker * envelope(t)).coerceIn(0f, 1f)
        if (alpha <= 0.01f) continue
        val rayTop = base - h * curtain.rayHeight * (0.5f + 0.5f * variation)
        val rayBottom = base + h * 0.02f
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.55f to curtain.color.copy(alpha = 0.16f * alpha),
                0.88f to curtain.color.copy(alpha = 0.50f * alpha),
                0.95f to edgeColor.copy(alpha = 0.70f * alpha),
                1f to Color.Transparent,
                startY = rayTop,
                endY = rayBottom,
            ),
            topLeft = Offset(x - rayWidth / 2f, rayTop),
            size = Size(rayWidth, rayBottom - rayTop),
            blendMode = BlendMode.Plus,
        )
    }
}

/** Dos cordilleras en silueta con un leve reflejo de la aurora y el velo inferior. */
private fun DrawScope.drawAuroraLandscape(color1: Color, color2: Color) {
    val w = size.width
    val h = size.height
    val pi = PI.toFloat()

    fun ridge(baseY: Float, peaks: List<Pair<Float, Float>>): Path {
        val path = Path()
        val steps = 64
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            var y = h * baseY
            peaks.forEach { (freq, amp) -> y -= h * amp * (0.5f + 0.5f * sin(t * freq * pi + freq)) }
            if (i == 0) path.moveTo(0f, y) else path.lineTo(w * t, y)
        }
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        return path
    }

    // Cordillera lejana: recibe algo de luz de la aurora en las cimas.
    val farTop = h * 0.74f
    drawPath(
        path = ridge(0.80f, listOf(2.2f to 0.035f, 5.1f to 0.015f)),
        brush = Brush.verticalGradient(
            0f to lerp(Color(0xFF0B1120), color2, 0.18f),
            0.25f to Color(0xFF070B14),
            1f to AuroraNight,
            startY = farTop,
            endY = h,
        ),
    )
    // Cordillera cercana: casi negra.
    drawPath(
        path = ridge(0.88f, listOf(1.6f to 0.04f, 4.3f to 0.012f, 9.7f to 0.004f)),
        brush = Brush.verticalGradient(
            0f to lerp(Color(0xFF05070D), color1, 0.08f),
            0.3f to Color(0xFF030409),
            1f to AuroraNight,
            startY = h * 0.80f,
            endY = h,
        ),
    )
    // Velo inferior para que los controles se lean bien.
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            0.70f to Color.Transparent,
            1f to AuroraNight.copy(alpha = 0.7f),
        ),
    )
}
