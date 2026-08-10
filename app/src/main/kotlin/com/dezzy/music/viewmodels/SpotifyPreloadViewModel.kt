/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Thin facade over [SpotifyPreloadManager] for the preload screen. All the real
 * work lives in the app-scoped manager (backed by a foreground service), so the
 * preload keeps running when the user leaves the screen or locks the phone —
 * this ViewModel only forwards calls and exposes the shared state flows.
 */

package com.dezzy.music.viewmodels

import androidx.lifecycle.ViewModel
import com.dezzy.music.spotify.SpotifyPreloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

sealed class SpotifyPreloadState {
    data object Idle : SpotifyPreloadState()
    data object FetchingPlaylists : SpotifyPreloadState()

    /** Playlists fetched — waiting for the user to pick which ones to preload. */
    data class SelectingPlaylists(val playlists: List<SelectablePlaylist>) : SpotifyPreloadState()
    data class Counting(val total: Int) : SpotifyPreloadState()
    data class Converting(val current: Int, val total: Int) : SpotifyPreloadState()
    data object Completed : SpotifyPreloadState()
    data object Cancelled : SpotifyPreloadState()
    data class Error(val message: String) : SpotifyPreloadState()
}

/** One selectable entry in the pre-preload picker (a playlist, or the Liked Songs pseudo-entry). */
data class SelectablePlaylist(
    val id: String,
    val name: String,
    val thumbnailUrl: String?,
    val isLikedSongs: Boolean = false,
)

@HiltViewModel
class SpotifyPreloadViewModel @Inject constructor(
    private val manager: SpotifyPreloadManager,
) : ViewModel() {

    val state: StateFlow<SpotifyPreloadState> = manager.state
    val selectedIds: StateFlow<Set<String>> = manager.selectedIds

    fun loadPlaylists() = manager.loadPlaylists()

    fun toggleSelection(id: String) = manager.toggleSelection(id)

    fun selectAll() = manager.selectAll()

    fun selectNone() = manager.selectNone()

    fun startPreload() = manager.startPreload()

    fun cancel() = manager.cancel()

    fun resetToIdle() = manager.resetToIdle()

    companion object {
        /** Re-exposed for UI code that only knows the ViewModel facade. */
        const val LIKED_SONGS_ID = SpotifyPreloadManager.LIKED_SONGS_ID
    }
}
