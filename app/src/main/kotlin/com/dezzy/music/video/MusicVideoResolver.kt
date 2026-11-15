/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.video

import android.content.Context
import android.content.SharedPreferences
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.utils.YTPlayerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Resuelve el videoclip de una canción para el «modo video».
 *
 * Diseño pensado para producción a gran escala:
 * - La resolución ocurre EN CADA DISPOSITIVO. Las URLs de googlevideo van atadas a la IP que
 *   las pidió (`/ip/…`), así que un servidor central no puede resolver por millones de usuarios;
 *   repartir el trabajo en los clientes es lo que escala.
 * - Cliente VISIONOS de InnerTube: con un visitorData fresco devuelve streams directos (sin
 *   poToken ni descifrado JS) y un manifiesto HLS con variantes H.264+AAC de 144p a 1080p.
 * - Cada dispositivo hace el mínimo de peticiones: caché de resultados hasta su caducidad,
 *   deduplicación de peticiones simultáneas, como mucho 3 resoluciones en paralelo, backoff
 *   cuando YouTube limita, y el mapeo canción→videoclip se guarda 30 días.
 */
object MusicVideoResolver {
    private const val TAG = "MusicVideoResolver"

    const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    private const val CLIENT_NAME = "VISIONOS"
    private const val CLIENT_VERSION = "1.02"
    private const val CLIENT_ID = "101"
    private const val DEVICE_MAKE = "Apple"
    private const val DEVICE_MODEL = "RealityDevice17,1"
    private const val OS_NAME = "visionOS"
    private const val OS_VERSION = "26.5.23O471"
    private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    private const val VISITOR_URL = "https://www.youtube.com/sw.js_data"

    private const val VISITOR_TTL_MS = 6L * 60 * 60 * 1000
    private const val DEFAULT_EXPIRES_SEC = 21_540L
    private const val EXPIRY_SAFETY_MS = 5L * 60 * 1000
    private const val MAX_RESULT_ENTRIES = 64
    private const val MAX_PARALLEL_RESOLVES = 3
    private const val BACKOFF_MS = 10L * 60 * 1000
    private const val MAPPING_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val NEGATIVE_TTL_MS = 24L * 60 * 60 * 1000
    private const val MAPPING_PREFS = "music_video_mapping"
    private const val MAPPING_MAX_ENTRIES = 2_000
    private const val MIN_MATCH_SCORE = 5.0

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    /** Streams resueltos para un video concreto. */
    data class ResolvedVideo(
        val videoId: String,
        /** Manifiesto HLS maestro (video+audio en el mismo stream, bitrate adaptativo). */
        val hlsUrl: String?,
        /** Video progresivo sin audio (se combina con [audioUrl]). */
        val videoUrl: String?,
        /** Audio progresivo para combinar con [videoUrl]. */
        val audioUrl: String?,
        /** Progresivo con video+audio juntos (itag 18 y similares). */
        val muxedUrl: String?,
        val height: Int?,
        val title: String?,
        val author: String?,
        val lengthSeconds: Int?,
        val expiresAtMs: Long,
        val source: String,
    ) {
        /** Pista «Art Track» de YouTube Music: solo la portada fija, no es un videoclip. */
        val isArtTrack: Boolean get() = author?.endsWith(" - Topic") == true

        /** Stream único que un receptor Cast puede reproducir, con su tipo MIME. */
        fun castStream(): Pair<String, String>? = when {
            muxedUrl != null -> muxedUrl to "video/mp4"
            hlsUrl != null -> hlsUrl to "application/x-mpegURL"
            else -> null
        }

        /** URL de video para usos sin audio (fondo animado del reproductor). */
        fun silentVideoUrl(): String? = videoUrl ?: muxedUrl ?: hlsUrl
    }

    /** Errores tipados con el mensaje que se enseña al usuario. */
    sealed class VideoError(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause) {
        class NotFound : VideoError("No encontramos un videoclip para esta canción.")
        class RegionBlocked : VideoError("Este videoclip no está disponible en tu región.")
        class AgeRestricted : VideoError("Este videoclip tiene restricción de edad y no se puede ver aquí.")
        class RateLimited : VideoError("YouTube está limitando las peticiones. Inténtalo de nuevo en unos minutos.")
        class Network(cause: Throwable?) : VideoError("Sin conexión. Revisa tu red e inténtalo de nuevo.", cause)
        class Unplayable(reason: String?) : VideoError(
            reason?.takeIf { it.isNotBlank() }?.let { "No se puede reproducir este videoclip: $it" }
                ?: "No se puede reproducir este videoclip.",
        )
    }

    /** Mensaje para el usuario a partir de cualquier error del resolvedor. */
    fun userMessageFor(error: Throwable?): String =
        (error as? VideoError)?.userMessage ?: "No hay video musical disponible para esta canción."

    /** Cliente HTTP propio: lo usan tanto la API de InnerTube como la descarga de los streams. */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(YouTube.streamProxy)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // ---- Estado en memoria ----

    private val resultCache = object : LinkedHashMap<String, ResolvedVideo>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedVideo>?): Boolean =
            size > MAX_RESULT_ENTRIES
    }
    private val keyLocks = Array(32) { Mutex() }
    private val resolveGate = Semaphore(MAX_PARALLEL_RESOLVES)
    private val visitorMutex = Mutex()

    @Volatile private var visitorCache: Pair<String, Long>? = null

    @Volatile private var backoffUntilMs = 0L

    @Volatile private var mappingPrefs: SharedPreferences? = null

    // ---- API pública ----

    /**
     * Resuelve el videoclip de una canción. Si [mediaId] es una pista «Art Track» (solo portada),
     * busca el videoclip oficial y recuerda el emparejamiento para no repetir la búsqueda.
     */
    suspend fun resolveForSong(
        context: Context,
        mediaId: String,
        title: String?,
        artist: String?,
        durationSec: Int?,
        maxHeight: Int,
    ): Result<ResolvedVideo> = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = prefs(context)
            val mapped = readMapping(prefs, mediaId)
            if (mapped == MAPPING_NONE) throw VideoError.NotFound()
            if (mapped != null) {
                try {
                    return@runCatching resolveStreams(mapped, maxHeight)
                } catch (e: VideoError) {
                    if (e is VideoError.Network || e is VideoError.RateLimited) throw e
                    // El videoclip emparejado dejó de estar disponible: se vuelve a buscar.
                    prefs.edit().remove(mediaId).apply()
                }
            }

            var directVideo: ResolvedVideo? = null
            var directError: VideoError? = null
            try {
                directVideo = resolveStreams(mediaId, maxHeight)
            } catch (e: VideoError) {
                if (e is VideoError.Network || e is VideoError.RateLimited) throw e
                directError = e
            }

            if (directVideo != null && !directVideo.isArtTrack) {
                writeMapping(prefs, mediaId, mediaId)
                return@runCatching directVideo
            }

            val searchTitle = title?.takeIf { it.isNotBlank() } ?: directVideo?.title
            val searchArtist = artist?.takeIf { it.isNotBlank() }
                ?: directVideo?.author?.removeSuffix(" - Topic")
            val searchDuration = durationSec?.takeIf { it > 0 } ?: directVideo?.lengthSeconds
            val candidate = findMusicVideo(searchTitle, searchArtist, searchDuration, exclude = mediaId)
            if (candidate == null) {
                writeMapping(prefs, mediaId, MAPPING_NONE)
                throw directError ?: VideoError.NotFound()
            }
            val video = resolveStreams(candidate, maxHeight)
            writeMapping(prefs, mediaId, candidate)
            video
        }.onFailure { Timber.tag(TAG).w(it, "resolveForSong(%s) failed", mediaId) }
    }

    private val metadataMatches = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Busca el videoclip por título y artista (sin mediaId) y lo resuelve. Lo usa el fondo
     * animado del reproductor cuando no hay canvas de Spotify/Apple.
     */
    suspend fun resolveByMetadata(
        title: String,
        artist: String?,
        durationSec: Int?,
        maxHeight: Int,
    ): Result<ResolvedVideo> = withContext(Dispatchers.IO) {
        runCatching {
            val key = normalize(title) + "|" + normalize(artist.orEmpty())
            val known = metadataMatches[key]
            if (known == MAPPING_NONE) throw VideoError.NotFound()
            val videoId = known ?: findMusicVideo(title, artist, durationSec, exclude = "")
                .also { metadataMatches[key] = it ?: MAPPING_NONE }
                ?: throw VideoError.NotFound()
            resolveStreams(videoId, maxHeight)
        }
    }

    /** Resuelve directamente un videoId (sin buscar videoclip alternativo). */
    suspend fun resolve(videoId: String, maxHeight: Int): Result<ResolvedVideo> =
        withContext(Dispatchers.IO) { runCatching { resolveStreams(videoId, maxHeight) } }

    /** Olvida un resultado (por ejemplo, cuando el reproductor recibe un 403). */
    fun invalidate(videoId: String) {
        synchronized(resultCache) {
            resultCache.keys.filter { it.startsWith("$videoId:") }.forEach { resultCache.remove(it) }
        }
    }

    // ---- Resolución de streams ----

    private fun lockFor(key: String): Mutex = keyLocks[(key.hashCode() and Int.MAX_VALUE) % keyLocks.size]

    private fun cachedResult(key: String): ResolvedVideo? = synchronized(resultCache) {
        val hit = resultCache[key] ?: return@synchronized null
        if (hit.expiresAtMs > System.currentTimeMillis()) hit else {
            resultCache.remove(key)
            null
        }
    }

    private suspend fun resolveStreams(videoId: String, maxHeight: Int): ResolvedVideo {
        val key = "$videoId:$maxHeight"
        cachedResult(key)?.let { return it }
        return lockFor(key).withLock {
            // Otra corrutina pudo resolverlo mientras esperábamos el candado.
            cachedResult(key)?.let { return@withLock it }
            val resolved = if (System.currentTimeMillis() < backoffUntilMs) {
                legacyFallback(videoId, maxHeight) ?: throw VideoError.RateLimited()
            } else {
                resolveGate.withPermit { resolveWithVisionOs(videoId, maxHeight) }
            }
            synchronized(resultCache) { resultCache[key] = resolved }
            resolved
        }
    }

    private suspend fun resolveWithVisionOs(videoId: String, maxHeight: Int): ResolvedVideo {
        var refreshedVisitor = false
        for (attempt in 0..1) {
            val visitor = visitorData(forceRefresh = refreshedVisitor)
            val json = try {
                callPlayer(videoId, visitor)
            } catch (e: VideoError.RateLimited) {
                backoffUntilMs = System.currentTimeMillis() + BACKOFF_MS
                return legacyFallback(videoId, maxHeight) ?: throw e
            } catch (e: IOException) {
                throw VideoError.Network(e)
            }

            val status = json.optJSONObject("playabilityStatus")
            val code = status?.optString("status").orEmpty()
            val reason = status?.optString("reason")?.takeIf { it.isNotBlank() }
            val reasonLower = reason.orEmpty().lowercase()

            if (code == "OK") {
                parseStreams(videoId, json, maxHeight)?.let { return it }
                return legacyFallback(videoId, maxHeight) ?: throw VideoError.Unplayable(reason)
            }

            when {
                reasonLower.isAgeRelated() || code.startsWith("AGE_") || code == "CONTENT_CHECK_REQUIRED" ->
                    throw VideoError.AgeRestricted()
                code == "LOGIN_REQUIRED" && !refreshedVisitor -> {
                    // «Confirma que no eres un bot»: casi siempre es un visitorData caducado.
                    refreshedVisitor = true
                    continue
                }
                code == "LOGIN_REQUIRED" -> {
                    backoffUntilMs = System.currentTimeMillis() + BACKOFF_MS
                    return legacyFallback(videoId, maxHeight) ?: throw VideoError.RateLimited()
                }
                reasonLower.isRegionRelated() -> throw VideoError.RegionBlocked()
                code == "ERROR" -> throw VideoError.NotFound()
                else -> return legacyFallback(videoId, maxHeight) ?: throw VideoError.Unplayable(reason)
            }
        }
        throw VideoError.RateLimited()
    }

    private fun String.isAgeRelated(): Boolean =
        contains("age") && (contains("confirm") || contains("restrict")) ||
            contains("edad") || contains("inappropriate") || contains("inapropiado")

    private fun String.isRegionRelated(): Boolean =
        contains("country") || contains("país") || contains("pais") ||
            contains("region") || contains("región")

    private fun callPlayer(videoId: String, visitor: String?): JSONObject {
        val locale = YouTube.locale
        val client = JSONObject()
            .put("clientName", CLIENT_NAME)
            .put("clientVersion", CLIENT_VERSION)
            .put("deviceMake", DEVICE_MAKE)
            .put("deviceModel", DEVICE_MODEL)
            .put("osName", OS_NAME)
            .put("osVersion", OS_VERSION)
            .put("hl", locale.hl)
            .put("gl", locale.gl)
        if (!visitor.isNullOrBlank()) client.put("visitorData", visitor)
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)

        val request = Request.Builder()
            .url(PLAYER_URL)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", USER_AGENT)
            .header("X-YouTube-Client-Name", CLIENT_ID)
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .header("Origin", "https://www.youtube.com")
            .apply { if (!visitor.isNullOrBlank()) header("X-Goog-Visitor-Id", visitor) }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code == 429) throw VideoError.RateLimited()
            if (!response.isSuccessful) throw IOException("player HTTP ${response.code}")
            return JSONObject(response.body.string())
        }
    }

    /** Elige los mejores streams que respetan la calidad máxima elegida por el usuario. */
    private fun parseStreams(videoId: String, json: JSONObject, maxHeight: Int): ResolvedVideo? {
        val streaming = json.optJSONObject("streamingData") ?: return null
        val details = json.optJSONObject("videoDetails")
        val expiresSec = streaming.optLong("expiresInSeconds", DEFAULT_EXPIRES_SEC).takeIf { it > 0 } ?: DEFAULT_EXPIRES_SEC
        val hls = streaming.optString("hlsManifestUrl").takeIf { it.startsWith("http") }

        var bestVideo: JSONObject? = null
        var bestVideoRank = Long.MIN_VALUE
        var bestAudio: JSONObject? = null
        var bestAudioRank = Long.MIN_VALUE
        val adaptive = streaming.optJSONArray("adaptiveFormats") ?: JSONArray()
        for (i in 0 until adaptive.length()) {
            val format = adaptive.optJSONObject(i) ?: continue
            if (!format.optString("url").startsWith("http")) continue
            val mime = format.optString("mimeType")
            val bitrate = format.optLong("bitrate", 0L)
            when {
                mime.startsWith("video/") -> {
                    val height = format.optInt("height", 0)
                    if (height <= 0 || height > maxHeight) continue
                    // H.264 se decodifica por hardware en todos los dispositivos; VP9 de respaldo.
                    val codecRank = when {
                        mime.contains("avc1") -> 2L
                        mime.contains("vp9") || mime.contains("vp09") -> 1L
                        else -> continue
                    }
                    val rank = height * 10_000_000L + codecRank * 1_000_000L + bitrate.coerceAtMost(999_999L)
                    if (rank > bestVideoRank) {
                        bestVideoRank = rank
                        bestVideo = format
                    }
                }
                mime.startsWith("audio/mp4") -> {
                    val rank = (if (format.optInt("itag") == 140) 1_000_000_000L else 0L) + bitrate
                    if (rank > bestAudioRank) {
                        bestAudioRank = rank
                        bestAudio = format
                    }
                }
            }
        }

        var muxed: JSONObject? = null
        val formats = streaming.optJSONArray("formats") ?: JSONArray()
        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            if (!format.optString("url").startsWith("http")) continue
            val height = format.optInt("height", 0)
            if (height > maxHeight) continue
            if (muxed == null || height > muxed.optInt("height", 0)) muxed = format
        }

        val videoUrl = bestVideo?.optString("url")?.takeIf { bestAudio != null }
        val audioUrl = bestAudio?.optString("url")?.takeIf { videoUrl != null }
        val muxedUrl = muxed?.optString("url")
        if (hls == null && videoUrl == null && muxedUrl == null) return null

        return ResolvedVideo(
            videoId = videoId,
            hlsUrl = hls,
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            muxedUrl = muxedUrl,
            height = (bestVideo?.optInt("height") ?: muxed?.optInt("height"))?.takeIf { it > 0 },
            title = details?.optString("title")?.takeIf { it.isNotBlank() },
            author = details?.optString("author")?.takeIf { it.isNotBlank() },
            lengthSeconds = details?.optString("lengthSeconds")?.toIntOrNull(),
            expiresAtMs = System.currentTimeMillis() + expiresSec * 1000L - EXPIRY_SAFETY_MS,
            source = CLIENT_NAME,
        )
    }

    /** Último recurso: los clientes de respaldo del resolvedor antiguo. */
    private suspend fun legacyFallback(videoId: String, maxHeight: Int): ResolvedVideo? {
        val data = runCatching {
            YTPlayerUtils.videoPlaybackDataForPlayback(videoId, maxHeight = maxHeight).getOrNull()
        }.getOrNull() ?: return null
        val muxed = data.streamUrl
        val video = data.adaptiveVideoUrl?.takeIf { data.adaptiveAudioUrl != null }
        if (muxed == null && video == null) return null
        return ResolvedVideo(
            videoId = videoId,
            hlsUrl = null,
            videoUrl = video,
            audioUrl = data.adaptiveAudioUrl?.takeIf { video != null },
            muxedUrl = muxed,
            height = data.format.height,
            title = null,
            author = null,
            lengthSeconds = null,
            expiresAtMs = System.currentTimeMillis() +
                data.streamExpiresInSeconds.toLong().coerceAtLeast(600L) * 1000L - EXPIRY_SAFETY_MS,
            source = "legacy",
        )
    }

    // ---- visitorData ----

    private suspend fun visitorData(forceRefresh: Boolean): String? = visitorMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = visitorCache
        if (!forceRefresh && cached != null && cached.second > now) return@withLock cached.first
        val fresh = runCatching { fetchVisitorData() }
            .onFailure { Timber.tag(TAG).w(it, "No se pudo obtener visitorData") }
            .getOrNull()
        if (!fresh.isNullOrBlank()) {
            visitorCache = fresh to now + VISITOR_TTL_MS
            fresh
        } else {
            cached?.first ?: YouTube.visitorData?.takeIf { it.isNotBlank() }
        }
    }

    private fun fetchVisitorData(): String? {
        val request = Request.Builder().url(VISITOR_URL).header("User-Agent", USER_AGENT).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("sw.js_data HTTP ${response.code}")
            val raw = response.body.string()
            val start = raw.indexOf('[')
            if (start < 0) return null
            return JSONArray(raw.substring(start))
                .optJSONArray(0)
                ?.optJSONArray(2)
                ?.optJSONArray(0)
                ?.optJSONArray(0)
                ?.optString(13)
                ?.takeIf { it.isNotBlank() }
        }
    }

    // ---- Emparejamiento canción → videoclip ----

    private const val MAPPING_NONE = "\u0000none"

    private fun prefs(context: Context): SharedPreferences =
        mappingPrefs ?: synchronized(this) {
            mappingPrefs ?: context.applicationContext
                .getSharedPreferences(MAPPING_PREFS, Context.MODE_PRIVATE)
                .also { mappingPrefs = it }
        }

    /** Devuelve el videoId emparejado, [MAPPING_NONE] si se sabe que no hay, o null si no consta. */
    private fun readMapping(prefs: SharedPreferences, mediaId: String): String? {
        val raw = prefs.getString(mediaId, null) ?: return null
        val parts = raw.split('|')
        val savedAt = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val target = parts[0]
        val ttl = if (target.isEmpty()) NEGATIVE_TTL_MS else MAPPING_TTL_MS
        if (System.currentTimeMillis() - savedAt > ttl) return null
        return target.ifEmpty { MAPPING_NONE }
    }

    private fun writeMapping(prefs: SharedPreferences, mediaId: String, target: String) {
        val value = (if (target == MAPPING_NONE) "" else target) + "|" + System.currentTimeMillis()
        val editor = prefs.edit().putString(mediaId, value)
        val all = prefs.all
        if (all.size > MAPPING_MAX_ENTRIES) {
            // Poda las entradas más antiguas para que el archivo no crezca sin límite.
            all.entries
                .sortedBy { (it.value as? String)?.substringAfter('|')?.toLongOrNull() ?: 0L }
                .take(all.size - MAPPING_MAX_ENTRIES + MAPPING_MAX_ENTRIES / 10)
                .forEach { editor.remove(it.key) }
        }
        editor.apply()
    }

    private val NOISE = Regex(
        """[(\[][^)\]]*(official|oficial|video|vídeo|audio|lyric|letra|visuali[sz]er|hd|4k|remaster)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val ARTIST_SPLIT = Regex("""\s*(,|&|\bfeat\.?|\bft\.?|\bx\b|\by\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE)
    private val PENALIZED = listOf(
        "live", "en vivo", "en directo", "cover", "karaoke", "reaction", "reaccion", "8d", "slowed",
        "sped up", "nightcore", "instrumental", "tutorial", "acustico", "acoustic", "concierto",
        "concert", "remix", "teaser", "trailer", "shorts", "behind the scenes",
    )
    private val OFFICIAL = listOf("official video", "official music video", "video oficial", "videoclip", "video musical")
    private val LYRIC = listOf("lyric", "lyrics", "letra", "audio", "visualizer")

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun tokens(s: String): Set<String> =
        normalize(s).split(' ').filter { it.length > 1 || it.firstOrNull()?.isDigit() == true }.toSet()

    private fun containsPhrase(haystack: String, phrase: String): Boolean = " $haystack ".contains(" $phrase ")

    private suspend fun findMusicVideo(title: String?, artist: String?, durationSec: Int?, exclude: String): String? {
        if (title.isNullOrBlank()) return null
        val cleanTitle = NOISE.replace(title, " ").trim().ifBlank { title }
        val primaryArtist = artist?.split(ARTIST_SPLIT)?.firstOrNull { it.isNotBlank() }?.trim()
        val query = listOfNotNull(cleanTitle, primaryArtist).joinToString(" ")

        val result = YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO)
        result.exceptionOrNull()?.let { throw VideoError.Network(it) }
        val candidates = result.getOrNull()?.items.orEmpty().filterIsInstance<SongItem>().take(10)

        val wantTitle = tokens(cleanTitle)
        if (wantTitle.isEmpty()) return null
        val wantArtist = primaryArtist?.let { tokens(it) }.orEmpty()
        val songTitleNorm = normalize(title)

        return candidates
            .asSequence()
            .filter { it.id != exclude }
            .map { item -> item to score(item, wantTitle, wantArtist, songTitleNorm, durationSec) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= MIN_MATCH_SCORE }
            ?.first
            ?.id
    }

    private fun score(
        item: SongItem,
        wantTitle: Set<String>,
        wantArtist: Set<String>,
        songTitleNorm: String,
        durationSec: Int?,
    ): Double {
        val candTitleNorm = normalize(item.title)
        val candTitle = tokens(item.title)
        val candArtist = tokens(item.artists.joinToString(" ") { it.name })

        val titleOverlap = wantTitle.count { it in candTitle }.toDouble() / wantTitle.size
        if (titleOverlap < 0.6) return Double.NEGATIVE_INFINITY
        var score = titleOverlap * 4.0

        if (wantArtist.isNotEmpty()) {
            val needed = ceil(wantArtist.size * 0.6).toInt().coerceAtLeast(1)
            val hits = wantArtist.count { it in candArtist || it in candTitle }
            score += if (hits >= needed) 3.0 else -2.0
        }

        val candDuration = item.duration
        if (durationSec != null && durationSec > 0 && candDuration != null && candDuration > 0) {
            val diff = abs(candDuration - durationSec)
            score += when {
                diff <= 10 -> 2.0
                diff <= 40 -> 1.0
                diff > 150 -> -3.0
                else -> 0.0
            }
        }

        PENALIZED.forEach { word ->
            if (containsPhrase(candTitleNorm, word) && !containsPhrase(songTitleNorm, word)) score -= 3.0
        }
        if (OFFICIAL.any { containsPhrase(candTitleNorm, it) }) {
            score += 1.5
        } else if (LYRIC.any { containsPhrase(candTitleNorm, it) && !containsPhrase(songTitleNorm, it) }) {
            score -= 1.0
        }
        return score
    }
}
