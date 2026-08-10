/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.social

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.arturo254.opentune.constants.AccountNameKey
import com.arturo254.opentune.constants.InnerTubeCookieKey
import com.arturo254.opentune.utils.SupabaseConfig
import com.arturo254.opentune.utils.dataStore
import com.arturo254.opentune.utils.reportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.URLDecoder

/**
 * Keeps the social-network (Supabase) identity in lockstep with the YouTube/Google login, and
 * owns the Google OAuth flow used to link a real Google account to it.
 *
 * Google no longer allows its OAuth consent screen to load inside an embedded WebView for
 * registered OAuth clients — it serves "Error 403: disallowed_useragent" regardless of user
 * agent spoofing or whether the WebView is visible. There is no silent/headless way around this
 * (that is the point of the policy). The only supported path is a real browser: this launches
 * the authorize URL in a Chrome Custom Tab and gets the result back via the `dezzy://social-callback`
 * redirect, which [com.arturo254.opentune.MainActivity] hands to [handleCallback].
 *
 * Requires `dezzy://social-callback` to be added to the Supabase project's Auth → URL
 * Configuration → Redirect URLs allow list, or `/auth/v1/authorize` rejects the redirect_to.
 */
object GoogleIdentityBridge {
    private const val TAG = "GoogleIdentityBridge"
    private const val REDIRECT_URI = "dezzy://social-callback"

    val authorizeUrl: String =
        "${SupabaseConfig.URL}/auth/v1/authorize?provider=google&redirect_to=$REDIRECT_URI"

    private var autoSyncStarted = false

    data class ImplicitTokens(val access: String, val refresh: String?, val expiresIn: Long)

    /** Parses the Supabase implicit-flow tokens out of any URL/URI that carries them (fragment or query). */
    fun parseImplicitTokens(url: String?): ImplicitTokens? {
        if (url == null || !url.contains("access_token=")) return null
        val blob = url.substringAfter("#", "").ifBlank { url.substringAfter("?", "") }
        val params = blob.split("&").associate { p ->
            val kv = p.split("=", limit = 2)
            kv[0] to kv.getOrElse(1) { "" }
        }
        val access = params["access_token"]?.takeIf { it.isNotBlank() } ?: return null
        return ImplicitTokens(
            access = URLDecoder.decode(access, "UTF-8"),
            refresh = params["refresh_token"]?.takeIf { it.isNotBlank() }
                ?.let { URLDecoder.decode(it, "UTF-8") },
            expiresIn = params["expires_in"]?.toLongOrNull() ?: 3600L,
        )
    }

    /**
     * Opens Google's account chooser in a Custom Tab. If the device's default browser (usually
     * Chrome) is already signed into a Google account, this is a single tap away — no password.
     *
     * Explicitly prefers Chrome over whatever the device's default Custom Tabs provider happens
     * to be: some third-party browsers (seen in testing: Opera GX) inject their own overlay/ad
     * chrome into the tab and can hang indefinitely on this exact redirect chain. Chrome is what
     * Google's own OAuth documentation assumes and is known to work.
     */
    fun launchGoogleLink(context: Context) {
        val packageName = CustomTabsClient.getPackageName(
            context,
            listOf("com.android.chrome"),
            true, // ignoreDefault: don't let a third-party default browser override this preference
        )
        CustomTabsIntent.Builder()
            .build()
            .apply { if (packageName != null) intent.setPackage(packageName) }
            .launchUrl(context, authorizeUrl.toUri())
    }

    /**
     * Called by MainActivity when the `dezzy://social-callback` redirect comes back from the
     * Custom Tab. Extracts the tokens and finishes signing the social session in.
     */
    suspend fun handleCallback(uri: Uri, seedName: String? = null): Boolean {
        val tokens = parseImplicitTokens(uri.toString())
        if (tokens == null) {
            Timber.tag(TAG).w("social-callback redirect carried no tokens: $uri")
            return false
        }
        return try {
            SupabaseAuth.signInWithTokens(
                access = tokens.access,
                refresh = tokens.refresh,
                expiresIn = tokens.expiresIn,
                seedName = seedName,
            )
        } catch (e: Exception) {
            reportException(e)
            false
        }
    }

    /**
     * Observes the YouTube login state (SAPISID cookie in DataStore) and keeps a usable Supabase
     * session around: a per-device anonymous identity by default, dropped when YouTube signs out.
     * Linking that identity to a *real* Google account is always an explicit, user-initiated tap
     * (see [launchGoogleLink]) — Google's policy above rules out doing it silently.
     */
    fun startAutoSync(context: Context, scope: CoroutineScope) {
        if (autoSyncStarted) return
        autoSyncStarted = true
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            var wasSignedIn: Boolean? = null
            appContext.dataStore.data
                .map { prefs ->
                    (prefs[InnerTubeCookieKey]?.contains("SAPISID") == true) to
                        prefs[AccountNameKey].orEmpty()
                }
                .distinctUntilChanged()
                .collect { (signedIn, accountName) ->
                    val previous = wasSignedIn
                    wasSignedIn = signedIn
                    try {
                        when {
                            signedIn && (SupabaseAuth.currentUserId.value == null) -> {
                                if (SupabaseAuth.ensureSession()) {
                                    SupabaseAuth.ensureProfile(accountName.ifBlank { null })
                                }
                            }
                            !signedIn && previous == true -> {
                                // The user signed out of YouTube/Google: drop the social session
                                // too so the app never keeps an orphaned identity around.
                                Timber.tag(TAG).i("YouTube signed out → closing social session")
                                SupabaseAuth.logout()
                            }
                        }
                    } catch (e: Exception) {
                        reportException(e)
                    }
                }
        }
    }
}
