package com.arturo254.opentune.utils

import android.content.Context
import android.net.Uri
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import okhttp3.HttpUrl
import java.io.File

/**
 * Intercepts Image requests across the entire application.
 * If the requested URL matches the hash of an artwork file we've already downloaded,
 * we reroute the request to read from the local file instead.
 * This completely transparently fixes offline artwork loading.
 */
class OfflineArtworkInterceptor(private val context: Context) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data

        // We only care about network requests (Strings, Uris, HttpUrls)
        val urlString = when (data) {
            is String -> data
            is Uri -> data.toString()
            is HttpUrl -> data.toString()
            else -> null
        }

        if (urlString != null && urlString.startsWith("http")) {
            val hash = urlString.toArtworkHash()
            val md5File = File(context.filesDir, "artwork/$hash.jpg")
            
            if (md5File.exists() && md5File.length() > 0) {
                // 4. Transform the request to load from local file
                val newRequest = chain.request.newBuilder()
                    .data(md5File)
                    .build()
                
                return chain.withRequest(newRequest).proceed()
            }
        }

        // 5. No cached file found, proceed normally
        val result = chain.proceed()

        // 6. Fallback to hqdefault when maxresdefault/sddefault is unavailable. YouTube signals this
        //    two ways: an HTTP 404 (ErrorResult) OR a 120x90 GRAY placeholder returned with HTTP 200
        //    (SuccessResult) — the latter is what makes the notification cover suddenly go gray.
        val isHighResToken = urlString != null &&
            (urlString.contains("maxresdefault.jpg") || urlString.contains("sddefault.jpg"))
        val isError = result is coil3.request.ErrorResult
        val isGrayPlaceholder = result is coil3.request.SuccessResult &&
            (result.image.width <= 121 || result.image.height <= 91)

        if (isHighResToken && (isError || isGrayPlaceholder)) {
            val fallbackUrl = urlString!!
                .replace("maxresdefault.jpg", "hqdefault.jpg")
                .replace("sddefault.jpg", "hqdefault.jpg")
            val fallbackRequest = chain.request.newBuilder()
                .data(fallbackUrl)
                .build()
            return chain.withRequest(fallbackRequest).proceed()
        }

        return result
    }
}
