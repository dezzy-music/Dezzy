package com.dezzy.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import java.io.File
import java.io.FileOutputStream

/**
 * Universal Offline Artwork Interceptor & Anti-Gray Image Resolver.
 * 
 * 1. Checks local persistent cache (File(context.filesDir, "artwork/$hash.jpg")) before network.
 * 2. On network success: auto-persists high-quality bitmaps to disk asynchronously.
 * 3. On network failure or offline mode: falls back to base URL hash, video ID artwork, or local downloads.
 * 4. Intercepts YouTube 120x90 gray error placeholders and transparently redirects to hqdefault.jpg.
 */
class OfflineArtworkInterceptor(private val context: Context) : Interceptor {

    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val artworkDir by lazy {
        File(context.filesDir, "artwork").apply { if (!exists()) mkdirs() }
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data

        val urlString = when (data) {
            is String -> data
            is Uri -> data.toString()
            is HttpUrl -> data.toString()
            else -> null
        }

        // 1. Fast-path: Check if artwork exists in persistent disk storage
        if (urlString != null && urlString.startsWith("http")) {
            val hash = urlString.toArtworkHash()
            val cachedFile = File(artworkDir, "$hash.jpg")

            if (cachedFile.exists() && cachedFile.length() > 0) {
                val localRequest = chain.request.newBuilder()
                    .data(cachedFile)
                    .build()
                return chain.withRequest(localRequest).proceed()
            }

            // Check base URL hash fallback
            val baseHash = urlString.toArtworkBaseUrl().toArtworkHash()
            val baseFile = File(artworkDir, "$baseHash.jpg")
            if (baseFile.exists() && baseFile.length() > 0) {
                val localRequest = chain.request.newBuilder()
                    .data(baseFile)
                    .build()
                return chain.withRequest(localRequest).proceed()
            }
        }

        // 2. Proceed with normal pipeline (memory cache, disk cache, network)
        val result = chain.proceed()

        // 3. YouTube gray-placeholder / missing-thumbnail fallback chain.
        // maxresdefault.jpg returns a 120x90 GREY placeholder when the video has
        // no max-res thumbnail (very common). hqdefault can also be missing for
        // old/private videos. Walk maxres -> hq -> mq -> default until a real
        // image loads; a grey/error result at ANY step keeps falling back instead
        // of returning the grey image. A grey image is never persisted.
        fun isGrayOrError(r: ImageResult): Boolean {
            if (r is coil3.request.ErrorResult) return true
            if (r is coil3.request.SuccessResult) {
                return r.image.width <= 121 || r.image.height <= 91
            }
            return false
        }

        val isYtimgThumb = urlString != null && urlString.contains("i.ytimg.com") &&
            (urlString.contains("default.jpg") || urlString.contains("default1.jpg") ||
                urlString.contains("default2.jpg") || urlString.contains("default3.jpg") ||
                urlString.contains("1.jpg") || urlString.contains("2.jpg") || urlString.contains("3.jpg"))
        if (urlString != null && (isYtimgThumb || urlString.contains("maxresdefault.jpg") || urlString.contains("sddefault.jpg")) && isGrayOrError(result)) {
            val fallbacks = listOf("hqdefault.jpg", "mqdefault.jpg", "default.jpg")
            var currentUrl: String = urlString
            for (token in fallbacks) {
                val fallbackUrl = currentUrl
                    .replace("maxresdefault.jpg", token)
                    .replace("sddefault.jpg", token)
                    .replace("hqdefault.jpg", token)
                    .replace("mqdefault.jpg", token)
                if (fallbackUrl == currentUrl) continue
                currentUrl = fallbackUrl
                val fallbackRequest = chain.request.newBuilder()
                    .data(fallbackUrl)
                    .build()
                val fallbackResult = chain.withRequest(fallbackRequest).proceed()
                if (!isGrayOrError(fallbackResult)) {
                    if (fallbackResult is coil3.request.SuccessResult) {
                        persistBitmapAsync(fallbackResult.image.toBitmap(), urlString)
                    }
                    return fallbackResult
                }
            }
        }

        // 4. If network failed (offline) and no direct hash file was found:
        // Attempt recovery from any matching artwork for this YouTube video ID
        if (result is coil3.request.ErrorResult && urlString != null) {
            val videoId = extractVideoId(urlString)
            if (videoId != null) {
                // Check if we have an artwork saved with video ID or base hash
                val fallbackFile = findLocalArtworkForVideoId(videoId)
                if (fallbackFile != null && fallbackFile.exists() && fallbackFile.length() > 0) {
                    val localRequest = chain.request.newBuilder()
                        .data(fallbackFile)
                        .build()
                    return chain.withRequest(localRequest).proceed()
                }
            }
        }

        // 5. If successful and valid image: auto-persist to disk cache asynchronously
        if (result is coil3.request.SuccessResult && urlString != null && urlString.startsWith("http")) {
            if (result.image.width > 121 && result.image.height > 91) {
                persistBitmapAsync(result.image.toBitmap(), urlString)
            }
        }

        return result
    }

    private fun persistBitmapAsync(bitmap: Bitmap?, sourceUrl: String) {
        if (bitmap == null) return
        val hash = sourceUrl.toArtworkHash()
        val file = File(artworkDir, "$hash.jpg")
        if (file.exists() && file.length() > 0) return

        persistenceScope.launch {
            try {
                val tempFile = File(artworkDir, "$hash.tmp")
                FileOutputStream(tempFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                }
                if (tempFile.exists() && tempFile.length() > 0) {
                    tempFile.renameTo(file)
                }
            } catch (_: Exception) {}
        }
    }

    private fun extractVideoId(url: String): String? {
        return when {
            url.contains("/vi/") -> url.substringAfter("/vi/").substringBefore("/")
            url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
            else -> null
        }
    }

    private fun findLocalArtworkForVideoId(videoId: String): File? {
        // Direct video ID filename
        val idFile = File(artworkDir, "$videoId.jpg")
        if (idFile.exists() && idFile.length() > 0) return idFile

        // Search artwork directory for any file associated with this videoId
        val candidates = artworkDir.listFiles()?.filter {
            it.name.contains(videoId) && it.length() > 0
        }
        return candidates?.firstOrNull()
    }
}
