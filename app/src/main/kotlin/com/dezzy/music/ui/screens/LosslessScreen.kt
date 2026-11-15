/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.lossless.DefaultLosslessResolverUrl
import com.dezzy.music.lossless.LosslessAlbum
import com.dezzy.music.lossless.LosslessDefaultProviderKey
import com.dezzy.music.lossless.LosslessDefaultQualityKey
import com.dezzy.music.lossless.LosslessDownloadManager
import com.dezzy.music.lossless.LosslessDownloadState
import com.dezzy.music.lossless.LosslessExportToPublicKey
import com.dezzy.music.lossless.LosslessProvider
import com.dezzy.music.lossless.LosslessQuality
import com.dezzy.music.lossless.LosslessRecentSearchesKey
import com.dezzy.music.lossless.LosslessResolverUrlKey
import com.dezzy.music.lossless.LosslessTrack
import com.dezzy.music.models.MediaMetadata
import com.dezzy.music.playback.FLAC_STREAM_PREFIX
import com.dezzy.music.playback.PlayerConnection
import com.dezzy.music.ui.utils.backToMain
import com.dezzy.music.utils.rememberPreference
import com.dezzy.music.viewmodels.LosslessSearchMode
import com.dezzy.music.viewmodels.LosslessViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.dezzy.music.ui.component.IconButton as LongPressIconButton

private const val SEARCH_DEBOUNCE_MS = 400L
private const val MAX_RECENT_SEARCHES = 8

/** Lo que hay que descargar desde la hoja: una pista o un álbum entero. */
private data class DownloadRequest(
    val tracks: List<LosslessTrack>,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
)

/** Proveedor y calidad guardados como predeterminados (o Deezer · FLAC). */
private data class LosslessDefaults(val provider: LosslessProvider, val quality: LosslessQuality)

@Composable
private fun rememberLosslessDefaults(): Pair<LosslessDefaults, (LosslessProvider, LosslessQuality) -> Unit> {
    var providerId by rememberPreference(LosslessDefaultProviderKey, "")
    var qualityId by rememberPreference(LosslessDefaultQualityKey, "")
    val provider = LosslessProvider.fromId(providerId) ?: LosslessProvider.DEFAULT
    val quality = provider.qualities.firstOrNull { it.id == qualityId } ?: provider.qualities.first()
    val save: (LosslessProvider, LosslessQuality) -> Unit = { p, q ->
        providerId = p.id
        qualityId = q.id
    }
    return LosslessDefaults(provider, quality) to save
}

/** Búsquedas recientes guardadas en DataStore (una por línea). */
private class RecentSearches(private val raw: String, private val write: (String) -> Unit) {
    val items: List<String> = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun add(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        val next = (listOf(q) + items.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_RECENT_SEARCHES)
        if (next != items) write(next.joinToString("\n"))
    }

    fun remove(query: String) = write(items.filterNot { it == query }.joinToString("\n"))

    fun clear() = write("")
}

@Composable
private fun rememberRecentSearches(): RecentSearches {
    var raw by rememberPreference(LosslessRecentSearchesKey, "")
    return remember(raw) { RecentSearches(raw) { raw = it } }
}

private fun stateFor(downloads: Map<String, LosslessDownloadState>, track: LosslessTrack): LosslessDownloadState? =
    downloads[track.provider.id + ":" + track.providerTrackId]
        ?: downloads.entries.firstOrNull { it.key.substringAfterLast(":") == track.providerTrackId }?.value

private fun formatDuration(seconds: Int): String =
    if (seconds <= 0) "" else "%d:%02d".format(seconds / 60, seconds % 60)

private fun LosslessQuality.isLossless(): Boolean = container.equals("flac", ignoreCase = true)

/**
 * Pantalla de búsqueda y descarga lossless (estilo SpotiFLAC). Busca mientras se escribe,
 * por canciones o por álbumes, y cada fila muestra el estado de su descarga (en cola,
 * progreso —tocar para parar—, descargado o error con reintento).
 * Ver [com.dezzy.music.lossless.LosslessDownloadManager].
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
        }
    }

    val query by viewModel.query.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val results by viewModel.results.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchedQuery by viewModel.searchedQuery.collectAsState()
    val expandedAlbumId by viewModel.expandedAlbumId.collectAsState()
    val albumTracks by viewModel.albumTracks.collectAsState()
    val loadingAlbumId by viewModel.loadingAlbumId.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val (resolverUrl) = rememberPreference(LosslessResolverUrlKey, DefaultLosslessResolverUrl)
    val (defaults, saveDefaults) = rememberLosslessDefaults()
    val recents = rememberRecentSearches()
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var request by remember { mutableStateOf<DownloadRequest?>(null) }

    LaunchedEffect(query, mode) {
        val q = query.trim()
        when {
            q.isEmpty() -> viewModel.search() // limpia resultados
            q.length >= 2 && q != searchedQuery -> {
                delay(SEARCH_DEBOUNCE_MS)
                viewModel.search()
            }
        }
    }

    val activeCount = downloads.values.count { it.phase.isActive }
    val trimmed = query.trim()
    val showResults = trimmed.length >= 2

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

        LosslessSearchField(
            query = query,
            onQueryChange = viewModel::onQueryChange,
            onSearch = {
                recents.add(query)
                focusManager.clearFocus()
                viewModel.search()
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DefaultQualityChip(defaults = defaults, onSelect = saveDefaults)
            FilterChip(
                selected = mode == LosslessSearchMode.TRACKS,
                onClick = { viewModel.onModeChange(LosslessSearchMode.TRACKS) },
                label = { Text("Canciones") },
            )
            FilterChip(
                selected = mode == LosslessSearchMode.ALBUMS,
                onClick = { viewModel.onModeChange(LosslessSearchMode.ALBUMS) },
                label = { Text("Álbumes") },
            )
        }

        Box(Modifier.fillMaxWidth().height(3.dp)) {
            if (isSearching && showResults) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        val onPlay: (LosslessTrack) -> Unit = { track ->
            recents.add(searchedQuery)
            if (playerConnection != null) {
                playLosslessStream(scope, context, playerConnection, viewModel.downloadManager, track)
            }
        }
        val onDownload: (LosslessTrack) -> Unit = { track ->
            request = DownloadRequest(listOf(track), track.title, track.artistLabel, track.thumbnailUrl)
        }
        val onCancel: (LosslessTrack, LosslessDownloadState) -> Unit = { track, state ->
            viewModel.cancel(track.copy(provider = state.provider))
            Toast.makeText(context, "Descarga parada", Toast.LENGTH_SHORT).show()
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (!showResults) {
                losslessEmptyState(
                    resolverMissing = resolverUrl.isBlank(),
                    recents = recents,
                    downloads = downloads,
                    onConfigure = { navController.navigate("settings/lossless") },
                    onRecentClick = { viewModel.onQueryChange(it) },
                    onSeeDownloads = { navController.navigate("lossless/downloads") },
                )
            } else if (mode == LosslessSearchMode.TRACKS) {
                if (results.isEmpty()) {
                    if (isSearching || searchedQuery != trimmed) {
                        items(6, key = { "ph$it" }) { ResultPlaceholder() }
                    } else {
                        item(key = "empty") { NoResults(trimmed) }
                    }
                } else {
                    items(results, key = { it.provider.id + it.providerTrackId }) { track ->
                        LosslessResultRow(
                            track = track,
                            state = stateFor(downloads, track),
                            defaultQuality = defaults.quality,
                            onPlayClick = { onPlay(track) },
                            onDownloadClick = { onDownload(track) },
                            onCancel = { state -> onCancel(track, state) },
                        )
                    }
                }
            } else {
                if (albums.isEmpty()) {
                    if (isSearching || searchedQuery != trimmed) {
                        items(6, key = { "aph$it" }) { ResultPlaceholder() }
                    } else {
                        item(key = "empty") { NoResults(trimmed) }
                    }
                } else {
                    albums.forEach { album ->
                        val expanded = expandedAlbumId == album.id
                        val tracks = albumTracks[album.id].orEmpty()
                        item(key = "album:" + album.id) {
                            LosslessAlbumRow(
                                album = album,
                                expanded = expanded,
                                onClick = { viewModel.toggleAlbum(album) },
                            )
                        }
                        if (expanded) {
                            when {
                                loadingAlbumId == album.id && tracks.isEmpty() -> item(key = "albumLoading:" + album.id) {
                                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    }
                                }

                                tracks.isEmpty() -> item(key = "albumEmpty:" + album.id) {
                                    Text(
                                        text = "No se pudieron cargar las canciones.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 88.dp, top = 4.dp, bottom = 8.dp),
                                    )
                                }

                                else -> {
                                    item(key = "albumAll:" + album.id) {
                                        FilledTonalButton(
                                            onClick = {
                                                request = DownloadRequest(
                                                    tracks = tracks,
                                                    title = album.title,
                                                    subtitle = "${album.artistName} · ${tracks.size} canciones",
                                                    thumbnailUrl = album.thumbnailUrl,
                                                )
                                            },
                                            modifier = Modifier.padding(start = 76.dp, top = 2.dp, bottom = 4.dp),
                                        ) {
                                            Icon(painterResource(R.drawable.download), null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Descargar álbum")
                                        }
                                    }
                                    items(tracks, key = { "albumTrack:" + album.id + ":" + it.providerTrackId }) { track ->
                                        LosslessResultRow(
                                            track = track,
                                            state = stateFor(downloads, track),
                                            defaultQuality = defaults.quality,
                                            showCover = false,
                                            onPlayClick = { onPlay(track) },
                                            onDownloadClick = { onDownload(track) },
                                            onCancel = { state -> onCancel(track, state) },
                                            modifier = Modifier.padding(start = 60.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    TopAppBar(
        title = { Text("Descarga lossless") },
        navigationIcon = {
            LongPressIconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = "Atrás")
            }
        },
        actions = {
            IconButton(onClick = { navController.navigate("lossless/downloads") }) {
                BadgedBox(
                    badge = {
                        if (activeCount > 0) Badge { Text(activeCount.toString()) }
                    },
                ) {
                    Icon(
                        painterResource(R.drawable.downloading),
                        contentDescription = if (activeCount > 0) "Descargas, $activeCount en curso" else "Descargas",
                    )
                }
            }
            IconButton(onClick = { navController.navigate("settings/lossless") }) {
                Icon(painterResource(R.drawable.tune), contentDescription = "Ajustes de descarga lossless")
            }
        },
        scrollBehavior = scrollBehavior,
    )

    request?.let { req ->
        LosslessDownloadSheet(
            request = req,
            defaults = defaults,
            onDismiss = { request = null },
            onConfirm = { provider, quality, remember ->
                if (remember) saveDefaults(provider, quality)
                recents.add(searchedQuery)
                val tracks = req.tracks.map { it.copy(provider = provider) }
                if (tracks.size == 1) viewModel.download(tracks.first(), quality) else viewModel.downloadAll(tracks, quality)
                if (tracks.size > 1) {
                    Toast.makeText(context, "${tracks.size} canciones en cola", Toast.LENGTH_SHORT).show()
                }
                request = null
            },
        )
    }
}

@Composable
private fun LosslessSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("Canción, artista o álbum") },
        leadingIcon = { Icon(painterResource(R.drawable.search), contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(painterResource(R.drawable.close), contentDescription = "Borrar búsqueda")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Chip «Deezer · FLAC ▾»: la calidad con la que se preselecciona cada descarga. */
@Composable
private fun DefaultQualityChip(
    defaults: LosslessDefaults,
    onSelect: (LosslessProvider, LosslessQuality) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text("${defaults.provider.displayName} · ${defaults.quality.label}") },
            leadingIcon = if (defaults.quality.isLossless()) {
                { Icon(painterResource(R.drawable.graphic_eq), null, Modifier.size(16.dp)) }
            } else null,
            trailingIcon = { Icon(painterResource(R.drawable.expand_more), null, Modifier.size(18.dp)) },
            modifier = Modifier.semantics {
                contentDescription = "Calidad predeterminada: ${defaults.provider.displayName}, ${defaults.quality.label}. Cambiar"
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LosslessProvider.entries.forEach { provider ->
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                provider.qualities.forEach { quality ->
                    val selected = provider == defaults.provider && quality == defaults.quality
                    DropdownMenuItem(
                        text = { Text(quality.label) },
                        onClick = {
                            onSelect(provider, quality)
                            open = false
                        },
                        trailingIcon = if (selected) {
                            { Icon(painterResource(R.drawable.done), null) }
                        } else null,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.losslessEmptyState(
    resolverMissing: Boolean,
    recents: RecentSearches,
    downloads: Map<String, LosslessDownloadState>,
    onConfigure: () -> Unit,
    onRecentClick: (String) -> Unit,
    onSeeDownloads: () -> Unit,
) {
    if (resolverMissing) {
        item(key = "resolver") {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Falta el servidor de descargas",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Puedes buscar y escuchar, pero para guardar en FLAC hace falta la dirección del servidor.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onConfigure) { Text("Configurar") }
                }
            }
        }
    }

    if (recents.items.isNotEmpty()) {
        item(key = "recentsHeader") {
            SectionHeader(title = "Búsquedas recientes", action = "Borrar", onAction = recents::clear)
        }
        item(key = "recents") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                recents.items.forEach { q ->
                    AssistChip(
                        onClick = { onRecentClick(q) },
                        label = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(painterResource(R.drawable.history), null, Modifier.size(16.dp)) },
                    )
                }
            }
        }
    }

    val latest = downloads.values.toList().asReversed().take(5)
    if (latest.isNotEmpty()) {
        item(key = "latestHeader") {
            SectionHeader(title = "Últimas descargas", action = "Ver todas", onAction = onSeeDownloads)
        }
        items(latest, key = { "latest:" + it.trackKey }) { state ->
            LatestDownloadRow(state)
        }
    }

    if (!resolverMissing && recents.items.isEmpty() && latest.isEmpty()) {
        item(key = "hint") {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 48.dp),
            ) {
                Icon(
                    painterResource(R.drawable.graphic_eq),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Busca una canción o un álbum",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Toca una fila para escucharla en FLAC, o la flecha para guardarla.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String?, onAction: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (action != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun LatestDownloadRow(state: LosslessDownloadState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        AsyncImage(
            model = state.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = when (state.phase) {
                    LosslessDownloadState.Phase.DONE -> "Descargado · ${state.provider.displayName}"
                    LosslessDownloadState.Phase.FAILED -> state.error ?: "Error"
                    LosslessDownloadState.Phase.DOWNLOADING -> "Descargando ${(state.progress * 100).toInt()} %"
                    else -> "En cola"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.phase == LosslessDownloadState.Phase.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun NoResults(query: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
    ) {
        Icon(
            painterResource(R.drawable.search),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Sin resultados para «$query»",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Prueba con otro nombre o revisa la conexión.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultPlaceholder() {
    val shade = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(shade),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.7f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shade),
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.45f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shade),
            )
        }
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
private fun SmallBadge(text: String, emphasized: Boolean) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun LosslessResultRow(
    track: LosslessTrack,
    state: LosslessDownloadState?,
    defaultQuality: LosslessQuality,
    onPlayClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onCancel: (LosslessDownloadState) -> Unit,
    modifier: Modifier = Modifier,
    showCover: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPlayClick, onClickLabel = "Escuchar en FLAC")
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCover) {
            AsyncImage(
                model = track.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = if (showCover) 12.dp else 0.dp, end = 8.dp),
        ) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            val subtitle = listOfNotNull(track.artistLabel, track.albumName?.takeIf { showCover }).joinToString(" · ")
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 3.dp),
            ) {
                SmallBadge(if (defaultQuality.isLossless()) "FLAC" else defaultQuality.container.uppercase(), emphasized = true)
                if (track.explicit) SmallBadge("E", emphasized = false)
                val duration = formatDuration(track.durationSec)
                if (duration.isNotEmpty()) {
                    Text(duration, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusLabel(state)
            }
        }
        DownloadStatusAction(
            state = state,
            onDownloadClick = onDownloadClick,
            onCancel = onCancel,
        )
    }
}

@Composable
private fun StatusLabel(state: LosslessDownloadState?) {
    val (text, color) = when (state?.phase) {
        LosslessDownloadState.Phase.QUEUED -> "En cola" to MaterialTheme.colorScheme.onSurfaceVariant
        LosslessDownloadState.Phase.RESOLVING -> "Preparando…" to MaterialTheme.colorScheme.onSurfaceVariant
        LosslessDownloadState.Phase.DOWNLOADING -> "${(state.progress * 100).toInt()} %" to MaterialTheme.colorScheme.primary
        LosslessDownloadState.Phase.TAGGING -> "Guardando…" to MaterialTheme.colorScheme.onSurfaceVariant
        LosslessDownloadState.Phase.DONE -> "Descargado" to MaterialTheme.colorScheme.primary
        LosslessDownloadState.Phase.FAILED -> (state.error ?: "Error") to MaterialTheme.colorScheme.error
        null -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Acción al final de la fila: descargar, anillo de progreso (tocar para parar), hecho o reintentar. */
@Composable
private fun DownloadStatusAction(
    state: LosslessDownloadState?,
    onDownloadClick: () -> Unit,
    onCancel: (LosslessDownloadState) -> Unit,
) {
    when {
        state == null -> IconButton(onClick = onDownloadClick) {
            Icon(painterResource(R.drawable.download), contentDescription = "Descargar")
        }

        state.phase.isActive -> IconButton(onClick = { onCancel(state) }) {
            Box(contentAlignment = Alignment.Center) {
                if (state.phase == LosslessDownloadState.Phase.DOWNLOADING && state.progress > 0f) {
                    CircularProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.size(30.dp),
                        strokeWidth = 2.5.dp,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.5.dp)
                }
                Icon(
                    painterResource(R.drawable.close),
                    contentDescription = "Parar descarga",
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        state.phase == LosslessDownloadState.Phase.DONE -> Box(
            Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.done),
                contentDescription = "Descargado",
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        else -> IconButton(onClick = onDownloadClick) {
            Icon(
                painterResource(R.drawable.replay),
                contentDescription = "Reintentar descarga",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun LosslessAlbumRow(
    album: LosslessAlbum,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, onClickLabel = if (expanded) "Ocultar canciones" else "Ver canciones")
            .heightIn(min = 72.dp)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        AsyncImage(
            model = album.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                album.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                listOfNotNull(
                    album.artistName.takeIf { it.isNotBlank() },
                    album.trackCount.takeIf { it > 0 }?.let { if (it == 1) "1 canción" else "$it canciones" },
                ).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 3.dp),
            ) {
                SmallBadge("FLAC", emphasized = true)
                if (album.explicit) SmallBadge("E", emphasized = false)
            }
        }
        Icon(
            painterResource(if (expanded) R.drawable.expand_less else R.drawable.expand_more),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Hoja de descarga: proveedor (con su calidad máxima), calidad, dónde se guarda y
 * «Recordar como predeterminada». Sirve para una pista o para un álbum entero.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LosslessDownloadSheet(
    request: DownloadRequest,
    defaults: LosslessDefaults,
    onDismiss: () -> Unit,
    onConfirm: (LosslessProvider, LosslessQuality, Boolean) -> Unit,
) {
    var provider by remember { mutableStateOf(defaults.provider) }
    var quality by remember { mutableStateOf(defaults.quality) }
    var rememberChoice by remember { mutableStateOf(false) }
    val (exportToPublic) = rememberPreference(LosslessExportToPublicKey, false)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                AsyncImage(
                    model = request.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        request.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        request.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                "Proveedor",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 24.dp, top = 20.dp, bottom = 4.dp)
                    .semantics { heading() },
            )
            LosslessProvider.entries.forEach { p ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = provider == p,
                            role = Role.RadioButton,
                            onClick = {
                                if (provider != p) {
                                    provider = p
                                    quality = p.qualities.first()
                                }
                            },
                        )
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 12.dp),
                ) {
                    RadioButton(selected = provider == p, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                    Text(p.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        "Hasta ${p.qualities.first().label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            }

            Text(
                "Calidad",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 24.dp, top = 16.dp, bottom = 6.dp)
                    .semantics { heading() },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                provider.qualities.forEach { q ->
                    FilterChip(
                        selected = quality == q,
                        onClick = { quality = q },
                        label = { Text(q.label, maxLines = 1) },
                        leadingIcon = if (q.isLossless()) {
                            { Icon(painterResource(R.drawable.graphic_eq), null, Modifier.size(16.dp)) }
                        } else null,
                    )
                }
            }

            Text(
                text = if (exportToPublic) {
                    "Se guarda en Música/Dezzy y aparece en tus Me gusta."
                } else {
                    "Se guarda en tu biblioteca y aparece en tus Me gusta."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .toggleable(value = rememberChoice, role = Role.Checkbox, onValueChange = { rememberChoice = it })
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp),
            ) {
                Checkbox(checked = rememberChoice, onCheckedChange = null, modifier = Modifier.padding(horizontal = 12.dp))
                Text("Recordar como predeterminada", style = MaterialTheme.typography.bodyLarge)
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 16.dp, top = 10.dp),
            ) {
                TextButton(onClick = onDismiss) { Text("Cancelar") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onConfirm(provider, quality, rememberChoice) }) {
                    Icon(painterResource(R.drawable.download), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (quality.isLossless()) "Descargar FLAC" else "Descargar ${quality.container.uppercase()}",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Modo de búsqueda LOSSLESS embebido en la barra de búsqueda principal (junto a
 * YouTube y Local). Reacciona a la [query] tecleada (con debounce) y reusa las
 * mismas filas y la misma hoja de descarga que [LosslessScreen]. Solo se muestra
 * si la función está activada (lo decide quien lo invoca, en MainActivity).
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
    val searchedQuery by viewModel.searchedQuery.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val (defaults, saveDefaults) = rememberLosslessDefaults()
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Esta vista solo busca canciones.
    LaunchedEffect(Unit) {
        if (viewModel.mode.value != LosslessSearchMode.TRACKS) viewModel.mode.value = LosslessSearchMode.TRACKS
    }

    LaunchedEffect(query) {
        val q = query.trim()
        viewModel.onQueryChange(q)
        if (q.length >= 2) {
            delay(SEARCH_DEBOUNCE_MS)
            viewModel.search()
        }
    }

    var request by remember { mutableStateOf<DownloadRequest?>(null) }
    val trimmed = query.trim()

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal),
            ),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (trimmed.length >= 2) {
                if (results.isEmpty()) {
                    if (isSearching || searchedQuery != trimmed) {
                        items(6, key = { "ph$it" }) { ResultPlaceholder() }
                    } else {
                        item(key = "empty") { NoResults(trimmed) }
                    }
                } else {
                    items(results, key = { it.provider.id + it.providerTrackId }) { track ->
                        LosslessResultRow(
                            track = track,
                            state = stateFor(downloads, track),
                            defaultQuality = defaults.quality,
                            onPlayClick = {
                                if (playerConnection != null) {
                                    playLosslessStream(scope, context, playerConnection, viewModel.downloadManager, track)
                                }
                            },
                            onDownloadClick = {
                                request = DownloadRequest(listOf(track), track.title, track.artistLabel, track.thumbnailUrl)
                            },
                            onCancel = { state ->
                                viewModel.cancel(track.copy(provider = state.provider))
                                Toast.makeText(context, "Descarga parada", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
            }
        }
        AnimatedVisibility(visible = isSearching && results.isNotEmpty(), modifier = Modifier.align(Alignment.TopCenter)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    request?.let { req ->
        LosslessDownloadSheet(
            request = req,
            defaults = defaults,
            onDismiss = { request = null },
            onConfirm = { provider, quality, remember ->
                if (remember) saveDefaults(provider, quality)
                viewModel.download(req.tracks.first().copy(provider = provider), quality)
                request = null
            },
        )
    }
}
