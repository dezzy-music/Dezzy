/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dezzy.music.lossless.LosslessAlbum
import com.dezzy.music.lossless.LosslessDownloadManager
import com.dezzy.music.lossless.LosslessExtension
import com.dezzy.music.lossless.LosslessQuality
import com.dezzy.music.lossless.LosslessTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Qué se busca en la pantalla lossless (chips «Canciones» / «Álbumes»). */
enum class LosslessSearchMode { TRACKS, ALBUMS }

@HiltViewModel
class LosslessViewModel @Inject constructor(
    val downloadManager: LosslessDownloadManager,
) : ViewModel() {

    val query = MutableStateFlow("")
    val mode = MutableStateFlow(LosslessSearchMode.TRACKS)
    val results = MutableStateFlow<List<LosslessTrack>>(emptyList())
    val albums = MutableStateFlow<List<LosslessAlbum>>(emptyList())
    val isSearching = MutableStateFlow(false)

    /** Consulta (y modo) de los resultados que se muestran; sirve para el estado vacío. */
    val searchedQuery = MutableStateFlow("")

    /** Álbum desplegado y las pistas ya cargadas de cada álbum (por id de Deezer). */
    val expandedAlbumId = MutableStateFlow<String?>(null)
    val albumTracks = MutableStateFlow<Map<String, List<LosslessTrack>>>(emptyMap())
    val loadingAlbumId = MutableStateFlow<String?>(null)

    /** Estado de descargas por trackKey, expuesto directamente por el manager. */
    val downloads = downloadManager.downloads

    val extensions = MutableStateFlow<List<LosslessExtension>>(emptyList())
    val isLoadingExtensions = MutableStateFlow(false)

    private var searchJob: Job? = null

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onModeChange(value: LosslessSearchMode) {
        if (mode.value == value) return
        mode.value = value
        // La pantalla vuelve a buscar al ver el cambio de modo (mismo debounce que al teclear).
        searchedQuery.value = ""
    }

    /**
     * Busca la consulta actual. Si ya había una búsqueda en marcha la cancela (antes se
     * ignoraba la nueva, y con el debounce se quedaban resultados de lo tecleado a medias).
     */
    fun search() {
        val q = query.value.trim()
        searchJob?.cancel()
        if (q.isEmpty()) {
            isSearching.value = false
            results.value = emptyList()
            albums.value = emptyList()
            searchedQuery.value = ""
            return
        }
        val searchMode = mode.value
        searchJob = viewModelScope.launch {
            isSearching.value = true
            when (searchMode) {
                LosslessSearchMode.TRACKS -> results.value = downloadManager.search(q).distinctBy { it.provider.id + it.providerTrackId }
                LosslessSearchMode.ALBUMS -> {
                    albums.value = downloadManager.searchAlbums(q).distinctBy { it.id }
                    expandedAlbumId.value = null
                }
            }
            searchedQuery.value = q
            isSearching.value = false
        }
    }

    /** Despliega o pliega un álbum; la primera vez carga sus pistas. */
    fun toggleAlbum(album: LosslessAlbum) {
        if (expandedAlbumId.value == album.id) {
            expandedAlbumId.value = null
            return
        }
        expandedAlbumId.value = album.id
        if (albumTracks.value.containsKey(album.id)) return
        viewModelScope.launch {
            loadingAlbumId.value = album.id
            val tracks = downloadManager.albumTracks(album)
            if (tracks.isNotEmpty()) albumTracks.update { it + (album.id to tracks) }
            if (loadingAlbumId.value == album.id) loadingAlbumId.value = null
        }
    }

    fun download(track: LosslessTrack, quality: LosslessQuality) {
        viewModelScope.launch {
            downloadManager.enqueue(track, quality)
        }
    }

    fun downloadAll(tracks: List<LosslessTrack>, quality: LosslessQuality) {
        viewModelScope.launch {
            tracks.forEach { downloadManager.enqueue(it, quality) }
        }
    }

    /** Para una descarga en cola o en curso. */
    fun cancel(track: LosslessTrack) = downloadManager.cancel(track)

    fun clearFinishedDownloads() = downloadManager.clearFinished()

    fun loadExtensions(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            isLoadingExtensions.value = true
            extensions.value = downloadManager.registry.load(forceRefresh)
            isLoadingExtensions.value = false
        }
    }
}
