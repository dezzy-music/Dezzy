/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 *
 * AI Canvas — phase 2: the "intelligence" layer.
 *
 * Given a song's lyric lines, an LLM (via the app's existing OpenRouter-compatible
 * settings) picks, per line, the single word that should be shown GIANT in the
 * kinetic-typography canvas, plus an overall "mood" that biases layout/energy.
 *
 * This sits ON TOP of the procedural engine in AiCanvasScreen: if the analysis is
 * available it overrides the local heuristic hero pick; otherwise the canvas still
 * works fully offline. Results are cached in-memory keyed by lyrics content so a
 * song is only analyzed once per session.
 */

package com.dezzy.music.lyrics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One word to enlarge per line (aligned to the input lines), plus an overall mood. */
data class CanvasAnalysis(
    val mood: String,
    val heroes: List<String>,
)

object AiCanvasAnalyzer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val cache = mutableMapOf<String, CanvasAnalysis>()
    private val mutex = Mutex()

    private fun cacheKey(linesText: String, model: String): String =
        "${linesText.hashCode()}_$model"

    /** Resolve a provider name to an OpenAI-compatible chat-completions endpoint. */
    private fun resolveBaseUrl(provider: String, baseUrl: String): String = when (provider) {
        "Groq" -> "https://api.groq.com/openai/v1/chat/completions"
        "Nvidia" -> "https://integrate.api.nvidia.com/v1/chat/completions"
        else -> baseUrl.ifBlank { "https://openrouter.ai/api/v1/chat/completions" }
    }

    /**
     * Analyze a song. Returns a cached result if available; otherwise calls the LLM.
     * DeepL is not a chat provider, so it is treated as "unsupported" → caller falls
     * back to the procedural heuristic.
     */
    suspend fun analyze(
        lines: List<String>,
        provider: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        context: Context,
    ): Result<CanvasAnalysis> = withContext(Dispatchers.IO) {
        val cleaned = lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return@withContext Result.failure(Exception("No lyrics to analyze"))
        if (provider == "DeepL" || apiKey.isBlank()) {
            return@withContext Result.failure(Exception("AI provider not available"))
        }
        if (!isNetworkAvailable(context)) {
            return@withContext Result.failure(Exception("No network"))
        }

        val linesText = cleaned.joinToString("\n")
        val key = cacheKey(linesText, model)

        mutex.withLock { cache[key] }?.let { return@withContext Result.success(it) }

        val numbered = cleaned.mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n")
        val count = cleaned.size

        val systemPrompt = """You are a creative director for kinetic-typography lyric visuals (TikTok/Instagram "lyric edit" style). Output ONLY valid JSON, no prose, no markdown fences."""

        val userPrompt = """For the song lyrics below, produce a JSON object with EXACTLY this shape:
{"mood": string, "heroes": [string, ...]}

Rules:
- "mood" MUST be one of: hype, energetic, romantic, dreamy, melancholic, dark, playful, neutral.
- "heroes" MUST have EXACTLY $count items, one per numbered line, in order.
- Each hero is the SINGLE most impactful word of that line to display GIANT — copy it EXACTLY as written in the line (same casing/characters). No paraphrasing.
- If a line has no strong word, use "".
- Prefer emotionally charged words, the hook, or recurring key words over filler.

Lyrics ($count lines):
$numbered

Return ONLY the JSON object."""

        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            put(JSONObject().apply { put("role", "user"); put("content", userPrompt) })
        }
        val body = JSONObject().apply {
            if (model.isNotBlank()) put("model", model)
            put("messages", messages)
            put("temperature", 0.6)
            put("max_tokens", count * 24 + 128)
        }

        val url = resolveBaseUrl(provider, baseUrl)
        val request = Request.Builder()
            .url(url)
            .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer ${apiKey.trim()}") }
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://github.com/dezzy-music/Dezzy")
            .addHeader("X-Title", "Dezzy")
            .post(body.toString().toRequestBody(JSON))
            .build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            if (!response.isSuccessful || responseBody == null) {
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val choices = JSONObject(responseBody).optJSONArray("choices")
            val content = choices?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content")?.trim()
                ?: return@withContext Result.failure(Exception("Empty response"))

            val analysis = parseAnalysis(content, count)
                ?: return@withContext Result.failure(Exception("Could not parse analysis"))

            mutex.withLock { cache[key] = analysis }
            Result.success(analysis)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseAnalysis(raw: String, expectedCount: Int): CanvasAnalysis? {
        val cleaned = raw.replace("```json", "").replace("```", "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null
        return try {
            val obj = JSONObject(cleaned.substring(start, end + 1))
            val mood = obj.optString("mood", "neutral").lowercase()
            val arr = obj.optJSONArray("heroes") ?: return null
            val heroes = ArrayList<String>(expectedCount)
            for (i in 0 until arr.length()) heroes.add(arr.optString(i, "").trim())
            // Normalize length to the number of lines we sent.
            while (heroes.size < expectedCount) heroes.add("")
            CanvasAnalysis(mood = mood, heroes = heroes.take(expectedCount))
        } catch (e: Exception) {
            null
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val nc = cm?.getNetworkCapabilities(cm.activeNetwork)
        nc?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) {
        false
    }
}
