/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dezzy.music.LocalDatabase
import com.dezzy.music.R
import com.dezzy.music.constants.InnerTubeCookieKey
import com.dezzy.music.db.entities.Playlist
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.utils.parseCookieString
import com.dezzy.music.security.SessionCrypto
import com.dezzy.music.ui.component.CreatePlaylistDialog
import com.dezzy.music.ui.component.PlaylistThumbnail
import com.dezzy.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

internal fun playlistsForAddToPlaylist(playlists: List<Playlist>): List<Playlist> =
    playlists.filter { it.playlist.isEditable || it.playlist.browseId != null }

private enum class PlaylistSort(val label: String) {
    RECENT("Recientes"),
    AZ("A–Z"),
    YOUTUBE("YouTube Music"),
}

/**
 * "Guardar en…": hoja grande con las playlists en cuadrícula. Muestra en cada una si la
 * canción ya está ("Ya está" / "Ya hay N"), permite elegir varias y guarda de una vez.
 * Si se toca una playlist donde ya está todo, pregunta si añadirla otra vez.
 *
 * [onGetSong] solo tiene que devolver los ids (y dejar las canciones en la base de datos):
 * la sincronización con YouTube Music de las playlists remotas la hace este diálogo.
 * Se llama una vez, al abrir, con la primera playlist de la lista.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistDialog(
    isVisible: Boolean,
    allowSyncing: Boolean = true,
    initialTextFieldValue: String? = null,
    onGetSong: suspend (Playlist) -> List<String>,
    onDismiss: () -> Unit,
    onAddComplete: ((songCount: Int, playlistNames: List<String>) -> Unit)? = null,
    /** Texto bajo el título (p. ej. el nombre de la canción). Por defecto, "N canciones". */
    subtitle: String? = null,
) {
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    var allPlaylists by remember { mutableStateOf(emptyList<Playlist>()) }
    val (innerTubeCookie) = rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn = remember(innerTubeCookie) { "SAPISID" in parseCookieString(SessionCrypto.decrypt(innerTubeCookie)) }

    var showCreatePlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(PlaylistSort.RECENT) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    var songIds by remember { mutableStateOf<List<String>?>(null) }
    var duplicates by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var addAgainIds by remember { mutableStateOf(emptySet<String>()) }
    var confirmAgain by remember { mutableStateOf<Playlist?>(null) }
    var saving by remember { mutableStateOf(false) }

    val editable = remember(allPlaylists) { playlistsForAddToPlaylist(allPlaylists) }
    val shown = remember(editable, sort, query) {
        val base = when (sort) {
            PlaylistSort.RECENT -> editable.sortedByDescending { it.playlist.lastUpdateTime ?: it.playlist.createdAt }
            PlaylistSort.AZ -> editable.sortedBy { it.playlist.name.lowercase() }
            PlaylistSort.YOUTUBE -> editable
                .filter { it.playlist.browseId != null }
                .sortedByDescending { it.playlist.lastUpdateTime ?: it.playlist.createdAt }
        }
        val q = query.trim()
        if (q.isEmpty()) base else base.filter { it.playlist.name.contains(q, ignoreCase = true) }
    }

    suspend fun addSongsToPlaylistSafely(
        playlist: Playlist,
        requestedSongIds: List<String>,
    ): Int {
        if (requestedSongIds.isEmpty()) return 0

        val browseId = playlist.playlist.browseId
        val accepted = if (isLoggedIn && browseId != null) {
            requestedSongIds.filter { songId ->
                var wasAdded = false
                for (attempt in 0 until 3) {
                    if (YouTube.addToPlaylist(browseId, songId).isSuccess) {
                        wasAdded = true
                        break
                    }
                    if (attempt < 2) delay(250)
                }
                wasAdded
            }
        } else {
            requestedSongIds
        }
        if (accepted.isNotEmpty()) {
            database.addSongToPlaylist(playlist, accepted)
            // Para que "Recientes" la ponga arriba la próxima vez.
            database.update(playlist.playlist.copy(lastUpdateTime = LocalDateTime.now()))
        }
        return accepted.size
    }

    LaunchedEffect(Unit) {
        database.playlistsByCreateDateAsc().collect {
            allPlaylists = it
        }
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            songIds = null
            duplicates = emptyMap()
            selectedIds = emptySet()
            addAgainIds = emptySet()
            confirmAgain = null
            saving = false
            query = ""
            searching = false
        }
    }

    // Ids de las canciones, en cuanto hay alguna playlist con la que llamar a onGetSong.
    val firstPlaylist = editable.firstOrNull()
    LaunchedEffect(isVisible, firstPlaylist?.id) {
        if (isVisible && songIds == null && firstPlaylist != null) {
            songIds = withContext(Dispatchers.IO) {
                runCatching { onGetSong(firstPlaylist) }.getOrNull()
            }.orEmpty().distinct()
        }
    }

    // Cuántas de esas canciones ya están en cada playlist.
    LaunchedEffect(songIds, editable) {
        val ids = songIds
        if (ids.isNullOrEmpty()) return@LaunchedEffect
        duplicates = withContext(Dispatchers.IO) {
            editable.associate { playlist ->
                playlist.id to runCatching { database.playlistDuplicates(playlist.id, ids).distinct().size }.getOrDefault(0)
            }.filterValues { it > 0 }
        }
    }

    fun toggle(playlist: Playlist) {
        val id = playlist.id
        if (id in selectedIds) {
            selectedIds = selectedIds - id
            addAgainIds = addAgainIds - id
            return
        }
        val total = songIds?.size ?: 0
        val dup = duplicates[id] ?: 0
        if (total > 0 && dup >= total) {
            confirmAgain = playlist
        } else {
            selectedIds = selectedIds + id
        }
    }

    fun save() {
        if (selectedIds.isEmpty() || saving) return
        saving = true
        coroutineScope.launch {
            val ids = songIds ?: withContext(Dispatchers.IO) {
                val target = editable.firstOrNull { it.id in selectedIds } ?: return@withContext null
                runCatching { onGetSong(target) }.getOrNull()?.distinct()
            }
            if (ids.isNullOrEmpty()) {
                saving = false
                onDismiss()
                return@launch
            }
            val targets = editable.filter { it.id in selectedIds }
            val results = withContext(Dispatchers.IO) {
                targets.map { playlist ->
                    val toAdd = if (playlist.id in addAgainIds) {
                        ids
                    } else {
                        val existing = runCatching { database.playlistDuplicates(playlist.id, ids).toSet() }.getOrDefault(emptySet())
                        ids.filterNot { it in existing }
                    }
                    playlist to addSongsToPlaylistSafely(playlist, toAdd)
                }
            }
            saving = false
            val added = results.filter { it.second > 0 }
            if (added.isNotEmpty()) {
                onAddComplete?.invoke(added.maxOf { it.second }, added.map { it.first.playlist.name })
            }
            onDismiss()
        }
    }

    if (isVisible) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { if (!saving) onDismiss() },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f),
            ) {
                // ── Cabecera ─────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Guardar en…",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val count = songIds?.size
                        val sub = subtitle ?: when {
                            count == null -> "Preparando…"
                            count == 1 -> "1 canción"
                            else -> "$count canciones"
                        }
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }) {
                        Icon(
                            painter = painterResource(if (searching) R.drawable.close else R.drawable.search),
                            contentDescription = if (searching) "Cerrar búsqueda" else "Buscar playlist",
                        )
                    }
                }

                AnimatedVisibility(visible = searching) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Buscar en tus playlists") },
                        leadingIcon = { Icon(painterResource(R.drawable.search), null) },
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }

                // ── Orden / filtro ───────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlaylistSort.entries.forEach { option ->
                        FilterChip(
                            selected = sort == option,
                            onClick = { sort = option },
                            label = { Text(option.label) },
                        )
                    }
                }

                // ── Cuadrícula ───────────────────────────────────────────
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    item(key = "new", contentType = "new") {
                        NewPlaylistTile(onClick = { showCreatePlaylistDialog = true })
                    }
                    items(shown, key = { it.id }, contentType = { "playlist" }) { playlist ->
                        PlaylistTile(
                            playlist = playlist,
                            selected = playlist.id in selectedIds,
                            addAgain = playlist.id in addAgainIds,
                            duplicateCount = duplicates[playlist.id] ?: 0,
                            totalSongs = songIds?.size ?: 0,
                            onClick = { toggle(playlist) },
                        )
                    }
                }

                if (shown.isEmpty()) {
                    Text(
                        text = when {
                            query.isNotBlank() -> "Ninguna playlist se llama así."
                            sort == PlaylistSort.YOUTUBE -> "No tienes playlists de YouTube Music."
                            else -> "Aún no tienes playlists. Crea una con «Nueva»."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }

                // ── Barra inferior ───────────────────────────────────────
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when (selectedIds.size) {
                            0 -> "Elige una o varias"
                            1 -> "1 seleccionada"
                            else -> "${selectedIds.size} seleccionadas"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selectedIds.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (selectedIds.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss, enabled = !saving) {
                        Text("Cancelar")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { save() },
                        enabled = selectedIds.isNotEmpty() && !saving,
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Guardar")
                        }
                    }
                }
            }
        }
    }

    confirmAgain?.let { playlist ->
        val total = songIds?.size ?: 0
        AlertDialog(
            onDismissRequest = { confirmAgain = null },
            title = { Text("¿Añadirla otra vez?") },
            text = {
                Text(
                    if (total == 1) {
                        "Esta canción ya está en «${playlist.playlist.name}». Si sigues, aparecerá dos veces."
                    } else {
                        "Estas canciones ya están en «${playlist.playlist.name}». Si sigues, aparecerán repetidas."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedIds = selectedIds + playlist.id
                    addAgainIds = addAgainIds + playlist.id
                    confirmAgain = null
                }) { Text("Añadir otra vez") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAgain = null }) { Text("Cancelar") }
            },
        )
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            initialTextFieldValue = initialTextFieldValue,
            allowSyncing = allowSyncing,
        )
    }
}

@Composable
private fun NewPlaylistTile(onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick, onClickLabel = "Crear playlist"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .drawBehind {
                    val stroke = 2.dp.toPx()
                    drawRoundRect(
                        color = outline,
                        topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(16.dp.toPx()),
                        style = Stroke(
                            width = stroke,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx())),
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.add),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Text(
            text = "Nueva",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "Crear playlist",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlaylistTile(
    playlist: Playlist,
    selected: Boolean,
    addAgain: Boolean,
    duplicateCount: Int,
    totalSongs: Int,
    onClick: () -> Unit,
) {
    val ring by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
        animationSpec = tween(160),
        label = "playlistTileRing",
    )
    val fully = totalSongs > 0 && duplicateCount >= totalSongs
    val badge = when {
        duplicateCount <= 0 -> null
        addAgain -> "Otra vez"
        fully -> "Ya está"
        else -> "Ya hay $duplicateCount"
    }
    val count = playlist.playlist.remoteSongCount?.takeIf { playlist.songCount == 0 } ?: playlist.songCount
    val state = buildString {
        append(if (selected) "Seleccionada" else "No seleccionada")
        if (badge != null) append(", ").append(badge)
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics {
                this.selected = selected
                stateDescription = state
            },
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .border(width = 3.dp, color = ring, shape = RoundedCornerShape(16.dp))
                .padding(if (selected) 5.dp else 0.dp),
        ) {
            val shape = RoundedCornerShape(if (selected) 12.dp else 16.dp)
            val thumbSize = maxWidth
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                PlaylistThumbnail(
                    thumbnails = playlist.thumbnails,
                    size = thumbSize,
                    placeHolder = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.queue_music),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    },
                    shape = shape,
                )
            }

            if (badge != null) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f),
                    )
                    .border(
                        width = if (selected) 0.dp else 2.dp,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        painter = painterResource(R.drawable.check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Text(
            text = playlist.playlist.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (playlist.playlist.browseId != null) {
                Text(
                    text = "YouTube Music · ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = if (count == 1) "1 canción" else "$count canciones",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = 16.dp),
            )
        }
    }
}
