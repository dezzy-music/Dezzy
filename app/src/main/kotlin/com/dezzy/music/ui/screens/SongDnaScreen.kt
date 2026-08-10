/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * SongDNA (estilo Spotify): combina DOS artistas (el principal de la canción y
 * su artista más afín) y, en base a ambos, recomienda NUEVOS artistas y canciones
 * presentados como una línea de tiempo. Datos 100% reales de YouTube Music
 * (InnerTube): artistas relacionados y canciones de cada semilla, mezclados y
 * priorizando los que aparecen en ambos (la verdadera "combinación").
 */

package com.dezzy.music.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.ArtistItem
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.models.toMediaMetadata
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.ui.component.IconButton
import com.dezzy.music.ui.utils.backToMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val SpotifyGreen = Color(0xFF1DB954)

private data class SongDnaResult(
    val seed: ArtistItem?,
    val partner: ArtistItem?,
    val artists: List<ArtistItem>,
    val songs: List<SongItem>,
)

@Composable
fun SongDnaScreen(
    navController: NavController,
    artistId: String,
) {
    val playerConnection = LocalPlayerConnection.current ?: return

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf(SongDnaResult(null, null, emptyList(), emptyList())) }

    LaunchedEffect(artistId) {
        loading = true
        error = null
        if (artistId.isBlank()) {
            error = "Sin artista para analizar"
            loading = false
            return@LaunchedEffect
        }
        data = withContext(Dispatchers.IO) {
            runCatching { buildSongDna(artistId) }.getOrNull()
                ?: SongDnaResult(null, null, emptyList(), emptyList())
        }
        if (data.seed == null && data.artists.isEmpty()) {
            error = "No se pudo generar el SongDNA de este artista"
        }
        loading = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF20131F), Color(0xFF0D0D0D))))
            .windowInsetsPadding(
                com.dezzy.music.LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            ),
    ) {
        // Cabecera
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Spacer(
                    Modifier.windowInsetsPadding(
                        com.dezzy.music.LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
                    ),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null, tint = Color.White)
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(R.drawable.auto_awesome),
                        contentDescription = null,
                        tint = SpotifyGreen,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("SongDNA", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(SpotifyGreen)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text("Beta", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = SpotifyGreen)
                    }
                }
                return@LazyColumn
            }
            error?.let { msg ->
                item {
                    Text(
                        msg,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
                return@LazyColumn
            }

            // Las dos semillas combinadas
            item {
                BlendHeader(seed = data.seed, partner = data.partner)
                Spacer(Modifier.height(20.dp))
            }

            // Timeline de artistas para descubrir
            if (data.artists.isNotEmpty()) {
                item {
                    SectionTitle("Nuevos artistas para descubrir")
                }
                itemsIndexed(data.artists) { index, artist ->
                    TimelineArtistNode(
                        artist = artist,
                        isFirst = index == 0,
                        isLast = index == data.artists.lastIndex,
                        onClick = { navController.navigate("artist/${artist.id}") },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }

            // Canciones recomendadas por la combinación
            if (data.songs.isNotEmpty()) {
                item {
                    SectionTitle("Canciones que conectan a ambos")
                    Spacer(Modifier.height(8.dp))
                }
                itemsIndexed(data.songs) { _, song ->
                    SongRow(
                        song = song,
                        onClick = {
                            playerConnection.playQueue(
                                YouTubeQueue(WatchEndpoint(videoId = song.id), song.toMediaMetadata()),
                            )
                        },
                    )
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }
}

// ── Combinación real de dos artistas ─────────────────────────────────────────

private suspend fun buildSongDna(seedId: String): SongDnaResult {
    val seedPage = YouTube.artist(seedId).getOrNull() ?: return SongDnaResult(null, null, emptyList(), emptyList())
    val seedArtists = seedPage.sections.flatMap { it.items }.filterIsInstance<ArtistItem>()
    val seedSongs = seedPage.sections.flatMap { it.items }.filterIsInstance<SongItem>()

    // El "compañero de mezcla": el artista más afín al principal.
    val partner = seedArtists.firstOrNull { it.id != seedId }
    val partnerPage = partner?.let { YouTube.artist(it.id).getOrNull() }
    val partnerArtists = partnerPage?.sections?.flatMap { it.items }?.filterIsInstance<ArtistItem>().orEmpty()
    val partnerSongs = partnerPage?.sections?.flatMap { it.items }?.filterIsInstance<SongItem>().orEmpty()

    val excluded = setOfNotNull(seedId, partner?.id)

    // Artistas recomendados: primero los que aparecen en AMBAS listas (la
    // verdadera intersección de la combinación), luego el resto, sin duplicados.
    val seedIds = seedArtists.map { it.id }.toSet()
    val partnerIds = partnerArtists.map { it.id }.toSet()
    val both = (seedIds intersect partnerIds)
    val ordered = (seedArtists + partnerArtists)
        .filter { it.id !in excluded }
        .distinctBy { it.id }
        .sortedByDescending { it.id in both }
        .take(12)

    // Canciones: intercalamos las de ambos para que la timeline conecte los dos.
    val songs = interleave(seedSongs, partnerSongs)
        .distinctBy { it.id }
        .take(15)

    return SongDnaResult(seed = seedPage.artist, partner = partner, artists = ordered, songs = songs)
}

private fun <T> interleave(a: List<T>, b: List<T>): List<T> {
    val out = ArrayList<T>(a.size + b.size)
    val max = maxOf(a.size, b.size)
    for (i in 0 until max) {
        if (i < a.size) out.add(a[i])
        if (i < b.size) out.add(b[i])
    }
    return out
}

// ── UI ───────────────────────────────────────────────────────────────────────

@Composable
private fun BlendHeader(seed: ArtistItem?, partner: ArtistItem?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BlendCircle(url = seed?.thumbnail, name = seed?.title)
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.add),
                contentDescription = "combinar",
                tint = SpotifyGreen,
                modifier = Modifier.size(26.dp),
            )
        }
        BlendCircle(url = partner?.thumbnail, name = partner?.title)
    }
}

@Composable
private fun BlendCircle(url: String?, name: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(120.dp)) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.artist),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = name ?: "—",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun TimelineArtistNode(
    artist: ArtistItem,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Línea de tiempo (columna izquierda con nodo + línea)
        Canvas(
            modifier = Modifier
                .width(24.dp)
                .height(76.dp),
        ) {
            val cx = size.width / 2f
            val nodeY = size.height / 2f
            if (!isFirst) {
                drawLine(
                    color = SpotifyGreen.copy(alpha = 0.5f),
                    start = Offset(cx, 0f),
                    end = Offset(cx, nodeY),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round,
                )
            }
            if (!isLast) {
                drawLine(
                    color = SpotifyGreen.copy(alpha = 0.5f),
                    start = Offset(cx, nodeY),
                    end = Offset(cx, size.height),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(color = SpotifyGreen, radius = 6f, center = Offset(cx, nodeY))
        }
        Spacer(Modifier.width(12.dp))
        AsyncImage(
            model = artist.thumbnail,
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Artista",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
            )
        }
        Icon(
            painter = painterResource(R.drawable.arrow_forward),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SongRow(song: SongItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = song.thumbnail,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artists.joinToString { it.name },
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painter = painterResource(R.drawable.play),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
    }
}
