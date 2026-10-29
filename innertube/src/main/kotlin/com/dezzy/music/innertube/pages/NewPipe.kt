/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.innertube

import com.dezzy.music.innertube.models.YouTubeClient
import com.dezzy.music.innertube.models.response.PlayerResponse
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.io.IOException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

private class NewPipeDownloaderImpl(proxy: Proxy?) : Downloader() {
    private val client =
        OkHttpClient.Builder()
            .proxy(proxy)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder =
            okhttp3.Request.Builder()
                .method(httpMethod, dataToSend?.toRequestBody())
                .url(url)

        var hasUserAgent = false
        headers.forEach { (headerName, headerValueList) ->
            if (headerName.equals("User-Agent", ignoreCase = true) && headerValueList.isNotEmpty()) {
                hasUserAgent = true
            }
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        if (!hasUserAgent) {
            requestBuilder.header("User-Agent", YouTubeClient.USER_AGENT_WEB)
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            response.close()

            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val responseBodyToReturn = response.body.string()

        val latestUrl = response.request.url.toString()
        return Response(response.code, response.message, response.headers.toMultimap(), responseBodyToReturn, latestUrl)
    }
}

object NewPipeUtils {
    init {
        NewPipe.init(NewPipeDownloaderImpl(YouTube.proxy))
        patchNewPipeRegexes()
    }

    private fun patchNewPipeRegexes() {
        // Obsolete: NewPipeExtractor v0.26.5 has been updated to handle the new YouTube player.
        // These custom loose regexes mistakenly match the new 'ez' BotGuard interpreter function
        // and cause the cipher decoder to crash with 'Could not parse deobfuscation function'.
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> =
        runCatching {
            YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
        }

    fun getStreamUrl(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        client: YouTubeClient? = null,
        authState: PlaybackAuthState = YouTube.currentPlaybackAuthState(),
    ): Result<String> =
        runCatching {
            val directUrl = format.url
            if (directUrl != null) {
                val resolvedDirectUrl =
                    if (client?.clientName == "ANDROID_VR") {
                        // ANDROID_VR streams are served unthrottled without the n-parameter
                        // transformation, so skip the JS player round-trip entirely.
                        directUrl
                    } else if (directUrl.toHttpUrlOrNull()?.queryParameter("n")?.isNotBlank() == true) {
                        runCatching {
                            retryWithBackoff(
                                maxAttempts = 3,
                                initialDelayMs = 250L,
                                maxDelayMs = 2_000L,
                            ) {
                                YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                                    videoId,
                                    directUrl,
                                )
                            }
                        }.getOrElse { directUrl }
                    } else {
                        directUrl
                    }

                return@runCatching YouTube.appendGvsPoToken(
                    url = resolvedDirectUrl,
                    client = client,
                    authState = authState,
                )
            }

            val url =
                run {
                    val cipherString = format.signatureCipher ?: format.cipher
                    if (cipherString == null) throw ParsingException("Could not find format url")

                    val params = parseQueryString(cipherString)
                    val obfuscatedSignature =
                        params["s"]
                            ?: throw ParsingException("Could not parse cipher signature")
                    val signatureParam =
                        params["sp"]
                            ?: throw ParsingException("Could not parse cipher signature parameter")
                    val url =
                        params["url"]?.let { URLBuilder(it) }
                            ?: throw ParsingException("Could not parse cipher url")

                    // Try to deobfuscate the signature with NewPipeExtractor.
                    // If NewPipeExtractor's regex patterns are broken (YouTube JS player changed),
                    // fall back to using the unsigned base URL — YouTube may still serve the stream
                    // when a valid PO token is attached.
                    try {
                        val deobfuscated = YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                            videoId,
                            obfuscatedSignature,
                        )
                        url.parameters[signatureParam] = deobfuscated
                    } catch (e: Exception) {
                        System.err.println(
                            "[NewPipeUtils] Cipher deobfuscation failed, trying unsigned URL fallback: ${e.message}"
                        )
                        // Leave the URL without a signature — the PO token appended below
                        // may be sufficient for YouTube to accept the request.
                    }

                    url.toString()
                }

            val resolvedUrl =
                runCatching {
                    retryWithBackoff(
                        maxAttempts = 3,
                        initialDelayMs = 250L,
                        maxDelayMs = 2_000L,
                    ) {
                        YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)
                    }
                }.getOrElse { url }

            YouTube.appendGvsPoToken(
                url = resolvedUrl,
                client = client,
                authState = authState,
            )
        }

    private inline fun <T> retryWithBackoff(
        maxAttempts: Int,
        initialDelayMs: Long,
        maxDelayMs: Long,
        block: () -> T,
    ): T {
        var attempt = 0
        var delayMs = initialDelayMs
        var lastError: Throwable? = null
        while (attempt < maxAttempts) {
            try {
                return block()
            } catch (e: Throwable) {
                val isRetryable =
                    e is SocketTimeoutException ||
                        e is IOException ||
                        e.cause is SocketTimeoutException ||
                        e.cause is IOException
                if (!isRetryable || attempt == maxAttempts - 1) throw e
                lastError = e
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
                delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
                attempt++
            }
        }
        throw lastError ?: IllegalStateException("Retry attempts exhausted")
    }
}
