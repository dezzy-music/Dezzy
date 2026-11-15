/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.musicmap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dezzy.music.R
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.musicmap.MusicMapGenres
import com.dezzy.music.ui.utils.highRes

/*
 * Mapa musical — boceto A «Hoja del país con géneros».
 * Al tocar un país, la hoja enseña cuánto pesa cada género en su top y unos chips para ver
 * el top de cada uno. Arriba se busca un artista o canción y sale la lista de países donde
 * más suena, con su mejor posición.
 */

/** Bandera emoji a partir del código ISO del país («MX» → 🇲🇽). */
internal fun flagEmoji(code: String): String {
    if (code == "ZZ" || code.length != 2 || !code.all { it in 'A'..'Z' }) return "🌐"
    val first = Character.toChars(0x1F1E6 + (code[0] - 'A'))
    val second = Character.toChars(0x1F1E6 + (code[1] - 'A'))
    return String(first) + String(second)
}

// ── Géneros ──────────────────────────────────────────────────────────────────

/** Peso de un género en el top de un país. */
internal data class GenreShare(val name: String, val count: Int, val percent: Int)

/** Cuántos géneros se nombran; el resto se junta en «Otros». */
private const val NAMED_GENRES = 5

/**
 * Reparte las canciones del top por género. Sólo cuentan las que ya tienen género; los
 * géneros pequeños y los no clasificados se juntan en «Otros», que va siempre al final.
 */
internal fun genreShares(songs: List<SongItem>, genreOf: (SongItem) -> String?): List<GenreShare> {
    val genres = songs.mapNotNull(genreOf)
    if (genres.isEmpty()) return emptyList()
    val counts = genres.groupingBy { it }.eachCount()
    val named = counts.filterKeys { it != MusicMapGenres.OTHER }
        .entries.sortedByDescending { it.value }
        .take(NAMED_GENRES)
    val namedCount = named.sumOf { it.value }
    val total = genres.size
    fun pct(n: Int) = (n * 100f / total).let { kotlin.math.round(it).toInt() }
    return buildList {
        named.forEach { add(GenreShare(it.key, it.value, pct(it.value))) }
        val rest = total - namedCount
        if (rest > 0) add(GenreShare(MusicMapGenres.OTHER, rest, pct(rest)))
    }
}

/** ¿La canción entra en el chip de género [filter]? «Otros» recoge lo que no tiene chip propio. */
internal fun matchesGenre(genre: String?, filter: String, shares: List<GenreShare>): Boolean {
    if (filter != MusicMapGenres.OTHER) return genre == filter
    val named = shares.map { it.name }.filter { it != MusicMapGenres.OTHER }
    return genre != null && genre !in named
}

@Composable
private fun genreColors(): List<Color> {
    val s = MaterialTheme.colorScheme
    return listOf(s.primary, s.tertiary, s.secondary, s.inversePrimary, s.outline)
}

@Composable
private fun colorFor(index: Int, share: GenreShare): Color =
    if (share.name == MusicMapGenres.OTHER) MaterialTheme.colorScheme.outlineVariant
    else genreColors()[index % NAMED_GENRES]

/** Barra con el peso de cada género, leyenda con los números y chips para filtrar el top. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GenreShareSection(
    shares: List<GenreShare>,
    loading: Boolean,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        if (shares.isEmpty()) {
            Text(
                text = if (loading) "Analizando los géneros del top…" else "Aún no sabemos los géneros de este top",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            return@Column
        }

        val description = shares.joinToString(prefix = "Géneros del top: ") { "${it.name} ${it.percent} %" }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            shares.forEachIndexed { index, share ->
                Box(
                    Modifier
                        .weight(share.count.toFloat())
                        .fillMaxHeight()
                        .background(colorFor(index, share)),
                )
            }
        }

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            shares.forEachIndexed { index, share ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(colorFor(index, share)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${share.name} ${share.percent} %",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GenreChip("Todo", selected == null) { onSelect(null) }
            shares.forEach { share ->
                GenreChip(share.name, selected == share.name) {
                    onSelect(if (selected == share.name) null else share.name)
                }
            }
        }
    }
}

@Composable
private fun GenreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            {
                Icon(
                    painterResource(R.drawable.check),
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        } else null,
    )
}

// ── Tendencia ────────────────────────────────────────────────────────────────

/**
 * Flecha de tendencia respecto al top anterior. Lleva texto («sube 3») para no depender sólo
 * del color. Sin foto anterior no enseña nada.
 */
@Composable
internal fun TrendBadge(rank: Int, previousRanks: Map<String, Int>, songId: String) {
    if (previousRanks.isEmpty()) return
    val previous = previousRanks[songId]
    val scheme = MaterialTheme.colorScheme
    val (text, icon, color, spoken) = when {
        previous == null -> Quad("Nuevo", null, scheme.tertiary, "nueva en el top")
        previous > rank -> Quad("${previous - rank}", R.drawable.arrow_upward, scheme.primary, "sube ${previous - rank}")
        previous < rank -> Quad("${rank - previous}", R.drawable.arrow_downward, scheme.error, "baja ${rank - previous}")
        else -> Quad("—", null, scheme.onSurfaceVariant, "sin cambios")
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontSize = 12.sp,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private data class Quad(val text: String, val icon: Int?, val color: Color, val spoken: String)

// ── Buscador: dónde se escucha más ───────────────────────────────────────────

internal enum class SearchTab(val label: String) { ARTIST("Artista"), SONGS("Canciones"), COUNTRIES("Países") }

/** Presencia de un artista o canción en el top de un país. */
internal data class CountryHit(
    val country: Country,
    val bestRank: Int,
    val count: Int,
    val bestSong: SongItem,
)

internal data class ArtistResult(
    val name: String,
    val artistId: String?,
    val thumbnail: String?,
    val hits: List<CountryHit>,
)

internal data class SongResult(val song: SongItem, val hits: List<CountryHit>)

internal data class MapSearch(
    val artist: ArtistResult?,
    val songs: List<SongResult>,
    val countries: List<CountryChart>,
) {
    val isEmpty get() = artist == null && songs.isEmpty() && countries.isEmpty()

    fun firstTab(): SearchTab = when {
        artist != null -> SearchTab.ARTIST
        songs.isNotEmpty() -> SearchTab.SONGS
        else -> SearchTab.COUNTRIES
    }
}

/** Busca en los tops ya cargados: el artista más presente, las canciones y los países. */
internal fun searchCharts(query: String, charts: List<CountryChart>): MapSearch {
    val q = MusicMapGenres.key(query)
    if (q.isEmpty()) return MapSearch(null, emptyList(), emptyList())

    // Artista: el nombre que más aparece entre los que contienen la búsqueda.
    val artistCounts = HashMap<String, Int>()
    val artistNames = HashMap<String, Pair<String, String?>>()
    charts.forEach { chart ->
        chart.songs.forEach { song ->
            song.artists.forEach { a ->
                val k = MusicMapGenres.key(a.name)
                if (k.contains(q)) {
                    artistCounts[k] = (artistCounts[k] ?: 0) + 1
                    val known = artistNames[k]
                    if (known == null || (known.second == null && a.id != null)) artistNames[k] = a.name to a.id
                }
            }
        }
    }
    val artistKey = artistCounts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { if (it.key == q) 1 else 0 }.thenByDescending { it.value })
        .firstOrNull()?.key
    val artist = artistKey?.let { k ->
        val hits = charts.mapNotNull { chart ->
            val ranked = chart.songs.withIndex().filter { (_, s) -> s.artists.any { MusicMapGenres.key(it.name) == k } }
            val best = ranked.firstOrNull() ?: return@mapNotNull null
            CountryHit(chart.country, best.index + 1, ranked.size, best.value)
        }.sortedWith(compareBy<CountryHit> { it.bestRank }.thenByDescending { it.count })
        val (name, id) = artistNames.getValue(k)
        ArtistResult(name, id, hits.firstOrNull()?.bestSong?.thumbnail, hits)
    }

    // Canciones cuyo título contiene la búsqueda.
    val songHits = LinkedHashMap<String, MutableList<CountryHit>>()
    val songById = HashMap<String, SongItem>()
    charts.forEach { chart ->
        chart.songs.forEachIndexed { index, song ->
            if (MusicMapGenres.key(song.title).contains(q)) {
                songById.putIfAbsent(song.id, song)
                songHits.getOrPut(song.id) { mutableListOf() } += CountryHit(chart.country, index + 1, 1, song)
            }
        }
    }
    val songs = songHits.map { (id, hits) -> SongResult(songById.getValue(id), hits.sortedBy { it.bestRank }) }
        .sortedWith(compareByDescending<SongResult> { it.hits.size }.thenBy { it.hits.first().bestRank })
        .take(20)

    val countries = charts.filter {
        MusicMapGenres.key(it.country.name).contains(q) || it.country.code.equals(query.trim(), ignoreCase = true)
    }

    return MapSearch(artist, songs, countries)
}

/** Resultados del buscador: lista de países ordenada por la mejor posición. */
@Composable
internal fun MapSearchResults(
    query: String,
    search: MapSearch,
    tab: SearchTab,
    countriesLoaded: Int,
    currentSongId: String?,
    onTab: (SearchTab) -> Unit,
    onRadio: (SongItem) -> Unit,
    onArtist: (String) -> Unit,
    onShowOnMap: (Set<String>) -> Unit,
    onFocusCountry: (Country) -> Unit,
    onPlaySong: (SongItem) -> Unit,
    onSearchEverywhere: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    val scheme = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.heightIn(max = 560.dp),
        contentPadding = PaddingValues(bottom = 16.dp + bottomPadding),
    ) {
        item(key = "tabs") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchTab.entries.forEach { t ->
                    val count = when (t) {
                        SearchTab.ARTIST -> if (search.artist != null) 1 else 0
                        SearchTab.SONGS -> search.songs.size
                        SearchTab.COUNTRIES -> search.countries.size
                    }
                    FilterChip(
                        selected = tab == t,
                        enabled = count > 0,
                        onClick = { onTab(t) },
                        label = { Text(t.label) },
                    )
                }
            }
        }

        if (search.isEmpty) {
            item(key = "empty") {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Text(
                        text = "«${query.trim()}» no está hoy en los tops de los $countriesLoaded países del mapa.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurface,
                    )
                    TextButton(onClick = onSearchEverywhere, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(painterResource(R.drawable.search), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Buscar en toda la música")
                    }
                }
            }
        } else when (tab) {
            SearchTab.ARTIST -> search.artist?.let { artist ->
                item(key = "artist") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp)
                            .then(if (artist.artistId != null) Modifier.clickable(onClickLabel = "Abrir ${artist.name}") { artist.artistId?.let(onArtist) } else Modifier)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        AsyncImage(
                            model = artist.thumbnail?.highRes(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(scheme.surfaceContainerHigh),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = artist.name,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "En el top de ${artist.hits.size} ${if (artist.hits.size == 1) "país" else "países"} hoy",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        artist.hits.firstOrNull()?.let { best ->
                            FilledTonalButton(onClick = { onRadio(best.bestSong) }) {
                                Icon(painterResource(R.drawable.radio), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Radio")
                            }
                        }
                    }
                }
                item(key = "artist_label") {
                    SectionLabel("Dónde se escucha más") {
                        onShowOnMap(artist.hits.map { it.country.code }.toSet())
                    }
                }
                val maxScore = artist.hits.maxOfOrNull { score(it) } ?: 1
                artist.hits.forEach { hit ->
                    item(key = "ah_${hit.country.code}") {
                        CountryHitRow(
                            hit = hit,
                            detail = buildString {
                                append(if (hit.count == 1) "1 canción en el top" else "${hit.count} canciones en el top")
                                append(", mejor: ")
                                append(hit.bestSong.title)
                            },
                            fraction = score(hit).toFloat() / maxScore,
                            onClick = { onFocusCountry(hit.country) },
                        )
                    }
                }
            }

            SearchTab.SONGS -> search.songs.forEach { result ->
                item(key = "sr_${result.song.id}") {
                    val best = result.hits.first()
                    val isCurrent = result.song.id == currentSongId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clickable { onPlaySong(result.song) }
                            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    ) {
                        AsyncImage(
                            model = result.song.thumbnail.highRes(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(scheme.surfaceContainerHigh),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = result.song.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isCurrent) scheme.primary else scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${result.song.artists.joinToString { it.name }}, en ${result.hits.size} " +
                                    (if (result.hits.size == 1) "país" else "países") +
                                    ", mejor #${best.bestRank} en ${best.country.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onShowOnMap(result.hits.map { it.country.code }.toSet()) }) {
                            Icon(painterResource(R.drawable.language), contentDescription = "Ver ${result.song.title} en el mapa")
                        }
                    }
                }
            }

            SearchTab.COUNTRIES -> search.countries.forEach { chart ->
                item(key = "sc_${chart.country.code}") {
                    val top = chart.songs.firstOrNull()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(onClickLabel = "Ver el top de ${chart.country.name}") { onFocusCountry(chart.country) }
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                    ) {
                        Text(flagEmoji(chart.country.code), fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics { })
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(chart.country.name, style = MaterialTheme.typography.titleSmall)
                            if (top != null) {
                                Text(
                                    text = "N.º 1: ${top.title}, ${top.artists.joinToString { it.name }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "note") {
            Row(
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    painterResource(R.drawable.info),
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Posición en los tops diarios de YouTube Music de $countriesLoaded países. No son reproducciones exactas.",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Más arriba y más canciones en el top = barra más larga. */
private fun score(hit: CountryHit): Int = (51 - hit.bestRank).coerceAtLeast(1) + (hit.count - 1) * 3

@Composable
private fun SectionLabel(text: String, onShowOnMap: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        TextButton(onClick = onShowOnMap) { Text("Ver en el mapa") }
    }
}

@Composable
private fun CountryHitRow(hit: CountryHit, detail: String, fraction: Float, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = "Ver el top de ${hit.country.name}", onClick = onClick)
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text(flagEmoji(hit.country.code), fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics { })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.country.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(scheme.surfaceContainerHighest)
                    .clearAndSetSemantics { },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0.04f, 1f))
                        .fillMaxHeight()
                        .background(scheme.primary),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "#${hit.bestRank}",
                style = MaterialTheme.typography.titleLarge,
                color = scheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text("mejor", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}
