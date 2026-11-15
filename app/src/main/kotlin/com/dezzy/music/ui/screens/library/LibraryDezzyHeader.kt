/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.library

import androidx.annotation.DrawableRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dezzy.music.R
import com.dezzy.music.constants.LibraryFilter
import com.dezzy.music.constants.ShowCachedPlaylistKey
import com.dezzy.music.constants.ShowDownloadedPlaylistKey
import com.dezzy.music.constants.ShowLikedPlaylistKey
import com.dezzy.music.constants.ShowTopPlaylistKey
import com.dezzy.music.constants.TopSize
import com.dezzy.music.utils.rememberPreference

/** Pestañas de la Biblioteca en orden (fase 4, boceto B). */
internal val LibraryTabs = listOf(
    LibraryFilter.PLAYLISTS,
    LibraryFilter.ALBUMS,
    LibraryFilter.ARTISTS,
    LibraryFilter.SONGS,
    LibraryFilter.GENRES,
)

private data class LibraryPill(
    val key: String,
    val title: String,
    @DrawableRes val icon: Int,
    val route: String,
    val container: Color,
    val content: Color,
)

/**
 * Cabecera de la Biblioteca (fase 4, boceto B): accesos fijos en píldoras
 * (Me gusta, Descargas, Mi top, En caché; respetan sus ajustes de visibilidad)
 * y pestañas por tipo. Va como primer elemento de la lista de cada pestaña.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryDezzyHeader(
    navController: NavController,
    current: LibraryFilter,
    onSelect: (LibraryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (showLiked) = rememberPreference(ShowLikedPlaylistKey, true)
    val (showDownloaded) = rememberPreference(ShowDownloadedPlaylistKey, true)
    val (showTop) = rememberPreference(ShowTopPlaylistKey, true)
    val (showCached) = rememberPreference(ShowCachedPlaylistKey, true)
    val (topSize) = rememberPreference(TopSize, "50")

    val colors = MaterialTheme.colorScheme
    val pills = buildList {
        if (showLiked) {
            add(LibraryPill("liked", stringResource(R.string.liked), R.drawable.favorite, "auto_playlist/liked", colors.primaryContainer, colors.onPrimaryContainer))
        }
        if (showDownloaded) {
            add(LibraryPill("downloaded", stringResource(R.string.offline), R.drawable.offline, "auto_playlist/downloaded", colors.secondaryContainer, colors.onSecondaryContainer))
        }
        if (showTop) {
            add(LibraryPill("top", stringResource(R.string.my_top) + " $topSize", R.drawable.trending_up, "top_playlist/$topSize", colors.tertiaryContainer, colors.onTertiaryContainer))
        }
        if (showCached) {
            add(LibraryPill("cached", stringResource(R.string.cached_playlist), R.drawable.cached, "cache_playlist/cached", colors.surfaceContainerHighest, colors.onSurfaceVariant))
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (pills.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                pills.forEach { pill ->
                    LibraryPillButton(pill = pill, onClick = { navController.navigate(pill.route) })
                }
            }
        }

        val labels = mapOf(
            LibraryFilter.PLAYLISTS to stringResource(R.string.filter_playlists),
            LibraryFilter.ALBUMS to stringResource(R.string.filter_albums),
            LibraryFilter.ARTISTS to stringResource(R.string.filter_artists),
            LibraryFilter.SONGS to stringResource(R.string.filter_songs),
            LibraryFilter.GENRES to "Géneros",
        )
        PrimaryScrollableTabRow(
            selectedTabIndex = LibraryTabs.indexOf(current).coerceAtLeast(0),
            edgePadding = 16.dp,
            containerColor = Color.Transparent,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            LibraryTabs.forEach { tab ->
                Tab(
                    selected = tab == current,
                    onClick = { if (tab != current) onSelect(tab) },
                    text = {
                        Text(
                            text = labels.getValue(tab),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryPillButton(
    pill: LibraryPill,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 40.dp)
                .padding(start = 6.dp, end = 16.dp),
        ) {
            Surface(shape = CircleShape, color = pill.container, modifier = Modifier.size(28.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(pill.icon),
                        contentDescription = null,
                        tint = pill.content,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = pill.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}
