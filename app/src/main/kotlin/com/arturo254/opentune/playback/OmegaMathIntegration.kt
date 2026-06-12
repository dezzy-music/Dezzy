package com.arturo254.opentune.playback

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import androidx.media3.common.MediaItem

object OmegaMathIntegration {
    private val client = OkHttpClient()
    // TODO: El usuario deberá poner su UUID de Omega Math aquí (o configurarlo en la app).
    // Por defecto, dejaremos uno de prueba, pero es fundamental actualizarlo.
    var userId: String? = "c45199ab-55c3-4102-9ac3-23b3bd1a8a53" 

    fun reportNowPlaying(mediaItem: MediaItem?) {
        val currentUserId = userId ?: return // No hacer nada si no hay ID configurado
        
        val title = mediaItem?.mediaMetadata?.title?.toString()
        val artist = mediaItem?.mediaMetadata?.artist?.toString()
        // Intentar obtener una URL o thumbnail si es posible, si no pasamos null.
        val url = mediaItem?.mediaMetadata?.artworkUri?.toString()
        
        val json = JSONObject().apply {
            put("userId", currentUserId)
            put("title", title ?: JSONObject.NULL)
            put("artist", artist ?: JSONObject.NULL)
            put("url", url ?: JSONObject.NULL)
        }

        val requestBody = json.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://omegamath.work/api/presence") // Endpoint de Vercel en Omega Math
            .post(requestBody)
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
