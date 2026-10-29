/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.dezzy.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.PlaylistItem
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.innertube.models.YTItem
import com.dezzy.music.innertube.models.filterExplicit
import com.dezzy.music.innertube.models.filterVideo
import com.dezzy.music.innertube.pages.ExplorePage
import com.dezzy.music.innertube.pages.HomePage
import com.dezzy.music.innertube.utils.completed
import com.dezzy.music.innertube.utils.parseCookieString
import com.dezzy.music.constants.HideExplicitKey
import com.dezzy.music.constants.HideVideoKey
import com.dezzy.music.constants.InnerTubeCookieKey
import com.dezzy.music.constants.QuickPicks
import com.dezzy.music.constants.QuickPicksKey
import com.dezzy.music.constants.SpeedDialSongIdsKey
import com.dezzy.music.constants.YtmSyncKey
import com.dezzy.music.db.MusicDatabase
import com.dezzy.music.db.entities.*
import com.dezzy.music.extensions.toEnum
import com.dezzy.music.models.ItemMetadata
import com.dezzy.music.models.SimilarRecommendation
import com.dezzy.music.playback.DownloadUtil
import com.dezzy.music.utils.dataStore
import com.dezzy.music.constants.PlaylistSortType
import com.dezzy.music.utils.get
import com.dezzy.music.utils.getAsync
import com.dezzy.music.utils.SyncUtils
import com.dezzy.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val syncUtils: SyncUtils,
    val downloadUtil: DownloadUtil,
) : ViewModel() {
    val isRefreshing = MutableStateFlow(false)
    val isLoading = MutableStateFlow(false)
    private val isInitialLoadComplete = MutableStateFlow(false)

    private val quickPicksEnum = context.dataStore.data.map {
        it[QuickPicksKey].toEnum(QuickPicks.QUICK_PICKS)
    }.distinctUntilChanged()

    val quickPicks = MutableStateFlow<List<Song>?>(null)
    val speedDialSongs = MutableStateFlow<List<Song>>(emptyList())
    val forgottenFavorites = MutableStateFlow<List<Song>?>(null)
    val keepListening = MutableStateFlow<List<LocalItem>?>(null)
    val similarRecommendations = MutableStateFlow<List<SimilarRecommendation>?>(null)
    val accountPlaylists = MutableStateFlow<List<PlaylistItem>?>(null)
    val homePage = MutableStateFlow<HomePage?>(null)
    val explorePage = MutableStateFlow<ExplorePage?>(null)
    val selectedChip = MutableStateFlow<HomePage.Chip?>(null)
    private val previousHomePage = MutableStateFlow<HomePage?>(null)

    val recentActivity = MutableStateFlow<List<YTItem>?>(null)
    val recentPlaylistsDb = MutableStateFlow<List<Playlist>?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val pinnedPlaylist1 = context.dataStore.data.map { it[com.dezzy.music.constants.PinnedPlaylist1Key] }
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) kotlinx.coroutines.flow.flowOf(null)
            else database.playlist(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val pinnedPlaylist2 = context.dataStore.data.map { it[com.dezzy.music.constants.PinnedPlaylist2Key] }
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) kotlinx.coroutines.flow.flowOf(null)
            else database.playlist(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ── Dezzy hero: última escucha, racha de días, minutos de hoy y mixes ──

    val lastListenEvent = database.lastListenEvent()
        // Un evento huérfano (canción borrada de `song`) hace que Room lance al
        // mapear EventWithSong.song y tumbaba la Home entera. La BD se sanea al
        // abrir (repairForeignKeyOrphans); esto cubre cualquier resto.
        .catch { e ->
            reportException(e)
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val todayListenMinutes = database.playTimeSince(
        java.time.LocalDate.now().atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli(),
    ).map { (it / 60_000L).toInt() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val listenStreakDays = database.listenTimestamps(
        java.time.LocalDate.now().minusDays(120).atStartOfDay()
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli(),
    ).map { stamps ->
        // Los timestamps se guardan como hora de pared local (ver Converters):
        // basta con quedarse con la fecha y contar días consecutivos hasta hoy
        // (la racha sigue viva si lo último fue ayer y hoy aún no escuchas).
        val days = stamps.mapTo(sortedSetOf()) { it.toLocalDate() }
        var cursor = java.time.LocalDate.now()
        if (cursor !in days && cursor.minusDays(1) in days) cursor = cursor.minusDays(1)
        var streak = 0
        while (cursor in days) {
            streak++
            cursor = cursor.minusDays(1)
        }
        streak
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val genreMixes = MutableStateFlow<List<GenreMix>>(emptyList())

    private fun loadGenreMixes() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val cache = com.dezzy.music.utils.GenreClassifier.loadCache(context)
                val pool = database.mostPlayedSongs(0, limit = 250).first()
                val grouped = pool.groupBy { s ->
                    cache[s.id]
                        ?: com.dezzy.music.utils.GenreClassifier.classifyText(
                            s.song.title,
                            s.artists.joinToString(" ") { it.name },
                            s.album?.title,
                        )
                        ?: com.dezzy.music.utils.GenreClassifier.UNKNOWN
                }
                genreMixes.value = grouped
                    .filterKeys { it != com.dezzy.music.utils.GenreClassifier.UNKNOWN }
                    .filterValues { it.size >= 4 }
                    .entries
                    .sortedByDescending { it.value.size }
                    .take(3)
                    .map { (key, songs) ->
                        GenreMix(
                            key = key,
                            name = com.dezzy.music.utils.GenreClassifier.displayName(key),
                            songs = songs,
                        )
                    }
            }.onFailure { Timber.w(it, "No se pudieron armar los mixes por género") }
        }
    }

    val allLocalItems = combine(
        quickPicks,
        forgottenFavorites,
        keepListening
    ) { quickPicks, forgottenFavorites, keepListening ->
        (quickPicks.orEmpty() + forgottenFavorites.orEmpty() + keepListening.orEmpty())
            .filter { it is Song || it is Album }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allYtItems = combine(
        similarRecommendations,
        homePage
    ) { similarRecommendations, homePage ->
        similarRecommendations?.flatMap { it.items }.orEmpty() +
                homePage?.sections?.flatMap { it.items }.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val libraryMetadata = combine(
        database.allSongs(),
        database.allArtistsByPlayTime(),
        database.playlists(PlaylistSortType.CREATE_DATE, true),
    ) { songs, artists, playlists ->
        val metadataMap = mutableMapOf<String, ItemMetadata>()

        songs.forEach { song ->
            metadataMap[song.id] = ItemMetadata(
                isLiked = song.song.liked,
                isInLibrary = song.song.inLibrary != null
            )
        }

        artists.forEach { artist ->
            if (artist.artist.bookmarkedAt != null) {
                metadataMap[artist.id] = ItemMetadata(isLiked = true)
            }
        }

        playlists.forEach { playlist ->
            if (playlist.playlist.bookmarkedAt != null) {
                metadataMap[playlist.id] = ItemMetadata(isLiked = true)
            }
        }

        metadataMap
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val allItemsMetadata = combine(
        libraryMetadata,
        downloadUtil.downloads
    ) { libraryMeta, downloads ->
        if (downloads.isEmpty()) return@combine libraryMeta

        val metadataMap = libraryMeta.toMutableMap()
        downloads.forEach { (id, download) ->
            val current = metadataMap[id] ?: ItemMetadata()
            metadataMap[id] = current.copy(downloadState = download.state)
        }
        metadataMap
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // Account display info
    val accountName = MutableStateFlow("Guest")
    val accountImageUrl = MutableStateFlow<String?>(null)
    
    // Track last processed cookie to avoid unnecessary updates
    private var lastProcessedCookie: String? = null
    
    // Track if we're currently processing account data
    private var isProcessingAccountData = false
    private var wasLoggedIn = false

    private fun filterHomeChips(chips: List<HomePage.Chip>?): List<HomePage.Chip>? {
        return chips?.filterNot { it.title.contains("podcasts", ignoreCase = true) }
    }

    private suspend fun getQuickPicks(){
        when (quickPicksEnum.first()) {
            QuickPicks.QUICK_PICKS -> {
                var picks = database.quickPicks().first().shuffled().take(20)
                if (picks.isEmpty()) {
                    picks = database.songsByPlayTimeAsc().first().reversed().take(20)
                }
                quickPicks.value = picks
            }
            QuickPicks.LAST_LISTEN -> songLoad()
        }
    }

    private suspend fun loadSpeedDialSongs() {
        val speedDialIds = context.dataStore.getAsync(SpeedDialSongIdsKey, "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(24)
        if (speedDialIds.isEmpty()) {
            speedDialSongs.value = emptyList()
            return
        }
        val songsById = database.getSongsByIds(speedDialIds).associateBy { it.id }
        speedDialSongs.value = speedDialIds.mapNotNull { songsById[it] }
    }

    private suspend fun load() {
        if (isLoading.value) return
        isLoading.value = true
        
        try {
            supervisorScope {
                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                val hideVideo = context.dataStore.get(HideVideoKey, false)
                val fromTimeStamp = System.currentTimeMillis() - 86400000 * 7 * 2

                launch { getQuickPicks() }
                launch { loadSpeedDialSongs() }
                launch { forgottenFavorites.value = database.forgottenFavorites().first().shuffled().take(20) }
                
                launch {
                    val keepListeningSongs = database.mostPlayedSongs(fromTimeStamp, limit = 15, offset = 5)
                        .first().shuffled().take(10)
                    val keepListeningAlbums = database.mostPlayedAlbums(fromTimeStamp, limit = 8, offset = 2)
                        .first().filter { it.album.thumbnailUrl != null }.shuffled().take(5)
                    val keepListeningArtists = database.mostPlayedArtists(fromTimeStamp)
                        .first().filter { it.artist.isYouTubeArtist && it.artist.thumbnailUrl != null }
                        .shuffled().take(5)
                    keepListening.value = (keepListeningSongs + keepListeningAlbums + keepListeningArtists).shuffled()
                }

                launch {
                        YouTube.home().onSuccess { page ->
                        homePage.value = page.copy(
                            chips = filterHomeChips(page.chips),
                            sections = page.sections.map { section ->
                                section.copy(items = section.items.filterExplicit(hideExplicit).filterVideo(hideVideo))
                            }
                        )
                    }.onFailure { reportException(it) }
                }

                launch {
                    YouTube.explore().onSuccess { page ->
                        val artists: MutableMap<Int, String> = mutableMapOf()
                        val favouriteArtists: MutableMap<Int, String> = mutableMapOf()
                        database.allArtistsByPlayTime().first().let { list ->
                            var favIndex = 0
                            for ((artistsIndex, artist) in list.withIndex()) {
                                artists[artistsIndex] = artist.id
                                if (artist.artist.bookmarkedAt != null) {
                                    favouriteArtists[favIndex] = artist.id
                                    favIndex++
                                }
                            }
                        }
                        explorePage.value = page.copy(
                            newReleaseAlbums = page.newReleaseAlbums
                                .sortedBy { album ->
                                    val artistIds = album.artists.orEmpty().mapNotNull { it.id }
                                    val firstArtistKey = artistIds.firstNotNullOfOrNull { artistId ->
                                        if (artistId in favouriteArtists.values) {
                                            favouriteArtists.entries.firstOrNull { it.value == artistId }?.key
                                        } else {
                                            artists.entries.firstOrNull { it.value == artistId }?.key
                                        }
                                    } ?: Int.MAX_VALUE
                                    firstArtistKey
                                }.filterExplicit(hideExplicit)
                        )
                    }.onFailure { reportException(it) }
                }
            }

            viewModelScope.launch(Dispatchers.IO) {
                loadSimilarRecommendations()
            }
                    
            isInitialLoadComplete.value = true
        } catch (e: Exception) {
            reportException(e)
        } finally {
            isLoading.value = false
        }
    }

    private suspend fun loadSimilarRecommendations() {
        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
        val hideVideo = context.dataStore.get(HideVideoKey, false)
        val fromTimeStamp = System.currentTimeMillis() - 86400000 * 7 * 2
        
        val artistRecommendations = database.mostPlayedArtists(fromTimeStamp, limit = 10).first()
            .filter { it.artist.isYouTubeArtist }
            .shuffled().take(3)
            .mapNotNull {
                val items = mutableListOf<YTItem>()
                YouTube.artist(it.id).onSuccess { page ->
                    items += page.sections.getOrNull(page.sections.size - 2)?.items.orEmpty()
                    items += page.sections.lastOrNull()?.items.orEmpty()
                }
                SimilarRecommendation(
                    title = it,
                    items = items.filterExplicit(hideExplicit).filterVideo(hideVideo).shuffled().ifEmpty { return@mapNotNull null }
                )
            }

        val songRecommendations = database.mostPlayedSongs(fromTimeStamp, limit = 10).first()
            .filter { it.album != null }
            .shuffled().take(2)
            .mapNotNull { song ->
                val endpoint = YouTube.next(WatchEndpoint(videoId = song.id)).getOrNull()?.relatedEndpoint
                    ?: return@mapNotNull null
                val page = YouTube.related(endpoint).getOrNull() ?: return@mapNotNull null
                SimilarRecommendation(
                    title = song,
                    items = (page.songs.shuffled().take(8) +
                            page.albums.shuffled().take(4) +
                            page.artists.shuffled().take(4) +
                            page.playlists.shuffled().take(4))
                        .filterExplicit(hideExplicit).filterVideo(hideVideo)
                        .shuffled()
                        .ifEmpty { return@mapNotNull null }
                )
            }

        similarRecommendations.value = (artistRecommendations + songRecommendations).shuffled()
    }

    private suspend fun songLoad() {
        val song = database.events().first().firstOrNull()?.song
        if (song != null) {
            if (database.hasRelatedSongs(song.id)) {
                val relatedSongs = database.getRelatedSongs(song.id).first().shuffled().take(20)
                quickPicks.value = relatedSongs
            } else {
                quickPicks.value = database.songsByPlayTimeAsc().first().reversed().take(20)
            }
        } else {
            quickPicks.value = database.songsByPlayTimeAsc().first().reversed().take(20)
        }
    }

    private val _isLoadingMore = MutableStateFlow(false)
    fun loadMoreYouTubeItems(continuation: String?) {
        if (continuation == null || _isLoadingMore.value) return
        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
        val hideVideo = context.dataStore.get(HideVideoKey, false)

        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingMore.value = true
            val nextSections = YouTube.home(continuation).getOrNull() ?: run {
                _isLoadingMore.value = false
                return@launch
            }

            homePage.value = nextSections.copy(
                chips = homePage.value?.chips,
                sections = (homePage.value?.sections.orEmpty() + nextSections.sections).map { section ->
                    section.copy(items = section.items.filterExplicit(hideExplicit).filterVideo(hideVideo))
                }
            )
            _isLoadingMore.value = false
        }
    }

    fun toggleChip(chip: HomePage.Chip?) {
        if (chip == null || chip == selectedChip.value && previousHomePage.value != null) {
            homePage.value = previousHomePage.value
            previousHomePage.value = null
            selectedChip.value = null
            return
        }

        if (selectedChip.value == null) {
            previousHomePage.value = homePage.value
        }

        viewModelScope.launch(Dispatchers.IO) {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideo = context.dataStore.get(HideVideoKey, false)
            val nextSections = YouTube.home(params = chip?.endpoint?.params).getOrNull() ?: return@launch

            homePage.value = nextSections.copy(
                chips = homePage.value?.chips,
                sections = nextSections.sections.map { section ->
                    section.copy(items = section.items.filterExplicit(hideExplicit).filterVideo(hideVideo))
                }
            )
            selectedChip.value = chip
        }
    }

    fun refresh() {
        if (isRefreshing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            isRefreshing.value = true
            load()
            isRefreshing.value = false
        }
    }

    fun refreshAccountData() {
        viewModelScope.launch(Dispatchers.IO) {
            if (isProcessingAccountData) return@launch
            
            isProcessingAccountData = true
            try {
                val cookie = context.dataStore.get(InnerTubeCookieKey, "")
                if (cookie.isNotEmpty()) {
                    YouTube.cookie = cookie
                    
                    YouTube.accountInfo().onSuccess { info ->
                        accountName.value = info.name
                        accountImageUrl.value = info.thumbnailUrl
                    }.onFailure {
                        timber.log.Timber.w(it, "Failed to fetch account info")
                    }

                    launch {
                        YouTube.library("FEmusic_liked_playlists").completed().onSuccess {
                            val lists = it.items.filterIsInstance<PlaylistItem>().filterNot { it.id == "SE" }
                            accountPlaylists.value = lists
                        }.onFailure {
                            timber.log.Timber.w(it, "Failed to fetch playlists")
                        }
                    }
                } else {
                    accountName.value = "Guest"
                    accountImageUrl.value = null
                    accountPlaylists.value = null
                }
            } finally {
                isProcessingAccountData = false
            }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            load()
        }

        loadGenreMixes()

        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.data
                .map { it[SpeedDialSongIdsKey].orEmpty() }
                .distinctUntilChanged()
                .collect {
                    loadSpeedDialSongs()
                }
        }

        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(3000)
            
            syncUtils.cleanupDuplicatePlaylists()
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            context.dataStore.data
                .map { it[InnerTubeCookieKey] }
                .distinctUntilChanged()
                .collect { cookie ->
                    if (isProcessingAccountData) return@collect
                    
                    lastProcessedCookie = cookie
                    isProcessingAccountData = true
                    
                    try {
                        val isLoggedIn = cookie?.let { "SAPISID" in parseCookieString(it) } ?: false
                        val loginTransition = isLoggedIn && !wasLoggedIn
                        wasLoggedIn = isLoggedIn
                        
                        if (isLoggedIn && cookie != null && cookie.isNotEmpty()) {
                            try {
                                YouTube.cookie = cookie
                            } catch (e: Exception) {
                                timber.log.Timber.e(e, "Failed to set YouTube cookie")
                                return@collect
                            }

                            if (loginTransition) {
                                launch {
                                    try {
                                        if (context.dataStore.get(YtmSyncKey, true)) {
                                            syncUtils.performFullSync()
                                        }
                                    } catch (e: Exception) {
                                        Timber.e(e, "Error during login-triggered sync")
                                        reportException(e)
                                    }
                                }
                            }
                            
                            kotlinx.coroutines.delay(100)
                            
                            try {
                                YouTube.accountInfo().onSuccess { info ->
                                    accountName.value = info.name
                                    accountImageUrl.value = info.thumbnailUrl
                                }.onFailure { e ->
                                    timber.log.Timber.w(e, "Failed to fetch account info")
                                }
                            } catch (e: Exception) {
                                timber.log.Timber.e(e, "Exception fetching account info")
                            }

                            launch {
                                try {
                                    YouTube.library("FEmusic_liked_playlists").completed().onSuccess {
                                        val lists = it.items.filterIsInstance<PlaylistItem>().filterNot { it.id == "SE" }
                                        accountPlaylists.value = lists
                                    }.onFailure { e ->
                                        timber.log.Timber.w(e, "Failed to fetch account playlists")
                                    }
                                } catch (e: Exception) {
                                    timber.log.Timber.e(e, "Exception fetching account playlists")
                                }
                            }
                        } else {
                            accountName.value = "Guest"
                            accountImageUrl.value = null
                            accountPlaylists.value = null
                        }
                    } catch (e: Exception) {
                        timber.log.Timber.e(e, "Error processing cookie change")
                        accountName.value = "Guest"
                        accountImageUrl.value = null
                        accountPlaylists.value = null
                    } finally {
                        isProcessingAccountData = false
                    }
                }
        }
    }
}

/** Mix automático por género para el hero de la home (carátulas = primeras canciones). */
data class GenreMix(
    val key: String,
    val name: String,
    val songs: List<Song>,
)
