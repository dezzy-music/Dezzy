/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.utils

import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.ArtistItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a representative popular artist image for a mood/genre card
 * (Spotify-style genre tiles). The genre titles come localized from YouTube's
 * explore feed, so matching is keyword-based over a normalized (lowercase,
 * accent-stripped) title. The artist photo itself is resolved once per session
 * through a YouTube artist search and cached in memory; Coil caches the actual
 * bitmap on disk.
 */
object GenreArtistImages {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lookups = ConcurrentHashMap<String, Deferred<String?>>()

    /** Ordered keyword → iconic artist mapping. First match wins. */
    private val CURATED: List<Pair<List<String>, String>> = listOf(
        listOf("reggaeton", "regueton", "urbano") to "Bad Bunny",
        listOf("k-pop", "kpop") to "BTS",
        listOf("hip-hop", "hip hop", "rap") to "Eminem",
        listOf("corrido", "mexican", "banda", "norten") to "Peso Pluma",
        listOf("cumbia") to "Los Angeles Azules",
        listOf("salsa") to "Marc Anthony",
        listOf("bachata") to "Romeo Santos",
        listOf("latin") to "Karol G",
        listOf("electro", "dance", "edm") to "David Guetta",
        listOf("r&b") to "The Weeknd",
        listOf("soul") to "Aretha Franklin",
        listOf("indie", "alternativ") to "Arctic Monkeys",
        listOf("metal") to "Metallica",
        listOf("punk") to "Green Day",
        listOf("rock") to "Queen",
        listOf("country") to "Johnny Cash",
        listOf("jazz") to "Louis Armstrong",
        listOf("clasic", "classical") to "Ludovico Einaudi",
        listOf("reggae", "caribe") to "Bob Marley",
        listOf("blues") to "B.B. King",
        listOf("folk", "acustic", "acoustic") to "Mumford & Sons",
        listOf("banda sonora", "soundtrack", "cine") to "Hans Zimmer",
        listOf("anime") to "LiSA",
        listOf("navidad", "christmas") to "Mariah Carey",
        listOf("fiesta", "party") to "Pitbull",
        listOf("entrenamiento", "workout", "ejercicio", "deporte") to "Eminem",
        listOf("romance", "romant") to "Ed Sheeran",
        listOf("triste", "sad") to "Billie Eilish",
        listOf("dormir", "sleep", "sueno") to "Ludovico Einaudi",
        listOf("concentracion", "focus", "estudio", "study") to "Hans Zimmer",
        listOf("sentirse bien", "feel good", "buen rollo") to "Bruno Mars",
        listOf("energia", "energy") to "Dua Lipa",
        listOf("relaj", "chill", "calma") to "Coldplay",
        listOf("viaje", "commute", "travel") to "Coldplay",
        listOf("decada", "80", "90", "2000", "throwback") to "Michael Jackson",
        listOf("pop") to "Taylor Swift",
    )

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()

    private fun curatedArtistFor(title: String): String? {
        val t = normalize(title)
        return CURATED.firstOrNull { (keywords, _) -> keywords.any { t.contains(it) } }?.second
    }

    /** Returns the already-resolved URL if available, without triggering a lookup. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun cached(title: String): String? =
        lookups[normalize(title)]?.takeIf { it.isCompleted }?.getCompleted()

    /**
     * Resolves the artist image URL for a genre title, or null when the genre
     * has no curated artist or the lookup fails. Concurrent callers share one
     * in-flight lookup per genre.
     */
    suspend fun imageFor(title: String): String? {
        val key = normalize(title)
        val deferred = lookups.getOrPut(key) {
            scope.async {
                val artist = curatedArtistFor(title) ?: return@async null
                runCatching {
                    YouTube.search(artist, YouTube.SearchFilter.FILTER_ARTIST)
                        .getOrNull()
                        ?.items
                        ?.filterIsInstance<ArtistItem>()
                        ?.firstOrNull()
                        ?.thumbnail
                }.getOrNull()
            }
        }
        return runCatching { deferred.await() }.getOrNull().also {
            // Don't cache transient failures for curated genres; allow a retry later.
            if (it == null && curatedArtistFor(title) != null) lookups.remove(key, deferred)
        }
    }
}
