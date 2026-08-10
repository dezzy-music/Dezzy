/**
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Message
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import com.arturo254.opentune.R
import com.arturo254.opentune.constants.SpotifyAccessTokenKey
import com.arturo254.opentune.constants.SpotifySpDcKey
import com.arturo254.opentune.constants.SpotifySpKeyKey
import com.arturo254.opentune.constants.SpotifyTokenExpiryKey
import com.arturo254.opentune.constants.SpotifyUserIdKey
import com.arturo254.opentune.constants.SpotifyUsernameKey
import com.arturo254.opentune.ui.component.IconButton
import com.arturo254.opentune.ui.utils.backToMain
import com.arturo254.opentune.utils.dataStore
import com.arturo254.opentune.spotify.Spotify
import com.arturo254.opentune.spotify.SpotifyAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }
    var retryCount by remember { mutableIntStateOf(0) }
    val tokenFetchStarted = remember { AtomicBoolean(false) }
    // Retry needs a live reference to re-run extractAndFetchToken — the login cookies are
    // already in CookieManager at that point, no need to reload the WebView or re-enter
    // credentials, but the previous retry button didn't call it at all and just left a blank
    // WebView (it had already been swapped to about:blank before the error was shown).
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Iniciar sesión en Spotify") },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )

        if (isLoading || isProcessing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()

                    WebView(ctx).apply {
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        // Spotify's login page opens "Continue with Google/Facebook/Apple" in a
                        // window.open() popup. Multiple windows must be enabled or that tap does
                        // nothing; onCreateWindow below reuses this same WebView as the target so
                        // the popup content just navigates here instead of vanishing.
                        settings.setSupportMultipleWindows(true)
                        settings.userAgentString = USER_AGENT_MOBILE

                        webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(
                                view: WebView,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message,
                            ): Boolean {
                                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                                transport.webView = view
                                resultMsg.sendToTarget()
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                // Once redirected to open.spotify.com after login, the sp_dc cookie is
                                // set. Grab it and fetch the access token in the background via TOTP
                                // (Meld's proven approach) — no fragile in-page JS injection.
                                if (url?.startsWith("https://open.spotify.com") == true &&
                                    tokenFetchStarted.compareAndSet(false, true)
                                ) {
                                    extractAndFetchToken(
                                        view, ctx, scope, navController,
                                        { isProcessing = it }, { statusMessage = it }, { hasError = it },
                                        tokenFetchStarted,
                                    )
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val requestUrl = request?.url?.toString() ?: return false

                                if (requestUrl.startsWith("intent://") || requestUrl.startsWith("spotify://")) {
                                    try {
                                        val intent = Intent.parseUri(requestUrl, Intent.URI_INTENT_SCHEME)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        ctx.startActivity(intent)
                                        return true
                                    } catch (e: Exception) {
                                        Timber.e(e, "Failed to launch intent: $requestUrl")
                                    }
                                }

                                if (requestUrl.startsWith("https://open.spotify.com")) {
                                    val spDc = extractSpDcCookie()
                                    if (spDc != null && tokenFetchStarted.compareAndSet(false, true)) {
                                        extractAndFetchToken(
                                            view, ctx, scope, navController,
                                            { isProcessing = it }, { statusMessage = it }, { hasError = it },
                                            tokenFetchStarted,
                                        )
                                        return true
                                    }
                                }
                                return false
                            }
                        }

                        loadUrl(SpotifyAuth.LOGIN_URL)
                    }.also { webViewRef = it }
                },
            )

            if (isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!hasError) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        Text(
                            text = statusMessage.ifEmpty {
                                "Iniciando sesión..."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (hasError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (hasError) {
                            Spacer(modifier = Modifier.height(16.dp))
                            TextButton(
                                onClick = {
                                    hasError = false
                                    statusMessage = ""
                                    retryCount++
                                    // The Spotify login itself already succeeded (that's how we got
                                    // here) and its cookies are still in CookieManager, so retry just
                                    // re-runs the token exchange — no WebView reload/re-login needed.
                                    tokenFetchStarted.set(true)
                                    extractAndFetchToken(
                                        webViewRef, context, scope, navController,
                                        { isProcessing = it }, { statusMessage = it }, { hasError = it },
                                        tokenFetchStarted,
                                    )
                                },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            ) {
                                Text("Reintentar")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * JavaScript injected into the open.spotify.com page to call get_access_token
 * from within the page's browser context (with its real cookies).
 * This bypasses any TOTP/client-token requirement that blocks external HTTP clients.
 */
/** Reads the sp_dc cookie from the CookieManager, or null if not yet available. */
private fun extractSpDcCookie(): String? {
    val allCookies = CookieManager.getInstance().getCookie("https://open.spotify.com")
    if (allCookies.isNullOrBlank()) return null
    return allCookies.split(";")
        .mapNotNull {
            val p = it.trim().split("=", limit = 2)
            if (p.size == 2) p[0].trim() to p[1].trim() else null
        }
        .firstOrNull { it.first == "sp_dc" && it.second.isNotBlank() }
        ?.second
}

/**
 * Extracts sp_dc/sp_key cookies and fetches the access token in the background via TOTP
 * ([SpotifyAuth.fetchAccessToken]) — the same approach Meld uses. No in-page JS injection,
 * which is what previously left the WebView on a black screen.
 */
private fun extractAndFetchToken(
    view: WebView?,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    navController: NavController,
    setProcessing: (Boolean) -> Unit,
    setStatus: (String) -> Unit,
    setError: (Boolean) -> Unit,
    tokenFetchStarted: AtomicBoolean,
) {
    val allCookies = CookieManager.getInstance().getCookie("https://open.spotify.com")
    val cookieMap = allCookies?.split(";")
        ?.mapNotNull {
            val p = it.trim().split("=", limit = 2)
            if (p.size == 2 && p[0].trim().isNotEmpty()) p[0].trim() to p[1].trim() else null
        }?.toMap() ?: emptyMap()

    val spDc = cookieMap["sp_dc"]
    if (spDc.isNullOrBlank()) {
        setProcessing(true)
        setStatus("No se encontró la sesión (sp_dc). Completa el inicio de sesión.")
        setError(true)
        tokenFetchStarted.set(false)
        return
    }
    val spKey = cookieMap["sp_key"] ?: ""

    setProcessing(true)
    setError(false)
    setStatus("Verificando sesión de Spotify...")
    view?.stopLoading()
    view?.loadUrl("about:blank")

    scope.launch(Dispatchers.IO) {
        try {
            context.dataStore.edit { prefs ->
                prefs[SpotifySpDcKey] = spDc
                prefs[SpotifySpKeyKey] = spKey
            }

            withContext(Dispatchers.Main) { setStatus("Conectando con Spotify...") }

            val token = SpotifyAuth.fetchAccessToken(spDc, spKey).getOrThrow()
            Spotify.accessToken = token.accessToken

            withContext(Dispatchers.Main) { setStatus("Cargando perfil...") }
            Spotify.me().onSuccess { user ->
                context.dataStore.edit { prefs ->
                    prefs[SpotifyUsernameKey] = user.displayName ?: user.id
                    prefs[SpotifyUserIdKey] = user.id
                }
            }.onFailure { e -> Timber.w(e, "SpotifyLogin: profile fetch failed (non-fatal)") }

            context.dataStore.edit { prefs ->
                prefs[SpotifyAccessTokenKey] = token.accessToken
                prefs[SpotifyTokenExpiryKey] = token.accessTokenExpirationTimestampMs
            }

            withContext(Dispatchers.Main) { setStatus("¡Conectado a Spotify!") }
            delay(300)
            withContext(Dispatchers.Main) { navController.navigateUp() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "SpotifyLogin: login failed — ${e.message}")
            withContext(Dispatchers.Main) {
                setStatus("Error: ${e.localizedMessage ?: e.message ?: "desconocido"}")
                setError(true)
                tokenFetchStarted.set(false)
            }
        }
    }
}

// A mismatched "desktop Chrome on a touchscreen" fingerprint is a classic bot-detection signal
// and Spotify/Google's login pages open SSO in ways that assume a real mobile browser. Mirrors
// the mobile UA already proven to work for Google OAuth elsewhere in this app (GoogleIdentityBridge,
// SocialScreen's GoogleLoginWebView).
private const val USER_AGENT_MOBILE =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
