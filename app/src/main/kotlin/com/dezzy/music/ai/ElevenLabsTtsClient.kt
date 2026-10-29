package com.dezzy.music.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class ElevenLabsTtsClient(private val client: OkHttpClient) {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun synthesize(
        text: String,
        voiceId: String,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("text", text)
                put("model_id", "eleven_multilingual_v2")
            }

            val request = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
                .header("xi-api-key", com.dezzy.music.BuildConfig.ELEVENLABS_API_KEY)
                .header("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(JSON))
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Result.success(outputFile)
            } else {
                Result.failure(Exception("ElevenLabs Proxy Error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
