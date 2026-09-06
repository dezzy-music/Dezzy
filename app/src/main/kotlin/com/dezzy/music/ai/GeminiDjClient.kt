package com.dezzy.music.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class GeminiDjClient(private val client: OkHttpClient) {

    // Tu API Key de Google Gemini
    private val API_KEY = "AIzaSyCVypOhkGqaByLZ1bTNi6cufi1iI37mVis"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun generateScript(
        djName: String,
        username: String,
        djLanguage: String,
        songTitle: String,
        artistName: String,
        isFirstSong: Boolean = true,
        customRequest: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val modelId = "gemini-1.5-flash-8b"
            
            val languageInstruction = when (djLanguage) {
                "es" -> "Responde en Español."
                "en" -> "Respond in English."
                else -> "Responde en el idioma predominante de la canción o español por defecto." // Auto
            }

            val userGreeting = if (username.isNotBlank()) " el usuario se llama $username." else ""

            val prompt = if (customRequest != null) {
                if (customRequest.contains("transición fluida")) {
                    "Eres $djName, un locutor de radio muy entusiasta de la app OpenTune.$userGreeting Instrucción de contexto: $customRequest. Anuncia la canción con mucha energía y estilo. $languageInstruction"
                } else {
                    "Eres $djName, un locutor de radio de OpenTune.$userGreeting El usuario expresó este estado de ánimo/petición: '$customRequest'. Anuncia que comenzarás tocando '$songTitle' de '$artistName' empatizando con su estado de ánimo. $languageInstruction"
                }
            } else if (isFirstSong) {
                "Eres $djName, un locutor de radio muy entusiasta de la app OpenTune.$userGreeting Preséntate brevemente por tu nombre y saluda al usuario. Vas a iniciar la sesión reproduciendo '$songTitle' de '$artistName'. Da 1 dato curioso rápido e interesante sobre el artista o la canción y mándalo con mucho ritmo. $languageInstruction"
            } else {
                "Eres un locutor de radio muy entusiasta.$userGreeting (OJO: NO repitas tu nombre, ya te presentaste antes). Vas a continuar la sesión musical reproduciendo '$songTitle' de '$artistName'. Menciona 1 dato curioso o anécdota muy interesante sobre esta canción o artista. Ponle muchísima energía, entusiasmo y ritmo para amenizar el momento, pero sé conciso (máximo 3 líneas). $languageInstruction"
            }

            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", 85) // Un poco más holgado para permitir datos interesantes
                    put("temperature", 0.8) // Más creativo/entusiasta
                })
            }

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$API_KEY")
                .header("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(JSON))
                .build()

            val response = client.newCall(request).execute()
            val bodyString = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val responseJson = JSONObject(bodyString)
                val text = responseJson.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text", "") ?: ""

                if (text.isNotBlank()) {
                    Result.success(text.trim())
                } else {
                    Result.failure(Exception("Respuesta vacía de Gemini"))
                }
            } else {
                Result.failure(Exception("Error Gemini: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    suspend fun generateSearchQuery(userRequest: String, sentiment: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val modelId = "gemini-1.5-flash-8b"
            val prompt = "El usuario de una app de música ha introducido la siguiente petición textual: '$userRequest'. El análisis de sentimiento detectó que su estado de ánimo es: '$sentiment'. Tu tarea es devolver ÚNICAMENTE los términos de búsqueda ideales (nombre de género, artista o mood) para buscar en YouTube Music. Por ejemplo, si dice 'acabo de terminar con mi novia', devuelve 'sad breakup songs' o 'canciones tristes de desamor'. Si dice 'vamos a entrenar', devuelve 'workout hype music'. Devuelve SOLO la consulta de búsqueda, sin comillas, ni explicaciones adicionales."
            
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                })
            }

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$API_KEY")
                .post(jsonBody.toString().toRequestBody(JSON))
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string()

            if (response.isSuccessful && bodyStr != null) {
                val jsonResponse = JSONObject(bodyStr)
                val textResponse = jsonResponse.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                Result.success(textResponse.trim())
            } else {
                Result.failure(Exception("Error en Gemini API: ${response.code} $bodyStr"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}