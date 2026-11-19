/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.musicmap

import android.content.Context
import android.content.SharedPreferences
import com.dezzy.music.spotify.Spotify
import com.dezzy.music.spotify.SpotifyFeatures
import com.dezzy.music.spotify.SpotifyTokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Estadísticas de ranking de CUALQUIER canción (no sólo las del top de hoy).
 *
 *  1. Spotify sólo sirve para encontrar el id de la canción. Hace falta sesión de Spotify
 *     (la búsqueda de Spotify no funciona con tokens anónimos).
 *  2. kworb.net/spotify/track/<id>.html da, para cada país donde la canción entró en la lista
 *     de Spotify (top 200), los streams totales, el mejor puesto y el historial semana a semana.
 *     La tabla diaria da los puestos de los últimos días («Ahora mismo»).
 *     Un 404 de kworb significa que la canción nunca entró en ninguna lista: no hay ranking
 *     y no se inventa nada.
 *
 * Todo se guarda en caché local: el id de Spotify 30 días y las estadísticas 12 h.
 */
object SongChartStats {
    private const val TAG = "SongChartStats"
    private const val PREFS = "song_chart_stats"
    private const val STATS_TTL_MS = 12L * 60 * 60 * 1000
    private const val ID_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val MAX_CACHED_STATS = 40
    /** La última fila diaria cuenta como «ahora mismo» si no tiene más de estos días. */
    private const val TODAY_MAX_AGE_DAYS = 4L

    /** Código de la fila «Global» de kworb (igual que el chart mundial del mapa). */
    const val GLOBAL = "ZZ"

    data class WeekPoint(val date: String, val position: Int, val streams: Long)

    data class CountryStats(
        val code: String,
        val totalStreams: Long,
        val peak: Int,
        val peakStreams: Long,
        /** Semanas en la lista, de la más antigua a la más reciente. */
        val weeks: List<WeekPoint>,
    ) {
        val name: String get() = countryName(code)
    }

    /** Puestos del último día publicado en la lista diaria de Spotify. */
    data class Today(val date: String, val positions: List<Pair<String, Int>>)

    data class SongStats(
        val trackId: String,
        /** «Artista - Canción» tal como lo nombra kworb. */
        val label: String?,
        val countries: List<CountryStats>,
        val today: Today?,
    ) {
        val global: CountryStats? get() = countries.firstOrNull { it.code == GLOBAL }
        /** Países (sin el global) ordenados por streams. */
        val byCountry: List<CountryStats> get() = countries.filter { it.code != GLOBAL }.sortedByDescending { it.totalStreams }
        val bestPeak: Int? get() = countries.minOfOrNull { it.peak }
        val totalStreams: Long get() = global?.totalStreams ?: byCountry.sumOf { it.totalStreams }
    }

    sealed interface Result {
        data class Ok(val stats: SongStats) : Result
        /** La canción existe en Spotify pero nunca entró en ninguna lista por país. */
        data class NeverCharted(val trackId: String) : Result
        /** No hay sesión de Spotify (o ya no vale): hay que iniciarla para buscar la canción. */
        data object NoSpotifySession : Result
        data object NotFoundOnSpotify : Result
        data object NetworkError : Result
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: synchronized(this) {
            prefs ?: context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .also { prefs = it }
        }

    private val spanishNames = Locale("es")

    /** Nombre del país en español («MX» → «México»); «ZZ» es «Mundial». */
    fun countryName(code: String): String =
        if (code == GLOBAL) "Mundial"
        else Locale("", code).getDisplayCountry(spanishNames).ifBlank { code }

    /** ¿Hay sesión de Spotify guardada? Barato: no hace peticiones. */
    fun hasSpotifySession(context: Context): Boolean = SpotifyFeatures.hasSession(context)

    /** Estadísticas de una canción por título y artista (busca su id en Spotify). */
    suspend fun forSong(context: Context, title: String, artist: String): Result = withContext(Dispatchers.IO) {
        val prefs = prefs(context)
        val key = "id_" + normalize("$title|$artist")
        val cachedId = prefs.getString(key, null)
            ?.takeIf { System.currentTimeMillis() - prefs.getLong("${key}_at", 0L) < ID_TTL_MS }
        val trackId = cachedId ?: run {
            if (!SpotifyFeatures.hasSession(context)) return@withContext Result.NoSpotifySession
            if (!SpotifyTokenManager.ensureAuthenticated()) return@withContext Result.NoSpotifySession
            val found = findTrackId(title, artist) ?: return@withContext Result.NotFoundOnSpotify
            prefs.edit().putString(key, found).putLong("${key}_at", System.currentTimeMillis()).apply()
            found
        }
        forTrackId(context, trackId)
    }

    /** Estadísticas de un id de Spotify ya conocido. */
    suspend fun forTrackId(context: Context, trackId: String): Result = withContext(Dispatchers.IO) {
        val prefs = prefs(context)
        readCached(prefs, trackId)?.let { return@withContext it }
        val request = Request.Builder()
            .url("https://kworb.net/spotify/track/$trackId.html")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) DezzyMusic")
            .build()
        val result = try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> Result.NeverCharted(trackId)
                    !response.isSuccessful -> Result.NetworkError
                    else -> {
                        val stats = parse(response.body.string(), trackId)
                        if (stats.countries.isEmpty()) Result.NeverCharted(trackId) else Result.Ok(stats)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "kworb falló para %s", trackId)
            Result.NetworkError
        }
        if (result is Result.Ok || result is Result.NeverCharted) writeCached(prefs, trackId, result)
        result
    }

    // ── Búsqueda del id en Spotify ─────────────────────────────────────────────

    private suspend fun findTrackId(title: String, artist: String): String? {
        val wantTitle = normalize(cleanTitle(title))
        val wantArtists = artist.split(",", "&", " y ", " x ", " feat.", " ft.").map { normalize(it) }.filter { it.isNotBlank() }
        val candidates = SpotifyFeatures.searchTracks("$title $artist", limit = 8)
        val best = candidates.maxByOrNull { track ->
            val t = normalize(cleanTitle(track.name))
            val a = track.artists.map { normalize(it.name) }
            var score = 0
            if (t == wantTitle) score += 4 else if (t.contains(wantTitle) || wantTitle.contains(t)) score += 2
            if (a.any { it in wantArtists }) score += 3
            else if (a.any { name -> wantArtists.any { name.contains(it) || it.contains(name) } }) score += 1
            score
        }
        if (best != null) return best.id
        // Plan B: la búsqueda de sugerencias del reproductor web.
        return Spotify.searchTrackId("$title $artist")
    }

    private fun cleanTitle(title: String): String = title
        .replace(Regex("\\s*[(\\[][^)\\]]*(feat|ft\\.|with|remaster|version|versión|live|en vivo)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+-\\s+.*(remaster|version|live).*$", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    // ── Lectura de la página de kworb ──────────────────────────────────────────

    private val ROW = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("<td[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
    private val HEAD = Regex("<th[^>]*>(.*?)</th>", RegexOption.DOT_MATCHES_ALL)
    private val POS = Regex("class=\"p\">(\\d+)<")
    private val STREAMS = Regex("class=\"s\">([\\d,]+)<")
    private val DATE = Regex("^\\d{4}/\\d{2}/\\d{2}$")
    private val TITLE = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
    private val KWORB_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    private class Table(val codes: List<String>, val rows: List<List<String>>)

    private fun table(html: String, cls: String): Table? {
        val start = html.indexOf("<div class=\"$cls\">").takeIf { it >= 0 } ?: return null
        val end = html.indexOf("</table>", start).takeIf { it >= 0 } ?: return null
        val section = html.substring(start, end)
        val codes = HEAD.findAll(section).map { it.groupValues[1].trim() }.drop(1)
            .map { if (it.equals("Global", ignoreCase = true)) GLOBAL else it.uppercase(Locale.ROOT) }
            .toList()
        val rows = ROW.findAll(section).map { row -> CELL.findAll(row.groupValues[1]).map { it.groupValues[1] }.toList() }
            .filter { it.isNotEmpty() }
            .toList()
        return Table(codes, rows)
    }

    private fun number(s: String): Long = s.filter { it.isDigit() }.toLongOrNull() ?: 0L

    internal fun parse(html: String, trackId: String): SongStats {
        val label = TITLE.find(html)?.groupValues?.get(1)
            ?.substringBefore(" - Spotify Chart History")
            ?.replace("&amp;", "&")?.replace("&#39;", "'")?.replace("&quot;", "\"")
            ?.trim()
        val weekly = table(html, "weekly")
        val countries = mutableListOf<CountryStats>()
        if (weekly != null) {
            val totals = weekly.rows.firstOrNull { it.firstOrNull()?.trim() == "Total" }
            val peaks = weekly.rows.firstOrNull { it.firstOrNull()?.trim() == "Peak" }
            val weekRows = weekly.rows.filter { DATE.matches(it.firstOrNull()?.trim().orEmpty()) }
            weekly.codes.forEachIndexed { i, code ->
                val col = i + 1
                val weeks = weekRows.mapNotNull { row ->
                    val cell = row.getOrNull(col) ?: return@mapNotNull null
                    val pos = POS.find(cell)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                    WeekPoint(row[0].trim(), pos, STREAMS.find(cell)?.groupValues?.get(1)?.let(::number) ?: 0L)
                }.sortedBy { it.date }
                val peakCell = peaks?.getOrNull(col).orEmpty()
                val peak = POS.find(peakCell)?.groupValues?.get(1)?.toIntOrNull() ?: weeks.minOfOrNull { it.position }
                    ?: return@forEachIndexed
                val peakStreams = STREAMS.find(peakCell)?.groupValues?.get(1)?.let(::number) ?: 0L
                val total = totals?.getOrNull(col)?.let(::number)?.takeIf { it > 0 } ?: weeks.sumOf { it.streams }
                countries += CountryStats(code, total, peak, peakStreams, weeks)
            }
        }
        return SongStats(trackId, label, countries, today(html))
    }

    private fun today(html: String): Today? {
        val daily = table(html, "daily") ?: return null
        val last = daily.rows.lastOrNull { DATE.matches(it.firstOrNull()?.trim().orEmpty()) } ?: return null
        val date = last[0].trim()
        val age = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(date, KWORB_DATE), LocalDate.now()) }.getOrNull()
        if (age == null || age > TODAY_MAX_AGE_DAYS) return null
        val positions = daily.codes.mapIndexedNotNull { i, code ->
            POS.find(last.getOrNull(i + 1).orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.let { code to it }
        }.sortedBy { it.second }
        return if (positions.isEmpty()) null else Today(date, positions)
    }

    // ── Caché ──────────────────────────────────────────────────────────────────

    private fun readCached(prefs: SharedPreferences, trackId: String): Result? {
        val raw = prefs.getString("stats_$trackId", null) ?: return null
        if (System.currentTimeMillis() - prefs.getLong("stats_${trackId}_at", 0L) > STATS_TTL_MS) return null
        return runCatching {
            val o = JSONObject(raw)
            if (o.optBoolean("never")) return@runCatching Result.NeverCharted(trackId)
            val countries = o.getJSONArray("c").let { arr ->
                (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i)
                    val w = c.getJSONArray("w")
                    CountryStats(
                        code = c.getString("k"),
                        totalStreams = c.getLong("t"),
                        peak = c.getInt("p"),
                        peakStreams = c.optLong("ps"),
                        weeks = (0 until w.length()).map { j ->
                            val p = w.getJSONArray(j)
                            WeekPoint(p.getString(0), p.getInt(1), p.getLong(2))
                        },
                    )
                }
            }
            val today = o.optJSONObject("d")?.let { d ->
                val ps = d.getJSONArray("p")
                Today(d.getString("date"), (0 until ps.length()).map { ps.getJSONArray(it).let { p -> p.getString(0) to p.getInt(1) } })
            }
            Result.Ok(SongStats(trackId, o.optString("l").ifBlank { null }, countries, today))
        }.getOrNull()
    }

    private fun writeCached(prefs: SharedPreferences, trackId: String, result: Result) {
        val o = JSONObject()
        when (result) {
            is Result.NeverCharted -> o.put("never", true)
            is Result.Ok -> {
                val s = result.stats
                o.put("l", s.label.orEmpty())
                o.put("c", JSONArray().apply {
                    s.countries.forEach { c ->
                        put(JSONObject()
                            .put("k", c.code).put("t", c.totalStreams).put("p", c.peak).put("ps", c.peakStreams)
                            .put("w", JSONArray().apply { c.weeks.forEach { put(JSONArray().put(it.date).put(it.position).put(it.streams)) } }))
                    }
                })
                s.today?.let { t ->
                    o.put("d", JSONObject().put("date", t.date).put("p", JSONArray().apply {
                        t.positions.forEach { put(JSONArray().put(it.first).put(it.second)) }
                    }))
                }
            }
            else -> return
        }
        // Lista de ids guardados, de más antiguo a más nuevo, para no crecer sin límite.
        val order = prefs.getString("order", "").orEmpty().split(",").filter { it.isNotBlank() && it != trackId } + trackId
        val edit = prefs.edit()
        order.dropLast(MAX_CACHED_STATS).forEach { old -> edit.remove("stats_$old").remove("stats_${old}_at") }
        edit.putString("order", order.takeLast(MAX_CACHED_STATS).joinToString(","))
            .putString("stats_$trackId", o.toString())
            .putLong("stats_${trackId}_at", System.currentTimeMillis())
            .apply()
    }
}
