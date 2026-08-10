/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.dezzy.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.constants.SwipeSensitivityKey
import com.dezzy.music.constants.ThumbnailCornerRadius
import com.dezzy.music.constants.UseNewMiniPlayerDesignKey
import com.dezzy.music.constants.MiniPlayerStyle
import com.dezzy.music.constants.MiniPlayerStyleKey
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import com.dezzy.music.constants.CropThumbnailToSquareKey
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.models.MediaMetadata
import com.dezzy.music.utils.rememberPreference
import android.os.Build
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.dezzy.music.constants.PlayerBackgroundStyle
import com.dezzy.music.constants.MiniPlayerBackgroundStyleKey
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.allowHardware
import coil3.toBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import com.dezzy.music.constants.DynamicMiniPlayerColorKey
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.absoluteValue
import com.dezzy.music.ui.component.BottomSheetState
import kotlin.math.roundToInt

// Glow Animated imports
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import com.dezzy.music.ui.theme.PlayerColorExtractor

enum class MiniPlayerBackgroundStyle {
    DEFAULT,
    BLUR,
    GRADIENT,
    GLOW_ANIMATED,
    LIVE_MESH
}


@Composable
fun MiniPlayer(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
    navController: NavController,
    state: BottomSheetState
) {
    NewMiniPlayer(
        position = position,
        duration = duration,
        modifier = modifier,
        pureBlack = pureBlack,
        navController = navController,
        state = state
    )
}

@Composable
private fun NewMiniPlayer(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
    navController: NavController,
    state: BottomSheetState
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val layoutDirection = LocalLayoutDirection.current
    val coroutineScope = rememberCoroutineScope()
    val swipeSensitivity by rememberPreference(SwipeSensitivityKey, 0.73f)
    val swipeThumbnail by rememberPreference(com.dezzy.music.constants.SwipeThumbnailKey, true)

    val (savedStyle, setStyle) = rememberPreference(MiniPlayerStyleKey, defaultValue = MiniPlayerStyle.PILL.name)
    val miniPlayerStyle = remember(savedStyle) {
        try { MiniPlayerStyle.valueOf(savedStyle) } catch (e: Exception) { MiniPlayerStyle.PILL }
    }
    
    val (dynamicColorEnabled) = rememberPreference(DynamicMiniPlayerColorKey, defaultValue = true)
    val (savedBgStyle) = rememberPreference(MiniPlayerBackgroundStyleKey, defaultValue = "DEFAULT")
    val miniPlayerBackground = remember(savedBgStyle) {
        try { MiniPlayerBackgroundStyle.valueOf(savedBgStyle) } catch (e: Exception) { MiniPlayerBackgroundStyle.DEFAULT }
    }
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    var paletteColor by remember { mutableStateOf<Color?>(null) }
    var miniPlayerGradientColors by remember { mutableStateOf<List<Color>>(emptyList()) }
    val context = LocalContext.current

    val defaultGradientColors = listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant)
    val fallbackColor = MaterialTheme.colorScheme.surface.toArgb()
    
    LaunchedEffect(mediaMetadata?.thumbnailUrl, dynamicColorEnabled, miniPlayerStyle, miniPlayerBackground) {
        val url = mediaMetadata?.thumbnailUrl
        if (url != null) {
            withContext(Dispatchers.IO) {
                try {
                    val req = ImageRequest.Builder(context).data(url).size(200, 200).allowHardware(false).build()
                    val res = context.imageLoader.execute(req)
                    val bmp = res.image?.toBitmap()
                    if (bmp != null) {
                        val p = Palette.from(bmp).generate()
                        if (dynamicColorEnabled) {
                            paletteColor = p.dominantSwatch?.rgb?.let { Color(it) } 
                                ?: p.mutedSwatch?.rgb?.let { Color(it) }
                        } else {
                            paletteColor = null
                        }

                        if (miniPlayerStyle == MiniPlayerStyle.GLOW_ANIMATED ||
                            miniPlayerBackground == MiniPlayerBackgroundStyle.GRADIENT ||
                            miniPlayerBackground == MiniPlayerBackgroundStyle.GLOW_ANIMATED ||
                            miniPlayerBackground == MiniPlayerBackgroundStyle.LIVE_MESH) {
                            val extracted = PlayerColorExtractor.extractGradientColors(p, fallbackColor)
                            miniPlayerGradientColors = extracted
                        } else {
                            miniPlayerGradientColors = emptyList()
                        }
                    }
                } catch (_: Exception) {
                    paletteColor = null
                    miniPlayerGradientColors = defaultGradientColors
                }
            }
        } else {
            paletteColor = null
            miniPlayerGradientColors = defaultGradientColors
        }
    }

    SwipeableMiniPlayerBox(
        modifier = modifier,
        swipeSensitivity = swipeSensitivity,
        swipeThumbnail = swipeThumbnail,
        playerConnection = playerConnection,
        layoutDirection = layoutDirection,
        coroutineScope = coroutineScope,
        pureBlack = pureBlack,
        useLegacyBackground = false
    ) { offsetX ->
        val shape = when (miniPlayerStyle) {
            MiniPlayerStyle.PILL -> RoundedCornerShape(32.dp)
            MiniPlayerStyle.CARD -> RoundedCornerShape(16.dp)
            MiniPlayerStyle.GLASS -> RoundedCornerShape(24.dp)
            MiniPlayerStyle.COMPACT -> RoundedCornerShape(8.dp)
            MiniPlayerStyle.GLOW_ANIMATED -> RoundedCornerShape(24.dp)
        }
        val baseBackgroundColor = when (miniPlayerStyle) {
            MiniPlayerStyle.PILL -> MaterialTheme.colorScheme.surfaceContainer
            MiniPlayerStyle.CARD -> MaterialTheme.colorScheme.surfaceContainerHigh
            MiniPlayerStyle.GLASS -> MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
            MiniPlayerStyle.COMPACT -> Color.Transparent
            MiniPlayerStyle.GLOW_ANIMATED -> Color.Transparent
        }
        val backgroundColor = if (dynamicColorEnabled && paletteColor != null) {
            if (miniPlayerStyle == MiniPlayerStyle.GLASS) paletteColor!!.copy(alpha = 0.4f)
            else if (miniPlayerStyle == MiniPlayerStyle.COMPACT || miniPlayerStyle == MiniPlayerStyle.GLOW_ANIMATED) Color.Transparent
            else paletteColor!!
        } else {
            baseBackgroundColor
        }

        val glowModifier = if (miniPlayerStyle == MiniPlayerStyle.GLOW_ANIMATED && miniPlayerGradientColors.isNotEmpty()) {
            val infiniteTransition = rememberInfiniteTransition(label = "MiniGlowAnimation")
            val progress by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(20000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "miniGlowProgress"
            )

            Modifier.drawWithCache {
                val width = size.width
                val height = size.height
                val baseColor = Color(0xFF050505)

                onDrawBehind {
                    val p = progress

                    fun rotatedColorAt(index: Int): Color {
                        val size = miniPlayerGradientColors.size
                        val idx = index.toFloat() + p * size
                        val a = kotlin.math.floor(idx).toInt() % size
                        val b = (a + 1) % size
                        val frac = idx - kotlin.math.floor(idx)
                        return androidx.compose.ui.graphics.lerp(miniPlayerGradientColors.getOrElse(a) { Color.DarkGray }, miniPlayerGradientColors.getOrElse(b) { Color.DarkGray }, frac)
                    }

                    fun oscillate(min: Float, max: Float, phase: Float, speed: Float = 1f): Float {
                        val v = kotlin.math.sin(2f * kotlin.math.PI.toFloat() * (p * speed + phase)).toFloat()
                        return min + (max - min) * ((v + 1f) * 0.5f)
                    }

                    val color1 = rotatedColorAt(0)
                    val color2 = rotatedColorAt(1)
                    val color3 = rotatedColorAt(2)
                    val color4 = rotatedColorAt(3)
                    val color5 = rotatedColorAt(4)
                    val color6 = rotatedColorAt(5)

                    val o1x = oscillate(0.0f, 1.0f, 0.00f, 1.0f)
                    val o1y = oscillate(0.0f, 0.5f, 0.07f, 1.0f)
                    val r1 = oscillate(0.8f, 1.6f, 0.12f, 1.0f)

                    val o2x = oscillate(1.0f, 0.0f, 0.2f, 1.0f)
                    val o2y = oscillate(0.5f, 1.0f, 0.25f, 1.0f)
                    val r2 = oscillate(0.7f, 1.5f, 0.18f, 1.0f)

                    val o3x = oscillate(0.2f, 0.8f, 0.33f, 1.0f)
                    val o3y = oscillate(0.8f, 0.2f, 0.36f, 1.0f)
                    val r3 = oscillate(0.6f, 1.4f, 0.29f, 1.0f)

                    val o4x = oscillate(0.3f, 0.7f, 0.44f, 1.0f)
                    val o4y = oscillate(0.2f, 0.8f, 0.41f, 1.0f)
                    val r4 = oscillate(0.9f, 1.7f, 0.47f, 1.0f)

                    val o5x = oscillate(0.4f, 0.6f, 0.55f, 1.0f)
                    val o5y = oscillate(0.0f, 1.0f, 0.51f, 1.0f)
                    val r5 = oscillate(0.7f, 1.5f, 0.58f, 1.0f)

                    val o6x = oscillate(0.0f, 1.0f, 0.66f, 1.0f)
                    val o6y = oscillate(0.5f, 0.7f, 0.62f, 1.0f)
                    val r6 = oscillate(0.8f, 1.8f, 0.69f, 1.0f)

                    val brush1 = Brush.radialGradient(
                        colors = listOf(color1.copy(alpha = 0.85f), color1.copy(alpha = 0.5f), Color.Transparent),
                        center = Offset(width * o1x, height * o1y),
                        radius = width * r1
                    )
                    val brush2 = Brush.radialGradient(
                        colors = listOf(color2.copy(alpha = 0.8f), color2.copy(alpha = 0.45f), Color.Transparent),
                        center = Offset(width * o2x, height * o2y),
                        radius = width * r2
                    )
                    val brush3 = Brush.radialGradient(
                        colors = listOf(color3.copy(alpha = 0.75f), color3.copy(alpha = 0.4f), Color.Transparent),
                        center = Offset(width * o3x, height * o3y),
                        radius = width * r3
                    )
                    val brush4 = Brush.radialGradient(
                        colors = listOf(color4.copy(alpha = 0.7f), color4.copy(alpha = 0.35f), Color.Transparent),
                        center = Offset(width * o4x, height * o4y),
                        radius = width * r4
                    )
                    val brush5 = Brush.radialGradient(
                        colors = listOf(color5.copy(alpha = 0.65f), color5.copy(alpha = 0.3f), Color.Transparent),
                        center = Offset(width * o5x, height * o5y),
                        radius = width * r5
                    )
                    val brush6 = Brush.radialGradient(
                        colors = listOf(color6.copy(alpha = 0.6f), color6.copy(alpha = 0.25f), Color.Transparent),
                        center = Offset(width * o6x, height * o6y),
                        radius = width * r6
                    )

                    drawRect(color = baseColor)
                    drawRect(brush = brush1)
                    drawRect(brush = brush2)
                    drawRect(brush = brush3)
                    drawRect(brush = brush4)
                    drawRect(brush = brush5)
                    drawRect(brush = brush6)
                }
            }
        } else {
            Modifier.background(backgroundColor)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .then(if (miniPlayerStyle == MiniPlayerStyle.CARD) Modifier.shadow(8.dp, shape) else Modifier)
                .clip(shape)
                .then(if (miniPlayerBackground == MiniPlayerBackgroundStyle.DEFAULT) glowModifier else Modifier)
                .then(if (miniPlayerStyle == MiniPlayerStyle.GLASS || miniPlayerStyle == MiniPlayerStyle.GLOW_ANIMATED) Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=0.2f), shape) else Modifier)
        ) {
            if (miniPlayerBackground != MiniPlayerBackgroundStyle.DEFAULT) {
                MiniPlayerBackgroundLayer(
                    style = miniPlayerBackground,
                    mediaMetadata = mediaMetadata,
                    gradientColors = miniPlayerGradientColors
                )
            }
            NewMiniPlayerContent(
                pureBlack = pureBlack,
                position = position,
                duration = duration,
                playerConnection = playerConnection,
                miniPlayerStyle = miniPlayerStyle,
                onStyleChange = { setStyle(it.name) },
                navController = navController,
                state = state
            )
        }
    }
}

@Composable
private fun MiniPlayerBackgroundLayer(
    style: MiniPlayerBackgroundStyle,
    mediaMetadata: MediaMetadata?,
    gradientColors: List<Color>
) {
    val context = LocalContext.current
    
    when (style) {
        MiniPlayerBackgroundStyle.BLUR -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(mediaMetadata?.thumbnailUrl)
                        .size(128, 128)
                        .allowHardware(false)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .blur(30.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(Color.Black.copy(alpha = 0.45f))
                )
            }
        }
        MiniPlayerBackgroundStyle.GRADIENT -> {
            if (gradientColors.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(Brush.verticalGradient(gradientColors))
                        .background(Color.Black.copy(alpha = 0.2f))
                )
            }
        }
        MiniPlayerBackgroundStyle.GLOW_ANIMATED -> {
            if (gradientColors.isNotEmpty()) {
                val infiniteTransition = rememberInfiniteTransition(label = "GlowAnimation")
                val progress = infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(20000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "glowProgress"
                )

                val colors = gradientColors
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .drawBehind {
                            val p = progress.value
                            val width = size.width
                            val height = size.height
                            
                            fun rotatedColorAt(index: Int): Color {
                                val size = colors.size
                                val idx = index.toFloat() + p * size
                                val a = kotlin.math.floor(idx).toInt() % size
                                val b = (a + 1) % size
                                val frac = idx - kotlin.math.floor(idx)
                                return androidx.compose.ui.graphics.lerp(colors.getOrElse(a) { Color.DarkGray }, colors.getOrElse(b) { Color.DarkGray }, frac)
                            }

                            fun oscillate(min: Float, max: Float, phase: Float): Float {
                                val v = kotlin.math.sin(2f * kotlin.math.PI.toFloat() * (p + phase)).toFloat()
                                return min + (max - min) * ((v + 1f) * 0.5f)
                            }

                            val c1 = rotatedColorAt(0)
                            val c2 = rotatedColorAt(1)

                            val o1x = oscillate(0.0f, 1.0f, 0.0f)
                            val o1y = oscillate(0.0f, 0.5f, 0.1f)
                            val o2x = oscillate(1.0f, 0.0f, 0.2f)
                            val o2y = oscillate(0.5f, 1.0f, 0.3f)

                            val b1 = Brush.radialGradient(
                                colors = listOf(c1.copy(alpha = 0.8f), Color.Transparent),
                                center = Offset(width * o1x, height * o1y),
                                radius = width * 1.2f
                            )
                            val b2 = Brush.radialGradient(
                                colors = listOf(c2.copy(alpha = 0.7f), Color.Transparent),
                                center = Offset(width * o2x, height * o2y),
                                radius = width * 1.0f
                            )
                            
                            drawRect(Color(0xFF050505))
                            drawRect(b1)
                            drawRect(b2)
                        }
                )
            }
        }
        MiniPlayerBackgroundStyle.LIVE_MESH -> {
            val infiniteTransition = rememberInfiniteTransition(label = "liveMesh")
            val rotation = infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(60000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "rotation"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .graphicsLayer {
                        scaleX = 1.5f
                        scaleY = 1.5f
                    }
            ) {
                val matrix = remember { ColorMatrix().apply { setToSaturation(1.6f) } }
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(mediaMetadata?.thumbnailUrl)
                        .size(128, 128)
                        .allowHardware(false)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(matrix),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .blur(40.dp)
                        .graphicsLayer { rotationZ = rotation.value }
                )
                Box(modifier = Modifier.fillMaxWidth().height(64.dp).background(Color.Black.copy(alpha = 0.3f)))
            }
        }
        else -> {
            // Default or custom: no-op
        }
    }
}