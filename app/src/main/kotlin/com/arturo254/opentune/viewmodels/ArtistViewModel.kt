/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.arturo254.opentune.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arturo254.opentune.innertube.YouTube
import com.arturo254.opentune.innertube.models.filterExplicit
import com.arturo254.opentune.innertube.pages.ArtistPage
import com.arturo254.opentune.db.MusicDatabase
import com.arturo254.opentune.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.Context
import com.arturo254.opentune.constants.HideExplicitKey
import com.arturo254.opentune.extensions.filterExplicit
import com.arturo254.opentune.extensions.filterExplicitAlbums
import com.arturo254.opentune.utils.dataStore
import com.arturo254.opentune.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.arturo254.opentune.artistvideo.ArtistVideoCanvasProvider

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArtistViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val artistId = savedStateHandle.get<String>("artistId")!!
    var artistPage by mutableStateOf<ArtistPage?>(null)

    private val _artistVideoUrl = MutableStateFlow<String?>(null)
    val artistVideoUrl: StateFlow<String?> = _artistVideoUrl

    private val _artistVideoSong = MutableStateFlow<com.arturo254.opentune.innertube.models.SongItem?>(null)
    val artistVideoSong: StateFlow<com.arturo254.opentune.innertube.models.SongItem?> = _artistVideoSong
    val libraryArtist = database.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val librarySongs = context.dataStore.data
        .map { it[HideExplicitKey] ?: false }
        .distinctUntilChanged()
        .flatMapLatest { hideExplicit ->
            database.artistSongsByCreateDateAsc(artistId).map { it.filterExplicit(hideExplicit) } // show all
            // database.artistSongsPreview(artistId).map { it.filterExplicit(hideExplicit) } // only preview
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val libraryAlbums = context.dataStore.data
        .map { it[HideExplicitKey] ?: false }
        .distinctUntilChanged()
        .flatMapLatest { hideExplicit ->
            database.artistAlbumsPreview(artistId).map { it.filterExplicitAlbums(hideExplicit) }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        // Load artist page and reload when hide explicit setting changes
        viewModelScope.launch {
            context.dataStore.data
                .map { it[HideExplicitKey] ?: false }
                .distinctUntilChanged()
                .collect {
                    fetchArtistsFromYTM()
                }
        }
    }

    fun fetchArtistsFromYTM() {
        viewModelScope.launch {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val targetId = if (!artistId.startsWith("UC")) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        YouTube.search(artistId, filter = YouTube.SearchFilter.FILTER_ARTIST).getOrNull()
                            ?.items?.firstOrNull()?.id
                    }.getOrNull() ?: artistId
                }
            } else {
                artistId
            }

            YouTube.artist(targetId)
                .onSuccess { page ->
                    val filteredSections = page.sections
                        .map { section ->
                            section.copy(items = section.items.filterExplicit(hideExplicit))
                        }

                    artistPage = page.copy(sections = filteredSections)

                    val topSongsSection = page.sections.find { it.items.firstOrNull() is com.arturo254.opentune.innertube.models.SongItem }
                    viewModelScope.launch(Dispatchers.IO) {
                        val artistModeStr = context.dataStore.get(
                            com.arturo254.opentune.constants.ArtistCanvasModeKey,
                            com.arturo254.opentune.constants.ArtistCanvasMode.APPLE_WITH_FALLBACK.name
                        )
                        val artistMode = runCatching {
                            com.arturo254.opentune.constants.ArtistCanvasMode.valueOf(artistModeStr)
                        }.getOrDefault(com.arturo254.opentune.constants.ArtistCanvasMode.APPLE_WITH_FALLBACK)

                        if (artistMode == com.arturo254.opentune.constants.ArtistCanvasMode.NONE) return@launch

                        topSongsSection?.items?.forEach { item ->
                            if (item is com.arturo254.opentune.innertube.models.SongItem) {
                                val canvas = ArtistVideoCanvasProvider.getBySongArtist(
                                    song = item.title,
                                    artist = page.artist.title
                                )
                                if (canvas?.preferredAnimationUrl != null) {
                                    _artistVideoUrl.value = canvas.preferredAnimationUrl
                                    _artistVideoSong.value = item
                                    return@launch
                                }
                            }
                        }

                        if (artistMode == com.arturo254.opentune.constants.ArtistCanvasMode.APPLE_WITH_FALLBACK) {
                            val ytFallbackVid = runCatching {
                                val searchRes = YouTube.search(
                                    query = "${page.artist.title} Official Video",
                                    filter = YouTube.SearchFilter.FILTER_VIDEO
                                ).getOrNull()
                                val vId = searchRes?.items?.firstOrNull()?.id
                                if (!vId.isNullOrBlank()) {
                                    com.arturo254.opentune.utils.YTPlayerUtils.videoPlaybackDataForPlayback(vId).getOrNull()?.streamUrl
                                } else null
                            }.getOrNull()
                            if (!ytFallbackVid.isNullOrBlank()) {
                                _artistVideoUrl.value = ytFallbackVid
                            }
                        }
                    }
                }.onFailure {
                    if (targetId == artistId && !artistId.startsWith("UC")) {
                        withContext(Dispatchers.IO) {
                            val searchArtistId = runCatching {
                                YouTube.search(artistId, filter = YouTube.SearchFilter.FILTER_ARTIST).getOrNull()
                                    ?.items?.firstOrNull()?.id
                            }.getOrNull()
                            if (searchArtistId != null) {
                                YouTube.artist(searchArtistId).onSuccess { page ->
                                    val filteredSections = page.sections.map { section ->
                                        section.copy(items = section.items.filterExplicit(hideExplicit))
                                    }
                                    artistPage = page.copy(sections = filteredSections)
                                }
                            }
                        }
                    } else {
                        reportException(it)
                    }
                }
        }
    }
}
