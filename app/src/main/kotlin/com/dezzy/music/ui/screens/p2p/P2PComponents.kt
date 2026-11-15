/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.p2p

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dezzy.music.R
import com.dezzy.music.models.MediaMetadata
import com.dezzy.music.p2p.P2PSocial
import com.dezzy.music.p2p.P2PSong
import com.dezzy.music.p2p.P2PUser
import com.dezzy.music.p2p.nostr.RelayPool
import com.dezzy.music.playback.PlayerConnection
import com.dezzy.music.playback.queues.YouTubeQueue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/** Perfil observado: se repinta cuando llega su nombre/foto desde los relés. */
@Composable
fun rememberP2PUser(pubkey: String): P2PUser {
    val profiles by P2PSocial.profiles.collectAsState()
    return profiles[pubkey] ?: remember(pubkey) { P2PSocial.user(pubkey) }
}

/** Convierte `data:image/...;base64,` en bytes para Coil; deja pasar URLs normales. */
private fun avatarModel(picture: String?): Any? {
    if (picture.isNullOrBlank()) return null
    if (!picture.startsWith("data:")) return picture.takeIf { it.startsWith("https://") }
    return runCatching { Base64.getDecoder().decode(picture.substringAfter("base64,", "")) }.getOrNull()
}

/** Avatar con foto o iniciales sobre un color estable derivado de la clave. */
@Composable
fun P2PAvatar(
    user: P2PUser,
    size: Dp = 44.dp,
    ring: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.secondaryContainer to scheme.onSecondaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer,
        scheme.surfaceContainerHighest to scheme.onSurface,
    )
    val (bg, fg) = palette[(user.pubkey.take(2).toIntOrNull(16) ?: 0) % palette.size]
    val model = remember(user.picture) { avatarModel(user.picture) }
    val ringModifier = if (ring) {
        Modifier
            .border(2.dp, scheme.primary, CircleShape)
            .padding(3.dp)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .size(size)
            .then(ringModifier)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            user.initials,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.36f).coerceAtLeast(10f).sp,
            maxLines = 1,
        )
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
            )
        }
    }
}

/** Chip «Cifrado · N relés conectados». */
@Composable
fun EncryptedChip(modifier: Modifier = Modifier) {
    val connected by RelayPool.connectedCount.collectAsState()
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(scheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = "Cifrado, $connected relés conectados" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(R.drawable.lock), null, Modifier.size(14.dp), tint = scheme.onSecondaryContainer)
        Text(
            if (connected > 0) "Cifrado · $connected relés conectados" else "Cifrado · conectando…",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSecondaryContainer,
        )
    }
}

/** Tarjeta de canción compartida (en chats y publicaciones). */
@Composable
fun P2PSongCard(
    song: P2PSong,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onPlay,
        shape = RoundedCornerShape(16.dp),
        color = container,
        modifier = modifier.heightIn(min = 64.dp),
    ) {
        Row(
            Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.music_note), null, tint = scheme.onSurfaceVariant)
                if (song.thumbnail != null) {
                    AsyncImage(
                        model = song.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(song.artist.takeIf { it.isNotBlank() }, formatDuration(song.durationSec)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalIconButton(onClick = onPlay) {
                Icon(painterResource(R.drawable.play), contentDescription = "Reproducir ${song.title}")
            }
        }
    }
}

private fun formatDuration(seconds: Int): String? =
    if (seconds <= 0) null else "%d:%02d".format(seconds / 60, seconds % 60)

/** Encabezado de sección accesible. */
@Composable
fun P2PSectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        trailing?.invoke()
    }
}

/** Estado vacío con icono, título y explicación. */
@Composable
fun P2PEmptyState(icon: Int, title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier.padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        action?.invoke()
    }
}

// ───────────────────────────── Utilidades ─────────────────────────────

private val esLocale = Locale.forLanguageTag("es")
private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", esLocale)
private val dateFmt = DateTimeFormatter.ofPattern("d/MMM/yyyy", esLocale)
private val weekdayFmt = DateTimeFormatter.ofPattern("EEE", esLocale)

/** «hace 5 min», «hace 3 h», «ayer», «12/nov/2026». */
fun timeAgo(epochSeconds: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = (now - epochSeconds).coerceAtLeast(0)
    return when {
        diff < 60 -> "ahora"
        diff < 3600 -> "hace ${diff / 60} min"
        diff < 86_400 -> "hace ${diff / 3600} h"
        diff < 2 * 86_400 -> "ayer"
        diff < 7 * 86_400 -> "hace ${diff / 86_400} días"
        else -> Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(dateFmt)
    }
}

/** Hora para la lista de chats: hoy «9:38 a.m.», esta semana «lun», si no la fecha. */
fun chatTime(epochSeconds: Long): String {
    val zone = ZoneId.systemDefault()
    val dt = Instant.ofEpochSecond(epochSeconds).atZone(zone)
    val today = LocalDate.now(zone)
    return when {
        dt.toLocalDate() == today -> dt.format(timeFmt)
        dt.toLocalDate() == today.minusDays(1) -> "ayer"
        dt.toLocalDate().isAfter(today.minusDays(7)) -> dt.format(weekdayFmt)
        else -> dt.format(dateFmt)
    }
}

fun messageTime(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(timeFmt)

fun MediaMetadata.toP2PSong(): P2PSong = P2PSong(
    videoId = id,
    title = title,
    artist = artists.joinToString(", ") { it.name },
    thumbnail = thumbnailUrl,
    durationSec = duration.coerceAtLeast(0),
)

/** Reproduce una canción compartida y arranca su radio. */
fun playP2PSong(playerConnection: PlayerConnection?, song: P2PSong) {
    val connection = playerConnection ?: return
    val metadata = MediaMetadata(
        id = song.videoId,
        title = song.title,
        artists = song.artist.takeIf { it.isNotBlank() }?.let { listOf(MediaMetadata.Artist(id = null, name = it)) }.orEmpty(),
        duration = if (song.durationSec > 0) song.durationSec else -1,
        thumbnailUrl = song.thumbnail,
    )
    connection.playQueue(YouTubeQueue.radio(metadata))
}

/** Abre un enlace propio de Dezzy (sala Juntos, perfil…) dentro de la app. */
fun openDezzyLink(context: Context, link: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(link))
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Comparte un texto con el selector del sistema. */
fun shareText(context: Context, text: String, title: String = "Compartir") {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Botón ancho (fila clicable) reutilizado en ajustes del perfil. */
@Composable
fun P2PSettingRow(icon: Int, title: String, subtitle: String? = null, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}
