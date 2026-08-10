/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.constants.CONTENT_TYPE_HEADER
import com.dezzy.music.constants.CONTENT_TYPE_SONG
import com.dezzy.music.extensions.toMediaItem
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.playback.queues.ListQueue
import com.dezzy.music.ui.component.LocalMenuState
import com.dezzy.music.ui.component.SongListItem
import com.dezzy.music.ui.menu.SongMenu
import com.dezzy.music.utils.GenreClassifier
import com.dezzy.music.viewmodels.LibraryGenresViewModel

/**
 * Organiza la biblioteca por géneros (reguetón, bachata, …) clasificados con
 * heurística local + keywords de InnerTube. Se accede desde el chip "Géneros"
 * de la biblioteca.
 */
@Composable
fun LibraryGenresScreen(
    navController: NavController,
    filterContent: @Composable () -> Unit,
    viewModel: LibraryGenresViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val songs by viewModel.songs.collectAsState()
    val genreMap by viewModel.genreMap.collectAsState()
    val classifying by viewModel.classifying.collectAsState()
    val progress by viewModel.progress.collectAsState()

    var selectedGenre by remember { mutableStateOf<String?>(null) }

    val grouped = remember(songs, genreMap) {
        songs.filter { genreMap.containsKey(it.id) }.groupBy { genreMap.getValue(it.id) }
    }
    val genreCounts = remember(grouped) {
        grouped.map { it.key to it.value.size }.sortedByDescending { it.second }
    }
    val pendingCount = remember(songs, genreMap) {
        songs.count { it.id !in genreMap }
    }
    val shownSongs = remember(grouped, selectedGenre) {
        selectedGenre?.let { grouped[it].orEmpty() } ?: emptyList()
    }

    LazyColumn(
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "filter", contentType = CONTENT_TYPE_HEADER) {
            filterContent()
        }

        // ── Estado de clasificación ──
        item(key = "classify_state") {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                when {
                    classifying -> {
                        val (done, total) = progress
                        Text(
                            text = if (total > 0) {
                                "Clasificando con YouTube Music… $done/$total"
                            } else {
                                "Clasificando biblioteca…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { if (total > 0) done.toFloat() / total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    pendingCount > 0 -> {
                        OutlinedButton(onClick = viewModel::classifyLibrary) {
                            Text("Clasificar $pendingCount canciones por género")
                        }
                    }

                    songs.isNotEmpty() -> {
                        TextButton(onClick = viewModel::reclassify) {
                            Text("Reclasificar biblioteca")
                        }
                    }

                    else -> {
                        Text(
                            text = "Tu biblioteca está vacía.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        // ── Chips de géneros con conteo ──
        if (genreCounts.isNotEmpty()) {
            item(key = "genre_chips") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(genreCounts, key = { it.first }) { (genre, count) ->
                        FilterChip(
                            selected = selectedGenre == genre,
                            onClick = {
                                selectedGenre = if (selectedGenre == genre) null else genre
                            },
                            label = { Text("${GenreClassifier.displayName(genre)} ($count)") },
                        )
                    }
                }
            }
        }

        if (selectedGenre == null && genreCounts.isNotEmpty()) {
            item(key = "hint") {
                Text(
                    text = "Elige un género para ver sus canciones.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        // ── Canciones del género seleccionado ──
        if (shownSongs.isNotEmpty()) {
            item(key = "genre_actions") {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = {
                        playerConnection.playQueue(
                            ListQueue(
                                title = GenreClassifier.displayName(selectedGenre.orEmpty()),
                                items = shownSongs.map { it.toMediaItem() },
                            ),
                        )
                    }) {
                        Text("Reproducir todo")
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    OutlinedButton(onClick = {
                        playerConnection.playQueue(
                            ListQueue(
                                title = GenreClassifier.displayName(selectedGenre.orEmpty()),
                                items = shownSongs.shuffled().map { it.toMediaItem() },
                            ),
                        )
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.shuffle),
                            contentDescription = null,
                            modifier = Modifier.width(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Aleatorio")
                    }
                }
            }

            itemsIndexed(
                items = shownSongs,
                key = { _, song -> song.id },
                contentType = { _, _ -> CONTENT_TYPE_SONG },
            ) { index, song ->
                SongListItem(
                    song = song,
                    showInLibraryIcon = true,
                    isActive = song.id == mediaMetadata?.id,
                    isPlaying = isPlaying,
                    trailingContent = {
                        IconButton(
                            onClick = {
                                menuState.show {
                                    SongMenu(
                                        originalSong = song,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.more_vert),
                                contentDescription = null,
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (song.id == mediaMetadata?.id) {
                                playerConnection.player.togglePlayPause()
                            } else {
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = GenreClassifier.displayName(selectedGenre.orEmpty()),
                                        items = shownSongs.map { it.toMediaItem() },
                                        startIndex = index,
                                    ),
                                )
                            }
                        },
                )
            }
        }
    }
}
