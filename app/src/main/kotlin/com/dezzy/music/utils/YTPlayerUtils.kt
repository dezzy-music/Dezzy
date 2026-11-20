/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.dezzy.music.utils

import android.net.ConnectivityManager
import androidx.media3.common.PlaybackException
import com.dezzy.music.constants.AudioQuality
import com.dezzy.music.constants.PlayerStreamClient
import com.dezzy.music.innertube.NewPipeUtils
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.YouTubeClient
import com.dezzy.music.innertube.models.YouTubeClient.Companion.IOS
import com.dezzy.music.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.dezzy.music.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.dezzy.music.innertube.models.YouTubeClient.Companion.ANDROID_TESTSUITE
import com.dezzy.music.innertube.models.response.PlayerResponse
import com.dezzy.music.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
import com.dezzy.music.innertube.models.YouTubeClient.Companion.ANDROID_MUSIC
import com.dezzy.music.innertube.models.YouTubeClient.Companion.ANDROID_TESTSUITE
import com.dezzy.music.innertube.models.YouTubeClient.Companion.ANDROID_UNPLUGGED
import com.dezzy.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.dezzy.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.dezzy.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.dezzy.music.innertube.models.YouTubeClient.Companion.IPADOS
import com.dezzy.music.innertube.models.YouTubeClient.Companion.IOS_MUSIC
import com.dezzy.music.innertube.models.YouTubeClient.Companion.MOBILE
import com.dezzy.music.innertube.models.YouTubeClient.Companion.TVHTML5
import com.dezzy.music.innertube.models.YouTubeClient.Companion.VISIONOS
import com.dezzy.music.innertube.models.YouTubeClient.Companion.WEB
import com.dezzy.music.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private const val FAILED_CLIENT_BACKOFF_MS = 10 * 60 * 1000L

    class LoginRequiredForPlaybackException(
        val videoId: String,
        val targetUrl: String,
        reason: String?,
    ) : IllegalStateException(reason)

    private data class PlaybackGateFailure(
        val clientName: String,
        val status: String,
        val reason: String?,
    )

    @Volatile private var streamClientPair: Pair<java.net.Proxy?, OkHttpClient>? = null

    private fun currentStreamClient(): OkHttpClient {
        val current = YouTube.streamProxy
        streamClientPair?.let { (proxy, client) ->
            if (proxy == current) return client
        }
        val client = OkHttpClient.Builder()
            .proxy(current)
            .build()
        streamClientPair = current to client
        return client
    }
    /**
     * The main client is used for metadata and initial streams.
     * Do not use other clients for this because it can result in inconsistent metadata.
     * For example other clients can have different normalization targets (loudnessDb).
     *
     * [com.dezzy.music.innertube.models.YouTubeClient.WEB_REMIX] should be preferred here because currently it is the only client which provides:
     * - the correct metadata (like loudnessDb)
     * - premium formats
     */
    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX
    /**
     * Clients used for fallback streams in case the streams of the main client do not work.
     */
    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        IOS,
        MOBILE,
        ANDROID_MUSIC,
        IOS_MUSIC,
        ANDROID_VR_NO_AUTH,
        ANDROID_VR_1_61_48,
        ANDROID_VR_1_43_32,
        ANDROID_CREATOR,
        ANDROID_TESTSUITE,
        ANDROID_UNPLUGGED,
        IPADOS,
        VISIONOS,
        TVHTML5,
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        WEB,
        WEB_CREATOR,
        WEB_REMIX
    )
    private data class CachedStreamUrl(
        val url: String,
        val expiresAtMs: Long,
    )

    private val streamUrlCache = ConcurrentHashMap<String, CachedStreamUrl>()
    private val failedStreamClientsUntil = ConcurrentHashMap<String, Long>()

    // Bot-detected non-web clients are backed off globally (across all videos) for a short window,
    // so subsequent songs skip the clients that just got flagged instead of re-sweeping all of them.
    // Web clients are never backed off here: they are the ones that recover once a genuine poToken
    // is in place.
    private const val BOT_DETECTION_BACKOFF_MS = 5 * 60 * 1000L
    private val botDetectedClientsUntil = ConcurrentHashMap<String, Long>()

    // ── Resolvedor rápido ───────────────────────────────────────────────
    // 1) Resultado completo en memoria: una canción ya resuelta (o pre-resuelta mientras
    //    sonaba la anterior) arranca sin ninguna petición a /player.
    // 2) Una sola resolución en curso por canción: la precarga y el reproductor comparten.
    // 3) Si el cliente principal falla, los demás se prueban en paralelo (de 3 en 3) y gana
    //    el primero que da una URL buena, en lugar de esperar uno detrás de otro.
    // 4) El cliente que ganó la última vez se prueba antes.
    private data class CachedPlayback(val data: PlaybackData, val expiresAtMs: Long)
    private data class CachedVideoPlayback(val data: VideoPlaybackData, val expiresAtMs: Long)
    private data class StreamPick(
        val clientName: String,
        val format: PlayerResponse.StreamingData.Format,
        val url: String,
        val expiresInSeconds: Int,
        val response: PlayerResponse,
    )

    private const val PARALLEL_STREAM_CLIENTS = 3
    private const val FAST_START_TIMEOUT_MS = 6_000L
    private const val RESULT_CACHE_SAFETY_MS = 10 * 60 * 1000L
    private const val RESULT_CACHE_MAX = 300

    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackResultCache = ConcurrentHashMap<String, CachedPlayback>()
    private val videoResultCache = ConcurrentHashMap<String, CachedVideoPlayback>()
    private val inFlightPlayback = ConcurrentHashMap<String, Deferred<PlaybackData>>()

    @Volatile
    private var lastWinningStreamClient: String? = null

    @Volatile
    private var lastWinningVideoClient: String? = null

    fun invalidateCachedStreamUrls(videoId: String) {
        val prefix = "$videoId:"
        streamUrlCache.keys.removeIf { it.startsWith(prefix) }
        failedStreamClientsUntil.keys.removeIf { it.startsWith(prefix) }
        dropResolvedResults(videoId)
    }

    private fun dropResolvedResults(videoId: String) {
        val prefix = "$videoId|"
        playbackResultCache.keys.removeIf { it.startsWith(prefix) }
        videoResultCache.keys.removeIf { it.startsWith(prefix) }
    }

    private fun trimResultCaches() {
        val now = System.currentTimeMillis()
        if (playbackResultCache.size > RESULT_CACHE_MAX) {
            playbackResultCache.entries.removeIf { it.value.expiresAtMs <= now }
            if (playbackResultCache.size > RESULT_CACHE_MAX) playbackResultCache.clear()
        }
        if (videoResultCache.size > RESULT_CACHE_MAX / 3) {
            videoResultCache.entries.removeIf { it.value.expiresAtMs <= now }
            if (videoResultCache.size > RESULT_CACHE_MAX / 3) videoResultCache.clear()
        }
    }

    private fun resultExpiry(expiresInSeconds: Int): Long =
        System.currentTimeMillis() +
            (expiresInSeconds * 1000L - RESULT_CACHE_SAFETY_MS).coerceAtLeast(60_000L)

    /**
     * Lanza [attempt] para todos los elementos a la vez y devuelve el primer resultado no
     * nulo. Los perdedores se cancelan sin esperarlos, así una petición lenta no retrasa.
     */
    private suspend fun <T, R : Any> raceFirst(items: List<T>, attempt: suspend (T) -> R?): R? {
        if (items.isEmpty()) return null
        if (items.size == 1) return runCatching { attempt(items[0]) }.getOrNull()
        val winner = CompletableDeferred<R?>()
        val remaining = AtomicInteger(items.size)
        val jobs = items.map { item ->
            resolverScope.launch {
                try {
                    val result = try { attempt(item) } catch (_: Throwable) { null }
                    if (result != null) winner.complete(result)
                } finally {
                    if (remaining.decrementAndGet() == 0) winner.complete(null)
                }
            }
        }
        return try {
            winner.await()
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    private suspend fun playerResponseMemoized(
        memo: ConcurrentHashMap<String, PlayerResponse>,
        videoId: String,
        playlistId: String?,
        client: YouTubeClient,
        signatureTimestamp: () -> Int?,
    ): PlayerResponse? {
        memo[client.clientName]?.let { return it }
        val response = YouTube.player(videoId, playlistId, client, signatureTimestamp()).getOrNull() ?: return null
        memo[client.clientName] = response
        return response
    }

    /**
     * Pre-resuelve una canción en segundo plano (la siguiente de la cola) para que, al
     * llegar, el reproductor encuentre la URL ya lista.
     */
    fun prefetchPlayback(
        videoId: String,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferredStreamClient: PlayerStreamClient,
        avoidCodecs: Set<String>,
    ) {
        resolverScope.launch {
            playerResponseForPlayback(
                videoId = videoId,
                audioQuality = audioQuality,
                connectivityManager = connectivityManager,
                preferredStreamClient = preferredStreamClient,
                avoidCodecs = avoidCodecs,
            ).onFailure { Timber.tag(logTag).d("Prefetch failed for $videoId: ${it.message}") }
        }
    }

    private fun markClientBotDetected(clientName: String) {
        if (StreamClientUtils.isWebClient(clientName)) return
        botDetectedClientsUntil[normalizeStreamClientKey(clientName)] =
            System.currentTimeMillis() + BOT_DETECTION_BACKOFF_MS
    }

    private fun isClientBotBackedOff(clientName: String): Boolean {
        val key = normalizeStreamClientKey(clientName)
        if (key.isEmpty()) return false
        val until = botDetectedClientsUntil[key] ?: return false
        if (until <= System.currentTimeMillis()) {
            botDetectedClientsUntil.remove(key)
            return false
        }
        return true
    }

    /** Cleared after a successful poToken refresh so recovered clients can be tried again. */
    fun clearBotDetectionBackoff() {
        botDetectedClientsUntil.clear()
    }

    fun markStreamClientFailed(videoId: String, clientKey: String?, httpStatusCode: Int?) {
        if (httpStatusCode != 403) return
        dropResolvedResults(videoId)
        val normalizedClientKey = normalizeStreamClientKey(clientKey)
        if (normalizedClientKey.isEmpty()) return
        failedStreamClientsUntil[buildFailedClientKey(videoId, normalizedClientKey)] =
            System.currentTimeMillis() + FAILED_CLIENT_BACKOFF_MS
    }

    fun markPreferredClientFailed(videoId: String, client: PlayerStreamClient, httpStatusCode: Int?) {
        markStreamClientFailed(videoId, client.name, httpStatusCode)
    }

    private fun isStreamClientTemporarilyBlocked(videoId: String, clientKey: String?): Boolean {
        val normalizedClientKey = normalizeStreamClientKey(clientKey)
        if (normalizedClientKey.isEmpty()) return false
        val key = buildFailedClientKey(videoId, normalizedClientKey)
        val until = failedStreamClientsUntil[key] ?: return false
        if (until <= System.currentTimeMillis()) {
            failedStreamClientsUntil.remove(key)
            return false
        }
        return true
    }

    class InvalidPlaybackLoginContextException(
        val videoId: String,
        val targetUrl: String,
        cause: Throwable,
    ) : IllegalStateException("Invalid YouTube Music playback login context", cause)

    private fun normalizeStreamClientKey(clientKey: String?): String {
        return clientKey?.trim()?.takeIf { it.isNotBlank() }?.uppercase(Locale.US).orEmpty()
    }

    private fun buildFailedClientKey(videoId: String, clientKey: String): String {
        return "$videoId:${normalizeStreamClientKey(clientKey)}"
    }

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )

    /**
     * A muxed (video+audio in one stream) format resolved for "watch the music video" mode.
     * Deliberately simpler than [PlaybackData]: no separate adaptive audio track to merge, no
     * validation round-trip, no persistent-cache integration — this is a self-contained URL meant
     * to feed a standalone player instance (see MusicService's video mode), so it can't corrupt
     * the audio playback cache/pipeline no matter what goes wrong.
     */
    data class VideoPlaybackData(
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String?,
        val streamExpiresInSeconds: Int,
        val adaptiveVideoUrl: String? = null,
        val adaptiveAudioUrl: String? = null,
    )

    private val VIDEO_MODE_CLIENTS: List<YouTubeClient> = listOf(
        // WEB first: ANDROID_VR / IOS / TVHTML5 are currently rejected by YouTube
        // with LOGIN_REQUIRED ("Sign in to confirm you're not a bot") because those
        // requests go out without a poToken. WEB mints a fresh per-video player
        // poToken through the BotGuard bridge (PlaybackPoTokenController), so it is
        // the client that can actually return streamingData for video mode.
        WEB,
        ANDROID_VR_1_61_48,
        ANDROID_VR_1_43_32,
        IOS,
        ANDROID_TESTSUITE,
        TVHTML5,
    )

    /**
     * Resolves a video+audio stream for "watch the music video" mode.
     * Tries to find a muxed stream first. If unavailable (like for Official Videos),
     * it resolves adaptive video and audio streams.
     */
    suspend fun videoPlaybackDataForPlayback(
        videoId: String,
        playlistId: String? = null,
        maxHeight: Int = 720,
        isValidUrl: suspend (String) -> Boolean = { true },
    ): Result<VideoPlaybackData> = runCatching {
        val cacheKey = "$videoId|$maxHeight"
        videoResultCache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > System.currentTimeMillis()) {
                Timber.tag(logTag).i("videoPlaybackDataForPlayback: cache hit for $videoId")
                return@runCatching cached.data
            }
            videoResultCache.remove(cacheKey, cached)
        }
        val lastError = AtomicReference<Throwable?>(null)

        suspend fun tryVideoClient(client: YouTubeClient): Pair<String, VideoPlaybackData>? {
            try {
                Timber.tag(logTag).i("videoPlaybackDataForPlayback: Trying client ${client.clientName}")
                val signatureTimestamp = if (client.useSignatureTimestamp) getSignatureTimestampOrNull(videoId) else null
                val response = YouTube.player(videoId, playlistId, client, signatureTimestamp).getOrNull()
                if (response == null) {
                    Timber.tag(logTag).w("videoPlaybackDataForPlayback: Response is null for ${client.clientName}")
                    return null
                }
                if (response.playabilityStatus.status != "OK") {
                    Timber.tag(logTag).w("videoPlaybackDataForPlayback: Status is ${response.playabilityStatus.status} for ${client.clientName}")
                    return null
                }
                val expiresIn = response.streamingData?.expiresInSeconds ?: return null

                val candidates = selectMuxedFormatCandidates(response, maxHeight)
                Timber.tag(logTag).i("videoPlaybackDataForPlayback: Found ${candidates.size} muxed candidates for ${client.clientName}")
                if (candidates.isNotEmpty()) {
                    for (candidate in candidates.take(4)) {
                        val urlKey = buildCacheKey(videoId, candidate.itag)
                        val cached = streamUrlCache[urlKey]
                        val url =
                            if (cached != null && cached.expiresAtMs > System.currentTimeMillis()) {
                                cached.url
                            } else {
                                findUrlOrNull(candidate, videoId, client) ?: continue
                            }
                        if (!isValidUrl(url)) continue
                        streamUrlCache[urlKey] = CachedStreamUrl(url, System.currentTimeMillis() + expiresIn * 1000L)
                        return client.clientName to VideoPlaybackData(candidate, url, expiresIn)
                    }
                    return null
                }

                // Sin muxed (p. ej. videos oficiales): video y audio adaptativos.
                val videoCandidates = selectAdaptiveVideoFormatCandidates(response, maxHeight)
                val audioCandidates = selectAudioFormatCandidates(response, com.dezzy.music.constants.AudioQuality.HIGH, false)
                Timber.tag(logTag).i("videoPlaybackDataForPlayback: Found ${videoCandidates.size} adaptive video candidates, ${audioCandidates.size} adaptive audio candidates for ${client.clientName}")
                if (videoCandidates.isEmpty() || audioCandidates.isEmpty()) return null

                var resolvedVideoUrl: String? = null
                var resolvedVideoCandidate: PlayerResponse.StreamingData.Format? = null
                for (vc in videoCandidates.take(4)) {
                    val vUrl = findUrlOrNull(vc, videoId, client)
                    if (vUrl != null && isValidUrl(vUrl)) {
                        resolvedVideoUrl = vUrl
                        resolvedVideoCandidate = vc
                        break
                    }
                }
                if (resolvedVideoUrl == null || resolvedVideoCandidate == null) return null

                var resolvedAudioUrl: String? = null
                for (ac in audioCandidates.take(4)) {
                    val aUrl = findUrlOrNull(ac, videoId, client)
                    if (aUrl != null && isValidUrl(aUrl)) {
                        resolvedAudioUrl = aUrl
                        break
                    }
                }
                if (resolvedAudioUrl == null) return null
                return client.clientName to
                    VideoPlaybackData(resolvedVideoCandidate, null, expiresIn, resolvedVideoUrl, resolvedAudioUrl)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError.set(e)
                return null
            }
        }

        // Antes se probaban los seis clientes uno detrás de otro (WEB espera además a su
        // poToken). Ahora van de 3 en 3 a la vez, empezando por el que ganó la última vez.
        val lastWinner = lastWinningVideoClient
        val ordered = VIDEO_MODE_CLIENTS.sortedBy { if (it.clientName == lastWinner) 0 else 1 }
        for (wave in ordered.chunked(PARALLEL_STREAM_CLIENTS)) {
            val won = raceFirst(wave) { tryVideoClient(it) } ?: continue
            lastWinningVideoClient = won.first
            videoResultCache[cacheKey] = CachedVideoPlayback(won.second, resultExpiry(won.second.streamExpiresInSeconds))
            trimResultCaches()
            return@runCatching won.second
        }
        throw lastError.get() ?: IllegalStateException("No video stream available for $videoId")
    }

    private fun selectMuxedFormatCandidates(
        playerResponse: PlayerResponse,
        maxHeight: Int,
    ): List<PlayerResponse.StreamingData.Format> =
        playerResponse.streamingData
            ?.formats
            ?.asSequence()
            ?.filter { it.url != null || it.signatureCipher != null || it.cipher != null }
            ?.filter { (it.height ?: 0) in 1..maxHeight }
            ?.sortedWith(
                compareByDescending<PlayerResponse.StreamingData.Format> { it.url != null }
                    .thenByDescending { it.height ?: 0 }
                    .thenByDescending { it.bitrate },
            )?.toList()
            .orEmpty()

    private fun selectAdaptiveVideoFormatCandidates(
        playerResponse: PlayerResponse,
        maxHeight: Int,
    ): List<PlayerResponse.StreamingData.Format> =
        playerResponse.streamingData
            ?.adaptiveFormats
            ?.asSequence()
            ?.filter { it.width != null }
            ?.filter { it.url != null || it.signatureCipher != null || it.cipher != null }
            ?.filter { (it.height ?: 0) in 1..maxHeight }
            ?.sortedWith(
                compareByDescending<PlayerResponse.StreamingData.Format> { it.url != null }
                    .thenByDescending { it.height ?: 0 }
                    .thenByDescending { it.bitrate },
            )
            ?.toList()
            .orEmpty()

    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferredStreamClient: PlayerStreamClient = PlayerStreamClient.ANDROID_VR,
        // if provided, this preference overrides ConnectivityManager.isActiveNetworkMetered
        networkMetered: Boolean? = null,
        avoidCodecs: Set<String> = emptySet(),
        isCancelled: (() -> Boolean)? = null,
    ): Result<PlaybackData> = runCatching {
        if (isCancelled?.invoke() == true) throw kotlin.coroutines.cancellation.CancellationException("Playback was cancelled")
        val isMetered = networkMetered ?: connectivityManager.isActiveNetworkMetered
        val key = "$videoId|$audioQuality|${preferredStreamClient.name}|${avoidCodecs.sorted().joinToString(",")}|$isMetered|${playlistId.orEmpty()}"

        playbackResultCache[key]?.let { cached ->
            if (cached.expiresAtMs > System.currentTimeMillis()) {
                Timber.tag(logTag).i("Playback cache hit for $videoId (no /player request)")
                return@runCatching cached.data
            }
            playbackResultCache.remove(key, cached)
        }

        // Ya se está resolviendo (p. ej. la precarga): esperar ese resultado en vez de repetirlo.
        inFlightPlayback[key]?.let { shared ->
            val sharedResult = awaitSharedPlayback(shared, isCancelled)
            sharedResult.getOrNull()?.let { return@runCatching it }
            val error = sharedResult.exceptionOrNull()
            if (error !is kotlin.coroutines.cancellation.CancellationException) throw error ?: IllegalStateException("Failed to resolve stream")
            inFlightPlayback.remove(key, shared)
        }

        val job =
            resolverScope.async(start = CoroutineStart.LAZY) {
                resolvePlaybackUncached(
                    videoId = videoId,
                    playlistId = playlistId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    preferredStreamClient = preferredStreamClient,
                    networkMetered = isMetered,
                    avoidCodecs = avoidCodecs,
                    isCancelled = isCancelled,
                )
            }
        val existing = inFlightPlayback.putIfAbsent(key, job)
        val target =
            if (existing != null) {
                job.cancel()
                existing
            } else {
                job.invokeOnCompletion { inFlightPlayback.remove(key, job) }
                job.start()
                job
            }
        val data = awaitSharedPlayback(target, isCancelled).getOrThrow()
        playbackResultCache[key] = CachedPlayback(data, resultExpiry(data.streamExpiresInSeconds))
        trimResultCaches()
        data
    }

    /** Espera una resolución compartida, soltándola en cuanto el que pide cambia de canción. */
    private suspend fun awaitSharedPlayback(
        deferred: Deferred<PlaybackData>,
        isCancelled: (() -> Boolean)?,
    ): Result<PlaybackData> {
        while (!deferred.isCompleted) {
            if (isCancelled?.invoke() == true) {
                throw kotlin.coroutines.cancellation.CancellationException("Playback was cancelled")
            }
            withTimeoutOrNull(100) { deferred.join() }
        }
        return runCatching { deferred.await() }
    }

    private suspend fun resolvePlaybackUncached(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferredStreamClient: PlayerStreamClient,
        networkMetered: Boolean,
        avoidCodecs: Set<String>,
        isCancelled: (() -> Boolean)?,
    ): PlaybackData {
        val attempts =
            when (audioQuality) {
                AudioQuality.HIGHEST -> listOf(AudioQuality.HIGHEST, AudioQuality.HIGH)
                AudioQuality.AUTO -> listOf(AudioQuality.AUTO, AudioQuality.HIGH)
                else -> listOf(audioQuality)
            }.distinct()

        // Las respuestas de /player se reutilizan entre intentos de calidad: el segundo
        // intento sólo vuelve a elegir formato, sin repetir peticiones.
        val responseMemo = ConcurrentHashMap<String, PlayerResponse>()
        var lastError: Throwable? = null
        for (attempt in attempts) {
            if (isCancelled?.invoke() == true) throw kotlin.coroutines.cancellation.CancellationException("Playback was cancelled")
            val attemptResult =
                runCatching {
                    playerResponseForPlaybackOnce(
                        videoId = videoId,
                        playlistId = playlistId,
                        audioQuality = attempt,
                        connectivityManager = connectivityManager,
                        preferredStreamClient = preferredStreamClient,
                        networkMetered = networkMetered,
                        avoidCodecs = avoidCodecs,
                        isCancelled = isCancelled,
                        responseMemo = responseMemo,
                    )
                }
            if (attemptResult.isSuccess) return attemptResult.getOrThrow()
            lastError = attemptResult.exceptionOrNull()
            if (lastError is kotlin.coroutines.cancellation.CancellationException) throw lastError
            if (lastError is LoginRequiredForPlaybackException) throw lastError
        }
        throw lastError ?: IllegalStateException("Failed to resolve stream")
    }

    private suspend fun playerResponseForPlaybackOnce(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferredStreamClient: PlayerStreamClient,
        networkMetered: Boolean?,
        avoidCodecs: Set<String>,
        isCancelled: (() -> Boolean)? = null,
        responseMemo: ConcurrentHashMap<String, PlayerResponse> = ConcurrentHashMap(),
    ): PlaybackData {
        if (isCancelled?.invoke() == true) throw kotlin.coroutines.cancellation.CancellationException("Playback was cancelled")
        Timber.tag(logTag).i("Fetching player response for videoId: $videoId, playlistId: $playlistId")
        // Resolving the signature timestamp downloads and evaluates YouTube's JS player via
        // NewPipeExtractor, which is slow and fragile. InnerTube only sends it for clients with
        // useSignatureTimestamp = true, so resolve it lazily and only for those clients.
        val lazySignatureTimestamp = lazy { getSignatureTimestampOrNull(videoId) }
        fun signatureTimestampFor(client: YouTubeClient): Int? =
            if (client.useSignatureTimestamp) lazySignatureTimestamp.value else null

        val isLoggedIn = YouTube.cookie != null
        val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
        Timber.tag(logTag).v("Session authentication status: ${if (isLoggedIn) "Logged in" else "Not logged in"} (sessionId=${sessionId.orEmpty()})")

        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null

        val orderedFallbackClients =
            (
                if (isLoggedIn) {
                    STREAM_FALLBACK_CLIENTS.filter { it.loginSupported } + STREAM_FALLBACK_CLIENTS.filterNot { it.loginSupported }
                } else {
                    STREAM_FALLBACK_CLIENTS.toList()
                }
                ).distinct()

        val preferredYouTubeClient =
            when (preferredStreamClient) {
                PlayerStreamClient.ANDROID_VR -> ANDROID_VR_NO_AUTH
                PlayerStreamClient.WEB_REMIX -> WEB_REMIX
                PlayerStreamClient.IOS -> IOS
                PlayerStreamClient.TVHTML5 -> TVHTML5
                PlayerStreamClient.ANDROID_MUSIC -> ANDROID_MUSIC
            }

        // ANDROID_VR is the primary client for metadata AND stream: since it is also first in the
        // stream client list below, the metadata response gets reused for the stream attempt, making
        // the happy path a single /player request with no signature timestamp and no web PoToken.
        // (WEB_REMIX metadata has nicer loudnessDb, but the extra request + JS-player download is
        // exactly the latency being cut here; the fallback path still reaches WEB_REMIX if needed.)
        //
        // Arranque rápido: ANDROID_VR y VISIONOS se piden A LA VEZ. Desde muchas IPs ANDROID_VR
        // devuelve LOGIN_REQUIRED («no eres un bot») y antes eso obligaba a recorrer olas de
        // clientes cifrados (JS de NewPipe + validación) => más de 10 s por canción. VISIONOS
        // da URLs directas sin parámetro «n» y sin cifrar, así que es el respaldo inmediato.
        // Los metadatos salen del primero que responda OK (VR primero: trae loudnessDb).
        val fastStartClients =
            listOf(ANDROID_VR_NO_AUTH, VISIONOS)
                .filterNot { isClientBotBackedOff(it.clientName) || isStreamClientTemporarilyBlocked(videoId, it.clientName) }
                .ifEmpty { listOf(VISIONOS) }

        if (isCancelled?.invoke() == true) throw kotlin.coroutines.cancellation.CancellationException("Playback was cancelled")
        Timber.tag(logTag).i("Fetching fast-start responses: ${fastStartClients.joinToString { it.clientName }}")
        // Como en escritorio: gana el PRIMERO que responda OK y no se espera al otro (antes se
        // esperaba a los dos, y si uno iba lento la canción tardaba hasta FAST_START_TIMEOUT_MS).
        val okPick: Pair<YouTubeClient, PlayerResponse>? =
            raceFirst(fastStartClients) { client ->
                withTimeoutOrNull(FAST_START_TIMEOUT_MS) {
                    playerResponseMemoized(responseMemo, videoId, playlistId, client) { null }
                }?.takeIf { it.playabilityStatus?.status == "OK" }?.let { client to it }
            }
        val metadataPick: Pair<YouTubeClient, PlayerResponse>? =
            okPick ?: fastStartClients.firstNotNullOfOrNull { c -> responseMemo[c.clientName]?.let { c to it } }
        val metadataClient = metadataPick?.first ?: VISIONOS
        val metadataPlayerResponse =
            metadataPick?.second
                ?: YouTube.player(videoId, playlistId, VISIONOS, null).getOrThrow()
                    .also { responseMemo[VISIONOS.clientName] = it }
        val audioConfig = metadataPlayerResponse.playerConfig?.audioConfig
        val videoDetails = metadataPlayerResponse.videoDetails
        val playbackTracking = metadataPlayerResponse.playbackTracking
        val expectedDurationMs = videoDetails?.lengthSeconds?.toLongOrNull()?.takeIf { it > 0 }?.times(1000L)

        val streamClients =
            buildList {
                add(ANDROID_VR_NO_AUTH)        // primary/priority: fastest direct-URL path
                add(VISIONOS)                  // direct URLs too; raced together with VR
                add(preferredYouTubeClient)     // the user's configured client comes next
                addAll(orderedFallbackClients)
                add(MAIN_CLIENT)
            }.distinct().filterNot { client ->
                val blocked = isStreamClientTemporarilyBlocked(videoId, client.clientName)
                if (blocked) {
                    Timber.tag(logTag).w("Temporarily blocked stream client for $videoId: ${client.clientName}")
                }
                val botBackedOff = isClientBotBackedOff(client.clientName)
                if (botBackedOff) {
                    Timber.tag(logTag).w("Skipping recently bot-detected client: ${client.clientName}")
                }
                blocked || botBackedOff
            }

        val botDetectedClients: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val gateFailureRef = AtomicReference<PlaybackGateFailure?>(null)
        val isMetered = networkMetered ?: connectivityManager.isActiveNetworkMetered

        suspend fun tryStreamClient(client: YouTubeClient): StreamPick? {
            if (isCancelled?.invoke() == true) return null
            if (client != MAIN_CLIENT && client.loginRequired && !isLoggedIn) {
                Timber.tag(logTag).w("Skipping client ${client.clientName} - requires login but user is not logged in")
                return null
            }
            val response =
                if (client == metadataClient) {
                    metadataPlayerResponse
                } else {
                    Timber.tag(logTag).i("Fetching player response for fallback client: ${client.clientName}")
                    playerResponseMemoized(responseMemo, videoId, playlistId, client) { signatureTimestampFor(client) }
                } ?: return null

            if (response.playabilityStatus.status != "OK") {
                val reason = response.playabilityStatus.reason.orEmpty()
                val isLoginRecovery = isLoginRecoveryError(reason)
                val isBotDetection = isBotDetectionError(reason)
                Timber.tag(logTag).w(
                    "Player response status not OK (${client.clientName}): ${response.playabilityStatus.status}, reason: $reason, loginRecovery: $isLoginRecovery, botDetection: $isBotDetection"
                )
                if (isLoginRecovery) {
                    gateFailureRef.compareAndSet(
                        null,
                        PlaybackGateFailure(
                            clientName = client.clientName,
                            status = response.playabilityStatus.status,
                            reason = response.playabilityStatus.reason,
                        ),
                    )
                } else if (isBotDetection) {
                    botDetectedClients.add(client.clientName)
                    markClientBotDetected(client.clientName)
                }
                return null
            }

            val candidates = selectAudioFormatCandidates(response, audioQuality, isMetered, avoidCodecs = avoidCodecs)
            if (candidates.isEmpty()) return null

            var selectedFormat: PlayerResponse.StreamingData.Format? = null
            var selectedUrl: String? = null
            for (candidate in candidates.asSequence().take(6)) {
                if (isLoggedIn && expectedDurationMs != null && isLikelyPreview(candidate, expectedDurationMs)) continue
                if (shouldSkipCipheredWebCandidate(client, candidate)) continue
                val cacheKey = buildCacheKey(videoId, candidate.itag)
                val cached = streamUrlCache[cacheKey]
                val candidateUrl =
                    if (cached != null && cached.expiresAtMs > System.currentTimeMillis()) {
                        cached.url
                    } else {
                        findUrlOrNull(candidate, videoId, client)
                    } ?: continue
                selectedFormat = candidate
                selectedUrl = candidateUrl
                break
            }
            val chosenFormat = selectedFormat ?: return null
            val chosenUrl = selectedUrl ?: return null
            val expiresIn = response.streamingData?.expiresInSeconds ?: return null

            // ANDROID_VR direct URLs are reliable and are the primary fast path, so skip the extra
            // validation round-trip. If such a URL does 403 at playback time, MusicService marks the
            // client failed and re-extracts, so this stays recoverable.
            if (!canSkipStreamValidation(client, chosenFormat)) {
                val valid = runInterruptible(Dispatchers.IO) { validateStatus(chosenUrl, client.userAgent) }
                if (!valid) {
                    Timber.tag(logTag).w("Stream validation failed with client: ${client.clientName}")
                    return null
                }
            }
            return StreamPick(client.clientName, chosenFormat, chosenUrl, expiresIn, response)
        }

        // Primero el cliente principal (reutiliza la respuesta de metadatos: cero peticiones
        // extra). Si falla, el resto en paralelo de PARALLEL_STREAM_CLIENTS en
        // PARALLEL_STREAM_CLIENTS, empezando por el que ganó la última vez.
        val lastWinner = lastWinningStreamClient
        val (fastClients, slowClients) =
            streamClients.partition { it == ANDROID_VR_NO_AUTH || it == VISIONOS }
        val orderedSlowClients =
            slowClients.sortedBy { if (it.clientName == lastWinner) 0 else 1 }
        // VR y VISIONOS ya tienen la respuesta en memoria: la carrera entre ambos es instantánea.
        var pick: StreamPick? = raceFirst(fastClients) { tryStreamClient(it) }
        if (pick == null) {
            for (wave in orderedSlowClients.chunked(PARALLEL_STREAM_CLIENTS)) {
                if (isCancelled?.invoke() == true) throw kotlin.coroutines.cancellation.CancellationException("Playback was cancelled")
                Timber.tag(logTag).v("Racing stream clients: ${wave.joinToString { it.clientName }}")
                pick = raceFirst(wave) { tryStreamClient(it) }
                if (pick != null) break
            }
        }
        if (isCancelled?.invoke() == true) throw kotlin.coroutines.cancellation.CancellationException("Playback was cancelled")
        val gateFailure = gateFailureRef.get()
        if (pick != null) {
            lastWinningStreamClient = pick.clientName
            format = pick.format
            streamUrl = pick.url
            streamExpiresInSeconds = pick.expiresInSeconds
            streamPlayerResponse = pick.response
            Timber.tag(logTag).i("Stream resolved with ${pick.clientName}: ${pick.format.mimeType}, bitrate: ${pick.format.bitrate}")
        }

        if (streamPlayerResponse == null) {
            gateFailure?.let { failure ->
                Timber.tag(logTag).w(
                    "Playback requires login recovery for $videoId via ${failure.clientName} (${failure.status}): ${failure.reason.orEmpty()}"
                )
                throw LoginRequiredForPlaybackException(
                    videoId = videoId,
                    targetUrl = "https://music.youtube.com/watch?v=$videoId",
                    reason = failure.reason,
                )
            }
            if (botDetectedClients.isNotEmpty()) {
                Timber.tag(logTag).e("Bot detection triggered on clients: $botDetectedClients - all clients failed")
                // Ask the BotGuard pipeline to mint a fresh session poToken for the next attempt.
                com.dezzy.music.playback.potoken.PlaybackPoTokenController.onBotDetected()
                throw PlaybackException(
                    "Sign in to confirm you're not a bot",
                    null,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR
                )
            }
            Timber.tag(logTag).e("Bad stream player response - all clients failed")
            throw Exception("Bad stream player response")
        }

        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            val errorReason = streamPlayerResponse.playabilityStatus.reason
            if (isLoginRecoveryError(errorReason.orEmpty())) {
                Timber.tag(logTag).w("Playback requires login recovery for $videoId: $errorReason")
                throw LoginRequiredForPlaybackException(
                    videoId = videoId,
                    targetUrl = "https://music.youtube.com/watch?v=$videoId",
                    reason = errorReason,
                )
            }
            Timber.tag(logTag).e("Playability status not OK: $errorReason")
            throw PlaybackException(
                errorReason,
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        if (streamExpiresInSeconds == null) {
            Timber.tag(logTag).e("Missing stream expire time")
            throw Exception("Missing stream expire time")
        }

        if (format == null) {
            Timber.tag(logTag).e("Could not find suitable format for quality: $audioQuality. Available formats from last client: ${streamPlayerResponse.streamingData?.adaptiveFormats?.filter { it.isAudio }?.map { "${it.mimeType} @ ${it.bitrate}bps (itag: ${it.itag})" }}")
            throw Exception("Could not find format for quality: $audioQuality")
        }

        if (streamUrl == null) {
            Timber.tag(logTag).e("Could not find stream url for format: ${format.mimeType}, itag: ${format.itag}")
            throw Exception("Could not find stream url")
        }

        Timber.tag(logTag).i("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")

        streamUrlCache[buildCacheKey(videoId, format.itag)] =
            CachedStreamUrl(
                url = streamUrl,
                expiresAtMs = System.currentTimeMillis() + (streamExpiresInSeconds * 1000L),
            )

        return PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
        )
    }
    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(logTag).i("Fetching metadata-only player response for videoId: $videoId using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        return YouTube.player(videoId, playlistId, client = MAIN_CLIENT)
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        // optional override from user preference; if non-null, use this instead of ConnectivityManager
        networkMetered: Boolean? = null,
        avoidCodecs: Set<String> = emptySet(),
    ): PlayerResponse.StreamingData.Format? {
        val isMetered = networkMetered ?: connectivityManager.isActiveNetworkMetered
        return selectAudioFormatCandidates(
            playerResponse,
            audioQuality,
            isMetered,
            avoidCodecs = avoidCodecs,
        ).firstOrNull()
    }

    private fun selectAudioFormatCandidates(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        networkMetered: Boolean,
        avoidCodecs: Set<String> = emptySet(),
    ): List<PlayerResponse.StreamingData.Format> {
        Timber.tag(logTag).i("Finding format with audioQuality: $audioQuality, network metered: $networkMetered")

        val audioFormats =
            playerResponse.streamingData?.adaptiveFormats
                ?.asSequence()
                ?.filter { it.isAudio && it.bitrate > 0 }
                ?.filter { it.url != null || it.signatureCipher != null || it.cipher != null }
                ?.filter { format ->
                    val codec = extractCodec(format.mimeType)?.lowercase()
                    codec == null || codec !in avoidCodecs
                }
                ?.toList()
                .orEmpty()

        if (audioFormats.isEmpty()) return emptyList()

        val effectiveQuality =
            when (audioQuality) {
                AudioQuality.AUTO -> if (networkMetered) AudioQuality.HIGH else AudioQuality.HIGHEST
                else -> audioQuality
            }

        val targetBitrateBps =
            when (effectiveQuality) {
                AudioQuality.LOW -> 70_000
                AudioQuality.HIGH -> 160_000
                AudioQuality.HIGHEST -> 320_000
                AudioQuality.AUTO -> null
            }

        val preferHigher =
            compareByDescending<PlayerResponse.StreamingData.Format> { it.url != null }
                .thenByDescending { it.bitrate }
                .thenByDescending { codecRank(extractCodec(it.mimeType)) }
                .thenByDescending { it.audioSampleRate ?: 0 }

        val preferLowerAboveTarget =
            compareByDescending<PlayerResponse.StreamingData.Format> { it.url != null }
                .thenBy { it.bitrate }
                .thenByDescending { codecRank(extractCodec(it.mimeType)) }
                .thenByDescending { it.audioSampleRate ?: 0 }

        val candidates =
            if (targetBitrateBps == null) {
                audioFormats.sortedWith(preferHigher)
            } else {
                val belowOrEqual = audioFormats.filter { it.bitrate <= targetBitrateBps }
                if (belowOrEqual.isNotEmpty()) {
                    belowOrEqual.sortedWith(preferHigher)
                } else {
                    val aboveOrEqual = audioFormats.filter { it.bitrate >= targetBitrateBps }
                    if (aboveOrEqual.isNotEmpty()) aboveOrEqual.sortedWith(preferLowerAboveTarget)
                    else audioFormats.sortedWith(preferHigher)
                }
            }

        Timber.tag(logTag)
            .v(
                "Available audio formats: ${
                    candidates.take(12).map {
                        val codec = extractCodec(it.mimeType)
                        val direct = if (it.url != null) "direct" else "cipher"
                        "${it.mimeType} ($direct, codec=${codec ?: "unknown"}) @ ${it.bitrate}bps"
                    }
                }"
            )

        return candidates
    }

    private fun extractCodec(mimeType: String): String? {
        val match = Regex("""codecs="([^"]+)"""").find(mimeType) ?: return null
        return match.groupValues.getOrNull(1)?.split(",")?.firstOrNull()?.trim()
    }

    private fun isCipheredFormat(format: PlayerResponse.StreamingData.Format): Boolean {
        return format.url == null && (format.signatureCipher != null || format.cipher != null)
    }

    private fun canSkipStreamValidation(
        client: YouTubeClient,
        format: PlayerResponse.StreamingData.Format,
    ): Boolean {
        // Only the ANDROID_VR fast path (real, ready-to-play direct URLs) is trusted without probing.
        // VISIONOS también da URLs directas sin «n» (comprobado: 206 hasta el último byte).
        return (client.clientName == "ANDROID_VR" || client.clientName == "VISIONOS") && format.url != null
    }

    private fun shouldSkipCipheredWebCandidate(
        client: YouTubeClient,
        format: PlayerResponse.StreamingData.Format,
    ): Boolean {
        if (!YouTube.webClientPoTokenEnabled) return false
        if (!StreamClientUtils.isWebClient(client.clientName)) return false
        if (!isCipheredFormat(format)) return false

        Timber.tag(logTag).w(
            "Skipping ciphered ${client.clientName} stream candidate while Web PoToken is enabled; using a direct URL or fallback client instead"
        )
        return true
    }

    private fun codecRank(codec: String?): Int =
        when {
            codec.isNullOrBlank() -> 0
            codec.contains("opus", ignoreCase = true) -> 3
            codec.contains("mp4a", ignoreCase = true) -> 2
            else -> 1
        }
    private fun isLikelyPreview(
        format: PlayerResponse.StreamingData.Format,
        expectedDurationMs: Long,
    ): Boolean {
        val approx = format.approxDurationMs?.toLongOrNull() ?: return false
        if (expectedDurationMs < 90_000L) return false
        return approx in 1L..(minOf(90_000L, (expectedDurationMs * 9L) / 10L))
    }
    /**
     * Checks if the stream url returns a successful status.
     * If this returns true the url is likely to work.
     * If this returns false the url might cause an error during playback.
     */
    private fun validateStatus(url: String, userAgent: String): Boolean {
        Timber.tag(logTag).v("Validating stream URL status")
        try {
            val httpUrl = url.toHttpUrlOrNull()
            val clientParam = httpUrl?.queryParameter("c")?.trim().orEmpty()

            val resolvedUserAgent = StreamClientUtils.resolveUserAgent(clientParam).ifEmpty { userAgent }
            val originReferer = StreamClientUtils.resolveOriginReferer(clientParam)

            val probeRanges =
                if (StreamClientUtils.isWebClient(clientParam)) {
                    listOf("bytes=0-0", "bytes=262144-262145", "bytes=1048576-1048577")
                } else {
                    listOf("bytes=0-0")
                }

            for (range in probeRanges) {
                val rangeRequest =
                    okhttp3.Request.Builder()
                        .get()
                        .header("User-Agent", resolvedUserAgent)
                        .header("Range", range)
                        .apply {
                            originReferer.origin?.let { header("Origin", it) }
                            originReferer.referer?.let { header("Referer", it) }
                        }.url(url)
                        .build()

                val code = currentStreamClient().newCall(rangeRequest).execute().use { response -> response.code }
                if (code == 403) return false
                if (code !in 200..399 && code != 416) return false
            }

            return true
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Stream URL validation failed with exception")
            reportException(e)
        }
        return false
    }
    /**
     * Wrapper around the [NewPipeUtils.getSignatureTimestamp] function which reports exceptions
     */
    private fun getSignatureTimestampOrNull(
        videoId: String
    ): Int? {
        Timber.tag(logTag).i("Getting signature timestamp for videoId: $videoId")
        return NewPipeUtils.getSignatureTimestamp(videoId)
            .onSuccess { Timber.tag(logTag).i("Signature timestamp obtained: $it") }
            .onFailure {
                Timber.tag(logTag).e(it, "Failed to get signature timestamp")
                reportException(it)
            }
            .getOrNull()
    }
    /**
     * Wrapper around the [NewPipeUtils.getStreamUrl] function which reports exceptions.
     * Also patches cver to match the client version.
     */
    private fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        client: YouTubeClient? = null,
    ): String? {
        Timber.tag(logTag).i("Finding stream URL for format: ${format.mimeType}, videoId: $videoId")
        var url = NewPipeUtils.getStreamUrl(format, videoId, client)
            .onSuccess { Timber.tag(logTag).i("Stream URL obtained successfully") }
            .onFailure {
                Timber.tag(logTag).e(it, "Failed to get stream URL")
                reportException(it)
            }
            .getOrNull() ?: return null

        // Patch cver in the URL to match the client we actually used
        if (client != null) {
            url = StreamClientUtils.patchClientVersion(url, client.clientVersion)
            if (!url.contains("c=")) {
                val separator = if (url.contains("?")) "&" else "?"
                url = "$url${separator}c=${client.clientName}"
            }
        }

        return url
    }

    private fun buildCacheKey(videoId: String, itag: Int): String {
        return "$videoId:$itag"
    }

    private fun isBotDetectionError(reason: String): Boolean {
        val lower = reason.lowercase(Locale.US)
        return "bot" in lower ||
            "unusual traffic" in lower ||
            "automated" in lower ||
            "confirm" in lower && "not a" in lower ||
            "not a robot" in lower ||
            "verify" in lower && "human" in lower
    }

    private fun isLoginRecoveryError(reason: String): Boolean {
        val lower = reason.lowercase(Locale.US)
        return "confirm your age" in lower ||
            "age-restricted" in lower ||
            "age restricted" in lower ||
            "inappropriate for some users" in lower ||
            "mature audiences" in lower ||
            "adult" in lower && "sign in" in lower ||
            "allow" in lower && "youtube music" in lower
    }

    fun isBotDetectionException(error: PlaybackException): Boolean {
        val message = error.message.orEmpty()
        if (isBotDetectionError(message)) return true
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (isBotDetectionError(cause.message.orEmpty())) return true
            cause = cause.cause
        }
        return false
    }
}
