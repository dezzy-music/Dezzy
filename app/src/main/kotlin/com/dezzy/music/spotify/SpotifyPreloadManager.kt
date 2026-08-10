/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * App-scoped engine for the Spotify preload job. Lives outside any screen so the
 * preload keeps running when the user navigates away or locks the phone; while a
 * preload is active, [SpotifyPreloadService] holds a foreground notification with
 * progress so the process isn't killed. The screen's ViewModel is a thin facade
 * over this singleton.
 */

package com.dezzy.music.spotify

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.dezzy.music.constants.SpotifyPreloadDownloadKey
import com.dezzy.music.db.MusicDatabase
import com.dezzy.music.db.entities.PlaylistEntity
import com.dezzy.music.db.entities.PlaylistSongMap
import com.dezzy.music.utils.dataStore
import com.dezzy.music.viewmodels.SelectablePlaylist
import com.dezzy.music.viewmodels.SpotifyPreloadState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyPreloadManager @Inject constructor(
    private val database: MusicDatabase,
    @ApplicationContext private val context: Context,
) {
    private val _state = MutableStateFlow<SpotifyPreloadState>(SpotifyPreloadState.Idle)
    val state: StateFlow<SpotifyPreloadState> = _state.asStateFlow()

    /** Ids (playlist id or [LIKED_SONGS_ID]) the user has ticked in the picker. */
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    // App-lifetime scope: the job must outlive any screen. The foreground service keeps
    // the process alive; this scope keeps the job independent from ViewModel clearing.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var preloadJob: Job? = null
    private var cancelled = false

    /** Full playlist objects cached from [loadPlaylists] so [startPreload] doesn't refetch. */
    private var fetchedPlaylists: List<SpotifyPlaylist> = emptyList()

    private fun isBusy(): Boolean = when (_state.value) {
        is SpotifyPreloadState.FetchingPlaylists,
        is SpotifyPreloadState.Counting,
        is SpotifyPreloadState.Converting,
        -> true
        else -> false
    }

    /**
     * Step 1: fetch only the playlist metadata (names + covers, no tracks) and let the
     * user choose what to preload instead of pulling the whole library at once.
     */
    fun loadPlaylists() {
        if (isBusy()) return
        cancelled = false
        preloadJob?.cancel()
        preloadJob = scope.launch {
            try {
                runFetchPlaylists()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = SpotifyPreloadState.Cancelled
                throw e
            } catch (e: Exception) {
                Timber.e(e, "SpotifyPreload: fetching playlists failed")
                _state.value = SpotifyPreloadState.Error(e.message ?: "Error inesperado")
            }
        }
    }

    fun toggleSelection(id: String) {
        _selectedIds.value = _selectedIds.value.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
    }

    fun selectAll() {
        val s = _state.value as? SpotifyPreloadState.SelectingPlaylists ?: return
        _selectedIds.value = s.playlists.map { it.id }.toSet()
    }

    fun selectNone() {
        _selectedIds.value = emptySet()
    }

    /** Step 2: preload only the entries the user ticked, inside a foreground service. */
    fun startPreload() {
        if (_state.value !is SpotifyPreloadState.SelectingPlaylists) return
        val selected = _selectedIds.value
        if (selected.isEmpty()) return
        val includeLiked = LIKED_SONGS_ID in selected
        val playlists = fetchedPlaylists.filter { it.id in selected }
        cancelled = false
        preloadJob?.cancel()
        // Set a busy state BEFORE starting the service: it stops itself on non-busy states,
        // so if it observed the still-current SelectingPlaylists it would quit immediately.
        _state.value = SpotifyPreloadState.FetchingPlaylists
        // The service observes `state` for progress and stops itself on terminal states.
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SpotifyPreloadService::class.java),
            )
        }.onFailure { Timber.w(it, "SpotifyPreload: could not start foreground service") }
        preloadJob = scope.launch {
            try {
                runPreload(playlists, includeLiked)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Job was cancelled at a suspension point — the in-loop `cancelled` checks
                // never got to run, so set the terminal state here.
                _state.value = SpotifyPreloadState.Cancelled
                throw e
            } catch (e: Exception) {
                // Without this, any network/parse hiccup killed the whole app process.
                Timber.e(e, "SpotifyPreload: failed")
                _state.value = SpotifyPreloadState.Error(e.message ?: "Error inesperado")
            }
        }
    }

    fun cancel() {
        cancelled = true
        // Cancelling the job interrupts runPreload at a suspension point, so the loop's own
        // `cancelled` checks may never run again — set the terminal state up-front or the UI
        // stays stuck on a progress screen with no way to restart.
        _state.value = SpotifyPreloadState.Cancelled
        preloadJob?.cancel()
    }

    fun resetToIdle() {
        if (isBusy()) return
        _state.value = SpotifyPreloadState.Idle
    }

    /** A Spotify playlist plus its ordered tracks, captured during fetch. */
    private data class PreloadPlaylist(
        val name: String,
        val thumbnailUrl: String?,
        val tracks: List<SpotifyTrack>,
    )

    /** Fetches playlist metadata only (paginated) and moves to the selection state. */
    private suspend fun runFetchPlaylists() = withContext(Dispatchers.IO) {
        _state.value = SpotifyPreloadState.FetchingPlaylists

        if (!SpotifyTokenManager.ensureAuthenticated()) {
            _state.value = SpotifyPreloadState.Error("Authentication failed")
            return@withContext
        }

        val playlists = mutableListOf<SpotifyPlaylist>()
        var plOffset = 0
        val plLimit = 50
        while (true) {
            if (cancelled) {
                _state.value = SpotifyPreloadState.Cancelled
                return@withContext
            }
            val plCall = Spotify.myPlaylists(limit = plLimit, offset = plOffset)
            val plResult = plCall.getOrNull()
                ?: run {
                    val reason = plCall.exceptionOrNull()?.message ?: "desconocido"
                    _state.value = SpotifyPreloadState.Error("No se pudieron cargar las playlists: $reason")
                    return@withContext
                }
            val page = plResult.items
            if (page.isEmpty()) break
            playlists.addAll(page)
            if (page.size < plLimit || plOffset + page.size >= plResult.total) break
            plOffset += page.size
        }

        fetchedPlaylists = playlists

        val selectable = buildList {
            // Liked Songs is always offered as the first pseudo-entry.
            add(
                SelectablePlaylist(
                    id = LIKED_SONGS_ID,
                    name = "Tus me gusta de Spotify",
                    thumbnailUrl = null,
                    isLikedSongs = true,
                ),
            )
            playlists.forEach {
                add(
                    SelectablePlaylist(
                        id = it.id,
                        name = it.name,
                        thumbnailUrl = it.images?.firstOrNull()?.url,
                    ),
                )
            }
        }
        // Default: everything selected — one tap keeps the old "preload it all" behavior.
        _selectedIds.value = selectable.map { it.id }.toSet()
        _state.value = SpotifyPreloadState.SelectingPlaylists(selectable)
    }

    private suspend fun runPreload(
        selectedPlaylists: List<SpotifyPlaylist>,
        includeLiked: Boolean,
    ) = withContext(Dispatchers.IO) {
        val mapper = SpotifyYouTubeMapper(database, context)
        val downloadOffline = runCatching {
            context.dataStore.data.first()[SpotifyPreloadDownloadKey] ?: false
        }.getOrDefault(false)
        // true (default): liked songs go ONLY to the "Me gusta de Spotify" playlist.
        // false: they are also flagged liked, merging into the app's native Liked songs.
        val likedAsPlaylistOnly = runCatching {
            context.dataStore.data.first()[com.dezzy.music.constants.SpotifyPreloadLikedAsPlaylistKey] ?: true
        }.getOrDefault(true)

        _state.value = SpotifyPreloadState.FetchingPlaylists

        if (!SpotifyTokenManager.ensureAuthenticated()) {
            _state.value = SpotifyPreloadState.Error("Authentication failed")
            return@withContext
        }

        // Preserve playlist → tracks structure so we can recreate the playlists locally,
        // and a flat de-duped set (by id) for counting + resolving each track once.
        val allTracks = LinkedHashMap<String, SpotifyTrack>()
        val preloadPlaylists = mutableListOf<PreloadPlaylist>()
        val likedTrackIds = LinkedHashSet<String>()

        // Fetch the tracks of each *selected* playlist (paginated)
        for (playlist in selectedPlaylists) {
            if (cancelled) {
                _state.value = SpotifyPreloadState.Cancelled
                return@withContext
            }
            val playlistTracks = mutableListOf<SpotifyTrack>()
            var trackOffset = 0
            val trackLimit = 100
            while (true) {
                val ptResult = Spotify.playlistTracks(
                    playlist.id,
                    limit = trackLimit,
                    offset = trackOffset,
                ).getOrNull() ?: break
                val items = ptResult.items.mapNotNull { it.track?.takeIf { t -> !t.isLocal } }
                playlistTracks.addAll(items)
                items.forEach { allTracks.putIfAbsent(it.id, it) }
                if (items.size < trackLimit || trackOffset + items.size >= ptResult.total) break
                trackOffset += items.size
                // Gentle pacing so a big library doesn't trip Spotify's rate limiter.
                kotlinx.coroutines.delay(250)
            }
            preloadPlaylists.add(
                PreloadPlaylist(
                    name = playlist.name,
                    thumbnailUrl = playlist.images?.firstOrNull()?.url,
                    tracks = playlistTracks,
                ),
            )
            kotlinx.coroutines.delay(120)
        }

        // Fetch all liked songs (paginated) — only if the user ticked that entry
        if (includeLiked) {
            var likedOffset = 0
            val likedLimit = 50
            while (true) {
                if (cancelled) {
                    _state.value = SpotifyPreloadState.Cancelled
                    return@withContext
                }
                val likedResult = Spotify.likedSongs(limit = likedLimit, offset = likedOffset).getOrNull()
                    ?: break
                val tracks = likedResult.items.map { it.track }.filter { !it.isLocal }
                tracks.forEach {
                    allTracks.putIfAbsent(it.id, it)
                    likedTrackIds.add(it.id)
                }
                if (tracks.size < likedLimit || likedOffset + tracks.size >= likedResult.total) break
                likedOffset += tracks.size
            }
        }

        val list = allTracks.values.toList()
        val total = list.size
        _state.value = SpotifyPreloadState.Counting(total)

        if (total == 0) {
            _state.value = SpotifyPreloadState.Completed
            return@withContext
        }

        // Resolve each unique track to YouTube AND insert it into the library so it
        // actually shows up. Keep a spotifyId → youtubeId map to rebuild the playlists.
        val resolved = HashMap<String, String>(total)
        var converted = 0
        for ((index, track) in list.withIndex()) {
            if (cancelled) {
                _state.value = SpotifyPreloadState.Cancelled
                return@withContext
            }
            runCatching {
                val metadata = mapper.mapToYouTube(track) ?: return@runCatching
                // Only merge into the app's native likes when the user explicitly chose to —
                // otherwise Spotify likes live solely in their own playlist (no duplicates).
                val isLiked = !likedAsPlaylistOnly && likedTrackIds.contains(track.id)
                val now = java.time.LocalDateTime.now()
                // Insert the song (IGNORE if it already exists) with library/liked flags,
                // then force the flags on even for pre-existing rows.
                database.insert(metadata) { entity ->
                    entity.copy(
                        inLibrary = now,
                        liked = isLiked || entity.liked,
                        likedDate = if (isLiked) now else entity.likedDate,
                    )
                }
                database.inLibrary(metadata.id, now)
                if (isLiked) database.setLiked(metadata.id, true, now)
                resolved[track.id] = metadata.id
                // Optional: download the song for real offline playback via the
                // existing ExoPlayer download pipeline.
                if (downloadOffline) enqueueDownload(metadata.id, metadata.title)
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "SpotifyPreload: failed to map/insert '${track.name}', skipping")
            }
            converted = index + 1
            _state.value = SpotifyPreloadState.Converting(converted, total)
        }

        // Recreate each Spotify playlist locally with its resolved songs, in order.
        for (pl in preloadPlaylists) {
            if (cancelled) {
                _state.value = SpotifyPreloadState.Cancelled
                return@withContext
            }
            val songIds = pl.tracks.mapNotNull { resolved[it.id] }.distinct()
            if (songIds.isEmpty()) continue
            runCatching {
                val playlistEntity = PlaylistEntity(
                    name = pl.name,
                    thumbnailUrl = pl.thumbnailUrl,
                    isLocal = true,
                    // The library playlists screen only lists playlists with
                    // bookmarkedAt set (WHERE bookmarkedAt IS NOT NULL) — without this
                    // the imported playlists were created but never shown.
                    bookmarkedAt = java.time.LocalDateTime.now(),
                )
                database.insert(playlistEntity)
                songIds.forEachIndexed { position, songId ->
                    database.insert(
                        PlaylistSongMap(
                            songId = songId,
                            playlistId = playlistEntity.id,
                            position = position,
                        ),
                    )
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "SpotifyPreload: failed to create playlist '${pl.name}'")
            }
        }

        // Create an explicit "Liked Songs" playlist mirroring Spotify's, in original order.
        // (When the user opted in, the individual songs are also flagged liked=true.)
        val likedSongIds = likedTrackIds.mapNotNull { resolved[it] }.distinct()
        if (likedSongIds.isNotEmpty()) {
            runCatching {
                val likedPlaylist = PlaylistEntity(
                    name = "Me gusta de Spotify",
                    isLocal = true,
                    bookmarkedAt = java.time.LocalDateTime.now(),
                )
                database.insert(likedPlaylist)
                likedSongIds.forEachIndexed { position, songId ->
                    database.insert(
                        PlaylistSongMap(
                            songId = songId,
                            playlistId = likedPlaylist.id,
                            position = position,
                        ),
                    )
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "SpotifyPreload: failed to create Liked Songs playlist")
            }
        }

        Timber.d("SpotifyPreload: completed — ${resolved.size}/$total tracks, ${preloadPlaylists.size} playlists, ${likedSongIds.size} liked")
        _state.value = SpotifyPreloadState.Completed
    }

    /** Enqueues a real offline download through the app's ExoPlayer download service. */
    private fun enqueueDownload(songId: String, title: String) {
        runCatching {
            val request = androidx.media3.exoplayer.offline.DownloadRequest
                .Builder(songId, android.net.Uri.parse(songId))
                .setCustomCacheKey(songId)
                .setData(title.toByteArray())
                .build()
            androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                context,
                com.dezzy.music.playback.ExoDownloadService::class.java,
                request,
                false,
            )
        }.onFailure { Timber.w(it, "SpotifyPreload: failed to enqueue download for $songId") }
    }

    companion object {
        /** Pseudo-id for the "Liked Songs" entry in the selection picker. */
        const val LIKED_SONGS_ID = "__spotify_liked_songs__"
    }
}
