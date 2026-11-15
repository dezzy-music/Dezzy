/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.playlist

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dezzy.music.LocalDatabase
import com.dezzy.music.R
import com.dezzy.music.constants.InnerTubeCookieKey
import com.dezzy.music.constants.SongSortType
import com.dezzy.music.db.MusicDatabase
import com.dezzy.music.db.entities.Playlist
import com.dezzy.music.db.entities.PlaylistSongMap
import com.dezzy.music.db.entities.Song
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.innertube.utils.parseCookieString
import com.dezzy.music.models.MediaMetadata
import com.dezzy.music.models.toMediaMetadata
import com.dezzy.music.security.SessionCrypto
import com.dezzy.music.utils.makeTimeString
import com.dezzy.music.utils.rememberPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/** Id especial con el que se abre la hoja para la lista «Me gusta». */
private const val LIKED_TARGET = "LIKED"

/** Pestañas de origen cuando no se está buscando. */
private enum class AddSource(val label: String) {
    SUGGESTED("Sugeridas"),
    LIKED("Me gusta"),
    RECENT("Recientes"),
    LIBRARY("Biblioteca"),
}

/** Chips de la búsqueda. */
private enum class AddSearchSource(val label: String) {
    SONGS("Canciones"),
    LIBRARY("En mi biblioteca"),
    VIDEOS("Videos"),
}

private class SuggestionResult(
    val songs: List<MediaMetadata>,
    val basedOnPlaylist: Boolean,
)

/** Qué pinta la lista en cada momento. */
private sealed interface AddListState {
    data object Loading : AddListState
    data class Failed(val title: String, val body: String) : AddListState
    data class Empty(val title: String, val body: String?) : AddListState
    data class Ready(val songs: List<MediaMetadata>) : AddListState
}

/**
 * Botón «Añadir» de la fila de acciones de una playlist (boceto A de la fase 9).
 * [filled] se usa en la playlist vacía, donde es la acción principal.
 */
@Composable
fun AddSongsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        Icon(
            painter = painterResource(R.drawable.add),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (filled) "Añadir canciones" else "Añadir",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
    if (filled) {
        Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) { content() }
    } else {
        FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) { content() }
    }
}

/**
 * Hoja «Añadir a» (fase 9, boceto A). Conserva el nombre y la firma de la antigua
 * pantalla «Añadir a esta playlist» para no tocar a quien la abre.
 *
 * - [playlistId] = id de una playlist local, o "LIKED" (también null) para Me gusta.
 * - Pestañas de origen: Sugeridas (relacionadas de YouTube Music a partir de la
 *   playlist), Me gusta, Recientes y Biblioteca (estas tres salen de la base de datos
 *   y funcionan sin conexión). En Me gusta no aparece la pestaña Me gusta.
 * - Al escribir, las pestañas dejan paso a chips: Canciones / En mi biblioteca / Videos.
 * - Tocar la fila o el círculo añade; tocar la palomita quita (como antes). Cada
 *   añadido muestra «Deshacer». Tocar NO reproduce nada.
 * - Lo que ya se está guardando termina aunque cierres la hoja.
 * - Si la playlist está enlazada a YouTube Music y hay sesión, la canción se sube
 *   también allí (antes sólo se guardaba en el teléfono).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSongsToLikedScreen(
    playlistId: String? = null,
    onDismiss: () -> Unit,
) {
    val targetId = playlistId ?: LIKED_TARGET
    val isLikedTarget = targetId == LIKED_TARGET
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }
    val writeLock = remember { Mutex() }

    val (innerTubeCookie) = rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(SessionCrypto.decrypt(innerTubeCookie))
    }

    val playlist by remember(targetId) {
        if (isLikedTarget) flowOf<Playlist?>(null) else database.playlist(targetId)
    }.collectAsState(initial = null)

    // Ids que hay ahora mismo en el destino (en vivo) y los que había al abrir.
    val liveIds by remember(targetId) {
        (if (isLikedTarget) database.likedSongIds() else database.playlistSongIds(targetId))
            .map { it.toSet() }
    }.collectAsState(initial = null)
    var initialIds by remember { mutableStateOf<Set<String>?>(null) }

    // Cambios lanzados que la base de datos aún no refleja (para que el toque se vea al instante).
    var pendingAdd by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingRemove by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(liveIds) {
        val live = liveIds ?: return@LaunchedEffect
        if (initialIds == null) initialIds = live
        pendingAdd = pendingAdd.filterNot { it in live }.toSet()
        pendingRemove = pendingRemove.filter { it in live }.toSet()
    }

    fun isAdded(id: String): Boolean {
        if (id in pendingRemove) return false
        return id in pendingAdd || liveIds?.contains(id) == true
    }

    val addedCount = run {
        val before = initialIds ?: return@run 0
        ((liveIds.orEmpty() + pendingAdd) - pendingRemove - before).size
    }

    // ── Escritura ─────────────────────────────────────────────────────────────
    suspend fun removeFromTarget(songId: String): Boolean = writeLock.withLock {
        if (isLikedTarget) {
            val song = database.song(songId).first()?.song ?: return@withLock true
            if (song.liked) database.update(song.toggleLike())
            return@withLock true
        }
        val target = database.getPlaylistById(targetId) ?: return@withLock false
        val map = database.playlistSongMaps(targetId, 0).lastOrNull { it.songId == songId }
            ?: return@withLock true
        database.withTransaction {
            move(map.playlistId, map.position, Int.MAX_VALUE)
            delete(map.copy(position = Int.MAX_VALUE))
        }
        val browseId = target.playlist.browseId
        if (browseId != null && isLoggedIn) {
            val setVideoId = map.setVideoId ?: database.getSetVideoId(songId)?.setVideoId
            if (setVideoId != null) YouTube.removeFromPlaylist(browseId, songId, setVideoId)
        }
        true
    }

    /** null = añadida; si no, el texto del error para el aviso. */
    suspend fun addToTarget(meta: MediaMetadata): String? = writeLock.withLock {
        database.insert(meta)
        if (isLikedTarget) {
            val song = database.song(meta.id).first()?.song
                ?: return@withLock "No se pudo añadir «${meta.title}»"
            if (!song.liked) database.update(song.toggleLike())
            return@withLock null
        }
        val target = database.getPlaylistById(targetId)
            ?: return@withLock "La playlist ya no existe"
        val browseId = target.playlist.browseId
        var setVideoId: String? = null
        if (browseId != null && isLoggedIn) {
            val remote = YouTube.addToPlaylist(browseId, meta.id)
            if (remote.isFailure) return@withLock "No se pudo añadir en YouTube Music"
            setVideoId = remote.getOrNull()
        }
        database.withTransaction {
            if (playlistDuplicates(targetId, listOf(meta.id)).isEmpty()) {
                val position = (maxPlaylistSongPosition(targetId) ?: -1) + 1
                insert(
                    PlaylistSongMap(
                        playlistId = targetId,
                        songId = meta.id,
                        position = position,
                        setVideoId = setVideoId,
                    )
                )
            }
            update(target.playlist.copy(lastUpdateTime = LocalDateTime.now()))
        }
        null
    }

    fun remove(songId: String) {
        if (songId in pendingRemove) return
        pendingAdd = pendingAdd - songId
        pendingRemove = pendingRemove + songId
        scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO + NonCancellable) { removeFromTarget(songId) } }
                .getOrElse { if (it is CancellationException) throw it else false }
            if (!ok) {
                pendingRemove = pendingRemove - songId
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar("No se pudo quitar la canción")
            }
        }
    }

    fun add(meta: MediaMetadata) {
        if (meta.id in pendingAdd) return
        pendingRemove = pendingRemove - meta.id
        pendingAdd = pendingAdd + meta.id
        scope.launch {
            val error = runCatching { withContext(Dispatchers.IO + NonCancellable) { addToTarget(meta) } }
                .getOrElse { if (it is CancellationException) throw it else "No se pudo añadir «${meta.title}»" }
            snackbarHostState.currentSnackbarData?.dismiss()
            if (error != null) {
                pendingAdd = pendingAdd - meta.id
                snackbarHostState.showSnackbar(error)
            } else {
                val result = snackbarHostState.showSnackbar(
                    message = "«${meta.title}» añadida",
                    actionLabel = "Deshacer",
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) remove(meta.id)
            }
        }
    }

    fun toggle(meta: MediaMetadata) {
        if (isAdded(meta.id)) remove(meta.id) else add(meta)
    }

    // ── Orígenes ──────────────────────────────────────────────────────────────
    val sources = remember(isLikedTarget) {
        AddSource.entries.filter { !(isLikedTarget && it == AddSource.LIKED) }
    }
    var source by rememberSaveable { mutableStateOf(AddSource.SUGGESTED) }

    var suggestionRound by rememberSaveable { mutableIntStateOf(0) }
    var suggestions by remember { mutableStateOf<SuggestionResult?>(null) }
    var suggestionsFailed by remember { mutableStateOf(false) }
    LaunchedEffect(targetId, suggestionRound) {
        suggestions = null
        suggestionsFailed = false
        val result = withContext(Dispatchers.IO) {
            runCatching { loadSuggestions(database, targetId, isLikedTarget, suggestionRound) }
        }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        suggestionsFailed = result.isFailure
        suggestions = result.getOrNull() ?: SuggestionResult(emptyList(), basedOnPlaylist = false)
    }

    var localLists by remember { mutableStateOf<Map<AddSource, List<MediaMetadata>>>(emptyMap()) }
    LaunchedEffect(source) {
        if (source == AddSource.SUGGESTED || localLists.containsKey(source)) return@LaunchedEffect
        val songs = withContext(Dispatchers.IO) {
            val list: List<Song> = when (source) {
                AddSource.LIKED -> database.likedSongsByCreateDateAsc().first().asReversed()
                AddSource.RECENT -> database.recentlyPlayedSongs(100).first()
                AddSource.LIBRARY -> database.songs(SongSortType.CREATE_DATE, true).first()
                AddSource.SUGGESTED -> emptyList()
            }
            list.map { it.toMediaMetadata() }.distinctBy { it.id }
        }
        localLists = localLists + (source to songs)
    }

    // ── Búsqueda ──────────────────────────────────────────────────────────────
    var query by rememberSaveable { mutableStateOf("") }
    var searchSource by rememberSaveable { mutableStateOf(AddSearchSource.SONGS) }
    var searchRetry by remember { mutableIntStateOf(0) }
    var searchResults by remember { mutableStateOf<List<MediaMetadata>?>(emptyList()) }
    var searchFailed by remember { mutableStateOf(false) }
    val isSearching = query.isNotBlank()
    LaunchedEffect(query, searchSource, searchRetry) {
        val q = query.trim()
        if (q.isEmpty()) {
            searchResults = emptyList()
            searchFailed = false
            return@LaunchedEffect
        }
        delay(if (searchSource == AddSearchSource.LIBRARY) 200 else 400)
        searchResults = null
        searchFailed = false
        val result = withContext(Dispatchers.IO) {
            runCatching {
                when (searchSource) {
                    AddSearchSource.SONGS -> YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).getOrThrow()
                        .items.filterIsInstance<SongItem>().map { it.toMediaMetadata() }
                    AddSearchSource.VIDEOS -> YouTube.search(q, YouTube.SearchFilter.FILTER_VIDEO).getOrThrow()
                        .items.filterIsInstance<SongItem>().map { it.toMediaMetadata() }
                    AddSearchSource.LIBRARY -> database.searchSongs(q, 100).first().map { it.toMediaMetadata() }
                }.distinctBy { it.id }
            }
        }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        searchFailed = result.isFailure
        searchResults = result.getOrNull().orEmpty()
    }

    // ── Estado de la lista ───────────────────────────────────────────────────
    val listState: AddListState = when {
        isSearching -> when {
            searchResults == null -> AddListState.Loading
            searchFailed -> AddListState.Failed("No se pudo buscar", "Revisa tu conexión e inténtalo otra vez.")
            searchResults!!.isEmpty() -> AddListState.Empty(
                "Sin resultados para «${query.trim()}»",
                if (searchSource == AddSearchSource.LIBRARY) "Prueba en «Canciones» para buscar en YouTube Music." else null,
            )
            else -> AddListState.Ready(searchResults!!)
        }
        source == AddSource.SUGGESTED -> {
            val s = suggestions
            when {
                s == null -> AddListState.Loading
                suggestionsFailed -> AddListState.Failed(
                    "No se pudieron cargar sugerencias",
                    "Revisa tu conexión. Mientras, puedes elegir de Me gusta, Recientes o Biblioteca.",
                )
                s.songs.isEmpty() -> AddListState.Empty("No hay sugerencias por ahora", "Prueba con otra tanda o busca arriba.")
                else -> AddListState.Ready(s.songs)
            }
        }
        else -> {
            val list = localLists[source]
            when {
                list == null -> AddListState.Loading
                list.isEmpty() -> when (source) {
                    AddSource.LIKED -> AddListState.Empty("Aún no tienes canciones en Me gusta", null)
                    AddSource.RECENT -> AddListState.Empty("Todavía no has escuchado nada", null)
                    else -> AddListState.Empty("Tu biblioteca está vacía", "Busca arriba para añadir desde YouTube Music.")
                }
                else -> AddListState.Ready(list)
            }
        }
    }

    val targetName = if (isLikedTarget) stringResource(R.string.liked_songs) else playlist?.playlist?.name.orEmpty()
    val alreadyInLabel = if (isLikedTarget) "Ya está en Me gusta" else "Ya está en la playlist"

    fun close() {
        focusManager.clearFocus()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Cabecera: a qué playlist se añade + «Listo · N» ──────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AddTargetCover(isLiked = isLikedTarget, playlist = playlist)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Añadir a",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = targetName,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { close() }) {
                        Text(if (addedCount > 0) "Listo · $addedCount" else "Listo", maxLines = 1)
                    }
                }

                // ── Buscador en píldora ───────────────────────────────────────
                AddSearchPill(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = { focusManager.clearFocus() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                // ── Pestañas de origen o chips de búsqueda ────────────────────
                if (!isSearching) {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = sources.indexOf(source).coerceAtLeast(0),
                        edgePadding = 16.dp,
                        containerColor = Color.Transparent,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        sources.forEach { tab ->
                            Tab(
                                selected = tab == source,
                                onClick = { source = tab },
                                text = { Text(tab.label, maxLines = 1) },
                            )
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        AddSearchSource.entries.forEach { chip ->
                            val selected = chip == searchSource
                            FilterChip(
                                selected = selected,
                                onClick = { searchSource = chip },
                                label = { Text(chip.label) },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            painter = painterResource(R.drawable.check),
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }

                // ── Lista ─────────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    when (listState) {
                        AddListState.Loading -> AddSheetLoading(
                            text = when {
                                isSearching -> "Buscando…"
                                source == AddSource.SUGGESTED -> "Buscando sugerencias…"
                                else -> "Cargando…"
                            },
                        )

                        is AddListState.Failed -> AddSheetMessage(
                            title = listState.title,
                            body = listState.body,
                            actionLabel = "Reintentar",
                            onAction = {
                                if (isSearching) searchRetry++ else suggestionRound++
                            },
                        )

                        is AddListState.Empty -> AddSheetMessage(
                            title = listState.title,
                            body = listState.body,
                            actionLabel = if (!isSearching && source == AddSource.SUGGESTED) "Otra tanda" else null,
                            onAction = { suggestionRound++ },
                        )

                        is AddListState.Ready -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 96.dp),
                        ) {
                            if (!isSearching && source == AddSource.SUGGESTED) {
                                item(key = "suggested_header") {
                                    AddSectionHeader(
                                        title = if (isLikedTarget) "Para tus Me gusta" else "Para esta playlist",
                                        subtitle = if (suggestions?.basedOnPlaylist == true) {
                                            "Según lo último que añadiste"
                                        } else {
                                            "Según lo que escuchas"
                                        },
                                        onRefresh = { suggestionRound++ },
                                    )
                                }
                            } else if (isSearching) {
                                item(key = "results_header") {
                                    AddSectionHeader(title = "Resultados", subtitle = null, onRefresh = null)
                                }
                            }
                            items(listState.songs, key = { it.id }) { meta ->
                                val added = isAdded(meta.id)
                                AddCandidateRow(
                                    meta = meta,
                                    added = added,
                                    alreadyInLabel = if (added && initialIds?.contains(meta.id) == true) alreadyInLabel else null,
                                    onToggle = { toggle(meta) },
                                )
                            }
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Sugerencias: relacionadas de YouTube Music a partir de 3 canciones semilla de la
 * playlist (cada «Actualizar» usa las 3 siguientes). Si la playlist está vacía, las
 * semillas salen de lo último que escuchaste; si todo falla, «hits music».
 */
private suspend fun loadSuggestions(
    database: MusicDatabase,
    targetId: String,
    isLikedTarget: Boolean,
    round: Int,
): SuggestionResult {
    val local: List<Song> = if (isLikedTarget) {
        database.likedSongsByCreateDateAsc().first().asReversed()
    } else {
        database.playlistSongs(targetId).first().map { it.song }.asReversed()
    }
    val exclude = local.mapTo(HashSet()) { it.song.id }
    val pool = local.ifEmpty { database.recentlyPlayedSongs(30).first() }.take(30)
    val seeds = if (pool.size <= 3) {
        pool
    } else {
        val start = (round * 3) % pool.size
        (pool.drop(start) + pool).take(3)
    }

    val found = mutableListOf<SongItem>()
    for (seed in seeds) {
        val related = YouTube.next(WatchEndpoint(videoId = seed.song.id)).getOrNull()?.relatedEndpoint ?: continue
        YouTube.related(related).getOrNull()?.songs?.let(found::addAll)
    }
    if (found.isEmpty()) {
        found += YouTube.search("hits music", YouTube.SearchFilter.FILTER_SONG).getOrThrow()
            .items.filterIsInstance<SongItem>()
    }

    val songs = found
        .distinctBy { it.id }
        .filter { it.id !in exclude }
        .let { if (round > 0) it.shuffled() else it }
        .map { it.toMediaMetadata() }
    return SuggestionResult(songs, basedOnPlaylist = local.isNotEmpty())
}

@Composable
private fun AddTargetCover(isLiked: Boolean, playlist: Playlist?) {
    val shape = RoundedCornerShape(8.dp)
    val modifier = Modifier
        .size(44.dp)
        .clip(shape)
    when {
        isLiked -> Box(
            modifier = modifier.background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.favorite),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
        }

        (playlist?.thumbnails?.size ?: 0) >= 4 -> Column(modifier = modifier) {
            val thumbs = playlist!!.thumbnails
            for (rowIndex in 0..1) {
                Row(modifier = Modifier.weight(1f)) {
                    for (colIndex in 0..1) {
                        AsyncImage(
                            model = thumbs[rowIndex * 2 + colIndex],
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }

        playlist?.thumbnails?.isNotEmpty() == true -> AsyncImage(
            model = playlist!!.thumbnails.first(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )

        else -> Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.queue_music),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun AddSearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Buscar canciones",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = "Borrar búsqueda",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

@Composable
private fun AddSectionHeader(
    title: String,
    subtitle: String?,
    onRefresh: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onRefresh != null) {
            IconButton(onClick = onRefresh) {
                Icon(
                    painter = painterResource(R.drawable.sync),
                    contentDescription = "Otras sugerencias",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddCandidateRow(
    meta: MediaMetadata,
    added: Boolean,
    alreadyInLabel: String?,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = added, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        AsyncImage(
            model = meta.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = meta.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (meta.explicit) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(16.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(3.dp)),
                    ) {
                        Text(
                            text = "E",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val artists = meta.artists.joinToString { it.name }.ifBlank { "Artista desconocido" }
                val extra = alreadyInLabel
                    ?: meta.duration.takeIf { it > 0 }?.let { makeTimeString(it * 1000L) }
                Text(
                    text = if (extra != null) "$artists · $extra" else artists,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AddToggleMark(added = added, dimmed = alreadyInLabel != null)
    }
}

@Composable
private fun AddToggleMark(added: Boolean, dimmed: Boolean) {
    val container by animateColorAsState(
        targetValue = if (added) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "addMark",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .alpha(if (dimmed) 0.55f else 1f)
                .clip(CircleShape)
                .background(container)
                .then(
                    if (added) Modifier else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                ),
        ) {
            Icon(
                painter = painterResource(if (added) R.drawable.check else R.drawable.add),
                contentDescription = null,
                tint = if (added) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun AddSheetLoading(text: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 64.dp),
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.size(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddSheetMessage(
    title: String,
    body: String?,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 32.dp, end = 32.dp, bottom = 64.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.music_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Spacer(Modifier.size(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null) {
            Spacer(Modifier.size(12.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
