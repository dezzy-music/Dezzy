/*
 * OpenTune Project (2026)
 *
 * Genuine BotGuard PoToken generator backed by a hidden WebView that runs Google's own
 * attestation VM, then mints web-client poTokens against it. This is a coroutine port of
 * NewPipe's PoTokenWebView.kt (org.schabi.newpipe.util.potoken), Licensed Under GPL-3.0.
 *
 * A poToken proves to YouTube that the request comes from a real client. Without a genuine one,
 * playback is rejected with "Sign in to confirm you're not a bot". The previous implementation
 * fabricated a random blob that YouTube always rejected; this generates a real token instead.
 */

package com.arturo254.opentune.playback.potoken

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class BotGuardPoTokenGenerator private constructor(
    private val appContext: Context,
    private val httpClient: OkHttpClient,
) {
    // Created and used exclusively on the main thread.
    private lateinit var webView: WebView
    private var expirationInstant: Instant = Instant.MIN

    // Pending JavaScript async results, keyed by a tag ("botguard" or "obtain:<identifier>").
    private val jsResults = ConcurrentHashMap<String, CompletableDeferred<String>>()

    fun isExpired(): Boolean = Instant.now().isAfter(expirationInstant)

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun buildWebView() = withContext(Dispatchers.Main) {
        webView = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.userAgentString = USER_AGENT
            settings.blockNetworkLoads = true // the VM never needs network access itself
            addJavascriptInterface(this@BotGuardPoTokenGenerator, JS_INTERFACE)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (m.message().contains("Uncaught")) {
                        val msg = "\"${m.message()}\", source: ${m.sourceId()} (${m.lineNumber()})"
                        Timber.tag(TAG).e("WebView JS error: $msg")
                        failAll(BadWebViewException(msg))
                    }
                    return super.onConsoleMessage(m)
                }
            }
        }
    }

    /** Loads the BotGuard HTML and runs Create + GenerateIT to obtain an integrity token. */
    private suspend fun initialize() {
        buildWebView()
        val html = withContext(Dispatchers.IO) {
            appContext.assets.open("po_token.html").bufferedReader().use { it.readText() }
        }
        // Load the page; the trigger call happens once JS is parsed and ready.
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                webView.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
                webView.loadDataWithBaseURL(
                    "https://www.youtube.com",
                    html,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        }

        // Step 1: Create -> challenge data.
        val createResponse = botguardRequest(CREATE_URL, "[ \"$REQUEST_KEY\" ]")
        val challenge = parseChallengeData(createResponse)

        // Step 2: run BotGuard in the WebView -> botguardResponse.
        val botguardResponse = awaitJs("botguard") {
            """
            try {
                data = $challenge
                runBotGuard(data).then(function (result) {
                    this.webPoSignalOutput = result.webPoSignalOutput;
                    $JS_INTERFACE.onJsResult('botguard', result.botguardResponse);
                }, function (error) {
                    $JS_INTERFACE.onJsError('botguard', error + '\n' + error.stack);
                });
            } catch (error) {
                $JS_INTERFACE.onJsError('botguard', error + '\n' + error.stack);
            }
            """.trimIndent()
        }

        // Step 3: GenerateIT -> integrity token + lifetime.
        val generateResponse =
            botguardRequest(GENERATE_IT_URL, "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]")
        val (integrityToken, expirationSeconds) = parseIntegrityTokenData(generateResponse)
        // Leave 10 minutes of margin, just like NewPipe.
        expirationInstant = Instant.now().plusSeconds(expirationSeconds - 600)

        evaluateUnit("this.integrityToken = $integrityToken")
        Timber.tag(TAG).i("BotGuard initialized, integrity token valid for ${expirationSeconds}s")
    }

    /**
     * Mints a poToken bound to [identifier] (visitorData for the streaming/session token, or a
     * videoId for a per-video player token).
     */
    suspend fun generatePoToken(identifier: String): String {
        val u8Identifier = stringToU8(identifier)
        val tag = "obtain:$identifier"
        val u8Result = awaitJs(tag) {
            """
            try {
                identifier = "$identifier"
                u8Identifier = $u8Identifier
                poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                poTokenU8String = ""
                for (i = 0; i < poTokenU8.length; i++) {
                    if (i != 0) poTokenU8String += ",";
                    poTokenU8String += poTokenU8[i];
                }
                $JS_INTERFACE.onJsResult('$tag', poTokenU8String);
            } catch (error) {
                $JS_INTERFACE.onJsError('$tag', error + '\n' + error.stack);
            }
            """.trimIndent()
        }
        return u8ToBase64(u8Result)
    }

    fun close() {
        val wv = if (::webView.isInitialized) webView else return
        Runnable {
            runCatching {
                wv.clearHistory()
                wv.clearCache(true)
                wv.loadUrl("about:blank")
                wv.onPause()
                wv.removeAllViews()
                wv.destroy()
            }
        }.let { android.os.Handler(android.os.Looper.getMainLooper()).post(it) }
    }

    //region JavaScript bridge
    @JavascriptInterface
    fun onJsResult(tag: String, value: String) {
        jsResults.remove(tag)?.complete(value)
    }

    @JavascriptInterface
    fun onJsError(tag: String, error: String) {
        Timber.tag(TAG).e("JS error for $tag: $error")
        jsResults.remove(tag)?.completeExceptionally(PoTokenException(error))
    }

    private fun failAll(t: Throwable) {
        val pending = jsResults.keys.toList()
        pending.forEach { jsResults.remove(it)?.completeExceptionally(t) }
    }

    private suspend fun awaitJs(tag: String, script: () -> String): String {
        val deferred = CompletableDeferred<String>()
        jsResults[tag] = deferred
        try {
            withContext(Dispatchers.Main) { webView.evaluateJavascript(script(), null) }
            return deferred.await()
        } finally {
            jsResults.remove(tag)
        }
    }

    private suspend fun evaluateUnit(script: String) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { if (cont.isActive) cont.resume(Unit) }
        }
    }
    //endregion

    private suspend fun botguardRequest(url: String, body: String): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json+protobuf")
                .header("x-goog-api-key", GOOGLE_API_KEY)
                .header("x-user-agent", "grpc-web-javascript/0.1")
                .post(body.toRequestBody())
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw PoTokenException("BotGuard request to $url failed: HTTP ${response.code}")
                }
                response.body?.string() ?: throw PoTokenException("Empty BotGuard response")
            }
        }

    companion object {
        private const val TAG = "BotGuardPoToken"

        // Public BotGuard endpoints/keys, identical to what the YouTube web player uses.
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw" // NOSONAR
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val CREATE_URL = "https://www.youtube.com/api/jnn/v1/Create"
        private const val GENERATE_IT_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val JS_INTERFACE = "OpenTuneBotGuard"

        suspend fun create(context: Context, httpClient: OkHttpClient): BotGuardPoTokenGenerator {
            val generator = BotGuardPoTokenGenerator(context.applicationContext, httpClient)
            generator.initialize()
            return generator
        }
    }
}
