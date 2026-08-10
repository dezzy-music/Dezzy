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

    fun invalidateCachedStreamUrls(videoId: String) {
        val prefix = "$videoId:"
        streamUrlCache.keys.removeIf { it.startsWith(prefix) }
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
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )

    private val VIDEO_MODE_CLIENTS: List<YouTubeClient> = listOf(ANDROID_VR_NO_AUTH, WEB_REMIX, IOS, ANDROID_MUSIC)

    /**
     * Resolves a muxed (progressive, single-file) video+audio stream for "watch the music video"
     * mode. YouTube only serves muxed formats up to 720p (itag 22) / 360p (itag 18) — true 1080p+
     * requires merging separate adaptive video-only and audio-only tracks, which this deliberately
     * does not do: video mode runs on its own standalone player (see MusicService), so keeping its
     * resolution path this simple means it can never destabilize the hardened main audio pipeline.
     */
    suspend fun videoPlaybackDataForPlayback(
        videoId: String,
        playlistId: String? = null,
        maxHeight: Int = 720,
    ): Result<VideoPlaybackData> = runCatching {
        var lastError: Throwable? = null
        for (client in VIDEO_MODE_CLIENTS) {
            try {
                val signatureTimestamp = if (client.useSignatureTimestamp) getSignatureTimestampOrNull(videoId) else null
                val response = YouTube.player(videoId, playlistId, client, signatureTimestamp).getOrNull() ?: continue
                if (response.playabilityStatus.status != "OK") continue

                val candidates = selectMuxedFormatCandidates(response, maxHeight)
                for (candidate in candidates.take(4)) {
                    val cacheKey = buildCacheKey(videoId, candidate.itag)
                    val cached = streamUrlCache[cacheKey]
                    val url =
                        if (cached != null && cached.expiresAtMs > System.currentTimeMillis()) {
                            cached.url
                        } else {
                            findUrlOrNull(candidate, videoId, client) ?: continue
                        }
                    val expiresIn = response.streamingData?.expiresInSeconds ?: continue
                    streamUrlCache[cacheKey] =
                        CachedStreamUrl(url, System.currentTimeMillis() + expiresIn * 1000L)
                    return@runCatching VideoPlaybackData(candidate, url, expiresIn)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No muxed video stream available for $videoId")
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
        val attempts =
            when (audioQuality) {
                AudioQuality.HIGHEST -> listOf(AudioQuality.HIGHEST, AudioQuality.HIGH)
                AudioQuality.AUTO -> listOf(AudioQuality.AUTO, AudioQuality.HIGH)
                else -> listOf(audioQuality)
            }.distinct()

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
                    )
                }
            if (attemptResult.isSuccess) return@runCatching attemptResult.getOrThrow()
            lastError = attemptResult.exceptionOrNull()
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
        val metadataClient = ANDROID_VR_NO_AUTH

        if (isCancelled?.invoke() == true) throw kotlin.coroutines.cancellation.CancellationException("Playback was cancelled")
        Timber.tag(logTag).i("Fetching metadata response using client: ${metadataClient.clientName}")
        val metadataPlayerResponse =
            YouTube.player(videoId, playlistId, metadataClient, signatureTimestampFor(metadataClient)).getOrThrow()
        val audioConfig = metadataPlayerResponse.playerConfig?.audioConfig
        val videoDetails = metadataPlayerResponse.videoDetails
        val playbackTracking = metadataPlayerResponse.playbackTracking
        val expectedDurationMs = videoDetails?.lengthSeconds?.toLongOrNull()?.takeIf { it > 0 }?.times(1000L)

        val streamClients =
            buildList {
                add(ANDROID_VR_NO_AUTH)        // primary/priority: fastest direct-URL path
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

        val botDetectedClients = mutableSetOf<String>()
        var gateFailure: PlaybackGateFailure? = null

        for ((index, client) in streamClients.withIndex()) {
            if (isCancelled?.invoke() == true) throw kotlin.coroutines.cancellation.CancellationException("Playback was cancelled")
            format = null
            streamUrl = null
            streamExpiresInSeconds = null
            streamPlayerResponse = null

            Timber.tag(logTag).v(
                "Trying ${if (client == MAIN_CLIENT) "MAIN_CLIENT" else "fallback client"} ${index + 1}/${streamClients.size}: ${client.clientName}"
            )

            if (client != MAIN_CLIENT && client.loginRequired && !isLoggedIn) {
                Timber.tag(logTag).w("Skipping client ${client.clientName} - requires login but user is not logged in")
                continue
            }

            streamPlayerResponse =
                if (client == metadataClient) {
                    metadataPlayerResponse
                } else {
                    Timber.tag(logTag).i("Fetching player response for fallback client: ${client.clientName}")
                    YouTube.player(videoId, playlistId, client, signatureTimestampFor(client)).getOrNull()
                }

            if (streamPlayerResponse == null) continue

            if (streamPlayerResponse.playabilityStatus.status != "OK") {
                val reason = streamPlayerResponse.playabilityStatus.reason.orEmpty()
                val isLoginRecovery = isLoginRecoveryError(reason)
                val isBotDetection = isBotDetectionError(reason)
                Timber.tag(logTag).w(
                    "Player response status not OK: ${streamPlayerResponse.playabilityStatus.status}, reason: $reason, loginRecovery: $isLoginRecovery, botDetection: $isBotDetection"
                )
                if (isLoginRecovery) {
                    gateFailure = PlaybackGateFailure(
                        clientName = client.clientName,
                        status = streamPlayerResponse.playabilityStatus.status,
                        reason = streamPlayerResponse.playabilityStatus.reason,
                    )
                } else if (isBotDetection) {
                    botDetectedClients.add(client.clientName)
                    markClientBotDetected(client.clientName)
                }
                continue
            }

            val isMetered = networkMetered ?: connectivityManager.isActiveNetworkMetered
            val candidates =
                selectAudioFormatCandidates(
                    streamPlayerResponse,
                    audioQuality,
                    isMetered,
                    avoidCodecs = avoidCodecs,
                )

            if (candidates.isEmpty()) continue

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

            if (selectedFormat == null || selectedUrl == null) continue

            format = selectedFormat
            streamUrl = selectedUrl
            streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds

            if (streamExpiresInSeconds == null) continue

            Timber.tag(logTag).i("Format found: ${format.mimeType}, bitrate: ${format.bitrate}")
            Timber.tag(logTag).v("Stream expires in: $streamExpiresInSeconds seconds")

            // ANDROID_VR direct URLs are reliable and are the primary fast path, so skip the extra
            // validation round-trip. If such a URL does 403 at playback time, MusicService marks the
            // client failed and re-extracts, so this stays recoverable.
            if (canSkipStreamValidation(client, format)) {
                Timber.tag(logTag).i("Skipping stream validation for fast direct-URL client: ${client.clientName}")
                break
            }

            val valid = validateStatus(streamUrl, client.userAgent)
            if (valid) {
                Timber.tag(logTag).i("Stream validated successfully with client: ${client.clientName}")
                break
            }

            Timber.tag(logTag).w("Stream validation failed with client: ${client.clientName}, trying next fallback")
            format = null
            streamUrl = null
            streamExpiresInSeconds = null
            streamPlayerResponse = null
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
        return client.clientName == "ANDROID_VR" && format.url != null
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
