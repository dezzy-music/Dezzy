package com.dezzy.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import coil3.size.Dimension
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
import java.util.concurrent.ConcurrentHashMap

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

    /** Lado mayor (px) de cada portada guardada, para no leer el archivo en cada carga. */
    private val savedSizes = ConcurrentHashMap<String, Int>()

    private fun savedSize(file: File): Int = savedSizes.getOrPut(file.path) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.path, opts) }
        maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(0)
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data

        val rawUrl = when (data) {
            is String -> data
            is Uri -> data.toString()
            is HttpUrl -> data.toString()
            else -> null
        }

        // 0. Las miniaturas de YouTube Music suelen venir a 60-226 px. Se piden al
        // tamaño en que se van a pintar (mínimo 544 px) para que no se vean borrosas.
        val wantedPx = chain.size.let { maxOf(it.width.px(), it.height.px()) }
            .coerceIn(MIN_ARTWORK_PX, MAX_ARTWORK_PX)
        val urlString = rawUrl?.let { upgradeGoogleArtwork(it, wantedPx) }
        val upgradedRequest = if (urlString != null && urlString != rawUrl) {
            request.newBuilder().data(urlString).build()
        } else {
            request
        }

        // 1. Fast-path: portada guardada en disco. La clave no lleva el tamaño, así que
        // una copia pequeña sólo se usa si no hay red; con red se baja la buena.
        var smallLocal: File? = null
        if (urlString != null && urlString.startsWith("http")) {
            val cachedFile = File(artworkDir, "${urlString.toArtworkHash()}.jpg")
            if (cachedFile.exists() && cachedFile.length() > 0) {
                if (savedSize(cachedFile) >= minOf(wantedPx, GOOD_ENOUGH_PX)) {
                    val localRequest = chain.request.newBuilder()
                        .data(cachedFile)
                        .build()
                    return chain.withRequest(localRequest).proceed()
                }
                smallLocal = cachedFile
            }
        }

        // 2. Proceed with normal pipeline (memory cache, disk cache, network)
        val result = chain.withRequest(upgradedRequest).proceed()
        if (result is coil3.request.ErrorResult && smallLocal != null) {
            return chain.withRequest(chain.request.newBuilder().data(smallLocal).build()).proceed()
        }

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
        val newSize = maxOf(bitmap.width, bitmap.height)
        // Sólo se reemplaza una copia guardada si la nueva es más grande.
        if (file.exists() && file.length() > 0 && savedSize(file) >= newSize) return

        persistenceScope.launch {
            try {
                val tempFile = File(artworkDir, "$hash.tmp")
                FileOutputStream(tempFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                }
                if (tempFile.exists() && tempFile.length() > 0) {
                    if (file.exists()) file.delete()
                    if (tempFile.renameTo(file)) savedSizes[file.path] = newSize
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

    private fun Dimension.px(): Int = (this as? Dimension.Pixels)?.px ?: 0

    private companion object {
        const val MIN_ARTWORK_PX = 544
        const val MAX_ARTWORK_PX = 1200
        /** Con esto basta para cualquier lista; sólo el reproductor pide más. */
        const val GOOD_ENOUGH_PX = 540

        private val GOOGLE_WH = Regex("""=w(\d+)-h(\d+)""")
        private val GOOGLE_S = Regex("""=s(\d+)""")

        /** Sube la resolución de una portada de lh3/yt3 si viene más pequeña de lo que se pinta. */
        fun upgradeGoogleArtwork(url: String, px: Int): String {
            if (!url.contains("googleusercontent.com") && !url.contains("ggpht.com")) return url
            GOOGLE_WH.find(url)?.let { m ->
                val w = m.groupValues[1].toIntOrNull() ?: return url
                val h = m.groupValues[2].toIntOrNull() ?: return url
                // Sólo portadas cuadradas: los banners y fondos tienen otra proporción.
                if (w != h || w >= px) return url
                return url.substring(0, m.range.first) + "=w$px-h$px" + url.substring(m.range.last + 1)
            }
            GOOGLE_S.findAll(url).lastOrNull()?.let { m ->
                val s = m.groupValues[1].toIntOrNull() ?: return url
                if (s >= px) return url
                return url.substring(0, m.range.first) + "=s$px" + url.substring(m.range.last + 1)
            }
            return url
        }
    }
}
