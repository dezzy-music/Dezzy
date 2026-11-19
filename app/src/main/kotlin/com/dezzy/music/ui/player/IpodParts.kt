/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dezzy.music.R
import kotlin.math.atan2
import kotlin.math.hypot

/** Qué enseña la pantalla del iPod. */
internal enum class IpodLcdMode { NOW_PLAYING, MENU }

/** Entradas del menú de la LCD (boceto B + «Dispositivos» y «Más opciones…»). */
internal enum class IpodMenuItem(val label: String) {
    LYRICS("Letras"),
    QUEUE("Cola"),
    DEVICES("Dispositivos"),
    VIDEO("Video"),
    VISUALIZER("Visualizador"),
    SHARE("Compartir"),
    MORE("Más opciones…"),
    NOW_PLAYING("Reproduciendo"),
}

/** Lista del menú con la fila elegida resaltada en azul, como el iPod original. */
@Composable
internal fun IpodLcdMenu(
    items: List<IpodMenuItem>,
    selected: Int,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = 4.dp)) {
        items.forEachIndexed { index, item ->
            val isSelected = index == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isSelected) {
                            Modifier.background(
                                Brush.verticalGradient(listOf(Color(0xFF3A5A78), Color(0xFF1F3A55)))
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onClick(index) }
                    .padding(horizontal = 12.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = item.label,
                    color = if (isSelected) Color(0xFFE9F2F0) else Color(0xFF1B2631),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                )
                Icon(
                    painter = painterResource(R.drawable.navigate_next),
                    contentDescription = null,
                    tint = if (isSelected) Color(0xFFE9F2F0) else Color(0xFF1B2631),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Giro de la rueda: cada [stepDegrees] de arrastre circular llama a [onStep] con +1
 * (horario) o −1 (antihorario). Los toques siguen llegando a los botones de la rueda.
 */
internal fun Modifier.ipodWheelRotation(
    innerRadiusPx: Float,
    stepDegrees: Float = 24f,
    onStep: (Int) -> Unit,
): Modifier = this.then(
    Modifier.pointerInput(innerRadiusPx) {
        var lastAngle = 0f
        var accumulated = 0f
        var active = false
        fun angleOf(p: Offset): Float {
            val c = Offset(size.width / 2f, size.height / 2f)
            return Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat()
        }
        detectDragGestures(
            onDragStart = { start ->
                val c = Offset(size.width / 2f, size.height / 2f)
                active = hypot(start.x - c.x, start.y - c.y) > innerRadiusPx
                lastAngle = angleOf(start)
                accumulated = 0f
            },
            onDragEnd = { active = false },
            onDragCancel = { active = false },
        ) { change, _ ->
            if (!active) return@detectDragGestures
            change.consume()
            val a = angleOf(change.position)
            var d = a - lastAngle
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            lastAngle = a
            accumulated += d
            while (accumulated >= stepDegrees) { accumulated -= stepDegrees; onStep(1) }
            while (accumulated <= -stepDegrees) { accumulated += stepDegrees; onStep(-1) }
        }
    }
)

/** Variante con el callback siempre actualizado (para usar desde composables). */
@Composable
internal fun Modifier.ipodWheelRotationUpdated(innerRadiusPx: Float, onStep: (Int) -> Unit): Modifier {
    val latest by rememberUpdatedState(onStep)
    return ipodWheelRotation(innerRadiusPx) { latest(it) }
}
