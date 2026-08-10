/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arturo254.opentune.lossless.LosslessDownloadManager
import com.arturo254.opentune.lossless.LosslessExtension
import com.arturo254.opentune.lossless.LosslessQuality
import com.arturo254.opentune.lossless.LosslessTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LosslessViewModel @Inject constructor(
    val downloadManager: LosslessDownloadManager,
) : ViewModel() {

    val query = MutableStateFlow("")
    val results = MutableStateFlow<List<LosslessTrack>>(emptyList())
    val isSearching = MutableStateFlow(false)

    /** Estado de descargas por trackKey, expuesto directamente por el manager. */
    val downloads = downloadManager.downloads

    val extensions = MutableStateFlow<List<LosslessExtension>>(emptyList())
    val isLoadingExtensions = MutableStateFlow(false)

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun search() {
        val q = query.value.trim()
        if (q.isEmpty() || isSearching.value) return
        viewModelScope.launch {
            isSearching.value = true
            results.value = downloadManager.search(q)
            isSearching.value = false
        }
    }

    fun download(track: LosslessTrack, quality: LosslessQuality) {
        viewModelScope.launch {
            downloadManager.enqueue(track, quality)
        }
    }

    fun clearFinishedDownloads() = downloadManager.clearFinished()

    fun loadExtensions(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            isLoadingExtensions.value = true
            extensions.value = downloadManager.registry.load(forceRefresh)
            isLoadingExtensions.value = false
        }
    }
}
