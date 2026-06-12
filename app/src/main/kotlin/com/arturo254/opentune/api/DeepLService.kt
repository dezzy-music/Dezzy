/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object DeepLService {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    suspend fun translate(
        text: String,
        targetLanguage: String,
        apiKey: String,
        formality: String = "default",
        maxRetries: Int = 3,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            var currentAttempt = 0

            // Validate input
            if (text.isBlank()) {
                return@withContext Result.failure(Exception("Input text is empty"))
            }

            // DeepL handles multiline text fine, but we'll preserve lines manually
            val lines = text.lines()
            val lineCount = lines.size

            while (currentAttempt < maxRetries) {
                try {
                    val formBody =
                        FormBody
                            .Builder()
                            .add("text", text)
                            .add("target_lang", targetLanguage.uppercase())
                            .add("tag_handling", "xml") // Helps preserve structure sometimes
                            .apply {
                                if (formality != "default") {
                                    add("formality", formality)
                                }
                            }.build()

                    // Handle both Free and Pro API keys
                    val url =
                        if (apiKey.endsWith(":fx")) {
                            "https://api-free.deepl.com/v2/translate"
                        } else {
                            "https://api.deepl.com/v2/translate"
                        }

                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .addHeader("Authorization", "DeepL-Auth-Key $apiKey")
                            .post(formBody)
                            .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string()

                    if (!response.isSuccessful) {
                        // Retry on server errors (5xx) or rate limits (429)
                        if (response.code >= 500 || response.code == 429) {
                            currentAttempt++
                            kotlinx.coroutines.delay(1000L * currentAttempt)
                            continue
                        }

                        val errorMsg =
                            try {
                                JSONObject(responseBody ?: "").optString("message")
                                    ?: "HTTP ${response.code}: ${response.message}"
                            } catch (e: Exception) {
                                "HTTP ${response.code}: ${response.message}"
                            }
                        val finalMsg = if (response.code in 401..403) {
                            "Invalid API Key or Access Denied by Provider ($errorMsg)"
                        } else {
                            errorMsg
                        }
                        return@withContext Result.failure(Exception("DeepL translation failed: $finalMsg"))
                    }

                    if (responseBody == null) {
                        currentAttempt++
                        continue
                    }

                    val jsonResponse = JSONObject(responseBody)
                    val translations = jsonResponse.optJSONArray("translations")
                    if (translations != null && translations.length() > 0) {
                        val translatedText = translations.getJSONObject(0).optString("text")
                        if (translatedText != null) {
                            val translatedLines = translatedText.lines()

                            // DeepL usually preserves line count, but let's be safe
                            return@withContext if (translatedLines.size == lineCount) {
                                Result.success(translatedLines)
                            } else if (translatedLines.size > lineCount) {
                                Result.success(translatedLines.take(lineCount))
                            } else {
                                val paddedLines = translatedLines.toMutableList()
                                while (paddedLines.size < lineCount) {
                                    paddedLines.add("")
                                }
                                Result.success(paddedLines)
                            }
                        }
                    }
                } catch (e: java.net.UnknownHostException) {
                    if (currentAttempt == maxRetries - 1) {
                        return@withContext Result.failure(Exception("Network error: Unable to resolve host. Check internet connection."))
                    }
                } catch (e: java.net.SocketException) {
                    if (currentAttempt == maxRetries - 1) {
                        return@withContext Result.failure(Exception("Network error: Connection failed or access denied."))
                    }
                } catch (e: Exception) {
                    if (currentAttempt == maxRetries - 1) {
                        return@withContext Result.failure(e)
                    }
                }
                currentAttempt++
                kotlinx.coroutines.delay(1000L * currentAttempt)
            }
            return@withContext Result.failure(Exception("DeepL: Max retries exceeded"))
        }
}
