/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.musicmap

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dezzy.music.R
import com.dezzy.music.db.entities.Song
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.musicmap.SongChartStats
import com.dezzy.music.spotify.SpotifyFeatures
import com.dezzy.music.ui.component.CountryFlag
import com.dezzy.music.ui.utils.highRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ── Buscador de cualquier canción ────────────────────────────────────────────

/** Resultado de la búsqueda en todo el catálogo para la [query] actual. */
internal data class CatalogSearch(val query: String, val loading: Boolean, val songs: List<SongItem>)

/** Busca canciones en YouTube Music con 400 ms de espera mientras se escribe. */
@Composable
internal fun rememberCatalogSearch(query: String): CatalogSearch {
    var result by remember { mutableStateOf<Pair<String, List<SongItem>>?>(null) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            result = null
            return@LaunchedEffect
        }
        delay(400)
        val songs = YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).getOrNull()
            ?.items?.filterIsInstance<SongItem>().orEmpty().take(20)
        result = q to songs
    }
    val q = query.trim()
    val current = result?.takeIf { it.first == q }
    return CatalogSearch(q, loading = q.length >= 2 && current == null, songs = current?.second.orEmpty())
}

@Composable
internal fun RankingsSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("Busca cualquier canción", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(painterResource(R.drawable.search), contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(painterResource(R.drawable.close), contentDescription = "Borrar búsqueda")
                }
            }
        },
        supportingText = { Text("Mira en qué países ha estado en el top y cuántos streams lleva") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

// ── Tus canciones en los rankings (historial) ────────────────────────────────

/** Mejor puesto conocido de una canción del historial. */
internal sealed interface HistoryPeak {
    data object Loading : HistoryPeak
    data class Charted(val peak: Int, val countryCode: String, val countries: Int) : HistoryPeak
    data object NeverCharted : HistoryPeak
    data object Unknown : HistoryPeak
}

/** Estado de la sección «Tus canciones en los rankings». */
internal class HistoryRankingsState {
    var hasSpotify by mutableStateOf<Boolean?>(null)
    val peaks: SnapshotStateMap<String, HistoryPeak> = mutableStateMapOf()
}

/**
 * Consulta, una a una, las listas de tus canciones más escuchadas. Cada resultado se guarda
 * en caché (12 h), así que volver a entrar en Rankings no repite las peticiones.
 */
@Composable
internal fun rememberHistoryRankings(songs: List<Song>): HistoryRankingsState {
    val context = LocalContext.current
    val state = remember { HistoryRankingsState() }
    val ids = songs.map { it.song.id }
    LaunchedEffect(ids) {
        val has = withContext(Dispatchers.IO) { SpotifyFeatures.hasSession(context) }
        state.hasSpotify = has
        if (!has) return@LaunchedEffect
        songs.forEach { song ->
            if (state.peaks[song.song.id].let { it != null && it != HistoryPeak.Loading }) return@forEach
            state.peaks[song.song.id] = HistoryPeak.Loading
            state.peaks[song.song.id] = peakOf(context, song)
        }
    }
    return state
}

private suspend fun peakOf(context: Context, song: Song): HistoryPeak {
    val artist = song.artists.joinToString { it.name }
    return when (val r = SongChartStats.forSong(context, song.song.title, artist)) {
        is SongChartStats.Result.Ok -> {
            val countries = r.stats.byCountry
            val best = countries.minWithOrNull(compareBy<SongChartStats.CountryStats> { it.peak }.thenByDescending { it.totalStreams })
                ?: r.stats.global
            if (best == null) HistoryPeak.NeverCharted else HistoryPeak.Charted(best.peak, best.code, countries.size)
        }
        is SongChartStats.Result.NeverCharted -> HistoryPeak.NeverCharted
        else -> HistoryPeak.Unknown
    }
}

@Composable
internal fun RankingsSectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Aviso dentro de Rankings cuando no hay sesión de Spotify. */
@Composable
internal fun ConnectSpotifyCard(onConnect: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Icon(painterResource(R.drawable.stats), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                "Conecta Spotify para ver en qué listas han estado tus canciones.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onConnect) { Text("Conectar") }
        }
    }
}

/** Fila genérica de canción con botón de estadísticas. */
@Composable
internal fun RankingSongRow(
    title: String,
    subtitle: String,
    thumbnail: String?,
    isActive: Boolean,
    onPlay: () -> Unit,
    onStats: () -> Unit,
    rank: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = "Escuchar $title", onClick = onPlay)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        if (rank != null) {
            Text(
                "$rank",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
        }
        AsyncImage(
            model = thumbnail?.highRes(),
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(scheme.surfaceContainerHigh),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isActive) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
        IconButton(onClick = onStats) {
            Icon(painterResource(R.drawable.stats), contentDescription = "Estadísticas de $title")
        }
    }
}

/** Mejor puesto a la derecha de la fila del historial. */
@Composable
internal fun HistoryPeakBadge(peak: HistoryPeak?) {
    val scheme = MaterialTheme.colorScheme
    when (peak) {
        HistoryPeak.Loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        is HistoryPeak.Charted -> Row(verticalAlignment = Alignment.CenterVertically) {
            CountryFlag(peak.countryCode, height = 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                "#${peak.peak}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (peak.peak <= 10) scheme.primary else scheme.onSurface,
            )
        }
        else -> Unit
    }
}

/** Texto bajo el título de una canción del historial. */
internal fun historySubtitle(song: Song, peak: HistoryPeak?): String {
    val artist = song.artists.joinToString { it.name }
    return when (peak) {
        is HistoryPeak.Charted -> "$artist, mejor #${peak.peak} en ${SongChartStats.countryName(peak.countryCode)}" +
            if (peak.countries > 1) ", en ${peak.countries} países" else ""
        HistoryPeak.NeverCharted -> "$artist, no ha entrado en ninguna lista"
        else -> artist
    }
}
