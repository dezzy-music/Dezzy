/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.lyrics

import android.content.Context
import android.widget.Toast
import com.dezzy.music.R
import com.dezzy.music.constants.AiProviderKey
import com.dezzy.music.constants.AiSystemPromptKey
import com.dezzy.music.constants.DeeplApiKey
import com.dezzy.music.constants.DeeplFormalityKey
import com.dezzy.music.constants.OpenRouterApiKey
import com.dezzy.music.constants.OpenRouterBaseUrlKey
import com.dezzy.music.constants.OpenRouterModelKey
import com.dezzy.music.constants.TranslateLanguageKey
import com.dezzy.music.constants.TranslateModeKey
import com.dezzy.music.db.MusicDatabase
import com.dezzy.music.db.entities.LyricsEntity
import com.dezzy.music.models.MediaMetadata
import com.dezzy.music.utils.NetworkConnectivityObserver
import com.dezzy.music.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

object PlaylistTranslationManager {
    sealed class TranslationState {
        object Idle : TranslationState()
        data class Translating(val current: Int, val total: Int, val songTitle: String) : TranslationState()
        data class Completed(val translatedCount: Int, val totalCount: Int) : TranslationState()
        data class Error(val message: String) : TranslationState()
    }

    private val _status = MutableStateFlow<TranslationState>(TranslationState.Idle)
    val status: StateFlow<TranslationState> = _status.asStateFlow()

    private var translationJob: Job? = null

    fun isTranslating(): Boolean = _status.value is TranslationState.Translating

    fun cancelTranslation() {
        translationJob?.cancel()
        translationJob = null
        _status.value = TranslationState.Idle
    }

    fun translatePlaylist(
        context: Context,
        database: MusicDatabase,
        scope: CoroutineScope,
        songs: List<MediaMetadata>
    ) {
        if (isTranslating()) {
            Toast.makeText(context, "Ya hay una traducción en curso", Toast.LENGTH_SHORT).show()
            return
        }

        if (songs.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.playlist_is_empty), Toast.LENGTH_SHORT).show()
            return
        }

        translationJob?.cancel()
        translationJob = scope.launch(Dispatchers.IO) {
            try {
                val prefs = context.dataStore.data.first()
                val aiProvider = prefs[AiProviderKey] ?: "OpenRouter"
                val openRouterApiKey = prefs[OpenRouterApiKey] ?: ""
                val deeplApiKey = prefs[DeeplApiKey] ?: ""
                val openRouterBaseUrl = prefs[OpenRouterBaseUrlKey] ?: "https://openrouter.ai/api/v1/chat/completions"
                val openRouterModel = prefs[OpenRouterModelKey] ?: "mistralai/mistral-small-3.1-24b-instruct:free"
                val translateMode = prefs[TranslateModeKey] ?: "Literal"
                val targetLanguage = prefs[TranslateLanguageKey] ?: Locale.getDefault().language
                val deeplFormality = prefs[DeeplFormalityKey] ?: "default"
                val customSystemPrompt = prefs[AiSystemPromptKey] ?: ""

                val requiresApiKey = aiProvider != "FreeOnline" && aiProvider != "Offline"
                val effectiveApiKey = if (aiProvider == "DeepL") deeplApiKey else openRouterApiKey

                if (requiresApiKey && effectiveApiKey.isBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.ai_error_api_key_required),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    _status.value = TranslationState.Error(context.getString(R.string.ai_error_api_key_required))
                    return@launch
                }

                val networkObserver = NetworkConnectivityObserver(context)
                val lyricsHelper = LyricsHelper(context, networkObserver)

                val totalSongs = songs.size
                var translatedCount = 0

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.translating_playlist_progress, 0, totalSongs),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                for ((index, song) in songs.withIndex()) {
                    _status.value = TranslationState.Translating(
                        current = index + 1,
                        total = totalSongs,
                        songTitle = song.title
                    )

                    try {
                        // 1. Get lyrics from DB or online
                        var lyricsStr: String? = null
                        val existingLyrics = database.lyrics(song.id).first()
                        if (existingLyrics != null && existingLyrics.lyrics != LyricsEntity.LYRICS_NOT_FOUND) {
                            lyricsStr = existingLyrics.lyrics
                        } else {
                            try {
                                lyricsStr = lyricsHelper.getLyrics(song)
                                if (lyricsStr != null && lyricsStr != LyricsEntity.LYRICS_NOT_FOUND) {
                                    database.query {
                                        upsert(LyricsEntity(id = song.id, lyrics = lyricsStr))
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore lyrics fetch errors for individual song
                            }
                        }

                        if (lyricsStr.isNullOrBlank() || lyricsStr == LyricsEntity.LYRICS_NOT_FOUND) {
                            continue
                        }

                        val parsedLines = LyricsUtils.parseLyrics(lyricsStr)
                        if (parsedLines.isEmpty()) {
                            continue
                        }

                        val lyricsTextForCache = parsedLines.filter { it.text.isNotBlank() }.joinToString("\n") { it.text }
                        val cacheKey = "${lyricsTextForCache.hashCode()}_${translateMode}_$targetLanguage"

                        val existingTranslation = database.getLyricsTranslation(cacheKey)
                        if (existingTranslation != null) {
                            translatedCount++
                            continue
                        }

                        val result = LyricsTranslationHelper.prefetchTranslation(
                            lyrics = parsedLines,
                            targetLanguage = targetLanguage,
                            apiKey = openRouterApiKey,
                            baseUrl = openRouterBaseUrl,
                            model = openRouterModel,
                            mode = translateMode,
                            context = context,
                            database = database,
                            provider = aiProvider,
                            deeplApiKey = deeplApiKey,
                            deeplFormality = deeplFormality,
                            customSystemPrompt = customSystemPrompt
                        )

                        if (result.isSuccess) {
                            translatedCount++
                        }

                        // Gentle delay between songs to respect rate limits
                        delay(200)

                    } catch (e: Exception) {
                        // Continue next song on individual error
                    }
                }

                _status.value = TranslationState.Completed(
                    translatedCount = translatedCount,
                    totalCount = totalSongs
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.playlist_translation_done, translatedCount, totalSongs),
                        Toast.LENGTH_LONG
                    ).show()
                }

                delay(4000)
                if (_status.value is TranslationState.Completed) {
                    _status.value = TranslationState.Idle
                }

            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    val msg = e.message ?: context.getString(R.string.translation_failed)
                    _status.value = TranslationState.Error(msg)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
