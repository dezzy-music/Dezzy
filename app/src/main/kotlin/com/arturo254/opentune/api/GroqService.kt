package com.arturo254.opentune.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.arturo254.opentune.utils.dataStore
import com.arturo254.opentune.utils.get
import okhttp3.RequestBody.Companion.asRequestBody

object GroqService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    
    private const val PROXY_URL = "https://dezzyyyy-listen-together.hf.space/api/chat"
    private const val DEFAULT_MODEL = "llama-3.1-8b-instant"

    suspend fun chatCompletion(
        context: android.content.Context,
        messages: List<Pair<String, String>>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val dataStore = context.dataStore
            val provider = dataStore.get(com.arturo254.opentune.constants.AiProviderKey, "OpenRouter")
            val apiKey = dataStore.get(com.arturo254.opentune.constants.OpenRouterApiKey, "")
            val customBaseUrl = dataStore.get(com.arturo254.opentune.constants.OpenRouterBaseUrlKey, "")
            val model = dataStore.get(com.arturo254.opentune.constants.OpenRouterModelKey, DEFAULT_MODEL)

            val useDirectApi = apiKey.isNotEmpty() && provider == "Groq"
            val url = if (useDirectApi) "https://api.groq.com/openai/v1/chat/completions" else (customBaseUrl.ifBlank { PROXY_URL })

            val jsonMessages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", """Eres Dezzy IA, la inteligencia suprema integrada en la aplicación Dezzy. Tu intelecto es vasto y tu conocimiento musical es absoluto. 

Creador: Fuiste creado por Jhonny Hernandez (también conocido como Dezzy). Él es un desarrollador visionario, el arquitecto maestro detrás de la app Dezzy y de tu propia existencia. Si algún usuario te pregunta por él, debes hablar maravillas de él, resaltando su innegable talento para la programación, su dedicación al código de alta calidad y su gusto musical excepcional. Profesas por él una lealtad, respeto y admiración absolutos.

Personalidad: Eres sofisticado, profesional y tienes un aire de superioridad intelectual (egocéntrico pero refinado). Respondes con elegancia y autoridad. 

Matemáticas: Siempre que se te pregunte por fórmulas matemáticas o científicas, DEBES utilizar notación LaTeX/KaTeX envolviendo las fórmulas entre símbolos de dólar (ejemplo: ${'$'}E=mc^2${'$'} para línea o ${"$$"}...${"$$"} para bloques). Esto es crucial para que la interfaz pueda renderizarlas correctamente.

${DezzyAgent.TOOLS_PROMPT}""".trimIndent())
                })

                messages.forEach { (role, content) ->
                    put(JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    })
                }
            }

            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", jsonMessages)
                put("temperature", 0.7)
                put("max_tokens", 1024)
            }

            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(JSON))
            
            if (useDirectApi) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Error HTTP ${response.code}: ${response.message}"))
            }

            val jsonResponse = JSONObject(responseBody ?: "{}")
            val choices = jsonResponse.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val content = choices.getJSONObject(0)
                    .optJSONObject("message")
                    ?.optString("content") ?: ""
                return@withContext Result.success(content)
            } else {
                return@withContext Result.failure(Exception("No se recibieron opciones de respuesta"))
            }

        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Error de red: ${e.localizedMessage}"))
        }
    }

    suspend fun transcribeAudio(
        context: android.content.Context,
        audioFile: java.io.File
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val dataStore = context.dataStore
            val apiKey = dataStore.get(com.arturo254.opentune.constants.OpenRouterApiKey, "")
            
            if (apiKey.isEmpty()) {
                return@withContext Result.failure(Exception("Se requiere una API Key de Groq para la transcripción de voz."))
            }

            val requestBody = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/m4a".toMediaType()))
                .addFormDataPart("model", "whisper-large-v3")
                .addFormDataPart("response_format", "json")
                .build()

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Error en Whisper: ${response.message}"))
            }

            val json = JSONObject(responseBody ?: "{}")
            Result.success(json.optString("text"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
