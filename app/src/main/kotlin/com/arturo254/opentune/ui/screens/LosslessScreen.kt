/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.arturo254.opentune.LocalPlayerAwareWindowInsets
import com.arturo254.opentune.LocalPlayerConnection
import com.arturo254.opentune.R
import com.arturo254.opentune.lossless.DefaultLosslessResolverUrl
import com.arturo254.opentune.lossless.LosslessResolverUrlKey
import com.arturo254.opentune.lossless.LosslessDownloadManager
import com.arturo254.opentune.lossless.LosslessDownloadState
import com.arturo254.opentune.lossless.LosslessProvider
import com.arturo254.opentune.lossless.LosslessQuality
import com.arturo254.opentune.lossless.LosslessTrack
import com.arturo254.opentune.models.MediaMetadata
import com.arturo254.opentune.playback.FLAC_STREAM_PREFIX
import com.arturo254.opentune.playback.PlayerConnection
import com.arturo254.opentune.ui.component.IconButton
import com.arturo254.opentune.ui.utils.backToMain
import com.arturo254.opentune.utils.rememberPreference
import com.arturo254.opentune.viewmodels.LosslessViewModel

/**
 * Pantalla de búsqueda y descarga lossless (estilo SpotiFLAC). Busca una pista,
 * elige proveedor + formato, y la descarga se guarda como archivo local que
 * aparece en los "me gusta". Ver [com.arturo254.opentune.lossless.LosslessDownloadManager].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LosslessScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    initialQuery: String = "",
    viewModel: LosslessViewModel = hiltViewModel(),
) {
    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank() && viewModel.query.value != initialQuery) {
            viewModel.onQueryChange(initialQuery)
            viewModel.search()
        }
    }

    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val (resolverUrl) = rememberPreference(LosslessResolverUrlKey, DefaultLosslessResolverUrl)
    val playerConnection = LocalPlayerConnection.current
    val playScope = rememberCoroutineScope()
    val playContext = LocalContext.current

    var dialogTrack by remember { mutableStateOf<LosslessTrack?>(null) }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            )
            .fillMaxSize(),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)),
        )

        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            label = { Text("Buscar canción, artista o álbum") },
            trailingIcon = {
                IconButton(onClick = viewModel::search, onLongClick = {}) {
                    Icon(painterResource(R.drawable.search), contentDescription = "Buscar")
                }
            },
        )

        if (resolverUrl.isBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { navController.navigate("settings/lossless") },
            ) {
                Text(
                    text = "Configura la URL del resolver para poder descargar. Toca aquí.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Box(Modifier.fillMaxSize()) {
            if (isSearching) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(results, key = { it.provider.id + it.providerTrackId }) { track ->
                    val state = downloads.entries.firstOrNull { it.key.substringAfterLast(":") == track.providerTrackId }?.value
                    LosslessResultRow(
                        track = track,
                        state = state,
                        onDownloadClick = { dialogTrack = track },
                        onPlayClick = {
                            if (playerConnection != null) {
                                playLosslessStream(playScope, playContext, playerConnection, viewModel.downloadManager, track)
                            }
                        },
                    )
                }
            }
        }
    }

    TopAppBar(
        title = { Text("Descarga lossless") },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
        actions = {
            IconButton(
                onClick = { navController.navigate("lossless/downloads") },
                onLongClick = {},
            ) {
                Icon(painterResource(R.drawable.downloading), contentDescription = "Cola de descargas")
            }
        },
        scrollBehavior = scrollBehavior,
    )

    dialogTrack?.let { track ->
        DownloadOptionsDialog(
            track = track,
            onDismiss = { dialogTrack = null },
            onConfirm = { provider, quality ->
                viewModel.download(track.copy(provider = provider), quality)
                dialogTrack = null
            },
        )
    }
}

/**
 * Reproduce una pista de la búsqueda lossless en FLAC (Deezer, por defecto)
 * haciendo streaming desde el resolver — rápido, sin descargar el archivo.
 */
private fun playLosslessStream(
    scope: CoroutineScope,
    context: Context,
    playerConnection: PlayerConnection,
    downloadManager: LosslessDownloadManager,
    track: LosslessTrack,
) {
    scope.launch {
        Toast.makeText(context, "Reproduciendo en FLAC…", Toast.LENGTH_SHORT).show()
        val dzr = track.copy(provider = LosslessProvider.DEEZER)
        val quality = LosslessProvider.DEEZER.qualities.first() // FLAC
        val url = downloadManager.resolveStreamUrl(dzr, quality)
        if (url.isNullOrBlank()) {
            Toast.makeText(context, "No se pudo reproducir esta canción", Toast.LENGTH_SHORT).show()
            return@launch
        }
        val metadata = MediaMetadata(
            id = FLAC_STREAM_PREFIX + track.providerTrackId,
            title = track.title,
            artists = track.artistNames.ifEmpty { listOf("Desconocido") }
                .map { MediaMetadata.Artist(id = null, name = it) },
            duration = track.durationSec,
            thumbnailUrl = track.thumbnailUrl,
            album = null,
        )
        playerConnection.playLosslessStream(metadata, url)
    }
}

@Composable
private fun LosslessResultRow(
    track: LosslessTrack,
    state: LosslessDownloadState?,
    onDownloadClick: () -> Unit,
    onPlayClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlayClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                track.artistLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state?.phase) {
                LosslessDownloadState.Phase.QUEUED ->
                    Text("En cola…", style = MaterialTheme.typography.labelSmall)
                LosslessDownloadState.Phase.RESOLVING ->
                    Text("Resolviendo…", style = MaterialTheme.typography.labelSmall)
                LosslessDownloadState.Phase.DOWNLOADING ->
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                LosslessDownloadState.Phase.TAGGING ->
                    Text("Guardando…", style = MaterialTheme.typography.labelSmall)
                LosslessDownloadState.Phase.DONE ->
                    Text("Descargado ✓", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                LosslessDownloadState.Phase.FAILED ->
                    Text(state.error ?: "Error", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                null -> Unit
            }
        }
        val busy = state?.phase == LosslessDownloadState.Phase.QUEUED ||
            state?.phase == LosslessDownloadState.Phase.RESOLVING ||
            state?.phase == LosslessDownloadState.Phase.DOWNLOADING ||
            state?.phase == LosslessDownloadState.Phase.TAGGING
        if (!busy) {
            IconButton(onClick = onDownloadClick, onLongClick = {}) {
                Icon(painterResource(R.drawable.download), contentDescription = "Descargar")
            }
        } else {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun DownloadOptionsDialog(
    track: LosslessTrack,
    onDismiss: () -> Unit,
    onConfirm: (LosslessProvider, LosslessQuality) -> Unit,
) {
    var provider by remember { mutableStateOf(track.provider) }
    var quality by remember { mutableStateOf(provider.qualities.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = track.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artistLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        text = {
            Column {
                Text(
                    "Proveedor",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LosslessProvider.entries.forEach { p ->
                        FilterChip(
                            selected = provider == p,
                            onClick = { provider = p; quality = p.qualities.first() },
                            label = { Text(p.displayName, maxLines = 1) },
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                Text(
                    "Calidad",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    provider.qualities.forEach { q ->
                        val lossless = q.container.equals("flac", ignoreCase = true)
                        FilterChip(
                            selected = quality == q,
                            onClick = { quality = q },
                            label = { Text(q.label, maxLines = 1) },
                            leadingIcon = if (lossless) {
                                {
                                    Icon(
                                        painter = painterResource(R.drawable.graphic_eq),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else null,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (quality.container.equals("flac", ignoreCase = true))
                        "Se descargará en Hi-Fi lossless (FLAC)."
                    else "Se descargará en MP3 320 kbps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(provider, quality) }) {
                Text("Descargar", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

/**
 * Modo de búsqueda LOSSLESS embebido en la barra de búsqueda principal (junto a
 * YouTube y Local). Reacciona a la [query] tecleada (con debounce) y reusa la
 * misma lógica y filas que [LosslessScreen]. Solo se muestra si la función está
 * activada (lo decide quien lo invoca, en MainActivity).
 */
@Composable
fun LosslessSearchScreen(
    query: String,
    navController: NavController,
    onDismiss: () -> Unit,
    pureBlack: Boolean = false,
    viewModel: LosslessViewModel = hiltViewModel(),
) {
    val results by viewModel.results.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Debounce: buscamos ~450ms después de que el usuario deja de teclear.
    LaunchedEffect(query) {
        val q = query.trim()
        viewModel.onQueryChange(q)
        if (q.length >= 2) {
            delay(450)
            viewModel.search()
        }
    }

    var dialogTrack by remember { mutableStateOf<LosslessTrack?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal),
            ),
    ) {
        if (isSearching) {
            CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 16.dp))
        }
        if (results.isEmpty() && !isSearching && query.trim().length >= 2) {
            Text(
                text = "Sin resultados lossless para «$query»",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopCenter).padding(24.dp),
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(results, key = { it.provider.id + it.providerTrackId }) { track ->
                val state = downloads.entries.firstOrNull { it.key.substringAfterLast(":") == track.providerTrackId }?.value
                LosslessResultRow(
                    track = track,
                    state = state,
                    onDownloadClick = { dialogTrack = track },
                    onPlayClick = {
                        if (playerConnection != null) {
                            playLosslessStream(scope, context, playerConnection, viewModel.downloadManager, track)
                        }
                    },
                )
            }
        }
    }

    dialogTrack?.let { track ->
        DownloadOptionsDialog(
            track = track,
            onDismiss = { dialogTrack = null },
            onConfirm = { provider, quality ->
                viewModel.download(track.copy(provider = provider), quality)
                dialogTrack = null
            },
        )
    }
}
