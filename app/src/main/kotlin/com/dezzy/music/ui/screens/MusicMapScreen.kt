/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.models.toMediaMetadata
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.ui.component.LocalMenuState
import com.dezzy.music.ui.component.shimmer.ShimmerHost
import com.dezzy.music.ui.component.shimmer.TextPlaceholder
import com.dezzy.music.ui.menu.YouTubeSongMenu
import com.dezzy.music.viewmodels.MusicMapViewModel
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MusicMapScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: MusicMapViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current

    val selectedCountry by viewModel.selectedCountry.collectAsState()
    val countryCharts by viewModel.countryCharts.collectAsState()
    val isLoadingCharts by viewModel.isLoadingCharts.collectAsState()

    // Zoom/pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.8f, 4f)
        val maxOffset = 400f * (scale - 1f)
        offsetX = (offsetX + panChange.x).coerceIn(-maxOffset, maxOffset)
        offsetY = (offsetY + panChange.y).coerceIn(-maxOffset, maxOffset)
    }

    val bottomPanelHeight by animateDpAsState(
        targetValue = if (selectedCountry != null) 380.dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "BottomPanel"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0D1B2A),
                        Color(0xFF1B2838),
                        Color(0xFF0D1B2A),
                    )
                )
            )
    ) {
        // ── Top Bar ───────────────────────────────────────────────────────────
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🗺️ Music Map",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 20.sp,
                    )
                }
            },
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color(0xCC0D1B2A),
            ),
            scrollBehavior = scrollBehavior,
            modifier = Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            ),
        )

        // ── World Map Canvas ──────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 64.dp, bottom = if (selectedCountry != null) 380.dp else 0.dp)
                .transformable(state = transformableState)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
                .pointerInput(scale, offsetX, offsetY) {
                    detectTapGestures { tapOffset ->
                        // Convert tap to normalized map coordinates
                        val nx = (tapOffset.x / size.width)
                        val ny = (tapOffset.y / size.height)
                        val country = viewModel.getCountryAtMapPosition(nx, ny)
                        if (country != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.selectCountry(country)
                        } else {
                            viewModel.deselectCountry()
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawWorldMapBackground()
                drawCountryDots(selectedCountry?.countryCode)
            }
        }

        // ── Subtitle hint ─────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = selectedCountry == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 200.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "🎵",
                    fontSize = 40.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Toca un país para explorar su música",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Pellizca para hacer zoom",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.35f),
                )
            }
        }

        // ── Bottom Country Panel ──────────────────────────────────────────────
        AnimatedVisibility(
            visible = selectedCountry != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            selectedCountry?.let { country ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .shadow(24.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = Color(0xFF1A2332),
                    tonalElevation = 8.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(
                                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                            )
                    ) {
                        // Country header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Flag + name
                            Text(text = country.emoji, fontSize = 36.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = country.countryName,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                                Text(
                                    text = country.dominantGenres.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = country.accentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // Close button
                            androidx.compose.material3.IconButton(
                                onClick = { viewModel.deselectCountry() }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.close),
                                    contentDescription = "Close",
                                    tint = Color.White.copy(alpha = 0.6f),
                                )
                            }
                        }

                        // Artist highlight chip
                        country.artistHighlight?.let { artist ->
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = country.accentColor.copy(alpha = 0.15f),
                                ) {
                                    Text(
                                        text = "🎤 $artist",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = country.accentColor,
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        // Charts section
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Top Charts",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = CircleShape,
                                color = country.accentColor.copy(alpha = 0.2f),
                            ) {
                                Text(
                                    text = "LIVE",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = country.accentColor,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        if (isLoadingCharts) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        color = country.accentColor,
                                        modifier = Modifier.size(28.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "Cargando charts de ${country.countryName}...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.5f),
                                    )
                                }
                            }
                        } else {
                            // Top songs list (first section of charts, max 5 items)
                            val topSongs = countryCharts?.sections
                                ?.firstOrNull()
                                ?.items
                                ?.filterIsInstance<SongItem>()
                                ?.take(5)
                                .orEmpty()

                            if (topSongs.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(100.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "No hay charts disponibles para esta región",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.4f),
                                    )
                                }
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    items(topSongs, key = { it.id }) { song ->
                                        val isCurrentSong = mediaMetadata?.id == song.id
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(
                                                    if (isCurrentSong) country.accentColor.copy(alpha = 0.15f)
                                                    else Color.Transparent
                                                )
                                                .clickable {
                                                    if (isCurrentSong) {
                                                        playerConnection.player.togglePlayPause()
                                                    } else {
                                                        playerConnection.playQueue(
                                                            YouTubeQueue(song.endpoint ?: return@clickable)
                                                        )
                                                    }
                                                }
                                                .padding(horizontal = 8.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // Thumbnail
                                            AsyncImage(
                                                model = song.thumbnail,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(RoundedCornerShape(6.dp)),
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = song.title,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isCurrentSong) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isCurrentSong) country.accentColor else Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = song.artists?.joinToString(", ") { it.name } ?: "",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color.White.copy(alpha = 0.6f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            if (isCurrentSong && isPlaying) {
                                                Icon(
                                                    painter = painterResource(R.drawable.graphic_eq),
                                                    contentDescription = null,
                                                    tint = country.accentColor,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                    }

                                    item {
                                        Spacer(Modifier.height(4.dp))
                                        FilledTonalButton(
                                            onClick = { navController.navigate("charts_screen") },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = country.accentColor.copy(alpha = 0.2f),
                                                contentColor = country.accentColor,
                                            ),
                                        ) {
                                            Text("Ver todos los charts →")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Canvas drawing functions ──────────────────────────────────────────────────

private fun DrawScope.drawWorldMapBackground() {
    // Stylized ocean background with subtle grid lines
    val gridColor = Color(0xFF1E3A5F).copy(alpha = 0.3f)
    val cols = 18
    val rows = 10

    for (i in 0..cols) {
        val x = size.width * i / cols
        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.5f)
    }
    for (j in 0..rows) {
        val y = size.height * j / rows
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5f)
    }

    // Draw landmass silhouettes as simplified rectangular blobs
    // These are approximate continent shapes for visual reference
    val landColor = Color(0xFF1A3A2A).copy(alpha = 0.6f)

    // North America
    drawRoundRect(landColor, topLeft = Offset(size.width * 0.05f, size.height * 0.10f),
        size = Size(size.width * 0.22f, size.height * 0.45f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f))

    // South America
    drawRoundRect(landColor, topLeft = Offset(size.width * 0.18f, size.height * 0.48f),
        size = Size(size.width * 0.14f, size.height * 0.40f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f))

    // Europe
    drawRoundRect(landColor, topLeft = Offset(size.width * 0.40f, size.height * 0.12f),
        size = Size(size.width * 0.10f, size.height * 0.22f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f))

    // Africa
    drawRoundRect(landColor, topLeft = Offset(size.width * 0.43f, size.height * 0.35f),
        size = Size(size.width * 0.12f, size.height * 0.40f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f))

    // Asia
    drawRoundRect(landColor, topLeft = Offset(size.width * 0.52f, size.height * 0.10f),
        size = Size(size.width * 0.30f, size.height * 0.45f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f))

    // Oceania
    drawRoundRect(landColor, topLeft = Offset(size.width * 0.78f, size.height * 0.55f),
        size = Size(size.width * 0.12f, size.height * 0.22f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f))
}

private fun DrawScope.drawCountryDots(selectedCountryCode: String?) {
    WorldMapData.countries.forEach { country ->
        val cx = country.mapX * size.width
        val cy = country.mapY * size.height
        val isSelected = country.countryCode == selectedCountryCode
        val baseRadius = if (isSelected) 14f else 9f
        val color = country.accentColor

        // Glow ring for selected
        if (isSelected) {
            drawCircle(
                color = color.copy(alpha = 0.25f),
                radius = baseRadius * 2.2f,
                center = Offset(cx, cy),
            )
            drawCircle(
                color = color.copy(alpha = 0.4f),
                radius = baseRadius * 1.5f,
                center = Offset(cx, cy),
            )
        }

        // Main dot
        drawCircle(
            color = color.copy(alpha = if (isSelected) 1f else 0.85f),
            radius = baseRadius,
            center = Offset(cx, cy),
        )

        // Inner white highlight
        drawCircle(
            color = Color.White.copy(alpha = 0.5f),
            radius = baseRadius * 0.35f,
            center = Offset(cx - baseRadius * 0.2f, cy - baseRadius * 0.2f),
        )
    }
}
