/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.p2p

import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
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
/** Modelo para Coil de una foto P2P: URL `https://` o miniatura `data:` en base64. */
internal fun p2pImageModel(picture: String?): Any? = avatarModel(picture)

private fun avatarModel(picture: String?): Any? {
    if (picture.isNullOrBlank()) return null
    if (!picture.startsWith("data:")) return picture.takeIf { it.startsWith("https://") }
    return runCatching { Base64.getDecoder().decode(picture.substringAfter("base64,", "")) }.getOrNull()
}

// ── Portada del perfil ───────────────────────────────────────────────────────

/**
 * Degradados de portada. Son arte de marca (estilo TikTok: cian y rosa eléctrico), no colores
 * de interfaz, por eso no salen del tema. El primero es la portada por defecto.
 */
enum class P2PBannerPreset(val id: String, val label: String) {
    Neon("tiktok", "Cian y rosa sobre negro"),
    Electric("electric", "Cian, violeta y rosa"),
    Dusk("dusk", "Rosa, vino y cian"),
    Wine("wine", "Vino y dorado"),
    ;

    val value: String get() = P2PSocial.BANNER_PRESET_PREFIX + id

    companion object {
        /** Preset de una portada guardada; el de por defecto si no hay o es una foto. */
        fun of(banner: String?): P2PBannerPreset =
            banner?.takeIf { it.startsWith(P2PSocial.BANNER_PRESET_PREFIX) }
                ?.removePrefix(P2PSocial.BANNER_PRESET_PREFIX)
                ?.let { id -> entries.firstOrNull { it.id == id } }
                ?: Neon
    }
}

private val BannerCyan = Color(0xFF25F4EE)
private val BannerPink = Color(0xFFFE2C55)

fun DrawScope.drawP2PBannerPreset(preset: P2PBannerPreset) {
    val w = size.width
    val h = size.height
    when (preset) {
        P2PBannerPreset.Neon -> {
            drawRect(
                Brush.linearGradient(
                    0f to Color(0xFF0A1A1F), 0.5f to Color(0xFF050505), 1f to Color(0xFF1F0610),
                    start = Offset.Zero, end = Offset(w, h),
                ),
            )
            val r = maxOf(w, h) * 0.75f
            drawRect(Brush.radialGradient(0f to BannerCyan.copy(alpha = 0.95f), 0.55f to BannerCyan.copy(alpha = 0f), center = Offset.Zero, radius = r))
            drawRect(Brush.radialGradient(0f to BannerPink.copy(alpha = 0.95f), 0.55f to BannerPink.copy(alpha = 0f), center = Offset(w, h), radius = r))
        }

        P2PBannerPreset.Electric -> drawRect(
            Brush.linearGradient(
                0f to BannerCyan, 0.48f to Color(0xFF6A5CFF), 1f to BannerPink,
                start = Offset.Zero, end = Offset(w, h * 0.6f),
            ),
        )

        P2PBannerPreset.Dusk -> drawRect(
            Brush.linearGradient(
                0f to BannerPink, 0.35f to Color(0xFF8E1C3A), 0.7f to Color(0xFF062C33), 1f to BannerCyan,
                start = Offset(w * 0.3f, 0f), end = Offset(w * 0.7f, h),
            ),
        )

        P2PBannerPreset.Wine -> drawRect(
            Brush.linearGradient(listOf(Color(0xFF7D2833), Color(0xFFE8C08E)), start = Offset.Zero, end = Offset(w, h)),
        )
    }
}

/**
 * Portada: la foto subida (recortada), el video en bucle o el degradado elegido. Es decorativa
 * para TalkBack. Con [fade] se desvanece por abajo y se funde con cualquier fondo (tema claro,
 * oscuro o cristal), igual que en el escritorio.
 */
@Composable
fun P2PProfileBanner(
    banner: String?,
    modifier: Modifier = Modifier,
    video: String? = null,
    fade: Boolean = false,
) {
    val preset = remember(banner) { P2PBannerPreset.of(banner) }
    val url = banner?.takeIf { it.startsWith("https://") }
    val context = LocalContext.current
    // Sin animaciones del sistema o con ahorro de batería se queda el fotograma fijo.
    val playVideo = remember(video) {
        video?.startsWith("https://") == true &&
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f &&
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode != true
    }
    Box(
        modifier
            .clipToBounds()
            .then(
                if (fade) {
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(0.34f to Color.Black, 1f to Color.Transparent),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                } else {
                    Modifier
                },
            )
            .drawBehind { drawP2PBannerPreset(preset) },
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        if (playVideo && video != null) {
            BannerVideoLoop(video, Modifier.matchParentSize())
        }
    }
}

/**
 * Video de portada silenciado y en bucle. Va en un TextureView (no SurfaceView) para que el
 * desvanecido y las esquinas redondeadas también le afecten. No pide el foco de audio, así que
 * no corta la música; se pausa cuando la app pasa a segundo plano.
 */
@Composable
private fun BannerVideoLoop(url: String, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ALL
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> player.play()
                Lifecycle.Event.ON_PAUSE -> player.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) player.play()
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { view ->
                player.setVideoTextureView(view)
                player.addListener(
                    object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) = cropTexture(view, videoSize)
                    },
                )
                view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> cropTexture(view, player.videoSize) }
            }
        },
        modifier = modifier,
    )
}

/** Escala el video para cubrir la portada sin deformarse (como ContentScale.Crop). */
private fun cropTexture(view: TextureView, size: VideoSize) {
    val vw = view.width.toFloat()
    val vh = view.height.toFloat()
    if (vw <= 0f || vh <= 0f || size.width == 0 || size.height == 0) return
    val videoW = size.width * size.pixelWidthHeightRatio
    val videoH = size.height.toFloat()
    val scale = maxOf(vw / videoW, vh / videoH)
    view.setTransform(Matrix().apply { setScale(videoW * scale / vw, videoH * scale / vh, vw / 2f, vh / 2f) })
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
