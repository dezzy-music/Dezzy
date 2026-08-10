/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.lossless

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Búsqueda de pistas contra la API pública de Deezer (`api.deezer.com`). Es
 * abierta y sin cuenta: devuelve id, artista, álbum, duración, carátula y —vía
 * detalle— el ISRC, que sirve para re-emparejar la misma grabación en otros
 * proveedores a través del resolver.
 *
 * Esto cubre el "busco una canción y me aparece". La descarga la hace luego el
 * resolver propio del usuario (ver [ResolverClient]).
 */
class DeezerSearchClient(
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun search(query: String, limit: Int = 25): List<LosslessTrack> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            runCatching {
                val url = "$API/search?q=${URLEncoder.encode(q, "UTF-8")}&limit=$limit"
                get(url)?.let(::parseSearch).orEmpty()
            }.getOrElse {
                Timber.w(it, "[Lossless] búsqueda Deezer falló")
                emptyList()
            }
        }

    /** ISRC de una pista de Deezer, necesario para descargar por otro proveedor. */
    suspend fun isrcFor(deezerId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            get("$API/track/$deezerId")?.let { JSONObject(it).optString("isrc").takeIf { s -> s.isNotBlank() } }
        }.getOrNull()
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    private fun parseSearch(raw: String): List<LosslessTrack> {
        val data = JSONObject(raw).optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                val o = data.optJSONObject(i) ?: continue
                val id = o.optLong("id").takeIf { it != 0L }?.toString() ?: continue
                val album = o.optJSONObject("album")
                val artist = o.optJSONObject("artist")
                add(
                    LosslessTrack(
                        providerTrackId = id,
                        provider = LosslessProvider.DEEZER,
                        title = o.optString("title"),
                        artistNames = listOfNotNull(artist?.optString("name")?.takeIf { it.isNotBlank() }),
                        albumName = album?.optString("title")?.takeIf { it.isNotBlank() },
                        durationSec = o.optInt("duration", -1),
                        isrc = null,
                        thumbnailUrl = album?.optString("cover_big")?.takeIf { it.isNotBlank() }
                            ?: album?.optString("cover_medium")?.takeIf { it.isNotBlank() },
                        explicit = o.optBoolean("explicit_lyrics", false),
                    ),
                )
            }
        }
    }

    companion object {
        private const val API = "https://api.deezer.com"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
