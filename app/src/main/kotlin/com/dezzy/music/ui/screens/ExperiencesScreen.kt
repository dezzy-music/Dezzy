/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.dezzy.music.LocalDatabase
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.ui.component.IconButton
import com.dezzy.music.ui.screens.musicrecognition.MusicRecognitionRoute
import com.dezzy.music.ui.utils.backToMain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Colores propios del DJ (su tarjeta conserva el aire oscuro de la pantalla del DJ). */
private val ExperienceDjStart = Color(0xFF2A0F2E)
private val ExperienceDjMid = Color(0xFF5B2830)
private val ExperienceDjEnd = Color(0xFF9C3F48)
private val ExperienceDjAccent = Color(0xFFFFB3B9)

private enum class ExperienceTone { Neutral, Primary, Tertiary }

private data class ExperienceTile(
    val key: String,
    @DrawableRes val icon: Int,
    val title: String,
    val subtitle: String,
    val tone: ExperienceTone = ExperienceTone.Neutral,
    val badge: String? = null,
    val onClick: () -> Unit,
)

private data class ExperienceLink(
    val key: String,
    @DrawableRes val icon: Int,
    val title: String,
    val subtitle: String,
    val route: String,
)

/**
 * Pantalla «Experiencias» (fase 6 del rediseño, boceto B): reúne en un mosaico las
 * experiencias de Dezzy —DJ, Tu año, estadísticas, Máquina del tiempo, Mapa,
 * Song DNA y Lossless— y debajo el resto de accesos que antes vivían en la hoja
 * «Más de Dezzy». Se abre desde «Más» en Herramientas Dezzy (Inicio).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperiencesScreen(navController: NavController) {
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val mediaMetadata by (playerConnection?.mediaMetadata ?: MutableStateFlow(null)).collectAsState()

    // Minutos de los últimos 7 días (misma convención de tiempo que la tabla event).
    val weekFrom = remember {
        LocalDateTime.now().minusDays(7).toInstant(ZoneOffset.UTC).toEpochMilli()
    }
    val weekPlayTime by remember(weekFrom) { database.playTimeSince(weekFrom) }.collectAsState(initial = 0L)

    // Song DNA necesita un artista: el de la canción actual o, si no hay, el más escuchado.
    val topArtistId by remember {
        database.mostPlayedArtists(fromTimeStamp = 0, limit = 1).map { it.firstOrNull()?.id }
    }.collectAsState(initial = null)
    val dnaArtistId = mediaMetadata?.artists?.firstOrNull { !it.id.isNullOrBlank() }?.id ?: topArtistId

    val tiles = listOf(
        ExperienceTile(
            key = "year",
            icon = R.drawable.auto_awesome,
            title = "Tu año en música",
            subtitle = "Tu resumen ${LocalDateTime.now().year}",
            tone = ExperienceTone.Primary,
            onClick = { navController.navigate("year_in_music") },
        ),
        ExperienceTile(
            key = "stats",
            icon = R.drawable.stats,
            title = formatExperienceListenTime(weekPlayTime),
            subtitle = "Escuchado esta semana",
            onClick = { navController.navigate("stats") },
        ),
        ExperienceTile(
            key = "time_machine",
            icon = R.drawable.restore,
            title = "Máquina del tiempo",
            subtitle = "Viaja por décadas",
            onClick = { navController.navigate("time_machine") },
        ),
        ExperienceTile(
            key = "map",
            icon = R.drawable.language,
            title = "Mapa musical",
            subtitle = "Sonidos por país",
            onClick = { navController.navigate("music_map") },
        ),
        ExperienceTile(
            key = "song_dna",
            icon = R.drawable.similar,
            title = "Song DNA",
            subtitle = if (dnaArtistId != null) "Artistas afines" else "Escucha algo primero",
            badge = "Beta",
            onClick = { dnaArtistId?.let { navController.navigate("songdna/$it") } },
        ),
        ExperienceTile(
            key = "lossless",
            icon = R.drawable.headphones,
            title = "Lossless",
            subtitle = "FLAC sin pérdida",
            tone = ExperienceTone.Tertiary,
            onClick = { navController.navigate("lossless") },
        ),
    )

    val links = listOf(
        ExperienceLink("new", R.drawable.new_release, "Novedades", "Lo nuevo de los artistas que sigues", "new_release"),
        ExperienceLink("history", R.drawable.history, "Historial", "Lo que escuchaste hace poco", "history"),
        ExperienceLink("downloads", R.drawable.download, "Mis descargas", "Canciones descargadas y cola", Screens.DownloadQueue.route),
        ExperienceLink("together", R.drawable.group, "Escuchar juntos", "Salas públicas y privadas", "public_rooms"),
        ExperienceLink("charts", R.drawable.trending_up, "Listas de éxitos", "Lo más viral y charts mundiales", "charts_screen"),
        ExperienceLink("recognize", R.drawable.mic, "Reconocer música", "Qué canción está sonando", MusicRecognitionRoute),
        ExperienceLink("settings", R.drawable.settings, "Ajustes", "Apariencia, reproducción y cuentas", "settings"),
    )

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                .asPaddingValues()
                .let { base ->
                    PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = base.calculateBottomPadding() + 16.dp,
                    )
                },
            modifier = Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            ),
        ) {
            item(key = "dj", span = { GridItemSpan(maxLineSpan) }) {
                ExperienceDjCard(onClick = { navController.navigate("dj_dezzy") })
            }

            items(items = tiles, key = { "tile_${it.key}" }) { tile ->
                ExperienceTileCard(tile = tile)
            }

            item(key = "more_title", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Más de Dezzy",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(top = 16.dp, bottom = 4.dp, start = 4.dp)
                        .semantics { heading() },
                )
            }

            items(
                items = links,
                key = { "link_${it.key}" },
                span = { GridItemSpan(maxLineSpan) },
            ) { link ->
                ListItem(
                    headlineContent = { Text(link.title) },
                    supportingContent = {
                        Text(
                            text = link.subtitle,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(link.icon),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    },
                    trailingContent = {
                        Icon(
                            painter = painterResource(R.drawable.navigate_next),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button) { navController.navigate(link.route) },
                )
            }
        }

        TopAppBar(
            title = { Text("Experiencias") },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
        )
    }
}

/** Tarjeta grande del DJ: ocupa las dos columnas y lleva el degradado oscuro del DJ. */
@Composable
private fun ExperienceDjCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(listOf(ExperienceDjStart, ExperienceDjMid, ExperienceDjEnd)),
            )
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        // Orbe decorativo arriba a la derecha.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 36.dp, y = (-28).dp)
                .size(160.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color.White, ExperienceDjAccent, Color(0xFFED5564), ExperienceDjEnd, Color.Transparent),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.graphic_eq),
                contentDescription = null,
                tint = ExperienceDjAccent,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.fillMaxWidth(0.7f)) {
                Text(
                    text = "DJ Dezzy",
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
                Text(
                    text = "Tu música, mezclada y presentada por voz",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = ExperienceDjEnd,
                ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.play),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Iniciar")
            }
        }
    }
}

@Composable
private fun ExperienceTileCard(tile: ExperienceTile) {
    val colorScheme = MaterialTheme.colorScheme
    val (container, content, iconTint) = when (tile.tone) {
        ExperienceTone.Primary -> Triple(colorScheme.primaryContainer, colorScheme.onPrimaryContainer, colorScheme.onPrimaryContainer)
        ExperienceTone.Tertiary -> Triple(colorScheme.tertiaryContainer, colorScheme.onTertiaryContainer, colorScheme.tertiary)
        ExperienceTone.Neutral -> Triple(colorScheme.surfaceContainer, colorScheme.onSurface, colorScheme.primary)
    }
    Surface(
        onClick = tile.onClick,
        shape = RoundedCornerShape(20.dp),
        color = container,
        contentColor = content,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(tile.icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tile.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (tile.badge != null) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = colorScheme.inverseSurface,
                            contentColor = colorScheme.inverseOnSurface,
                        ) {
                            Text(
                                text = tile.badge,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tile.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = content.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** «38 h 12 min», «45 min» o «0 min» a partir de milisegundos. */
internal fun formatExperienceListenTime(ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0L -> "$minutes min"
        minutes == 0L -> "$hours h"
        else -> "$hours h $minutes min"
    }
}
