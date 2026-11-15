/**
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
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
import com.dezzy.music.R
import com.dezzy.music.constants.SpotifyAccessTokenKey
import com.dezzy.music.constants.SpotifySpDcKey
import com.dezzy.music.constants.SpotifySpKeyKey
import com.dezzy.music.constants.SpotifyTokenExpiryKey
import com.dezzy.music.constants.SpotifyUserIdKey
import com.dezzy.music.constants.SpotifyUsernameKey
import com.dezzy.music.security.SessionCrypto
import com.dezzy.music.spotify.Spotify
import com.dezzy.music.spotify.SpotifyAuth
import com.dezzy.music.ui.component.IconButton
import com.dezzy.music.ui.utils.backToMain
import com.dezzy.music.utils.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    var webViewLoadError by remember { mutableStateOf<String?>(null) }
    var pageStarted by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE")
    var retryCount by remember { mutableIntStateOf(0) }
    val tokenFetchStarted = remember { AtomicBoolean(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Watchdog: if the login page never starts loading, show the retry overlay
    // instead of leaving an empty screen.
    LaunchedEffect(loadAttempt) {
        delay(12_000)
        if (!pageStarted && webViewLoadError == null && !isProcessing) {
            isLoading = false
            webViewLoadError = "La pagina de Spotify no respondio. Revisa tu conexion e intentalo de nuevo."
            Timber.w("SpotifyLogin: login page never started loading")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Iniciar sesion en Spotify") },
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
                    // Start from a clean Spotify session. Done before the WebView exists
                    // so the login page never depends on the async cookie callback.
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()

                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(true)
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = USER_AGENT_MOBILE

                        webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(
                                view: WebView,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message,
                            ): Boolean {
                                val transport = resultMsg.obj as? WebView.WebViewTransport
                                    ?: return false
                                val dummyWebView = WebView(view.context)
                                dummyWebView.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        this@apply.loadUrl(request.url.toString())
                                        return true
                                    }
                                }
                                transport.webView = dummyWebView
                                resultMsg.sendToTarget()
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?,
                            ) {
                                isLoading = true
                                pageStarted = true
                                webViewLoadError = null
                                view?.evaluateJavascript(ANTI_BOT_JS, null)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    isLoading = false
                                    webViewLoadError = "No se pudo cargar la pagina de inicio de sesion (${error?.errorCode ?: "?"}). Revisa tu conexion e intentalo de nuevo."
                                    Timber.e("SpotifyLogin: WebView error ${error?.errorCode}: ${error?.description} url=${request.url}")
                                }
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onReceivedError(
                                view: WebView?,
                                errorCode: Int,
                                description: String?,
                                failingUrl: String?,
                            ) {
                                isLoading = false
                                webViewLoadError = "No se pudo cargar la pagina de inicio de sesion ($errorCode). Revisa tu conexion e intentalo de nuevo."
                                Timber.e("SpotifyLogin: WebView error $errorCode: $description url=$failingUrl")
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    isLoading = false
                                    webViewLoadError = "Spotify respondio con un error (HTTP ${errorResponse?.statusCode ?: "?"}). Intentalo de nuevo."
                                    Timber.e("SpotifyLogin: WebView HTTP error ${errorResponse?.statusCode} url=${request.url}")
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                view?.evaluateJavascript(ANTI_BOT_JS, null)

                                if (url?.startsWith("https://open.spotify.com") == true &&
                                    tokenFetchStarted.compareAndSet(false, true)
                                ) {
                                    extractAndFetchToken(
                                        view, ctx, scope, navController,
                                        { isProcessing = it }, { statusMessage = it },
                                        { hasError = it }, tokenFetchStarted,
                                    )
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val requestUrl = request?.url?.toString() ?: return false

                                if (requestUrl.startsWith("intent://") ||
                                    requestUrl.startsWith("spotify://")
                                ) {
                                    try {
                                        val intent = Intent.parseUri(
                                            requestUrl,
                                            Intent.URI_INTENT_SCHEME,
                                        )
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        ctx.startActivity(intent)
                                        return true
                                    } catch (e: Exception) {
                                        Timber.e(e, "Failed to launch intent: $requestUrl")
                                    }
                                }

                                if (requestUrl.startsWith("https://open.spotify.com")) {
                                    val spDc = extractSpDcCookie()
                                    if (spDc != null &&
                                        tokenFetchStarted.compareAndSet(false, true)
                                    ) {
                                        extractAndFetchToken(
                                            view, ctx, scope, navController,
                                            { isProcessing = it }, { statusMessage = it },
                                            { hasError = it }, tokenFetchStarted,
                                        )
                                        return true
                                    }
                                }
                                return false
                            }
                        }

                        // Clear any previous Spotify session first, then load the
                        // login page. Loading before the async cookie-clear finishes
                        // can leave a stale session behind and Spotify then skips
                        // the login form entirely (redirect to open.spotify.com
                        // with no usable sp_dc), so the interface never appears.
                        // Load immediately. Deferring this into removeAllCookies { post { } }
                        // left the WebView blank when the callback never ran.
                        loadUrl(SpotifyAuth.LOGIN_URL)
                    }.also { webViewRef = it }
                },
            )

            if (webViewLoadError != null && !isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            text = webViewLoadError ?: "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                webViewLoadError = null
                                isLoading = true
                                pageStarted = false
                                loadAttempt++
                                webViewRef?.loadUrl(SpotifyAuth.LOGIN_URL)
                            },
                        ) { Text("Reintentar") }
                    }
                }
            }

            if (isProcessing || hasError) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        if (!hasError) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        Text(
                            text = statusMessage.ifEmpty { "Iniciando sesion..." },
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
                                    tokenFetchStarted.set(true)
                                    extractAndFetchToken(
                                        webViewRef, context, scope, navController,
                                        { isProcessing = it }, { statusMessage = it },
                                        { hasError = it }, tokenFetchStarted,
                                    )
                                },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            ) { Text("Reintentar") }
                        }
                    }
                }
            }
        }
    }
}

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

private fun extractAndFetchToken(
    view: WebView?,
    context: Context,
    scope: CoroutineScope,
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
        setStatus("No se encontro la sesion (sp_dc). Completa el inicio de sesion.")
        setError(true)
        tokenFetchStarted.set(false)
        return
    }
    val spKey = cookieMap["sp_key"] ?: ""

    setProcessing(true)
    setError(false)
    setStatus("Verificando sesion de Spotify...")
    view?.stopLoading()
    view?.loadUrl("about:blank")

    loginWithSpDc(
        spDc = spDc,
        spKey = spKey,
        context = context,
        scope = scope,
        navController = navController,
        setProcessing = setProcessing,
        setStatus = setStatus,
        setError = { err ->
            setError(err)
            if (err) tokenFetchStarted.set(false)
        },
    )
}

private fun loginWithSpDc(
    spDc: String,
    spKey: String = "",
    context: Context,
    scope: CoroutineScope,
    navController: NavController,
    setProcessing: (Boolean) -> Unit,
    setStatus: (String) -> Unit,
    setError: (Boolean) -> Unit,
) {
    setProcessing(true)
    setError(false)
    setStatus("Verificando cookie de Spotify...")

    scope.launch(Dispatchers.IO) {
        try {
            // In-memory verification & project data retrieval without storing plaintext cookies
            withContext(Dispatchers.Main) { setStatus("Conectando con Spotify...") }

            val token = SpotifyAuth.fetchAccessToken(spDc, spKey).getOrThrow()
            Spotify.accessToken = token.accessToken

            withContext(Dispatchers.Main) { setStatus("Cargando perfil...") }
            val profileUser = Spotify.me().getOrNull()

            // Encrypt cookies using Android KeyStore before saving to persistent storage
            val encryptedSpDc = SessionCrypto.encrypt(spDc)
            val encryptedSpKey = if (spKey.isNotBlank()) SessionCrypto.encrypt(spKey) else ""

            context.dataStore.edit { prefs ->
                prefs[SpotifySpDcKey] = encryptedSpDc
                if (encryptedSpKey.isNotEmpty()) {
                    prefs[SpotifySpKeyKey] = encryptedSpKey
                }
                if (profileUser != null) {
                    prefs[SpotifyUsernameKey] = profileUser.displayName ?: profileUser.id
                    prefs[SpotifyUserIdKey] = profileUser.id
                }
                prefs[SpotifyAccessTokenKey] = token.accessToken
                prefs[SpotifyTokenExpiryKey] = token.accessTokenExpirationTimestampMs
            }

            // Also keep in Keystore-backed SharedPreferences
            SessionCrypto.getSecurePreferences(context).edit()
                .putString(SpotifySpDcKey.name, spDc)
                .apply()

            // Purge unencrypted session cookies from WebView disk storage
            withContext(Dispatchers.Main) {
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
                setStatus("Conectado a Spotify!")
            }
            delay(300)
            withContext(Dispatchers.Main) { navController.navigateUp() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "SpotifyLogin: login failed -- ${e.message}")
            withContext(Dispatchers.Main) {
                setStatus("Error: ${e.localizedMessage ?: e.message ?: "desconocido"}")
                setError(true)
                setProcessing(false)
            }
        }
    }
}

private const val ANTI_BOT_JS = """
(function() {
  try { Object.defineProperty(navigator, 'webdriver', { get: () => undefined }); } catch(e) {}
  try {
    var pd = [
      { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', length: 1 },
      { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', length: 1 },
      { name: 'Native Client', filename: 'internal-nacl-plugin', length: 2 }
    ];
    Object.defineProperty(navigator, 'plugins', { get: () => pd });
  } catch(e) {}
  try { Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en', 'es'] }); } catch(e) {}
  try {
    if (!window.chrome) window.chrome = { runtime: {}, loadTimes: function(){}, csi: function(){}, app: {} };
  } catch(e) {}
})();
"""

private const val USER_AGENT_MOBILE =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
