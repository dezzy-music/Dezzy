/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.arturo254.opentune.LocalPlayerAwareWindowInsets
import com.arturo254.opentune.R
import com.arturo254.opentune.constants.SpotifyPreloadDownloadKey
import com.arturo254.opentune.constants.SpotifyPreloadLikedAsPlaylistKey
import com.arturo254.opentune.utils.rememberPreference
import com.arturo254.opentune.ui.component.IconButton
import com.arturo254.opentune.ui.utils.backToMain
import com.arturo254.opentune.viewmodels.SelectablePlaylist
import com.arturo254.opentune.viewmodels.SpotifyPreloadState
import com.arturo254.opentune.viewmodels.SpotifyPreloadViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyPreloadScreen(
    navController: NavController,
    viewModel: SpotifyPreloadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val (downloadOffline, onDownloadOfflineChange) = rememberPreference(
        SpotifyPreloadDownloadKey,
        defaultValue = false,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Precargar canciones de Spotify") },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )

        // The selection step needs the full height (scrollable list), so it renders
        // outside the centered Box the other states use.
        if (state is SpotifyPreloadState.SelectingPlaylists) {
            PlaylistSelectionContent(
                playlists = (state as SpotifyPreloadState.SelectingPlaylists).playlists,
                viewModel = viewModel,
                downloadOffline = downloadOffline,
                onDownloadOfflineChange = onDownloadOfflineChange,
            )
            return@Column
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current),
            ) {
                when (val s = state) {
                    is SpotifyPreloadState.Idle -> {
                        Text(
                            "Busca tus playlists y canciones que te gustan en Spotify, " +
                                "elige cuáles quieres precargar y guarda en caché su equivalente " +
                                "en YouTube Music para que se reproduzcan al instante más tarde.",
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Descargar para escuchar sin conexión",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "Descarga el audio de cada canción (usa más datos y almacenamiento)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = downloadOffline,
                                onCheckedChange = onDownloadOfflineChange,
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { viewModel.loadPlaylists() }) {
                            Text("Elegir qué precargar")
                        }
                    }

                    is SpotifyPreloadState.SelectingPlaylists -> Unit // handled above

                    is SpotifyPreloadState.FetchingPlaylists -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Buscando tus playlists y canciones…")
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(onClick = { viewModel.cancel() }) { Text("Cancelar") }
                    }

                    is SpotifyPreloadState.Counting -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("${s.total} canciones encontradas, preparando…")
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(onClick = { viewModel.cancel() }) { Text("Cancelar") }
                    }

                    is SpotifyPreloadState.Converting -> {
                        val progress = if (s.total > 0) s.current.toFloat() / s.total else 0f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("${s.current} / ${s.total} canciones")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Puedes salir de esta pantalla: la precarga sigue en " +
                                "segundo plano y verás el progreso en la notificación.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(onClick = { viewModel.cancel() }) { Text("Cancelar") }
                    }

                    is SpotifyPreloadState.Completed -> {
                        Icon(
                            painterResource(R.drawable.check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Precarga completada")
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(onClick = { viewModel.resetToIdle() }) { Text("Volver a empezar") }
                    }

                    is SpotifyPreloadState.Cancelled -> {
                        Text("Precarga cancelada")
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { viewModel.resetToIdle() }) { Text("Reintentar") }
                    }

                    is SpotifyPreloadState.Error -> {
                        Text(
                            "Error: ${s.message}",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { viewModel.resetToIdle() }) { Text("Reintentar") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistSelectionContent(
    playlists: List<SelectablePlaylist>,
    viewModel: SpotifyPreloadViewModel,
    downloadOffline: Boolean,
    onDownloadOfflineChange: (Boolean) -> Unit,
) {
    val selectedIds by viewModel.selectedIds.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current),
    ) {
        Text(
            "Elige qué quieres precargar",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { viewModel.selectAll() }) {
                Icon(
                    painterResource(R.drawable.select_all),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Todo")
            }
            TextButton(onClick = { viewModel.selectNone() }) {
                Icon(
                    painterResource(R.drawable.deselect),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Nada")
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${selectedIds.size} de ${playlists.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(playlists, key = { it.id }) { playlist ->
                val checked = playlist.id in selectedIds
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleSelection(playlist.id) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { viewModel.toggleSelection(playlist.id) },
                    )
                    Spacer(Modifier.width(4.dp))
                    if (playlist.isLikedSongs) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                        ) {
                            Icon(
                                painterResource(R.drawable.favorite),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    } else if (playlist.thumbnailUrl != null) {
                        AsyncImage(
                            model = playlist.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Icon(
                                painterResource(R.drawable.music_note),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        playlist.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Descargar para escuchar sin conexión",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Descarga el audio de cada canción seleccionada",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = downloadOffline,
                    onCheckedChange = onDownloadOfflineChange,
                )
            }

            // Destino de "Tus me gusta de Spotify" — solo aplica si esa entrada está marcada.
            if (SpotifyPreloadViewModel.LIKED_SONGS_ID in selectedIds) {
                val (likedAsPlaylist, onLikedAsPlaylistChange) = rememberPreference(
                    SpotifyPreloadLikedAsPlaylistKey,
                    defaultValue = true,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "¿Dónde guardar tus me gusta de Spotify?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = likedAsPlaylist,
                        onClick = { onLikedAsPlaylistChange(true) },
                        label = { Text("Solo playlist \"Me gusta de Spotify\"") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = !likedAsPlaylist,
                        onClick = { onLikedAsPlaylistChange(false) },
                        label = { Text("También en Me gusta de la app") },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    if (likedAsPlaylist) {
                        "Se crea una playlist aparte; tus me gusta de la app (YouTube Music) quedan intactos, sin duplicados."
                    } else {
                        "Además de la playlist, cada canción se marca como Me gusta en la app (se mezclan con los de YouTube Music)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { viewModel.resetToIdle() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancelar")
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { viewModel.startPreload() },
                    enabled = selectedIds.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (selectedIds.isEmpty()) "Precargar" else "Precargar (${selectedIds.size})")
                }
            }
        }
    }
}
