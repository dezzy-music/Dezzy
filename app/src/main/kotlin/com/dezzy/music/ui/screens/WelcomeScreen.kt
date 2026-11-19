/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.dezzy.music.R
import com.dezzy.music.constants.InnerTubeCookieKey
import com.dezzy.music.constants.SpotifySpDcKey
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.AlbumItem
import com.dezzy.music.innertube.models.ArtistItem
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.utils.parseCookieString
import com.dezzy.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.Normalizer

private val WelcomeBg = Color(0xFF140D0E)
private val WelcomeText = Color(0xFFFFDADC)
private val WelcomeMuted = Color(0xFFD7C1C2)
private val WelcomeAccent = Color(0xFFFFB2B9)
private val SpotifyGreen = Color(0xFF1ED760)

/** Una foto de la cinta: artista en círculo o portada cuadrada. */
private data class WelcomeTile(val key: String, val name: String, val thumb: String?, val round: Boolean)

/**
 * Pantalla de la primera vez que se abre la app (boceto A): cintas de artistas
 * reales de YouTube Music arriba y, abajo, Spotify y YouTube Music para iniciar
 * sesión de una vez. Cada tarjeta pasa a «Conectado» al volver del login.
 */
@Composable
fun WelcomeScreen(
    onSpotifyLogin: () -> Unit,
    onYoutubeMusicLogin: () -> Unit,
    onFinish: () -> Unit,
) {
    val (spotifySpDc) = rememberPreference(SpotifySpDcKey, "")
    val (innerTubeCookie) = rememberPreference(InnerTubeCookieKey, "")
    val spotifyLinked = spotifySpDc.isNotBlank()
    val youtubeLinked = remember(innerTubeCookie) {
        innerTubeCookie.isNotBlank() && "SAPISID" in parseCookieString(innerTubeCookie)
    }

    var tiles by remember { mutableStateOf<List<WelcomeTile>>(emptyList()) }
    var lyricLabel by remember { mutableStateOf<String?>(null) }

    // Al conectar YouTube Music el inicio pasa a ser el de la cuenta y las cintas cambian con él.
    LaunchedEffect(youtubeLinked) {
        val loaded = withContext(Dispatchers.IO) { runCatching { loadWelcomeTiles() }.getOrNull() }
            ?: return@LaunchedEffect
        if (loaded.first.size >= 6) tiles = loaded.first
        lyricLabel = loaded.second
    }

    Dialog(
        onDismissRequest = { /* Se sale con «Ahora no» o «Continuar». */ },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WelcomeBg)
                .verticalScroll(rememberScrollState()),
        ) {
            WelcomeHero(tiles = tiles, lyricLabel = lyricLabel)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (-30).dp)
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column {
                    Text(
                        text = "Toda tu música, en un sitio",
                        color = WelcomeText,
                        fontSize = 30.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = "Inicia sesión y trae tu biblioteca de una vez.",
                        color = WelcomeMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                AccountCard(
                    gradient = listOf(Color(0xFF0F3D22), Color(0xFF162B1D), Color(0xFF1C1A1A)),
                    logo = R.drawable.ic_spotify_login,
                    title = "Spotify",
                    subtitle = "Playlists, me gusta y artistas",
                    linked = spotifyLinked,
                    buttonText = "Continuar con Spotify",
                    buttonColor = SpotifyGreen,
                    buttonContent = Color.Black,
                    buttonLogo = R.drawable.ic_spotify_mono,
                    onClick = onSpotifyLogin,
                )
                AccountCard(
                    gradient = listOf(Color(0xFF4A1010), Color(0xFF2E1414), Color(0xFF1C1A1A)),
                    logo = R.drawable.ic_youtube_music_login,
                    title = "YouTube Music",
                    subtitle = "Tu inicio, mixes y videoclips",
                    linked = youtubeLinked,
                    buttonText = "Continuar con YouTube Music",
                    buttonColor = Color.White,
                    buttonContent = Color(0xFF0F0F0F),
                    buttonLogo = R.drawable.ic_youtube_music_login,
                    onClick = onYoutubeMusicLogin,
                )

                val anyLinked = spotifyLinked || youtubeLinked
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (anyLinked) "Puedes conectar la otra cuando quieras" else "Puedes hacerlo luego en Ajustes",
                        color = WelcomeMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (anyLinked) {
                        Button(
                            onClick = onFinish,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WelcomeAccent,
                                contentColor = Color(0xFF5F1320),
                            ),
                        ) { Text("Continuar") }
                    } else {
                        TextButton(onClick = onFinish) {
                            Text("Ahora no", color = WelcomeAccent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeHero(tiles: List<WelcomeTile>, lyricLabel: String?) {
    // Sin red: degradados en lugar de fotos.
    val shown = tiles.ifEmpty { List(18) { WelcomeTile("g$it", "", null, it % 4 != 3) } }
    val third = (shown.size + 2) / 3
    val rows = listOf(shown.take(third), shown.drop(third).take(third), shown.drop(third * 2))
        .map { row -> if (row.size >= 5) row else (row + shown).take(6) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(
                Brush.radialGradient(
                    0f to Color(0xFF6E2430),
                    0.5f to Color(0xFF3A1D22),
                    0.95f to WelcomeBg,
                    center = Offset.Zero,
                    radius = 1100f,
                ),
            )
            .clearAndSetSemantics { },
    ) {
        Column(
            modifier = Modifier
                .requiredWidth(560.dp)
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
                .rotate(-8f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            rows.forEachIndexed { i, row ->
                TileRibbon(row, reverse = i == 1, durationMs = if (i == 1) 46_000 else 36_000)
            }
        }
        // Fundido hacia el fondo para que el título se lea.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.5f to Color.Transparent, 0.94f to WelcomeBg)),
        )
        val bubble = RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 16.dp, top = 24.dp)
                .widthIn(max = 210.dp)
                .rotate(-3f)
                .shadow(10.dp, bubble)
                .background(WelcomeAccent.copy(alpha = 0.95f), bubble)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = lyricLabel ?: "Letras sincronizadas",
                color = Color(0xFF4D0A16).copy(alpha = 0.75f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("Cada verso a su tiempo", color = Color(0xFF4D0A16), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 206.dp, end = 14.dp)
                .rotate(3f)
                .shadow(10.dp, RoundedCornerShape(16.dp))
                .background(Color(0xFF231819), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(painterResource(R.drawable.favorite), null, tint = WelcomeAccent, modifier = Modifier.size(16.dp))
            Text("Tus me gusta, siempre", color = WelcomeText, fontSize = 12.sp)
        }
    }
}

/** Fila que se desplaza sin fin: se pinta dos veces y avanza media anchura. */
@Composable
private fun TileRibbon(row: List<WelcomeTile>, reverse: Boolean, durationMs: Int) {
    val step = 102.dp // 92 de foto + 10 de hueco
    val halfPx = with(LocalDensity.current) { (step * row.size).toPx() }
    val progress by rememberInfiniteTransition(label = "ribbon").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Restart),
        label = "ribbonProgress",
    )
    Row(
        modifier = Modifier
            .requiredWidth(step * row.size * 2)
            .graphicsLayer {
                val p = if (reverse) 1f - progress else progress
                translationX = halfPx / 2f - p * halfPx
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        (row + row).forEachIndexed { i, tile ->
            val shape = if (tile.round) CircleShape else RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .shadow(10.dp, shape)
                    .clip(shape)
                    .background(Brush.linearGradient(TileGradients[i % TileGradients.size])),
            ) {
                if (tile.thumb != null) {
                    AsyncImage(
                        model = tile.thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))),
                    )
                    Text(
                        text = tile.name,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

private val TileGradients = listOf(
    listOf(Color(0xFF6E2430), Color(0xFF3A1D22)),
    listOf(Color(0xFF5B3A1F), Color(0xFF2A1B10)),
    listOf(Color(0xFF3C2A55), Color(0xFF1E1530)),
    listOf(Color(0xFF1F4A3A), Color(0xFF10261E)),
)

@Composable
private fun AccountCard(
    gradient: List<Color>,
    logo: Int,
    title: String,
    subtitle: String,
    linked: Boolean,
    buttonText: String,
    buttonColor: Color,
    buttonContent: Color,
    buttonLogo: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(gradient)),
    ) {
        // Logo grande de fondo, muy suave.
        Image(
            painter = painterResource(logo),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 26.dp, y = (-26).dp)
                .size(130.dp)
                .graphicsLayer { alpha = 0.13f },
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(painterResource(logo), contentDescription = null, modifier = Modifier.size(40.dp))
                Column {
                    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
                }
            }
            Button(
                onClick = onClick,
                enabled = !linked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = buttonContent,
                    disabledContainerColor = Color.White.copy(alpha = 0.12f),
                    disabledContentColor = Color.White,
                ),
            ) {
                if (linked) {
                    Icon(painterResource(R.drawable.check), null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Conectado")
                } else {
                    Image(painterResource(buttonLogo), contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(buttonText, maxLines = 1)
                }
            }
        }
    }
}

private fun String.normalizedName(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(Regex("""\p{M}"""), "").trim()

/**
 * Artistas y portadas del inicio de YouTube Music (los de la cuenta o, sin ella, los
 * generales del país). Sin cuenta casi no salen artistas: se buscan los de sus canciones.
 * Devuelve las fotos y una etiqueta «artista · canción» para el bocadillo de letras.
 */
private suspend fun loadWelcomeTiles(): Pair<List<WelcomeTile>, String?>? = coroutineScope {
    val home = YouTube.home().getOrNull() ?: return@coroutineScope null
    val items = home.sections.flatMap { it.items }
    val firstSong = items.firstOrNull { it is SongItem } as? SongItem
    val label = firstSong?.let { song ->
        listOfNotNull(song.artists.firstOrNull()?.name, song.title).joinToString(" · ")
    }

    val artists = items.filterIsInstance<ArtistItem>().toMutableList()
    if (artists.size < 8) {
        val names = items.mapNotNull {
            when (it) {
                is SongItem -> it.artists.firstOrNull()?.name
                is AlbumItem -> it.artists?.firstOrNull()?.name
                else -> null
            }
        }.distinct().take(12)
        artists += names.map { name ->
            async {
                YouTube.search(name, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()
                    ?.items?.filterIsInstance<ArtistItem>()
                    ?.firstOrNull { it.title.normalizedName() == name.normalizedName() && it.thumbnail != null }
            }
        }.awaitAll().filterNotNull()
    }

    val seen = HashSet<String>()
    val artistTiles = artists.filter { it.thumbnail != null && seen.add(it.id) }
        .map { WelcomeTile(it.id, it.title, it.thumbnail, round = true) }
    val coverTiles = items.filter { (it is AlbumItem || it is SongItem) && it.thumbnail != null && seen.add(it.id) }
        .map { WelcomeTile(it.id, it.title, it.thumbnail, round = false) }

    // Artistas en círculo con una portada cuadrada cada cuatro.
    val out = ArrayList<WelcomeTile>(18)
    var a = 0
    var c = 0
    while (out.size < 18 && (a < artistTiles.size || c < coverTiles.size)) {
        val coverTurn = out.size % 4 == 3 || a >= artistTiles.size
        if (coverTurn && c < coverTiles.size) {
            out += coverTiles[c++]
        } else if (a < artistTiles.size) {
            out += artistTiles[a++]
        } else {
            out += coverTiles[c++]
        }
    }
    out to label
}
