import okhttp3.RequestBody.Companion.toRequestBody
package com.dezzy.music.ui.screens

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.dezzy.music.innertube.models.WatchEndpoint
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.dezzy.music.R
import com.dezzy.music.ai.DjDezzyClient
import com.dezzy.music.ai.ElevenLabsTtsClient
import com.dezzy.music.ai.GeminiDjClient
import com.dezzy.music.constants.DjVoiceKey
import kotlinx.coroutines.flow.first
import com.dezzy.music.db.MusicDatabase
import com.dezzy.music.db.entities.Song
import com.dezzy.music.extensions.toMediaItem
import com.dezzy.music.playback.queues.ListQueue
import com.dezzy.music.utils.dataStore
import com.dezzy.music.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import com.dezzy.music.playback.PlayerConnection

import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class DJDezzyViewModel @Inject constructor(
    application: Application,
    private val database: MusicDatabase
) : AndroidViewModel(application) {

    private val context: Context
        get() = getApplication()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val ttsClient = ElevenLabsTtsClient(httpClient)

    private val _uiState = MutableStateFlow(DJUiState.IDLE)
    val uiState: StateFlow<DJUiState> = _uiState

    private val _statusText = MutableStateFlow("Toca el orbe para iniciar")
    val statusText: StateFlow<String> = _statusText

    private val _djAudioSessionId = MutableStateFlow(0)
    val djAudioSessionId: StateFlow<Int> = _djAudioSessionId.asStateFlow()

    private val _isUserTyping = MutableStateFlow(false)
    val isUserTyping: StateFlow<Boolean> = _isUserTyping.asStateFlow()

    fun setUserTyping(isTyping: Boolean) {
        _isUserTyping.value = isTyping
    }

    private var djMediaPlayer: android.media.MediaPlayer? = null
    private var djJob: kotlinx.coroutines.Job? = null

    fun handleUserRequest(requestText: String, playerConnection: PlayerConnection) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val prefsForApi = context.dataStore.data.first()
            val apiKey = prefsForApi[com.dezzy.music.constants.DjSentimentApiKey] ?: ""
            _statusText.value = "Analizando el sentimiento de tu mensaje..."
            val sentiment = fetchSentiment(requestText, apiKey)
            val moodQuery = if (sentiment == "negative") "triste" else if (sentiment == "positive") "feliz" else ""
            val contextualRequest = "$requestText (El usuario se siente $sentiment)"
            
            _statusText.value = "Interpretando tu petición con IA..."
            _uiState.value = DJUiState.GENERATING
            val intelligentQuery = GeminiDjClient(httpClient).generateSearchQuery(requestText, sentiment).getOrDefault(requestText)
            
            _statusText.value = "Buscando \"$intelligentQuery\"..."
            val result = com.dezzy.music.innertube.YouTube.search(intelligentQuery, com.dezzy.music.innertube.YouTube.SearchFilter.FILTER_SONG).getOrNull()
            val mediaItems = result?.items?.filterIsInstance<com.dezzy.music.innertube.models.SongItem>()?.map { it.toMediaItem() }
            
            if (!mediaItems.isNullOrEmpty()) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    playerConnection.playQueue(com.dezzy.music.playback.queues.ListQueue("DJ Mix: $requestText", mediaItems))
                    _uiState.value = DJUiState.PLAYING
                }
                val prefs = context.dataStore.data.first()
                val customName = prefs[com.dezzy.music.constants.DjNameKey] ?: ""
                val djLanguage = prefs[com.dezzy.music.constants.DjLanguageKey] ?: "auto"
                val djName = if (customName.isNotBlank()) "DJ $customName" else "DJ Dezzy"
                val username = prefs[com.dezzy.music.constants.UsernameKey] ?: "Oyente"
                val firstSong = mediaItems.first()
                generateAndPlayDjTrack(playerConnection, djName, username, djLanguage, firstSong.mediaMetadata.title.toString(), firstSong.mediaMetadata.artist.toString(), isFirstSong = true, customRequest = contextualRequest)
                startMonitoringQueue(playerConnection, djName, username, djLanguage)
            } else {
                _statusText.value = "No encontré nada para \"$requestText\""
                _uiState.value = DJUiState.IDLE
            }
        }
    }

    fun startDjSession(playerConnection: PlayerConnection) {
        if (_uiState.value == DJUiState.GENERATING) return
        if (_uiState.value == DJUiState.PLAYING) {
            val title = playerConnection.queueTitle.value ?: ""
            // If the user changed the playlist, the title won't contain "Mix" or "Top Hits"
            if (title.contains("Mix", ignoreCase = true) || title.contains("Top Hits", ignoreCase = true)) {
                _isUserTyping.value = true
                return
            }
        }
        
        djJob?.cancel()
        djJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = DJUiState.GENERATING
                _statusText.value = "Analizando tus gustos musicales..."
                
                val prefs = context.dataStore.data.first()
                val customName = prefs[com.dezzy.music.constants.DjNameKey] ?: ""
                val username = prefs[com.dezzy.music.constants.UsernameKey] ?: ""
                val djLanguage = prefs[com.dezzy.music.constants.DjLanguageKey] ?: "auto"
                val djName = if (customName.isNotBlank()) "DJ $customName" else "DJ Dezzy"

                val likedSongs = database.likedSongsByCreateDateAsc().first()
                val seedSong = if (likedSongs.isNotEmpty()) {
                    likedSongs.shuffled().first()
                } else {
                    val randomSongs = database.songsByPlayTimeAsc().first().shuffled()
                    if (randomSongs.isNotEmpty()) randomSongs.first() else null
                }

                val mediaItems = if (seedSong != null) {
                    _statusText.value = "Generando estación de radio basada en ${seedSong.title}..."
                    val endpoint = WatchEndpoint(videoId = seedSong.id, playlistId = "RDAMVM${seedSong.id}")
                    val nextResult = com.dezzy.music.innertube.YouTube.next(endpoint).getOrNull()
                    nextResult?.items?.filterIsInstance<com.dezzy.music.innertube.models.SongItem>()?.map { it.toMediaItem() } ?: listOf(seedSong.toMediaItem())
                } else {
                    _statusText.value = "Buscando algo popular para ti..."
                    val result = com.dezzy.music.innertube.YouTube.search("Top Hits", com.dezzy.music.innertube.YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    result?.items?.filterIsInstance<com.dezzy.music.innertube.models.SongItem>()?.map { it.toMediaItem() } ?: emptyList()
                }

                if (mediaItems.isEmpty()) {
                    _statusText.value = "No encontré canciones para reproducir."
                    _uiState.value = DJUiState.IDLE
                    return@launch
                }
                
                withContext(Dispatchers.Main) {
                    playerConnection.playQueue(com.dezzy.music.playback.queues.ListQueue(
                        title = "$djName Mix",
                        items = mediaItems
                    ))
                    _uiState.value = DJUiState.PLAYING
                }

                // Generar intro para la primera canción
                val firstSong = mediaItems.first()
                generateAndPlayDjTrack(playerConnection, djName, username, djLanguage, firstSong.mediaMetadata.title.toString(), firstSong.mediaMetadata.artist.toString(), isFirstSong = true)

                // Monitorear progreso para hablar cada 3 canciones
                startMonitoringQueue(playerConnection, djName, username, djLanguage)

            } catch (e: Exception) {
                _statusText.value = "Error: ${e.message}"
                _uiState.value = DJUiState.IDLE
            }
        }
    }

    private var monitorJob: kotlinx.coroutines.Job? = null
    
    private fun startMonitoringQueue(playerConnection: PlayerConnection, djName: String, username: String, djLanguage: String) {
        monitorJob?.cancel()
        monitorJob = viewModelScope.launch(Dispatchers.IO) {
            var lastSpokenIndex = 0
            var previousSongTitle = ""
            var previousArtistName = ""
            
            playerConnection.currentMediaItemIndex.collect { index ->
                val title = playerConnection.queueTitle.value ?: ""
                if (!title.contains("Mix", ignoreCase = true) && !title.contains("Top Hits", ignoreCase = true)) {
                    _uiState.value = DJUiState.IDLE
                    monitorJob?.cancel()
                    return@collect
                }
                
                val currentItem = withContext(Dispatchers.Main) { playerConnection.player.currentMediaItem }
                val songTitle = currentItem?.mediaMetadata?.title?.toString() ?: "una canción"
                val artistName = currentItem?.mediaMetadata?.artist?.toString() ?: "tu artista favorito"

                // Dynamic pacing: Speak every 4 songs.
                if (index > 0 && index - lastSpokenIndex >= 4) {
                    lastSpokenIndex = index
                    
                    val contextualRequest = if (previousSongTitle.isNotEmpty()) {
                        "Acabamos de escuchar $previousSongTitle de $previousArtistName, y ahora viene $songTitle de $artistName. Haz una transición fluida."
                    } else null
                    
                    generateAndPlayDjTrack(playerConnection, djName, username, djLanguage, songTitle, artistName, isFirstSong = false, customRequest = contextualRequest)
                }

                previousSongTitle = songTitle
                previousArtistName = artistName
            }
        }
    }


    private suspend fun generateAndPlayDjTrack(
        playerConnection: PlayerConnection,
        djName: String,
        username: String,
        djLanguage: String,
        songTitle: String,
        artistName: String,
        isFirstSong: Boolean = false,
        customRequest: String? = null
    ) {
        withContext(Dispatchers.IO) {
            _statusText.value = "Escribiendo guion con Gemini..."
            
            val httpClient = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val scriptResult = GeminiDjClient(httpClient).generateScript(djName, username, djLanguage, songTitle, artistName, isFirstSong, customRequest)
            val script = scriptResult.getOrElse {
                if (isFirstSong) {
                    listOf(
                        "¡Hola $username! Soy $djName. Aquí viene una mezcla espectacular empezando con $songTitle. ¡Súbele al volumen!",
                        "¿Qué tal $username? Te habla $djName. He preparado esta sesión para ti. ¡Que suene la música con $artistName!"
                    ).random()
                } else {
                    listOf(
                        "¡Seguimos con todo! Ahora vamos con un exitazo de $artistName. ¡A disfrutar!",
                        "La música no para. Súbele el volumen para escuchar $songTitle."
                    ).random()
                }
            }

            _statusText.value = "Grabando locución..."
            var voiceName = context.dataStore.get(DjVoiceKey, "EXAVITQu4vr4xnSDxMaL")
            if (voiceName == "es-MX-DaliaNeural" || voiceName == "nPczCjzI2devNBz1zQrb" || voiceName == "k9KXsQFJqzAoomTCOrJB" || voiceName.isBlank()) {
                voiceName = "EXAVITQu4vr4xnSDxMaL" 
            }
            val timestamp = System.currentTimeMillis()
            val outputFile = File(context.cacheDir, "dj_intro_$timestamp.mp3")
            
            
            val ttsResult = ttsClient.synthesize(script, voiceName, outputFile)
            var elevenLabsError = ""
            val audioUri = if (ttsResult.isSuccess) {
                android.net.Uri.fromFile(outputFile).toString()
            } else {
                elevenLabsError = ttsResult.exceptionOrNull()?.message ?: "Error desconocido"
                val encodedScript = java.net.URLEncoder.encode(script, "UTF-8")
                "https://translate.google.com/translate_tts?ie=UTF-8&q=$encodedScript&tl=es-MX&client=tw-ob"
            }

            _statusText.value = if (elevenLabsError.isNotEmpty()) "Voz genérica (ElevenLabs falló)" else "Todo listo!"

            withContext(Dispatchers.Main) {
                playerConnection.player.volume = 0.2f
                _uiState.value = DJUiState.SPEAKING
                try {
                    djMediaPlayer?.release()
                    djMediaPlayer = android.media.MediaPlayer().apply {
                        setDataSource(context, android.net.Uri.parse(audioUri))
                        prepare()
                        _djAudioSessionId.value = audioSessionId
                        setOnCompletionListener {
                            playerConnection.player.volume = 1.0f
                            _uiState.value = DJUiState.PLAYING
                            _djAudioSessionId.value = 0
                            it.release()
                            djMediaPlayer = null
                        }
                    }
                    djMediaPlayer?.start()
                } catch (e: Exception) {
                    playerConnection.player.volume = 1.0f
                    _uiState.value = DJUiState.PLAYING
                    _djAudioSessionId.value = 0
                }

                if (elevenLabsError.isEmpty()) {
                    _statusText.value = "$djName al aire"
                }
            }
        }
    }

    private suspend fun fetchSentiment(text: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://dezzyyyy-listen-together.hf.space/api/sentiment"
                val mediaType = "application/json".toMediaTypeOrNull()
                val body = "{\"text\":\"$text\"}".toRequestBody(mediaType)
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val respStr = response.body?.string() ?: ""
                    val json = JSONObject(respStr)
                    json.optString("sentiment", "neutral")
                } else {
                    "neutral"
                }
            } catch (e: Exception) {
                "neutral"
            }
        }
    }
}

enum class DJUiState {
    IDLE, GENERATING, PLAYING, SPEAKING
}
