package com.dezzy.music.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import androidx.palette.graphics.Palette
import com.dezzy.music.playback.PlayerConnection
import com.dezzy.music.models.MediaMetadata
import com.dezzy.music.utils.AudioSpectrumEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val SPHERE_POINTS = 950
private const val GOLDEN_ANGLE = 2.399963f
private const val STAR_COUNT = 110
private const val SPARKLE_COUNT = 18
private const val ORBITER_COUNT = 3
private const val TWO_PI = 6.2831855f

private class SpherePoint(val x: Float, val y: Float, val z: Float, val lat: Float)

private fun buildSphere(): List<SpherePoint> = List(SPHERE_POINTS) { i ->
    val y = 1f - 2f * (i + 0.5f) / SPHERE_POINTS
    val r = sqrt(1f - y * y)
    val phi = i * GOLDEN_ANGLE
    SpherePoint(cos(phi) * r, y, sin(phi) * r, asin(y))
}

private class Star(val x: Float, val y: Float, val z: Float, val phase: Float, val speed: Float)

private fun buildStars(): List<Star> {
    val rnd = Random(20260716)
    return List(STAR_COUNT) {
        Star(
            x = rnd.nextFloat(),
            y = rnd.nextFloat(),
            z = rnd.nextFloat(),
            phase = rnd.nextFloat() * TWO_PI,
            speed = 0.6f + rnd.nextFloat() * 1.8f,
        )
    }
}

private class Sparkle(val angle: Float, val dist: Float, val scale: Float, val phase: Float, val rotSpeed: Float, val band: Int)

private fun buildSparkles(): List<Sparkle> {
    val rnd = Random(8472)
    return List(SPARKLE_COUNT) { i ->
        Sparkle(
            angle = rnd.nextFloat() * TWO_PI,
            dist = 1.05f + rnd.nextFloat() * 0.45f,
            scale = 0.5f + rnd.nextFloat() * 1.5f,
            phase = rnd.nextFloat() * TWO_PI,
            rotSpeed = 0.8f + rnd.nextFloat() * 2f,
            band = 10 + i * 2,
        )
    }
}

@Composable
fun DJVisualizerBox(
    onRequestSong: (String) -> Unit = {},
    onUserTypingChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    playerConnection: PlayerConnection?,
    mediaMetadata: MediaMetadata?,
    isDjTalking: Boolean,
    transparentBackground: Boolean = false,
    alwaysShowOrb: Boolean = false
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    var sphereColor by remember { mutableStateOf(Color(0xFFA855F7)) }
    LaunchedEffect(mediaMetadata?.thumbnailUrl) {
        val url = mediaMetadata?.thumbnailUrl ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(120, 120)
                        .allowHardware(false)
                        .build()
                ).image?.toBitmap()
            }.getOrNull()
        } ?: return@LaunchedEffect
        val palette = withContext(Dispatchers.Default) {
            Palette.from(bitmap).maximumColorCount(12).generate()
        }
        val swatchColors = listOfNotNull(
            palette.vibrantSwatch?.rgb,
            palette.lightVibrantSwatch?.rgb,
            palette.darkVibrantSwatch?.rgb,
            palette.dominantSwatch?.rgb,
            palette.mutedSwatch?.rgb,
        )
        val best = swatchColors.maxByOrNull { rgb ->
            val h = FloatArray(3); ColorUtils.colorToHSL(rgb, h); h[1]
        }
        if (best != null) {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(best, hsl)
            sphereColor = if (hsl[1] < 0.12f) {
                Color(0xFF1BA9C9)
            } else {
                val sat = (hsl[1] * 1.15f).coerceIn(0.45f, 0.85f)
                Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], sat, 0.52f)))
            }
        }
    }

    val djViewModel = com.dezzy.music.LocalDjViewModel.current
    val djAudioSessionId by (djViewModel?.djAudioSessionId ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsState()

    val engine = remember { AudioSpectrumEngine() }
    var engineRunning by remember { mutableStateOf(false) }
    DisposableEffect(hasPermission, djAudioSessionId, isDjTalking) {
        if (hasPermission && playerConnection != null) {
            var sessionId = runCatching { playerConnection.player.audioSessionId }.getOrDefault(0)
            if (isDjTalking && djAudioSessionId > 0) {
                sessionId = djAudioSessionId
            }
            engineRunning = sessionId > 0 && engine.start(sessionId)
        }
        onDispose {
            engine.release()
            engineRunning = false
        }
    }

    var timeSec by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    timeSec += ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                }
                last = now
            }
        }
    }

    val sphere = remember { buildSphere() }
    val stars = remember { buildStars() }
    val sparkles = remember { buildSparkles() }
    val nativeCoords = remember { FloatArray(SPHERE_POINTS * 4) }
    val orbAlpha by animateFloatAsState(targetValue = if (isDjTalking || alwaysShowOrb) 1f else 0f, animationSpec = tween(1500))

    Box(
        modifier = modifier.then(
            if (transparentBackground) {
                Modifier.background(Color.Transparent)
            } else {
                Modifier.background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF090412), Color(0xFF140728), Color(0xFF0C041A))
                    )
                )
            }
        )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = timeSec
            val isPlaying = playerConnection?.player?.isPlaying == true
            val isRealAudio = engineRunning && engine.loudness > 0.001f
            val simLoudness = if (isPlaying || isDjTalking) {
                0.28f + 0.14f * sin(t * 3.2f) + 0.08f * cos(t * 5.4f)
            } else {
                0.12f + 0.04f * sin(t * 1.5f)
            }
            val loud = if (isRealAudio) engine.loudness else simLoudness.coerceIn(0.05f, 0.95f)

            if (!isRealAudio) {
                for (i in 0 until AudioSpectrumEngine.BANDS) {
                    val freq = (i + 1).toFloat() / AudioSpectrumEngine.BANDS
                    val wave = (0.22f + 0.38f * sin(t * 3.8f + freq * 7.5f) * cos(t * 2.2f - freq * 3.8f)).coerceIn(0.05f, 0.95f)
                    engine.bands[i] = if (isPlaying || isDjTalking) wave * (0.4f + loud * 0.6f) else 0.08f
                }
            }
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val minDim = size.minDimension
            val baseR = minDim * 0.34f * (1f + loud * 0.25f)
            val spreadX = 1f

            for (s in stars) {
                val drift = t * 0.012f * (0.25f + s.z)
                val sx = ((s.x + drift) % 1f) * w
                val sy = s.y * h
                val twinkle = 0.5f + 0.5f * sin(t * s.speed + s.phase)
                val alpha = ((0.05f + 0.30f * twinkle * (0.35f + loud)).coerceIn(0f, 0.5f)) * orbAlpha
                drawCircle(color = sphereColor.copy(alpha = alpha), radius = minDim * 0.0016f * (0.6f + s.z * 1.6f), center = Offset(sx, sy))
            }

            val barsPerSide = 42
            val barW = w / (barsPerSide * 2f)
            val maxBarH = h * 0.08f
            for (i in 0 until barsPerSide) {
                val bandIdx = i * (AudioSpectrumEngine.BANDS - 1) / (barsPerSide - 1)
                val band = engine.bands[bandIdx]
                val bh = minDim * 0.004f + band * maxBarH * (0.35f + loud)
                val alpha = ((0.08f + band * 0.42f).coerceIn(0f, 0.6f)) * orbAlpha
                val barSize = Size(barW * 0.62f, bh)
                val radius = CornerRadius(barW * 0.3f)
                drawRoundRect(color = sphereColor.copy(alpha = alpha), topLeft = Offset(cx + i * barW, h - bh), size = barSize, cornerRadius = radius)
                drawRoundRect(color = sphereColor.copy(alpha = alpha), topLeft = Offset(cx - (i + 1) * barW, h - bh), size = barSize, cornerRadius = radius)
            }

            for (k in 0 until ORBITER_COUNT) {
                val dir = if (k % 2 == 0) 1f else -1f
                val speed = 0.55f + k * 0.18f
                val orbR = baseR * (1.30f + k * 0.22f)
                val tilt = 0.30f + k * 0.10f
                val baseA = t * speed * dir + k * 2.1f
                val treble = engine.bands[40 + k * 6]
                for (j in 0 until 7) {
                    val a = baseA - dir * j * 0.05f * (1f + loud)
                    val fade = 1f - j / 7f
                    drawCircle(color = sphereColor.copy(alpha = ((0.10f + 0.75f * fade * (0.35f + 0.65f * treble)).coerceIn(0f, 1f)) * orbAlpha), radius = baseR * 0.018f * fade * (1f + treble), center = Offset(cx + cos(a) * orbR * spreadX, cy + sin(a) * orbR * tilt))
                }
            }

            val sparkleStroke = (minDim * 0.0022f).coerceAtLeast(1f)
            for (sp in sparkles) {
                val band = engine.bands[sp.band]
                val pulse = 0.5f + 0.5f * sin(t * 1.8f + sp.phase)
                val alpha = ((0.05f + 0.65f * pulse * (0.25f + 0.75f * band)).coerceIn(0f, 0.9f)) * orbAlpha
                if (alpha < 0.07f) continue
                val px = cx + cos(sp.angle) * baseR * sp.dist * spreadX
                val py = cy + sin(sp.angle) * baseR * sp.dist
                val len = baseR * 0.045f * sp.scale * (0.7f + band)
                val rot = t * sp.rotSpeed + sp.phase
                val c = cos(rot)
                val s2 = sin(rot)
                val color = sphereColor.copy(alpha = alpha)
                drawLine(color = color, start = Offset(px - c * len, py - s2 * len), end = Offset(px + c * len, py + s2 * len), strokeWidth = sparkleStroke, cap = StrokeCap.Round)
                drawLine(color = color, start = Offset(px + s2 * len, py - c * len), end = Offset(px - s2 * len, py + c * len), strokeWidth = sparkleStroke, cap = StrokeCap.Round)
            }

            val rotY = t * 0.30f
            val cosR = cos(rotY)
            val sinR = sin(rotY)
            val dotBase = baseR * 0.016f

            if (com.dezzy.music.utils.NativeDspEngine.isNativeSupported()) {
                com.dezzy.music.utils.NativeDspEngine.computeSpherePointsNative(
                    nativeCoords, t, engine.bands, loud, cx, cy, baseR, orbAlpha
                )
                for (i in 0 until SPHERE_POINTS) {
                    val offset = i * 4
                    val alpha = nativeCoords[offset + 3]
                    if (alpha <= 0.005f) continue
                    drawCircle(
                        color = sphereColor.copy(alpha = alpha),
                        radius = nativeCoords[offset + 2],
                        center = Offset(nativeCoords[offset], nativeCoords[offset + 1])
                    )
                }
            } else {
                for (p in sphere) {
                    val x1 = p.x * cosR + p.z * sinR
                    val z1 = -p.x * sinR + p.z * cosR
                    val y1 = p.y
                    val bandIdx = (((y1 + 1f) / 2f) * (AudioSpectrumEngine.BANDS - 1)).toInt().coerceIn(0, AudioSpectrumEngine.BANDS - 1)
                    val band = engine.bands[bandIdx]
                    val ripple = 1f + 0.055f * sin(p.lat * 9f - t * 2.6f) * (0.35f + loud) + band * 0.11f
                    val rr = baseR * ripple
                    val sx = cx + x1 * rr
                    val sy = cy + y1 * rr
                    val depth = (z1 + 1f) / 2f
                    drawCircle(color = sphereColor.copy(alpha = ((0.22f + 0.72f * depth).coerceIn(0f, 1f)) * orbAlpha), radius = dotBase * (0.55f + 0.85f * depth) * (1f + band * 0.5f), center = Offset(sx, sy))
                }
            }
        }


    }
}
