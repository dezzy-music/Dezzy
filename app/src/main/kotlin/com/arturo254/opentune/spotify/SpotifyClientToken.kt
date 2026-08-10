/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.spotify

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Fetches and caches the Spotify **Client-Token** that the real open.spotify.com web
 * player sends alongside its Bearer token on every `api.spotify.com` request. Without
 * it, a bare web-player access token is throttled with HTTP 429 almost immediately;
 * with it, the token gets the full web-player rate-limit quota.
 *
 * The token is issued by `clienttoken.spotify.com` (no user auth needed) and is valid
 * for ~14 days, so it's cached in-memory and only refetched on expiry.
 */
object SpotifyClientToken {
    private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"

    // Real open.spotify.com web-player client_id (verified against the live endpoint).
    private const val CLIENT_ID = "d8a5ed958d274c2e8ee717e6a4b0971d"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedToken: String? = null
    @Volatile private var expiresAtMs: Long = 0L

    /**
     * Returns a valid Client-Token, fetching a new one if needed. Blocking (call from
     * an IO thread). Returns null if it can't be obtained — callers should proceed
     * without the header rather than fail.
     */
    @Synchronized
    fun get(): String? {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < expiresAtMs - 60_000L) return it }
        return runCatching { fetch() }.getOrElse {
            Timber.w(it, "SpotifyClientToken: fetch failed")
            null
        }
    }

    fun clear() {
        cachedToken = null
        expiresAtMs = 0L
    }

    private fun fetch(): String? {
        val deviceId = UUID.randomUUID().toString().replace("-", "")
        val body = JSONObject().apply {
            put("client_data", JSONObject().apply {
                put("client_version", "1.2.57.601.g9b4c09e3")
                put("client_id", CLIENT_ID)
                put("js_sdk_data", JSONObject().apply {
                    put("device_brand", "unknown")
                    put("device_model", "unknown")
                    put("os", "windows")
                    put("os_version", "NT 10.0")
                    put("device_id", deviceId)
                    put("device_type", "computer")
                })
            })
        }.toString()

        val request = Request.Builder()
            .url(CLIENT_TOKEN_URL)
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val str = response.body?.string().orEmpty()
            if (!response.isSuccessful || str.isBlank()) {
                Timber.w("SpotifyClientToken: HTTP ${response.code}")
                return null
            }
            val granted = JSONObject(str).optJSONObject("granted_token") ?: return null
            val token = granted.optString("token", "")
            if (token.isBlank()) return null
            val expiresInSec = granted.optLong("expires_after_seconds", 1_209_600L)
            cachedToken = token
            expiresAtMs = System.currentTimeMillis() + expiresInSec * 1000L
            return token
        }
    }
}
