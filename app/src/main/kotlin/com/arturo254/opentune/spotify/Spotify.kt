package com.arturo254.opentune.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import kotlinx.serialization.Serializable

@Serializable
data class SpotifyUser(
    val id: String,
    val displayName: String? = null
)

@Serializable
data class SpotifyImage(
    val url: String,
    val width: Int? = null,
    val height: Int? = null
)

@Serializable
data class SpotifyArtist(
    val id: String,
    val name: String,
    val images: List<SpotifyImage>? = null
)

@Serializable
data class SpotifySimpleArtist(
    val name: String
)

@Serializable
data class SpotifyAlbum(
    val id: String,
    val name: String,
    val images: List<SpotifyImage>? = null
)

@Serializable
data class SpotifyTrack(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtist>,
    val durationMs: Int,
    val album: SpotifyAlbum? = null,
    val explicit: Boolean = false,
    val isLocal: Boolean = false,
    val uri: String? = null
)

@Serializable
data class SpotifyPlaylistsResult(
    val items: List<SpotifyPlaylist>,
    val total: Int
)

@Serializable
data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val images: List<SpotifyImage>? = null
)

@Serializable
data class SpotifyPlaylistTracksResult(
    val items: List<SpotifyPlaylistTrack>,
    val total: Int
)

@Serializable
data class SpotifyPlaylistTrack(
    val track: SpotifyTrack? = null
)

@Serializable
data class SpotifyLikedSongsResult(
    val items: List<SpotifyLikedSong>,
    val total: Int
)

@Serializable
data class SpotifyLikedSong(
    val track: SpotifyTrack
)

@Serializable
data class SpotifyArtistsResult(
    val items: List<SpotifyArtist>,
    val total: Int
)

@Serializable
data class SpotifyTracksResult(
    val items: List<SpotifyTrack>,
    val total: Int = 0
)

@Serializable
data class SpotifySearchResult(
    val tracks: SpotifyTracksResult? = null
)

@Serializable
data class SpotifyAuthTokenResponse(
    val accessToken: String,
    val accessTokenExpirationTimestampMs: Long,
    val isAnonymous: Boolean
)

object SpotifyAuth {
    const val LOGIN_URL = "https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchAccessToken(spDc: String, spKey: String): Result<SpotifyAuthTokenResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "https://open.spotify.com/get_access_token?reason=transport&productType=web_player"
            val cookieHeader = "sp_dc=$spDc" + (if (spKey.isNotEmpty()) "; sp_key=$spKey" else "")
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Cookie", cookieHeader)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Failed to fetch token: HTTP ${response.code}"))
                }
                val json = JSONObject(bodyStr)
                val token = json.getString("accessToken")
                val expiry = json.getLong("accessTokenExpirationTimestampMs")
                val isAnon = json.optBoolean("isAnonymous", false)
                Result.success(SpotifyAuthTokenResponse(token, expiry, isAnon))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

object Spotify {
    @Volatile
    var accessToken: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun getRequest(url: String): Request {
        val token = accessToken ?: throw Exception("Not authenticated with Spotify")
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .get()
            .build()
    }

    private inline fun <T> makeApiCall(url: String, crossinline parser: (JSONObject) -> T): Result<T> {
        return try {
            client.newCall(getRequest(url)).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return Result.failure(Exception("Spotify API error HTTP ${response.code}: $bodyStr"))
                }
                Result.success(parser(JSONObject(bodyStr)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun me(): Result<SpotifyUser> {
        return makeApiCall("https://api.spotify.com/v1/me") { json ->
            SpotifyUser(
                id = json.getString("id"),
                displayName = json.optString("display_name", null)
            )
        }
    }

    private fun parseImage(json: JSONObject): SpotifyImage {
        return SpotifyImage(
            url = json.getString("url"),
            width = json.optInt("width", -1).takeIf { it != -1 },
            height = json.optInt("height", -1).takeIf { it != -1 }
        )
    }

    private fun parseArtist(json: JSONObject): SpotifyArtist {
        val imgArray = json.optJSONArray("images")
        val images = if (imgArray != null) {
            List(imgArray.length()) { parseImage(imgArray.getJSONObject(it)) }
        } else null
        return SpotifyArtist(
            id = json.getString("id"),
            name = json.getString("name"),
            images = images
        )
    }

    private fun parseAlbum(json: JSONObject): SpotifyAlbum {
        val imgArray = json.optJSONArray("images")
        val images = if (imgArray != null) {
            List(imgArray.length()) { parseImage(imgArray.getJSONObject(it)) }
        } else null
        return SpotifyAlbum(
            id = json.getString("id"),
            name = json.getString("name"),
            images = images
        )
    }

    private fun parseTrack(json: JSONObject): SpotifyTrack {
        val artistsArray = json.getJSONArray("artists")
        val artists = List(artistsArray.length()) { parseArtist(artistsArray.getJSONObject(it)) }
        val albumObj = json.optJSONObject("album")
        val album = if (albumObj != null) parseAlbum(albumObj) else null
        return SpotifyTrack(
            id = json.getString("id"),
            name = json.getString("name"),
            artists = artists,
            durationMs = json.getInt("duration_ms"),
            album = album,
            explicit = json.optBoolean("explicit", false),
            isLocal = json.optBoolean("is_local", false),
            uri = json.optString("uri", null)
        )
    }

    fun likedSongs(limit: Int = 20, offset: Int = 0): Result<SpotifyLikedSongsResult> {
        return makeApiCall("https://api.spotify.com/v1/me/tracks?limit=$limit&offset=$offset") { json ->
            val itemsArray = json.getJSONArray("items")
            val items = List(itemsArray.length()) {
                val item = itemsArray.getJSONObject(it)
                SpotifyLikedSong(track = parseTrack(item.getJSONObject("track")))
            }
            SpotifyLikedSongsResult(items = items, total = json.getInt("total"))
        }
    }

    fun myPlaylists(limit: Int = 20, offset: Int = 0): Result<SpotifyPlaylistsResult> {
        return makeApiCall("https://api.spotify.com/v1/me/playlists?limit=$limit&offset=$offset") { json ->
            val itemsArray = json.getJSONArray("items")
            val items = List(itemsArray.length()) {
                val item = itemsArray.getJSONObject(it)
                val imgArray = item.optJSONArray("images")
                val images = if (imgArray != null) {
                    List(imgArray.length()) { parseImage(imgArray.getJSONObject(it)) }
                } else null
                SpotifyPlaylist(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    images = images
                )
            }
            SpotifyPlaylistsResult(items = items, total = json.getInt("total"))
        }
    }

    fun playlistTracks(playlistId: String, limit: Int = 20, offset: Int = 0): Result<SpotifyPlaylistTracksResult> {
        return makeApiCall("https://api.spotify.com/v1/playlists/$playlistId/tracks?limit=$limit&offset=$offset") { json ->
            val itemsArray = json.getJSONArray("items")
            val items = List(itemsArray.length()) {
                val item = itemsArray.getJSONObject(it)
                val trackObj = item.optJSONObject("track")
                SpotifyPlaylistTrack(track = if (trackObj != null) parseTrack(trackObj) else null)
            }
            SpotifyPlaylistTracksResult(items = items, total = json.getInt("total"))
        }
    }

    fun myArtists(limit: Int = 20, offset: Int = 0): Result<SpotifyArtistsResult> {
        return makeApiCall("https://api.spotify.com/v1/me/following?type=artist&limit=$limit") { json ->
            val artistsObj = json.getJSONObject("artists")
            val itemsArray = artistsObj.getJSONArray("items")
            val items = List(itemsArray.length()) {
                parseArtist(itemsArray.getJSONObject(it))
            }
            SpotifyArtistsResult(items = items, total = artistsObj.optInt("total", items.size))
        }
    }

    fun artistRelatedArtists(artistId: String): Result<List<SpotifyArtist>> {
        return makeApiCall("https://api.spotify.com/v1/artists/$artistId/related-artists") { json ->
            val artistsArray = json.getJSONArray("artists")
            List(artistsArray.length()) {
                parseArtist(artistsArray.getJSONObject(it))
            }
        }
    }

    fun artist(artistId: String): Result<SpotifyArtist> {
        return makeApiCall("https://api.spotify.com/v1/artists/$artistId") { json ->
            parseArtist(json)
        }
    }

    fun search(query: String, types: List<String> = listOf("track"), limit: Int = 20): Result<SpotifySearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val typeStr = types.joinToString(",")
        return makeApiCall("https://api.spotify.com/v1/search?q=$encodedQuery&type=$typeStr&limit=$limit") { json ->
            val tracksObj = json.optJSONObject("tracks")
            val tracksResult = if (tracksObj != null) {
                val itemsArray = tracksObj.getJSONArray("items")
                val items = List(itemsArray.length()) { parseTrack(itemsArray.getJSONObject(it)) }
                SpotifyTracksResult(items = items, total = tracksObj.getInt("total"))
            } else null
            SpotifySearchResult(tracks = tracksResult)
        }
    }

    fun topTracks(timeRange: String = "short_term", limit: Int = 50): Result<SpotifyTracksResult> {
        return makeApiCall("https://api.spotify.com/v1/me/top/tracks?time_range=$timeRange&limit=$limit") { json ->
            val itemsArray = json.getJSONArray("items")
            val items = List(itemsArray.length()) { parseTrack(itemsArray.getJSONObject(it)) }
            SpotifyTracksResult(items = items, total = json.optInt("total", items.size))
        }
    }

    fun topArtists(timeRange: String = "medium_term", limit: Int = 50): Result<SpotifyArtistsResult> {
        return makeApiCall("https://api.spotify.com/v1/me/top/artists?time_range=$timeRange&limit=$limit") { json ->
            val itemsArray = json.getJSONArray("items")
            val items = List(itemsArray.length()) { parseArtist(itemsArray.getJSONObject(it)) }
            SpotifyArtistsResult(items = items, total = json.optInt("total", items.size))
        }
    }
}

object SpotifyMapper {
    data class Precomputed(
        val title: String,
        val artist: String,
        val durationMs: Int
    )

    private fun normalize(str: String): String {
        return str.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun diceCoefficient(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.length < 2 || s2.length < 2) return if (s1 == s2) 1.0 else 0.0
        val s1Bigrams = mutableSetOf<String>()
        for (i in 0 until s1.length - 1) {
            s1Bigrams.add(s1.substring(i, i + 2))
        }
        val s2Bigrams = mutableSetOf<String>()
        for (i in 0 until s2.length - 1) {
            s2Bigrams.add(s2.substring(i, i + 2))
        }
        val intersection = s1Bigrams.intersect(s2Bigrams).size
        return 2.0 * intersection / (s1Bigrams.size + s2Bigrams.size)
    }

    fun buildSearchQuery(track: SpotifyTrack): String {
        val artistName = track.artists.firstOrNull()?.name ?: ""
        return "${track.name} $artistName".trim()
    }

    fun precompute(title: String, artist: String, durationMs: Int): Precomputed {
        return Precomputed(normalize(title), normalize(artist), durationMs)
    }

    fun earlyExitThreshold(): Double = 0.85

    fun matchScorePrecomputed(
        precomputed: Precomputed,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int?
    ): Double {
        val cleanCandTitle = normalize(candidateTitle)
        val cleanCandArtist = normalize(candidateArtist)

        val titleScore = diceCoefficient(precomputed.title, cleanCandTitle)
        val artistScore = diceCoefficient(precomputed.artist, cleanCandArtist)

        val durationPenalty = if (candidateDurationSec != null && precomputed.durationMs > 0) {
            val diffSec = Math.abs(precomputed.durationMs / 1000 - candidateDurationSec)
            if (diffSec < 5) 1.0
            else if (diffSec < 15) 0.85
            else if (diffSec < 30) 0.6
            else 0.2
        } else 1.0

        return (titleScore * 0.6 + artistScore * 0.4) * durationPenalty
    }

    fun getTrackThumbnail(track: SpotifyTrack): String? {
        return track.album?.images?.firstOrNull()?.url
    }
}
