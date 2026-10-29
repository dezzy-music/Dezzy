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
    private val API_KEY = com.dezzy.music.BuildConfig.GEMINI_API_KEY

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


    suspend fun generatePlaylistRecommendationsQuery(contextSongs: List<String>): Result<String> = withContext(Dispatchers.IO) {
        try {
            val modelId = "gemini-1.5-flash-8b"
            val songsList = contextSongs.joinToString(", ")
            val prompt = "Eres un experto musical. El usuario quiere añadir nuevas canciones a su playlist. La playlist actualmente contiene las siguientes canciones: [$songsList]. Tu tarea es devolver ÚNICAMENTE una consulta de búsqueda (query) ideal para buscar en YouTube Music canciones similares que encajen perfectamente en este estilo, pero que sean diferentes a las de la lista. Por ejemplo, podrías devolver un subgénero, o 'canciones estilo [Artista 1] y [Artista 2]'. Devuelve SOLO la consulta de búsqueda, sin comillas ni explicaciones."
            
            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
            }
            
            val url = java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$API_KEY")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            connection.outputStream.use { os ->
                val input = requestBody.toString().toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }
            
            if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                val responseJson = JSONObject(responseBody)
                val text = responseJson.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                Result.success(text.trim())
            } else {
                Result.failure(Exception("HTTP Error: ${connection.responseCode}"))
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

    data class AgentResponse(
        val action: String,
        val searchQuery: String?,
        val songName: String?,
        val playlistName: String?,
        val djReply: String
    )

    suspend fun executeAgentTask(userRequest: String, sentiment: String, djName: String): Result<AgentResponse> = withContext(Dispatchers.IO) {
        try {
            val modelId = "gemini-1.5-flash-8b"
            val prompt = """
                Eres $djName, un DJ y asistente musical inteligente con pensamiento crítico. 
                El usuario te ha dicho: '$userRequest'. (Su estado de ánimo es: $sentiment).
                Tu tarea es actuar como un Agente Autónomo. Decide qué acción realizar en la base de datos o reproductor.
                Puedes elegir UNA de estas acciones:
                1. "PLAY_MUSIC": Busca música, un mix, o canciones para reproducir.
                2. "ADD_TO_PLAYLIST": Añade una canción específica a una playlist.
                3. "RESPOND_ONLY": Solo responde conversacionalmente.
                
                Debes devolver tu decisión ESTRICTAMENTE en formato JSON, sin markdown, usando esta estructura:
                {
                  "action": "PLAY_MUSIC" o "ADD_TO_PLAYLIST" o "RESPOND_ONLY",
                  "search_query": "términos ideales para buscar en YouTube (solo si action=PLAY_MUSIC)",
                  "song_name": "nombre de la canción (solo si action=ADD_TO_PLAYLIST)",
                  "playlist_name": "nombre de la playlist (solo si action=ADD_TO_PLAYLIST)",
                  "dj_reply": "Tu respuesta hablada corta como locutor de radio anunciando lo que harás"
                }
            """.trimIndent()
            
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
                    put("temperature", 0.4)
                    put("response_mime_type", "application/json")
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
                
                val parsed = JSONObject(textResponse.trim().removePrefix("```json").removeSuffix("```").trim())
                Result.success(AgentResponse(
                    action = parsed.optString("action", "RESPOND_ONLY"),
                    searchQuery = parsed.optString("search_query").takeIf { it.isNotBlank() },
                    songName = parsed.optString("song_name").takeIf { it.isNotBlank() },
                    playlistName = parsed.optString("playlist_name").takeIf { it.isNotBlank() },
                    djReply = parsed.optString("dj_reply", "Claro que sí, aquí tienes.")
                ))
            } else {
                Result.failure(Exception("Error de Gemini: ${response.code} $bodyStr"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateVibeCards(topContext: String): List<String> = withContext(Dispatchers.IO) {
        val defaultCards = listOf(
            "Ritmos de power pop para relajarme esta noche",
            "Canciones de lo-fi house atmosférico",
            "Alternativo intenso que tienes en tu playlist",
            "Éxitos de trap y reggaetón para motivar"
        )
        try {
            val modelId = "gemini-1.5-flash-8b"
            val prompt = """
                Basándote en estos gustos musicales del usuario: "$topContext", genera exactamente 4 sugerencias breves de vibras o estados de ánimo musicales en español, idénticas al estilo de Spotify AI DJ (por ejemplo: "Ritmos de power pop para relajarme esta noche", "Canciones de lo-fi house atmosférico", "Alternativo intenso que tienes en tu playlist", "Éxitos de trap para entrenar").
                Devuelve un JSON con el campo "vibes": array de 4 strings de máximo 8 palabras cada uno.
            """.trimIndent()

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
                    put("response_mime_type", "application/json")
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

                val parsed = JSONObject(textResponse.trim().removePrefix("```json").removeSuffix("```").trim())
                val vibesArray = parsed.optJSONArray("vibes")
                if (vibesArray != null && vibesArray.length() > 0) {
                    val list = mutableListOf<String>()
                    for (i in 0 until vibesArray.length()) {
                        val item = vibesArray.getString(i).trim()
                        if (item.isNotBlank()) list.add(item)
                    }
                    if (list.isNotEmpty()) return@withContext list
                }
            }
            defaultCards
        } catch (e: Exception) {
            defaultCards
        }
    }
}