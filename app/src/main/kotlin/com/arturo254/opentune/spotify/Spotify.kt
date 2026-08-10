package com.arturo254.opentune.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor
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
data class SpotifyExternalIds(
    val isrc: String? = null,
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
    val uri: String? = null,
    val external_ids: SpotifyExternalIds? = null,
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

    // Modern Spotify token endpoint requires a TOTP (time-based one-time password) generated from a
    // rotating secret. We mirror Meld's proven approach: fetch the current secret from a
    // community-maintained gist + Spotify's server time, generate the TOTP, and call /api/token.
    private const val TOKEN_URL = "https://open.spotify.com/api/token"
    private const val SERVER_TIME_URL = "https://open.spotify.com/api/server-time"
    private const val NUANCE_GIST_URL = "https://api.github.com/gists/22ed9c6ba463899e933427f7de1f0eef"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    suspend fun fetchAccessToken(spDc: String, spKey: String): Result<SpotifyAuthTokenResponse> = withContext(Dispatchers.IO) {
        try {
            val (secret, version) = fetchNuance()
            val serverTimeSec = fetchServerTime()
            // Current /api/token protocol (mirrors the maintained spotify-lyrics-api): a single
            // `totp` derived from Spotify's own server time plus a `ts` (Unix seconds) query param.
            // `ts` is now mandatory — omitting it returns HTTP 403 "Invalid request". The older
            // `totpServer` param this code used to send is no longer part of the protocol.
            val totp = generateTotp(secret, serverTimeSec)

            val url = "$TOKEN_URL?reason=transport&productType=web-player" +
                "&totp=$totp&totpVer=$version&ts=$serverTimeSec"
            val cookieHeader = "sp_dc=$spDc" + (if (spKey.isNotEmpty()) "; sp_key=$spKey" else "")

            val request = Request.Builder()
                .url(url)
                .addHeader("Cookie", cookieHeader)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "application/json, text/plain, */*")
                .addHeader("Accept-Language", "en")
                .addHeader("Referer", "https://open.spotify.com/")
                .addHeader("Origin", "https://open.spotify.com")
                .addHeader("App-Platform", "WebPlayer")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Failed to fetch token: HTTP ${response.code}: $bodyStr"))
                }
                val json = JSONObject(bodyStr)
                val token = json.optString("accessToken", "")
                if (token.isBlank()) {
                    return@withContext Result.failure(Exception("No accessToken in response: $bodyStr"))
                }
                val expiry = json.optLong("accessTokenExpirationTimestampMs", 0L)
                val isAnon = json.optBoolean("isAnonymous", false)
                if (isAnon) {
                    return@withContext Result.failure(Exception("anonymous token returned — sp_dc may be expired"))
                }
                Result.success(SpotifyAuthTokenResponse(token, expiry, isAnon))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Fetches the highest-version TOTP secret from the community gist. */
    private fun fetchNuance(): Pair<String, Int> {
        val request = Request.Builder().url(NUANCE_GIST_URL).addHeader("User-Agent", USER_AGENT).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) error("Failed to fetch TOTP secret (HTTP ${response.code})")
            val files = JSONObject(body).getJSONObject("files")
            val firstFile = files.keys().asSequence().firstOrNull() ?: error("Gist has no files")
            val content = files.getJSONObject(firstFile).getString("content")
            val arr = JSONArray(content)
            var bestSecret = ""
            var bestVersion = -1
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val v = o.getInt("v")
                if (v > bestVersion) {
                    bestVersion = v
                    bestSecret = o.getString("s")
                }
            }
            if (bestVersion < 0) error("No nuance data in gist")
            return bestSecret to bestVersion
        }
    }

    private fun fetchServerTime(): Long {
        val request = Request.Builder().url(SERVER_TIME_URL).addHeader("User-Agent", USER_AGENT).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) error("Failed to fetch Spotify server time (HTTP ${response.code})")
            return JSONObject(body).getLong("serverTime")
        }
    }

    /** RFC 6238 TOTP: HMAC-SHA1, 30s step, 6 digits. */
    private fun generateTotp(secret: String, serverTimeSec: Long): String {
        val key = base32Decode(secret)
        val timeStep = floor(serverTimeSec.toDouble() / 30L).toLong()
        val timeBytes = ByteArray(8)
        var value = timeStep
        for (i in 7 downTo 0) {
            timeBytes[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(timeBytes)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val code = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return (code % 1_000_000).toString().padStart(6, '0')
    }

    private fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val cleaned = input.uppercase().replace("=", "")
        val output = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0
        for (c in cleaned) {
            val v = alphabet.indexOf(c)
            if (v < 0) continue
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return output.toByteArray()
    }
}

object Spotify {
    @Volatile
    var accessToken: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private fun getRequest(url: String): Request {
        val token = accessToken ?: throw Exception("Not authenticated with Spotify")
        val builder = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("User-Agent", WEB_USER_AGENT)
            // Mirror the real open.spotify.com web player. Its api.spotify.com calls carry a
            // Client-Token and these Origin/App-Platform headers, which grant the full
            // web-player rate-limit quota — a bare Bearer token gets throttled (HTTP 429)
            // almost immediately, which is what broke the Spotify preload.
            .addHeader("Accept", "application/json")
            .addHeader("Accept-Language", "en")
            .addHeader("Referer", "https://open.spotify.com/")
            .addHeader("Origin", "https://open.spotify.com")
            .addHeader("App-Platform", "WebPlayer")
        SpotifyClientToken.get()?.let { builder.addHeader("Client-Token", it) }
        return builder.get().build()
    }

    /**
     * Executes an authenticated Web API GET with production-grade resilience:
     *  - HTTP 401 → refresh the token once via [SpotifyTokenManager] and retry (the
     *    web-player token can expire between login and a long preload run).
     *  - HTTP 429 → honor `Retry-After` (or exponential backoff) and retry; Spotify
     *    rate-limits aggressively and the preload fires many calls in a row. This used
     *    to surface as a generic null → "Failed to load playlists".
     *  - Any other error → propagate the real HTTP code + body so the UI can show it.
     */
    private inline fun <T> makeApiCall(url: String, crossinline parser: (JSONObject) -> T): Result<T> {
        var refreshedOnce = false
        var rateLimitRetries = 0
        while (true) {
            val request = try {
                getRequest(url)
            } catch (e: Exception) {
                return Result.failure(e)
            }
            try {
                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        return Result.success(parser(JSONObject(bodyStr)))
                    }
                    when {
                        response.code == 401 && !refreshedOnce -> {
                            refreshedOnce = true
                            val ok = runCatching {
                                runBlocking { SpotifyTokenManager.ensureAuthenticated() }
                            }.getOrDefault(false)
                            if (!ok) {
                                return Result.failure(
                                    Exception("Sesión de Spotify expirada. Vuelve a iniciar sesión."),
                                )
                            }
                            // fall through → retry with the refreshed token
                        }

                        response.code == 429 && rateLimitRetries < 8 -> {
                            rateLimitRetries++
                            // Spotify's 429 Retry-After is short (~15s) and the window resets,
                            // so honor it and keep retrying rather than giving up after a few
                            // attempts — that difference is what let the preload finish.
                            val retryAfterSec = response.header("Retry-After")?.toLongOrNull()
                                ?: (2L * rateLimitRetries)
                            Thread.sleep(retryAfterSec.coerceIn(1L, 30L) * 1000L)
                            // fall through → retry after backoff
                        }

                        else -> {
                            return Result.failure(
                                Exception("Spotify API error HTTP ${response.code}: ${bodyStr.take(300)}"),
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Internal GraphQL API (api-partner.spotify.com/pathfinder) — used for
    //  library/playlist reads because the public REST API is throttled (429)
    //  for web-player tokens. Mirrors the real open.spotify.com web player.
    // ─────────────────────────────────────────────────────────────────────

    private const val GQL_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val gqlClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private sealed class GqlOutcome {
        data class Ok(val json: JSONObject) : GqlOutcome()
        data object PersistedQueryNotFound : GqlOutcome()
    }

    /** One GraphQL POST with in-place 401-refresh and 429-backoff retries. */
    private fun executeGql(bodyJson: JSONObject): GqlOutcome {
        var refreshedOnce = false
        var rateRetries = 0
        while (true) {
            val token = accessToken ?: throw Exception("Not authenticated with Spotify")
            val request = Request.Builder()
                .url(GQL_URL)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("app-platform", "WebPlayer")
                .addHeader("Origin", "https://open.spotify.com")
                .addHeader("Referer", "https://open.spotify.com/")
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", WEB_USER_AGENT)
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
                .build()

            gqlClient.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(text)
                    val errors = json.optJSONArray("errors")
                    if (errors != null && errors.length() > 0) {
                        val msg = errors.optJSONObject(0)?.optString("message", "") ?: ""
                        if (msg.contains("PersistedQueryNotFound", ignoreCase = true)) {
                            return GqlOutcome.PersistedQueryNotFound
                        }
                        throw Exception("Spotify GraphQL: $msg")
                    }
                    return GqlOutcome.Ok(json)
                }
                when (response.code) {
                    401 -> {
                        if (!refreshedOnce) {
                            refreshedOnce = true
                            val ok = runCatching {
                                runBlocking { SpotifyTokenManager.ensureAuthenticated() }
                            }.getOrDefault(false)
                            if (!ok) throw Exception("Sesión de Spotify expirada. Vuelve a iniciar sesión.")
                            // fall through → retry with refreshed token
                        } else {
                            throw Exception("Sesión de Spotify expirada. Vuelve a iniciar sesión.")
                        }
                    }
                    429 -> {
                        if (rateRetries >= 6) throw Exception("Spotify GraphQL rate limited (HTTP 429)")
                        rateRetries++
                        val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: (2L * rateRetries)
                        Thread.sleep(retryAfter.coerceIn(1L, 30L) * 1000L)
                        // fall through → retry after backoff
                    }
                    412 -> return GqlOutcome.PersistedQueryNotFound
                    else -> throw Exception("Spotify GraphQL error HTTP ${response.code}: ${text.take(200)}")
                }
            }
        }
    }

    /**
     * POSTs a persisted GraphQL query, trying the current hash then the previous
     * one (covers a Spotify hash rotation) before failing.
     */
    private fun graphqlPost(operationName: String, variables: JSONObject): JSONObject {
        if (accessToken == null) throw Exception("Not authenticated with Spotify")
        val candidates = buildList {
            add(SpotifyHashProvider.getHash(operationName))
            SpotifyHashProvider.getPreviousHash(operationName)?.let { if (it !in this) add(it) }
        }
        for ((idx, hash) in candidates.withIndex()) {
            val body = JSONObject().apply {
                put("variables", variables)
                put("operationName", operationName)
                put(
                    "extensions",
                    JSONObject().put(
                        "persistedQuery",
                        JSONObject().put("version", 1).put("sha256Hash", hash),
                    ),
                )
            }
            when (val r = executeGql(body)) {
                is GqlOutcome.Ok -> return r.json
                GqlOutcome.PersistedQueryNotFound -> {
                    if (idx == candidates.lastIndex) {
                        throw Exception(
                            "Spotify cambió su API (PersistedQueryNotFound: $operationName). Actualiza la app.",
                        )
                    }
                    // else: try the previous hash
                }
            }
        }
        throw Exception("No hay hash válido para $operationName")
    }

    // ── GraphQL response parsers ─────────────────────────────────────────

    private fun gqlSources(sources: JSONArray?): List<SpotifyImage>? {
        if (sources == null) return null
        val list = (0 until sources.length()).mapNotNull { i ->
            val s = sources.optJSONObject(i) ?: return@mapNotNull null
            val url = s.optString("url", "")
            if (url.isEmpty()) null else SpotifyImage(
                url = url,
                width = s.optInt("width", -1).takeIf { it != -1 },
                height = s.optInt("height", -1).takeIf { it != -1 },
            )
        }
        return list.ifEmpty { null }
    }

    /** Flattens playlist `images.items[].sources[]`. */
    private fun gqlPlaylistImages(imagesObj: JSONObject?): List<SpotifyImage>? {
        val items = imagesObj?.optJSONArray("items") ?: return null
        val out = ArrayList<SpotifyImage>()
        for (i in 0 until items.length()) {
            gqlSources(items.optJSONObject(i)?.optJSONArray("sources"))?.let { out.addAll(it) }
        }
        return out.ifEmpty { null }
    }

    /** Parses a track from the GQL `...data` payload shared by fetchPlaylist / fetchLibraryTracks. */
    private fun gqlTrack(trackData: JSONObject, uriOverride: String?): SpotifyTrack {
        val uri = uriOverride?.takeIf { it.isNotEmpty() }
            ?: trackData.optString("uri", "").ifEmpty { trackData.optString("_uri", "") }
        val id = uri.substringAfterLast(":")

        val artistItems = trackData.optJSONObject("artists")?.optJSONArray("items")
        val artists = if (artistItems != null) {
            (0 until artistItems.length()).mapNotNull { i ->
                val a = artistItems.optJSONObject(i) ?: return@mapNotNull null
                val aUri = a.optString("uri", "")
                SpotifyArtist(
                    id = aUri.substringAfterLast(":"),
                    name = a.optJSONObject("profile")?.optString("name", "") ?: "",
                )
            }
        } else emptyList()

        val albumData = trackData.optJSONObject("albumOfTrack")
        val album = if (albumData != null) {
            val albumUri = albumData.optString("uri", "")
            SpotifyAlbum(
                id = albumUri.substringAfterLast(":"),
                name = albumData.optString("name", ""),
                images = gqlSources(albumData.optJSONObject("coverArt")?.optJSONArray("sources")),
            )
        } else null

        val durationMs = trackData.optJSONObject("duration")?.optInt("totalMilliseconds", 0)
            ?: trackData.optInt("duration_ms", 0)

        return SpotifyTrack(
            id = id,
            name = trackData.optString("name", ""),
            artists = artists,
            durationMs = durationMs,
            album = album,
            uri = uri.ifEmpty { null },
        )
    }

    /**
     * Resolves an arbitrary "song artist" query to a Spotify track id via the
     * web player's `searchSuggestions` GraphQL op. Shape-agnostic: scans the
     * response for the first `spotify:track:` URI. Returns null on any failure so
     * callers fall back gracefully. Requires a real (non-anonymous) user token.
     */
    fun searchTrackId(query: String): String? {
        if (query.isBlank()) return null
        return try {
            val vars = JSONObject().put("query", query).put("limit", 5)
            val response = graphqlPost("searchSuggestions", vars)
            findFirstTrackId(response)
        } catch (e: Exception) {
            null
        }
    }

    /** Recursively scans a JSON tree for the first `spotify:track:<id>` URI. */
    private fun findFirstTrackId(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val v = node.opt(keys.next())
                    if (v is String && v.startsWith("spotify:track:")) {
                        return v.substringAfterLast(":")
                    }
                    findFirstTrackId(v)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findFirstTrackId(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    fun me(): Result<SpotifyUser> {
        return try {
            val response = graphqlPost("profileAttributes", JSONObject())
            val profile = response.optJSONObject("data")?.optJSONObject("me")?.optJSONObject("profile")
                ?: return Result.failure(Exception("Invalid profileAttributes response"))
            val uri = profile.optString("uri", "")
            Result.success(
                SpotifyUser(
                    id = uri.substringAfterLast(":"),
                    displayName = profile.optString("name", "").ifEmpty { null },
                ),
            )
        } catch (e: Exception) {
            Result.failure(e)
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

    fun likedSongs(limit: Int = 50, offset: Int = 0): Result<SpotifyLikedSongsResult> {
        return try {
            val vars = JSONObject().put("offset", offset).put("limit", limit)
            val response = graphqlPost("fetchLibraryTracks", vars)
            val tracksData = response.optJSONObject("data")?.optJSONObject("me")
                ?.optJSONObject("library")?.optJSONObject("tracks")
                ?: return Result.failure(Exception("Invalid fetchLibraryTracks response"))
            val itemsArray = tracksData.optJSONArray("items")
            val items = if (itemsArray != null) {
                (0 until itemsArray.length()).mapNotNull { i ->
                    val trackWrapper = itemsArray.optJSONObject(i)?.optJSONObject("track") ?: return@mapNotNull null
                    val trackData = trackWrapper.optJSONObject("data") ?: return@mapNotNull null
                    val wrapperUri = trackWrapper.optString("_uri", "").ifEmpty { trackWrapper.optString("uri", "") }
                    SpotifyLikedSong(track = gqlTrack(trackData, wrapperUri))
                }
            } else emptyList()
            Result.success(SpotifyLikedSongsResult(items = items, total = tracksData.optInt("totalCount", items.size)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun myPlaylists(limit: Int = 50, offset: Int = 0): Result<SpotifyPlaylistsResult> {
        return try {
            val vars = JSONObject().apply {
                put("filters", JSONArray().put("Playlists"))
                put("order", JSONObject.NULL)
                put("textFilter", "")
                put("features", JSONArray().put("LIKED_SONGS").put("YOUR_EPISODES_V2").put("PRERELEASES").put("EVENTS"))
                put("limit", limit)
                put("offset", offset)
                // flatten=true returns every playlist regardless of folder nesting.
                put("flatten", true)
                put("expandedFolders", JSONArray())
                put("folderUri", JSONObject.NULL)
                put("includeFoldersWhenFlattening", false)
            }
            val response = graphqlPost("libraryV3", vars)
            val libraryData = response.optJSONObject("data")?.optJSONObject("me")?.optJSONObject("libraryV3")
                ?: return Result.failure(Exception("Invalid libraryV3 response"))
            val rawItems = libraryData.optJSONArray("items")
            val items = if (rawItems != null) {
                (0 until rawItems.length()).mapNotNull { i ->
                    val wrapper = rawItems.optJSONObject(i)?.optJSONObject("item") ?: return@mapNotNull null
                    val typeName = wrapper.optString("__typename", "")
                    if (!typeName.contains("Playlist", ignoreCase = true)) return@mapNotNull null
                    val data = wrapper.optJSONObject("data") ?: return@mapNotNull null
                    if (data.optString("__typename", "") != "Playlist") return@mapNotNull null
                    val playlistUri = wrapper.optString("_uri", "")
                    // Only real playlists — exclude pseudo-collections like Liked Songs and
                    // Your Episodes (spotify:collection:… / spotify:user:…), which otherwise
                    // showed up as junk "playlists" in the library.
                    if (!playlistUri.startsWith("spotify:playlist:")) return@mapNotNull null
                    SpotifyPlaylist(
                        id = playlistUri.substringAfterLast(":"),
                        name = data.optString("name", ""),
                        images = gqlPlaylistImages(data.optJSONObject("images")),
                    )
                }
            } else emptyList()
            Result.success(SpotifyPlaylistsResult(items = items, total = libraryData.optInt("totalCount", items.size)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun playlistTracks(playlistId: String, limit: Int = 100, offset: Int = 0): Result<SpotifyPlaylistTracksResult> {
        return try {
            val vars = JSONObject()
                .put("uri", "spotify:playlist:$playlistId")
                .put("offset", offset)
                .put("limit", limit)
                .put("enableWatchFeedEntrypoint", false)
            val response = graphqlPost("fetchPlaylist", vars)
            val content = response.optJSONObject("data")?.optJSONObject("playlistV2")?.optJSONObject("content")
                ?: return Result.failure(Exception("No content in fetchPlaylist response"))
            val itemsArray = content.optJSONArray("items")
            val items = if (itemsArray != null) {
                (0 until itemsArray.length()).mapNotNull { i ->
                    val elem = itemsArray.optJSONObject(i) ?: return@mapNotNull null
                    val itemWrapper = elem.optJSONObject("itemV2") ?: return@mapNotNull null
                    val itemData = itemWrapper.optJSONObject("data") ?: return@mapNotNull null
                    val wrapperUri = itemWrapper.optString("_uri", "").ifEmpty { itemWrapper.optString("uri", "") }
                    SpotifyPlaylistTrack(track = gqlTrack(itemData, wrapperUri))
                }
            } else emptyList()
            Result.success(SpotifyPlaylistTracksResult(items = items, total = content.optInt("totalCount", items.size)))
        } catch (e: Exception) {
            Result.failure(e)
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
        val images = track.album?.images ?: return null
        // Pick the highest-resolution cover, not just the first (Spotify returns
        // multiple sizes and the order isn't guaranteed largest-first — the old
        // firstOrNull() often yielded the 64px thumbnail, hence the poor quality).
        return images.maxByOrNull { (it.width ?: 0) * (it.height ?: 0) }?.url
            ?: images.firstOrNull()?.url
    }
}
