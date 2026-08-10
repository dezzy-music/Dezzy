/*
 * Dezzy Project (2026)
 *
 * Owns the lifecycle of the BotGuard poToken generator and exposes cheap accessors for the two
 * kinds of token playback needs:
 *   - a session/streaming token bound to visitorData (attached to stream URLs and registerPlayback)
 *   - a per-video player token bound to a videoId (sent with the /player request)
 *
 * The streaming token must be generated exactly once before any player token, so all access goes
 * through a single mutex. The generator is transparently recreated when it expires, when
 * visitorData changes, or after a failure.
 */

package com.dezzy.music.playback.potoken

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import timber.log.Timber

class WebPoTokenManager(
    private val appContext: Context,
    private val httpClient: OkHttpClient,
) {
    private val mutex = Mutex()
    private var generator: BotGuardPoTokenGenerator? = null
    private var boundVisitorData: String? = null
    private var streamingPoToken: String? = null

    data class SessionTokens(
        val visitorData: String,
        val streamingPoToken: String,
    )

    /**
     * Ensures a live generator bound to [visitorData] and returns the session/streaming token.
     * Returns null if the token could not be generated (playback then falls back to non-web clients).
     */
    suspend fun getSessionTokens(visitorData: String): SessionTokens? = mutex.withLock {
        val streaming = runCatching { ensureGeneratorLocked(visitorData) }
            .getOrElse {
                Timber.tag(TAG).e(it, "Failed to obtain session poToken")
                null
            } ?: return null
        SessionTokens(visitorData, streaming)
    }

    /**
     * Mints a per-video player token bound to [videoId]. Requires a session already established
     * for [visitorData].
     */
    suspend fun getPlayerPoToken(videoId: String, visitorData: String): String? {
        val gen = mutex.withLock {
            runCatching { ensureGeneratorLocked(visitorData) }.getOrNull()
            generator
        } ?: return null
        return runCatching { gen.generatePoToken(videoId) }
            .getOrElse {
                Timber.tag(TAG).w(it, "Failed to mint player poToken for $videoId; recreating generator")
                // Recreate once: the WebView content may have been evicted.
                mutex.withLock { recreateLocked(visitorData) }
                val fresh = generator ?: return null
                runCatching { fresh.generatePoToken(videoId) }.getOrNull()
            }
    }

    /** Must be called while holding [mutex]. Returns the streaming token. */
    private suspend fun ensureGeneratorLocked(visitorData: String): String {
        val current = generator
        if (current != null && !current.isExpired() && boundVisitorData == visitorData) {
            return streamingPoToken ?: generateStreamingLocked(current, visitorData)
        }
        return recreateLocked(visitorData)
    }

    private suspend fun recreateLocked(visitorData: String): String {
        generator?.close()
        generator = null
        streamingPoToken = null
        boundVisitorData = null

        val gen = BotGuardPoTokenGenerator.create(appContext, httpClient)
        generator = gen
        boundVisitorData = visitorData
        return generateStreamingLocked(gen, visitorData)
    }

    private suspend fun generateStreamingLocked(
        gen: BotGuardPoTokenGenerator,
        visitorData: String,
    ): String {
        val token = gen.generatePoToken(visitorData)
        streamingPoToken = token
        return token
    }

    fun reset() {
        generator?.close()
        generator = null
        streamingPoToken = null
        boundVisitorData = null
    }

    companion object {
        private const val TAG = "WebPoTokenManager"
    }
}
