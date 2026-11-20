/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Estilos extra del visualizador (boceto dezzy-android-visualizadores-bocetos.html):
 * CORDILLERA (crestas apiladas tipo «Unknown Pleasures»), TÚNEL (anillos que
 * vienen hacia ti deformados por el espectro) y ESPEJO (barras con reflejo y
 * picos que caen). Todos se pintan con los colores de la carátula ([VisualizerPalette])
 * y leen las bandas en vivo de [AudioSpectrumEngine].
 */

package com.dezzy.music.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.toArgb
import com.dezzy.music.utils.AudioSpectrumEngine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Estilos disponibles, en el orden de los chips (y del deslizamiento). */
enum class VisualizerStyle(val label: String) {
    ESFERA("Esfera"),
    CORDILLERA("Cordillera"),
    TUNEL("Túnel"),
    ESPEJO("Espejo"),
}

/**
 * Tres tonos sacados de la carátula: [light] (líneas de fondo), [accent]
 * (primer plano) y [deep] (base del degradado / reflejo).
 */
data class VisualizerPalette(val light: Color, val accent: Color, val deep: Color)

/** Los tres tonos a partir del color base de la carátula. */
fun visualizerPaletteOf(base: Color): VisualizerPalette {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(base.toArgb(), hsl)
    fun tone(sat: Float, light: Float) =
        Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hsl[0], sat, light)))
    return VisualizerPalette(
        light = tone(hsl[1] * 0.7f, 0.82f),
        accent = tone(hsl[1], 0.66f),
        deep = tone(hsl[1], 0.36f),
    )
}

private const val BANDS = AudioSpectrumEngine.BANDS
private const val TWO_PI_F = 6.2831855f

// ─────────────────────────── CORDILLERA ───────────────────────────

private const val RIDGE_ROWS = 34
private const val RIDGE_POINTS = 60
private const val RIDGE_STEP_SEC = 0.07f

/** Historial de filas de espectro: la fila 0 es la más reciente (la de delante). */
class RidgeState {
    val rows = ArrayDeque<FloatArray>()
    var lastPush = -1f
    val path = Path()
}

fun DrawScope.drawCordillera(t: Float, bands: FloatArray, pal: VisualizerPalette, st: RidgeState) {
    if (st.lastPush < 0f || t - st.lastPush >= RIDGE_STEP_SEC || t < st.lastPush) {
        st.lastPush = t
        st.rows.addFirst(bands.copyOf())
        while (st.rows.size > RIDGE_ROWS) st.rows.removeLast()
    }

    val w = size.width
    val h = size.height
    drawRect(Color.Black)

    val pad = w * 0.11f
    val base = h * 0.66f
    val gap = h * 0.0135f
    val amp = h * 0.19f
    val usable = w - pad * 2f

    // De atrás hacia delante: cada cresta se rellena de negro y tapa las de detrás.
    for (r in st.rows.indices.reversed()) {
        val row = st.rows[r]
        val y0 = base - r * gap
        val path = st.path
        path.reset()
        path.moveTo(pad, y0)
        for (i in 0..RIDGE_POINTS) {
            val f = i / RIDGE_POINTS.toFloat()
            // Pesado gaussiano: el centro se levanta, los bordes quedan planos.
            val c = exp(-((f - 0.5f) * 2.6f).pow(2))
            val idx = (abs(f - 0.5f) * 2f * (BANDS - 1)).toInt().coerceIn(0, BANDS - 1)
            // Un poco de «ruido» fijo por fila para que no sean curvas lisas.
            val v = row[idx] + 0.12f * abs(sin(f * 40f + r))
            path.lineTo(pad + f * usable, y0 - v * c * amp)
        }
        path.lineTo(w - pad, y0)
        path.close()
        drawPath(path, Color.Black)
        val front = r == 0
        drawPath(
            path,
            color = if (front) pal.accent else pal.light.copy(alpha = (1f - r / 40f).coerceIn(0f, 1f)),
            style = Stroke(
                width = (if (front) 2f else 1.2f) * density,
                join = StrokeJoin.Round,
            ),
        )
    }
}

// ───────────────────────────── TÚNEL ─────────────────────────────

private const val TUNNEL_RINGS = 22
private const val TUNNEL_SEGMENTS = 48

class TunnelState {
    var z = 0f
    var lastT = -1f
    val path = Path()
}

fun DrawScope.drawTunel(t: Float, bands: FloatArray, pal: VisualizerPalette, st: TunnelState) {
    // El avance se escala por el tiempo real para ir igual a 60 o a 30 fps.
    val frames = if (st.lastT < 0f || t < st.lastT) 1f else ((t - st.lastT) * 60f).coerceIn(0f, 3f)
    st.lastT = t
    val bass = (bands[0] + bands[1] + bands[2] + bands[3]) / 4f
    st.z = (st.z + (0.012f + bass * 0.01f) * frames) % 1f

    val w = size.width
    val h = size.height
    drawRect(Color(0xFF0A0410))

    val unit = size.minDimension / 360f
    val cx = w / 2f + sin(t * 0.7f) * 20f * unit
    val cy = h * 0.4f + cos(t * 0.5f) * 16f * unit
    val maxR = 420f * unit
    val colors = arrayOf(pal.light, pal.accent, pal.deep)

    for (k in 0 until TUNNEL_RINGS) {
        val z = ((k / TUNNEL_RINGS.toFloat()) + st.z) % 1f
        val s = z.pow(2.2f)
        val r = 10f * unit + s * maxR
        val path = st.path
        path.reset()
        for (j in 0..TUNNEL_SEGMENTS) {
            val a = j / TUNNEL_SEGMENTS.toFloat() * TWO_PI_F
            val idx = (j / TUNNEL_SEGMENTS.toFloat() * (BANDS / 2)).toInt().coerceIn(0, BANDS - 1)
            val v = bands[idx] * s * 30f * unit
            val rr = r + v * sin(a * 6f + t * 2f)
            val x = cx + cos(a) * rr
            val y = cy + sin(a) * rr
            if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            color = colors[k % 3].copy(alpha = (s * 1.6f).coerceIn(0f, 1f)),
            style = Stroke(width = (1f + s * 3f) * density),
        )
    }
}

// ───────────────────────────── ESPEJO ─────────────────────────────

private const val MIRROR_BARS = 40

class MirrorState {
    val peaks = FloatArray(MIRROR_BARS)
}

fun DrawScope.drawEspejo(bands: FloatArray, pal: VisualizerPalette, st: MirrorState) {
    val w = size.width
    val h = size.height
    val bg = Color(0xFF07070B)
    drawRect(bg)

    val cy = h * 0.42f
    val side = w * 0.04f
    val bw = (w - side * 2f) / MIRROR_BARS
    val maxH = h * 0.24f
    val gapPx = 3f * density
    val barW = (bw - gapPx).coerceAtLeast(1f)
    val capH = 2f * density
    val grad = Brush.verticalGradient(listOf(pal.accent, pal.deep), startY = cy - maxH, endY = cy)
    val reflection = pal.deep.copy(alpha = 0.25f)

    for (i in 0 until MIRROR_BARS) {
        val idx = (i / MIRROR_BARS.toFloat() * BANDS * 0.8f).toInt().coerceIn(0, BANDS - 1)
        val v = bands[idx].coerceIn(0f, 1f)
        // El pico sube de golpe y cae despacio.
        st.peaks[i] = if (v > st.peaks[i]) v else (st.peaks[i] - 0.006f).coerceAtLeast(v)
        val bh = v * maxH
        val px = side + i * bw + gapPx / 2f
        if (bh > 0.5f) {
            drawRect(grad, topLeft = Offset(px, cy - bh), size = Size(barW, bh))
            drawRect(reflection, topLeft = Offset(px, cy + 4f * density), size = Size(barW, bh * 0.55f))
        }
        val p = st.peaks[i] * maxH
        drawRect(Color.White, topLeft = Offset(px, cy - p - 5f * density), size = Size(barW, capH))
    }

    // Fundido del reflejo hacia el fondo.
    val fadeH = h * 0.16f
    drawRect(
        Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg), startY = cy + 2f, endY = cy + 2f + fadeH),
        topLeft = Offset(0f, cy + 2f),
        size = Size(w, fadeH),
    )
}
