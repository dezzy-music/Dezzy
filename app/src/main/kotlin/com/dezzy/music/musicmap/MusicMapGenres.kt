/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.musicmap

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Género de cada artista para las secciones por género del Mapa musical.
 *
 * YouTube Music no da géneros en sus charts, así que se pregunta a la búsqueda pública de
 * iTunes (sin clave, una petición por artista) y se guarda para siempre en el teléfono: el
 * género de un artista casi nunca cambia. Sólo se clasifican los artistas del país que el
 * usuario abre, así que son pocas peticiones y cada teléfono reparte su propia carga.
 */
object MusicMapGenres {
    private const val TAG = "MusicMapGenres"
    private const val PREFS = "music_map_genres"
    private const val MISS_TTL_MS = 3L * 24 * 60 * 60 * 1000
    private const val SEARCH_URL = "https://itunes.apple.com/search"

    /** Grupo para los artistas que no se pudieron clasificar. */
    const val OTHER = "Otros"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val memory = ConcurrentHashMap<String, String>()

    @Volatile private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: synchronized(this) {
            prefs ?: context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .also { prefs = it }
        }

    /** Clave estable de un artista: minúsculas, sin acentos ni signos. */
    fun key(artist: String): String =
        Normalizer.normalize(artist.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    /** Género ya conocido (memoria o disco) o null si aún no se ha preguntado. */
    fun cached(context: Context, artist: String): String? {
        val k = key(artist)
        if (k.isEmpty()) return OTHER
        memory[k]?.let { return it }
        val p = prefs(context)
        p.getString("g_$k", null)?.let { memory[k] = it; return it }
        val missAt = p.getLong("miss_$k", 0L)
        if (missAt > 0 && System.currentTimeMillis() - missAt < MISS_TTL_MS) {
            memory[k] = OTHER
            return OTHER
        }
        return null
    }

    /**
     * Clasifica [artists] y devuelve clave de artista → grupo de género. [onPartial] recibe los
     * resultados según llegan. Si iTunes limita las peticiones, se para y lo que falte queda en
     * [OTHER] hasta la próxima vez.
     */
    suspend fun classify(
        context: Context,
        artists: List<String>,
        onPartial: suspend (Map<String, String>) -> Unit = {},
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val result = ConcurrentHashMap<String, String>()
        val pending = ArrayList<String>()
        artists.distinctBy { key(it) }.forEach { artist ->
            val k = key(artist)
            if (k.isEmpty()) return@forEach
            val known = cached(context, artist)
            if (known != null) result[k] = known else pending += artist
        }
        if (result.isNotEmpty()) onPartial(HashMap(result))
        if (pending.isEmpty()) return@withContext HashMap(result)

        val gate = Semaphore(2)
        val throttled = AtomicBoolean(false)
        val p = prefs(context)
        coroutineScope {
            pending.map { artist ->
                async {
                    gate.withPermit {
                        if (throttled.get()) return@withPermit
                        val k = key(artist)
                        val genre = runCatching { lookup(artist) }
                            .onFailure {
                                if (it is ThrottledException) throttled.set(true)
                                Timber.tag(TAG).w(it, "Género de %s falló", artist)
                            }
                            .getOrElse { return@withPermit }
                        if (genre != null) {
                            p.edit().putString("g_$k", genre).apply()
                            memory[k] = genre
                            result[k] = genre
                        } else {
                            p.edit().putLong("miss_$k", System.currentTimeMillis()).apply()
                            memory[k] = OTHER
                            result[k] = OTHER
                        }
                        onPartial(HashMap(result))
                    }
                }
            }.awaitAll()
        }
        HashMap(result)
    }

    private class ThrottledException : RuntimeException("iTunes limitó las peticiones")

    /** Género agrupado del artista o null si iTunes no lo conoce. */
    private fun lookup(artist: String): String? {
        // La tienda de México da los nombres en español; si no lo encuentra, la de EE. UU.
        for (store in listOf("mx", "us")) {
            val url = SEARCH_URL.toHttpUrl().newBuilder()
                .addQueryParameter("entity", "musicArtist")
                .addQueryParameter("limit", "3")
                .addQueryParameter("country", store)
                .addQueryParameter("term", artist)
                .build()
            val json = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code == 403 || response.code == 429) throw ThrottledException()
                if (!response.isSuccessful) return null
                JSONObject(response.body.string())
            }
            val results = json.optJSONArray("results") ?: continue
            val wanted = key(artist)
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val name = key(r.optString("artistName"))
                if (name.isEmpty() || !(name == wanted || name.contains(wanted) || wanted.contains(name))) continue
                val raw = r.optString("primaryGenreName").takeIf { it.isNotBlank() } ?: continue
                return group(raw)
            }
        }
        return null
    }

    /** Junta los nombres de iTunes (en español o inglés) en grupos fáciles de leer. */
    fun group(raw: String): String {
        val g = key(raw)
        fun has(vararg words: String) = words.any { g.contains(it) }
        return when {
            has("mexican", "regional", "banda", "norten", "grupera") -> "Regional mexicano"
            has("urbano", "urban", "reggaeton", "trap latino") -> "Urbano latino"
            has("pop latino", "latin pop") -> "Pop latino"
            has("k pop", "kpop", "korean") -> "K-pop"
            has("j pop", "jpop", "anime", "japanese") -> "J-pop"
            has("hip hop", "rap") -> "Hip-hop y rap"
            has("r b", "soul") -> "R&B y soul"
            has("electr", "dance", "house", "techno") -> "Electrónica"
            has("rock", "alternativ", "metal", "indie", "punk") -> "Rock y alternativa"
            has("afro", "african", "amapiano") -> "Afrobeats"
            has("bollywood", "indian", "india", "punjabi", "tamil") -> "Música india"
            has("sertanejo", "brasil", "brazil", "mpb", "pagode", "forro") -> "Música brasileña"
            has("cumbia") -> "Cumbia"
            has("salsa", "tropical", "bachata", "merengue") -> "Tropical"
            has("latin", "latina", "latino") -> "Latina"
            has("country") -> "Country"
            has("christian", "cristian", "gospel", "worship") -> "Cristiana"
            has("arab", "khaleeji") -> "Árabe"
            has("turk") -> "Pop turco"
            has("pop") -> "Pop"
            else -> raw.trim().replaceFirstChar { it.uppercase() }
        }
    }
}
