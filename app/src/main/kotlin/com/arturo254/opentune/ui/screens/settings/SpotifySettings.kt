/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.ui.screens.settings

import android.webkit.CookieManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.room.withTransaction
import com.arturo254.opentune.LocalDatabase
import com.arturo254.opentune.LocalPlayerAwareWindowInsets
import com.arturo254.opentune.R
import com.arturo254.opentune.constants.EnableSpotifyKey
import com.arturo254.opentune.constants.SpotifyAccessTokenKey
import com.arturo254.opentune.constants.SpotifySpDcKey
import com.arturo254.opentune.constants.SpotifySpKeyKey
import com.arturo254.opentune.constants.SpotifySyncLikesKey
import com.arturo254.opentune.constants.SpotifyTokenExpiryKey
import com.arturo254.opentune.constants.SpotifyUserIdKey
import com.arturo254.opentune.constants.SpotifyUsernameKey
import com.arturo254.opentune.constants.SpotifyHomeOnlyKey
import com.arturo254.opentune.constants.UseSpotifyHomeKey
import com.arturo254.opentune.constants.UseSpotifySearchKey
import com.arturo254.opentune.models.MediaMetadata
import com.arturo254.opentune.spotify.SpotifyYouTubeMapper
import com.arturo254.opentune.ui.component.IconButton
import com.arturo254.opentune.ui.component.PreferenceEntry
import com.arturo254.opentune.ui.component.PreferenceGroupTitle
import com.arturo254.opentune.ui.component.SwitchPreference
import com.arturo254.opentune.ui.utils.backToMain
import com.arturo254.opentune.utils.rememberPreference
import com.arturo254.opentune.spotify.Spotify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifySettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (spotifyUsername, setSpotifyUsername) = rememberPreference(SpotifyUsernameKey, "")
    val (spotifyAccessToken, setSpotifyAccessToken) = rememberPreference(SpotifyAccessTokenKey, "")
    val (spotifySpDc, setSpotifySpDc) = rememberPreference(SpotifySpDcKey, "")
    val (spotifySpKey, setSpotifySpKey) = rememberPreference(SpotifySpKeyKey, "")
    val (spotifyTokenExpiry, setSpotifyTokenExpiry) = rememberPreference(SpotifyTokenExpiryKey, 0L)
    val (spotifyUserId, setSpotifyUserId) = rememberPreference(SpotifyUserIdKey, "")

    val (enableSpotify, onEnableSpotifyChange) = rememberPreference(
        key = EnableSpotifyKey,
        defaultValue = false,
    )

    val isLoggedIn = remember(spotifyAccessToken, spotifySpDc) {
        spotifyAccessToken.isNotEmpty() && spotifySpDc.isNotEmpty()
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            )
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.account),
        )

        PreferenceEntry(
            title = {
                Text(
                    text = if (isLoggedIn) spotifyUsername else "No has iniciado sesión",
                    modifier = Modifier.alpha(if (isLoggedIn) 1f else 0.5f),
                )
            },
            description = null,
            icon = { Icon(painterResource(R.drawable.ic_music), null) },
            trailingContent = {
                if (isLoggedIn) {
                    OutlinedButton(onClick = {
                        setSpotifyAccessToken("")
                        setSpotifySpDc("")
                        setSpotifySpKey("")
                        setSpotifyTokenExpiry(0L)
                        setSpotifyUsername("")
                        setSpotifyUserId("")
                        onEnableSpotifyChange(false)
                        CookieManager.getInstance().removeAllCookies(null)
                    }) {
                        Text(stringResource(R.string.action_logout))
                    }
                } else {
                    OutlinedButton(
                        onClick = { navController.navigate("settings/spotify/login") },
                    ) {
                        Text(stringResource(R.string.action_login))
                    }
                }
            },
        )

        PreferenceGroupTitle(
            title = "Opciones",
        )

        SwitchPreference(
            title = { Text("Habilitar Spotify") },
            description = "Sincroniza tus gustos y recomendaciones",
            checked = enableSpotify,
            onCheckedChange = onEnableSpotifyChange,
            isEnabled = isLoggedIn,
        )

        if (isLoggedIn && enableSpotify) {
            val (useSpotifySearch, onUseSpotifySearchChange) = rememberPreference(
                key = UseSpotifySearchKey,
                defaultValue = false,
            )
            val (useSpotifyHome, onUseSpotifyHomeChange) = rememberPreference(
                key = UseSpotifyHomeKey,
                defaultValue = false,
            )
            val (spotifyHomeOnly, onSpotifyHomeOnlyChange) = rememberPreference(
                key = SpotifyHomeOnlyKey,
                defaultValue = false,
            )

            SwitchPreference(
                title = { Text("Usar para búsquedas") },
                description = "Habilitar búsqueda mediante la base de datos de Spotify",
                checked = useSpotifySearch,
                onCheckedChange = onUseSpotifySearchChange,
            )

            SwitchPreference(
                title = { Text("Usar para la pantalla de inicio") },
                description = "Mostrar recomendaciones basadas en Spotify",
                checked = useSpotifyHome,
                onCheckedChange = onUseSpotifyHomeChange,
            )

            if (useSpotifyHome) {
                SwitchPreference(
                    title = { Text("Exclusivo de inicio") },
                    description = "Mostrar solo recomendaciones de Spotify",
                    checked = spotifyHomeOnly,
                    onCheckedChange = onSpotifyHomeOnlyChange,
                )
            }

            val (syncLikes, onSyncLikesChange) = rememberPreference(
                key = SpotifySyncLikesKey,
                defaultValue = false,
            )

            val context = LocalContext.current
            val database = LocalDatabase.current
            val coroutineScope = rememberCoroutineScope()
            val syncProgress by SpotifyLikeSyncState.progress.collectAsState()
            val isSyncing by SpotifyLikeSyncState.isSyncing.collectAsState()

            SwitchPreference(
                title = { Text("Sincronizar Me Gusta") },
                description = "Sincronizar tus pistas favoritas de Spotify automáticamente",
                checked = syncLikes,
                onCheckedChange = { enabled ->
                    onSyncLikesChange(enabled)
                    if (enabled) {
                        coroutineScope.launch {
                            runInitialSpotifyLikeSync(
                                database = database,
                                context = context,
                                onComplete = { count ->
                                    Toast.makeText(
                                        context,
                                        "Sincronización completada: $count canciones",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                onError = {
                                    Toast.makeText(
                                        context,
                                        "Error al sincronizar",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                    }
                },
                isEnabled = !isSyncing,
            )

            if (isSyncing) {
                PreferenceEntry(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(
                                text = "Sincronizando: ${syncProgress.first} / ${syncProgress.second}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    description = null,
                    icon = {},
                )
            }

            PreferenceGroupTitle(
                title = "Precargar canciones",
            )

            PreferenceEntry(
                title = { Text("Precargar canciones") },
                description = "Precarga en caché tus pistas de Spotify en la base de datos",
                icon = {
                    Icon(
                        painterResource(R.drawable.ic_music),
                        contentDescription = null,
                    )
                },
                trailingContent = {
                    OutlinedButton(
                        onClick = { navController.navigate("settings/spotify/preload") },
                    ) {
                        Text("Iniciar precarga")
                    }
                },
            )

            PreferenceGroupTitle(
                title = "Información",
            )

            PreferenceEntry(
                title = {
                    Text(
                        text = "Tus pistas de Spotify se mapean con la mejor coincidencia en YouTube Music.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                description = null,
                icon = {
                    Icon(
                        painterResource(R.drawable.info),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }

    TopAppBar(
        title = { Text("Integración de Spotify") },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}

/**
 * Holds observable state for the initial Spotify→local like sync so the
 * settings UI can show a progress indicator.
 */
internal object SpotifyLikeSyncState {
    /** (current, total) */
    val progress = MutableStateFlow(0 to 0)
    val isSyncing = MutableStateFlow(false)
}

/**
 * Pulls all liked songs from Spotify, resolves each to a YouTube equivalent
 * via [SpotifyYouTubeMapper], and marks them as liked in Room.
 */
internal suspend fun runInitialSpotifyLikeSync(
    database: com.arturo254.opentune.db.MusicDatabase,
    context: android.content.Context,
    onComplete: (Int) -> Unit,
    onError: (Throwable) -> Unit,
) {
    if (SpotifyLikeSyncState.isSyncing.value) return
    SpotifyLikeSyncState.isSyncing.value = true
    SpotifyLikeSyncState.progress.value = 0 to 0

    try {
        withContext(Dispatchers.IO) {
            val mapper = SpotifyYouTubeMapper(database, context)
            val allTracks = mutableListOf<com.arturo254.opentune.spotify.SpotifyTrack>()

            // Paginated fetch of all Spotify liked songs
            var offset = 0
            val pageSize = 50
            while (true) {
                val page = Spotify.likedSongs(limit = pageSize, offset = offset).getOrThrow()
                allTracks.addAll(page.items.mapNotNull { it.track })
                SpotifyLikeSyncState.progress.value = allTracks.size to page.total
                if (allTracks.size >= page.total || page.items.isEmpty()) break
                offset += pageSize
            }

            Timber.d("SpotifyLikeSync: fetched ${allTracks.size} liked songs from Spotify")

            var synced = 0

            data class ResolvedTrack(
                val metadata: MediaMetadata,
                val index: Int,
            )

            val resolved = mutableListOf<ResolvedTrack>()
            allTracks.forEachIndexed { index, spotifyTrack ->
                try {
                    val metadata = mapper.mapToYouTube(spotifyTrack)
                    if (metadata != null) {
                        resolved.add(ResolvedTrack(metadata, index))
                    }
                    SpotifyLikeSyncState.progress.value = (index + 1) to allTracks.size
                } catch (e: Exception) {
                    Timber.w(e, "SpotifyLikeSync: failed to resolve ${spotifyTrack.id}")
                }
            }

            Timber.d("SpotifyLikeSync: resolved ${resolved.size}/${allTracks.size} tracks, writing to DB")

            database.withTransaction {
                resolved.forEach { (metadata, index) ->
                    try {
                        val existingSong = song(metadata.id).firstOrNull()
                        if (existingSong == null) {
                            insert(metadata) {
                                it.copy(liked = true, likedDate = java.time.LocalDateTime.now().minusSeconds(index * 10L))
                            }
                        } else if (!existingSong.song.liked) {
                            update(existingSong.song.copy(liked = true, likedDate = java.time.LocalDateTime.now().minusSeconds(index * 10L)))
                        }
                        synced++
                    } catch (e: Exception) {
                        Timber.w(e, "SpotifyLikeSync: failed to insert ${metadata.id}")
                    }
                }
            }

            Timber.d("SpotifyLikeSync: synced $synced/${allTracks.size} songs to local DB")
            withContext(Dispatchers.Main) { onComplete(synced) }
        }
    } catch (e: Exception) {
        Timber.e(e, "SpotifyLikeSync: initial sync failed")
        withContext(Dispatchers.Main) { onError(e) }
    } finally {
        SpotifyLikeSyncState.isSyncing.value = false
    }
}
