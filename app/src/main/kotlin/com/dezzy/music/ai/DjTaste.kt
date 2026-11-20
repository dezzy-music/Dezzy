/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ai

import android.content.Context
import com.dezzy.music.db.MusicDatabase
import com.dezzy.music.db.entities.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/** Normaliza textos para comparar artistas y títulos sin tildes, mayúsculas ni espacios de más. */
internal object DjText {
    private val MARKS = Regex("\\p{M}+")
    private val ARTIST_SPLIT =
        Regex("""\s*(?:,|&|·|/|\bfeat\.?|\bft\.?|\bfeaturing\b|\bx\b|\by\b|\bcon\b)\s*""", RegexOption.IGNORE_CASE)

    /** «(Official Video)», «[Lyrics]», «- Remastered 2011»… para reconocer la misma canción en otro vídeo. */
    private val TITLE_NOISE =
        Regex("""\s*[(\[][^)\]]*[)\]]|\s+-\s+.*$""")

    fun normalize(text: String): String =
        MARKS.replace(Normalizer.normalize(text.lowercase().trim(), Normalizer.Form.NFD), "")

    fun artistKeys(artist: String?): Set<String> =
        artist.orEmpty().split(ARTIST_SPLIT).map(::normalize).filter { it.length > 1 }.toSet()

    fun titleKey(title: String?): String =
        normalize(TITLE_NOISE.replace(title.orEmpty(), "")).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** «la misma canción» aunque sea otro videoId: título limpio + artista principal. */
    fun songKey(title: String?, artist: String?): String =
        titleKey(title) + "|" + (artistKeys(artist).firstOrNull() ?: "")

    /**
     * Versiones que casi nunca son lo que el oyente quiere en una sesión: karaoke, instrumentales,
     * en 8D, slowed/sped up, nightcore, «1 hora», reacciones, tutoriales, tonos…
     * Sólo se descartan si el oyente no suele escuchar ese tipo de versión (ver [TasteProfile.likesVariant]).
     */
    private val JUNK = Regex(
        """\b(karaoke|instrumental|8d|slowed|sped ?up|nightcore|1 ?hora|1 ?hour|10 ?hours?|""" +
            """reaction|reaccion|tutorial|cover|ringtone|tono de llamada|bass ?boosted|tiktok version)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun variantOf(title: String?): String? = JUNK.find(normalize(title.orEmpty()))?.value?.lowercase()
}

/**
 * Lo que se salta el oyente. El historial normal sólo guarda escuchas de 30 s o más, así que los
 * saltos no dejaban rastro: el DJ volvía a poner justo lo que acababas de quitar.
 * Se guarda en un JSON pequeño (sin tocar la base de datos ni migraciones).
 */
object DjSkipMemory {
    private const val FILE = "dj_skips.json"
    private const val MAX_SONGS = 3000
    private val lock = Mutex()

    data class Skip(val count: Int, val lastAt: Long)

    @Volatile private var cache: MutableMap<String, Skip>? = null

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun read(context: Context): MutableMap<String, Skip> {
        cache?.let { return it }
        val map = HashMap<String, Skip>()
        runCatching {
            val json = JSONObject(file(context).takeIf { it.exists() }?.readText() ?: "{}")
            json.keys().forEach { key ->
                val o = json.getJSONObject(key)
                map[key] = Skip(o.optInt("c"), o.optLong("t"))
            }
        }
        cache = map
        return map
    }

    private fun write(context: Context, map: Map<String, Skip>) {
        val json = JSONObject()
        map.entries.sortedByDescending { it.value.lastAt }.take(MAX_SONGS).forEach { (k, v) ->
            json.put(k, JSONObject().put("c", v.count).put("t", v.lastAt))
        }
        runCatching { file(context).writeText(json.toString()) }
    }

    /** Clave por canción («s:<id>») y por artista («a:<nombre>») para castigar ambos. */
    suspend fun recordSkip(context: Context, songId: String, artist: String?) = withContext(Dispatchers.IO) {
        lock.withLock {
            val map = read(context)
            val now = System.currentTimeMillis()
            val keys = listOf("s:$songId") + DjText.artistKeys(artist).map { "a:$it" }
            keys.forEach { k -> map[k] = Skip((map[k]?.count ?: 0) + 1, now) }
            write(context, map)
        }
    }

    suspend fun snapshot(context: Context): Map<String, Skip> = withContext(Dispatchers.IO) {
        lock.withLock { HashMap(read(context)) }
    }
}

/**
 * Perfil de gustos del oyente, sacado de su historial real (no sólo de la biblioteca):
 *  - Cada escucha pesa menos cuanto más vieja es (vida media de [HALF_LIFE_DAYS] días).
 *  - Las de esta franja horaria pesan aparte: el DJ pone lo que sueles oír a esta hora.
 *  - Me gusta suma; los saltos restan a la canción y a su artista.
 *  - [explorationRate]: cuánto descubre el oyente normalmente, para dosificar lo nuevo.
 */
class TasteProfile private constructor(
    val songs: Map<String, Song>,
    /** Afinidad total por canción (≈ escuchas completas recientes ponderadas). */
    val songScore: Map<String, Double>,
    /** Peso de las escuchas de los últimos 14 días. */
    val recentScore: Map<String, Double>,
    /** Peso de las escuchas de hace más de 60 días (para «recuerdos» de verdad). */
    val oldScore: Map<String, Double>,
    /** Peso de las escuchas en esta franja horaria (±2 h). */
    val hourScore: Map<String, Double>,
    val lastPlayed: Map<String, LocalDateTime>,
    val artistScore: Map<String, Double>,
    /** Veces que el oyente escuchó al artista (en el año), para contarlo en voz alta. */
    val artistPlays: Map<String, Int>,
    val liked: Set<String>,
    private val skips: Map<String, DjSkipMemory.Skip>,
    /** «título|artista» de todo lo que ya conoce, para no venderle como nueva otra versión. */
    val knownSongKeys: Set<String>,
    /** Tipos de versión que sí escucha (si oye «slowed», no se le quitan). */
    private val variants: Set<String>,
    val explorationRate: Double,
) {
    val isEmpty: Boolean get() = songScore.isEmpty()

    fun songSkips(id: String): Int = skips["s:$id"]?.count ?: 0

    /** Proporción de saltos del artista frente a sus escuchas (0 = nunca lo salta). */
    fun artistSkipRatio(keys: Set<String>): Double = keys.maxOfOrNull { key ->
        val s = skips["a:$key"]?.count ?: 0
        val p = artistPlays[key] ?: 0
        if (s + p == 0) 0.0 else s.toDouble() / (s + p)
    } ?: 0.0

    fun artistAffinity(keys: Set<String>): Double = keys.maxOfOrNull { artistScore[it] ?: 0.0 } ?: 0.0

    fun topArtistOf(keys: Set<String>): String? = keys.maxByOrNull { artistScore[it] ?: 0.0 }

    fun likesVariant(variant: String): Boolean = variant in variants

    fun knows(title: String?, artist: String?): Boolean = DjText.songKey(title, artist) in knownSongKeys

    companion object {
        private const val HALF_LIFE_DAYS = 30.0
        private const val TYPICAL_PLAY_MS = 180_000.0

        suspend fun build(database: MusicDatabase, context: Context): TasteProfile = withContext(Dispatchers.IO) {
            val now = LocalDateTime.now()
            val from = now.minusDays(365).toInstant(ZoneOffset.UTC).toEpochMilli()
            val events = runCatching { database.tasteEvents(from) }.getOrDefault(emptyList())
            val likedSongs = runCatching { database.likedSongsByCreateDateAsc().first() }.getOrDefault(emptyList())
            val skips = runCatching { DjSkipMemory.snapshot(context) }.getOrDefault(emptyMap())

            val songScore = HashMap<String, Double>()
            val recent = HashMap<String, Double>()
            val old = HashMap<String, Double>()
            val hour = HashMap<String, Double>()
            val last = HashMap<String, LocalDateTime>()
            val first = HashMap<String, LocalDateTime>()
            val nowHour = now.hour
            for (e in events) {
                val ageDays = Duration.between(e.timestamp, now).toHours() / 24.0
                val plays = min(e.playTime, 360_000L) / TYPICAL_PLAY_MS
                val w = plays * 0.5.pow(ageDays / HALF_LIFE_DAYS)
                songScore.merge(e.songId, w, Double::plus)
                if (ageDays <= 14) recent.merge(e.songId, plays, Double::plus)
                if (ageDays > 60) old.merge(e.songId, plays, Double::plus)
                val dh = abs(e.timestamp.hour - nowHour).let { min(it, 24 - it) }
                if (dh <= 2) hour.merge(e.songId, plays * 0.5.pow(ageDays / 90.0), Double::plus)
                if (last[e.songId]?.isBefore(e.timestamp) != false) last[e.songId] = e.timestamp
                if (first[e.songId]?.isAfter(e.timestamp) != false) first[e.songId] = e.timestamp
            }
            val likedIds = likedSongs.mapTo(HashSet()) { it.id }
            likedIds.forEach { songScore.merge(it, 2.5, Double::plus) }

            val ids = (songScore.keys + likedIds).toList()
            val songs = HashMap<String, Song>()
            likedSongs.forEach { songs[it.id] = it }
            ids.filter { it !in songs }.chunked(500).forEach { chunk ->
                runCatching { database.getSongsByIds(chunk) }.getOrNull()?.forEach { songs[it.id] = it }
            }

            val artistScore = HashMap<String, Double>()
            val artistPlays = HashMap<String, Int>()
            val playsPerSong = events.groupingBy { it.songId }.eachCount()
            val known = HashSet<String>()
            val variants = HashMap<String, Int>()
            songs.values.forEach { song ->
                val artist = song.artists.joinToString { it.name }
                known += DjText.songKey(song.song.title, artist)
                DjText.variantOf(song.song.title)?.let { v -> variants.merge(v, playsPerSong[song.id] ?: 1, Int::plus) }
                val score = songScore[song.id] ?: 0.0
                DjText.artistKeys(artist).forEach { key ->
                    artistScore.merge(key, score, Double::plus)
                    artistPlays.merge(key, playsPerSong[song.id] ?: 0, Int::plus)
                }
            }

            // Cuánto descubre: escuchas del último mes a canciones que conoció ese mismo mes.
            val monthAgo = now.minusDays(30)
            val monthEvents = events.filter { it.timestamp.isAfter(monthAgo) }
            val fresh = monthEvents.count { first[it.songId]?.isAfter(monthAgo) == true }
            val exploration = if (monthEvents.size < 20) 0.35 else (fresh.toDouble() / monthEvents.size).coerceIn(0.2, 0.55)

            TasteProfile(
                songs = songs,
                songScore = songScore,
                recentScore = recent,
                oldScore = old,
                hourScore = hour,
                lastPlayed = last,
                artistScore = artistScore,
                artistPlays = artistPlays,
                liked = likedIds,
                skips = skips,
                knownSongKeys = known,
                // Un tipo de versión «cuenta» como gusto si suma 5 escuchas o más.
                variants = variants.filterValues { it >= 5 }.keys,
                explorationRate = exploration,
            )
        }

        /** log suave para que un artista escuchadísimo no aplaste todo lo demás. */
        internal fun soft(x: Double): Double = ln(1.0 + x.coerceAtLeast(0.0))
    }
}
