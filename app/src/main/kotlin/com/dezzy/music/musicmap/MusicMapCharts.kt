/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.musicmap

import android.content.Context
import android.content.SharedPreferences
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.Artist
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.models.YouTubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Charts por país para el Mapa musical.
 *
 * YouTube Music ya no devuelve canciones en `FEmusic_charts`: devuelve las playlists oficiales
 * de charts («Daily Top Music Videos - <país>», «Trending 20 <país>», …) y además ignora el
 * parámetro `gl`. El país se elige con `formData.selectedValues` (igual que el selector de la web).
 *
 * Sin servidor propio (ni Supabase): cada teléfono saca los charts directamente de YouTube Music.
 *  1. Caché local del dispositivo (6 h).
 *  2. Descarga en el dispositivo (playlist de chart del país), con concurrencia limitada.
 *     El id de la playlist de cada país se guarda 7 días, así que casi siempre es UNA
 *     petición por país y cada usuario reparte su propia carga.
 */
object MusicMapCharts {
    private const val TAG = "MusicMapCharts"
    private const val PREFS = "music_map_charts"
    private const val LOCAL_TTL_MS = 6L * 60 * 60 * 1000
    private const val PLAYLIST_ID_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val SONGS_PER_COUNTRY = 50
    /** Una foto del top al día como mucho: sirve para las flechas de tendencia. */
    private const val SNAPSHOT_MIN_AGE_MS = 20L * 60 * 60 * 1000
    private const val BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
    private const val CHARTS_PARAMS = "ggMGCgQIgAQ%3D"

    /** Código que YouTube Music usa para el chart mundial. */
    const val GLOBAL = "ZZ"

    private val JSON_TYPE = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: synchronized(this) {
            prefs ?: context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .also { prefs = it }
        }

    /**
     * Devuelve el top de cada país pedido. [onPartial] recibe resultados según van llegando
     * para que el mapa se pinte poco a poco. Los países sin datos no aparecen en el mapa.
     */
    suspend fun load(
        context: Context,
        countryCodes: List<String>,
        onPartial: suspend (Map<String, List<SongItem>>) -> Unit = {},
    ): Map<String, List<SongItem>> = withContext(Dispatchers.IO) {
        val prefs = prefs(context)
        val result = LinkedHashMap<String, List<SongItem>>()

        // 1. Caché local.
        countryCodes.forEach { code ->
            readLocal(prefs, code)?.let { result[code] = it }
        }
        if (result.isNotEmpty()) onPartial(LinkedHashMap(result))

        // 2. Descarga en el dispositivo para lo que falte.
        val missing = countryCodes.filter { it !in result }
        if (missing.isNotEmpty()) {
            val gate = Semaphore(4)
            coroutineScope {
                missing.map { code ->
                    async {
                        val songs = gate.withPermit {
                            runCatching { fetchCountry(prefs, code) }
                                .onFailure { Timber.tag(TAG).w(it, "Chart de %s falló", code) }
                                .getOrNull()
                        }.orEmpty()
                        if (songs.isNotEmpty()) {
                            writeLocal(prefs, code, songs)
                            val snapshot = synchronized(result) {
                                result[code] = songs
                                LinkedHashMap(result)
                            }
                            onPartial(snapshot)
                        }
                    }
                }.awaitAll()
            }
        }
        synchronized(result) { LinkedHashMap(result) }
    }

    // ---- 1. Caché local ----

    private fun readLocal(prefs: SharedPreferences, code: String): List<SongItem>? {
        val raw = prefs.getString("songs_$code", null) ?: return null
        val savedAt = prefs.getLong("at_$code", 0L)
        if (System.currentTimeMillis() - savedAt > LOCAL_TTL_MS) return null
        return runCatching { parseSongs(JSONArray(raw)) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun writeLocal(prefs: SharedPreferences, code: String, songs: List<SongItem>) {
        val now = System.currentTimeMillis()
        val edit = prefs.edit()
            .putString("songs_$code", songsToJson(songs).toString())
            .putLong("at_$code", now)
        // Guarda una foto del top como mucho una vez al día; la anterior queda para comparar.
        if (now - prefs.getLong("snap_at_$code", 0L) >= SNAPSHOT_MIN_AGE_MS) {
            prefs.getString("snap_ids_$code", null)?.let { edit.putString("prev_ids_$code", it) }
            edit.putString("snap_ids_$code", songs.joinToString(",") { it.id })
                .putLong("snap_at_$code", now)
        }
        edit.apply()
    }

    /**
     * Posiciones del top anterior (de hace un día o más): id de canción → puesto (1 = primero).
     * Vacío si aún no hay foto anterior.
     */
    fun previousRanks(context: Context, code: String): Map<String, Int> {
        val raw = prefs(context).getString("prev_ids_$code", null) ?: return emptyMap()
        return raw.split(',')
            .filter { it.isNotBlank() }
            .withIndex()
            .associate { (index, id) -> id to index + 1 }
    }

    // ---- 2. Descarga en el dispositivo ----

    private suspend fun fetchCountry(prefs: SharedPreferences, code: String): List<SongItem> {
        val playlistId = chartPlaylistId(prefs, code) ?: return emptyList()
        val page = YouTube.playlist(playlistId).getOrThrow()
        return page.songs
            .distinctBy { it.id }
            .take(SONGS_PER_COUNTRY)
    }

    /** Playlist oficial del chart del país (se guarda 7 días: los ids de chart no cambian). */
    private fun chartPlaylistId(prefs: SharedPreferences, code: String): String? {
        val cached = prefs.getString("pl_$code", null)
        val cachedAt = prefs.getLong("pl_at_$code", 0L)
        if (cached != null && System.currentTimeMillis() - cachedAt < PLAYLIST_ID_TTL_MS) return cached

        val ytClient = JSONObject()
            .put("clientName", YouTubeClient.WEB_REMIX.clientName)
            .put("clientVersion", YouTubeClient.WEB_REMIX.clientVersion)
            .put("hl", "en")
            .put("gl", if (code == GLOBAL) "US" else code)
        val body = JSONObject()
            .put("context", JSONObject().put("client", ytClient))
            .put("browseId", "FEmusic_charts")
            .put("params", CHARTS_PARAMS)
            .put("formData", JSONObject().put("selectedValues", JSONArray().put(code)))
        val request = Request.Builder()
            .url(BROWSE_URL)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .header("User-Agent", YouTubeClient.WEB_REMIX.userAgent)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .build()
        val json = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("charts HTTP ${response.code}")
            JSONObject(response.body.string())
        }

        val playlists = ArrayList<Pair<String, String>>()
        val sections = json.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
            ?: JSONArray()
        for (i in 0 until sections.length()) {
            val shelf = sections.optJSONObject(i)?.optJSONObject("musicCarouselShelfRenderer") ?: continue
            val items = shelf.optJSONArray("contents") ?: continue
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                val browse = item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: continue
                val pageType = browse.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")
                    ?.optString("pageType")
                if (pageType != "MUSIC_PAGE_TYPE_PLAYLIST") continue
                val title = item.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
                val id = browse.optString("browseId").removePrefix("VL")
                if (id.isNotBlank()) playlists += title.lowercase() to id
            }
        }

        // Si YouTube no tiene top de ese país devuelve el mundial: eso no vale para el país.
        if (code != GLOBAL) playlists.removeAll { it.first.contains("global") }

        // Preferencia: top diario → tendencias → top 100. Nunca directos ni géneros.
        val chosen = listOf(
            { t: String -> t.startsWith("daily top music videos") },
            { t: String -> t.startsWith("trending") },
            { t: String -> t.startsWith("top 100 music videos") },
        ).firstNotNullOfOrNull { matches -> playlists.firstOrNull { matches(it.first) }?.second }
            ?: playlists.firstOrNull { !it.first.contains("live") }?.second

        if (chosen != null) {
            prefs.edit().putString("pl_$code", chosen).putLong("pl_at_$code", System.currentTimeMillis()).apply()
        }
        return chosen ?: cached
    }

    // ---- JSON ----

    private fun parseSongs(array: JSONArray?): List<SongItem> {
        if (array == null) return emptyList()
        val out = ArrayList<SongItem>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
            val title = o.optString("title").takeIf { it.isNotBlank() } ?: continue
            val thumbnail = o.optString("thumbnail").takeIf { it.isNotBlank() }
                ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            val artistsJson = o.optJSONArray("artists") ?: JSONArray()
            val artists = (0 until artistsJson.length()).mapNotNull { k ->
                val a = artistsJson.optJSONObject(k) ?: return@mapNotNull null
                val name = a.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Artist(name = name, id = a.optString("id").takeIf { it.isNotBlank() })
            }
            out += SongItem(
                id = id,
                title = title,
                artists = artists,
                duration = o.optInt("duration", 0).takeIf { it > 0 },
                thumbnail = thumbnail,
            )
        }
        return out
    }

    private fun songsToJson(songs: List<SongItem>): JSONArray = JSONArray().apply {
        songs.forEach { song ->
            put(
                JSONObject()
                    .put("id", song.id)
                    .put("title", song.title)
                    .put("thumbnail", song.thumbnail)
                    .put("duration", song.duration ?: 0)
                    .put(
                        "artists",
                        JSONArray().apply {
                            song.artists.forEach { a ->
                                put(JSONObject().put("name", a.name).put("id", a.id ?: ""))
                            }
                        },
                    ),
            )
        }
    }
}
