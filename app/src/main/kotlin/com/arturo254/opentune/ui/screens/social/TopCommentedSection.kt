/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.ui.screens.social

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arturo254.opentune.LocalPlayerConnection
import com.arturo254.opentune.R
import com.arturo254.opentune.innertube.YouTube
import com.arturo254.opentune.innertube.models.WatchEndpoint
import com.arturo254.opentune.models.toMediaMetadata
import com.arturo254.opentune.playback.queues.YouTubeQueue
import com.arturo254.opentune.social.DezzySocial
import com.arturo254.opentune.social.SupabaseAuth
import com.arturo254.opentune.social.TrackComment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Pista con datos de comentarios REALES de YouTube (conteo total + comentario
 * destacado), como la estantería "Pistas más comentadas" de YouTube Music.
 */
data class RealCommentedTrack(
    val videoId: String,
    val title: String,
    val artist: String?,
    val thumbnail: String?,
    val commentCount: Long?,
    val topComment: String?,
    val topAuthor: String?,
    val topAvatar: String?,
)

/**
 * Fuente de datos de "pistas más comentadas" con datos REALES de InnerTube: toma
 * canciones en tendencia de YouTube Music (charts) y las enriquece con el conteo
 * de comentarios y el comentario destacado REALES de YouTube, ordenando por
 * número de comentarios — como hace la estantería de YouTube Music.
 *
 * Se cachea en memoria durante la sesión (TTL) porque enriquecer cada pista
 * cuesta varias llamadas de red: no queremos repetirlas en cada scroll ni cada
 * vez que se entra a la Home.
 */
object RealTopCommented {
    private const val TTL_MS = 15 * 60 * 1000L
    /** Candidatos a enriquecer. La playlist de tendencias trae 20; muchos
     *  tendrán los comentarios apagados, así que conviene pedir todos. */
    private const val CANDIDATES = 20
    private var cache: List<RealCommentedTrack> = emptyList()
    private var cachedAt = 0L
    private val mutex = kotlinx.coroutines.sync.Mutex()

    suspend fun load(limit: Int): List<RealCommentedTrack> = mutex.withLock {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cachedAt < TTL_MS) return cache.take(limit)
        val result = fetch(limit)
        if (result.isNotEmpty()) {
            cache = result
            cachedAt = now
        }
        result.take(limit)
    }

    private suspend fun fetch(limit: Int): List<RealCommentedTrack> =
        withContext(Dispatchers.IO) {
            // 1. Intentar cargar primero las pistas más comentadas en la comunidad DezzySocial
            val dezzyTracks = runCatching {
                DezzySocial.topCommented(limit)
            }.getOrNull().orEmpty()

            val dezzyRealTracks = mutableListOf<RealCommentedTrack>()
            for (dt in dezzyTracks) {
                val playerResp = runCatching { YouTube.player(dt.videoId, client = com.arturo254.opentune.innertube.models.YouTubeClient.WEB).getOrNull() }.getOrNull()
                val details = playerResp?.videoDetails
                if (details != null) {
                    dezzyRealTracks.add(
                        RealCommentedTrack(
                            videoId = dt.videoId,
                            title = details.title.orEmpty(),
                            artist = details.author,
                            thumbnail = details.thumbnail?.thumbnails?.lastOrNull()?.url,
                            commentCount = dt.commentCount,
                            topComment = dt.sampleContent,
                            topAuthor = dt.sampleAuthor,
                            topAvatar = dt.sampleAvatar,
                        )
                    )
                }
            }

            // 2. Cargar tendencias de YouTube y enriquecerlas con el resumen de comentarios
            val songs = runCatching {
                YouTube.trendingTracks(CANDIDATES).getOrNull()?.distinctBy { it.id }
            }.getOrNull().orEmpty()

            val gate = Semaphore(4)
            val enriched = coroutineScope {
                songs.map { song ->
                    async {
                        val summary = gate.withPermit {
                            runCatching {
                                YouTube.getVideoCommentSummary(song.id).getOrNull()
                            }.getOrNull()
                        }
                        RealCommentedTrack(
                            videoId = song.id,
                            title = song.title,
                            artist = song.artists.joinToString(", ") { it.name }
                                .takeIf { it.isNotBlank() },
                            thumbnail = song.thumbnail,
                            commentCount = summary?.commentCount,
                            topComment = summary?.topCommentText,
                            topAuthor = summary?.topCommentAuthor,
                            topAvatar = summary?.topCommentAvatarUrl,
                        )
                    }
                }.awaitAll()
            }

            val ytCommented = enriched
                .filter { it.commentCount != null || it.topComment != null }
                .sortedByDescending { it.commentCount ?: 0L }

            // Combinar comunidad + YouTube sin duplicados
            val combined = (dezzyRealTracks + ytCommented).distinctBy { it.videoId }

            if (combined.isNotEmpty()) {
                combined.take(limit)
            } else {
                // Fallback garantizado: si no hay comentarios aún, mostrar las canciones en tendencia enriquecidas
                enriched.take(limit)
            }
        }
}

/**
 * Sección "Pistas más comentadas" al estilo de YouTube Music, con datos REALES de
 * YouTube (canciones en tendencia + conteo y comentario destacado reales). Al
 * tocar una tarjeta se abre la hoja de comentarios completa (pestaña de YouTube
 * con los comentarios reales + pestaña de Dezzy para comentar en la comunidad).
 *
 * Vive aquí (y no dentro de una pantalla) porque la usan la Home y Comunidad.
 * Si no hay datos (sin red o sin comentarios), no dibuja nada.
 */
@Composable
fun TopCommentedTracksSection(
    modifier: Modifier = Modifier,
    limit: Int = 8,
    header: @Composable () -> Unit = { TopCommentedHeader() },
) {
    val scope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current

    var tracks by remember { mutableStateOf<List<RealCommentedTrack>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var openTrack by remember { mutableStateOf<RealCommentedTrack?>(null) }

    LaunchedEffect(Unit) {
        tracks = RealTopCommented.load(limit)
        loaded = true
    }

    // Cargada y vacía = no hay nada real que enseñar: la sección se oculta.
    if (loaded && tracks.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        header()
        if (!loaded) {
            Box(
                Modifier.fillMaxWidth().height(140.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tracks, key = { it.videoId }) { track ->
                    CommentedTrackCard(
                        track = track,
                        onPlay = { playCommentedTrack(playerConnection, track) },
                        onOpenComments = { openTrack = track },
                    )
                }
            }
        }
    }

    openTrack?.let { track ->
        RealCommentsSheet(track = track, onDismiss = { openTrack = null })
    }
}

@Composable
fun TopCommentedHeader(text: String = "Pistas más comentadas") {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * Tarjeta de pista más comentada (datos reales). La tarjeta reproduce; la burbuja
 * inferior con el comentario destacado abre los comentarios.
 */
@Composable
private fun CommentedTrackCard(
    track: RealCommentedTrack,
    onPlay: () -> Unit,
    onOpenComments: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .width(320.dp)
            .clickable(onClick = onPlay),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtworkThumb(url = track.thumbnail, size = 56.dp, corner = 10.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = "Reproducir",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenComments),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Comentarios",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        track.commentCount?.let { count ->
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = formatCount(count),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ListeningAvatar(avatarUrl = track.topAvatar, isPlaying = false, size = 26.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = track.topComment ?: "Toca para ver los comentarios",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (track.topComment == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            painter = painterResource(R.drawable.expand_more),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hoja de comentarios de una pista de la tarjeta: reutiliza [CommentSheet], que
 * trae los comentarios REALES de YouTube y la pestaña de Dezzy para comentar en
 * la comunidad. Así "más comentadas" enseña lo mismo que se ve en YouTube Music
 * y además permite comentar dentro de Dezzy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RealCommentsSheet(track: RealCommentedTrack, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        com.arturo254.opentune.ui.screens.CommentSheet(
            videoId = track.videoId,
            onDismiss = onDismiss,
        )
    }
}

/**
 * Lista de comentarios de Dezzy de una canción + campo para escribir.
 *
 * Se usa tal cual dentro de [TrackCommentsSheet] (tarjeta de "más comentadas")
 * y dentro de la hoja de comentarios de YouTube del reproductor, para que
 * comentar sea lo mismo se entre por donde se entre.
 */
@Composable
fun DezzyTrackCommentsPanel(
    videoId: String,
    modifier: Modifier = Modifier,
    minHeight: androidx.compose.ui.unit.Dp = 120.dp,
    /** Alto máximo de la lista; null = ocupa todo el espacio libre de la columna. */
    maxHeight: androidx.compose.ui.unit.Dp? = 380.dp,
) {
    val scope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val nowPlaying by (playerConnection?.mediaMetadata
        ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    val userId by SupabaseAuth.currentUserId.collectAsState()

    var comments by remember { mutableStateOf<List<TrackComment>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(videoId, userId) {
        loading = true
        comments = DezzySocial.getTrackComments(videoId)
        loading = false
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(
                    if (maxHeight != null) Modifier.heightIn(min = minHeight, max = maxHeight)
                    else Modifier.weight(1f)
                ),
        ) {
            when {
                loading -> Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                userId == null -> Text(
                    text = "Entra en la comunidad de Dezzy para leer y escribir comentarios.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )

                comments.isEmpty() -> Text(
                    text = "Todavía no hay comentarios. Estrena el hilo.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )

                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    comments.forEach { c ->
                        TrackCommentRow(
                            comment = c,
                            onSeek = {
                                // Solo tiene sentido saltar si esa canción es la que suena.
                                if (nowPlaying?.id == videoId) {
                                    playerConnection?.player?.seekTo(c.positionMs.toLong())
                                }
                            },
                        )
                    }
                }
            }
        }

        if (userId != null) {
            Spacer(Modifier.height(8.dp))
            DezzyComposerField(
                value = input,
                onValueChange = { input = it },
                sending = sending,
                placeholder = "Comenta esta canción…",
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
                onSend = { text ->
                    sending = true
                    scope.launch {
                        // El comentario queda anclado al segundo que suena ahora,
                        // pero solo si lo que suena es ESTA canción.
                        val positionMs = if (nowPlaying?.id == videoId) {
                            (playerConnection?.player?.currentPosition ?: 0L)
                                .coerceAtLeast(0L)
                                .toInt()
                        } else 0
                        DezzySocial.addTrackComment(videoId, positionMs, text)
                        comments = DezzySocial.getTrackComments(videoId)
                        sending = false
                    }
                },
            )
        }
    }
}

@Composable
private fun TrackCommentRow(comment: TrackComment, onSeek: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ListeningAvatar(avatarUrl = comment.authorAvatar, isPlaying = false, size = 34.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.authorName,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (comment.positionMs > 0) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.clickable(onClick = onSeek),
                    ) {
                        Text(
                            text = formatPosition(comment.positionMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(comment.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ── Utilidades ───────────────────────────────────────────────────────────────

/** "254 k" en vez de "254321": el número exacto no aporta nada en la tarjeta. */
private fun formatCount(count: Long): String = when {
    count <= 0 -> "0"
    count < 1_000 -> count.toString()
    count < 1_000_000 -> "${count / 1_000} k"
    else -> "${count / 1_000_000} M"
}

private fun formatPosition(positionMs: Int): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(positionMs.toLong())
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/**
 * Resuelve el video en InnerTube y lo pone a sonar, precargando la metadata para
 * que el reproductor muestre título y carátula sin esperar a la red.
 */
internal fun playCommentedTrack(
    playerConnection: com.arturo254.opentune.playback.PlayerConnection?,
    track: RealCommentedTrack,
) {
    val connection = playerConnection ?: return
    val metadata = com.arturo254.opentune.models.MediaMetadata(
        id = track.videoId,
        title = track.title,
        artists = listOf(com.arturo254.opentune.models.MediaMetadata.Artist(id = null, name = track.artist ?: "")),
        duration = -1,
        thumbnailUrl = track.thumbnail,
    )
    connection.playQueue(com.arturo254.opentune.playback.queues.YouTubeQueue.radio(metadata))
}

internal fun playVideoId(
    scope: CoroutineScope,
    playerConnection: com.arturo254.opentune.playback.PlayerConnection?,
    videoId: String,
) {
    val connection = playerConnection ?: return
    val metadata = com.arturo254.opentune.models.MediaMetadata(
        id = videoId,
        title = "",
        artists = emptyList(),
        duration = -1,
        thumbnailUrl = null,
    )
    connection.playQueue(com.arturo254.opentune.playback.queues.YouTubeQueue.radio(metadata))
}
