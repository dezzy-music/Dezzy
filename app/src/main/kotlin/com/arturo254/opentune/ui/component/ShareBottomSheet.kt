package com.arturo254.opentune.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.arturo254.opentune.R
import com.arturo254.opentune.constants.FontFamilyKey
import com.arturo254.opentune.constants.UseSystemFontKey
import com.arturo254.opentune.ui.player.CanvasArtworkPlayer
import com.arturo254.opentune.ui.player.rememberPlaybackCanvas
import com.arturo254.opentune.utils.CanvasCardVideoComposer
import com.arturo254.opentune.utils.ComposeToImage
import com.arturo254.opentune.utils.ShareUtils
import com.arturo254.opentune.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Fondo de la tarjeta: color sólido o degradado vertical color → negro. */
private data class BgChoice(val color: Color, val gradient: Boolean)

private const val TAB_SONG = 0
private const val TAB_LYRICS = 1
private const val TAB_CANVAS = 2

/**
 * Qué se está compartiendo. `Song` es el comportamiento histórico (tabs de letra y
 * canvas); `Artist` y `Playlist` usan la tarjeta de entidad estilo Spotify: imagen
 * dentro de una tarjeta oscura, con el nombre y el descriptor debajo.
 */
enum class ShareEntity(val descriptor: String, val circularImage: Boolean) {
    Song("", false),
    Artist("Artista en Dezzy", true),
    Album("Álbum en Dezzy", false),
    Playlist("Playlist en Dezzy", false),
}

/**
 * Hoja de compartir estilo Spotify, usada por TODOS los botones de compartir:
 *  - Tabs "Canción" / "Letra" / "Canvas" (los dos últimos solo si existen),
 *    cada uno con su preview en vivo — el canvas se reproduce dentro de la
 *    tarjeta igual que en Spotify.
 *  - Paleta dinámica de la carátula con variantes sólidas Y en degradado
 *    (color arriba → negro abajo), aplicadas también a lo exportado.
 *  - El tab Canvas comparte un VIDEO de la tarjeta con el clip adentro.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareBottomSheet(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    shareLink: String,
    lyrics: String? = null,
    hasVideo: Boolean = false,
    mediaId: String? = null,
    entity: ShareEntity = ShareEntity.Song,
    /** Primeras pistas de la playlist; habilita el tab "Playlist" con listado. */
    tracks: List<ComposeToImage.ShareTrack> = emptyList(),
    onDismiss: () -> Unit
) {
    val isEntityCard = entity != ShareEntity.Song
    // Tab "Playlist" (listado) vs "Portada de la playlist" (tarjeta con la portada).
    val hasTracklist = entity == ShareEntity.Playlist && tracks.isNotEmpty()
    var showTracklist by remember(hasTracklist) { mutableStateOf(hasTracklist) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }
    // Match the app's selected font.
    val appFontFamily = MaterialTheme.typography.bodyLarge.fontFamily
    // Fuente elegida en Ajustes → se aplica también a lo EXPORTADO (imagen y video).
    val (fontName) = rememberPreference(FontFamilyKey, defaultValue = "POPPINS")
    val (useSystemFont) = rememberPreference(UseSystemFontKey, defaultValue = false)
    val exportFontName = if (useSystemFont) "SYSTEM" else fontName

    val darkBg = Color(0xFF1E1E1E)

    // ── Letra principal: sin timestamps LRC, máximo 5 líneas ──
    val lyricLines = remember(lyrics) {
        lyrics?.lines()
            ?.map { it.replace(Regex("\\[[^\\]]*\\]"), "").trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.take(5)
            .orEmpty()
    }
    val hasLyrics = lyricLines.isNotEmpty() && !isEntityCard

    // ── Canvas de la canción (si existe) ──
    val canvasArtwork = if (mediaId != null && !isEntityCard) {
        rememberPlaybackCanvas(
            mediaId = mediaId,
            title = title,
            artist = subtitle,
            albumTitle = null,
            durationMs = 0L,
        )
    } else {
        null
    }
    val canvasVideoUrl = canvasArtwork?.videoUrlVertical
        ?: canvasArtwork?.videoUrl
        ?: canvasArtwork?.preferredVerticalAnimationUrl
        ?: canvasArtwork?.preferredAnimationUrl
    val hasCanvas = canvasVideoUrl != null

    var shareTab by remember { mutableStateOf(TAB_SONG) }
    var canvasDurationSec by remember { mutableIntStateOf(30) }
    var audioStartSec by remember { mutableFloatStateOf(0f) }
    var canvasExportMode by remember { mutableStateOf(com.arturo254.opentune.utils.CanvasExportMode.FULL_BRANDING) }

    // Si el contenido del tab activo deja de existir, volver a Canción.
    LaunchedEffect(hasLyrics, hasCanvas) {
        if ((shareTab == TAB_LYRICS && !hasLyrics) || (shareTab == TAB_CANVAS && !hasCanvas)) {
            shareTab = TAB_SONG
        }
    }

    // Candidate card colors extracted from the cover palette (+ neutral fallbacks).
    // Cada color ofrece su variante sólida y su degradado color→negro.
    var swatches by remember(thumbnailUrl) { mutableStateOf(listOf<Color>()) }
    var selectedChoice by remember(thumbnailUrl) { mutableStateOf<BgChoice?>(null) }
    // Estilo Polaroid (foto sobre papel): cuando está activo se ignora el color y
    // se exporta con el layout PaperPolaroid.
    var polaroid by remember(thumbnailUrl) { mutableStateOf(false) }
    val bgChoices = remember(swatches) {
        swatches.flatMap { listOf(BgChoice(it, false), BgChoice(it, true)) }
    }
    val choice = selectedChoice ?: bgChoices.firstOrNull() ?: BgChoice(Color(0xFF2B2B2B), false)
    val textColor = if (choice.gradient || choice.color.luminance() <= 0.5f) Color.White else Color.Black
    val secondaryColor = textColor.copy(alpha = 0.7f)
    LaunchedEffect(thumbnailUrl) {
        val url = thumbnailUrl ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(120, 120)
                    .allowHardware(false)
                    .build()
                context.imageLoader.execute(request).image?.toBitmap()
            }.getOrNull()
        } ?: return@LaunchedEffect
        val palette = withContext(Dispatchers.Default) {
            Palette.from(bitmap).maximumColorCount(12).generate()
        }
        swatches = buildList {
            palette.darkVibrantSwatch?.let { add(Color(it.rgb)) }
            palette.dominantSwatch?.let { add(Color(it.rgb)) }
            palette.vibrantSwatch?.let { add(Color(it.rgb)) }
            palette.darkMutedSwatch?.let { add(Color(it.rgb)) }
            palette.mutedSwatch?.let { add(Color(it.rgb)) }
            palette.lightVibrantSwatch?.let { add(Color(it.rgb)) }
            // Neutral fallbacks so there's always a choice.
            add(Color(0xFF2B2B2B)); add(Color(0xFF121212)); add(Color(0xFFF2F2F2))
        }.distinct()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = darkBg,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = appFontFamily)
        ) {
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val isPureCanvas = shareTab == TAB_CANVAS && canvasExportMode == com.arturo254.opentune.utils.CanvasExportMode.PURE_CANVAS
            val cardBgModifier = when {
                isPureCanvas -> Modifier.background(Color.Black)
                polaroid -> Modifier.background(Color(0xFFF4F1E8))
                choice.gradient -> Modifier.background(Brush.verticalGradient(listOf(choice.color, Color.Black)))
                else -> Modifier.background(choice.color)
            }
            val cardPadding = when {
                isLandscape && shareTab == TAB_CANVAS -> PaddingValues(horizontal = 140.dp, vertical = 8.dp)
                isLandscape -> PaddingValues(horizontal = 120.dp, vertical = 12.dp)
                shareTab == TAB_CANVAS -> PaddingValues(horizontal = 64.dp, vertical = 10.dp)
                else -> PaddingValues(horizontal = 48.dp, vertical = 20.dp)
            }
            val cardModifier = if (isLandscape) {
                Modifier
                    .fillMaxWidth(0.65f)
                    .padding(cardPadding)
                    .aspectRatio(if (isPureCanvas) 9f / 16f else 3f / 4f)
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(cardPadding)
                    .aspectRatio(if (isPureCanvas) 9f / 16f else 3f / 4f)
            }
            Box(
                modifier = cardModifier
                    .clip(RoundedCornerShape(16.dp))
                    .then(cardBgModifier),
                contentAlignment = Alignment.Center
            ) {
                when {
                    hasTracklist && showTracklist -> {
                        // ── Tarjeta de PLAYLIST con listado ──
                        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(thumbnailUrl).crossfade(true).build(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(74.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                                Spacer(modifier = Modifier.width(11.dp))
                                Column {
                                    Text(
                                        text = title,
                                        color = textColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = entity.descriptor,
                                        color = secondaryColor,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(18.dp))
                            tracks.take(4).forEach { track ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 9.dp),
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(track.thumbnailUrl).crossfade(true).build(),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Spacer(modifier = Modifier.width(9.dp))
                                    Column {
                                        Text(
                                            text = track.title,
                                            color = textColor,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = track.artist,
                                            color = secondaryColor,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Box(modifier = Modifier.align(Alignment.End)) {
                                BrandingRow(textColor)
                            }
                        }
                    }

                    isEntityCard -> {
                        // ── Tarjeta de ARTISTA / PLAYLIST (estilo Spotify) ──
                        // Tarjeta oscura anclada arriba, con la imagen dentro y el
                        // nombre + descriptor alineados a la izquierda debajo.
                        Column(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 20.dp),
                            verticalArrangement = Arrangement.Top,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF0B0B0B))
                                    .padding(13.dp),
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(thumbnailUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(
                                            if (entity.circularImage) CircleShape
                                            else RoundedCornerShape(3.dp)
                                        ),
                                    contentScale = ContentScale.Crop,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = title,
                                    color = textColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = subtitle.ifBlank { entity.descriptor },
                                    color = secondaryColor,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                BrandingRow(textColor)
                            }
                        }
                    }

                    polaroid -> {
                        // Vista previa Polaroid: papel de cuaderno + foto blanca rotada.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .drawBehind {
                                    var y = size.height * 0.14f
                                    val gap = size.height * 0.06f
                                    while (y < size.height) {
                                        drawLine(
                                            Color(0x22597EA8),
                                            androidx.compose.ui.geometry.Offset(0f, y),
                                            androidx.compose.ui.geometry.Offset(size.width, y),
                                            strokeWidth = 1.6f,
                                        )
                                        y += gap
                                    }
                                    drawLine(
                                        Color(0x33D06A63),
                                        androidx.compose.ui.geometry.Offset(size.width * 0.1f, 0f),
                                        androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height),
                                        strokeWidth = 2.2f,
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.62f)
                                .graphicsLayer { rotationZ = -4.5f }
                                .shadow(10.dp)
                                .background(Color.White)
                                .padding(top = 8.dp, start = 8.dp, end = 8.dp, bottom = 16.dp),
                        ) {
                            AsyncImage(
                                model = thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = title,
                                color = Color(0xFF1A1A1A),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = subtitle,
                                color = Color(0xFF777777),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        }
                    }
                    shareTab == TAB_CANVAS && hasCanvas -> {
                        if (canvasExportMode == com.arturo254.opentune.utils.CanvasExportMode.PURE_CANVAS) {
                            // ── Preview de PURO CANVAS (sin marco, sin textos, sin marca) ──
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center,
                            ) {
                                CanvasArtworkPlayer(
                                    primaryUrl = canvasVideoUrl,
                                    fallbackUrl = canvasArtwork?.videoUrl,
                                    isPlaying = true,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        } else {
                            // ── Tarjeta de CANVAS con marca completa ──
                            Column(
                                modifier = Modifier.fillMaxSize().padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.Black),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CanvasArtworkPlayer(
                                        primaryUrl = canvasVideoUrl,
                                        fallbackUrl = canvasArtwork?.videoUrl,
                                        isPlaying = true,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = title,
                                    color = textColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = subtitle,
                                    color = secondaryColor,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                BrandingRow(textColor)
                            }
                        }
                    }

                    shareTab == TAB_LYRICS && hasLyrics -> {
                        // ── Tarjeta de LETRA: carátula pequeña + letra principal ──
                        Column(
                            modifier = Modifier.fillMaxSize().padding(20.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(thumbnailUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = title,
                                        color = textColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = subtitle,
                                        color = secondaryColor,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(18.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                lyricLines.forEach { line ->
                                    Text(
                                        text = line,
                                        color = textColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 20.sp,
                                        lineHeight = 27.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                            Box(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                BrandingRow(textColor)
                            }
                        }
                    }

                    else -> {
                        // ── Tarjeta de CANCIÓN: carátula grande + título/artista ──
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(thumbnailUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = title,
                                color = textColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = subtitle,
                                color = secondaryColor,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            BrandingRow(textColor)
                        }
                    }
                }
            }

            // ── Selector Playlist / Portada de la playlist (como Spotify) ──
            if (hasTracklist) {
                Surface(shape = CircleShape, color = Color(0xFF2E2E2E)) {
                    Row(modifier = Modifier.padding(4.dp)) {
                        SharePillTab("Playlist", showTracklist) { showTracklist = true }
                        SharePillTab("Portada de la playlist", !showTracklist) { showTracklist = false }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── Selector Canción / Letra / Canvas (como Spotify) ──
            if (hasLyrics || hasCanvas) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF2E2E2E),
                ) {
                    Row(modifier = Modifier.padding(4.dp)) {
                        SharePillTab("Canción", shareTab == TAB_SONG) { shareTab = TAB_SONG }
                        if (hasLyrics) {
                            SharePillTab("Letra", shareTab == TAB_LYRICS) { shareTab = TAB_LYRICS }
                        }
                        if (hasCanvas) {
                            SharePillTab("Canvas", shareTab == TAB_CANVAS) { shareTab = TAB_CANVAS }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Controles de exportación avanzada de Canvas (duración + corte de audio estilo Instagram)
            if (shareTab == TAB_CANVAS && hasCanvas) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Duración:",
                            fontSize = 12.sp,
                            color = Color.LightGray,
                            fontWeight = FontWeight.Medium,
                        )
                        listOf(15, 30, 60).forEach { dur ->
                            Surface(
                                onClick = { canvasDurationSec = dur },
                                shape = CircleShape,
                                color = if (canvasDurationSec == dur) Color.White else Color(0xFF333333),
                            ) {
                                Text(
                                    text = if (dur == 30) "30s (Stories)" else "${dur}s",
                                    color = if (canvasDurationSec == dur) Color.Black else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val startMin = (audioStartSec / 60).toInt()
                    val startSec = (audioStartSec % 60).toInt()
                    val endSecTotal = audioStartSec.toInt() + canvasDurationSec
                    val endMin = endSecTotal / 60
                    val endSec = endSecTotal % 60
                    Text(
                        text = "Segmento de audio: ${startMin}:${String.format(java.util.Locale.US, "%02d", startSec)} - ${endMin}:${String.format(java.util.Locale.US, "%02d", endSec)}",
                        fontSize = 12.sp,
                        color = Color.LightGray,
                        fontWeight = FontWeight.Medium,
                    )
                    Slider(
                        value = audioStartSec,
                        onValueChange = { audioStartSec = it },
                        valueRange = 0f..180f,
                        modifier = Modifier.fillMaxWidth(0.9f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color(0xFF444444),
                        ),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Color picker — sólido y degradado color→negro por cada color de la paleta.
            if (bgChoices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Opción especial: estilo Polaroid (foto sobre papel de cuaderno).
                    // No aplica a artistas/playlists, que usan la tarjeta de entidad.
                    if (!isEntityCard) Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF4F1E8))
                            .border(
                                width = if (polaroid) 3.dp else 1.dp,
                                color = if (polaroid) Color.White else Color.White.copy(alpha = 0.3f),
                                shape = CircleShape,
                            )
                            .clickable { polaroid = !polaroid },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.image),
                            contentDescription = "Polaroid",
                            tint = Color(0xFF444444),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    bgChoices.forEach { c ->
                        val isSel = c == choice && !polaroid
                        val swatchBg = if (c.gradient) {
                            Modifier.background(Brush.verticalGradient(listOf(c.color, Color.Black)))
                        } else {
                            Modifier.background(c.color)
                        }
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .then(swatchBg)
                                .border(
                                    width = if (isSel) 3.dp else 1.dp,
                                    color = if (isSel) Color.White else Color.White.copy(alpha = 0.3f),
                                    shape = CircleShape
                                )
                                .clickable { selectedChoice = c; polaroid = false }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Toca un color para personalizar la tarjeta",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
            }

            if (shareTab == TAB_CANVAS) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = canvasExportMode == com.arturo254.opentune.utils.CanvasExportMode.FULL_BRANDING,
                        onClick = { canvasExportMode = com.arturo254.opentune.utils.CanvasExportMode.FULL_BRANDING },
                        label = { Text("Con marca completa", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color.White,
                            selectedLabelColor = Color.Black,
                            containerColor = Color.White.copy(alpha = 0.1f),
                            labelColor = Color.White,
                        )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    FilterChip(
                        selected = canvasExportMode == com.arturo254.opentune.utils.CanvasExportMode.PURE_CANVAS,
                        onClick = { canvasExportMode = com.arturo254.opentune.utils.CanvasExportMode.PURE_CANVAS },
                        label = { Text("Puro Canvas (sin marco)", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color.White,
                            selectedLabelColor = Color.Black,
                            containerColor = Color.White.copy(alpha = 0.1f),
                            labelColor = Color.White,
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Progreso silencioso mientras se genera la imagen/video (sin toasts).
            if (isProcessing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp)
                        .clip(CircleShape),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.15f),
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── Preparar contenido según el tab y entregarlo (uri, mime) ──
            val prepareAndShare = { deliver: (Uri, String) -> Unit ->
                if (!isProcessing) {
                    isProcessing = true
                    coroutineScope.launch {
                        try {
                            if (shareTab == TAB_CANVAS && canvasVideoUrl != null) {
                                val rawCanvas = withContext(Dispatchers.IO) {
                                    val out = File(context.cacheDir, "share_canvas_src.mp4")
                                    val localFile = mediaId
                                        ?.let { File(context.filesDir, "canvas/$it.mp4") }
                                        ?.takeIf { it.exists() && it.length() > 0 }
                                    when {
                                        localFile != null -> localFile.copyTo(out, overwrite = true)
                                        else -> downloadCanvasMediaFile(canvasVideoUrl, out)
                                    }
                                }
                                val audioFile = withContext(Dispatchers.IO) {
                                    findAudioFileForMedia(context, mediaId)
                                }
                                val cardVideo = CanvasCardVideoComposer.compose(
                                    context = context,
                                    canvasFile = rawCanvas,
                                    title = title,
                                    artist = subtitle,
                                    bgColor = choice.color.toArgb(),
                                    bgGradient = choice.gradient,
                                    textColor = if (textColor == Color.White) android.graphics.Color.WHITE else android.graphics.Color.BLACK,
                                    secondaryColor = if (textColor == Color.White) 0xB3FFFFFF.toInt() else 0xB3000000.toInt(),
                                    fontName = exportFontName,
                                    output = File(context.cacheDir, "share_canvas.mp4"),
                                    targetDurationMs = canvasDurationSec * 1000L,
                                    audioFile = audioFile,
                                    audioStartMs = (audioStartSec * 1000L).toLong(),
                                    mode = canvasExportMode,
                                ).getOrNull()

                                val target = cardVideo ?: rawCanvas
                                val uri = ShareUtils.uriForCacheFile(context, target)
                                    ?: error("FileProvider rechazó el archivo")
                                deliver(uri, "video/mp4")
                                onDismiss()
                            } else {
                                val config = LyricsCardConfig(
                                    layoutStyle = when {
                                        isEntityCard -> LyricsLayoutStyle.EntityCard
                                        polaroid -> LyricsLayoutStyle.PaperPolaroid
                                        else -> LyricsLayoutStyle.DezzyShare
                                    },
                                    entityCircularImage = entity.circularImage,
                                    showCoverArt = true,
                                    showTitle = true,
                                    showArtist = true,
                                    showBranding = true,
                                    customBgColor = choice.color,
                                    customTextColor = textColor,
                                    customSecondaryTextColor = secondaryColor,
                                    customBgGradient = choice.gradient,
                                    textAlign = TextAlign.Start,
                                    fontName = exportFontName,
                                )
                                val bitmap = if (hasTracklist && showTracklist) {
                                    ComposeToImage.createPlaylistShareImage(
                                        context = context,
                                        coverArtUrl = thumbnailUrl,
                                        playlistName = title,
                                        descriptor = entity.descriptor,
                                        tracks = tracks,
                                        config = config,
                                        outputSize = 1080,
                                    )
                                } else ComposeToImage.createLyricsImageWithConfig(
                                    context = context,
                                    coverArtUrl = thumbnailUrl,
                                    songTitle = title,
                                    artistName = if (isEntityCard) entity.descriptor else subtitle,
                                    lyrics = if (shareTab == TAB_LYRICS) lyricLines.joinToString("\n") else "",
                                    config = config,
                                    outputSize = 1080
                                )
                                val uri = ShareUtils.saveBitmapToCache(context, bitmap)
                                    ?: error("No se pudo guardar la imagen")
                                deliver(uri, "image/png")
                                onDismiss()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                "Error al preparar: ${e.message ?: e.javaClass.simpleName}",
                                Toast.LENGTH_LONG,
                            ).show()
                        } finally {
                            isProcessing = false
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                ShareIconButton(
                    iconId = R.drawable.download,
                    label = "Guardar",
                    onClick = {
                        if (!isProcessing) {
                            isProcessing = true
                            coroutineScope.launch {
                                try {
                                    if (shareTab == TAB_CANVAS && canvasVideoUrl != null) {
                                        val rawCanvas = withContext(Dispatchers.IO) {
                                            val out = File(context.cacheDir, "share_canvas_src.mp4")
                                            val localFile = mediaId
                                                ?.let { File(context.filesDir, "canvas/$it.mp4") }
                                                ?.takeIf { it.exists() && it.length() > 0 }
                                            when {
                                                localFile != null -> localFile.copyTo(out, overwrite = true)
                                                else -> downloadCanvasMediaFile(canvasVideoUrl, out)
                                            }
                                        }
                                        val audioFile = withContext(Dispatchers.IO) {
                                            findAudioFileForMedia(context, mediaId)
                                        }
                                        val cardVideo = CanvasCardVideoComposer.compose(
                                            context = context,
                                            canvasFile = rawCanvas,
                                            title = title,
                                            artist = subtitle,
                                            bgColor = choice.color.toArgb(),
                                            bgGradient = choice.gradient,
                                            textColor = if (textColor == Color.White) android.graphics.Color.WHITE else android.graphics.Color.BLACK,
                                            secondaryColor = if (textColor == Color.White) 0xB3FFFFFF.toInt() else 0xB3000000.toInt(),
                                            fontName = exportFontName,
                                            output = File(context.cacheDir, "share_canvas.mp4"),
                                            targetDurationMs = canvasDurationSec * 1000L,
                                            audioFile = audioFile,
                                            audioStartMs = (audioStartSec * 1000L).toLong(),
                                            mode = canvasExportMode,
                                        ).getOrNull()

                                        val target = cardVideo ?: rawCanvas
                                        val savedUri = ShareUtils.saveVideoToGallery(context, target, title)
                                        if (savedUri != null) {
                                            Toast.makeText(context, "Video guardado en la galería", Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        } else {
                                            Toast.makeText(context, "Error al guardar el video", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        val config = LyricsCardConfig(
                                            layoutStyle = when {
                                                isEntityCard -> LyricsLayoutStyle.EntityCard
                                                polaroid -> LyricsLayoutStyle.PaperPolaroid
                                                else -> LyricsLayoutStyle.DezzyShare
                                            },
                                            entityCircularImage = entity.circularImage,
                                            showCoverArt = true,
                                            showTitle = true,
                                            showArtist = true,
                                            showBranding = true,
                                            customBgColor = choice.color,
                                            customTextColor = textColor,
                                            customSecondaryTextColor = secondaryColor,
                                            customBgGradient = choice.gradient,
                                            textAlign = TextAlign.Start,
                                            fontName = exportFontName,
                                        )
                                        val bitmap = if (hasTracklist && showTracklist) {
                                            ComposeToImage.createPlaylistShareImage(
                                                context = context,
                                                coverArtUrl = thumbnailUrl,
                                                playlistName = title,
                                                descriptor = entity.descriptor,
                                                tracks = tracks,
                                                config = config,
                                                outputSize = 1080,
                                            )
                                        } else ComposeToImage.createLyricsImageWithConfig(
                                            context = context,
                                            coverArtUrl = thumbnailUrl,
                                            songTitle = title,
                                            artistName = if (isEntityCard) entity.descriptor else subtitle,
                                            lyrics = if (shareTab == TAB_LYRICS) lyricLines.joinToString("\n") else "",
                                            config = config,
                                            outputSize = 1080
                                        )
                                        val savedUri = ShareUtils.saveImageToGallery(context, bitmap, title)
                                        if (savedUri != null) {
                                            Toast.makeText(context, "Imagen guardada en la galería", Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        } else {
                                            Toast.makeText(context, "Error al guardar la imagen", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error al guardar: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                                } finally {
                                    isProcessing = false
                                }
                            }
                        }
                    }
                )
                ShareIconButton(
                    iconId = R.drawable.link,
                    label = "Copiar YT",
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("URL", shareLink))
                        Toast.makeText(context, "Enlace de YouTube copiado", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                )
                val spotifyMatch = remember(mediaId) {
                    mediaId?.let { com.arturo254.opentune.spotify.SpotifyMatchFileCache.getMatchByYouTubeId(it) }
                }
                if (spotifyMatch != null) {
                    ShareIconButton(
                        iconId = R.drawable.link,
                        label = "Spotify",
                        onClick = {
                            val spLink = "https://open.spotify.com/track/${spotifyMatch.spotifyId}"
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("URL", spLink))
                            Toast.makeText(context, "Enlace de Spotify copiado", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    )
                }
                ShareIconButton(
                    iconId = R.drawable.instagram,
                    label = "Historias",
                    onClick = {
                        prepareAndShare { uri, mime ->
                            ShareUtils.shareToInstagramStory(
                                context, uri,
                                if (mime.startsWith("video")) "video/mp4" else "image/*",
                            )
                        }
                    }
                )
                ShareIconButton(
                    iconId = R.drawable.share, // WhatsApp icon
                    label = "WhatsApp",
                    onClick = {
                        prepareAndShare { uri, mime ->
                            ShareUtils.shareToWhatsApp(context, uri, shareLink, mime)
                        }
                    }
                )
                ShareIconButton(
                    iconId = R.drawable.more_horiz,
                    label = "Más",
                    onClick = {
                        prepareAndShare { uri, mime ->
                            ShareUtils.shareGeneric(context, uri, shareLink, mime)
                        }
                    }
                )
            }
        }
        }
    }
}

@Composable
private fun BrandingRow(textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(id = R.drawable.star),
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Dezzy",
            color = textColor,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun SharePillTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) Color.White else Color.Transparent,
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ShareIconButton(
    iconId: Int,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Color(0xFF333333)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = iconId),
                contentDescription = label,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun findAudioFileForMedia(context: Context, mediaId: String?): File? {
    if (mediaId.isNullOrBlank()) return null
    return runCatching {
        // 1. Lossless / descargas locales
        val dirs = listOf(
            File(context.filesDir, "lossless"),
            File(context.filesDir, "downloads"),
        )
        val exts = listOf(".flac", ".mp3", ".m4a", ".opus", ".webm")
        for (dir in dirs) {
            for (ext in exts) {
                val f = File(dir, "$mediaId$ext")
                if (f.exists() && f.length() > 0) return f
            }
        }

        // 2. Caché de ExoPlayer
        val entryPoint = dagger.hilt.EntryPoints.get(
            context.applicationContext,
            com.arturo254.opentune.di.CacheEntryPoint::class.java
        )
        val playerCache = runCatching { entryPoint.playerCache() }.getOrNull()
        val downloadCache = runCatching { entryPoint.downloadCache() }.getOrNull()

        val spans = (playerCache?.getCachedSpans(mediaId).orEmpty() + downloadCache?.getCachedSpans(mediaId).orEmpty())
            .filter { it.file != null && it.file!!.exists() && it.file!!.length() > 0 }

        val bestSpan = spans.maxByOrNull { it.length }
        if (bestSpan?.file != null) return bestSpan.file

        // 3. Búsqueda en directorio de exoplayer por si el span tiene nombre asignado
        val exoDir = File(context.filesDir, "exoplayer")
        if (exoDir.exists()) {
            exoDir.walkTopDown().firstOrNull { it.isFile && it.length() > 200_000L }
        } else null
    }.getOrNull()
}

private suspend fun downloadCanvasMediaFile(url: String, outputFile: File): File = withContext(Dispatchers.IO) {
    if (url.startsWith("file://")) {
        val srcFile = File(Uri.parse(url).path!!)
        srcFile.copyTo(outputFile, overwrite = true)
        return@withContext outputFile
    }
    if (url.startsWith("/")) {
        val srcFile = File(url)
        srcFile.copyTo(outputFile, overwrite = true)
        return@withContext outputFile
    }

    val client = okhttp3.OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    val req = okhttp3.Request.Builder().url(url).build()
    client.newCall(req).execute().use { response ->
        if (!response.isSuccessful) error("HTTP ${response.code} descargando canvas")
        val bodyBytes = response.body?.bytes() ?: error("Cuerpo de respuesta vacío")
        val bodyStr = String(bodyBytes, 0, minOf(bodyBytes.size, 1024), Charsets.UTF_8)

        if (bodyStr.contains("#EXTM3U")) {
            val fullManifest = String(bodyBytes, Charsets.UTF_8)
            val lines = fullManifest.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
            val firstMediaUrl = lines.firstOrNull() ?: error("Lista M3U8 vacía")
            val resolvedUrl = if (firstMediaUrl.startsWith("http://") || firstMediaUrl.startsWith("https://")) {
                firstMediaUrl
            } else {
                val base = url.substringBeforeLast('/')
                "$base/$firstMediaUrl"
            }
            downloadCanvasMediaFile(resolvedUrl, outputFile)
        } else {
            outputFile.writeBytes(bodyBytes)
            outputFile
        }
    }
}
