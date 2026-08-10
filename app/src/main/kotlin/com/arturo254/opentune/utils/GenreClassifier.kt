/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.utils

import android.content.Context
import com.arturo254.opentune.innertube.YouTube
import com.arturo254.opentune.innertube.models.YouTubeClient
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.text.Normalizer

/**
 * Clasificación de canciones por género para la biblioteca.
 *
 * Estrategia en dos fases:
 *  1. Heurística local: se busca el género en título/álbum/artista (gratis).
 *  2. InnerTube: para lo que quede sin género, se consultan los `keywords`
 *     del video (los sellos suelen etiquetar el género: "reggaeton",
 *     "bachata", …) con un cliente ligero sin poToken.
 *
 * El resultado se cachea en un JSON en filesDir — sin tocar el schema de Room.
 */
object GenreClassifier {

    const val UNKNOWN = "otros"

    private const val CACHE_FILE = "genre_cache.json"

    /** (clave, nombre visible, palabras clave normalizadas). El orden importa:
     *  los géneros más específicos van primero para ganar los empates. */
    private val GENRES: List<Triple<String, String, List<String>>> = listOf(
        Triple("regueton", "Reguetón", listOf("reggaeton", "regueton", "perreo", "urbano latino", "latin urban")),
        Triple("dembow", "Dembow", listOf("dembow")),
        Triple("bachata", "Bachata", listOf("bachata")),
        Triple("vallenato", "Vallenato", listOf("vallenato")),
        Triple("merengue", "Merengue", listOf("merengue")),
        Triple("cumbia", "Cumbia", listOf("cumbia")),
        Triple("salsa", "Salsa", listOf("salsa")),
        Triple("regional", "Regional Mexicano", listOf("corrido", "corridos", "banda sinaloense", "norteno", "mariachi", "regional mexican", "sierreno", "grupera", "ranchera")),
        Triple("kpop", "K-Pop", listOf("kpop", "k pop")),
        Triple("trap", "Trap", listOf("trap")),
        Triple("hiphop", "Hip-Hop / Rap", listOf("hip hop", "hiphop", "rap", "drill", "freestyle")),
        Triple("rnb", "R&B / Soul", listOf("r&b", "rnb", "soul", "neo soul")),
        Triple("metal", "Metal", listOf("metal", "metalcore", "death metal")),
        Triple("rock", "Rock", listOf("rock", "grunge", "punk")),
        Triple("indie", "Indie / Alternativo", listOf("indie", "alternative", "alternativo")),
        Triple("electronica", "Electrónica", listOf("edm", "house", "techno", "electronic", "electronica", "dubstep", "trance", "dance", "electro")),
        Triple("balada", "Baladas", listOf("balada", "ballad", "bolero")),
        Triple("reggae", "Reggae", listOf("reggae", "ska")),
        Triple("jazz", "Jazz", listOf("jazz", "swing")),
        Triple("clasica", "Clásica", listOf("classical", "clasica", "orchestra", "orquesta", "symphony", "sinfonia")),
        Triple("country", "Country", listOf("country")),
        Triple("pop", "Pop", listOf("pop")),
    )

    fun displayName(key: String): String =
        GENRES.firstOrNull { it.first == key }?.second
            ?: if (key == UNKNOWN) "Otros" else key

    // ── Clasificación ─────────────────────────────────────────────────────────

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    /**
     * Busca un género en los textos dados (título, álbum, artista, keywords…).
     * Usa límites de palabra para que "rap" no matchee dentro de "trapo".
     */
    fun classifyText(vararg texts: String?): String? {
        val haystack = normalize(texts.filterNotNull().joinToString(" "))
        if (haystack.isBlank()) return null
        for ((key, _, keywords) in GENRES) {
            for (kw in keywords) {
                val pattern = Regex("\\b${Regex.escape(kw)}\\b")
                if (pattern.containsMatchIn(haystack)) return key
            }
        }
        return null
    }

    /**
     * Consulta los keywords del video en InnerTube (cliente ligero, sin
     * poToken ni login) y clasifica con ellos. Null si no se pudo determinar.
     */
    suspend fun classifyWithInnerTube(videoId: String): String? =
        runCatching {
            val details = YouTube.player(
                videoId = videoId,
                client = YouTubeClient.ANDROID_VR_NO_AUTH,
                setLogin = false,
            ).getOrNull()?.videoDetails ?: return null
            classifyText(
                details.keywords?.joinToString(" "),
                details.title,
                details.author,
            )
        }.onFailure {
            Timber.w(it, "Genre lookup failed for $videoId")
        }.getOrNull()

    // ── Caché en disco (JSON, sin migraciones de Room) ────────────────────────

    fun loadCache(context: Context): Map<String, String> =
        runCatching {
            val file = File(context.filesDir, CACHE_FILE)
            if (!file.exists()) return emptyMap()
            val json = JSONObject(file.readText())
            buildMap {
                json.keys().forEach { key -> put(key, json.getString(key)) }
            }
        }.getOrDefault(emptyMap())

    fun saveCache(context: Context, map: Map<String, String>) {
        runCatching {
            val json = JSONObject()
            map.forEach { (k, v) -> json.put(k, v) }
            File(context.filesDir, CACHE_FILE).writeText(json.toString())
        }.onFailure { Timber.w(it, "Failed to save genre cache") }
    }

    fun clearCache(context: Context) {
        runCatching { File(context.filesDir, CACHE_FILE).delete() }
    }
}
