package com.dezzy.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dezzy.music.LocalDatabase
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.models.MediaMetadata
import com.dezzy.music.playback.PlayerConnection
import com.dezzy.music.ui.utils.rememberReduceMotion
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Pantalla del DJ · boceto A «Expresivo» (Material 3 Expressive).
 *
 * En espera: una forma «galleta» grande que respira (toca para empezar), el saludo según la
 * hora en letra grande, «Elige tú» como botón principal y chips de ambiente para empezar con
 * un toque; debajo, lo que has escuchado.
 * En sesión: la forma más chica cambia de color y se deforma mientras el DJ habla, la
 * pastilla de estado (liveRegion), lo que dice el DJ en un globo de diálogo, la canción y
 * los controles. «Pídele algo al DJ» siempre abajo.
 * Con «reducir movimiento» la forma queda quieta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DJDezzyScreen(
    navController: NavController,
    viewModel: DJDezzyViewModel = hiltViewModel()
) {
    var showBottomSheet by remember { mutableStateOf(false) }

    val playerConnection = LocalPlayerConnection.current
    val database = LocalDatabase.current
    val uiState by viewModel.uiState.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val djScript by viewModel.djScript.collectAsState()
    val mediaMetadata by (playerConnection?.mediaMetadata ?: MutableStateFlow<MediaMetadata?>(null)).collectAsState()
    val isPlaying by (playerConnection?.isPlaying ?: MutableStateFlow(false)).collectAsState()
    val isLiked by (playerConnection?.currentSongLiked ?: MutableStateFlow(false)).collectAsState()
    val recentSongs by remember(database) { database.recentlyPlayedSongs(10) }.collectAsState(initial = emptyList())

    val (customDjName) = com.dezzy.music.utils.rememberPreference(com.dezzy.music.constants.DjNameKey, "")
    val djName = if (customDjName.isNotBlank()) "DJ $customDjName" else "DJ"
    val isDjTalking = mediaMetadata?.artists?.any { it.name == "Mezcla Especial" } == true ||
                      mediaMetadata?.title == djName ||
                      uiState == DJUiState.SPEAKING

    // La sesión empieza al tocar la forma (o al pedir algo); no al entrar.
    var sessionStarted by rememberSaveable { mutableStateOf(false) }
    val sessionActive = sessionStarted || uiState != DJUiState.IDLE
    var showCaptions by rememberSaveable { mutableStateOf(true) }

    fun startSession(connection: PlayerConnection?) {
        if (connection != null) {
            sessionStarted = true
            viewModel.startDjSession(connection)
        }
    }

    fun askDj(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotBlank() && playerConnection != null) {
            sessionStarted = true
            viewModel.handleUserRequest(trimmed, playerConnection)
        }
    }

    val startDictation = rememberDjDictation(if (customDjName.isNotBlank()) "DJ $customDjName" else "DJ Dezzy") { askDj(it) }
    val daypart = remember { currentDaypart() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            DjTopBar(
                subtitle = if (sessionActive) daypart.sessionLabel else null,
                onClose = { navController.popBackStack() },
                onSettings = { navController.navigate("settings/ai") },
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!sessionActive) {
                    // ── En espera ──
                    Spacer(Modifier.size(8.dp))
                    DjBlob(
                        size = 220.dp,
                        color = MaterialTheme.colorScheme.primary,
                        lively = false,
                        actionLabel = "Empezar sesión con el DJ",
                        onClick = { startSession(playerConnection) },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.play),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                    Text(
                        text = "${daypart.greeting},\nsoy tu $djName",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(start = 24.dp, end = 24.dp, top = 18.dp)
                            .semantics { heading() },
                    )
                    Text(
                        text = "${daypart.pitch} Toca la forma para empezar.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
                    )
                    Button(
                        onClick = { askDj("Elige tú una vibra fresca y variada que me sorprenda") },
                        enabled = playerConnection != null,
                        contentPadding = PaddingValues(horizontal = 28.dp),
                        modifier = Modifier
                            .padding(top = 20.dp)
                            .heightIn(min = 56.dp),
                    ) {
                        Icon(painterResource(R.drawable.auto_awesome), contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Elige tú, ${customDjName.ifBlank { "Dezzy" }}", style = MaterialTheme.typography.titleMedium)
                    }

                    SectionLabel("Empieza por un ambiente", topPadding = 24.dp)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(DjMoods, key = { it.label }) { mood ->
                            MoodChip(
                                mood = mood,
                                suggested = mood.label == daypart.suggestedMood,
                                enabled = playerConnection != null,
                                onClick = { askDj(mood.request) },
                            )
                        }
                    }

                    if (recentSongs.isNotEmpty()) {
                        SectionLabel("Lo que has estado escuchando", topPadding = 24.dp)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(recentSongs, key = { it.song.id }) { song ->
                                val artist = song.artists.firstOrNull()?.name
                                AsyncImage(
                                    model = song.song.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(88.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .clickable(
                                            role = Role.Button,
                                            onClickLabel = "Pedir algo parecido",
                                        ) {
                                            askDj(
                                                if (artist != null) "Pon algo parecido a ${song.song.title} de $artist"
                                                else "Pon algo parecido a ${song.song.title}"
                                            )
                                        }
                                        .semantics {
                                            contentDescription = listOfNotNull(song.song.title, artist).joinToString(", ")
                                        },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.size(16.dp))
                } else {
                    // ── Sesión en marcha ──
                    val generating = uiState == DJUiState.GENERATING
                    val showPlay = !isPlaying && !isDjTalking && !generating
                    Spacer(Modifier.size(4.dp))
                    DjBlob(
                        size = 150.dp,
                        color = when {
                            isDjTalking -> MaterialTheme.colorScheme.primaryContainer
                            generating -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        },
                        lively = isDjTalking || generating,
                        actionLabel = if (isPlaying) "Pausar" else "Reproducir",
                        onClick = { playerConnection?.player?.togglePlayPause() },
                    ) {
                        val tint = when {
                            isDjTalking -> MaterialTheme.colorScheme.onPrimaryContainer
                            generating -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        }
                        Icon(
                            painter = painterResource(
                                when {
                                    generating -> R.drawable.auto_awesome
                                    showPlay -> R.drawable.play
                                    else -> R.drawable.graphic_eq
                                },
                            ),
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    Spacer(Modifier.size(12.dp))

                    val (stateLabel, stateDetail) = when {
                        generating -> "Generando" to statusText.takeUnless { it.startsWith("Error", ignoreCase = true) }
                        isDjTalking -> "Hablando" to null
                        isPlaying -> "Reproduciendo" to null
                        else -> "En pausa" to null
                    }
                    DjStatePill(label = stateLabel, active = generating || isDjTalking)
                    if (!stateDetail.isNullOrBlank()) {
                        Text(
                            text = stateDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp),
                        )
                    }

                    AnimatedVisibility(
                        visible = showCaptions && isDjTalking && !djScript.isNullOrBlank(),
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        DjSpeechBubble(text = djScript.orEmpty())
                    }

                    mediaMetadata?.let { meta ->
                        NowPlayingCard(
                            metadata = meta,
                            isLiked = isLiked,
                            onToggleLike = { playerConnection?.toggleLike() },
                        )
                    }

                    DjControls(
                        isPlaying = isPlaying,
                        showCaptions = showCaptions,
                        onToggleCaptions = { showCaptions = it },
                        onPrevious = { playerConnection?.seekToPrevious() },
                        onPlayPause = { playerConnection?.player?.togglePlayPause() },
                        onNext = { playerConnection?.seekToNext() },
                        onMore = { showBottomSheet = true },
                    )
                    Spacer(Modifier.size(8.dp))
                }
            }

            AskDjBar(
                enabled = playerConnection != null,
                onOpen = { showBottomSheet = true },
                onDictate = startDictation,
            )
        }

        if (showBottomSheet) {
            DJInteractionBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                viewModel = viewModel,
                playerConnection = playerConnection,
                mediaMetadata = mediaMetadata,
                onRequestSent = { sessionStarted = true },
            )
        }
    }
}

/** Momento del día: saludo, frase de contexto, etiqueta de sesión y ambiente sugerido. */
private data class Daypart(
    val greeting: String,
    val pitch: String,
    val sessionLabel: String,
    val suggestedMood: String,
)

private fun currentDaypart(): Daypart = when (LocalTime.now().hour) {
    in 6..11 -> Daypart(
        greeting = "Buenos días",
        pitch = "Te pongo algo con energía para arrancar el día.",
        sessionLabel = "Sesión de mañana",
        suggestedMood = "Con energía",
    )
    in 12..19 -> Daypart(
        greeting = "Buenas tardes",
        pitch = "Te acompaño con algo a tu medida esta tarde.",
        sessionLabel = "Sesión de tarde",
        suggestedMood = "Para estudiar",
    )
    else -> Daypart(
        greeting = "Buenas noches",
        pitch = "Hoy te pongo algo tranquilo para cerrar el día.",
        sessionLabel = "Sesión de noche",
        suggestedMood = "Tranquilo",
    )
}

private data class DjMood(val label: String, val iconRes: Int, val request: String)

private val DjMoods = listOf(
    DjMood("Tranquilo", R.drawable.ic_mood_relax, "Ponme música tranquila y relajada"),
    DjMood("Para estudiar", R.drawable.ic_mood_focus, "Ponme música para concentrarme y estudiar, sin distracciones"),
    DjMood("Fiesta", R.drawable.ic_mood_party, "Ponme música de fiesta para bailar"),
    DjMood("Con energía", R.drawable.ic_mood_energy, "Ponme música con mucha energía para entrenar"),
    DjMood("Para dormir", R.drawable.bedtime, "Ponme música suave para dormir"),
)

@Composable
private fun SectionLabel(text: String, topPadding: Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = topPadding, bottom = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun MoodChip(
    mood: DjMood,
    suggested: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    SuggestionChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(mood.label) },
        icon = {
            Icon(
                painter = painterResource(mood.iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        colors = if (suggested) {
            SuggestionChipDefaults.suggestionChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            SuggestionChipDefaults.suggestionChipColors()
        },
        border = if (suggested) {
            SuggestionChipDefaults.suggestionChipBorder(enabled = enabled, borderColor = Color.Transparent)
        } else {
            SuggestionChipDefaults.suggestionChipBorder(enabled = enabled)
        },
        modifier = Modifier.semantics {
            if (suggested) stateDescription = "Sugerido para ahora"
        },
    )
}

@Composable
private fun DjTopBar(
    subtitle: String?,
    onClose: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(painterResource(R.drawable.expand_more), contentDescription = "Cerrar")
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "DJ",
                style = if (subtitle == null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelMedium,
                color = if (subtitle == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onSettings) {
            Icon(painterResource(R.drawable.tune), contentDescription = "Ajustes del DJ")
        }
    }
}

/**
 * Forma «galleta» de M3 Expressive: un círculo con [DjBlobLobes] ondas suaves. Gira despacio
 * y, si [lively] (el DJ habla o está generando), las ondas «respiran» más hondo. Con
 * «reducir movimiento» queda quieta. Se dibuja en la fase de dibujo (sin recomponer).
 */
@Composable
private fun DjBlob(
    size: Dp,
    color: Color,
    lively: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val fill by animateColorAsState(targetValue = color, animationSpec = tween(450), label = "djBlobColor")

    val rotation: State<Float>
    val depth: State<Float>
    if (reduceMotion) {
        rotation = remember { mutableFloatStateOf(0f) }
        depth = remember { mutableFloatStateOf(DjBlobRestDepth) }
    } else {
        val transition = rememberInfiniteTransition(label = "djBlob")
        rotation = transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(if (lively) 9_000 else 18_000, easing = LinearEasing)),
            label = "djBlobRotation",
        )
        depth = transition.animateFloat(
            initialValue = DjBlobRestDepth,
            targetValue = if (lively) 0.16f else 0.10f,
            animationSpec = infiniteRepeatable(tween(if (lively) 650 else 2_400), RepeatMode.Reverse),
            label = "djBlobDepth",
        )
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
            .semantics { contentDescription = "DJ" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawPath(blobPath(this.size, rotation.value, depth.value), fill)
        }
        content()
    }
}

private const val DjBlobLobes = 9
private const val DjBlobRestDepth = 0.07f

private fun blobPath(size: Size, rotation: Float, depth: Float): Path {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = min(cx, cy)
    val steps = 144
    val path = Path()
    for (i in 0..steps) {
        val t = (i.toFloat() / steps) * (2 * PI).toFloat()
        // Radio entre radius·(1 − depth) y radius: ondas redondeadas, nunca puntas.
        val r = radius * (1f - depth * (1f - cos(DjBlobLobes * t + rotation)) / 2f)
        val x = cx + r * cos(t)
        val y = cy + r * sin(t)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

@Composable
private fun DjStatePill(label: String, active: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
            contentDescription = "Estado del DJ: $label"
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Lo que dice el DJ, como globo de diálogo (esquina inferior izquierda casi recta). */
@Composable
private fun DjSpeechBubble(text: String) {
    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = "TU DJ",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Normal,
                fontSize = 18.sp,
                lineHeight = 26.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun NowPlayingCard(
    metadata: MediaMetadata,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = metadata.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = metadata.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metadata.artists.joinToString { it.name }.ifEmpty { "Desconocido" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconToggleButton(checked = isLiked, onCheckedChange = { onToggleLike() }) {
                Icon(
                    painter = painterResource(if (isLiked) R.drawable.favorite else R.drawable.favorite_border),
                    contentDescription = if (isLiked) "Quitar de Me gusta" else "Me gusta",
                    tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DjControls(
    isPlaying: Boolean,
    showCaptions: Boolean,
    onToggleCaptions: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconToggleButton(checked = showCaptions, onCheckedChange = onToggleCaptions) {
            Icon(
                painter = painterResource(R.drawable.closed_caption),
                contentDescription = if (showCaptions) "Ocultar subtítulos" else "Mostrar subtítulos",
                tint = if (showCaptions) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPrevious) {
            Icon(painterResource(R.drawable.skip_previous), contentDescription = "Anterior", modifier = Modifier.size(28.dp))
        }
        // Botón principal M3 Expressive: cuadrado redondeado grande en vez de círculo.
        FilledIconButton(
            onClick = onPlayPause,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.size(72.dp),
        ) {
            Icon(
                painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                modifier = Modifier.size(32.dp),
            )
        }
        IconButton(onClick = onNext) {
            Icon(painterResource(R.drawable.skip_next), contentDescription = "Siguiente", modifier = Modifier.size(28.dp))
        }
        IconButton(onClick = onMore) {
            Icon(painterResource(R.drawable.more_vert), contentDescription = "Más opciones del DJ")
        }
    }
}

@Composable
private fun AskDjBar(
    enabled: Boolean,
    onOpen: () -> Unit,
    onDictate: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
            .heightIn(min = 56.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Pídele algo al DJ",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(
                onClick = onDictate,
                enabled = enabled,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(painterResource(R.drawable.mic), contentDescription = "Dictar petición")
            }
        }
    }
}
