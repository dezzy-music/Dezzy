package com.dezzy.music.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Alias que Google actualiza solo; los nombres con versión (p. ej. 1.5-flash-8b) se retiran. */
private const val MODEL_ID = "gemini-flash-lite-latest"

/**
 * Guion, vibras y búsquedas del DJ con Gemini. `apiKey` es la del usuario (Ajustes › DJ); Dezzy
 * no lleva ninguna. Sin clave, cada llamada falla al momento y el DJ usa sus frases de reserva.
 */
class GeminiDjClient(private val client: OkHttpClient, private val apiKey: String) {

    /** La clave va en cabecera, no en la URL, para que no acabe en registros. */
    private fun key(): String = apiKey.trim().ifEmpty { throw IllegalStateException("Sin clave de Gemini") }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun generateScript(
        djName: String,
        username: String,
        djLanguage: String,
        songTitle: String,
        artistName: String,
        isFirstSong: Boolean = true,
        customRequest: String? = null,
        style: String = "radio",
        recentLines: List<String> = emptyList(),
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val modelId = MODEL_ID
            
            val languageInstruction = when (djLanguage) {
                "es" -> "Responde en Español."
                "en" -> "Respond in English."
                else -> "Responde en el idioma predominante de la canción o español por defecto." // Auto
            }

            // Sólo la primera vez se presenta y saluda por su nombre; después ni su nombre ni el del oyente.
            val identity = if (isFirstSong) {
                "Eres $djName, el DJ con IA de la app Dezzy." + (if (username.isNotBlank()) " El oyente se llama $username." else "")
            } else {
                "Eres el DJ con IA de la app Dezzy y ya estás en mitad de la sesión."
            }
            val rules = if (isFirstSong) {
                "Preséntate por tu nombre y saluda en una sola frase corta."
            } else {
                "REGLAS: ya te presentaste. NO digas tu nombre, NO saludes y NO digas el nombre del oyente. " +
                    "No empieces con «¡Hola!», «¿Qué tal?» ni «Seguimos»."
            }
            // Cada intervención con un enfoque distinto para que no suene a plantilla.
            val angle = if (isFirstSong) "" else " Enfoque de esta vez: " + listOf(
                "un dato curioso y poco conocido de la canción",
                "una anécdota de cómo se grabó o se compuso",
                "qué detalle escuchar en la canción (un instrumento, la voz, el ritmo)",
                "cómo conecta con lo que sonaba antes",
                "el momento o ambiente ideal para esta canción",
                "algo sobre la trayectoria del artista en ese momento",
            ).random() + "."
            val memory = if (recentLines.isEmpty()) "" else
                " Lo que ya dijiste antes (no repitas ideas, aperturas ni muletillas): " + recentLines.takeLast(4).joinToString(" | ") { "«$it»" } + "."

            val basePrompt = buildString {
                append(identity).append(' ').append(rules)
                if (customRequest != null) append(" Contexto: ").append(customRequest)
                append(" Va a sonar '$songTitle' de '$artistName'.")
                append(angle).append(memory)
                append(" Habla natural, como un locutor que conoce al oyente. Máximo 2 frases. ").append(languageInstruction)
            }
            // Estilo elegido en Ajustes › DJ.
            val styleInstruction = when (style) {
                "party" -> "Estilo: fiesta, muy animado, como un DJ de club. Frases cortas."
                "chill" -> "Estilo: tranquilo, cálido y pausado, como radio de madrugada. Nada de gritos ni exceso de energía."
                else -> "Estilo: locutor de radio cercano y con ritmo."
            }
            val prompt = "$basePrompt $styleInstruction"

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
                .url("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent")
                .header("x-goog-api-key", key())
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


    /** Primera vez: el DJ se presenta y pregunta qué le gusta al oyente para armarle una estación. */
    suspend fun generateTasteQuestion(
        djName: String,
        username: String,
        djLanguage: String,
        style: String = "radio",
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val language = if (djLanguage == "en") "Respond in English." else "Responde en español."
            val who = if (username.isNotBlank()) " El usuario se llama $username." else ""
            val tone = when (style) {
                "party" -> "Estilo: fiesta, animado, frases cortas."
                "chill" -> "Estilo: tranquilo y cálido."
                else -> "Estilo: locutor de radio cercano."
            }
            val prompt = "Eres $djName, el DJ con IA de la app de música Dezzy.$who Es la primera vez que hablas con él y todavía no conoces sus gustos. " +
                "Preséntate en una frase y pídele con naturalidad que te cuente qué música le gusta (artistas, géneros o el momento) para ponerle una estación basada en eso. " +
                "Máximo 2 frases, sin listas ni emojis. $language $tone"
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", 90)
                    put("temperature", 0.8)
                })
            }
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL_ID:generateContent")
                .header("x-goog-api-key", key())
                .post(jsonBody.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext Result.failure(Exception("Error Gemini: ${response.code}"))
                val text = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                    ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "").orEmpty()
                    .replace("*", "").trim()
                if (text.isNotBlank()) Result.success(text) else Result.failure(Exception("Respuesta vacía de Gemini"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generatePlaylistRecommendationsQuery(contextSongs: List<String>): Result<String> = withContext(Dispatchers.IO) {
        try {
            val modelId = MODEL_ID
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
            
            val url = java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("x-goog-api-key", key())
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
            val modelId = MODEL_ID
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
                .url("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent")
                .header("x-goog-api-key", key())
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
            val modelId = MODEL_ID
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
                .url("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent")
                .header("x-goog-api-key", key())
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
            val modelId = MODEL_ID
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
                .url("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent")
                .header("x-goog-api-key", key())
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