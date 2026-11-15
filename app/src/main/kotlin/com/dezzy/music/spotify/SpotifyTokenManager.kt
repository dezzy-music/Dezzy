package com.dezzy.music.spotify

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dezzy.music.constants.SpotifyAccessTokenKey
import com.dezzy.music.constants.SpotifySpDcKey
import com.dezzy.music.constants.SpotifySpKeyKey
import com.dezzy.music.constants.SpotifyTokenExpiryKey
import com.dezzy.music.security.SessionCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Centralized Spotify token management. All token refresh operations go through
 * this singleton to prevent race conditions from multiple callers refreshing
 * simultaneously, which can cause redundant API calls and inconsistent state.
 */
object SpotifyTokenManager {
    private lateinit var dataStore: DataStore<Preferences>
    private val refreshMutex = Mutex()

    /*
     * Spotify devuelve tokens «anónimos» también cuando frena por exceso de peticiones, así que un
     * rechazo NO significa que sp_dc haya caducado. Reglas:
     *  - Tras cada fallo se espera cada vez más antes de volver a pedir token (no se martillea).
     *  - La cookie nunca se borra sola; sólo se avisa de volver a entrar si el rechazo se repite
     *    durante al menos [REJECTION_WINDOW_MS]. Si era un bloqueo pasajero, se recupera sola.
     */
    private val BACKOFF_MS = longArrayOf(15_000L, 60_000L, 5 * 60_000L, 15 * 60_000L)
    private const val REJECTIONS_BEFORE_RELOGIN = 3
    private const val REJECTION_WINDOW_MS = 20 * 60_000L

    @Volatile private var consecutiveFailures = 0
    @Volatile private var retryAfterMs = 0L
    @Volatile private var rejections = 0
    @Volatile private var firstRejectionAtMs = 0L

    private val _needsReLogin = MutableStateFlow(false)
    val needsReLogin: StateFlow<Boolean> = _needsReLogin.asStateFlow()

    fun init(dataStore: DataStore<Preferences>) {
        this.dataStore = dataStore
    }

    /**
     * Ensures a valid Spotify access token is available. If the current token
     * is expired, acquires the shared mutex and refreshes it via sp_dc cookie.
     * Only one refresh can happen at a time across the entire app.
     *
     * @return true if a valid token is set on [Spotify.accessToken], false otherwise
     */
    suspend fun ensureAuthenticated(): Boolean {
        val settings = dataStore.data.first()
        val accessToken = settings[SpotifyAccessTokenKey] ?: ""
        val expiry = settings[SpotifyTokenExpiryKey] ?: 0L
        val spDc = SessionCrypto.decrypt(settings[SpotifySpDcKey] ?: "")

        if (accessToken.isNotEmpty() && System.currentTimeMillis() < expiry) {
            Spotify.accessToken = accessToken
            return true
        }

        if (spDc.isEmpty()) {
            Timber.w("SpotifyTokenManager: token expired or empty, and no sp_dc cookie available")
            return false
        }

        return refreshMutex.withLock {
            // Re-read after acquiring lock — another caller may have already refreshed
            val freshSettings = dataStore.data.first()
            val freshToken = freshSettings[SpotifyAccessTokenKey] ?: ""
            val freshExpiry = freshSettings[SpotifyTokenExpiryKey] ?: 0L

            if (freshToken.isNotEmpty() && System.currentTimeMillis() < freshExpiry) {
                Spotify.accessToken = freshToken
                Timber.d("SpotifyTokenManager: token already refreshed by another caller")
                return@withLock true
            }

            val now = System.currentTimeMillis()
            if (now < retryAfterMs) {
                Timber.d("SpotifyTokenManager: backing off, next refresh in %d s", (retryAfterMs - now) / 1000)
                return@withLock false
            }

            val freshSpDc = SessionCrypto.decrypt(freshSettings[SpotifySpDcKey] ?: "")
            val freshSpKey = SessionCrypto.decrypt(freshSettings[SpotifySpKeyKey] ?: "")
            if (freshSpDc.isEmpty()) {
                Timber.w("SpotifyTokenManager: no sp_dc cookie available under lock")
                return@withLock false
            }

            Timber.d("SpotifyTokenManager: token empty or expired, fetching/refreshing via cookie...")

            SpotifyAuth.fetchAccessToken(freshSpDc, freshSpKey).fold(
                onSuccess = { token ->
                    Spotify.accessToken = token.accessToken
                    dataStore.edit { prefs ->
                        prefs[SpotifyAccessTokenKey] = token.accessToken
                        prefs[SpotifyTokenExpiryKey] = token.accessTokenExpirationTimestampMs
                    }
                    _needsReLogin.value = false
                    consecutiveFailures = 0
                    retryAfterMs = 0L
                    rejections = 0
                    firstRejectionAtMs = 0L
                    Timber.d("SpotifyTokenManager: token refreshed successfully")
                    true
                },
                onFailure = { e ->
                    Timber.e(e, "SpotifyTokenManager: refresh FAILED")

                    val failedAt = System.currentTimeMillis()
                    consecutiveFailures++
                    retryAfterMs = failedAt + BACKOFF_MS[(consecutiveFailures - 1).coerceAtMost(BACKOFF_MS.lastIndex)]

                    val rejected = e.message?.contains("anonymous") == true ||
                        e.message?.contains("HTTP 401") == true
                    if (rejected) {
                        if (firstRejectionAtMs == 0L) firstRejectionAtMs = failedAt
                        rejections++
                        val persistent = rejections >= REJECTIONS_BEFORE_RELOGIN &&
                            failedAt - firstRejectionAtMs >= REJECTION_WINDOW_MS
                        if (persistent && !_needsReLogin.value) {
                            Timber.w("SpotifyTokenManager: sp_dc rejected repeatedly; asking to sign in again (cookie kept)")
                            Spotify.accessToken = null
                            _needsReLogin.value = true
                        }
                    }

                    false
                },
            )
        }
    }

    fun clearReLoginFlag() {
        _needsReLogin.value = false
    }
}
