/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.musicmap

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.musicmap.SongChartStats
import com.dezzy.music.musicmap.SongChartStats.CountryStats
import com.dezzy.music.musicmap.SongChartStats.SongStats
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.ui.component.CountryFlag
import com.dezzy.music.ui.utils.highRes
import java.util.Locale

/** Ruta de la pantalla de estadísticas de una canción. */
fun songStatsRoute(
    title: String,
    artist: String,
    thumbnail: String? = null,
    videoId: String? = null,
    artistId: String? = null,
): String = "song_stats?title=${Uri.encode(title)}&artist=${Uri.encode(artist)}" +
    "&thumb=${Uri.encode(thumbnail.orEmpty())}&videoId=${Uri.encode(videoId.orEmpty())}" +
    "&artistId=${Uri.encode(artistId.orEmpty())}"

const val SongStatsRoutePattern = "song_stats?title={title}&artist={artist}&thumb={thumb}&videoId={videoId}&artistId={artistId}"

/** Cuántos países se enseñan antes de «Ver todos». */
private const val COUNTRIES_PREVIEW = 5
/** Semanas que se dibujan en la gráfica (las más recientes). */
private const val CHART_WEEKS = 26

/**
 * Estadísticas de ranking de cualquier canción: países donde más se escucha (streams en las
 * listas de Spotify), mejor puesto de cada uno, evolución semanal y puestos de los últimos días.
 * Datos de kworb.net; Spotify sólo pone el id. Sin sesión de Spotify se pide iniciarla.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongStatsScreen(
    navController: NavController,
    title: String,
    artist: String,
    thumbnail: String?,
    videoId: String?,
    artistId: String?,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    var reload by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<SongChartStats.Result?>(null) }

    LaunchedEffect(title, artist, reload) {
        result = null
        result = SongChartStats.forSong(context, title, artist)
    }

    // Al volver de iniciar sesión con Spotify se reintenta solo.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && result == SongChartStats.Result.NoSpotifySession) reload++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val stats = (result as? SongChartStats.Result.Ok)?.stats
    val onRadio: (() -> Unit)? = videoId?.let { id ->
        {
            playerConnection?.playQueue(YouTubeQueue(endpoint = WatchEndpoint(videoId = id)))
            Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Estadísticas") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = "Atrás")
                    }
                },
                actions = {
                    if (stats != null) {
                        IconButton(onClick = { shareStats(context, title, artist, stats) }) {
                            Icon(painterResource(R.drawable.share), contentDescription = "Compartir estadísticas")
                        }
                    }
                },
            )
        },
    ) { inner ->
        val bottom = LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = 24.dp + bottom),
        ) {
            item(key = "header") {
                SongHeader(title, artist, thumbnail, stats)
            }
            when (val r = result) {
                null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is SongChartStats.Result.Ok -> statsItems(r.stats)
                is SongChartStats.Result.NeverCharted -> item(key = "never") {
                    EmptyStatsCard(
                        icon = R.drawable.stats,
                        title = "Todavía no ha entrado en ninguna lista",
                        body = "Las listas recogen el top 200 de Spotify de cada país. Esta canción no ha llegado a ninguno, así que no hay puestos que enseñar.",
                    ) {
                        if (!artistId.isNullOrBlank()) {
                            OutlinedButton(onClick = { navController.navigate("artist/$artistId") }) {
                                Icon(painterResource(R.drawable.person), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Ver el artista")
                            }
                        }
                        onRadio?.let { radio ->
                            FilledTonalButton(onClick = radio) {
                                Icon(painterResource(R.drawable.radio), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Radio")
                            }
                        }
                    }
                }
                SongChartStats.Result.NoSpotifySession -> item(key = "login") {
                    EmptyStatsCard(
                        icon = R.drawable.login,
                        title = "Sin sesión de Spotify",
                        body = "Para buscar las estadísticas tengo que encontrar la canción en Spotify. Inicia sesión una vez y listo: los rankings serán los reales de cada país.",
                    ) {
                        Button(onClick = { navController.navigate("settings/spotify/login") }) {
                            Icon(painterResource(R.drawable.link), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Conectar Spotify")
                        }
                    }
                }
                SongChartStats.Result.NotFoundOnSpotify -> item(key = "notfound") {
                    EmptyStatsCard(
                        icon = R.drawable.search,
                        title = "No encontré esta canción en Spotify",
                        body = "Sin la canción en Spotify no hay listas que consultar. Prueba con otra versión del tema.",
                    ) {}
                }
                SongChartStats.Result.NetworkError -> item(key = "error") {
                    EmptyStatsCard(
                        icon = R.drawable.info,
                        title = "No se pudieron cargar las estadísticas",
                        body = "Revisa la conexión e inténtalo de nuevo.",
                    ) {
                        Button(onClick = { reload++ }) { Text("Reintentar") }
                    }
                }
            }
        }
    }
}

// ── Contenido ────────────────────────────────────────────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.statsItems(stats: SongStats) {
    item(key = "kpis") { KpiRow(stats) }

    stats.today?.let { today ->
        item(key = "today_label") { SectionTitle("Ahora mismo en Spotify", "día ${shortDate(today.date)}") }
        item(key = "today") { TodayChips(today) }
    }

    item(key = "countries") { CountriesBlock(stats) }

    item(key = "source") {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Icon(
                painterResource(R.drawable.info),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Datos: listas de Spotify por país (kworb.net). Streams y puestos de las semanas que la canción estuvo en el top 200. Se actualizan una vez al día.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SongHeader(title: String, artist: String, thumbnail: String?, stats: SongStats?) {
    val scheme = MaterialTheme.colorScheme
    val since = stats?.countries?.flatMap { it.weeks }?.minOfOrNull { it.date }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AsyncImage(
            model = thumbnail?.takeIf { it.isNotBlank() }?.highRes(),
            contentDescription = null,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surfaceContainerHigh),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Text(artist, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (since != null) {
                Text(
                    "En las listas desde el ${longDate(since)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun KpiRow(stats: SongStats) {
    val countries = stats.byCountry
    // Mejor puesto en un país concreto (el global se cuenta aparte): el más alto y, a igualdad, el de más streams.
    val best = countries.minWithOrNull(compareBy<CountryStats> { it.peak }.thenByDescending { it.totalStreams })
    val weeksAtPeak = best?.weeks?.count { it.position == best.peak } ?: 0
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Kpi(formatStreams(stats.totalStreams), "streams en las listas", Modifier.weight(1f))
        Kpi(
            best?.let { "#${it.peak}" } ?: "—",
            best?.let { "en ${it.name}" + if (weeksAtPeak > 1) ", $weeksAtPeak semanas" else "" } ?: "sin puesto",
            Modifier.weight(1f),
        )
        Kpi("${countries.size}", if (countries.size == 1) "país con top" else "países con top", Modifier.weight(1f))
    }
}

@Composable
private fun Kpi(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, detail: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 24.dp, end = 12.dp, top = 12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        if (detail != null) {
            Spacer(Modifier.width(8.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun TodayChips(today: SongChartStats.Today) {
    val scheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        today.positions.forEach { (code, pos) ->
            Surface(
                shape = CircleShape,
                color = if (pos <= 3) scheme.primaryContainer else scheme.surfaceContainerHigh,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "Puesto $pos en ${SongChartStats.countryName(code)}"
                },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp),
                ) {
                    CountryFlag(code, height = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "#$pos",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (pos <= 3) scheme.onPrimaryContainer else scheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun CountriesBlock(stats: SongStats) {
    val countries = stats.byCountry.ifEmpty { listOfNotNull(stats.global) }
    var showAll by remember(stats.trackId) { mutableStateOf(false) }
    var selected by remember(stats.trackId) { mutableStateOf(countries.firstOrNull()?.code) }
    val maxStreams = countries.maxOfOrNull { it.totalStreams }?.coerceAtLeast(1) ?: 1

    Column {
        SectionTitle("Dónde más se escucha") {
            if (countries.size > COUNTRIES_PREVIEW) {
                TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Ver menos" else "Ver los ${countries.size}")
                }
            }
        }
        val shown = if (showAll) countries else countries.take(COUNTRIES_PREVIEW)
        shown.forEach { c ->
            CountryStatRow(
                stats = c,
                fraction = c.totalStreams.toFloat() / maxStreams,
                selected = c.code == selected,
                onClick = { selected = c.code },
            )
        }
        val chosen = countries.firstOrNull { it.code == selected }
        if (chosen != null && chosen.weeks.size >= 2) {
            SectionTitle("Semana a semana en ${chosen.name}")
            WeeklyChart(chosen)
        } else if (chosen != null && chosen.weeks.size == 1) {
            SectionTitle("Semana a semana en ${chosen.name}")
            Text(
                "Sólo estuvo una semana en la lista: #${chosen.weeks[0].position} el ${longDate(chosen.weeks[0].date)}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

@Composable
private fun CountryStatRow(stats: CountryStats, fraction: Float, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(if (selected) scheme.secondaryContainer.copy(alpha = 0.45f) else scheme.surface)
            .clickable(onClickLabel = "Ver la evolución en ${stats.name}", onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        CountryFlag(stats.code, height = 22.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stats.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(formatStreams(stats.totalStreams), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0.02f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                drawStopIndicator = {},
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "#${stats.peak}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (stats.peak <= 3) scheme.primary else scheme.onSurface,
            )
            Text("mejor", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

/** Puesto semana a semana: #1 arriba. Se dibujan las [CHART_WEEKS] semanas más recientes. */
@Composable
private fun WeeklyChart(stats: CountryStats) {
    val scheme = MaterialTheme.colorScheme
    val weeks = stats.weeks.takeLast(CHART_WEEKS)
    val best = weeks.minOf { it.position }
    val worst = weeks.maxOf { it.position }.coerceAtLeast(best + 1)
    val line = scheme.primary
    val grid = scheme.outlineVariant
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Gráfica de ${weeks.size} semanas en ${stats.name}: mejor puesto $best, peor $worst, " +
                    "última semana #${weeks.last().position}."
            },
    ) {
        Row {
            Column(
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
                modifier = Modifier.height(140.dp).width(40.dp).padding(end = 6.dp),
            ) {
                Text("#$best", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Text("#$worst", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
            Canvas(Modifier.weight(1f).height(140.dp)) {
                val stepX = if (weeks.size > 1) size.width / (weeks.size - 1) else 0f
                fun y(pos: Int) = (pos - best).toFloat() / (worst - best) * (size.height - 8.dp.toPx()) + 4.dp.toPx()
                drawLine(grid, Offset(0f, y(best)), Offset(size.width, y(best)), strokeWidth = 1.dp.toPx())
                drawLine(grid, Offset(0f, y(worst)), Offset(size.width, y(worst)), strokeWidth = 1.dp.toPx())
                val path = Path()
                weeks.forEachIndexed { i, w ->
                    val p = Offset(i * stepX, y(w.position))
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                weeks.forEachIndexed { i, w ->
                    if (w.position == best || i == weeks.lastIndex) {
                        drawCircle(line, radius = 4.dp.toPx(), center = Offset(i * stepX, y(w.position)))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 40.dp, top = 4.dp)) {
            Text(shortDate(weeks.first().date), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(shortDate(weeks.last().date), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun EmptyStatsCard(icon: Int, title: String, body: String, actions: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = scheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(56.dp).clip(CircleShape).background(scheme.secondaryContainer),
            ) {
                Icon(painterResource(icon), contentDescription = null, tint = scheme.onSecondaryContainer)
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

// ── Formatos ─────────────────────────────────────────────────────────────────

private val ES = Locale("es")
private val MONTHS = arrayOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

/** 56 400 000 → «56,4 M»; 885 000 → «885 mil». */
internal fun formatStreams(n: Long): String = when {
    n >= 1_000_000_000 -> String.format(ES, "%.1f mil M", n / 1_000_000_000.0)
    n >= 1_000_000 -> String.format(ES, "%.1f M", n / 1_000_000.0)
    n >= 1_000 -> "${n / 1_000} mil"
    else -> "$n"
}

/** «2026/10/15» → «15/oct/2026». */
private fun longDate(kworb: String): String {
    val p = kworb.split("/")
    if (p.size != 3) return kworb
    val m = p[1].toIntOrNull()?.let { MONTHS.getOrNull(it - 1) } ?: return kworb
    return "${p[2]}/$m/${p[0]}"
}

/** «2026/10/15» → «15/oct». */
private fun shortDate(kworb: String): String = longDate(kworb).substringBeforeLast("/")

private fun shareStats(context: android.content.Context, title: String, artist: String, stats: SongStats) {
    val top = stats.byCountry.take(5).joinToString("\n") { "• ${it.name}: ${formatStreams(it.totalStreams)} streams, mejor #${it.peak}" }
    val text = buildString {
        append("$title, $artist\n")
        append("${formatStreams(stats.totalStreams)} streams en las listas de Spotify, en ${stats.byCountry.size} países.\n")
        if (top.isNotEmpty()) append(top).append('\n')
        append("Visto en Dezzy")
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Compartir estadísticas"))
}
