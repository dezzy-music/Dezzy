/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.dezzy.music.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dezzy.music.LocalDatabase
import com.dezzy.music.constants.ChipSortTypeKey
import com.dezzy.music.constants.LibraryFilter
import com.dezzy.music.constants.PlaylistTagsFilterKey
import com.dezzy.music.constants.ShowTagsInLibraryKey
import com.dezzy.music.ui.component.TagsFilterChips
import com.dezzy.music.utils.rememberEnumPreference
import com.dezzy.music.utils.rememberPreference

/**
 * Pestaña Biblioteca (fase 4, boceto B): accesos en píldoras, pestañas por tipo
 * y contenido en cuadrícula o lista. La antigua vista mezclada
 * ([LibraryFilter.LIBRARY]) abre ahora la pestaña Playlists.
 */
@Composable
fun LibraryScreen(navController: NavController) {
    var filterType by rememberEnumPreference(ChipSortTypeKey, LibraryFilter.LIBRARY)
    val currentTab = if (filterType == LibraryFilter.LIBRARY) LibraryFilter.PLAYLISTS else filterType

    val database = LocalDatabase.current
    val (showTagsInLibrary) = rememberPreference(ShowTagsInLibraryKey, true)
    val (selectedTagsFilter, onSelectedTagsFilterChange) = rememberPreference(PlaylistTagsFilterKey, "")
    val selectedTagIds = remember(selectedTagsFilter) {
        selectedTagsFilter.split(",").filter { it.isNotBlank() }.toSet()
    }

    val libraryHeader = @Composable {
        LibraryDezzyHeader(
            navController = navController,
            current = currentTab,
            onSelect = { filterType = it },
        )
    }

    // Las etiquetas filtran playlists: sólo se muestran en esa pestaña.
    val playlistsHeader = @Composable {
        Column {
            libraryHeader()
            if (showTagsInLibrary) {
                TagsFilterChips(
                    database = database,
                    selectedTags = selectedTagIds,
                    onTagToggle = { tag ->
                        val newTags = if (tag.id in selectedTagIds) {
                            selectedTagIds - tag.id
                        } else {
                            selectedTagIds + tag.id
                        }
                        onSelectedTagsFilterChange(newTags.joinToString(","))
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        when (currentTab) {
            LibraryFilter.LIBRARY,
            LibraryFilter.PLAYLISTS -> LibraryPlaylistsScreen(navController, playlistsHeader)

            LibraryFilter.SONGS -> LibrarySongsScreen(navController, libraryHeader)
            LibraryFilter.ALBUMS -> LibraryAlbumsScreen(navController, libraryHeader)
            LibraryFilter.GENRES -> LibraryGenresScreen(navController, libraryHeader)
            LibraryFilter.ARTISTS -> LibraryArtistsScreen(navController, libraryHeader)
        }
    }
}
