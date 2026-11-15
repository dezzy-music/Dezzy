/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/** Una barra del gráfico de Estadísticas: etiqueta corta (día, semana o mes) y tiempo escuchado. */
@Immutable
data class StatsBucket(
    val label: String,
    val playTimeMs: Long,
)

/** Tiempo total del periodo y su reparto en barras. */
@Immutable
data class StatsListening(
    val totalMs: Long = 0L,
    val buckets: List<StatsBucket> = emptyList(),
)

/**
 * Tarjeta héroe de Estadísticas (fase 6, boceto A): tiempo escuchado a 48 sp,
 * resumen del periodo y barras por día / semana / mes.
 */
@Composable
fun StatsListeningCard(
    periodLabel: String,
    listening: StatsListening,
    summary: String,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val totalMinutes = listening.totalMs / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val bigText = buildAnnotatedString {
        if (hours > 0) {
            append("$hours")
            withStyle(SpanStyle(fontSize = 22.sp)) { append(" h ") }
        }
        append("$minutes")
        withStyle(SpanStyle(fontSize = 22.sp)) { append(" min") }
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colorScheme.primaryContainer,
        contentColor = colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Tiempo escuchado, $periodLabel",
                style = MaterialTheme.typography.labelLarge,
                fontSize = 13.sp,
                color = colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = bigText,
                fontSize = 48.sp,
                lineHeight = 54.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.semantics {
                    contentDescription = if (hours > 0) "$hours horas y $minutes minutos" else "$minutes minutos"
                },
            )
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                    color = colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
            }
            if (listening.buckets.size >= 2) {
                StatsBars(
                    buckets = listening.buckets,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun StatsBars(
    buckets: List<StatsBucket>,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val max = buckets.maxOf { it.playTimeMs }.coerceAtLeast(1L)
    val peakIndex = buckets.indexOfFirst { it.playTimeMs == max }
    val description = buckets.joinToString(", ") { "${it.label}: ${it.playTimeMs / 60_000L} min" }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .clearAndSetSemantics { contentDescription = "Tiempo escuchado por periodo. $description" },
        horizontalArrangement = Arrangement.spacedBy(if (buckets.size > 12) 3.dp else 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        buckets.forEachIndexed { index, bucket ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                val fraction = (bucket.playTimeMs.toFloat() / max).coerceIn(0.04f, 1f)
                // Hueco por encima de la barra: con los dos pesos la altura es proporcional.
                if (fraction < 1f) {
                    Spacer(modifier = Modifier.weight(1f - fraction))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(fraction, fill = true)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                        .background(
                            if (index == peakIndex) colorScheme.primary else colorScheme.primary.copy(alpha = 0.35f),
                        ),
                )
                Text(
                    text = bucket.label,
                    fontSize = 11.sp,
                    maxLines = 1,
                    color = colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Canción y artista favoritos lado a lado. */
@Composable
fun StatsFavoritesRow(
    songTitle: String?,
    songSubtitle: String?,
    songThumbnail: String?,
    onSongClick: () -> Unit,
    artistName: String?,
    artistSubtitle: String?,
    artistThumbnail: String?,
    onArtistClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (songTitle == null && artistName == null) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (songTitle != null) {
            StatsFavoriteCard(
                overline = "Canción favorita",
                title = songTitle,
                subtitle = songSubtitle.orEmpty(),
                thumbnailUrl = songThumbnail,
                round = false,
                onClick = onSongClick,
                modifier = Modifier.weight(1f),
            )
        }
        if (artistName != null) {
            StatsFavoriteCard(
                overline = "Artista favorito",
                title = artistName,
                subtitle = artistSubtitle.orEmpty(),
                thumbnailUrl = artistThumbnail,
                round = true,
                onClick = onArtistClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatsFavoriteCard(
    overline: String,
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    round: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .then(if (round) Modifier.widthIn(max = 112.dp) else Modifier)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(if (round) CircleShape else RoundedCornerShape(12.dp))
                        .background(colorScheme.surfaceContainerHighest),
                )
            }
            Column {
                Text(
                    text = overline.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Número de posición del ranking (columna fija a la izquierda de la miniatura). */
@Composable
fun StatsRankNumber(rank: Int, modifier: Modifier = Modifier) {
    Text(
        text = "$rank",
        style = MaterialTheme.typography.titleMedium,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = modifier.width(28.dp),
    )
}
