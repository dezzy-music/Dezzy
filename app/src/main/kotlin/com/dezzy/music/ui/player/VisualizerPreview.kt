/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Vistas previas animadas de los visualizadores (Ajustes › Apariencia, boceto B «Carrusel»,
 * design/dezzy-visualizador-ajustes-bocetos.html). Usan un espectro de prueba con bombo a
 * ~120 bpm: no hace falta el permiso de micrófono ni que suene nada.
 */

package com.dezzy.music.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dezzy.music.R
import com.dezzy.music.utils.AudioSpectrumEngine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Espectro sintético (el mismo de los bocetos): graves que laten y agudos que bailan. */
class FakeSpectrum {
    val bands = FloatArray(AudioSpectrumEngine.BANDS)
    var loudness = 0f
        private set

    fun step(t: Float) {
        val beat = max(0f, sin(t * PI.toFloat() * 2f / 0.5f)).pow(12)
        loudness = loudness * 0.85f + beat * 0.15f
        val n = bands.size
        for (i in 0 until n) {
            val f = i / n.toFloat()
            val base = (1 - f) * 0.7f * (0.55f + 0.45f * beat) +
                0.25f * abs(sin(t * 1.7f + i * 0.35f)) * (1 - f * 0.5f) +
                0.18f * abs(sin(t * 5.3f + i * 1.3f)) * f
            bands[i] = bands[i] * 0.6f + min(1f, base) * 0.4f
        }
    }
}

/** Un visualizador en pequeño, animándose solo mientras está en pantalla. */
@Composable
fun VisualizerPreview(
    style: VisualizerStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val spectrum = remember { FakeSpectrum() }
    val palette = remember(color) { visualizerPaletteOf(color) }
    val esfera = remember { EsferaScene() }
    val ridge = remember(style) { RidgeState() }
    val tunnel = remember(style) { TunnelState() }
    val mirror = remember(style) { MirrorState() }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now ->
            time = (now - start) / 1_000_000_000f
            spectrum.step(time)
        }
    }
    Canvas(modifier.background(Color(0xFF07080B))) {
        val t = time // ata el redibujado al bucle de fotogramas
        when (style) {
            VisualizerStyle.ESFERA -> drawEsfera(t, spectrum.bands, spectrum.loudness, color, esfera)
            VisualizerStyle.CORDILLERA -> drawCordillera(t, spectrum.bands, palette, ridge)
            VisualizerStyle.TUNEL -> drawTunel(t, spectrum.bands, palette, tunnel)
            VisualizerStyle.ESPEJO -> if (t >= 0f) drawEspejo(spectrum.bands, palette, mirror)
        }
        Unit
    }
}

/**
 * Fila deslizable con los estilos como tarjetas verticales (forma de la pantalla real).
 * Tocar una la elige al momento.
 */
@Composable
fun VisualizerCarousel(
    selected: VisualizerStyle,
    onSelect: (VisualizerStyle) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (selected.ordinal - 1).coerceAtLeast(0),
    )
    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(VisualizerStyle.entries, key = { it.name }) { style ->
            val on = style == selected
            val shape = RoundedCornerShape(22.dp)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(124.dp)
                    .clip(shape)
                    .clickable(role = Role.RadioButton) { onSelect(style) }
                    .semantics {
                        this.selected = on
                        contentDescription = "Visualizador ${style.label}"
                    },
            ) {
                VisualizerPreview(
                    style = style,
                    color = color,
                    modifier = Modifier
                        .width(124.dp)
                        .height(216.dp)
                        .clip(shape)
                        .border(
                            width = 3.dp,
                            color = if (on) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = shape,
                        ),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    if (on) {
                        Icon(
                            painter = painterResource(R.drawable.check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = style.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
