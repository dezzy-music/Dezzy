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

class OmniVoiceTtsClient(private val client: OkHttpClient, private val endpointUrl: String = "https://dezzyyyy-omnivoice.hf.space/api/tts") {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun synthesize(
        text: String,
        voiceId: String,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // Se adapta el body para soportar OmniVoice (generalmente text + speaker)
            val jsonBody = JSONObject().apply {
                put("text", text)
                put("voice_id", voiceId) // o "speaker" dependiendo del backend de OmniVoice
            }

            val request = Request.Builder()
                .url(endpointUrl)
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
                Result.failure(Exception("OmniVoice Server Error: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
