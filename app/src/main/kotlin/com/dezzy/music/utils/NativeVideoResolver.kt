package com.dezzy.music.utils

import com.dezzy.music.innertube.models.response.PlayerResponse
import org.json.JSONObject
import timber.log.Timber

/**
 * High-performance native Rust video resolver for YouTube video mode.
 * Loads `libdezzy_video_resolver.so` to resolve unthrottled, botguard-signed video streams
 * with sub-200ms latency.
 */
object NativeVideoResolver {
    private const val TAG = "NativeVideoResolver"

    @Volatile
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("dezzy_video_resolver")
            isLoaded = true
            android.util.Log.i(TAG, "Loaded libdezzy_video_resolver.so successfully")
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed to load libdezzy_video_resolver.so", t)
            isLoaded = false
        }
    }

    data class ResolvedVideo(
        val success: Boolean,
        val videoUrl: String?,
        val audioUrl: String?,
        val isMuxed: Boolean,
        val height: Int,
        val expiresIn: Long,
        val error: String?,
    )

    @JvmStatic
    private external fun resolveVideoNative(
        videoId: String,
        maxHeight: Int,
        poToken: String?,
        visitorData: String?,
    ): String

    fun isAvailable(): Boolean = isLoaded

    suspend fun resolveVideo(
        videoId: String,
        maxHeight: Int = 720,
        poToken: String? = null,
        visitorData: String? = null,
    ): ResolvedVideo? {
        if (!isLoaded) return null
        return try {
            val token = poToken
                ?: com.dezzy.music.innertube.YouTube.poTokenPlayer
                ?: com.dezzy.music.innertube.YouTube.poTokenMinter?.invoke(videoId)
                ?: com.dezzy.music.innertube.YouTube.poTokenGvs
                ?: com.dezzy.music.innertube.YouTube.poToken
            val vd = visitorData ?: com.dezzy.music.innertube.YouTube.visitorData
            val jsonStr = resolveVideoNative(videoId, maxHeight, token, vd)
            val json = JSONObject(jsonStr)
            val success = json.optBoolean("success", false)
            val videoUrl = json.optString("video_url").takeIf { it.isNotEmpty() }
            val audioUrl = json.optString("audio_url").takeIf { it.isNotEmpty() }
            val isMuxed = json.optBoolean("is_muxed", false)
            val height = json.optInt("height", 0)
            val expiresIn = json.optLong("expires_in", 0)
            val error = json.optString("error").takeIf { it.isNotEmpty() }

            if (!success && error != null) {
                android.util.Log.w(TAG, "Native resolution for $videoId: $error")
            }

            ResolvedVideo(
                success = success,
                videoUrl = videoUrl,
                audioUrl = audioUrl,
                isMuxed = isMuxed,
                height = height,
                expiresIn = expiresIn,
                error = error,
            )
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Native video resolution failed for videoId: $videoId", t)
            null
        }
    }

    fun toVideoPlaybackData(res: ResolvedVideo): YTPlayerUtils.VideoPlaybackData {
        val format = PlayerResponse.StreamingData.Format(
            itag = if (res.isMuxed) 18 else 136,
            url = res.videoUrl,
            mimeType = "video/mp4",
            bitrate = 0,
            width = 0,
            height = res.height,
            contentLength = null,
            quality = "${res.height}p",
            fps = null,
            qualityLabel = "${res.height}p",
            averageBitrate = null,
            audioQuality = null,
            approxDurationMs = null,
            audioSampleRate = null,
            audioChannels = null,
            loudnessDb = null,
            lastModified = null,
            signatureCipher = null,
            cipher = null,
        )
        return YTPlayerUtils.VideoPlaybackData(
            format = format,
            streamUrl = if (res.isMuxed) res.videoUrl else null,
            streamExpiresInSeconds = res.expiresIn.toInt(),
            adaptiveVideoUrl = if (!res.isMuxed) res.videoUrl else null,
            adaptiveAudioUrl = if (!res.isMuxed) res.audioUrl else null,
        )
    }
}
