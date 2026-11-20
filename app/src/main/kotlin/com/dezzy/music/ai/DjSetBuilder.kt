/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ai

import androidx.media3.common.MediaItem
import com.dezzy.music.ai.TasteProfile.Companion.soft
import com.dezzy.music.extensions.toMediaItem
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.models.WatchEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Arma las sesiones del DJ a partir de tus gustos reales, al estilo del DJ de Spotify.
 *
 * 1. Perfil ([TasteProfile]): todo tu historial del último año (no sólo la biblioteca), con más
 *    peso a lo reciente y a lo que sueles oír a esta hora; me gusta suman, saltos restan.
 * 2. Candidatas:
 *    - En repeat: lo que más escuchas estas semanas y a esta hora.
 *    - Recuerdos: lo que ponías mucho hace meses y ya no (calculado aquí, no con la consulta
 *      de la portada, que exigía escuchas recientes).
 *    - Nuevas de tus artistas: canciones que no conoces de artistas que sí escuchas.
 *    - Descubrimientos: la radio de varias favoritas, puntuada. Sube lo que aparece en la radio
 *      de varias semillas (encaja con tu gusto, no con una sola canción) y lo de artistas que te
 *      gustan; baja o se descarta lo que saltas, lo que ya conoces en otra versión y el relleno
 *      (karaoke, 8D, slowed, nightcore, «1 hora»…) salvo que tú escuches ese tipo de versión.
 * 3. Orden con sentido: abre con algo que te encanta ahora; cada favorita va seguida de su
 *    «racimo» de descubrimientos (la transición es natural porque salen de su radio) y se
 *    alternan nuevas de tus artistas y recuerdos. Lo nuevo se dosifica según cuánto descubres tú.
 * 4. Variedad: un artista no vuelve hasta [MIN_GAP] canciones después y como mucho
 *    [MAX_PER_ARTIST] por sesión (si lo pides tú, sí se repite, alternando).
 *
 * Cada canción lleva su [Pick.reason]: el DJ cuenta por qué viene con datos de verdad.
 * Durante la sesión el DJ se ajusta a lo que haces (ver [moreLike] y el monitor del ViewModel).
 */
object DjSetBuilder {
    enum class Kind { REQUEST, FAVORITES, THROWBACK, ARTIST_NEW, DISCOVERY }

    data class Pick(
        val item: MediaItem,
        val kind: Kind,
        val seed: String? = null,
        /** Motivo concreto para el DJ («la pones mucho a esta hora», «suena en la radio de 3 de tus favoritas»…). */
        val reason: String? = null,
    )

    /** Tramo de la cola con un mismo motivo. [start] es el índice de su primera canción. */
    data class Block(val kind: Kind, val start: Int, val size: Int, val seed: String?, val reason: String? = null)

    class DjSet(val picks: List<Pick>) {
        val items: List<MediaItem> = picks.map { it.item }
        val blocks: List<Block> = blocksOf(picks)
        fun blockStartingAt(index: Int): Block? = blocks.firstOrNull { it.start == index }
        fun blockAt(index: Int): Block? = blocks.lastOrNull { it.start <= index }

        companion object {
            val EMPTY = DjSet(emptyList())
        }
    }

    private const val MIN_GAP = 4
    private const val MAX_PER_ARTIST = 2
    private const val MAX_FOCUS = 8
    private const val SESSION_SIZE = 40

    fun artistKeys(item: MediaItem): Set<String> = DjText.artistKeys(item.mediaMetadata.artist?.toString())

    /**
     * Reordena y recorta [picks] para que ningún artista se repita antes de [minGap] canciones
     * ni pase de [maxPerArtist]. Mantiene el orden de entrada todo lo posible: si una canción no
     * cabe aún, se adelanta la siguiente que sí, y la otra espera su turno. Lo que no cabe se
     * descarta (mejor una sesión más corta que repetir).
     */
    fun diversify(
        picks: List<Pick>,
        minGap: Int = MIN_GAP,
        maxPerArtist: Int = MAX_PER_ARTIST,
        focus: Set<String> = emptySet(),
        limit: Int = SESSION_SIZE,
    ): List<Pick> {
        val pool = picks.distinctBy { it.item.mediaId }.toMutableList()
        val out = ArrayList<Pick>()
        val count = HashMap<String, Int>()
        val titles = HashSet<String>()
        while (out.size < limit && pool.isNotEmpty()) {
            val recent = out.takeLast(minGap).flatMapTo(HashSet()) { artistKeys(it.item) - focus }
            val lastFocus = out.lastOrNull()?.let { artistKeys(it.item) intersect focus }.orEmpty()
            val index = pool.indexOfFirst { pick ->
                val keys = artistKeys(pick.item)
                (keys - focus).none { it in recent } &&
                    (keys intersect focus).none { it in lastFocus } &&
                    keys.all { (count[it] ?: 0) < if (it in focus) MAX_FOCUS else maxPerArtist }
            }
            if (index < 0) break
            val pick = pool.removeAt(index)
            // La misma canción en otro vídeo (audio, videoclip, lyric video) no se repite.
            val key = DjText.songKey(pick.item.mediaMetadata.title?.toString(), pick.item.mediaMetadata.artist?.toString())
            if (!titles.add(key)) continue
            artistKeys(pick.item).forEach { count[it] = (count[it] ?: 0) + 1 }
            out += pick
        }
        return out
    }

    /** Junta canciones seguidas del mismo motivo en bloques; los sueltos se unen al anterior. */
    private fun blocksOf(picks: List<Pick>): List<Block> {
        val blocks = ArrayList<Block>()
        picks.forEachIndexed { index, pick ->
            val last = blocks.lastOrNull()
            val sameRun = last != null && last.kind == pick.kind && (pick.kind != Kind.DISCOVERY || last.seed == pick.seed)
            val orphan = last != null && picks.getOrNull(index + 1)?.let { it.kind != pick.kind } ?: true
            if (last != null && (sameRun || orphan)) {
                blocks[blocks.lastIndex] = last.copy(size = last.size + 1)
            } else {
                blocks += Block(pick.kind, index, 1, pick.seed, pick.reason)
            }
        }
        return blocks
    }

    // ── Puntuación ───────────────────────────────────────────────────────────────

    /** Lo que sale de la radio de una semilla, con su posición (cuanto antes, más parecida). */
    private class Candidate(val song: SongItem) {
        var hits = 0
        var bestPos = Int.MAX_VALUE
        val seeds = LinkedHashMap<String, Int>()
    }

    private suspend fun radioOf(id: String): List<SongItem> =
        runCatching { YouTube.next(WatchEndpoint(videoId = id)).getOrNull()?.items.orEmpty() }
            .getOrDefault(emptyList())
            .filterIsInstance<SongItem>()

    /**
     * Junta las radios de varias semillas y puntúa cada canción contra el perfil. Devuelve sólo
     * lo que pasa los filtros, de mejor a peor. [exclude]: ids que ya están en la sesión.
     */
    private fun scoreRadios(
        radios: List<Pair<MediaItem, List<SongItem>>>,
        profile: TasteProfile?,
        exclude: Set<String>,
        newOnly: Boolean,
    ): List<Pick> {
        val byId = LinkedHashMap<String, Candidate>()
        for ((seed, list) in radios) {
            val seedTitle = seed.mediaMetadata.title?.toString().orEmpty()
            list.forEachIndexed { pos, song ->
                if (song.id == seed.mediaId || song.id in exclude) return@forEachIndexed
                val c = byId.getOrPut(song.id) { Candidate(song) }
                c.hits++
                c.bestPos = minOf(c.bestPos, pos)
                c.seeds[seedTitle] = minOf(c.seeds[seedTitle] ?: Int.MAX_VALUE, pos)
            }
        }
        val scored = byId.values.mapNotNull { c ->
            val artist = c.song.artists.joinToString { it.name }
            val keys = DjText.artistKeys(artist)
            val variant = DjText.variantOf(c.song.title)
            if (profile == null) {
                if (variant != null) return@mapNotNull null
                return@mapNotNull c to (c.hits * 1.5 + 1.0 / (1 + c.bestPos / 6.0))
            }
            // Ya la conoces (aunque sea en otro vídeo): no es un descubrimiento.
            if (newOnly && ((profile.songScore[c.song.id] ?: 0.0) > 0.3 || profile.knows(c.song.title, artist))) return@mapNotNull null
            if (profile.songSkips(c.song.id) >= 2) return@mapNotNull null
            val skipRatio = profile.artistSkipRatio(keys)
            if (skipRatio > 0.6) return@mapNotNull null
            var score = 1.6 * (c.hits - 1) + 1.0 / (1 + c.bestPos / 6.0) + 1.1 * soft(profile.artistAffinity(keys))
            score -= 4.0 * skipRatio + 1.5 * profile.songSkips(c.song.id)
            if (variant != null && !profile.likesVariant(variant)) score -= 4.0
            val seconds = c.song.duration ?: 200
            if (seconds < 75 || seconds > 480) score -= 1.5
            if (score < -1.0) null else c to score
        }
        return scored.sortedByDescending { it.second }.map { (c, _) ->
            val artist = c.song.artists.joinToString { it.name }
            val keys = DjText.artistKeys(artist)
            val seed = c.seeds.minByOrNull { it.value }?.key
            val knownArtist = profile != null && profile.artistAffinity(keys) >= 1.0
            if (knownArtist) {
                val name = c.song.artists.firstOrNull()?.name ?: artist
                val plays = profile!!.topArtistOf(keys)?.let { profile.artistPlays[it] } ?: 0
                Pick(
                    c.song.toMediaItem(), Kind.ARTIST_NEW, seed,
                    "Es de $name, a quien escucha mucho" + (if (plays >= 5) " ($plays veces este año)" else "") + ", y todavía no conoce esta.",
                )
            } else {
                val why = when {
                    c.hits >= 3 -> "Aparece en la radio de ${c.hits} de sus favoritas a la vez: encaja con su gusto."
                    c.hits == 2 -> "Sale en la radio de dos de sus favoritas" + (seed?.let { ", entre ellas «$it»" } ?: "") + "."
                    else -> seed?.let { "Suena parecida a «$it», que le encanta." }
                }
                Pick(c.song.toMediaItem(), Kind.DISCOVERY, seed, why)
            }
        }
    }

    /** Orden «con ruido»: respeta la puntuación pero cada sesión sale distinta. */
    private fun <T> noisy(items: List<T>, score: (T) -> Double): List<T> =
        items.map { it to score(it) * Random.nextDouble(0.75, 1.25) }.sortedByDescending { it.second }.map { it.first }

    // ── Sesión personal ─────────────────────────────────────────────────────────

    /**
     * Sesión personal sin petición. Vacía si todavía no hay historial (entonces el DJ usa la
     * búsqueda de siempre).
     */
    suspend fun buildSession(profile: TasteProfile): DjSet = withContext(Dispatchers.IO) {
        if (profile.isEmpty) return@withContext DjSet.EMPTY
        val now = LocalDateTime.now()

        fun daysSince(id: String) = profile.lastPlayed[id]?.let { Duration.between(it, now).toHours() / 24.0 } ?: 999.0

        // En repeat: reciente + esta hora; nada de lo que sonó en las últimas 3 horas ni lo que saltas.
        val heavyIds = profile.songs.keys.filter { id ->
            profile.songSkips(id) < 2 && daysSince(id) > 0.125 &&
                ((profile.recentScore[id] ?: 0.0) >= 1.0 || (profile.hourScore[id] ?: 0.0) >= 1.5 || id in profile.liked)
        }
        val heavy = noisy(heavyIds) { id ->
            (profile.recentScore[id] ?: 0.0) + 0.8 * (profile.hourScore[id] ?: 0.0) + 0.3 * (profile.songScore[id] ?: 0.0)
        }.take(30).mapNotNull { id ->
            val song = profile.songs[id] ?: return@mapNotNull null
            val recent = (profile.recentScore[id] ?: 0.0).roundToInt()
            val atThisHour = (profile.hourScore[id] ?: 0.0) >= 2.0
            val why = when {
                atThisHour -> "Es de las que más pone a esta hora del día."
                recent >= 5 -> "La ha puesto $recent veces en las últimas dos semanas."
                id in profile.liked -> "Le dio me gusta y la sigue escuchando."
                else -> "Es de sus favoritas de estos días."
            }
            Pick(song.toMediaItem(), Kind.FAVORITES, reason = why)
        }
        if (heavy.isEmpty()) return@withContext DjSet.EMPTY

        // Recuerdos de verdad: mucho antes, casi nada ahora.
        val throwbackIds = profile.songs.keys.filter { id ->
            val old = profile.oldScore[id] ?: 0.0
            profile.songSkips(id) < 2 && daysSince(id) > 45 &&
                (profile.recentScore[id] ?: 0.0) < 0.5 && (old >= 2.0 || id in profile.liked)
        }
        val throwback = noisy(throwbackIds) { id ->
            (profile.oldScore[id] ?: 0.0) + if (id in profile.liked) 3.0 else 0.0
        }.take(20).mapNotNull { id ->
            val song = profile.songs[id] ?: return@mapNotNull null
            val months = (daysSince(id) / 30).roundToInt().coerceAtLeast(1)
            val why = if (months >= 12) "La ponía mucho y no la escucha desde hace más de un año."
            else "La ponía mucho y lleva $months ${if (months == 1) "mes" else "meses"} sin escucharla."
            Pick(song.toMediaItem(), Kind.THROWBACK, reason = why)
        }

        // Semillas: 5 favoritas de artistas distintos; la primera abre la sesión.
        val seeds = diversify(heavy, minGap = 5, maxPerArtist = 1, limit = 5)
        val inSession = (heavy + throwback).mapTo(HashSet()) { it.item.mediaId }
        val radios = coroutineScope {
            seeds.map { seed -> async { seed.item to radioOf(seed.item.mediaId) } }.awaitAll()
        }
        val fresh = scoreRadios(radios, profile, inSession, newOnly = true)
        val artistNew = ArrayDeque(fresh.filter { it.kind == Kind.ARTIST_NEW })
        // Racimos por semilla: cada favorita va seguida de lo que salió de su radio.
        val clusters = fresh.filter { it.kind == Kind.DISCOVERY }.groupBy { it.seed }
            .mapValues { ArrayDeque(it.value) }.toMutableMap()

        // Dosis de lo nuevo según cuánto descubres tú (entre 1 de cada 5 y algo más de la mitad).
        val newTarget = (SESSION_SIZE * profile.explorationRate).roundToInt()
        val heavyQ = ArrayDeque(heavy.filter { p -> seeds.none { it.item.mediaId == p.item.mediaId } })
        val throwQ = ArrayDeque(throwback)
        val ordered = ArrayList<Pick>()
        var newCount = 0
        var round = 0
        while (ordered.size < SESSION_SIZE * 2 && (heavyQ.isNotEmpty() || seeds.size > round || clusters.values.any { it.isNotEmpty() })) {
            val seed = seeds.getOrNull(round)
            // Favoritas: la semilla y otras dos.
            if (seed != null) ordered += seed
            repeat(if (seed != null) 2 else 3) { heavyQ.removeFirstOrNull()?.let { ordered += it } }
            // Su racimo de descubrimientos (o el más grande que quede).
            val clusterKey = seed?.item?.mediaMetadata?.title?.toString()
            val cluster = clusters[clusterKey]?.takeIf { it.isNotEmpty() } ?: clusters.values.maxByOrNull { it.size }
            val wantNew = newCount < newTarget
            if (wantNew && cluster != null) repeat(3) { cluster.removeFirstOrNull()?.let { ordered += it; newCount++ } }
            // Alternan nuevas de tus artistas y recuerdos.
            if (round % 2 == 0 && artistNew.isNotEmpty() && newCount < newTarget) {
                repeat(2) { artistNew.removeFirstOrNull()?.let { ordered += it; newCount++ } }
            } else {
                repeat(3) { throwQ.removeFirstOrNull()?.let { ordered += it } }
            }
            round++
            if (round > 20) break
        }
        DjSet(diversify(ordered))
    }

    /**
     * Más como [seed] (te gustó o la escuchaste entera): su radio puntuada, para meterla en la
     * sesión justo después. Sin perfil, la radio tal cual sin relleno.
     */
    suspend fun moreLike(seed: MediaItem, profile: TasteProfile?, exclude: Set<String>, count: Int = 3): List<Pick> =
        withContext(Dispatchers.IO) {
            val title = seed.mediaMetadata.title?.toString().orEmpty()
            scoreRadios(listOf(seed to radioOf(seed.mediaId)), profile, exclude, newOnly = true)
                .take(count)
                .map { it.copy(kind = Kind.DISCOVERY, seed = title, reason = "Como le gustó «$title», el DJ trae más en esa línea.") }
        }

    // ── Petición ─────────────────────────────────────────────────────────────────

    /**
     * Sesión a partir de una petición («algo para entrenar», «pon Peso Pluma»). Los resultados se
     * reordenan por tu gusto (primero artistas que escuchas, fuera lo que saltas y el relleno) y
     * los descubrimientos salen de la radio de los dos resultados que más encajan contigo.
     * Si la petición nombra a un artista, ése puede repetirse (alternando con otros).
     */
    suspend fun buildFromRequest(query: String, results: List<SongItem>, profile: TasteProfile? = null): DjSet =
        withContext(Dispatchers.IO) {
            if (results.isEmpty()) return@withContext DjSet.EMPTY
            val asked = DjText.normalize(query)
            val n = results.size.toDouble()
            val ranked = results.withIndex().mapNotNull { (i, song) ->
                val artist = song.artists.joinToString { it.name }
                val keys = DjText.artistKeys(artist)
                val variant = DjText.variantOf(song.title)
                if (variant != null && profile?.likesVariant(variant) != true && !asked.contains(variant)) return@mapNotNull null
                var score = 2.0 * (n - i) / n
                if (profile != null) {
                    score += 1.2 * soft(profile.artistAffinity(keys)) - 3.0 * profile.artistSkipRatio(keys) -
                        2.0 * profile.songSkips(song.id)
                }
                song to score
            }.sortedByDescending { it.second }.map { it.first }.ifEmpty { results }

            val requested = ranked.map { Pick(it.toMediaItem(), Kind.REQUEST, reason = "Es lo que pidió.") }
            val focus = requested.flatMapTo(HashSet()) { artistKeys(it.item) }.filterTo(HashSet()) { it.length > 2 && asked.contains(it) }
            val exclude = results.mapTo(HashSet()) { it.id }
            val seeds = requested.take(2).map { it.item }
            val radios = coroutineScope { seeds.map { s -> async { s to radioOf(s.mediaId) } }.awaitAll() }
            // En una petición sí valen canciones que ya conoces: sólo se filtra el relleno y lo que saltas.
            val discovery = scoreRadios(radios, profile, exclude, newOnly = false)
                .map { it.copy(kind = Kind.DISCOVERY, reason = "Va con lo que pidió" + (it.seed?.let { s -> " y suena como «$s»" } ?: "") + ".") }
            val ordered = ArrayList<Pick>()
            val req = ArrayDeque(requested)
            val disc = ArrayDeque(discovery)
            while (req.isNotEmpty() || disc.isNotEmpty()) {
                repeat(4) { req.removeFirstOrNull()?.let { ordered += it } }
                repeat(3) { disc.removeFirstOrNull()?.let { ordered += it } }
            }
            val picked = diversify(ordered, minGap = 4, focus = focus, limit = 30)
            // Si la variedad dejó casi nada (búsqueda de un solo artista sin radio), mejor algo que nada.
            DjSet(picked.ifEmpty { requested.take(1) })
        }

    // ── Lo que dice el DJ ────────────────────────────────────────────────────────

    /** Lo que el DJ debe contar al empezar [block]. Se pasa a Gemini como contexto. */
    fun describe(block: Block, title: String, artist: String, first: Boolean): String {
        val why = when (block.kind) {
            Kind.FAVORITES -> "Ahora vienen canciones que el oyente tiene en repeat."
            Kind.THROWBACK -> "Ahora un regreso al pasado: canciones que el oyente escuchaba mucho y no pone desde hace tiempo."
            Kind.ARTIST_NEW -> "Ahora canciones nuevas para el oyente de artistas que ya le gustan."
            Kind.DISCOVERY -> "Ahora descubrimientos nuevos para el oyente" + (block.seed?.let { ", parecidos a «$it»" } ?: "") + "."
            Kind.REQUEST -> "Seguimos con lo que pidió el oyente."
        }
        val fact = block.reason?.let { " Dato real de su historial (úsalo con naturalidad, sin sonar a estadística): $it" }.orEmpty()
        val opening = if (first) "Preséntate por tu nombre y saluda en una frase. " else "No repitas tu nombre ni el saludo. "
        return "$opening$why$fact Empieza con «$title» de $artist. Cuenta en una frase por qué viene, con emoción y cercanía, " +
            "como un locutor que conoce al oyente. No repitas frases de antes. Máximo 2 frases. Haz una transición fluida."
    }

    /** Frase de respaldo cuando la IA no responde: también explica el motivo del bloque. */
    fun fallbackLine(block: Block?, title: String, artist: String, username: String, djName: String, first: Boolean): String {
        val hi = if (first) "¡Hola${username.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}! Soy $djName. " else ""
        val body = when (block?.kind) {
            Kind.FAVORITES -> listOf(
                "Empezamos con las que no te sacas de la cabeza: aquí va «$title» de $artist.",
                "Toca un bloque de tus favoritas. Arrancamos con $artist.",
            )
            Kind.THROWBACK -> listOf(
                "Vamos atrás en el tiempo: hace mucho que no ponías «$title».",
                "Un regreso al pasado con $artist. A ver si te acuerdas de ésta.",
            )
            Kind.ARTIST_NEW -> listOf(
                "Tú ya conoces a $artist, pero ésta todavía no: «$title».",
                "Algo de $artist que aún no habías escuchado. Creo que te va a gustar.",
            )
            Kind.DISCOVERY -> listOf(
                "Ahora algo nuevo para ti" + (block.seed?.let { ", parecido a «$it»" } ?: "") + ": «$title» de $artist.",
                "Te traigo descubrimientos. Empezamos con $artist, creo que te va a gustar.",
            )
            Kind.REQUEST, null -> listOf(
                "Va lo que pediste: «$title» de $artist.",
                "Seguimos con tu petición. Que suene $artist.",
            )
        }
        return hi + body.random()
    }
}
