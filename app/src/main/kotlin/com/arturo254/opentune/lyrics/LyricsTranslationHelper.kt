/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.lyrics

import android.content.Context
import com.arturo254.opentune.api.DeepLService
import com.arturo254.opentune.api.MistralService
import com.arturo254.opentune.api.OpenRouterService
import com.arturo254.opentune.constants.LanguageCodeToName
import com.arturo254.opentune.db.MusicDatabase
import com.arturo254.opentune.db.entities.LyricsTranslationEntity
import com.arturo254.opentune.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

object LyricsTranslationHelper {
    private val _status = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    val status: StateFlow<TranslationStatus> = _status.asStateFlow()

    private val _manualTrigger = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val manualTrigger: SharedFlow<Unit> = _manualTrigger.asSharedFlow()
    
    private var translationJob: Job? = null
    private var isCompositionActive = true
    
    private val persistenceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Cache for translations: key = hash of (lyrics content + mode + language), value = list of translations
    private val translationCache = mutableMapOf<String, List<String>>()
    
    private fun getCacheKey(lyricsText: String, mode: String, language: String): String {
        return "${lyricsText.hashCode()}_${mode}_$language"
    }
    
    suspend fun getCachedTranslations(
        lyrics: List<LyricsEntry>, 
        mode: String, 
        language: String,
        database: MusicDatabase? = null
    ): List<String>? {
        val lyricsText = lyrics.filter { it.text.isNotBlank() }.joinToString("\n") { it.text }
        val key = getCacheKey(lyricsText, mode, language)
        
        // Check in-memory cache first
        translationCache[key]?.let { return it }
        
        // Check database if provided
        database?.let { db ->
            try {
                val entity = db.getLyricsTranslation(key)
                if (entity != null) {
                    val translatedLines = entity.translatedLyrics.lines()
                    // Basic validation: line count should be reasonable (not always exact due to AI, but let's cache it)
                    translationCache[key] = translatedLines
                    return translatedLines
                }
            } catch (e: Exception) {
                // Ignore DB errors
            }
        }
        
        return null
    }
    
    suspend fun applyCachedTranslations(
        lyrics: List<LyricsEntry>, 
        mode: String, 
        language: String,
        database: MusicDatabase? = null
    ): Boolean {
        val cached = getCachedTranslations(lyrics, mode, language, database) ?: return false
        val nonEmptyEntries = lyrics.mapIndexedNotNull { index, entry ->
            if (entry.text.isNotBlank()) index to entry else null
        }
        
        if (cached.isNotEmpty()) {
            val limit = minOf(cached.size, nonEmptyEntries.size)
            for (i in 0 until limit) {
                val originalIndex = nonEmptyEntries[i].first
                lyrics[originalIndex].translatedTextFlow.value = cached[i]
            }
            return true
        }
        return false
    }

    fun triggerManualTranslation() {
        _manualTrigger.tryEmit(Unit)
    }
    
    fun resetStatus() {
        _status.value = TranslationStatus.Idle
    }
    
    fun clearCache() {
        translationCache.clear()
    }
    
    fun setCompositionActive(active: Boolean) {
        isCompositionActive = active
    }
    
    fun cancelTranslation() {
        isCompositionActive = false
        translationJob?.cancel()
        translationJob = null
    }

    fun translateLyrics(
        lyrics: List<LyricsEntry>,
        targetLanguage: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        mode: String,
        scope: CoroutineScope,
        context: Context,
        database: MusicDatabase? = null,
        provider: String = "OpenRouter",
        deeplApiKey: String = "",
        deeplFormality: String = "default",
        customSystemPrompt: String = ""
    ) {
        translationJob?.cancel()
        
        // Check network connectivity before starting
        if (!isNetworkAvailable(context)) {
            _status.value = TranslationStatus.Error(context.getString(R.string.error_no_internet))
            return
        }
        
        _status.value = TranslationStatus.Translating
        
        // Clear existing translations to indicate re-translation
        lyrics.forEach { it.translatedTextFlow.value = null }
        
        translationJob = scope.launch(Dispatchers.IO) {
            try {
                // Validate inputs based on provider
                val effectiveApiKey = if (provider == "DeepL") deeplApiKey else apiKey
                if (effectiveApiKey.isBlank()) {
                    _status.value = TranslationStatus.Error(context.getString(R.string.ai_error_api_key_required))
                    return@launch
                }
                
                if (lyrics.isEmpty()) {
                    _status.value = TranslationStatus.Error(context.getString(R.string.ai_error_no_lyrics))
                    return@launch
                }
                
                // Filter out empty lines and keep track of their indices
                val nonEmptyEntries = lyrics.mapIndexedNotNull { index, entry ->
                    if (entry.text.isNotBlank()) index to entry else null
                }
                
                if (nonEmptyEntries.isEmpty()) {
                    _status.value = TranslationStatus.Error(context.getString(R.string.ai_error_lyrics_empty))
                    return@launch
                }
                
                // Create text from non-empty lines only
                val fullText = nonEmptyEntries.joinToString("\n") { it.second.text }

                // Validate language for all modes
                if (targetLanguage.isBlank()) {
                    _status.value = TranslationStatus.Error(context.getString(R.string.ai_error_language_required))
                    return@launch
                }

                // Convert language code to full language name for better AI understanding
                val fullLanguageName = LanguageCodeToName[targetLanguage] 
                    ?: try {
                        Locale.forLanguageTag(targetLanguage).displayLanguage.takeIf { it.isNotBlank() && it != targetLanguage }
                    } catch (e: Exception) { null }
                    ?: targetLanguage

                val result = when (provider) {
                    "DeepL" -> DeepLService.translate(
                        text = fullText,
                        targetLanguage = targetLanguage,
                        apiKey = deeplApiKey,
                        formality = deeplFormality
                    )
                    "Mistral" -> MistralService.translate(
                        text = fullText,
                        targetLanguage = fullLanguageName,
                        apiKey = apiKey,
                        model = model,
                        mode = mode,
                        customSystemPrompt = customSystemPrompt
                    )
                    "Gemini", "OpenAI", "Perplexity", "Claude", "XAi" -> {
                        // All these can use the generic OpenRouterService logic with custom baseUrl
                        OpenRouterService.translate(
                            text = fullText,
                            targetLanguage = fullLanguageName,
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            model = model,
                            mode = mode,
                            customSystemPrompt = customSystemPrompt
                        )
                    }
                    "Groq" -> {
                        OpenRouterService.translate(
                            text = fullText,
                            targetLanguage = fullLanguageName,
                            apiKey = apiKey,
                            baseUrl = "https://api.groq.com/openai/v1/chat/completions",
                            model = model,
                            mode = mode,
                            customSystemPrompt = customSystemPrompt
                        )
                    }
                    else -> { // Default to OpenRouter
                        OpenRouterService.translate(
                            text = fullText,
                            targetLanguage = fullLanguageName,
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            model = model,
                            mode = mode,
                            customSystemPrompt = customSystemPrompt
                        )
                    }
                }
                
                result.onSuccess { translatedLines ->
                    // Cache the translations
                    val cacheKey = getCacheKey(fullText, mode, targetLanguage)
                    translationCache[cacheKey] = translatedLines
                    
                    // Persist to database - Do this before checking isCompositionActive
                    // so it saves even if the user closes the lyrics screen
                    database?.let { db ->
                        persistenceScope.launch {
                            try {
                                db.upsert(
                                    LyricsTranslationEntity(
                                        cacheKey = cacheKey,
                                        translatedLyrics = translatedLines.joinToString("\n")
                                    )
                                )
                            } catch (e: Exception) {
                                // Ignore DB errors
                            }
                        }
                    }

                    // Check if composition is still active before updating UI state
                    if (!isCompositionActive) {
                        return@onSuccess
                    }
                    
                    // Map translations back to original non-empty entries only
                    val expectedCount = nonEmptyEntries.size
                    
                    when {
                        translatedLines.size >= expectedCount -> {
                            // Perfect match or more - map to non-empty entries
                            nonEmptyEntries.forEachIndexed { idx, (originalIndex, _) ->
                                lyrics[originalIndex].translatedTextFlow.value = translatedLines[idx]
                            }
                            _status.value = TranslationStatus.Success
                        }
                        translatedLines.size < expectedCount -> {
                            // Fewer translations than expected - map what we have
                            translatedLines.forEachIndexed { idx, translation ->
                                if (idx < nonEmptyEntries.size) {
                                    val originalIndex = nonEmptyEntries[idx].first
                                    lyrics[originalIndex].translatedTextFlow.value = translation
                                }
                            }
                            _status.value = TranslationStatus.Success
                        }
                        else -> {
                            _status.value = TranslationStatus.Error(context.getString(R.string.ai_error_unexpected))
                        }
                    }
                    
                    // Auto-hide success message after 3 seconds
                    delay(3000)
                    if (_status.value is TranslationStatus.Success && isCompositionActive) {
                        _status.value = TranslationStatus.Idle
                    }
                }.onFailure { error ->
                    if (!isCompositionActive) {
                        return@onFailure
                    }
                    
                    val errorMessage = error.message ?: context.getString(R.string.error_unknown)
                    
                    // Show error in UI
                    _status.value = TranslationStatus.Error(errorMessage)
                }
            } catch (e: Exception) {
                // Ignore cancellation exceptions or if composition is no longer active
                if (e !is kotlinx.coroutines.CancellationException && isCompositionActive) {
                    val errorMessage = e.message ?: context.getString(R.string.ai_error_translation_failed)
                    _status.value = TranslationStatus.Error(errorMessage)
                }
            }
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val nc = cm?.getNetworkCapabilities(cm.activeNetwork)
            nc?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } catch (e: Exception) {
            false
        }
    }

    sealed class TranslationStatus {
        data object Idle : TranslationStatus()
        data object Translating : TranslationStatus()
        data object Success : TranslationStatus()
        data class Error(val message: String) : TranslationStatus()
    }
}
