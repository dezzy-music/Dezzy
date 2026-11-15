/*
 * Dezzy Insight — Year in Music, redesigned (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens

import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.db.entities.Album
import com.dezzy.music.db.entities.Artist
import com.dezzy.music.db.entities.Song
import com.dezzy.music.db.entities.SongWithStats
import com.dezzy.music.ui.component.IconButton
import com.dezzy.music.ui.component.LocalMenuState
import com.dezzy.music.ui.menu.ArtistMenu
import com.dezzy.music.ui.menu.SongMenu
import com.dezzy.music.ui.utils.backToMain
import com.dezzy.music.ui.utils.rememberReduceMotion
import com.dezzy.music.utils.ComposeToImage
import com.dezzy.music.utils.makeTimeString
import com.dezzy.music.viewmodels.YearInMusicViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.NumberFormat
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.coroutines.resume
import androidx.compose.material3.IconButton as M3IconButton

/**
 * Tu año en música — Fase 11, boceto A «Historias con ritmo».
 * Mismo pase de páginas (8 como máximo), ahora con barra por segmentos, avance
 * automático con pausa, toques a izquierda y derecha, un color de la paleta por
 * página y «Compartir» en cada una. El resumen es una tarjeta clara lista para
 * compartir o guardar como imagen.
 */

/** Tiempo que dura cada página con el avance automático. */
private const val StoryDurationMs = 8_000

private val EsLocale: Locale = Locale.forLanguageTag("es-ES")

private fun Long.formatEs(): String = NumberFormat.getIntegerInstance(EsLocale).format(this)

// ─────────────────────────────────────────────────────────────────────────────
// Perfil de oyente
// ─────────────────────────────────────────────────────────────────────────────

private data class InsightPersonality(
    val icon: Int,
    val title: String,
    val description: String,
)

private val PersonalityDevoted = InsightPersonality(
    icon = R.drawable.favorite,
    title = "Fan fiel",
    description = "Cuando una canción te atrapa, la pones una y otra vez. Tu lealtad a tus favoritas no tiene rival.",
)
private val PersonalityExplorer = InsightPersonality(
    icon = R.drawable.auto_awesome,
    title = "Explorador",
    description = "Siempre descubriendo, nunca conformándote. Tu gusto abarca mundos y tu cola nunca suena igual dos veces.",
)
private val PersonalityAudiophile = InsightPersonality(
    icon = R.drawable.headphones,
    title = "Audiófilo",
    description = "Las horas se te pasan volando cuando estás en tu zona. La música no es ruido de fondo para ti: lo es todo.",
)
private val PersonalityCasual = InsightPersonality(
    icon = R.drawable.waves,
    title = "Oyente relajado",
    description = "La música fluye con tu vida. Siempre está ahí cuando la necesitas, fácil y en sintonía.",
)

private fun computePersonality(
    topSongs: List<SongWithStats>,
    totalPlayed: Long,
    totalTimeMs: Long,
): InsightPersonality {
    if (topSongs.isEmpty()) return PersonalityCasual
    val topRatio      = topSongs.first().songCountListened.toFloat() / totalPlayed.coerceAtLeast(1)
    val hoursListened = totalTimeMs / 3_600_000L
    return when {
        topRatio >= 0.25f   -> PersonalityDevoted
        hoursListened >= 50 -> PersonalityAudiophile
        topSongs.size >= 5  -> PersonalityExplorer
        else                -> PersonalityCasual
    }
}

private fun timesLabel(count: Int): String = if (count == 1) "1 vez" else "$count veces"

// ─────────────────────────────────────────────────────────────────────────────
// Páginas y colores
// ─────────────────────────────────────────────────────────────────────────────

private enum class InsightPage(val label: String) {
    Welcome("Bienvenida"),
    Minutes("Minutos"),
    TopSongsList("Top canciones"),
    SongSpotlight("Canción del año"),
    ArtistSpotlight("Artista del año"),
    TopAlbumsList("Top álbumes"),
    Personality("Personalidad"),
    Summary("Resumen"),
}

/** Fondo y color de contenido de cada página, sacados del esquema de color de Dezzy. */
@Immutable
private data class StoryTone(
    val top: Color,
    val bottom: Color,
    val glow: Color,
    val content: Color,
    val isLight: Boolean = false,
)

@Composable
private fun storyToneFor(page: InsightPage): StoryTone {
    val scheme = MaterialTheme.colorScheme
    fun dark(base: Color) = StoryTone(
        top = lerp(base, Color.Black, 0.36f),
        bottom = lerp(base, Color.Black, 0.68f),
        glow = lerp(base, Color.White, 0.12f).copy(alpha = 0.55f),
        content = Color.White,
    )
    return when (page) {
        InsightPage.Welcome,
        InsightPage.Minutes,
        InsightPage.Personality -> dark(scheme.primary)
        InsightPage.TopSongsList,
        InsightPage.ArtistSpotlight -> dark(scheme.tertiary)
        InsightPage.SongSpotlight,
        InsightPage.TopAlbumsList -> dark(scheme.secondary)
        InsightPage.Summary -> StoryTone(
            top = scheme.tertiaryContainer,
            bottom = scheme.primaryContainer,
            glow = Color.Transparent,
            content = scheme.onSurface,
            isLight = true,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pantalla principal
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun YearInMusicScreen(
    navController: NavController,
    viewModel: YearInMusicViewModel = hiltViewModel(),
) {
    val context           = LocalContext.current
    val menuState         = LocalMenuState.current
    val haptic            = LocalHapticFeedback.current
    LocalPlayerConnection.current ?: return
    val coroutineScope    = rememberCoroutineScope()
    val view              = LocalView.current
    val reduceMotion      = rememberReduceMotion()
    val snackbarHostState = remember { SnackbarHostState() }

    val availableYears      by viewModel.availableYears.collectAsState()
    val selectedYear        by viewModel.selectedYear.collectAsState()
    val topSongsStats       by viewModel.topSongsStats.collectAsState()
    val topSongs            by viewModel.topSongs.collectAsState()
    val topArtists          by viewModel.topArtists.collectAsState()
    val topAlbums           by viewModel.topAlbums.collectAsState()
    val totalListeningTime  by viewModel.totalListeningTime.collectAsState()
    val totalSongsPlayed    by viewModel.totalSongsPlayed.collectAsState()
    val monthlyListening    by viewModel.monthlyListening.collectAsState()
    val yearListeningTime   by viewModel.yearListeningTime.collectAsState()

    // El total del año sale de todos los eventos; si aún no hay, el del top 5.
    val listeningTime = if (yearListeningTime > 0) yearListeningTime else totalListeningTime

    var isGeneratingImage   by remember { mutableStateOf(false) }
    var isShareCaptureMode  by remember { mutableStateOf(false) }
    var shareBounds         by remember { mutableStateOf<Rect?>(null) }
    var isYearPickerOpen    by remember { mutableStateOf(false) }
    var isPaused            by rememberSaveable { mutableStateOf(false) }

    // Con «reducir movimiento» no hay avance automático: sólo toques y botones.
    val autoAdvance = !reduceMotion

    val pages = remember(topSongsStats, topArtists, topAlbums, listeningTime, totalSongsPlayed) {
        buildList {
            add(InsightPage.Welcome)
            if (listeningTime > 0 || totalSongsPlayed > 0) add(InsightPage.Minutes)
            if (topSongsStats.isNotEmpty()) {
                add(InsightPage.TopSongsList)
                add(InsightPage.SongSpotlight)
            }
            if (topArtists.isNotEmpty()) add(InsightPage.ArtistSpotlight)
            if (topAlbums.isNotEmpty()) add(InsightPage.TopAlbumsList)
            if (topSongsStats.isNotEmpty()) add(InsightPage.Personality)
            add(InsightPage.Summary)
        }
    }
    val personality = remember(topSongsStats, totalSongsPlayed, listeningTime) {
        computePersonality(topSongsStats, totalSongsPlayed, listeningTime)
    }

    val pagerState  = rememberPagerState { pages.size }
    val currentPage = pagerState.currentPage
    val isLastPage  = currentPage == pages.lastIndex
    val page        = pages.getOrNull(currentPage) ?: InsightPage.Summary
    val tone        = storyToneFor(page)

    fun goTo(index: Int) {
        if (index in pages.indices) coroutineScope.launch { pagerState.animateScrollToPage(index) }
    }

    // Avance automático por segmentos; se detiene en pausa, al compartir y en el resumen.
    var segmentProgress by remember { mutableFloatStateOf(0f) }
    var progressPage    by remember { mutableIntStateOf(-1) }
    LaunchedEffect(currentPage, isPaused, autoAdvance, isShareCaptureMode, pages.size) {
        if (progressPage != currentPage) {
            progressPage = currentPage
            segmentProgress = 0f
        }
        if (!autoAdvance || isPaused || isShareCaptureMode || currentPage >= pages.lastIndex) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (segmentProgress < 1f) {
            val now = withFrameMillis { it }
            segmentProgress = (segmentProgress + (now - last) / StoryDurationMs.toFloat()).coerceAtMost(1f)
            last = now
        }
        pagerState.animateScrollToPage(currentPage + 1)
    }

    /** Captura la página visible sin controles y la comparte o la guarda en Imágenes. */
    fun exportCurrentPage(share: Boolean) {
        if (isGeneratingImage) return
        isGeneratingImage = true
        val bgArgb = tone.bottom.toArgb()
        coroutineScope.launch {
            try {
                isShareCaptureMode = true
                awaitNextPreDraw(view)
                awaitNextPreDraw(view)
                val raw = ComposeToImage.captureViewBitmap(view = view, backgroundColor = bgArgb)
                val bounds = shareBounds
                val cropped = if (bounds != null) {
                    ComposeToImage.cropBitmap(
                        raw,
                        bounds.left.toInt(), bounds.top.toInt(),
                        bounds.width.toInt(), bounds.height.toInt()
                    )
                } else raw
                val fitted = ComposeToImage.fitBitmap(cropped, 1080, 1920, bgArgb)
                val uri = ComposeToImage.saveBitmapAsFile(
                    context, fitted, "Dezzy_TuAno_${selectedYear}_${page.name}"
                )
                isShareCaptureMode = false
                if (share) {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                            context.getString(R.string.share_summary)
                        )
                    )
                } else {
                    snackbarHostState.showSnackbar(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Guardada en Imágenes/Dezzy"
                        else "Imagen lista para compartir"
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                isShareCaptureMode = false
                snackbarHostState.showSnackbar("No se pudo crear la imagen")
            } finally {
                isShareCaptureMode = false
                isGeneratingImage  = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tone.bottom)
            .onGloballyPositioned { shareBounds = it.boundsInRoot() }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Anterior") { goTo(currentPage - 1); currentPage > 0 },
                    CustomAccessibilityAction("Siguiente") { goTo(currentPage + 1); currentPage < pages.lastIndex },
                )
            }
    ) {
        // ── Páginas ─────────────────────────────────────────────────────────
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !isShareCaptureMode,
            beyondViewportPageCount = 1,
        ) { pageIndex ->
            val pageItem = pages.getOrNull(pageIndex) ?: InsightPage.Summary
            val pageTone = storyToneFor(pageItem)
            StoryBackground(
                tone = pageTone,
                modifier = Modifier
                    .fillMaxSize()
                    // Toque a la izquierda vuelve, a la derecha avanza (los botones lo consumen antes).
                    .pointerInput(pageIndex, pages.size) {
                        detectTapGestures { offset ->
                            if (offset.x < size.width * 0.3f) goTo(pageIndex - 1) else goTo(pageIndex + 1)
                        }
                    },
            ) {
                InsightPageContent(
                    page            = pageItem,
                    tone            = pageTone,
                    year            = selectedYear,
                    listeningTime   = listeningTime,
                    monthly         = monthlyListening,
                    totalSongsPlayed = totalSongsPlayed,
                    topSongsStats   = topSongsStats,
                    topSongs        = topSongs,
                    topArtists      = topArtists,
                    topAlbums       = topAlbums,
                    personality     = personality,
                    animateNumbers  = !reduceMotion,
                    onSongOptions   = {
                        topSongs.firstOrNull()?.let { entity ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuState.show {
                                SongMenu(
                                    originalSong = entity,
                                    navController = navController,
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        }
                    },
                    onArtistOptions = {
                        topArtists.firstOrNull()?.let { artist ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuState.show {
                                ArtistMenu(
                                    originalArtist = artist,
                                    coroutineScope = coroutineScope,
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        }
                    },
                )
            }
        }

        // ── Barra de segmentos, año, pausa y cerrar ─────────────────────────
        if (!isShareCaptureMode) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(3f)
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp)
            ) {
                StorySegments(
                    total    = pages.size,
                    current  = currentPage,
                    progress = if (autoAdvance && !isLastPage) segmentProgress else 1f,
                    tone     = tone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp)
                        .semantics { contentDescription = "Página ${currentPage + 1} de ${pages.size}: ${page.label}" },
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    YearChip(
                        year    = selectedYear,
                        tone    = tone,
                        enabled = availableYears.size > 1,
                        onClick = { isYearPickerOpen = true },
                    )
                    Spacer(Modifier.weight(1f))
                    if (isLastPage) {
                        M3IconButton(onClick = { goTo(0) }) {
                            Icon(painterResource(R.drawable.replay), contentDescription = "Ver desde el principio", tint = tone.content)
                        }
                    } else if (autoAdvance) {
                        M3IconButton(onClick = { isPaused = !isPaused }) {
                            Icon(
                                painterResource(if (isPaused) R.drawable.play else R.drawable.pause),
                                contentDescription = if (isPaused) "Reanudar" else "Pausar",
                                tint = tone.content,
                            )
                        }
                    }
                    IconButton(
                        onClick     = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(painterResource(R.drawable.close), contentDescription = "Cerrar", tint = tone.content)
                    }
                }
            }
        }

        // ── Acciones de la página ───────────────────────────────────────────
        if (!isShareCaptureMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (tone.isLight) {
                    Button(
                        onClick = { exportCurrentPage(share = true) },
                        enabled = !isGeneratingImage,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        ShareLabel(isGeneratingImage, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    OutlinedButton(
                        onClick = { exportCurrentPage(share = false) },
                        enabled = !isGeneratingImage,
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Icon(painterResource(R.drawable.download), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Guardar")
                    }
                } else {
                    Button(
                        onClick = { exportCurrentPage(share = true) },
                        enabled = !isGeneratingImage,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = tone.top,
                        ),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        ShareLabel(isGeneratingImage, tint = tone.top)
                    }
                    Button(
                        onClick = { goTo(currentPage + 1) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.18f),
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text("Siguiente")
                        Spacer(Modifier.width(8.dp))
                        Icon(painterResource(R.drawable.arrow_forward), contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom))
                .padding(bottom = 80.dp),
        )

        // ── Selector de año ─────────────────────────────────────────────────
        if (!isShareCaptureMode && isYearPickerOpen) {
            InsightYearPickerDialog(
                availableYears = availableYears,
                selectedYear   = selectedYear,
                onSelectYear   = { y ->
                    viewModel.selectedYear.value = y
                    isYearPickerOpen = false
                    goTo(0)
                },
                onDismiss      = { isYearPickerOpen = false },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// awaitNextPreDraw
// ─────────────────────────────────────────────────────────────────────────────

private suspend fun awaitNextPreDraw(view: View) = suspendCancellableCoroutine<Unit> { cont ->
    val vto = view.viewTreeObserver
    val listener = object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (vto.isAlive) vto.removeOnPreDrawListener(this)
            cont.resume(Unit)
            return true
        }
    }
    vto.addOnPreDrawListener(listener)
    cont.invokeOnCancellation { if (vto.isAlive) vto.removeOnPreDrawListener(listener) }
    view.invalidate()
}

// ─────────────────────────────────────────────────────────────────────────────
// Router de páginas
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InsightPageContent(
    page: InsightPage,
    tone: StoryTone,
    year: Int,
    listeningTime: Long,
    monthly: List<Long>,
    totalSongsPlayed: Long,
    topSongsStats: List<SongWithStats>,
    topSongs: List<Song>,
    topArtists: List<Artist>,
    topAlbums: List<Album>,
    personality: InsightPersonality,
    animateNumbers: Boolean,
    onSongOptions: () -> Unit,
    onArtistOptions: () -> Unit,
) {
    when (page) {
        InsightPage.Welcome -> WelcomePage(year)
        InsightPage.Minutes -> MinutesPage(listeningTime, totalSongsPlayed, monthly, animateNumbers)
        InsightPage.TopSongsList -> RankingPage(
            overline = "Tus canciones más escuchadas",
            title = "Top canciones",
            rows = topSongsStats.take(5).map { RankRow(it.thumbnailUrl, it.title, timesLabel(it.songCountListened)) },
            tone = tone,
        )
        InsightPage.SongSpotlight -> SongSpotlightPage(
            song = topSongsStats.firstOrNull(),
            artistNames = topSongs.firstOrNull()?.artists?.joinToString { it.name }.orEmpty(),
            onOptions = onSongOptions,
        )
        InsightPage.ArtistSpotlight -> ArtistSpotlightPage(topArtists.firstOrNull(), onArtistOptions)
        InsightPage.TopAlbumsList -> RankingPage(
            overline = "Los discos que no soltaste",
            title = "Top álbumes",
            rows = topAlbums.take(5).map { album ->
                RankRow(album.thumbnailUrl, album.album.title, album.artists.take(2).joinToString { it.name })
            },
            tone = tone,
        )
        InsightPage.Personality -> PersonalityPage(personality, topSongsStats.firstOrNull())
        InsightPage.Summary -> SummaryPage(
            year             = year,
            listeningTime    = listeningTime,
            topSongsStats    = topSongsStats,
            topSongArtists   = topSongs.firstOrNull()?.artists?.joinToString { it.name }.orEmpty(),
            topArtists       = topArtists,
            personality      = personality.takeIf { topSongsStats.isNotEmpty() },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Estructura común de una historia
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StoryBackground(
    tone: StoryTone,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .background(Brush.verticalGradient(listOf(tone.top, tone.bottom)))
            .background(
                Brush.radialGradient(
                    colors = listOf(tone.glow, Color.Transparent),
                    center = Offset(Float.POSITIVE_INFINITY, 0f),
                    radius = 900f,
                )
            ),
        content = content,
    )
}

/** Columna con el hueco de la barra superior y de los botones inferiores. */
@Composable
private fun StoryColumn(
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom))
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(start = 20.dp, end = 20.dp, top = 84.dp, bottom = 88.dp),
        content = content,
    )
}

@Composable
private fun StoryOverline(text: String, color: Color = Color.White) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp,
        letterSpacing = 0.4.sp,
        color = color.copy(alpha = 0.9f),
    )
}

@Composable
private fun StoryHero(text: String, color: Color = Color.White) {
    Text(
        text = text,
        style = MaterialTheme.typography.displayLarge,
        fontSize = 76.sp,
        lineHeight = 80.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-2).sp,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .padding(top = 6.dp)
            .semantics { heading() },
    )
}

@Composable
private fun StoryBody(text: String, color: Color = Color.White) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        lineHeight = 23.sp,
        color = color.copy(alpha = 0.92f),
        modifier = Modifier.padding(top = 14.dp),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Bienvenida
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun WelcomePage(year: Int) {
    StoryColumn {
        StoryOverline("Dezzy Insight")
        StoryHero(year.toString())
        Text(
            text = "Tu año en música",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(top = 4.dp),
        )
        StoryBody("Todo lo que escuchaste este año, en un solo lugar.")
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.dezzymusic_monochrome),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Toca a la derecha para avanzar",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. Minutos con barras por mes
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MinutesPage(
    listeningTimeMs: Long,
    totalSongsPlayed: Long,
    monthly: List<Long>,
    animate: Boolean,
) {
    val totalMinutes = listeningTimeMs / 60_000L
    val shown = if (animate) rememberAnimatedLong(totalMinutes, durationMs = 1600) else totalMinutes
    val peakIndex = monthly.indices.maxByOrNull { monthly[it] }?.takeIf { monthly[it] > 0 }
    val peakMax = monthly.maxOrNull()?.coerceAtLeast(1L) ?: 1L

    val body = buildString {
        val days = totalMinutes / 1440L
        val hours = totalMinutes / 60L
        when {
            days >= 1 -> append("Son ${days.formatEs()} ${if (days == 1L) "día" else "días"} seguidos sin parar.")
            hours >= 1 -> append("Son ${hours.formatEs()} ${if (hours == 1L) "hora" else "horas"} de música.")
            else -> append("Y esto acaba de empezar.")
        }
        if (peakIndex != null) {
            val month = Month.of(peakIndex + 1).getDisplayName(TextStyle.FULL, EsLocale)
            append(" Tu mes más intenso fue $month, con ${(monthly[peakIndex] / 60_000L).formatEs()} minutos.")
        } else if (totalSongsPlayed > 0) {
            append(" En ${totalSongsPlayed.formatEs()} reproducciones de tus favoritas.")
        }
    }

    StoryColumn {
        StoryOverline("Este año escuchaste")
        StoryHero(shown.formatEs())
        Text(
            text = "minutos de música",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(top = 4.dp),
        )
        StoryBody(body)
        Spacer(Modifier.weight(1f))
        if (monthly.any { it > 0 }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clearAndSetSemantics {
                        contentDescription = "Minutos por mes" + (peakIndex?.let {
                            ", el más alto es " + Month.of(it + 1).getDisplayName(TextStyle.FULL, EsLocale)
                        } ?: "")
                    },
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                monthly.forEachIndexed { index, value ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        val fraction = (value.toFloat() / peakMax).coerceIn(0.04f, 1f)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(fraction)
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                                    .background(if (index == peakIndex) Color.White else Color.White.copy(alpha = 0.32f)),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = Month.of(index + 1).getDisplayName(TextStyle.NARROW, EsLocale).uppercase(EsLocale),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.9f),
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3 y 6. Listas de canciones y álbumes
// ─────────────────────────────────────────────────────────────────────────────

private data class RankRow(val image: String?, val title: String, val subtitle: String)

@Composable
private fun RankingPage(overline: String, title: String, rows: List<RankRow>, tone: StoryTone) {
    StoryColumn(scrollable = true) {
        StoryOverline(overline)
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            modifier = Modifier
                .padding(top = 6.dp, bottom = 16.dp)
                .semantics { heading() },
        )
        rows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .semantics(mergeDescendants = true) { },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (index == 0) Color.White else Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(36.dp),
                )
                Spacer(Modifier.width(10.dp))
                AsyncImage(
                    model = rememberSafeImageRequest(row.image),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(if (index == 0) 64.dp else 52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tone.glow),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Medium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.subtitle.isNotBlank()) {
                        Text(
                            text = row.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Canción del año
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SongSpotlightPage(song: SongWithStats?, artistNames: String, onOptions: () -> Unit) {
    if (song == null) return
    StoryColumn(scrollable = true) {
        StoryOverline("Tu canción del año")
        Spacer(Modifier.height(20.dp))
        AsyncImage(
            model = rememberSafeImageRequest(song.thumbnailUrl),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(240.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.12f)),
        )
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                if (artistNames.isNotBlank()) {
                    Text(
                        text = artistNames,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            M3IconButton(onClick = onOptions) {
                Icon(painterResource(R.drawable.more_vert), contentDescription = "Opciones de la canción", tint = Color.White)
            }
        }
        StoryBody(
            buildString {
                append("La pusiste ${timesLabel(song.songCountListened)}")
                makeTimeString(song.timeListened).takeIf { it.isNotBlank() }?.let { append(", $it en total") }
                append(".")
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. Artista del año
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ArtistSpotlightPage(artist: Artist?, onOptions: () -> Unit) {
    if (artist == null) return
    StoryColumn(scrollable = true) {
        StoryOverline("Tu artista del año")
        Spacer(Modifier.height(20.dp))
        AsyncImage(
            model = rememberSafeImageRequest(artist.artist.thumbnailUrl),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(220.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f)),
        )
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = artist.artist.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            M3IconButton(onClick = onOptions) {
                Icon(painterResource(R.drawable.more_vert), contentDescription = "Opciones del artista", tint = Color.White)
            }
        }
        StoryBody(
            buildString {
                append("Sonó ${timesLabel(artist.songCount)}")
                artist.timeListened?.toLong()?.let(::makeTimeString)?.takeIf { it.isNotBlank() }?.let { append(", $it en total") }
                append(". Nadie te acompañó tanto este año.")
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 7. Perfil de oyente
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PersonalityPage(personality: InsightPersonality, topSong: SongWithStats?) {
    StoryColumn(scrollable = true) {
        StoryOverline("Tu perfil de oyente")
        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(personality.icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
        }
        Text(
            text = personality.title,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            modifier = Modifier
                .padding(top = 20.dp)
                .semantics { heading() },
        )
        StoryBody(personality.description)
        topSong?.let {
            Spacer(Modifier.height(20.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White.copy(alpha = 0.14f),
                contentColor = Color.White,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.music_note), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Tu himno del año: ${it.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 8. Resumen en tarjeta clara
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SummaryPage(
    year: Int,
    listeningTime: Long,
    topSongsStats: List<SongWithStats>,
    topSongArtists: String,
    topArtists: List<Artist>,
    personality: InsightPersonality?,
) {
    val scheme = MaterialTheme.colorScheme
    val topSong = topSongsStats.firstOrNull()
    StoryColumn(scrollable = true) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = scheme.surfaceContainerLowest,
            contentColor = scheme.onSurface,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp)) {
                if (topSong == null && topArtists.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_listening_data),
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant,
                    )
                } else {
                    topSong?.let { song ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = rememberSafeImageRequest(song.thumbnailUrl),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(84.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(scheme.surfaceContainerHigh),
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "TU CANCIÓN DEL AÑO",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontSize = 13.sp,
                                    letterSpacing = 0.4.sp,
                                    color = scheme.primary,
                                )
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = listOf(topSongArtists, timesLabel(song.songCountListened))
                                        .filter { it.isNotBlank() }
                                        .joinToString(", "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SummaryList("Canciones", topSongsStats.take(5).map { it.title }, Modifier.weight(1f))
                        SummaryList("Artistas", topArtists.take(5).map { it.artist.name }, Modifier.weight(1f))
                    }
                }
                HorizontalDivider(Modifier.padding(top = 14.dp), color = scheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SummaryStat((listeningTime / 60_000L).formatEs(), "minutos", Modifier.weight(1f))
                    SummaryStat(personality?.title ?: "—", "tu perfil de oyente", Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(scheme.primary),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Dezzy, Tu año $year",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryList(title: String, items: List<String>, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = title.uppercase(EsLocale),
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 0.4.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(bottom = 4.dp)
                .semantics { heading() },
        )
        items.forEachIndexed { index, item ->
            Row {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SummaryStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) { }) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Controles
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StorySegments(
    total: Int,
    current: Int,
    progress: Float,
    tone: StoryTone,
    modifier: Modifier = Modifier,
) {
    val on = tone.content
    val off = if (tone.isLight) on.copy(alpha = 0.18f) else on.copy(alpha = 0.35f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(total) { index ->
            val fill = when {
                index < current -> 1f
                index == current -> progress.coerceIn(0f, 1f)
                else -> 0f
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(off)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fill)
                        .fillMaxHeight()
                        .background(on)
                )
            }
        }
    }
}

@Composable
private fun YearChip(year: Int, tone: StoryTone, enabled: Boolean, onClick: () -> Unit) {
    val container = if (tone.isLight) tone.content.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.18f)
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Cambiar año", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(container)
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = year.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontSize = 14.sp,
                color = tone.content,
            )
            if (enabled) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    painterResource(R.drawable.expand_more),
                    contentDescription = null,
                    tint = tone.content,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ShareLabel(isGenerating: Boolean, tint: Color) {
    if (isGenerating) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = tint,
        )
    } else {
        Icon(painterResource(R.drawable.share), contentDescription = null, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.width(8.dp))
    Text("Compartir")
}

@Composable
private fun InsightYearPickerDialog(
    availableYears: List<Int>,
    selectedYear: Int,
    onSelectYear: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Elige un año") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                availableYears.forEach { year ->
                    val isSelected = year == selectedYear
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(selected = isSelected, role = Role.RadioButton) { onSelectYear(year) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(year.toString(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Contador animado
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun rememberAnimatedLong(target: Long, durationMs: Int = 1400): Long {
    var current by remember { mutableLongStateOf(0L) }
    LaunchedEffect(target) {
        val startMs = System.currentTimeMillis()
        while (true) {
            val elapsed  = (System.currentTimeMillis() - startMs).coerceAtMost(durationMs.toLong())
            val progress = elapsed.toFloat() / durationMs
            val eased    = FastOutSlowInEasing.transform(progress)
            current      = (target * eased).toLong()
            if (elapsed >= durationMs) break
            delay(16L)
        }
    }
    return current
}

// ─────────────────────────────────────────────────────────────────────────────
// Imagen sin bitmap de hardware (necesario para capturar al compartir)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun rememberSafeImageRequest(data: Any?): Any? {
    val context = LocalContext.current
    return remember(data) {
        data?.let {
            ImageRequest.Builder(context)
                .data(it)
                .allowHardware(false)
                .build()
        }
    }
}
