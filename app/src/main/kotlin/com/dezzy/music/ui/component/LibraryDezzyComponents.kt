/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dezzy.music.R
import com.dezzy.music.db.entities.Playlist
import com.dezzy.music.db.entities.PlaylistEntity
import com.dezzy.music.innertube.models.PlaylistItem
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.ui.menu.PlaylistMenu
import com.dezzy.music.ui.menu.YouTubePlaylistMenu
import kotlinx.coroutines.CoroutineScope

/**
 * Celda de playlist para la cuadrícula de la Biblioteca (fase 4, boceto B).
 * Abre y muestra el menú igual que [LibraryPlaylistListItem]; el menú también
 * está en el botón ⋮ como alternativa a la pulsación larga.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryPlaylistGridItem(
    navController: NavController,
    menuState: MenuState,
    coroutineScope: CoroutineScope,
    playlist: Playlist,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    val showMenu: () -> Unit = {
        menuState.show {
            if (playlist.playlist.isEditable || playlist.songCount != 0) {
                PlaylistMenu(
                    playlist = playlist,
                    coroutineScope = coroutineScope,
                    onDismiss = menuState::dismiss
                )
            } else {
                playlist.playlist.browseId?.let { browseId ->
                    YouTubePlaylistMenu(
                        playlist = PlaylistItem(
                            id = browseId,
                            title = playlist.playlist.name,
                            author = null,
                            songCountText = null,
                            thumbnail = playlist.thumbnails.getOrNull(0) ?: "",
                            playEndpoint = WatchEndpoint(
                                playlistId = browseId,
                                params = playlist.playlist.playEndpointParams
                            ),
                            shuffleEndpoint = WatchEndpoint(
                                playlistId = browseId,
                                params = playlist.playlist.shuffleEndpointParams
                            ),
                            radioEndpoint = WatchEndpoint(
                                playlistId = "RDAMPL$browseId",
                                params = playlist.playlist.radioEndpointParams
                            ),
                            isEditable = false
                        ),
                        coroutineScope = coroutineScope,
                        onDismiss = menuState::dismiss
                    )
                }
            }
        }
    }

    val openPlaylist: () -> Unit = {
        if (playlist.id == PlaylistEntity.LIKED_PLAYLIST_ID) {
            navController.navigate("auto_playlist/liked")
        } else if (
            !playlist.playlist.isEditable &&
            playlist.songCount == 0 &&
            playlist.playlist.remoteSongCount != 0
        ) {
            navController.navigate("online_playlist/${playlist.playlist.browseId}")
        } else {
            navController.navigate("local_playlist/${playlist.id}")
        }
    }

    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .clip(shape)
            .combinedClickable(
                onClick = openPlaylist,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showMenu()
                },
            ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            val side = maxWidth
            PlaylistThumbnail(
                thumbnails = playlist.thumbnails,
                size = side,
                placeHolder = {
                    Icon(
                        painter = painterResource(playlistPlaceholderIcon(playlist, false)),
                        contentDescription = null,
                        tint = LocalContentColor.current.copy(alpha = 0.8f),
                        modifier = Modifier.size(side / 3),
                    )
                },
                shape = shape,
            )
        }
        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 8.dp, bottom = 4.dp),
            ) {
                Text(
                    text = playlist.playlist.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = playlistCountText(playlist = playlist, autoPlaylist = false),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = showMenu) {
                Icon(
                    painter = painterResource(R.drawable.more_vert),
                    contentDescription = null,
                )
            }
        }
    }
}
