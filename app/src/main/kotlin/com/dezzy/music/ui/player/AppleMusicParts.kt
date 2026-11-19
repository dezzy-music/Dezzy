/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R

/** Asa de cierre arriba del reproductor Apple Music: tocarla pliega el reproductor. */
@Composable
internal fun AppleGrabHandle(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Cerrar reproductor" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 40.dp, height = 5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.45f)),
        )
    }
}

/** Chip de calidad («Lossless», «AAC 256»…) entre los tiempos. */
@Composable
internal fun AppleQualityChip(label: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.graphic_eq),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
        )
    }
}

/** Volumen en línea: silencio · barra · máximo. La barra engorda mientras se arrastra. */
@Composable
internal fun AppleInlineVolume(modifier: Modifier = Modifier) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val volume by playerConnection.service.playerVolume.collectAsState()
    var dragging by remember { mutableStateOf(false) }
    val barHeight by animateDpAsState(if (dragging) 10.dp else 6.dp, label = "volumeBar")

    fun set(v: Float) {
        playerConnection.service.playerVolume.value = v.coerceIn(0f, 1f)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.volume_off),
            contentDescription = "Silenciar",
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier
                .size(18.dp)
                .clickable { set(0f) },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .semantics { contentDescription = "Volumen ${(volume * 100).toInt()} %" }
                .pointerInput(Unit) {
                    detectTapGestures { set(it.x / size.width) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true; set(it.x / size.width) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ -> set(change.position.x / size.width) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .clip(RoundedCornerShape(barHeight / 2))
                    .background(Color.White.copy(alpha = 0.25f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(volume.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.8f)),
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.volume_up),
            contentDescription = "Volumen al máximo",
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier
                .size(18.dp)
                .clickable { set(1f) },
        )
    }
}

/**
 * Barra de progreso de Apple Music: 6 dp en reposo y 10 dp mientras se toca o se arrastra.
 * Tocar salta a ese punto; arrastrar sólo confirma el salto al soltar.
 */
@Composable
internal fun AppleSeekBar(
    value: Float,
    max: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var touching by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(0f) }
    fun scrub(x: Float, width: Int) {
        last = (x / width).coerceIn(0f, 1f) * max
        onValueChange(last)
    }
    val barHeight by animateDpAsState(if (touching) 10.dp else 6.dp, label = "seekBar")
    val fraction = if (max <= 0f) 0f else (value / max).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .semantics { contentDescription = "Progreso de la canción ${(fraction * 100).toInt()} %" }
            .pointerInput(max) {
                detectTapGestures(
                    onPress = {
                        touching = true
                        tryAwaitRelease()
                        touching = false
                    },
                    onTap = {
                        scrub(it.x, size.width)
                        onValueChangeFinished(last)
                    },
                )
            }
            .pointerInput(max) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        touching = true
                        scrub(it.x, size.width)
                    },
                    onDragEnd = { touching = false; onValueChangeFinished(last) },
                    onDragCancel = { touching = false; onValueChangeFinished(last) },
                ) { change, _ -> scrub(change.position.x, size.width) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(barHeight / 2))
                .background(Color.White.copy(alpha = 0.25f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = if (touching) 1f else 0.8f)),
            )
        }
    }
}
