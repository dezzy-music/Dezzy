/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.R
import com.dezzy.music.listentogether.ActivityItem
import com.dezzy.music.listentogether.ListenTogetherClient
import com.dezzy.music.listentogether.ListenerStatus
import com.dezzy.music.listentogether.RoomDashboardPayload
import com.dezzy.music.listentogether.RoomRole
import com.dezzy.music.listentogether.SuggestionItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/** Desfase por debajo del cual un oyente cuenta como «al día». */
private const val IN_SYNC_MS = 800L
/** A partir de aquí el desfase se pinta como problema. */
private const val BAD_DRIFT_MS = 3_000L
/** Informe más viejo que esto = sin señal (igual que REPORT_STALE_MS del servidor). */
private const val STALE_REPORT_MS = 15_000L

/**
 * Panel de la sala (Listen Together online, servidor v2.3).
 *
 * - Anfitrión: estado en vivo, cómo va cada oyente respecto a él, sugerencias con votos,
 *   permisos (entrada automática por votos) y la actividad reciente.
 * - Invitado: la misma pantalla se reduce a las sugerencias para votarlas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenTogetherDashboardScreen(
    manager: ListenTogetherClient,
    onBack: () -> Unit,
) {
    val dashboard by manager.dashboard.collectAsState()
    val suggestions by manager.suggestions.collectAsState()
    val roomState by manager.roomState.collectAsState()
    val role by manager.role.collectAsState()
    val myId by manager.userId.collectAsState()

    val isHost = role == RoomRole.HOST
    val inRoom = roomState != null
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resyncDone = stringResource(R.string.lt_dash_resync_done)

    // Anfitrión: pedir el panel al abrir y luego cada 5 s, por si la sala está parada y
    // no llegan informes que lo disparen.
    LaunchedEffect(isHost, inRoom) {
        if (!isHost || !inRoom) return@LaunchedEffect
        while (true) {
            manager.requestDashboard()
            delay(5_000L)
        }
    }

    // Reloj local para que las barras avancen suaves entre fotos del servidor (1/s como mucho).
    val receivedAt = remember(dashboard) { SystemClock.elapsedRealtime() }
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(500L)
        }
    }
    val sinceUpdate = (now - receivedAt).coerceAtLeast(0L)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(if (isHost) R.string.lt_dash_title else R.string.lt_dash_title_guest),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        roomState?.roomCode?.takeIf { it.isNotBlank() }?.let { code ->
                            Text(
                                text = stringResource(R.string.lt_dash_room_code, code),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = stringResource(R.string.lt_dash_back))
                    }
                },
                actions = {
                    if (isHost && inRoom) {
                        IconButton(onClick = {
                            manager.resyncAll()
                            scope.launch { snackbar.showSnackbar(resyncDone) }
                        }) {
                            Icon(painterResource(R.drawable.sync), contentDescription = stringResource(R.string.lt_dash_resync))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // Incluye el minirreproductor de abajo para que la lista no quede tapada.
        contentWindowInsets = LocalPlayerAwareWindowInsets.current,
    ) { inner ->
        when {
            !inRoom -> CenteredMessage(
                title = stringResource(R.string.lt_dash_not_in_room),
                body = stringResource(R.string.lt_dash_not_in_room_desc),
                modifier = Modifier.padding(inner),
            )

            isHost && dashboard == null -> CenteredMessage(
                title = stringResource(R.string.lt_dash_waiting),
                body = stringResource(R.string.lt_dash_waiting_desc),
                loading = true,
                modifier = Modifier.padding(inner),
            )

            else -> {
                val d = dashboard
                val durationMs = roomState?.currentTrack?.duration ?: 0L
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = inner.calculateTopPadding() + 8.dp,
                        bottom = inner.calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (isHost && d != null) {
                        item(key = "live") {
                            LiveCard(
                                dashboard = d,
                                title = roomState?.currentTrack?.title.orEmpty(),
                                artist = roomState?.currentTrack?.artist.orEmpty(),
                                thumbnail = roomState?.currentTrack?.thumbnail,
                                suggestionCount = suggestions.size,
                                sinceUpdate = sinceUpdate,
                            )
                        }

                        item(key = "h_listeners") { SectionHeader(stringResource(R.string.lt_dash_section_listeners)) }
                        val hostPos = d.hostPosition + if (d.isPlaying) sinceUpdate else 0L
                        items(d.listeners, key = { "l_" + it.userId }) { s ->
                            val pos = s.position + if (d.isPlaying && s.isPlaying && !s.buffering) sinceUpdate else 0L
                            ListenerRow(
                                status = s,
                                isMe = s.userId == myId,
                                roomTrackId = d.trackId,
                                durationMs = durationMs,
                                positionMs = if (s.isHost) hostPos else pos,
                                hostPositionMs = hostPos,
                                canManage = !s.isHost && s.userId != myId,
                                onMakeHost = { manager.transferHost(s.userId) },
                                onKick = { manager.kickUser(s.userId) },
                            )
                        }
                    } else if (!isHost) {
                        item(key = "guest_note") {
                            Text(
                                text = stringResource(R.string.lt_dash_guest_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    item(key = "h_suggestions") { SectionHeader(stringResource(R.string.lt_dash_section_suggestions)) }
                    if (suggestions.isEmpty()) {
                        item(key = "no_suggestions") {
                            EmptyBlock(
                                title = stringResource(R.string.lt_dash_no_suggestions),
                                body = stringResource(R.string.lt_dash_no_suggestions_desc),
                            )
                        }
                    } else {
                        items(suggestions, key = { "s_" + it.suggestionId }) { sg ->
                            SuggestionRow(
                                item = sg,
                                isHost = isHost,
                                hasMyVote = myId != null && myId in sg.voters,
                                onApprove = { manager.approveSuggestion(sg.suggestionId) },
                                onReject = { manager.rejectSuggestion(sg.suggestionId) },
                                onVote = { vote -> manager.voteSuggestion(sg.suggestionId, vote) },
                            )
                        }
                    }

                    if (isHost && d != null) {
                        item(key = "h_permissions") { SectionHeader(stringResource(R.string.lt_dash_section_permissions)) }
                        item(key = "permissions") {
                            AutoQueueSetting(
                                current = roomState?.settings?.autoQueueVotes,
                                onChange = { manager.setAutoQueueVotes(it) },
                            )
                        }

                        item(key = "h_activity") { SectionHeader(stringResource(R.string.lt_dash_section_activity)) }
                        val recent = d.activity.asReversed().take(20)
                        if (recent.isEmpty()) {
                            item(key = "no_activity") {
                                Text(
                                    text = stringResource(R.string.lt_dash_no_activity),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            itemsIndexed(recent, key = { i, a -> "a_${a.at}_$i" }) { _, a ->
                                ActivityRow(item = a, agoMs = (d.serverTime - a.at).coerceAtLeast(0L) + sinceUpdate)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────────────────────────────────────────────── piezas

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(top = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun CenteredMessage(title: String, body: String, modifier: Modifier = Modifier, loading: Boolean = false) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) CircularProgressIndicator()
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyBlock(title: String, body: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveCard(
    dashboard: RoomDashboardPayload,
    title: String,
    artist: String,
    thumbnail: String?,
    suggestionCount: Int,
    sinceUpdate: Long,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (thumbnail != null) {
                    AsyncImage(
                        model = thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                    )
                }
                Column(Modifier.weight(1f)) {
                    StatusPill(
                        text = stringResource(if (dashboard.isPlaying) R.string.lt_dash_live else R.string.lt_dash_paused),
                        container = if (dashboard.isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        content = if (dashboard.isPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (title.isNotBlank()) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (artist.isNotBlank()) {
                        Text(artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            val connected = dashboard.listeners.count { it.isConnected }
            val openMin = ((dashboard.serverTime - dashboard.openedAt + sinceUpdate) / 60_000L).coerceAtLeast(0L).toInt()
            FlowRow(
                maxItemsInEachRow = 2,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Tile(
                    label = stringResource(R.string.lt_dash_tile_listeners),
                    value = connected.toString(),
                    extra = stringResource(R.string.lt_dash_tile_peak, dashboard.peakListeners),
                    modifier = Modifier.weight(1f),
                )
                Tile(
                    label = stringResource(R.string.lt_dash_tile_drift),
                    value = dashboard.avgDriftMs?.let { formatSeconds(abs(it)) + " s" } ?: "—",
                    modifier = Modifier.weight(1f),
                )
                Tile(
                    label = stringResource(R.string.lt_dash_tile_suggestions),
                    value = suggestionCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                Tile(
                    label = stringResource(R.string.lt_dash_tile_open),
                    value = if (openMin < 60) stringResource(R.string.lt_dash_minutes, openMin)
                    else stringResource(R.string.lt_dash_hours, openMin / 60, openMin % 60),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier, extra: String? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.heightIn(min = 72.dp).semantics(mergeDescendants = true) {},
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            if (extra != null) {
                Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = CircleShape) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** Cómo va un oyente respecto al anfitrión. */
private sealed interface SyncStatus {
    data object Host : SyncStatus
    data object InSync : SyncStatus
    data class Drift(val ms: Long) : SyncStatus
    data object Buffering : SyncStatus
    data object OtherTrack : SyncStatus
    data object NoSignal : SyncStatus
    data object NoData : SyncStatus
    data object Disconnected : SyncStatus
}

private fun syncStatusOf(s: ListenerStatus, roomTrackId: String): SyncStatus {
    val age = s.reportAgeMs
    val drift = s.driftMs
    return when {
        !s.isConnected -> SyncStatus.Disconnected
        s.isHost -> SyncStatus.Host
        age == null -> SyncStatus.NoData
        age > STALE_REPORT_MS -> SyncStatus.NoSignal
        s.buffering -> SyncStatus.Buffering
        s.trackId.isNotEmpty() && roomTrackId.isNotEmpty() && s.trackId != roomTrackId -> SyncStatus.OtherTrack
        drift == null -> SyncStatus.NoData
        abs(drift) <= IN_SYNC_MS -> SyncStatus.InSync
        else -> SyncStatus.Drift(drift)
    }
}

private fun formatSeconds(ms: Long): String = String.format(Locale.getDefault(), "%.1f", ms / 1000.0)

@Composable
private fun ListenerRow(
    status: ListenerStatus,
    isMe: Boolean,
    roomTrackId: String,
    durationMs: Long,
    positionMs: Long,
    hostPositionMs: Long,
    canManage: Boolean,
    onMakeHost: () -> Unit,
    onKick: () -> Unit,
) {
    val sync = syncStatusOf(status, roomTrackId)
    val scheme = MaterialTheme.colorScheme
    val (chipText, chipContainer, chipContent) = when (sync) {
        SyncStatus.Host -> Triple(stringResource(R.string.lt_dash_host), scheme.primary, scheme.onPrimary)
        SyncStatus.InSync -> Triple(stringResource(R.string.lt_dash_in_sync), scheme.primaryContainer, scheme.onPrimaryContainer)
        is SyncStatus.Drift -> {
            val txt = if (sync.ms > 0) stringResource(R.string.lt_dash_ahead, formatSeconds(sync.ms))
            else stringResource(R.string.lt_dash_behind, formatSeconds(-sync.ms))
            if (abs(sync.ms) >= BAD_DRIFT_MS) Triple(txt, scheme.errorContainer, scheme.onErrorContainer)
            else Triple(txt, scheme.tertiaryContainer, scheme.onTertiaryContainer)
        }
        SyncStatus.Buffering -> Triple(stringResource(R.string.lt_dash_buffering), scheme.secondaryContainer, scheme.onSecondaryContainer)
        SyncStatus.OtherTrack -> Triple(stringResource(R.string.lt_dash_other_track), scheme.errorContainer, scheme.onErrorContainer)
        SyncStatus.NoSignal -> Triple(stringResource(R.string.lt_dash_no_signal), scheme.surfaceVariant, scheme.onSurfaceVariant)
        SyncStatus.NoData -> Triple(stringResource(R.string.lt_dash_no_data), scheme.surfaceVariant, scheme.onSurfaceVariant)
        SyncStatus.Disconnected -> Triple(stringResource(R.string.lt_dash_disconnected), scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
    val network = when (status.network) {
        "wifi" -> stringResource(R.string.lt_dash_net_wifi)
        "cellular" -> stringResource(R.string.lt_dash_net_cellular)
        "ethernet" -> stringResource(R.string.lt_dash_net_ethernet)
        else -> null
    }
    val rtt = status.rttMs.takeIf { it > 0 }?.let { stringResource(R.string.lt_dash_rtt, it.toInt()) }
    val meta = listOfNotNull(network, rtt).joinToString(", ")
    val name = if (isMe) "${status.username} (${stringResource(R.string.lt_dash_you)})" else status.username
    val a11y = stringResource(R.string.lt_dash_listener_progress, name, chipText)

    var menuOpen by remember { mutableStateOf(false) }
    var confirmKick by remember { mutableStateOf(false) }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clearAndSetSemantics { contentDescription = if (meta.isEmpty()) a11y else "$a11y, $meta" },
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(scheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = status.username.trim().take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSecondaryContainer,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (meta.isNotEmpty()) {
                            Text(meta, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    StatusPill(chipText, chipContainer, chipContent)
                }
                if (canManage) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.more_vert), contentDescription = stringResource(R.string.lt_dash_more_options, status.username))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.lt_dash_make_host)) },
                                leadingIcon = { Icon(painterResource(R.drawable.swap_horiz), null) },
                                onClick = { menuOpen = false; onMakeHost() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.lt_dash_kick)) },
                                leadingIcon = { Icon(painterResource(R.drawable.kick), null) },
                                onClick = { menuOpen = false; confirmKick = true },
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }

            if (durationMs > 0 && status.isConnected) {
                Spacer(Modifier.height(10.dp))
                ProgressWithHostMarker(
                    fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                    hostFraction = (hostPositionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                    showMarker = !status.isHost,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
    }

    if (confirmKick) {
        AlertDialog(
            onDismissRequest = { confirmKick = false },
            title = { Text(stringResource(R.string.lt_dash_kick_confirm_title, status.username)) },
            text = { Text(stringResource(R.string.lt_dash_kick_confirm_text), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { confirmKick = false; onKick() }) {
                    Text(stringResource(R.string.lt_dash_kick), color = scheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmKick = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Barra de progreso del oyente con una marca vertical donde va el anfitrión. */
@Composable
private fun ProgressWithHostMarker(fraction: Float, hostFraction: Float, showMarker: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(14.dp)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.CenterStart,
    ) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            trackColor = scheme.surfaceVariant,
        )
        if (showMarker) {
            Box(
                Modifier
                    .offset(x = (maxWidth * hostFraction - 1.5.dp).coerceIn(0.dp, maxWidth - 3.dp))
                    .width(3.dp)
                    .height(14.dp)
                    .clip(CircleShape)
                    .background(scheme.onSurface),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionRow(
    item: SuggestionItem,
    isHost: Boolean,
    hasMyVote: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onVote: (Boolean) -> Unit,
) {
    val track = item.trackInfo
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.semantics(mergeDescendants = true) {},
            ) {
                if (track?.thumbnail != null) {
                    AsyncImage(
                        model = track.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(track?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!track?.artist.isNullOrBlank()) {
                        Text(track?.artist.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (item.fromUsername.isNotBlank()) {
                        Text(
                            stringResource(R.string.lt_dash_suggested_by, item.fromUsername),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                StatusPill(
                    text = pluralStringResource(R.plurals.lt_dash_votes, item.votes, item.votes),
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isHost) {
                    FilledTonalButton(onClick = onApprove) {
                        Icon(painterResource(R.drawable.playlist_add), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.lt_dash_add_to_queue))
                    }
                    TextButton(onClick = onReject) {
                        Text(stringResource(R.string.lt_dash_dismiss))
                    }
                } else {
                    FilterChip(
                        selected = hasMyVote,
                        onClick = { onVote(!hasMyVote) },
                        label = { Text(stringResource(if (hasMyVote) R.string.lt_dash_voted else R.string.lt_dash_vote)) },
                        leadingIcon = { Icon(painterResource(R.drawable.thumbup), null, Modifier.size(18.dp)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoQueueSetting(current: Int?, onChange: (Int) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.lt_dash_auto_queue), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(if (current == null) R.string.lt_dash_auto_queue_unavailable else R.string.lt_dash_auto_queue_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 2, 3, 5).forEach { n ->
                    FilterChip(
                        selected = current == n,
                        enabled = current != null,
                        onClick = { onChange(n) },
                        label = { Text(if (n == 0) stringResource(R.string.lt_dash_auto_queue_off) else n.toString()) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(item: ActivityItem, agoMs: Long) {
    val who = item.username.ifBlank { "—" }
    val text = when (item.kind) {
        "join" -> stringResource(R.string.lt_dash_act_join, who)
        "leave" -> stringResource(R.string.lt_dash_act_leave, who)
        "disconnect" -> stringResource(R.string.lt_dash_act_disconnect, who)
        "kick" -> stringResource(R.string.lt_dash_act_kick, who)
        "suggest" -> stringResource(R.string.lt_dash_act_suggest, who, item.detail)
        "vote" -> stringResource(R.string.lt_dash_act_vote, who, item.detail)
        "approve" -> stringResource(R.string.lt_dash_act_approve, item.detail)
        "auto_queue" -> stringResource(R.string.lt_dash_act_auto_queue, item.detail)
        "reject" -> stringResource(R.string.lt_dash_act_reject)
        "host_changed" -> stringResource(R.string.lt_dash_act_host_changed, who)
        "resync" -> stringResource(R.string.lt_dash_act_resync)
        "storm_cut" -> stringResource(R.string.lt_dash_act_storm_cut)
        else -> stringResource(R.string.lt_dash_act_other, item.kind)
    }
    val ago = when {
        agoMs < 10_000L -> stringResource(R.string.lt_dash_just_now)
        agoMs < 60_000L -> stringResource(R.string.lt_dash_ago_s, (agoMs / 1000L).toInt())
        else -> stringResource(R.string.lt_dash_ago_m, (agoMs / 60_000L).toInt())
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) {},
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(ago, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
