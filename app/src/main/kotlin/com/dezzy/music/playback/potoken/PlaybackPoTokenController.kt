/*
 * Dezzy Project (2026)
 *
 * Coordinates the genuine BotGuard poToken lifecycle for playback:
 *   - registers a per-video player-token minter bridge into the innertube module,
 *   - generates and persists the session/streaming token at startup (no user action needed),
 *   - refreshes everything when YouTube reports bot detection.
 *
 * This replaces the old flow where the user had to manually open a WebView and "regenerate"
 * a fake token that never actually worked.
 */

package com.dezzy.music.playback.potoken

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dezzy.music.constants.PoTokenGvsKey
import com.dezzy.music.constants.PoTokenKey
import com.dezzy.music.constants.VisitorDataKey
import com.dezzy.music.constants.WebClientPoTokenEnabledKey
import com.dezzy.music.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object PlaybackPoTokenController {
    private const val TAG = "PlaybackPoToken"
    private const val REFRESH_DEBOUNCE_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val lastRefreshAt = AtomicLong(0L)

    @Volatile private var manager: WebPoTokenManager? = null
    @Volatile private var dataStore: DataStore<Preferences>? = null

    fun init(context: Context, dataStore: DataStore<Preferences>) {
        if (manager != null) return
        this.dataStore = dataStore

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val mgr = WebPoTokenManager(context.applicationContext, httpClient)
        manager = mgr

        // Bridge: innertube asks us to mint a per-video player token for web clients.
        YouTube.poTokenMinter = minter@{ videoId ->
            val visitorData = YouTube.visitorData ?: return@minter null
            mgr.getPlayerPoToken(videoId, visitorData)
        }

        // Establish the session/streaming token proactively so the first song already has it.
        scope.launch { ensureSessionToken(forceRecreate = false) }
    }

    /**
     * Forces a fresh session token now and suspends until done. Used by the settings screen's
     * manual "regenerate" action. Returns true if a token is in place afterwards.
     */
    suspend fun forceRefresh(): Boolean {
        ensureSessionToken(forceRecreate = true)
        return !YouTube.poTokenGvs.isNullOrBlank()
    }

    /** Called by playback when YouTube rejects a request with bot detection. */
    fun onBotDetected() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshAt.get() < REFRESH_DEBOUNCE_MS) return
        scope.launch { ensureSessionToken(forceRecreate = true) }
    }

    private suspend fun ensureSessionToken(forceRecreate: Boolean) {
        refreshMutex.withLock {
            val mgr = manager ?: return
            val visitorData = resolveVisitorData() ?: run {
                Timber.tag(TAG).w("No visitorData available; cannot generate session poToken")
                return
            }

            if (forceRecreate) mgr.reset()

            val tokens = mgr.getSessionTokens(visitorData) ?: run {
                Timber.tag(TAG).w("Session poToken generation failed")
                return
            }
            lastRefreshAt.set(System.currentTimeMillis())

            // Apply immediately so in-flight playback picks it up without waiting for DataStore.
            YouTube.poTokenGvs = tokens.streamingPoToken
            YouTube.poToken = tokens.streamingPoToken
            YouTube.webClientPoTokenEnabled = true

            // Persist so it survives restarts and the existing DataStore collectors stay in sync.
            dataStore?.edit { prefs ->
                prefs[PoTokenGvsKey] = tokens.streamingPoToken
                prefs[PoTokenKey] = tokens.streamingPoToken
                prefs[VisitorDataKey] = tokens.visitorData
                prefs[WebClientPoTokenEnabledKey] = true
            }
            // A fresh token may unblock clients that were flagged before; let them be retried.
            com.dezzy.music.utils.YTPlayerUtils.clearBotDetectionBackoff()
            Timber.tag(TAG).i("Session poToken established for playback")
        }
    }

    private suspend fun resolveVisitorData(): String? {
        YouTube.visitorData?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        val fetched = YouTube.visitorData().getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        YouTube.visitorData = fetched
        return fetched
    }
}
