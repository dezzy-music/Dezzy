/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Sincronización automática de la biblioteca con la cuenta de Spotify conectada:
 * sus playlists y sus «Me gusta» aparecen en la biblioteca sin pasar por el
 * selector de «Precargar». Se lanza al iniciar sesión y, después, como mucho
 * cada [MIN_INTERVAL_MS]. La parte de YouTube Music la lleva [com.dezzy.music.utils.SyncUtils].
 *
 * - Cada playlist de Spotify queda ligada a una playlist local; al volver a
 *   sincronizar se actualiza esa misma (no se duplica).
 * - Si el usuario borra una de esas playlists en Dezzy, no se vuelve a crear.
 * - Una playlist de una precarga manual anterior con el mismo nombre se adopta.
 */

package com.dezzy.music.spotify

import android.content.Context
import com.dezzy.music.constants.SpotifyPreloadLikedAsPlaylistKey
import com.dezzy.music.constants.SpotifySpDcKey
import com.dezzy.music.db.MusicDatabase
import com.dezzy.music.db.entities.PlaylistEntity
import com.dezzy.music.db.entities.PlaylistSongMap
import com.dezzy.music.utils.dataStore
import com.dezzy.music.viewmodels.SpotifyPreloadState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyLibrarySync @Inject constructor(
    private val database: MusicDatabase,
    private val preloadManager: SpotifyPreloadManager,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private val prefs by lazy { context.getSharedPreferences("spotify_library_sync", Context.MODE_PRIVATE) }

    /** true mientras se sincroniza, para que la biblioteca pueda mostrarlo. */
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** Empieza a vigilar la cuenta de Spotify. Llamarlo más de una vez no hace nada. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var wasLinked: Boolean? = null
            context.dataStore.data
                .map { !it[SpotifySpDcKey].isNullOrBlank() }
                .distinctUntilChanged()
                .collect { linked ->
                    val justLinked = linked && wasLinked == false
                    wasLinked = linked
                    if (!linked) return@collect
                    // Recién conectada: ya. Al abrir la app: sólo si toca.
                    syncNow(force = justLinked)
                }
        }
    }

    /** Sincroniza ahora (desde «Sincronizar» en la biblioteca, por ejemplo). */
    fun requestSync() {
        scope.launch { syncNow(force = true) }
    }

    private suspend fun syncNow(force: Boolean) {
        if (!force && System.currentTimeMillis() - prefs.getLong(KEY_LAST_SYNC, 0L) < MIN_INTERVAL_MS) return
        if (preloadBusy()) return
        mutex.withLock {
            _syncing.value = true
            try {
                if (!SpotifyTokenManager.ensureAuthenticated()) {
                    Timber.w("SpotifyLibrarySync: sin sesión válida de Spotify")
                    return
                }
                syncLibrary()
                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "SpotifyLibrarySync: falló la sincronización")
            } finally {
                _syncing.value = false
            }
        }
    }

    private fun preloadBusy(): Boolean = when (preloadManager.state.value) {
        is SpotifyPreloadState.FetchingPlaylists,
        is SpotifyPreloadState.Counting,
        is SpotifyPreloadState.Converting,
        -> true
        else -> false
    }

    private suspend fun syncLibrary() = withContext(Dispatchers.IO) {
        val mapper = SpotifyYouTubeMapper(database, context)
        val likedAsPlaylistOnly = runCatching {
            context.dataStore.data.first()[SpotifyPreloadLikedAsPlaylistKey] ?: true
        }.getOrDefault(true)

        // Las playlists locales por nombre, para adoptar las de una precarga manual.
        val localByName = runCatching {
            database.playlistsByNameAsc().first().associateBy { it.playlist.name }
        }.getOrDefault(emptyMap())

        val playlists = fetchAllPlaylists()
        var synced = 0
        for (playlist in playlists) {
            if (prefs.getBoolean(KEY_REMOVED + playlist.id, false)) continue
            val tracks = fetchPlaylistTracks(playlist.id) ?: continue
            val songIds = resolve(tracks, mapper)
            if (songIds.isEmpty()) continue
            if (writePlaylist(
                    remoteKey = KEY_PLAYLIST + playlist.id,
                    name = playlist.name,
                    thumbnailUrl = playlist.images?.firstOrNull()?.url,
                    songIds = songIds,
                    localByName = localByName,
                )
            ) synced++
            delay(150) // sin prisa, para no tropezar con el límite de Spotify
        }

        // «Me gusta»: su propia playlist y, si el usuario lo eligió, también en los Me gusta de Dezzy.
        val liked = fetchLikedTracks()
        if (liked.isNotEmpty() && !prefs.getBoolean(KEY_REMOVED + LIKED_ID, false)) {
            val likedIds = resolve(liked, mapper)
            if (!likedAsPlaylistOnly) {
                val now = LocalDateTime.now()
                likedIds.forEach { id -> runCatching { database.setLiked(id, true, now) } }
            }
            writePlaylist(
                remoteKey = KEY_PLAYLIST + LIKED_ID,
                name = LIKED_NAME,
                thumbnailUrl = null,
                songIds = likedIds,
                localByName = localByName,
            )
        }
        Timber.d("SpotifyLibrarySync: ${playlists.size} playlists ($synced actualizadas), ${liked.size} me gusta")
    }

    private fun fetchAllPlaylists(): List<SpotifyPlaylist> {
        val out = ArrayList<SpotifyPlaylist>()
        var offset = 0
        while (true) {
            val page = Spotify.myPlaylists(limit = 50, offset = offset).getOrNull() ?: break
            out += page.items
            if (page.items.size < 50 || offset + page.items.size >= page.total) break
            offset += page.items.size
        }
        return out
    }

    /** null si la descarga se cortó a medias: así no se vacía una playlist por un fallo de red. */
    private suspend fun fetchPlaylistTracks(playlistId: String): List<SpotifyTrack>? {
        val out = ArrayList<SpotifyTrack>()
        var offset = 0
        while (true) {
            val page = Spotify.playlistTracks(playlistId, limit = 100, offset = offset).getOrNull() ?: return null
            out += page.items.mapNotNull { it.track?.takeIf { t -> !t.isLocal } }
            if (page.items.size < 100 || offset + page.items.size >= page.total) break
            offset += page.items.size
            delay(250)
        }
        return out
    }

    private fun fetchLikedTracks(): List<SpotifyTrack> {
        val out = ArrayList<SpotifyTrack>()
        var offset = 0
        while (true) {
            val page = Spotify.likedSongs(limit = 50, offset = offset).getOrNull() ?: break
            out += page.items.map { it.track }.filter { !it.isLocal }
            if (page.items.size < 50 || offset + page.items.size >= page.total) break
            offset += page.items.size
        }
        return out
    }

    /** Pasa cada canción de Spotify a su equivalente de YouTube y la mete en la biblioteca. */
    private suspend fun resolve(tracks: List<SpotifyTrack>, mapper: SpotifyYouTubeMapper): List<String> {
        val ids = LinkedHashSet<String>()
        for (track in tracks) {
            runCatching {
                val metadata = mapper.mapToYouTube(track) ?: return@runCatching
                val now = LocalDateTime.now()
                database.insert(metadata) { it.copy(inLibrary = it.inLibrary ?: now) }
                database.inLibrary(metadata.id, now)
                ids += metadata.id
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "SpotifyLibrarySync: no se pudo pasar '${track.name}'")
            }
        }
        return ids.toList()
    }

    /**
     * Crea o actualiza la playlist local ligada a [remoteKey]. Devuelve false si el
     * usuario la había borrado (y la marca para no recrearla).
     */
    private suspend fun writePlaylist(
        remoteKey: String,
        name: String,
        thumbnailUrl: String?,
        songIds: List<String>,
        localByName: Map<String, com.dezzy.music.db.entities.Playlist>,
    ): Boolean {
        val linkedId = prefs.getString(remoteKey, null)
        var entity: PlaylistEntity? = null
        if (linkedId != null) {
            entity = database.getPlaylistById(linkedId)?.playlist
            if (entity == null) {
                // La borró el usuario: se respeta.
                prefs.edit().putBoolean(KEY_REMOVED + remoteKey.removePrefix(KEY_PLAYLIST), true).apply()
                return false
            }
        }
        if (entity == null) {
            entity = localByName[name]?.playlist?.takeIf { it.isLocal && it.browseId == null }
        }

        // Nada que hacer si la lista de canciones no ha cambiado.
        val signature = songIds.hashCode()
        if (entity != null && prefs.getInt("$remoteKey:sig", 0) == signature) {
            prefs.edit().putString(remoteKey, entity.id).apply()
            return true
        }

        val target = entity?.copy(name = name, thumbnailUrl = thumbnailUrl ?: entity.thumbnailUrl)
            ?: PlaylistEntity(
                name = name,
                thumbnailUrl = thumbnailUrl,
                isLocal = true,
                // La biblioteca sólo muestra playlists con bookmarkedAt.
                bookmarkedAt = LocalDateTime.now(),
            )
        database.withTransaction {
            if (entity == null) insert(target) else update(target)
            clearPlaylist(target.id)
            songIds.forEachIndexed { position, songId ->
                insert(PlaylistSongMap(songId = songId, playlistId = target.id, position = position))
            }
        }
        prefs.edit()
            .putString(remoteKey, target.id)
            .putInt("$remoteKey:sig", signature)
            .apply()
        return true
    }

    companion object {
        private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val KEY_LAST_SYNC = "lastSync"
        private const val KEY_PLAYLIST = "pl:"
        private const val KEY_REMOVED = "removed:"
        private const val LIKED_ID = "__liked__"
        private const val LIKED_NAME = "Me gusta de Spotify"
    }
}
