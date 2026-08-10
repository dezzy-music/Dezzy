/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Visualizador estilo Spicetify: ESFERA de puntos (fibonacci) con ondas
 * viajeras — SOLO partículas, sin halo ni anillos — alimentada con FFT EN
 * VIVO del audio (vía Visualizer de Android). Alrededor de la esfera vive
 * un ecosistema que reacciona a la música: estrellas de fondo con deriva y
 * parpadeo, chispas orbitando con estela, destellos "+" que pulsan con su
 * banda de frecuencia y barras de espectro a todo lo ancho del pie de
 * pantalla (lo que además hace el layout responsive en horizontal).
 *
 * Sustituye al AI Canvas: mismo punto de acceso (✨ en el player).
 */

package com.dezzy.music.ui.player

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
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
private const val GOLDEN_ANGLE = 2.399963f // radianes
private const val STAR_COUNT = 110
private const val SPARKLE_COUNT = 18
private const val ORBITER_COUNT = 3
private const val TWO_PI = 6.2831855f

/** Punto unitario sobre la esfera (fibonacci lattice). */
private class SpherePoint(val x: Float, val y: Float, val z: Float, val lat: Float)

private fun buildSphere(): List<SpherePoint> = List(SPHERE_POINTS) { i ->
    val y = 1f - 2f * (i + 0.5f) / SPHERE_POINTS
    val r = sqrt(1f - y * y)
    val phi = i * GOLDEN_ANGLE
    SpherePoint(cos(phi) * r, y, sin(phi) * r, asin(y))
}

/** Estrella ambiental: coords 0..1 relativas a la pantalla, z = parallax. */
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

/** Destello "+" alrededor de la esfera, ligado a una banda de frecuencia. */
private class Sparkle(
    val angle: Float,
    val dist: Float,
    val phase: Float,
    val rotSpeed: Float,
    val scale: Float,
    val band: Int,
)

private fun buildSparkles(): List<Sparkle> {
    val rnd = Random(777)
    return List(SPARKLE_COUNT) {
        Sparkle(
            angle = rnd.nextFloat() * TWO_PI,
            dist = 1.25f + rnd.nextFloat() * 0.75f,
            phase = rnd.nextFloat() * TWO_PI,
            rotSpeed = (rnd.nextFloat() - 0.5f) * 1.6f,
            scale = 0.5f + rnd.nextFloat(),
            band = rnd.nextInt(AudioSpectrumEngine.BANDS),
        )
    }
}

@Composable
fun NcsVisualizerScreen(
    mediaMetadata: MediaMetadata,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current

    // ── Permiso RECORD_AUDIO (requisito de Android para leer la salida) ──
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    // ── Color dinámico de la carátula (el cian de Spicetify como base) ──
    var sphereColor by remember { mutableStateOf(Color(0xFF1BA9C9)) }
    LaunchedEffect(mediaMetadata.thumbnailUrl) {
        val url = mediaMetadata.thumbnailUrl ?: return@LaunchedEffect
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
        // Preferimos swatches CROMÁTICOS (con saturación real). En carátulas
        // en blanco y negro todos los swatches son casi grises y su "hue" es 0
        // (rojo) — forzar saturación producía el rojo fijo del que se quejaba
        // el usuario. Elegimos el swatch más saturado disponible y, si la
        // portada es prácticamente monocroma, conservamos el cian neutro.
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
                // Portada monocroma → cian neutro (no rojo).
                Color(0xFF1BA9C9)
            } else {
                // Realzamos moderadamente la saturación de la fuente, sin forzarla.
                val sat = (hsl[1] * 1.15f).coerceIn(0.45f, 0.85f)
                Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], sat, 0.52f)))
            }
        }
    }

    // ── Motor de espectro ──
    val engine = remember { AudioSpectrumEngine() }
    var engineRunning by remember { mutableStateOf(false) }
    DisposableEffect(hasPermission) {
        if (hasPermission) {
            val sessionId = runCatching { playerConnection.player.audioSessionId }.getOrDefault(0)
            engineRunning = sessionId > 0 && engine.start(sessionId)
        }
        onDispose {
            engine.release()
            engineRunning = false
        }
    }

    // ── Frame loop ──
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF090D0F), Color(0xFF0D1113), Color(0xFF0A1013)),
                ),
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = timeSec // leerlo aquí ata el redibujado al frame loop
            val loud = engine.loudness
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val minDim = size.minDimension
            // Pulso global suave con la sonoridad (como el original)
            val baseR = minDim * 0.34f * (1f + loud * 0.06f)
            // En apaisado, los adornos se expanden a lo ancho para llenar la pantalla.
            val spreadX = (w / h).coerceIn(1f, 2.2f)

            // ── 1. Estrellas de fondo: deriva lenta + parpadeo con la música ──
            for (s in stars) {
                val drift = t * 0.012f * (0.25f + s.z)
                val sx = ((s.x + drift) % 1f) * w
                val sy = s.y * h
                val twinkle = 0.5f + 0.5f * sin(t * s.speed + s.phase)
                val alpha = (0.05f + 0.30f * twinkle * (0.35f + loud)).coerceIn(0f, 0.5f)
                drawCircle(
                    color = sphereColor.copy(alpha = alpha),
                    radius = minDim * 0.0016f * (0.6f + s.z * 1.6f),
                    center = Offset(sx, sy),
                )
            }

            // ── 2. Barras de espectro al pie, a todo lo ancho (espejo centro→bordes) ──
            val barsPerSide = 42
            val barW = w / (barsPerSide * 2f)
            val maxBarH = h * 0.08f
            for (i in 0 until barsPerSide) {
                val bandIdx = i * (AudioSpectrumEngine.BANDS - 1) / (barsPerSide - 1)
                val band = engine.bands[bandIdx]
                val bh = minDim * 0.004f + band * maxBarH * (0.35f + loud)
                val alpha = (0.08f + band * 0.42f).coerceIn(0f, 0.6f)
                val barSize = Size(barW * 0.62f, bh)
                val radius = CornerRadius(barW * 0.3f)
                drawRoundRect(
                    color = sphereColor.copy(alpha = alpha),
                    topLeft = Offset(cx + i * barW, h - bh),
                    size = barSize,
                    cornerRadius = radius,
                )
                drawRoundRect(
                    color = sphereColor.copy(alpha = alpha),
                    topLeft = Offset(cx - (i + 1) * barW, h - bh),
                    size = barSize,
                    cornerRadius = radius,
                )
            }

            // ── 3. Chispas orbitando la esfera con estela (brillan con los agudos) ──
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
                    drawCircle(
                        color = sphereColor.copy(
                            alpha = (0.10f + 0.75f * fade * (0.35f + 0.65f * treble)).coerceIn(0f, 1f),
                        ),
                        radius = baseR * 0.018f * fade * (1f + treble),
                        center = Offset(
                            cx + cos(a) * orbR * spreadX,
                            cy + sin(a) * orbR * tilt,
                        ),
                    )
                }
            }

            // ── 4. Destellos "+" alrededor: cada uno pulsa con su banda ──
            val sparkleStroke = (minDim * 0.0022f).coerceAtLeast(1f)
            for (sp in sparkles) {
                val band = engine.bands[sp.band]
                val pulse = 0.5f + 0.5f * sin(t * 1.8f + sp.phase)
                val alpha = (0.05f + 0.65f * pulse * (0.25f + 0.75f * band)).coerceIn(0f, 0.9f)
                if (alpha < 0.07f) continue
                val px = cx + cos(sp.angle) * baseR * sp.dist * spreadX
                val py = cy + sin(sp.angle) * baseR * sp.dist
                val len = baseR * 0.045f * sp.scale * (0.7f + band)
                val rot = t * sp.rotSpeed + sp.phase
                val c = cos(rot)
                val s2 = sin(rot)
                val color = sphereColor.copy(alpha = alpha)
                drawLine(
                    color = color,
                    start = Offset(px - c * len, py - s2 * len),
                    end = Offset(px + c * len, py + s2 * len),
                    strokeWidth = sparkleStroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(px + s2 * len, py - c * len),
                    end = Offset(px - s2 * len, py + c * len),
                    strokeWidth = sparkleStroke,
                    cap = StrokeCap.Round,
                )
            }

            // ── 5. Esfera: SOLO puntos con ondas viajeras (sin halo ni anillos) ──
            val rotY = t * 0.30f
            val cosR = cos(rotY)
            val sinR = sin(rotY)
            val dotBase = baseR * 0.016f

            for (p in sphere) {
                // Rotación lenta sobre el eje Y
                val x1 = p.x * cosR + p.z * sinR
                val z1 = -p.x * sinR + p.z * cosR
                val y1 = p.y

                // Onda viajera por latitud (los "anillos" de la captura) +
                // desplazamiento por la banda de frecuencia de su latitud.
                val bandIdx = (((y1 + 1f) / 2f) * (AudioSpectrumEngine.BANDS - 1)).toInt()
                    .coerceIn(0, AudioSpectrumEngine.BANDS - 1)
                val band = engine.bands[bandIdx]
                val ripple = 1f +
                    0.055f * sin(p.lat * 9f - t * 2.6f) * (0.35f + loud) +
                    band * 0.11f

                val rr = baseR * ripple
                val sx = cx + x1 * rr
                val sy = cy + y1 * rr
                val depth = (z1 + 1f) / 2f // 0 = atrás, 1 = frente

                drawCircle(
                    color = sphereColor.copy(
                        alpha = (0.22f + 0.72f * depth).coerceIn(0f, 1f),
                    ),
                    radius = dotBase * (0.55f + 0.85f * depth) * (1f + band * 0.5f),
                    center = Offset(sx, sy),
                )
            }
        }

        // ── Título / artista ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = mediaMetadata.title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = mediaMetadata.artists.joinToString { it.name },
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ── Cerrar ──
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.expand_more),
                contentDescription = "Cerrar visualizador",
                tint = Color.White,
            )
        }

        // ── Gate de permiso ──
        if (!hasPermission) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 40.dp, vertical = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "El visualizador necesita permiso de audio para reaccionar a la música (no graba nada).",
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(onClick = {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                    Text("Conceder permiso")
                }
            }
        }
    }
}
