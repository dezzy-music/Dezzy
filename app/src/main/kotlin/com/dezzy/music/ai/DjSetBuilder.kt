/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ai

import androidx.media3.common.MediaItem
import com.dezzy.music.db.MusicDatabase
import com.dezzy.music.extensions.toMediaItem
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.models.WatchEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * Arma las sesiones del DJ con criterio, al estilo del DJ de Spotify: la cola se reparte en
 * bloques con un motivo (tus favoritas, recuerdos, descubrimientos, lo que pediste) y el DJ
 * habla al empezar cada bloque contando por qué viene.
 *
 * Reglas de variedad:
 *  - Un artista no vuelve a sonar hasta que pasen [MIN_GAP] canciones de otros.
 *  - Como mucho [MAX_PER_ARTIST] canciones por artista en toda la sesión.
 *  - Si pides un artista concreto («pon Peso Pluma») ese artista sí se repite, pero alternando
 *    con artistas parecidos, nunca dos seguidas.
 *
 * Todo se decide en el teléfono con tu historial y la radio de YouTube Music: no hace falta
 * ninguna IA externa para elegir las canciones (la IA solo escribe lo que dice el DJ).
 */
object DjSetBuilder {
    enum class Kind { REQUEST, FAVORITES, THROWBACK, DISCOVERY }

    data class Pick(val item: MediaItem, val kind: Kind, val seed: String? = null)

    /** Tramo de la cola con un mismo motivo. [start] es el índice de su primera canción. */
    data class Block(val kind: Kind, val start: Int, val size: Int, val seed: String?)

    data class DjSet(val items: List<MediaItem>, val blocks: List<Block>) {
        fun blockStartingAt(index: Int): Block? = blocks.firstOrNull { it.start == index }
        fun blockAt(index: Int): Block? = blocks.lastOrNull { it.start <= index }
    }

    private const val MIN_GAP = 5
    private const val MAX_PER_ARTIST = 2
    private const val MAX_FOCUS = 8
    private const val SESSION_SIZE = 40

    /** Separa «A, B & C feat. D» en artistas sueltos. */
    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|·|/|\bfeat\.?|\bft\.?|\bfeaturing\b|\bx\b|\by\b|\bcon\b)\s*""", RegexOption.IGNORE_CASE)
    private val MARKS = Regex("\\p{M}+")

    private fun normalize(text: String): String =
        MARKS.replace(Normalizer.normalize(text.lowercase().trim(), Normalizer.Form.NFD), "")

    fun artistKeys(item: MediaItem): Set<String> =
        item.mediaMetadata.artist?.toString().orEmpty()
            .split(ARTIST_SPLIT)
            .map(::normalize)
            .filter { it.length > 1 }
            .toSet()

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
            artistKeys(pick.item).forEach { count[it] = (count[it] ?: 0) + 1 }
            out += pick
        }
        return out
    }

    /** Junta canciones seguidas del mismo motivo en bloques; los sueltos se unen al anterior. */
    private fun toDjSet(picks: List<Pick>): DjSet {
        val blocks = ArrayList<Block>()
        picks.forEachIndexed { index, pick ->
            val last = blocks.lastOrNull()
            if (last != null && (last.kind == pick.kind || picks.getOrNull(index + 1)?.kind != pick.kind)) {
                blocks[blocks.lastIndex] = last.copy(size = last.size + 1)
            } else {
                blocks += Block(pick.kind, index, 1, pick.seed)
            }
        }
        return DjSet(picks.map { it.item }, blocks)
    }

    /** Intercala las fuentes por tramos según [pattern] hasta que se agoten. */
    private fun interleave(sources: Map<Kind, ArrayDeque<Pick>>, pattern: List<Pair<Kind, Int>>): List<Pick> {
        val out = ArrayList<Pick>()
        var guard = 0
        while (sources.values.any { it.isNotEmpty() } && guard++ < 50) {
            for ((kind, size) in pattern) {
                val queue = sources[kind] ?: continue
                repeat(size) { queue.removeFirstOrNull()?.let(out::add) }
            }
        }
        return out
    }

    /** De cada semilla, tramos de 4 por turnos: así cada bloque de descubrimientos es «parecido a» una. */
    private fun chunkedRoundRobin(lists: List<List<Pick>>): List<Pick> {
        val chunks = lists.map { it.chunked(4) }
        val out = ArrayList<Pick>()
        for (i in 0 until (chunks.maxOfOrNull { it.size } ?: 0)) chunks.forEach { c -> c.getOrNull(i)?.let { out.addAll(it) } }
        return out
    }

    private suspend fun radio(seed: MediaItem, kind: Kind, exclude: Set<String>): List<Pick> =
        YouTube.next(WatchEndpoint(videoId = seed.mediaId)).getOrNull()?.items.orEmpty()
            .filter { it.id != seed.mediaId && it.id !in exclude }
            .map { Pick(it.toMediaItem(), kind, seed.mediaMetadata.title?.toString()) }

    /**
     * Sesión personal sin petición: favoritas → descubrimientos parecidos → recuerdos → …
     * Vacía si todavía no hay historial (entonces el DJ usa la búsqueda de siempre).
     */
    suspend fun buildSession(database: MusicDatabase): DjSet = withContext(Dispatchers.IO) {
        val library = database.songsByPlayTimeAsc().first().reversed()
        if (library.isEmpty()) return@withContext DjSet(emptyList(), emptyList())
        val libraryIds = library.mapTo(HashSet()) { it.id }

        // Favoritas: las 50 más escuchadas, barajadas para que cada sesión sea distinta.
        val favorites = library.take(50).shuffled().map { Pick(it.toMediaItem(), Kind.FAVORITES) }
        // Recuerdos: las que escuchabas mucho y llevas tiempo sin poner.
        val throwback = runCatching { database.forgottenFavorites().first() }.getOrDefault(emptyList())
            .shuffled().take(20).map { Pick(it.toMediaItem(), Kind.THROWBACK) }
        // Descubrimientos: radio de 3 favoritas de artistas distintos, sin lo que ya conoces.
        val seeds = diversify(favorites, minGap = 3, maxPerArtist = 1, limit = 3).map { it.item }
        val discovery = coroutineScope {
            seeds.map { seed -> async { runCatching { radio(seed, Kind.DISCOVERY, libraryIds) }.getOrDefault(emptyList()) } }
                .awaitAll()
        }.let(::chunkedRoundRobin)

        val ordered = interleave(
            mapOf(
                Kind.FAVORITES to ArrayDeque(favorites),
                Kind.DISCOVERY to ArrayDeque(discovery),
                Kind.THROWBACK to ArrayDeque(throwback),
            ),
            listOf(Kind.FAVORITES to 3, Kind.DISCOVERY to 4, Kind.THROWBACK to 3, Kind.DISCOVERY to 4),
        )
        toDjSet(diversify(ordered))
    }

    /**
     * Sesión a partir de una petición («algo para entrenar», «pon Peso Pluma»): resultados de la
     * búsqueda alternados con la radio del primero, sin repetir artista. Si la petición nombra a
     * un artista, ése puede repetirse (alternando con otros).
     */
    suspend fun buildFromRequest(query: String, results: List<SongItem>): DjSet = withContext(Dispatchers.IO) {
        if (results.isEmpty()) return@withContext DjSet(emptyList(), emptyList())
        val asked = normalize(query)
        val requested = results.map { Pick(it.toMediaItem(), Kind.REQUEST) }
        val focus = requested.flatMapTo(HashSet()) { artistKeys(it.item) }.filterTo(HashSet()) { it.length > 2 && asked.contains(it) }
        val exclude = results.mapTo(HashSet()) { it.id }
        val discovery = runCatching { radio(requested.first().item, Kind.DISCOVERY, exclude) }.getOrDefault(emptyList())
        val ordered = interleave(
            mapOf(Kind.REQUEST to ArrayDeque(requested), Kind.DISCOVERY to ArrayDeque(discovery)),
            listOf(Kind.REQUEST to 4, Kind.DISCOVERY to 3),
        )
        val picked = diversify(ordered, minGap = 4, focus = focus, limit = 30)
        // Si la variedad dejó casi nada (búsqueda de un solo artista sin radio), mejor algo que nada.
        toDjSet(picked.ifEmpty { requested.take(1) })
    }

    /** Lo que el DJ debe contar al empezar [block]. Se pasa a Gemini como contexto. */
    fun describe(block: Block, title: String, artist: String, first: Boolean): String {
        val why = when (block.kind) {
            Kind.FAVORITES -> "Ahora vienen canciones que el oyente tiene en repeat, de sus favoritas."
            Kind.THROWBACK -> "Ahora un regreso al pasado: canciones que el oyente escuchaba mucho y no pone desde hace tiempo."
            Kind.DISCOVERY -> "Ahora descubrimientos nuevos para el oyente" + (block.seed?.let { ", parecidos a «$it»" } ?: "") + "."
            Kind.REQUEST -> "Seguimos con lo que pidió el oyente."
        }
        val opening = if (first) "Preséntate por tu nombre y saluda en una frase. " else "No repitas tu nombre ni el saludo. "
        return "$opening$why Empieza con «$title» de $artist. Cuenta en una frase por qué viene este bloque. " +
            "No repitas frases de antes. Máximo 2 frases. Haz una transición fluida."
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
            Kind.DISCOVERY -> listOf(
                "Ahora algo nuevo para ti" + (block?.seed?.let { ", parecido a «$it»" } ?: "") + ": «$title» de $artist.",
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
