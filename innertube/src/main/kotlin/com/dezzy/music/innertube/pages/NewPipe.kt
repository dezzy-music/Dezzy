/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.innertube

import com.dezzy.music.innertube.PlaybackAuthState
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
import java.lang.reflect.Field
import java.util.regex.Pattern

private class NewPipeDownloaderImpl(proxy: Proxy?) : Downloader() {

    private val client = OkHttpClient.Builder()
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

        val requestBuilder = okhttp3.Request.Builder()
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
        try {
            val clazz = Class.forName("org.schabi.newpipe.extractor.services.youtube.YoutubeSignatureUtils")
            val field = clazz.getDeclaredField("FUNCTION_REGEXES")
            field.isAccessible = true

            val newPatterns = arrayOf(
                Pattern.compile("\\b([a-zA-Z0-9_$]+)\\((\\d+,\\d+,)[a-zA-Z0-9_$]+\\(\\d+,\\d+,[a-zA-Z0-9_$]+(?:\\.s)?\\)\\)"),
                Pattern.compile("\\b([a-zA-Z0-9_$]+)\\((\\d+,)[a-zA-Z0-9_$]+\\(\\d+,\\d+,[a-zA-Z0-9_$]+(?:\\.s)?\\)\\)"),
                Pattern.compile("\\b([a-zA-Z0-9_$]+)\\([a-zA-Z0-9_$]+\\(\\d+,\\d+,[a-zA-Z0-9_$]+(?:\\.s)?\\)\\)"),
                Pattern.compile("\\b([a-zA-Z0-9_$]+)\\((\\d+,\\d+,)[a-zA-Z0-9_$]+\\([a-zA-Z0-9_$]+(?:\\.s)?\\)\\)"),
                Pattern.compile("\\b([a-zA-Z0-9_$]+)\\((\\d+,)[a-zA-Z0-9_$]+\\([a-zA-Z0-9_$]+(?:\\.s)?\\)\\)"),
                Pattern.compile("\\b([a-zA-Z0-9_$]+)\\([a-zA-Z0-9_$]+\\([a-zA-Z0-9_$]+(?:\\.s)?\\)\\)")
            )

            @Suppress("UNCHECKED_CAST")
            val originalRegexes = field.get(null) as Array<Pattern>

            // Construct new array with our patterns at the beginning
            val combinedRegexes = newPatterns + originalRegexes

            try {
                // Try setting field directly
                field.set(null, combinedRegexes)
                println("[Dezzy] Successfully patched YoutubeSignatureUtils.FUNCTION_REGEXES field directly")
            } catch (e: Exception) {
                println("[Dezzy] Failed to set YoutubeSignatureUtils.FUNCTION_REGEXES field directly, falling back to mutating array elements: ${e.message}")
                // Fallback: Mutate elements of the original array
                if (originalRegexes.size >= newPatterns.size) {
                    for (i in newPatterns.indices) {
                        originalRegexes[i] = newPatterns[i]
                    }
                    println("[Dezzy] Successfully mutated original YoutubeSignatureUtils.FUNCTION_REGEXES array elements")
                } else if (originalRegexes.isNotEmpty()) {
                    originalRegexes[0] = newPatterns[0]
                    println("[Dezzy] Successfully mutated first element of YoutubeSignatureUtils.FUNCTION_REGEXES array")
                }
            }
        } catch (e: Exception) {
            System.err.println("[Dezzy] Error patching YoutubeSignatureUtils.FUNCTION_REGEXES: ${e.message}")
            e.printStackTrace()
        }
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> = runCatching {
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
                                maxDelayMs = 2_000L
                            ) {
                                YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                                    videoId,
                                    directUrl
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

            val url = run {
                val cipherString = format.signatureCipher ?: format.cipher
                if (cipherString == null) throw ParsingException("Could not find format url")

                val params = parseQueryString(cipherString)
                val obfuscatedSignature = params["s"]
                    ?: throw ParsingException("Could not parse cipher signature")
                val signatureParam = params["sp"]
                    ?: throw ParsingException("Could not parse cipher signature parameter")
                val url = params["url"]?.let { URLBuilder(it) }
                    ?: throw ParsingException("Could not parse cipher url")
                url.parameters[signatureParam] =
                    YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                        videoId,
                        obfuscatedSignature
                    )
                url.toString()
            }

            val resolvedUrl = runCatching {
                retryWithBackoff(
                    maxAttempts = 3,
                    initialDelayMs = 250L,
                    maxDelayMs = 2_000L
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
        block: () -> T
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
