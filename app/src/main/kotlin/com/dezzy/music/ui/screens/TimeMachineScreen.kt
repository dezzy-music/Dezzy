package com.dezzy.music.ui.screens

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dezzy.music.LocalDatabase
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.db.entities.PlaylistEntity
import com.dezzy.music.db.entities.PlaylistSongMap
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.extensions.toggleRepeatMode
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.models.toMediaMetadata
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.ui.component.EmptyPlaceholder
import com.dezzy.music.ui.component.shimmer.ListItemPlaceHolder
import com.dezzy.music.ui.component.shimmer.ShimmerHost
import com.dezzy.music.ui.utils.rememberReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Máquina del tiempo — Fase 11, boceto A «Panel de mando».
 * Década en chips, año con deslizador o − / +, y «Viajar». El portal con la
 * cuenta de años se mantiene como transición. Al llegar, el aparato de la época
 * es la portada de un reproductor con controles iguales en todas las décadas.
 */

/** Cada década: chip, aparato de época, descripción y colores de su ilustración. */
private data class Era(
    val decade: Int,
    val chip: String,
    val device: String,
    val description: String,
    val start: Color,
    val end: Color,
    val years: IntRange,
)

private val Eras = listOf(
    Era(1970, "70s", "Vinilo disco", "Disco, funk y rock en vinilo", Color(0xFFB24592), Color(0xFFF15F79), 1970..1979),
    Era(1980, "80s", "Cassette synthwave", "Neon synthwave y electropop", Color(0xFF8E2DE2), Color(0xFF4A00E0), 1980..1989),
    Era(1990, "90s", "CD grunge", "Grunge, CD y eurodance", Color(0xFF1F1C2C), Color(0xFF928DAB), 1990..1999),
    Era(2000, "00s", "MP3 clásico", "La era del iPod y Winamp", Color(0xFF00C9FF), Color(0xFF92FE9D), 2000..2009),
    Era(2010, "10s", "Smartphone", "Streaming y pantallas de cristal", Color(0xFF1E3C72), Color(0xFF2A5298), 2010..2019),
    Era(2020, "20s", "Altavoz holográfico", "IA, wearables y sonido holográfico", Color(0xFF7B2CBF), Color(0xFFE0AAFF),
        2020..LocalDate.now().year.coerceIn(2026, 2029)),
)

private fun eraOf(year: Int): Era = Eras.lastOrNull { year >= it.decade } ?: Eras.first()

/** Fondo oscurecido de la cabecera: conserva el color de la época y deja texto blanco legible. */
private fun Era.heroBrush(): Brush =
    Brush.verticalGradient(listOf(lerp(start, Color.Black, 0.35f), lerp(end, Color.Black, 0.62f)))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeMachineScreen(
    navController: NavController
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val isLiked by playerConnection.currentSongLiked.collectAsState()
    val shuffleOn by playerConnection.shuffleModeEnabled.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val reduceMotion = rememberReduceMotion()

    // Destino elegido en el panel (década + año) y año de llegada.
    var selectedYear by rememberSaveable { mutableIntStateOf(1987) }
    var activeYear by rememberSaveable { mutableStateOf<Int?>(null) }
    var isTimeTraveling by remember { mutableStateOf(false) }
    var songs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isLoadingSongs by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val selectedEra = eraOf(selectedYear)
    val activeEra = activeYear?.let(::eraOf)

    val handleBackPress = {
        if (activeYear != null) {
            activeYear = null
            songs = emptyList()
        } else {
            navController.navigateUp()
        }
    }

    var currentPortalYear by remember { mutableIntStateOf(2026) }
    LaunchedEffect(isTimeTraveling) {
        if (isTimeTraveling) {
            val target = activeYear ?: selectedYear
            val step = if (target < currentPortalYear) -1 else 1
            // Con «reducir movimiento» la cuenta salta directamente al destino.
            val delayDuration = if (reduceMotion) 0L else 40L
            if (reduceMotion) currentPortalYear = target
            while (currentPortalYear != target) {
                currentPortalYear += step
                delay(delayDuration)
            }
            delay(if (reduceMotion) 150 else 500)
            isTimeTraveling = false

            isLoadingSongs = true
            error = null
            scope.launch {
                val query = "hits $target"
                withContext(Dispatchers.IO) {
                    YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                }.onSuccess { result ->
                    songs = result.items.filterIsInstance<SongItem>()
                    isLoadingSongs = false
                }.onFailure { err ->
                    error = err.localizedMessage
                    isLoadingSongs = false
                }
            }
        }
    }

    fun travel() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        activeYear = selectedYear
        currentPortalYear = 2026
        isTimeTraveling = true
    }

    fun saveTrip(year: Int) {
        val list = songs.map { it.toMediaMetadata() }
        if (list.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                database.transaction {
                    val playlist = PlaylistEntity(
                        name = "Sonaba en $year",
                        thumbnailUrl = songs.firstOrNull()?.thumbnail,
                        bookmarkedAt = LocalDateTime.now(),
                        isEditable = true,
                    )
                    insert(playlist)
                    list.forEach { insert(it) }
                    list.forEachIndexed { index, song ->
                        insert(PlaylistSongMap(songId = song.id, playlistId = playlist.id, position = index))
                    }
                }
            }
            snackbarHostState.showSnackbar("Guardada como «Sonaba en $year»")
        }
    }

    if (isTimeTraveling) {
        TimeTravelPortal(year = currentPortalYear, animate = !reduceMotion)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (activeYear == null || activeEra == null) {
                        Text("Máquina del tiempo")
                    } else {
                        Column {
                            Text(activeYear.toString())
                            Text(
                                text = activeEra.device,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { handleBackPress() }) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = "Atrás")
                    }
                },
                actions = {
                    if (activeYear == null) {
                        IconButton(onClick = {
                            val era = Eras.random()
                            selectedYear = era.years.random()
                        }) {
                            Icon(painterResource(R.drawable.casino), contentDescription = "Año al azar")
                        }
                    } else {
                        IconButton(onClick = { handleBackPress() }) {
                            Icon(painterResource(R.drawable.swap_horiz), contentDescription = "Cambiar destino")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { paddingValues ->
        val year = activeYear
        if (year == null || activeEra == null) {
            DestinationPanel(
                era = selectedEra,
                year = selectedYear,
                onYearChange = { selectedYear = it },
                onSelectEra = { era ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    // Conserva la posición dentro de la década al cambiar de época.
                    selectedYear = (era.decade + (selectedYear - selectedEra.decade)).coerceIn(era.years)
                },
                onTravel = { travel() },
                modifier = Modifier.padding(paddingValues),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = paddingValues.calculateBottomPadding() + 24.dp,
                ),
            ) {
                item(key = "player") {
                    EraPlayer(
                        era = activeEra,
                        title = mediaMetadata?.title ?: "Elige una canción",
                        artist = mediaMetadata?.artists?.joinToString { it.name }.orEmpty().ifBlank { "Radio del tiempo" },
                        artworkUrl = mediaMetadata?.thumbnailUrl,
                        isPlaying = isPlaying,
                        isLiked = isLiked,
                        shuffleOn = shuffleOn,
                        repeatMode = repeatMode,
                        player = playerConnection.player,
                        mediaId = mediaMetadata?.id,
                        onToggleLike = { playerConnection.toggleLike() },
                        onShuffle = { playerConnection.player.shuffleModeEnabled = !shuffleOn },
                        onPrevious = { playerConnection.seekToPrevious() },
                        onPlayPause = { playerConnection.player.togglePlayPause() },
                        onNext = { playerConnection.seekToNext() },
                        onRepeat = { playerConnection.player.toggleRepeatMode() },
                    )
                }

                item(key = "list-head") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Sonaba en $year",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { heading() },
                        )
                        TextButton(onClick = { saveTrip(year) }, enabled = songs.isNotEmpty()) {
                            Text("Guardar lista")
                        }
                    }
                }

                when {
                    isLoadingSongs -> item(key = "loading") {
                        ShimmerHost {
                            repeat(6) { ListItemPlaceHolder() }
                        }
                    }
                    error != null -> item(key = "error") {
                        EmptyPlaceholder(icon = R.drawable.sync, text = error ?: "Error desconocido")
                    }
                    else -> itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                        TripSongRow(
                            rank = index + 1,
                            song = song,
                            isActive = mediaMetadata?.id == song.id,
                            isPlaying = isPlaying,
                            onClick = {
                                playerConnection.playQueue(
                                    YouTubeQueue(WatchEndpoint(videoId = song.id), song.toMediaMetadata())
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

// ── Panel de destino ─────────────────────────────────────────────────────────

@Composable
private fun DestinationPanel(
    era: Era,
    year: Int,
    onYearChange: (Int) -> Unit,
    onSelectEra: (Era) -> Unit,
    onTravel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            items(Eras, key = { it.decade }) { item ->
                val selected = item.decade == era.decade
                FilterChip(
                    selected = selected,
                    onClick = { onSelectEra(item) },
                    label = { Text(item.chip) },
                    leadingIcon = if (selected) {
                        { Icon(painterResource(R.drawable.check), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                )
            }
        }

        // Cabecera con el aparato de la época y el año de destino
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(era.heroBrush())
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EraDevice(era = era, artworkUrl = null, spinning = false, height = 112.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "DESTINO",
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 2.sp,
                color = Color.White.copy(alpha = 0.85f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { onYearChange((year - 1).coerceIn(era.years)) },
                    enabled = year > era.years.first,
                ) {
                    Icon(painterResource(R.drawable.remove), contentDescription = "Año anterior")
                }
                Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .semantics { contentDescription = "Año de destino $year" },
                )
                FilledTonalIconButton(
                    onClick = { onYearChange((year + 1).coerceIn(era.years)) },
                    enabled = year < era.years.last,
                ) {
                    Icon(painterResource(R.drawable.add), contentDescription = "Año siguiente")
                }
            }
            Text(
                text = "${era.device}, ${era.description.replaceFirstChar { it.lowercase() }}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }

        // Deslizador de año dentro de la década
        val range = era.years
        Slider(
            value = year.toFloat(),
            onValueChange = { onYearChange(it.roundToInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp)
                .semantics { stateDescription = year.toString() },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf(range.first, (range.first + range.last) / 2, range.last).forEach {
                Text(
                    text = it.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = onTravel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 24.dp)
                .heightIn(min = 56.dp),
        ) {
            Icon(painterResource(R.drawable.rocket_launch), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Viajar a $year", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = "Buscaremos los éxitos de ese año y armaremos una cola a tu gusto.",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

// ── Reproductor de llegada ───────────────────────────────────────────────────

@Composable
private fun EraPlayer(
    era: Era,
    title: String,
    artist: String,
    artworkUrl: String?,
    isPlaying: Boolean,
    isLiked: Boolean,
    shuffleOn: Boolean,
    repeatMode: Int,
    player: Player,
    mediaId: String?,
    onToggleLike: () -> Unit,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
) {
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying, mediaId) {
        while (true) {
            val duration = player.duration
            progress = if (duration > 0) (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
            if (!isPlaying) break
            delay(500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(era.heroBrush())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EraDevice(era = era, artworkUrl = artworkUrl, spinning = isPlaying, height = 140.dp)
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconToggleButton(checked = isLiked, onCheckedChange = { onToggleLike() }) {
                Icon(
                    painter = painterResource(if (isLiked) R.drawable.favorite else R.drawable.favorite_border),
                    contentDescription = if (isLiked) "Quitar de Me gusta" else "Me gusta",
                    tint = Color.White,
                )
            }
        }
        LinearProgressIndicator(
            progress = { progress },
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.25f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val onHero = Color.White
            IconToggleButton(checked = shuffleOn, onCheckedChange = { onShuffle() }) {
                Icon(
                    painterResource(if (shuffleOn) R.drawable.shuffle_on else R.drawable.shuffle),
                    contentDescription = if (shuffleOn) "Desactivar aleatorio" else "Activar aleatorio",
                    tint = if (shuffleOn) onHero else onHero.copy(alpha = 0.7f),
                )
            }
            IconButton(onClick = onPrevious) {
                Icon(painterResource(R.drawable.skip_previous), contentDescription = "Anterior", tint = onHero, modifier = Modifier.size(30.dp))
            }
            FilledIconButton(
                onClick = onPlayPause,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = lerp(era.start, Color.Black, 0.4f)),
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                    contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(onClick = onNext) {
                Icon(painterResource(R.drawable.skip_next), contentDescription = "Siguiente", tint = onHero, modifier = Modifier.size(30.dp))
            }
            val repeatIcon = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
                else -> R.drawable.repeat
            }
            IconButton(onClick = onRepeat) {
                Icon(
                    painterResource(repeatIcon),
                    contentDescription = when (repeatMode) {
                        Player.REPEAT_MODE_ONE -> "Repetir una"
                        Player.REPEAT_MODE_ALL -> "Repetir todo"
                        else -> "Repetir desactivado"
                    },
                    tint = if (repeatMode == Player.REPEAT_MODE_OFF) onHero.copy(alpha = 0.7f) else onHero,
                )
            }
        }
    }
}

@Composable
private fun TripSongRow(
    rank: Int,
    song: SongItem,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(28.dp),
        )
        Spacer(Modifier.width(8.dp))
        AsyncImage(
            model = song.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artists.joinToString { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isActive && isPlaying) {
            Icon(
                painter = painterResource(R.drawable.graphic_eq),
                contentDescription = "Sonando",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            song.duration?.let { seconds ->
                Text(
                    text = "%d:%02d".format(seconds / 60, seconds % 60),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Ilustraciones de época ───────────────────────────────────────────────────

/** Ángulo de giro para discos y bobinas; 0 si no suena o con «reducir movimiento». */
@Composable
private fun rememberSpinAngle(spinning: Boolean, periodMs: Int): Float {
    val reduceMotion = rememberReduceMotion()
    if (!spinning || reduceMotion) return 0f
    val transition = rememberInfiniteTransition(label = "eraSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
        label = "eraSpinAngle",
    )
    return angle
}

/** El aparato de cada década, usado como portada (ilustración decorativa). */
@Composable
private fun EraDevice(era: Era, artworkUrl: String?, spinning: Boolean, height: Dp) {
    Box(
        modifier = Modifier
            .height(height)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        when (era.decade) {
            1970 -> VinylDevice(artworkUrl, spinning, height)
            1980 -> CassetteDevice(artworkUrl, spinning, height)
            1990 -> CdDevice(artworkUrl, spinning, height)
            2000 -> Mp3Device(artworkUrl, height)
            2010 -> PhoneDevice(artworkUrl, height)
            else -> SpeakerDevice(artworkUrl, spinning, height)
        }
    }
}

@Composable
private fun VinylDevice(artworkUrl: String?, spinning: Boolean, height: Dp) {
    val angle = rememberSpinAngle(spinning, 1800)
    Box(
        modifier = Modifier
            .size(height)
            .graphicsLayer(rotationZ = angle)
            .clip(CircleShape)
            .background(Color(0xFF111111))
            .drawBehind {
                val r = size.minDimension / 2
                for (i in 1..6) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.08f),
                        radius = r * (0.45f + i * 0.08f),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        EraArtwork(artworkUrl, Modifier.size(height * 0.38f).clip(CircleShape), fallback = Color(0xFFF15F79))
        Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF111111)))
    }
}

@Composable
private fun CassetteDevice(artworkUrl: String?, spinning: Boolean, height: Dp) {
    val angle = rememberSpinAngle(spinning, 3000)
    val bodyWidth = height * 1.6f
    Column(
        modifier = Modifier
            .width(bodyWidth)
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1B1530))
            .border(2.dp, Brush.linearGradient(listOf(Color(0xFFFF007F), Color(0xFF00F0FF))), RoundedCornerShape(12.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Etiqueta con la portada
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFFFF007F), Color(0xFF7B2CBF)))),
        ) {
            EraArtwork(artworkUrl, Modifier.fillMaxSize(), fallback = Color.Transparent, alpha = 0.55f)
            // Ventana con las dos bobinas
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC0C071E))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(height * 0.28f),
            ) {
                CassetteReel(rotationAngle = angle, size = height * 0.26f)
                CassetteReel(rotationAngle = angle, size = height * 0.26f)
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth(0.6f)
                .height(height * 0.14f)
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(Color(0xFF2A2342)),
        )
    }
}

@Composable
private fun CdDevice(artworkUrl: String?, spinning: Boolean, height: Dp) {
    val angle = rememberSpinAngle(spinning, 1400)
    Box(
        modifier = Modifier
            .size(height)
            .graphicsLayer(rotationZ = angle)
            .clip(CircleShape)
            .background(
                Brush.sweepGradient(
                    listOf(Color(0xFFC9D6FF), Color(0xFFFFD1F7), Color(0xFFB8FFF0), Color(0xFFFFF5B8), Color(0xFFC9D6FF)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        EraArtwork(artworkUrl, Modifier.fillMaxSize().clip(CircleShape), fallback = Color.Transparent, alpha = 0.6f)
        Box(
            Modifier
                .size(height * 0.28f)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.7f)),
        )
        Box(Modifier.size(height * 0.1f).clip(CircleShape).background(Color(0xFF1F1C2C)))
    }
}

@Composable
private fun Mp3Device(artworkUrl: String?, height: Dp) {
    val bodyWidth = height * 0.64f
    Column(
        modifier = Modifier
            .width(bodyWidth)
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFFF4F6F7), Color(0xFFBDC3C7))))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EraArtwork(
            artworkUrl,
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF9FD3E6)),
            fallback = Color(0xFF9FD3E6),
        )
        Spacer(Modifier.height(8.dp))
        // Rueda de clic
        Box(
            modifier = Modifier
                .size(bodyWidth * 0.62f)
                .clip(CircleShape)
                .background(Color.White)
                .border(1.dp, Color(0xFFAAB2B7), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(bodyWidth * 0.22f).clip(CircleShape).background(Color(0xFFD5DBDE)))
        }
    }
}

@Composable
private fun PhoneDevice(artworkUrl: String?, height: Dp) {
    Box(
        modifier = Modifier
            .width(height * 0.52f)
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0D1B2A))
            .border(2.dp, Color(0xFF3498DB).copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(horizontal = 5.dp, vertical = 12.dp),
    ) {
        EraArtwork(
            artworkUrl,
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp)),
            fallback = Color(0xFF2A5298),
        )
    }
}

@Composable
private fun SpeakerDevice(artworkUrl: String?, spinning: Boolean, height: Dp) {
    val transition = rememberInfiniteTransition(label = "holo")
    val reduceMotion = rememberReduceMotion()
    val float by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "holoFloat",
    )
    val lift = if (spinning && !reduceMotion) float * 4f else 0f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Holograma con la portada
        EraArtwork(
            artworkUrl,
            Modifier
                .offset(y = (-lift).dp)
                .size(height * 0.42f)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFFE0AAFF), RoundedCornerShape(10.dp)),
            fallback = Color(0xFF7B2CBF),
            alpha = 0.85f,
        )
        Canvas(Modifier.width(height * 0.5f).height(height * 0.12f)) {
            drawLine(
                brush = Brush.verticalGradient(listOf(Color(0x00E0AAFF), Color(0xAAE0AAFF))),
                start = Offset(size.width / 2, 0f),
                end = Offset(size.width / 2, size.height),
                strokeWidth = size.width * 0.6f,
                cap = StrokeCap.Butt,
            )
        }
        // Cuerpo del altavoz con el anillo de luz
        Box(
            modifier = Modifier
                .width(height * 0.5f)
                .height(height * 0.42f)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 10.dp, bottomEnd = 10.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF2B2140), Color(0xFF120D1F)))),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth(0.8f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(Color(0xFF7B2CBF), Color(0xFFE0AAFF), Color(0xFF00F0FF)))),
            )
        }
    }
}

@Composable
private fun EraArtwork(url: String?, modifier: Modifier, fallback: Color, alpha: Float = 1f) {
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = alpha,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(fallback))
    }
}

@Composable
fun CassetteReel(rotationAngle: Float, size: Dp = 48.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer(rotationZ = rotationAngle)
            .drawBehind {
                drawCircle(
                    color = Color.Black,
                    radius = this.size.width / 2
                )
                drawCircle(
                    color = Color(0xFF00F0FF),
                    radius = this.size.width / 3,
                    style = Stroke(width = 2.dp.toPx())
                )
                val center = Offset(this.size.width / 2, this.size.height / 2)
                for (i in 0 until 6) {
                    val angle = Math.toRadians((i * 60).toDouble())
                    val endX = center.x + (this.size.width / 3) * cos(angle).toFloat()
                    val endY = center.y + (this.size.width / 3) * sin(angle).toFloat()
                    drawLine(
                        color = Color(0xFF00F0FF),
                        start = center,
                        end = Offset(endX, endY),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
    )
}

// ── Portal de viaje (se conserva; estático con «reducir movimiento») ─────────

@Composable
fun TimeTravelPortal(year: Int, animate: Boolean = true) {
    val infiniteTransition = rememberInfiniteTransition(label = "portal")

    val animatedRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "portalRotation"
    )

    val animatedScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "portalScale"
    )
    val rotationAngle = if (animate) animatedRotation else 0f
    val scaleMultiplier = if (animate) animatedScale else 1f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070412)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(320.dp)
                .graphicsLayer(
                    rotationZ = rotationAngle,
                    scaleX = scaleMultiplier,
                    scaleY = scaleMultiplier
                )
                .drawBehind {
                    val brushColors = listOf(Color(0xFFFF007F), Color(0xFF00F0FF), Color(0xFF9000FF))
                    drawCircle(
                        brush = Brush.sweepGradient(brushColors),
                        radius = size.width / 2,
                        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                    )

                    drawCircle(
                        brush = Brush.sweepGradient(brushColors.reversed()),
                        radius = (size.width / 2) - 30.dp.toPx(),
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.semantics(mergeDescendants = true) { }
        ) {
            Text(
                text = "VIAJANDO EN EL TIEMPO",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF00F0FF),
                    letterSpacing = 4.sp
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = year.toString(),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 72.sp,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Cargando nostalgia...",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color.White.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace
                )
            )
        }
    }
}
