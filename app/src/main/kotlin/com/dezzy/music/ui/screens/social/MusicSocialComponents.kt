/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.social

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dezzy.music.R

/**
 * Piezas visuales compartidas por las pantallas sociales musicales.
 *
 * Se apoyan en el `MaterialTheme` de la app (colores dinámicos incluidos) en vez
 * de fijar colores propios, para que la red social se sienta parte de Dezzy y no
 * un injerto. El único color literal es el verde de "sonando", que es un
 * indicador de estado y debe leerse igual en tema claro y oscuro.
 */

/** Verde de "está sonando ahora". */
val NowPlayingGreen = Color(0xFF1DD05D)

/**
 * Avatar circular con anillo verde animado cuando la persona está escuchando.
 * El anillo late despacio: comunica "en vivo" sin robar atención.
 */
@Composable
fun ListeningAvatar(
    avatarUrl: String?,
    isPlaying: Boolean,
    size: androidx.compose.ui.unit.Dp = 52.dp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val ringAlpha by transition.animateFloat(
        initialValue = if (isPlaying) 0.45f else 0f,
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "ringAlpha",
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (isPlaying) {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(NowPlayingGreen.copy(alpha = ringAlpha * 0.35f))
            )
        }
        AsyncImage(
            model = avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size - if (isPlaying) 6.dp else 0.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        if (avatarUrl.isNullOrBlank()) {
            Icon(
                painter = painterResource(R.drawable.social_profile),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.45f),
            )
        }
    }
}

/**
 * Carátula con las esquinas redondeadas del resto de la app.
 * Cae a un icono de nota cuando no hay imagen, en vez de dejar un hueco gris.
 */
@Composable
fun ArtworkThumb(
    url: String?,
    size: androidx.compose.ui.unit.Dp,
    corner: androidx.compose.ui.unit.Dp = 8.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Icon(
                painter = painterResource(R.drawable.music_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.4f),
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Etiqueta "Sonando" con punto verde. */
@Composable
fun NowPlayingBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(NowPlayingGreen)
        )
        Text(
            text = "Sonando",
            color = NowPlayingGreen,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Fila de un amigo con lo que escucha. Tocarla reproduce esa canción.
 */
@Composable
fun FriendActivityRow(
    name: String,
    title: String?,
    artist: String?,
    avatarUrl: String?,
    artworkUrl: String?,
    isPlaying: Boolean,
    subtitle: String? = null,
    onClick: () -> Unit,
    onAvatarClick: () -> Unit = onClick,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ListeningAvatar(
            avatarUrl = avatarUrl,
            isPlaying = isPlaying,
            modifier = Modifier.clickable(onClick = onAvatarClick),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = title.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isPlaying) {
                Spacer(Modifier.height(3.dp))
                NowPlayingBadge()
            } else if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        ArtworkThumb(url = artworkUrl, size = 52.dp)
    }
}

/** Estado vacío con icono, título y explicación — mismo patrón en todas las pantallas. */
@Composable
fun SocialEmptyState(
    iconRes: Int,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** Formatea un timestamp ISO como "hace 5 min". */
fun relativeTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val then = runCatching { java.time.Instant.parse(iso) }.getOrNull()
        ?: runCatching {
            java.time.LocalDateTime.parse(iso.substringBefore('+').trim())
                .toInstant(java.time.ZoneOffset.UTC)
        }.getOrNull()
        ?: return ""
    val seconds = java.time.Duration.between(then, java.time.Instant.now()).seconds
    return when {
        seconds < 60 -> "ahora"
        seconds < 3600 -> "hace ${seconds / 60} min"
        seconds < 86_400 -> "hace ${seconds / 3600} h"
        seconds < 604_800 -> "hace ${seconds / 86_400} d"
        else -> "hace ${seconds / 604_800} sem"
    }
}
