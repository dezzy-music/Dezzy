/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 *
 * Spotify Canvas integration via spclient.wg.spotify.com internal endpoint.
 * This uses Spotify's internal Protobuf API (canvaz-cache/v0/canvases).
 * Requires a valid Spotify access token (sp_dc cookie) AND a client token.
 */

package com.dezzy.music.canvas

import com.dezzy.music.canvas.models.CanvasArtwork
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

// ─── Logger ──────────────────────────────────────────────────────────────────

private object SpotifyCanvasLogger {
    var logFilePath: String? = null

    fun init(filePath: String?) {
        logFilePath = filePath
        if (filePath != null) {
            try {
                val file = java.io.File(filePath)
                if (file.exists() && file.length() > 1024 * 1024) file.delete()
            } catch (_: Exception) {}
        }
    }

    private fun log(level: String, msg: String, t: Throwable? = null) {
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        val line = "[$ts] SpotifyCanvas: $level: $msg${t?.let { "\n" + it.stackTraceToString() } ?: ""}"
        println(line)
        val path = logFilePath ?: return
        try { java.io.File(path).appendText(line + "\n") } catch (_: Exception) {}
    }

    fun d(msg: String) = log("D", msg)
    fun w(msg: String) = log("W", msg)
    fun e(t: Throwable, msg: String) = log("E", msg, t)
}

// ─── Client-Token Manager ─────────────────────────────────────────────────────
// Spotify's spclient endpoints require a Client-Token in addition to the Bearer token.
// The Client-Token is obtained from https://clienttoken.spotify.com/v1/clienttoken
// and is valid for a long time (days).

private object SpotifyClientTokenManager {
    private val mutex = Mutex()
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"

    // Web Player Client ID. The previous value (65b70807…87bd) was rejected by
    // clienttoken.spotify.com with HTTP 400 on every request, which silently
    // disabled the entire Canvas feature (getToken() returned null → fetch aborted).
    // This is the real open.spotify.com web-player client_id, verified against the
    // live endpoint (returns a granted token valid ~14 days).
    // Find via: open.spotify.com > DevTools > clienttoken request payload > client_id
    private const val CLIENT_ID = "d8a5ed958d274c2e8ee717e6a4b0971d"

    suspend fun getToken(): String? {
        val now = System.currentTimeMillis()
        val current = cachedToken
        if (current != null && now < tokenExpiresAt - 60_000L) {
            SpotifyCanvasLogger.d("Client-Token cache hit, expires in ${(tokenExpiresAt - now) / 1000}s")
            return current
        }

        return mutex.withLock {
            // Double-check after acquiring lock
            val nowInner = System.currentTimeMillis()
            val currentInner = cachedToken
            if (currentInner != null && nowInner < tokenExpiresAt - 60_000L) {
                return@withLock currentInner
            }

            SpotifyCanvasLogger.d("Fetching new Spotify Client-Token...")
            fetchNewToken()
        }
    }

    private suspend fun fetchNewToken(): String? {
        return try {
            // Payload shaped like a real open.spotify.com web player handshake — the
            // "computer" device profile is what the live endpoint accepts (the old
            // android/tablet profile paired with the bad client_id produced 400s).
            val deviceId = java.util.UUID.randomUUID().toString().replace("-", "")
            val requestBody = """
                {
                    "client_data": {
                        "client_version": "1.2.57.601.g9b4c09e3",
                        "client_id": "$CLIENT_ID",
                        "js_sdk_data": {
                            "device_brand": "unknown",
                            "device_model": "unknown",
                            "os": "windows",
                            "os_version": "NT 10.0",
                            "device_id": "$deviceId",
                            "device_type": "computer"
                        }
                    }
                }
            """.trimIndent()

            val response = httpClient.post(CLIENT_TOKEN_URL) {
                contentType(ContentType.Application.Json)
                header("Accept", "application/json")
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                setBody(requestBody)
            }

            if (response.status != HttpStatusCode.OK) {
                SpotifyCanvasLogger.w("Client-Token request failed: HTTP ${response.status.value}")
                val errBody = try { response.body<String>() } catch (_: Exception) { "unavailable" }
                SpotifyCanvasLogger.w("Client-Token error body: $errBody")
                return null
            }

            val body = response.body<String>()
            val root = json.parseToJsonElement(body).jsonObject
            val token = root["granted_token"]?.jsonObject?.get("token")?.jsonPrimitive?.contentOrNull
            val expiresInMs = root["granted_token"]?.jsonObject
                ?.get("expires_after_seconds")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?.times(1000L) ?: 3_600_000L  // default 1h

            if (token.isNullOrBlank()) {
                SpotifyCanvasLogger.w("Client-Token response missing token field. Body: $body")
                return null
            }

            cachedToken = token
            tokenExpiresAt = System.currentTimeMillis() + expiresInMs
            SpotifyCanvasLogger.d("Client-Token fetched successfully, valid for ${expiresInMs / 1000}s")
            token
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SpotifyCanvasLogger.e(e, "Exception while fetching Client-Token")
            null
        }
    }

    fun clearCache() {
        cachedToken = null
        tokenExpiresAt = 0L
    }
}

// ─── Main Provider ────────────────────────────────────────────────────────────

/**
 * Fetches Spotify Canvas (looping MP4 background video) for a given track.
 *
 * Flow:
 * 1. Obtain a Client-Token from clienttoken.spotify.com
 * 2. Search Spotify catalog for the track to get its Spotify Track ID.
 * 3. Build a minimal Protobuf (EntityCanvazRequest) and POST to the spclient
 *    canvaz-cache endpoint (with both Bearer token AND Client-Token).
 * 4. Parse the response (Protobuf) to extract the canvas video URL.
 */
object SpotifyCanvasProvider {

    // Must permit null values (null = "looked up, no canvas exists"): ConcurrentHashMap
    // throws NullPointerException on a null value, which crashed playback. A synchronized
    // HashMap gives the same thread-safety while allowing the negative-cache sentinel.
    private val cache = java.util.Collections.synchronizedMap(HashMap<String, CanvasArtwork?>())

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            requestTimeoutMillis = 12_000
            socketTimeoutMillis = 12_000
        }
    }

    private const val SPOTIFY_SEARCH_API = "https://api.spotify.com/v1/search"
    private const val SPCLIENT_HOST = "https://spclient.wg.spotify.com"
    private const val CANVAZ_PATH = "/canvaz-cache/v0/canvases"

    // iOS User-Agent — the canvaz endpoint is more responsive to mobile clients
    private const val MOBILE_USER_AGENT = "Spotify/8.5.49 iOS/16.6 (iPhone14,3)"

    /**
     * Main entry point. Returns a [CanvasArtwork] with [CanvasArtwork.videoUrl] set
     * to the Spotify Canvas MP4 URL, or null if unavailable.
     */
    suspend fun getBySongArtist(
        song: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
        accessToken: String? = null,
        logFilePath: String? = null
    ): CanvasArtwork? {
        SpotifyCanvasLogger.init(logFilePath)

        if (accessToken.isNullOrBlank()) {
            SpotifyCanvasLogger.w("Cannot fetch Canvas: Spotify accessToken is null or blank!")
            return null
        }

        val cacheKey = "$song|$artist"
        if (cache.containsKey(cacheKey)) {
            val cached = cache[cacheKey]
            SpotifyCanvasLogger.d("Cache hit for key '$cacheKey': ${cached?.videoUrl ?: "no canvas"}")
            return cached
        }

        val maskedToken = if (accessToken.length > 20) {
            accessToken.take(10) + "..." + accessToken.takeLast(10)
        } else "invalid_short_token"
        SpotifyCanvasLogger.d("Starting Spotify Canvas fetch for '$song' by '$artist' (token: $maskedToken)")

        return try {
            // Step 1: Get Client-Token (required by spclient endpoints)
            val clientToken = SpotifyClientTokenManager.getToken()
            if (clientToken == null) {
                SpotifyCanvasLogger.w("Could not obtain Client-Token. Canvas fetch aborted.")
                cache[cacheKey] = null
                return null
            }
            SpotifyCanvasLogger.d("Client-Token obtained: ${clientToken.take(10)}...")

            // Step 2: Find the Spotify Track ID via search
            val trackId = searchSpotifyTrackId(song, artist, album, accessToken)
                ?: run {
                    SpotifyCanvasLogger.w("Track NOT found on Spotify catalog: '$song' – '$artist'")
                    cache[cacheKey] = null
                    return null
                }

            // Step 3: Fetch canvas from spclient
            val canvasUrl = fetchCanvasUrl(trackId, accessToken, clientToken)

            val result = if (!canvasUrl.isNullOrBlank()) {
                CanvasArtwork(
                    name = song,
                    artist = artist,
                    // Store in BOTH video fields AND animated fields so that
                    // DezzyPlayer (which reads animatedVertical/animated first) can render it.
                    animated = canvasUrl,
                    animatedVertical = canvasUrl,
                    videoUrl = canvasUrl,
                    videoUrlVertical = canvasUrl,
                )
            } else {
                null
            }

            cache[cacheKey] = result
            result
        } catch (e: CancellationException) {
            SpotifyCanvasLogger.w("Canvas fetch cancelled for '$song'")
            throw e
        } catch (e: Exception) {
            SpotifyCanvasLogger.e(e, "Error fetching Spotify Canvas for '$song'")
            cache[cacheKey] = null
            null
        }
    }

    /**
     * Fast path when the Spotify track id is already known (e.g. resolved during
     * the Spotify library preload). Skips the throttled catalog search entirely and
     * goes straight to the canvaz endpoint.
     */
    suspend fun getByTrackId(
        trackId: String,
        accessToken: String? = null,
        logFilePath: String? = null,
    ): CanvasArtwork? {
        SpotifyCanvasLogger.init(logFilePath)
        if (accessToken.isNullOrBlank()) {
            SpotifyCanvasLogger.w("getByTrackId: accessToken null/blank")
            return null
        }
        if (trackId.isBlank()) return null

        val cacheKey = "track:$trackId"
        if (cache.containsKey(cacheKey)) return cache[cacheKey]

        return try {
            val clientToken = SpotifyClientTokenManager.getToken()
            if (clientToken == null) {
                SpotifyCanvasLogger.w("getByTrackId: no Client-Token")
                cache[cacheKey] = null
                return null
            }
            val canvasUrl = fetchCanvasUrl(trackId, accessToken, clientToken)
            val result = if (!canvasUrl.isNullOrBlank()) {
                CanvasArtwork(
                    name = null,
                    artist = null,
                    animated = canvasUrl,
                    animatedVertical = canvasUrl,
                    videoUrl = canvasUrl,
                    videoUrlVertical = canvasUrl,
                )
            } else null
            cache[cacheKey] = result
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SpotifyCanvasLogger.e(e, "getByTrackId failed for $trackId")
            cache[cacheKey] = null
            null
        }
    }

    // ─── Step 1 — Track Search ────────────────────────────────────────────────

    private suspend fun searchSpotifyTrackId(
        song: String,
        artist: String,
        album: String?,
        accessToken: String,
    ): String? {
        // Try strict search first (track + artist + album)
        val strictQuery = buildString {
            append("track:\"$song\" artist:\"$artist\"")
            if (!album.isNullOrBlank()) append(" album:\"$album\"")
        }
        SpotifyCanvasLogger.d("Searching Spotify (strict): $strictQuery")
        var trackId = executeSearch(strictQuery, accessToken)

        if (trackId == null) {
            // Fallback to broad search
            val broadQuery = "$song $artist"
            SpotifyCanvasLogger.d("Strict search failed. Trying broad search: $broadQuery")
            trackId = executeSearch(broadQuery, accessToken)
        }

        return trackId
    }

    private suspend fun executeSearch(query: String, accessToken: String): String? {
        return try {
            val response = httpClient.get(SPOTIFY_SEARCH_API) {
                header("Authorization", "Bearer $accessToken")
                header("Accept", "application/json")
                header("User-Agent", MOBILE_USER_AGENT)
                parameter("q", query)
                parameter("type", "track")
                parameter("limit", "5")
            }

            if (response.status != HttpStatusCode.OK) {
                SpotifyCanvasLogger.w("Spotify search HTTP ${response.status.value}")
                val errBody = try { response.body<String>() } catch (_: Exception) { "unavailable" }
                SpotifyCanvasLogger.w("Search error body: $errBody")
                return null
            }

            val body = response.body<String>()
            val root = json.parseToJsonElement(body).jsonObject
            val tracks = root["tracks"]?.jsonObject ?: return null
            val items = tracks["items"]?.jsonArray ?: return null
            if (items.isEmpty()) {
                SpotifyCanvasLogger.d("No tracks found for: $query")
                return null
            }

            val firstItem = items.firstOrNull()?.jsonObject
            val id = firstItem?.get("id")?.jsonPrimitive?.contentOrNull
            val name = firstItem?.get("name")?.jsonPrimitive?.contentOrNull
            val artists = firstItem?.get("artists")?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString(", ")

            SpotifyCanvasLogger.d("Matched: '$name' by '$artists' → ID: $id")
            id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SpotifyCanvasLogger.e(e, "Exception during Spotify search: $query")
            null
        }
    }

    // ─── Step 2 — Canvas Fetch ────────────────────────────────────────────────

    private suspend fun fetchCanvasUrl(
        trackId: String,
        accessToken: String,
        clientToken: String,
    ): String? {
        val trackUri = "spotify:track:$trackId"
        SpotifyCanvasLogger.d("Requesting canvas for URI: $trackUri")
        val requestBody = encodeCanvazRequest(trackUri)

        return try {
            val response = httpClient.post("$SPCLIENT_HOST$CANVAZ_PATH") {
                header("Authorization", "Bearer $accessToken")
                header("Client-Token", clientToken)       // ← Required!
                header("Accept", "application/protobuf")
                header("User-Agent", MOBILE_USER_AGENT)
                header("App-platform", "iOS")
                contentType(ContentType.parse("application/protobuf"))
                setBody(requestBody)
            }

            if (response.status != HttpStatusCode.OK) {
                val code = response.status.value
                SpotifyCanvasLogger.w("spclient canvaz HTTP $code")
                val errBody = try { response.body<String>() } catch (_: Exception) { "unavailable" }
                SpotifyCanvasLogger.w("spclient error body: $errBody")

                // If 401/403, maybe the client token expired — clear it for next attempt
                if (code == 401 || code == 403) {
                    SpotifyCanvasLogger.w("Auth error on spclient — clearing Client-Token cache")
                    SpotifyClientTokenManager.clearCache()
                }
                return null
            }

            val bytes = response.body<ByteArray>()
            SpotifyCanvasLogger.d("spclient response: ${bytes.size} bytes. Decoding protobuf...")
            val url = decodeCanvazResponseUrl(bytes)
            if (url.isNullOrBlank()) {
                SpotifyCanvasLogger.w("Protobuf decoded but canvas URL is null/empty for $trackId")
            } else {
                SpotifyCanvasLogger.d("Canvas URL resolved: $url")
            }
            url
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SpotifyCanvasLogger.e(e, "Exception during spclient canvaz request for $trackId")
            null
        }
    }

    // ─── Minimal Protobuf Encoder ──────────────────────────────────────────────
    // Schema (from librespot / SimpMusic reverse engineering):
    //
    // message EntityCanvazRequest {
    //   message Entity {
    //     string entity_uri = 1;   // e.g. "spotify:track:xxxx"
    //   }
    //   repeated Entity entities = 1;
    // }

    private fun encodeCanvazRequest(trackUri: String): ByteArray {
        // Inner: Entity { entity_uri = trackUri }  (field 1, wire type 2 = length-delimited)
        val entityBytes = encodeProtoString(fieldNumber = 1, value = trackUri)
        // Outer: EntityCanvazRequest { entities[0] = entity }  (field 1, wire type 2)
        return encodeProtoBytes(fieldNumber = 1, value = entityBytes)
    }

    private fun encodeProtoString(fieldNumber: Int, value: String): ByteArray {
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().apply {
            writeVarint(((fieldNumber shl 3) or 2).toLong())
            writeVarint(valueBytes.size.toLong())
            write(valueBytes)
        }.toByteArray()
    }

    private fun encodeProtoBytes(fieldNumber: Int, value: ByteArray): ByteArray {
        return ByteArrayOutputStream().apply {
            writeVarint(((fieldNumber shl 3) or 2).toLong())
            writeVarint(value.size.toLong())
            write(value)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL == v) { write(v.toInt()); return }
            write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    // ─── Minimal Protobuf Decoder ──────────────────────────────────────────────
    // Schema (EntityCanvazResponse from librespot):
    //
    // message EntityCanvazResponse {
    //   message Canvaz {
    //     string id  = 1;
    //     string url = 2;   ← canvas video URL
    //     ...
    //     int32  type = 7;  (1 = VIDEO)
    //   }
    //   repeated Canvaz canvases = 1;
    // }

    private fun decodeCanvazResponseUrl(bytes: ByteArray): String? {
        var pos = 0
        while (pos < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, pos)
            pos = afterTag
            val fieldNumber = (tag shr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            when (wireType) {
                2 -> {
                    val (len, afterLen) = readVarint(bytes, pos)
                    pos = afterLen
                    val endPos = pos + len.toInt()
                    if (endPos > bytes.size) {
                        SpotifyCanvasLogger.w("Protobuf OOB: endPos=$endPos > size=${bytes.size}")
                        break
                    }
                    val fieldBytes = bytes.copyOfRange(pos, endPos)
                    pos = endPos
                    if (fieldNumber == 1) {
                        val url = decodeEntityCanvazUrl(fieldBytes)
                        if (!url.isNullOrBlank()) return url
                    }
                }
                0 -> { val (_, afterVal) = readVarint(bytes, pos); pos = afterVal }
                1 -> { if (pos + 8 > bytes.size) break; pos += 8 }
                5 -> { if (pos + 4 > bytes.size) break; pos += 4 }
                else -> break
            }
        }
        return null
    }

    private fun decodeEntityCanvazUrl(bytes: ByteArray): String? {
        var pos = 0
        var url: String? = null
        var canvazType: Long = 0L
        while (pos < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, pos)
            pos = afterTag
            val fieldNumber = (tag shr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            when (wireType) {
                2 -> {
                    val (len, afterLen) = readVarint(bytes, pos)
                    pos = afterLen
                    val endPos = pos + len.toInt()
                    if (endPos > bytes.size) {
                        SpotifyCanvasLogger.w("Protobuf submessage OOB: endPos=$endPos > size=${bytes.size}")
                        break
                    }
                    val fieldBytes = bytes.copyOfRange(pos, endPos)
                    pos = endPos
                    if (fieldNumber == 2) {
                        url = fieldBytes.toString(Charsets.UTF_8)
                    }
                }
                0 -> {
                    val (value, afterVal) = readVarint(bytes, pos)
                    pos = afterVal
                    if (fieldNumber == 7) canvazType = value
                }
                1 -> { if (pos + 8 > bytes.size) break; pos += 8 }
                5 -> { if (pos + 4 > bytes.size) break; pos += 4 }
                else -> break
            }
        }
        // type: VIDEO = 1, unset = 0 — accept both
        return if (canvazType == 0L || canvazType == 1L) url else null
    }

    private fun readVarint(bytes: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = offset
        while (pos < bytes.size) {
            val b = bytes[pos++].toLong() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0L) break
            shift += 7
            if (shift > 63) break  // guard against malformed varint
        }
        return result to pos
    }

    /** Clears both the in-memory canvas cache and the client token cache. */
    fun clearCache() {
        cache.clear()
        SpotifyClientTokenManager.clearCache()
    }
}
