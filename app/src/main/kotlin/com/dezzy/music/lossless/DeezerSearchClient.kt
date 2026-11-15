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

    /** Álbumes de Deezer para la consulta (chip «Álbumes» de la búsqueda lossless). */
    suspend fun searchAlbums(query: String, limit: Int = 20): List<LosslessAlbum> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            runCatching {
                val url = "$API/search/album?q=${URLEncoder.encode(q, "UTF-8")}&limit=$limit"
                get(url)?.let(::parseAlbums).orEmpty()
            }.getOrElse {
                Timber.w(it, "[Lossless] búsqueda de álbumes Deezer falló")
                emptyList()
            }
        }

    /** Pistas de un álbum, con el nombre y la portada del álbum (la API no los repite por pista). */
    suspend fun albumTracks(album: LosslessAlbum): List<LosslessTrack> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = get("$API/album/${album.id}") ?: return@runCatching emptyList()
            val root = JSONObject(raw)
            val cover = root.optString("cover_big").takeIf { it.isNotBlank() } ?: album.thumbnailUrl
            val title = root.optString("title").takeIf { it.isNotBlank() } ?: album.title
            val data = root.optJSONObject("tracks")?.optJSONArray("data") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    val id = o.optLong("id").takeIf { it != 0L }?.toString() ?: continue
                    val artist = o.optJSONObject("artist")?.optString("name")?.takeIf { it.isNotBlank() }
                    add(
                        LosslessTrack(
                            providerTrackId = id,
                            provider = LosslessProvider.DEEZER,
                            title = o.optString("title"),
                            artistNames = listOfNotNull(artist ?: album.artistName.takeIf { it.isNotBlank() }),
                            albumName = title,
                            durationSec = o.optInt("duration", -1),
                            isrc = o.optString("isrc").takeIf { it.isNotBlank() },
                            thumbnailUrl = cover,
                            explicit = o.optBoolean("explicit_lyrics", false),
                        ),
                    )
                }
            }
        }.getOrElse {
            Timber.w(it, "[Lossless] pistas del álbum Deezer fallaron")
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

    private fun parseAlbums(raw: String): List<LosslessAlbum> {
        val data = JSONObject(raw).optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                val o = data.optJSONObject(i) ?: continue
                val id = o.optLong("id").takeIf { it != 0L }?.toString() ?: continue
                add(
                    LosslessAlbum(
                        id = id,
                        title = o.optString("title"),
                        artistName = o.optJSONObject("artist")?.optString("name").orEmpty(),
                        thumbnailUrl = o.optString("cover_big").takeIf { it.isNotBlank() }
                            ?: o.optString("cover_medium").takeIf { it.isNotBlank() },
                        trackCount = o.optInt("nb_tracks", 0),
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

/** Álbum de Deezer en la búsqueda lossless. Sus pistas se piden aparte ([DeezerSearchClient.albumTracks]). */
data class LosslessAlbum(
    val id: String,
    val title: String,
    val artistName: String,
    val thumbnailUrl: String?,
    val trackCount: Int,
    val explicit: Boolean = false,
)
