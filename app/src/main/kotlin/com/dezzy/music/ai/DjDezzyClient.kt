package com.dezzy.music.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

import com.dezzy.music.utils.dataStore
import com.dezzy.music.constants.DjNameKey
import kotlinx.coroutines.flow.first

object DjDezzyClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    
    private var cachedVqdToken: String? = null

    suspend fun generateScript(
        context: Context,
        prompt: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val prefs = context.dataStore.data.first()
            val customName = prefs[DjNameKey] ?: ""
            val djName = if (customName.isNotBlank()) "DJ $customName" else "DJ"

            val systemPrompt = "Eres $djName, la carismática, sofisticada y enérgica DJ de la aplicación de música OpenTune. Hablas siempre en español. Tu objetivo es presentar la siguiente canción dando un dato sumamente interesante, curioso o una pequeña historia sobre la canción o el artista. Mezcla emoción, nostalgia o energía según amerite la canción. Replica el estilo de locutores legendarios. Solo responde con lo que dirías por el micrófono. Tienes mucha actitud, usas un tono de radio y te encanta conectar con la audiencia."
            
            var vqdToken = cachedVqdToken
            
            if (vqdToken == null) {
                val statusReq = Request.Builder()
                    .url("https://duckduckgo.com/duckchat/v1/status")
                    .header("x-vqd-accept", "1")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                    
                val statusRes = client.newCall(statusReq).execute()
                if (!statusRes.isSuccessful) {
                    return@withContext Result.failure(Exception("DDG Status Error: ${statusRes.code}"))
                }
                
                vqdToken = statusRes.header("x-vqd-4") 
                    ?: return@withContext Result.failure(Exception("No se pudo obtener el token VQD. Bloqueo activo."))
                cachedVqdToken = vqdToken
            }

            val jsonMessages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }

            val jsonBody = JSONObject().apply {
                put("model", "gpt-4o-mini") 
                put("messages", jsonMessages)
            }

            val chatReq = Request.Builder()
                .url("https://duckduckgo.com/duckchat/v1/chat")
                .header("x-vqd-4", vqdToken!!)
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .post(jsonBody.toString().toRequestBody(JSON))
                .build()
            
            val chatRes = client.newCall(chatReq).execute()
            val bodyString = chatRes.body?.string() ?: ""
            
            if (!chatRes.isSuccessful) {
                cachedVqdToken = null // invalidamos el token
                return@withContext Result.failure(Exception("DDG Chat Error: ${chatRes.code}"))
            }

            val contentBuilder = StringBuilder()
            bodyString.lines().forEach { line ->
                if (line.startsWith("data: ") && line != "data: [DONE]") {
                    val json = line.substring(6)
                    try {
                        val messageObj = JSONObject(json).optString("message", "")
                        contentBuilder.append(messageObj)
                    } catch (e: Exception) {
                    }
                }
            }

            val content = contentBuilder.toString().trim()
            if (content.isNotEmpty()) {
                Result.success(content)
            } else {
                Result.failure(Exception("Respuesta vacía de DDG"))
            }

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
