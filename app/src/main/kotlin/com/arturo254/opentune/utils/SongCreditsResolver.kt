/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Créditos REALES de una canción (como "Mostrar créditos" de Spotify):
 * intérpretes, compositores y productores desde MusicBrainz (API abierta,
 * sin key; exige User-Agent identificado y ~1 req/s). Caché JSON en disco
 * por canción para que cada crédito se consulte una sola vez.
 */

package com.arturo254.opentune.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SongCredits(
    val performers: List<String>,
    val writers: List<String>,
    val producers: List<String>,
    val source: String,
)

object SongCreditsResolver {

    private const val CACHE_FILE = "song_credits_cache.json"
    private const val MAX_CACHE = 600
    private const val UA = "DezzyMusic/3.0 (jhonny1408jh@gmail.com)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cacheMutex = Mutex()
    private var memCache: MutableMap<String, SongCredits?>? = null

    // MusicBrainz exige máx ~1 petición/segundo: serializamos las consultas.
    private val networkMutex = Mutex()
    private var lastRequestAt = 0L

    /** Devuelve los créditos reales de la canción, o null si no se encontraron. */
    suspend fun resolve(
        context: Context,
        mediaId: String,
        title: String,
        artist: String,
    ): SongCredits? = withContext(Dispatchers.IO) {
        loadCache(context)
        cacheMutex.withLock {
            memCache?.let { if (it.containsKey(mediaId)) return@withContext it[mediaId] }
        }

        val credits = runCatching { fetchFromMusicBrainz(title, artist) }
            .onFailure { Timber.w(it, "MusicBrainz credits failed for $title") }
            .getOrNull()

        cacheMutex.withLock {
            val cache = memCache ?: mutableMapOf()
            // null también se cachea: no reintentar canciones sin créditos en cada apertura
            cache[mediaId] = credits
            while (cache.size > MAX_CACHE) cache.remove(cache.keys.first())
            memCache = cache
            persistCache(context, cache)
        }
        credits
    }

    // ── MusicBrainz ──────────────────────────────────────────────────────────

    private suspend fun fetchFromMusicBrainz(title: String, artist: String): SongCredits? {
        val cleanTitle = cleanupTitle(title)
        val query = URLEncoder.encode(
            "recording:\"$cleanTitle\" AND artist:\"$artist\"", "UTF-8",
        )
        val search = getJson(
            "https://musicbrainz.org/ws/2/recording?query=$query&limit=5&fmt=json",
        ) ?: return null
        val recordings = search.optJSONArray("recordings") ?: return null
        // Mejor match por score (la búsqueda de MB ya pondera título+artista)
        var best: JSONObject? = null
        for (i in 0 until recordings.length()) {
            val r = recordings.getJSONObject(i)
            if (best == null || r.optInt("score") > best.optInt("score")) best = r
        }
        val mbid = best?.takeIf { it.optInt("score") >= 70 }?.optString("id")
            ?.takeIf { it.isNotBlank() } ?: return null

        val rec = getJson(
            "https://musicbrainz.org/ws/2/recording/$mbid" +
                "?inc=artist-credits+artist-rels+work-rels+work-level-rels&fmt=json",
        ) ?: return null

        val performers = buildList {
            val ac = rec.optJSONArray("artist-credit") ?: JSONArray()
            for (i in 0 until ac.length()) {
                ac.getJSONObject(i).optJSONObject("artist")?.optString("name")
                    ?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }

        val producers = mutableListOf<String>()
        val writers = mutableListOf<String>()
        val rels = rec.optJSONArray("relations") ?: JSONArray()
        for (i in 0 until rels.length()) {
            val rel = rels.getJSONObject(i)
            when (rel.optString("type")) {
                "producer" -> rel.optJSONObject("artist")?.optString("name")
                    ?.takeIf { it.isNotBlank() }?.let { producers.add(it) }

                "performance" -> {
                    // Relación con la obra (work): ahí viven compositores y letristas
                    val workRels = rel.optJSONObject("work")?.optJSONArray("relations")
                        ?: JSONArray()
                    for (j in 0 until workRels.length()) {
                        val wr = workRels.getJSONObject(j)
                        if (wr.optString("type") in setOf("composer", "lyricist", "writer")) {
                            wr.optJSONObject("artist")?.optString("name")
                                ?.takeIf { it.isNotBlank() }?.let { writers.add(it) }
                        }
                    }
                }
            }
        }

        if (performers.isEmpty() && writers.isEmpty() && producers.isEmpty()) return null
        return SongCredits(
            performers = performers.distinct(),
            writers = writers.distinct(),
            producers = producers.distinct(),
            source = "MusicBrainz",
        )
    }

    private suspend fun getJson(url: String): JSONObject? {
        networkMutex.withLock {
            val wait = 1_100L - (System.currentTimeMillis() - lastRequestAt)
            if (wait > 0) kotlinx.coroutines.delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            return runCatching { JSONObject(body) }.getOrNull()
        }
    }

    /** Quita sufijos de video ("(Official Video)", "[Lyric]", "- Remaster"…). */
    private fun cleanupTitle(title: String): String =
        title
            .replace(
                Regex(
                    "[(\\[][^)\\]]*(official|video|lyric|audio|visualizer|remaster|" +
                        "live|hd|4k|mv|explicit|clean)[^)\\]]*[)\\]]",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(Regex("\\s*-\\s*(official.*|video.*|remaster.*)$", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { title }

    // ── Caché en disco ───────────────────────────────────────────────────────

    private suspend fun loadCache(context: Context) {
        cacheMutex.withLock {
            if (memCache != null) return
            memCache = runCatching {
                val file = File(context.filesDir, CACHE_FILE)
                if (!file.exists()) return@runCatching mutableMapOf()
                val root = JSONObject(file.readText())
                val map = mutableMapOf<String, SongCredits?>()
                root.keys().forEach { key ->
                    val entry = root.optJSONObject(key)
                    map[key] = if (entry == null || !entry.optBoolean("found")) null else SongCredits(
                        performers = entry.optJSONArray("p").toStringList(),
                        writers = entry.optJSONArray("w").toStringList(),
                        producers = entry.optJSONArray("pr").toStringList(),
                        source = entry.optString("s", "MusicBrainz"),
                    )
                }
                map
            }.getOrElse { mutableMapOf() }
        }
    }

    private fun persistCache(context: Context, cache: Map<String, SongCredits?>) {
        runCatching {
            val root = JSONObject()
            cache.forEach { (key, credits) ->
                val entry = JSONObject()
                if (credits == null) {
                    entry.put("found", false)
                } else {
                    entry.put("found", true)
                    entry.put("p", JSONArray(credits.performers))
                    entry.put("w", JSONArray(credits.writers))
                    entry.put("pr", JSONArray(credits.producers))
                    entry.put("s", credits.source)
                }
                root.put(key, entry)
            }
            File(context.filesDir, CACHE_FILE).writeText(root.toString())
        }.onFailure { Timber.w(it, "No se pudo persistir la caché de créditos") }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { optString(it) }.filter { it.isNotBlank() }
    }
}
