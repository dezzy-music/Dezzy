package com.arturo254.opentune.playback

import com.arturo254.opentune.utils.SupabaseConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import androidx.media3.common.MediaItem

/**
 * Publishes the current "now playing" (+ optional location) to the app's NATIVE backend
 * (Supabase `music_presence` table, via the public anon key — the same direct-REST pattern the
 * in-app chat/friends already use). No third-party proxy is involved.
 *
 * [userId] is the Supabase profile id when signed in (set on login), so presence shares identity
 * with profiles/friends/messages — which is what lets the Music Map link to native messaging.
 */
object OmegaMathIntegration {
    private val client = OkHttpClient()

    @Volatile
    var userId: String? = "c45199ab-55c3-4102-9ac3-23b3bd1a8a53"

    @Volatile
    var shareLocation: Boolean = false

    @Volatile
    var latitude: Double? = null

    @Volatile
    var longitude: Double? = null

    @Volatile
    var displayName: String? = null

    fun reportNowPlaying(mediaItem: MediaItem?) {
        val currentUserId = userId?.takeIf { it.isNotBlank() } ?: return

        val title = mediaItem?.mediaMetadata?.title?.toString()
        val artist = mediaItem?.mediaMetadata?.artist?.toString()
        val url = mediaItem?.mediaMetadata?.artworkUri?.toString()

        // snake_case columns, matching the Supabase music_presence table.
        val record = JSONObject().apply {
            put("user_id", currentUserId)
            put("display_name", displayName ?: JSONObject.NULL)
            put("title", title ?: JSONObject.NULL)
            put("artist", artist ?: JSONObject.NULL)
            put("url", url ?: JSONObject.NULL)
            val lat = latitude
            val lng = longitude
            if (shareLocation && lat != null && lng != null) {
                put("lat", lat)
                put("lng", lng)
            } else {
                put("lat", JSONObject.NULL)
                put("lng", JSONObject.NULL)
            }
            put("updated_at", Instant.now().toString())
        }

        val body = record.toString().toRequestBody("application/json".toMediaType())

        // PostgREST upsert on the primary key (user_id).
        val request = Request.Builder()
            .url("${SupabaseConfig.REST}/music_presence")
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${SupabaseConfig.ANON_KEY}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }
}
