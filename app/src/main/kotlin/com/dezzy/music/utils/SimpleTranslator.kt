/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Lightweight, key-less text translation used for quick in-app translation of short
 * user content (e.g. YouTube comments) WITHOUT leaving the app. Uses the public
 * `translate.googleapis.com` gtx endpoint — no API key, source language auto-detected.
 *
 * For long-form / higher-quality lyric translation the app still uses the configurable
 * AI providers in [com.dezzy.music.lyrics.LyricsTranslationHelper]; this is the
 * zero-config path for one-off snippets.
 */
object SimpleTranslator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Small in-memory cache so re-tapping "translate" on the same comment is instant.
    private val cache = HashMap<String, String>()

    /** Device UI language as a 2-letter code (e.g. "es", "en"), used as the default target. */
    fun deviceTargetLanguage(): String =
        Locale.getDefault().language.ifBlank { "en" }

    /**
     * Traduce una lista de líneas (letras de canciones) preservando el mapeo
     * 1:1 línea a línea. Agrupa las líneas en lotes acotados por tamaño —el
     * endpoint gtx va por GET y las URLs tienen límite de longitud— y usa el
     * salto de línea como separador, que gtx preserva en los segmentos.
     * Si una línea traducida se pierde en el mapeo, se conserva la original.
     */
    suspend fun translateLines(
        lines: List<String>,
        targetLang: String = deviceTargetLanguage(),
    ): Result<List<String>> {
        if (lines.isEmpty()) return Result.success(emptyList())

        val batches = ArrayList<MutableList<String>>()
        var current = mutableListOf<String>()
        var currentLen = 0
        for (line in lines) {
            if (current.isNotEmpty() && currentLen + line.length > 1200) {
                batches.add(current)
                current = mutableListOf()
                currentLen = 0
            }
            current.add(line)
            currentLen += line.length + 1
        }
        if (current.isNotEmpty()) batches.add(current)

        val out = ArrayList<String>(lines.size)
        for (batch in batches) {
            val joined = batch.joinToString("\n")
            val translated = translate(joined, targetLang).getOrElse { return Result.failure(it) }
            val split = translated.lines()
            for (i in batch.indices) {
                out.add(split.getOrNull(i)?.takeIf { it.isNotBlank() } ?: batch[i])
            }
        }
        return Result.success(out)
    }

    /**
     * Translates [text] into [targetLang] (ISO code). Returns the translated string,
     * or a failure with a readable message. Source language is auto-detected.
     */
    suspend fun translate(
        text: String,
        targetLang: String = deviceTargetLanguage(),
    ): Result<String> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext Result.success(text)
        val key = "$targetLang|$text"
        cache[key]?.let { return@withContext Result.success(it) }

        try {
            val url = buildString {
                append("https://translate.googleapis.com/translate_a/single")
                append("?client=gtx&sl=auto&tl=")
                append(URLEncoder.encode(targetLang, "UTF-8"))
                append("&dt=t&q=")
                append(URLEncoder.encode(text, "UTF-8"))
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank()) {
                    return@withContext Result.failure(
                        Exception("Translation failed (HTTP ${response.code})"),
                    )
                }
                // Response shape: [[["translated","source",...], ...], ...]
                // We concatenate every segment in the first array.
                val root = JSONArray(body)
                val segments = root.optJSONArray(0)
                    ?: return@withContext Result.failure(Exception("Unexpected translation response"))
                val out = StringBuilder()
                for (i in 0 until segments.length()) {
                    val seg = segments.optJSONArray(i) ?: continue
                    out.append(seg.optString(0, ""))
                }
                val translated = out.toString().ifBlank { text }
                cache[key] = translated
                Result.success(translated)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
