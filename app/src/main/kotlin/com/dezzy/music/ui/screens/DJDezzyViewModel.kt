package com.dezzy.music.ui.screens

import okhttp3.RequestBody.Companion.toRequestBody

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.dezzy.music.innertube.models.WatchEndpoint
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.dezzy.music.R
import com.dezzy.music.ai.DjDezzyClient
import com.dezzy.music.ai.DjSetBuilder
import com.dezzy.music.ai.TasteProfile
import com.dezzy.music.ai.ElevenLabsTtsClient
import com.dezzy.music.ai.OmniVoiceTtsClient
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

    private val elevenLabsClient = ElevenLabsTtsClient(httpClient)
    private val omniVoiceClient = OmniVoiceTtsClient(httpClient)

    private val _uiState = MutableStateFlow(DJUiState.IDLE)
    val uiState: StateFlow<DJUiState> = _uiState

    private val _statusText = MutableStateFlow("Toca el orbe para iniciar")
    val statusText: StateFlow<String> = _statusText

    private val _djAudioSessionId = MutableStateFlow(0)
    val djAudioSessionId: StateFlow<Int> = _djAudioSessionId.asStateFlow()

    private val _isUserTyping = MutableStateFlow(false)
    val isUserTyping: StateFlow<Boolean> = _isUserTyping.asStateFlow()

    // Texto de la última locución: la pantalla lo muestra como subtítulos.
    private val _djScript = MutableStateFlow<String?>(null)
    val djScript: StateFlow<String?> = _djScript.asStateFlow()

    private val _dynamicVibeCards = MutableStateFlow<List<String>>(
        listOf(
            "Ritmos de power pop para relajarme esta noche",
            "Canciones de lo-fi house atmosférico",
            "Alternativo intenso que tienes en tu playlist",
            "Éxitos de trap y reggaetón para motivar"
        )
    )
    val dynamicVibeCards: StateFlow<List<String>> = _dynamicVibeCards.asStateFlow()

    init {
        loadVibeSuggestions()
    }

    fun loadVibeSuggestions() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val topSongs = database.songsByPlayTimeAsc().first().reversed().take(5)
                val topContext = topSongs.joinToString(", ") { "${it.title} de ${it.artists.firstOrNull()?.name ?: "Unknown"}" }
                val cards = GeminiDjClient(httpClient).generateVibeCards(topContext)
                if (cards.isNotEmpty()) {
                    _dynamicVibeCards.value = cards
                }
            } catch (_: Exception) {}
        }
    }

    fun setUserTyping(isTyping: Boolean) {
        _isUserTyping.value = isTyping
    }

    private var djMediaPlayer: android.media.MediaPlayer? = null

    /** Sesión en curso con sus bloques: el DJ habla al empezar cada uno. */
    @Volatile private var djSet: DjSetBuilder.DjSet? = null
    private var djJob: kotlinx.coroutines.Job? = null

    /** Gustos del oyente; se recalcula al empezar cada sesión. */
    @Volatile private var profile: TasteProfile? = null

    private suspend fun loadProfile(): TasteProfile? =
        runCatching { TasteProfile.build(database, context) }.getOrNull()?.also { profile = it }

    /** Búsqueda de respaldo con tu gusto (tu artista más escuchado), no un «Top Hits» genérico. */
    private fun tasteQuery(): String? {
        val p = profile ?: return null
        val top = p.songScore.entries.sortedByDescending { it.value }.take(10)
            .mapNotNull { p.songs[it.key]?.artists?.firstOrNull()?.name }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return top?.let { "$it mix" }
    }

    /** «Canción de Artista» de lo que más escuchas ahora, para dar contexto a la IA. */
    private fun tasteContext(): String? {
        val p = profile ?: return null
        return p.songScore.entries.sortedByDescending { it.value }.take(8)
            .mapNotNull { p.songs[it.key] }
            .joinToString(", ") { "${it.title} de ${it.artists.firstOrNull()?.name ?: "?"}" }
            .ifBlank { null }
    }

    fun handleUserRequest(requestText: String, playerConnection: PlayerConnection) {
        djJob?.cancel()
        djJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _statusText.value = "Analizando el sentimiento de tu mensaje..."
            val sentiment = fetchSentiment(requestText)
            val contextualRequest = "$requestText (El usuario se siente $sentiment)"
            loadProfile()

            _statusText.value = "Interpretando tu petición con IA..."
            _uiState.value = DJUiState.GENERATING
            val intelligentQuery = GeminiDjClient(httpClient).generateSearchQuery(requestText, sentiment).getOrDefault(requestText)

            _statusText.value = "Buscando \"$intelligentQuery\"..."
            val result = com.dezzy.music.innertube.YouTube.search(intelligentQuery, com.dezzy.music.innertube.YouTube.SearchFilter.FILTER_SONG).getOrNull()
            _statusText.value = "Armando la sesión a tu gusto..."
            val requestSet = DjSetBuilder.buildFromRequest(
                intelligentQuery,
                result?.items?.filterIsInstance<com.dezzy.music.innertube.models.SongItem>().orEmpty(),
                profile,
            )
            val mediaItems = requestSet.items

            if (mediaItems.isNotEmpty()) {
                djSet = requestSet
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

    fun startDjSession(playerConnection: PlayerConnection, userRequest: String? = null) {
        if (_uiState.value == DJUiState.GENERATING) return

        djJob?.cancel()
        djJob = viewModelScope.launch(Dispatchers.IO) {
            val prefs = context.dataStore.data.first()
            val customName = prefs[com.dezzy.music.constants.DjNameKey] ?: ""
            val username = prefs[com.dezzy.music.constants.UsernameKey] ?: ""
            val djLanguage = prefs[com.dezzy.music.constants.DjLanguageKey] ?: "auto"
            val djName = if (customName.isNotBlank()) "DJ $customName" else "DJ Dezzy"

            try {
                _uiState.value = DJUiState.GENERATING
                _statusText.value = "Pensando..."

                if (userRequest == null) {
                    _statusText.value = "Armando tu sesión: favoritas, recuerdos y descubrimientos..."
                    val session = loadProfile()?.let { p -> runCatching { DjSetBuilder.buildSession(p) }.getOrNull() }
                    if (session != null && session.items.size >= 5) {
                        djSet = session
                        withContext(Dispatchers.Main) {
                            playerConnection.playQueue(ListQueue(title = "$djName Mix", items = session.items))
                            _uiState.value = DJUiState.PLAYING
                        }
                        val first = session.items.first()
                        val firstTitle = first.mediaMetadata.title.toString()
                        val firstArtist = first.mediaMetadata.artist.toString()
                        val block = session.blockAt(0)
                        generateAndPlayDjTrack(
                            playerConnection, djName, username, djLanguage, firstTitle, firstArtist,
                            isFirstSong = true,
                            customRequest = block?.let { DjSetBuilder.describe(it, firstTitle, firstArtist, first = true) },
                            fallback = DjSetBuilder.fallbackLine(block, firstTitle, firstArtist, username, djName, first = true),
                        )
                        startMonitoringQueue(playerConnection, djName, username, djLanguage)
                        return@launch
                    }
                }

                if (profile == null) loadProfile()
                val topSongs = database.songsByPlayTimeAsc().first().reversed().take(5)
                val topContext = tasteContext()
                    ?: topSongs.joinToString(", ") { "${it.title} de ${it.artists.firstOrNull()?.name ?: "Unknown"}" }

                val geminiClient = GeminiDjClient(httpClient)
                val requestText = userRequest ?: "Armame un mix para descubrir musica fresca basándote en: $topContext"

                _statusText.value = "Preparando tu mezcla..."
                val agentResponseResult = runCatching { geminiClient.executeAgentTask(requestText, "animado", djName) }.getOrNull()

                val agentRes = if (agentResponseResult?.isSuccess == true) {
                    agentResponseResult.getOrThrow()
                } else {
                    // Fallback autónomo inteligente cuando la IA externa no está disponible
                    val fallbackSearch = tasteQuery() ?: if (topSongs.isNotEmpty()) {
                        val favArtist = topSongs.first().artists.firstOrNull()?.name ?: topSongs.first().title
                        "$favArtist mix"
                    } else {
                        "Grandes éxitos mix"
                    }
                    val fallbackGreeting = if (username.isNotBlank()) {
                        "¡Qué tal $username! Soy $djName. He preparado esta sesión especial para ti. ¡Súbele al volumen!"
                    } else {
                        "¡Qué tal! Soy $djName. He preparado una selección exclusiva para ti. ¡A disfrutar la música!"
                    }
                    com.dezzy.music.ai.GeminiDjClient.AgentResponse(
                        action = "PLAY_MUSIC",
                        searchQuery = fallbackSearch,
                        songName = null,
                        playlistName = null,
                        djReply = fallbackGreeting
                    )
                }

                if (agentRes.action == "ADD_TO_PLAYLIST" && agentRes.songName != null && agentRes.playlistName != null) {
                    // Action: Add to Playlist
                    val searchResult = com.dezzy.music.innertube.YouTube.search(agentRes.songName, com.dezzy.music.innertube.YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    val songItem = searchResult?.items?.filterIsInstance<com.dezzy.music.innertube.models.SongItem>()?.firstOrNull()
                    if (songItem != null) {
                        val playlist = database.playlistsByNameAsc().first().find { it.playlist.name.contains(agentRes.playlistName, ignoreCase = true) }
                        if (playlist != null) {
                            database.insert(
                                com.dezzy.music.db.entities.PlaylistSongMap(
                                    playlistId = playlist.id,
                                    songId = songItem.id,
                                    position = 0
                                )
                            )
                            database.insert(
                                com.dezzy.music.db.entities.SongEntity(
                                    id = songItem.id,
                                    title = songItem.title,
                                    thumbnailUrl = songItem.thumbnail
                                )
                            )
                            songItem.artists.forEach { artist ->
                                database.insert(
                                    com.dezzy.music.db.entities.ArtistEntity(
                                        id = artist.id ?: artist.name,
                                        name = artist.name
                                    )
                                )
                                database.insert(
                                    com.dezzy.music.db.entities.SongArtistMap(
                                        songId = songItem.id,
                                        artistId = artist.id ?: artist.name,
                                        position = 0
                                    )
                                )
                            }
                        }
                    }
                } else if (agentRes.action == "PLAY_MUSIC" && agentRes.searchQuery != null) {
                    _statusText.value = "Buscando canciones..."
                    val result = com.dezzy.music.innertube.YouTube.search(agentRes.searchQuery, com.dezzy.music.innertube.YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    var agentSet = DjSetBuilder.buildFromRequest(
                        agentRes.searchQuery,
                        result?.items?.filterIsInstance<com.dezzy.music.innertube.models.SongItem>().orEmpty(),
                        profile,
                    )

                    if (agentSet.items.isEmpty()) {
                        val backup = tasteQuery() ?: "Top Hits 2026"
                        val fallbackResult = com.dezzy.music.innertube.YouTube.search(backup, com.dezzy.music.innertube.YouTube.SearchFilter.FILTER_SONG).getOrNull()
                        agentSet = DjSetBuilder.buildFromRequest(
                            backup,
                            fallbackResult?.items?.filterIsInstance<com.dezzy.music.innertube.models.SongItem>().orEmpty(),
                            profile,
                        )
                    }
                    val mediaItems = agentSet.items
                    djSet = agentSet

                    if (mediaItems.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            playerConnection.playQueue(com.dezzy.music.playback.queues.ListQueue(
                                title = "$djName Mix",
                                items = mediaItems
                            ))
                            _uiState.value = DJUiState.PLAYING
                        }

                        val firstSong = mediaItems.first()
                        generateAndPlayDjTrack(playerConnection, djName, username, djLanguage, firstSong.mediaMetadata.title.toString(), firstSong.mediaMetadata.artist.toString(), isFirstSong = true, customRequest = agentRes.djReply)
                        startMonitoringQueue(playerConnection, djName, username, djLanguage)
                        return@launch
                    }
                }

                // Fallback seguro a música popular si la búsqueda anterior no arrojó items
                val lastResort = tasteQuery() ?: "Grandes exitos"
                val fallbackResult = com.dezzy.music.innertube.YouTube.search(lastResort, com.dezzy.music.innertube.YouTube.SearchFilter.FILTER_SONG).getOrNull()
                val fallbackSet = DjSetBuilder.buildFromRequest(
                    lastResort,
                    fallbackResult?.items?.filterIsInstance<com.dezzy.music.innertube.models.SongItem>().orEmpty(),
                    profile,
                )
                djSet = fallbackSet
                val mediaItems = fallbackSet.items
                if (mediaItems.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        playerConnection.playQueue(com.dezzy.music.playback.queues.ListQueue(
                            title = "$djName Mix",
                            items = mediaItems
                        ))
                        _uiState.value = DJUiState.PLAYING
                    }
                    val firstSong = mediaItems.first()
                    generateAndPlayDjTrack(playerConnection, djName, username, djLanguage, firstSong.mediaMetadata.title.toString(), firstSong.mediaMetadata.artist.toString(), isFirstSong = true, customRequest = agentRes.djReply)
                    startMonitoringQueue(playerConnection, djName, username, djLanguage)
                } else {
                    withContext(Dispatchers.Main) {
                        _statusText.value = "$djName al aire"
                        _uiState.value = DJUiState.IDLE
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _statusText.value = "$djName al aire"
                    _uiState.value = DJUiState.IDLE
                }
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
            var previousIndex = -1
            var expansions = 0
            val blockSkips = HashMap<Int, Int>()
            // Última posición vista de cada canción: al cambiar, dice si la anterior se saltó o se oyó entera.
            val lastSeen = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()
            launch(Dispatchers.Main) {
                while (true) {
                    val p = playerConnection.player
                    p.currentMediaItem?.mediaId?.let { id -> lastSeen[id] = p.currentPosition to p.duration.coerceAtLeast(0L) }
                    kotlinx.coroutines.delay(1_000)
                }
            }

            playerConnection.currentMediaItemIndex.collect { index ->
                val title = playerConnection.queueTitle.value ?: ""
                if (!title.contains("Mix", ignoreCase = true) && !title.contains("Top Hits", ignoreCase = true)) {
                    _uiState.value = DJUiState.IDLE
                    monitorJob?.cancel()
                    return@collect
                }

                // Se adapta a lo que haces, como el DJ de Spotify: si saltas, quita lo parecido que
                // venía; si te encanta algo nuevo, trae más en esa línea justo después.
                val prevPick = djSet?.picks?.getOrNull(previousIndex)
                if (prevPick != null && index == previousIndex + 1) {
                    val (pos, dur) = lastSeen[prevPick.item.mediaId] ?: (0L to 0L)
                    val skipped = pos in 1L until 30_000L && dur > 60_000L
                    val heard = dur > 0 && pos >= dur * 0.85
                    val liked = !skipped && runCatching { database.getSongById(prevPick.item.mediaId)?.song?.liked }.getOrNull() == true
                    if (skipped) {
                        adaptToSkip(playerConnection, previousIndex, prevPick, index, blockSkips)
                    } else if ((heard || liked) && prevPick.kind in NEW_KINDS && expansions < 4) {
                        expansions++
                        launch { expandAfter(playerConnection, prevPick) }
                    }
                }
                previousIndex = index

                val currentItem = withContext(Dispatchers.Main) { playerConnection.player.currentMediaItem }
                val songTitle = currentItem?.mediaMetadata?.title?.toString() ?: "una canción"
                val artistName = currentItem?.mediaMetadata?.artist?.toString() ?: "tu artista favorito"

                // Como el DJ de Spotify: habla al empezar cada bloque y cuenta por qué viene.
                // Si la cola ya no es la de la sesión (el usuario la cambió), cada 4 canciones.
                val set = djSet
                val followsSet = set != null && set.blocks.size > 1 &&
                    set.items.getOrNull(index)?.mediaId == currentItem?.mediaId
                val block = if (followsSet) set?.blockStartingAt(index) else null
                val speak = if (followsSet) {
                    block != null && index > 0 && index - lastSpokenIndex >= 2
                } else {
                    index > 0 && index - lastSpokenIndex >= 4
                }
                if (speak) {
                    lastSpokenIndex = index

                    val contextualRequest = when {
                        block != null -> DjSetBuilder.describe(block, songTitle, artistName, first = false)
                        previousSongTitle.isNotEmpty() ->
                            "Acabamos de escuchar $previousSongTitle de $previousArtistName, y ahora viene $songTitle de $artistName. Haz una transición fluida."
                        else -> null
                    }

                    generateAndPlayDjTrack(
                        playerConnection, djName, username, djLanguage, songTitle, artistName,
                        isFirstSong = false,
                        customRequest = contextualRequest,
                        fallback = block?.let { DjSetBuilder.fallbackLine(it, songTitle, artistName, username, djName, first = false) },
                    )
                }

                previousSongTitle = songTitle
                previousArtistName = artistName
            }
        }
    }

    /**
     * Saltaste [skipped]: fuera las novedades que venían del mismo artista y, si ya van dos saltos
     * en un bloque de novedades, el resto de ese bloque. La cola del reproductor y [djSet] cambian
     * a la vez (sólo detrás de la canción actual, así los índices de lo que suena no se mueven).
     */
    private suspend fun adaptToSkip(
        playerConnection: PlayerConnection,
        skippedIndex: Int,
        skipped: DjSetBuilder.Pick,
        currentIndex: Int,
        blockSkips: MutableMap<Int, Int>,
    ) = withContext(Dispatchers.Main) {
        val set = djSet ?: return@withContext
        if (playerConnection.player.currentMediaItem?.mediaId != set.items.getOrNull(currentIndex)?.mediaId) return@withContext
        val keys = DjSetBuilder.artistKeys(skipped.item)
        val block = set.blockAt(skippedIndex)
        val skipsInBlock = block?.let { b -> ((blockSkips[b.start] ?: 0) + 1).also { blockSkips[b.start] = it } } ?: 0
        val dropBlock = skipped.kind in NEW_KINDS && skipsInBlock >= 2 && block != null
        val drop = set.picks.indices.filter { i ->
            val p = set.picks[i]
            i > currentIndex && p.kind in NEW_KINDS && (
                (DjSetBuilder.artistKeys(p.item) intersect keys).isNotEmpty() ||
                    (dropBlock && i < block!!.start + block.size)
                )
        }
        if (drop.isEmpty()) return@withContext
        drop.sortedDescending().forEach { playerConnection.player.removeMediaItem(it) }
        val gone = drop.toHashSet()
        djSet = DjSetBuilder.DjSet(set.picks.filterIndexed { i, _ -> i !in gone })
    }

    /** Te gustó [seed] (escuchada entera o con me gusta): mete tres parecidas justo después de lo que suena. */
    private suspend fun expandAfter(playerConnection: PlayerConnection, seed: DjSetBuilder.Pick) {
        val exclude = djSet?.items?.mapTo(HashSet()) { it.mediaId } ?: return
        val more = runCatching { DjSetBuilder.moreLike(seed.item, profile, exclude) }.getOrNull().orEmpty()
        if (more.isEmpty()) return
        withContext(Dispatchers.Main) {
            val set = djSet ?: return@withContext
            val player = playerConnection.player
            val current = player.currentMediaItemIndex
            if (set.items.getOrNull(current)?.mediaId != player.currentMediaItem?.mediaId) return@withContext
            val inSet = set.items.mapTo(HashSet()) { it.mediaId }
            val fresh = more.filter { it.item.mediaId !in inSet }
            if (fresh.isEmpty()) return@withContext
            val at = current + 1
            player.addMediaItems(at, fresh.map { it.item })
            djSet = DjSetBuilder.DjSet(set.picks.take(at) + fresh + set.picks.drop(at))
        }
    }

    private companion object {
        val NEW_KINDS = setOf(DjSetBuilder.Kind.DISCOVERY, DjSetBuilder.Kind.ARTIST_NEW)
    }


    private suspend fun generateAndPlayDjTrack(
        playerConnection: PlayerConnection,
        djName: String,
        username: String,
        djLanguage: String,
        songTitle: String,
        artistName: String,
        isFirstSong: Boolean = false,
        customRequest: String? = null,
        fallback: String? = null,
    ) {
        withContext(Dispatchers.IO) {
            _statusText.value = "Escribiendo guion con Gemini..."

            val httpClient = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val scriptResult = GeminiDjClient(httpClient).generateScript(djName, username, djLanguage, songTitle, artistName, isFirstSong, customRequest)
            val script = scriptResult.getOrElse {
                fallback ?: if (isFirstSong) {
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


            val ttsResult = elevenLabsClient.synthesize(script, voiceName, outputFile)
            var ttsError = ""
            val audioUri = if (ttsResult.isSuccess) {
                android.net.Uri.fromFile(outputFile).toString()
            } else {
                // Fallback to OmniVoice
                val fallbackResult = omniVoiceClient.synthesize(script, voiceName, outputFile)
                if (fallbackResult.isSuccess) {
                    android.net.Uri.fromFile(outputFile).toString()
                } else {
                    ttsError = fallbackResult.exceptionOrNull()?.message ?: "Error desconocido"
                    val encodedScript = java.net.URLEncoder.encode(script, "UTF-8")
                    "https://translate.google.com/translate_tts?ie=UTF-8&q=$encodedScript&tl=es-MX&client=tw-ob"
                }
            }

            _djScript.value = script
            _statusText.value = "$djName al aire"

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

                if (ttsError.isEmpty()) {
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
                // JSONObject escapa comillas y saltos de línea (antes se rompía el JSON con «"»).
                val body = JSONObject().put("text", text).toString().toRequestBody(mediaType)
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
