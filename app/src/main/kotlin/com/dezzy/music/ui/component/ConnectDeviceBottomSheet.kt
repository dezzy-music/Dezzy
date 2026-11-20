/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0
 */

package com.dezzy.music.ui.component

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.connect.ConnectState
import com.dezzy.music.connect.DezzyConnect
import com.dezzy.music.connect.DezzyConnectSession
import com.dezzy.music.connect.DiscoveredDevice
import com.dezzy.music.connect.LinerConnectManager
import com.dezzy.music.constants.CastBetaNoticeShownKey
import com.dezzy.music.playback.cast.CastOptionsProvider
import com.dezzy.music.utils.rememberPreference
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.common.util.concurrent.MoreExecutors
import kotlin.math.roundToInt

/**
 * Hoja «Conectar a un dispositivo» (estilo Connect), rediseñada según
 * design/dispositivos-bocetos.html:
 *  - Arriba, «Escuchando en»: dónde suena la música, por qué salida y su volumen.
 *  - «En tu red»: tus otros Dezzy (Dezzy Connect: sólo allí o en ambos a la vez), PCs con
 *    Liner y Google Cast en una sola lista.
 *  - «Bluetooth y otras salidas»: abre el selector de salida del sistema.
 *  - «Escuchar juntos»: entrada a las salas compartidas.
 * El teléfono NO se pausa hasta que la PC confirma la conexión.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectDeviceBottomSheet(
    onDismissRequest: () -> Unit,
    onOpenListenTogether: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val linerManager = remember { LinerConnectManager.getInstance(context) }

    val connectionState by linerManager.connectionState.collectAsState()
    val connectedDeviceName by linerManager.connectedDeviceName.collectAsState()
    val activeDeviceIp by linerManager.activeDeviceIp.collectAsState()
    val discoveredDevices by linerManager.discoveredDevices.collectAsState()
    val desktopState by linerManager.desktopState.collectAsState()
    val isScanning by linerManager.isScanning.collectAsState()
    val lastError by linerManager.lastError.collectAsState()

    // Dezzy Connect: tus otros Dezzy (PC o móvil) con la misma clave de grupo.
    val dzDevices by DezzyConnect.devices.collectAsState()
    val dzSearching by DezzyConnect.searching.collectAsState()
    val dzRole by DezzyConnectSession.role.collectAsState()
    val dzRemote by DezzyConnectSession.remote.collectAsState()
    val dzBusy by DezzyConnectSession.busy.collectAsState()
    val dzNotice by DezzyConnectSession.notice.collectAsState()
    val dzDrift by DezzyConnectSession.drift.collectAsState()
    val dzMembers by DezzyConnectSession.members.collectAsState()
    val dzHub by DezzyConnectSession.hub.collectAsState()
    val dzControlAll by DezzyConnectSession.controlAll.collectAsState()
    val dzActive = dzRole != null
    val dzCenter = dzRole == DezzyConnectSession.Role.LEADER || dzRole == DezzyConnectSession.Role.REMOTE

    val isConnectedToDesktop = connectionState == ConnectState.CONNECTED
    val isConnecting = connectionState == ConnectState.CONNECTING
    val exactPhoneName = remember(context) { LinerConnectManager.getExactPhoneDeviceName(context) }

    // Google Cast: rutas descubiertas mientras la hoja está abierta.
    val cast = rememberCastRoutes()
    val (castBetaNoticeShown, setCastBetaNoticeShown) = rememberPreference(CastBetaNoticeShownKey, false)
    var pendingCastRoute by remember { mutableStateOf<MediaRouter.RouteInfo?>(null) }

    var lastAttempt by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var showIpDialog by remember { mutableStateOf(false) }

    // Bluetooth dentro de la app: vinculados, conectados y cuál suena ahora.
    val (bt, refreshBt) = rememberBluetoothAudio()
    val btPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshBt()
    }

    // Al abrir la hoja se busca en la red; al cerrarla se detiene la búsqueda.
    DisposableEffect(Unit) {
        linerManager.clearError()
        linerManager.discoverDevices()
        DezzyConnectSession.search()
        onDispose { linerManager.stopDiscovery() }
    }

    fun connectTo(device: DiscoveredDevice) {
        lastAttempt = device
        val meta = playerConnection?.mediaMetadata?.value
        linerManager.connectAndHandoff(
            device = device,
            title = meta?.title,
            artist = meta?.artists?.joinToString(", ") { it.name },
            coverUrl = meta?.thumbnailUrl,
            onHandedOff = { playerConnection?.player?.pause() },
        )
    }

    fun bringBackToPhone() {
        linerManager.disconnect()
        playerConnection?.player?.play()
    }

    val linerDevices = discoveredDevices
    val nothingFound = !isScanning && !dzSearching && linerDevices.none { it.isAvailable } &&
        cast.routes.isEmpty() && dzDevices.none { it.available }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // ── Encabezado ──
            item(key = "header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp),
                ) {
                    Text(
                        text = "Conectar a un dispositivo",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                    )
                    IconButton(
                        onClick = {
                            linerManager.discoverDevices()
                            DezzyConnectSession.search()
                        },
                        enabled = !isScanning && !dzSearching,
                    ) {
                        Icon(painterResource(R.drawable.cached), contentDescription = "Buscar de nuevo")
                    }
                }
                ScanStatusLine(
                    isScanning = isScanning || dzSearching,
                    isConnecting = isConnecting || dzBusy != null,
                    isConnected = isConnectedToDesktop || dzActive,
                    deviceName = connectedDeviceName ?: dzMembers.firstOrNull()?.name ?: dzHub?.name
                        ?: dzDevices.firstOrNull { it.id == dzBusy }?.name ?: lastAttempt?.name,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                )
            }

            // ── Error de conexión ──
            lastError?.let { error ->
                item(key = "error") {
                    ErrorBanner(
                        message = error,
                        onRetry = lastAttempt?.let { device -> { connectTo(device) } },
                        onDismiss = { linerManager.clearError() },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            dzNotice?.let { notice ->
                item(key = "dz_notice") {
                    ErrorBanner(
                        message = notice,
                        onRetry = null,
                        onDismiss = { DezzyConnectSession.clearNotice() },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            // ── Escuchando en ──
            item(key = "now") {
                val role = dzRole
                if (role != null) {
                    DezzyActiveCard(
                        role = role,
                        members = dzMembers,
                        hub = dzHub,
                        remote = dzRemote,
                        drift = dzDrift,
                        phoneName = exactPhoneName,
                        onOpenOutput = { openSystemOutputSwitcher(context) },
                    )
                } else if (isConnectedToDesktop) {
                    NowPlayingOnCard(
                        icon = R.drawable.desktop_windows,
                        title = connectedDeviceName ?: linerManager.lastDeviceName,
                        subtitle = "Dezzy para PC · controlas desde aquí",
                        trailing = {
                            IconButton(onClick = { bringBackToPhone() }) {
                                Icon(painterResource(R.drawable.close), contentDescription = "Desconectar")
                            }
                        },
                        output = null,
                        volume = desktopState?.volume ?: 1f,
                        onVolumeChange = { linerManager.setVolume(it) },
                    )
                } else {
                    val output = rememberAudioOutput()
                    val volume = rememberMusicVolume()
                    NowPlayingOnCard(
                        icon = R.drawable.smartphone,
                        title = exactPhoneName,
                        subtitle = if (isConnecting) "Este teléfono · sigue sonando aquí" else "Este teléfono",
                        trailing = null,
                        output = output,
                        onOutputClick = { openSystemOutputSwitcher(context) },
                        volume = volume.value,
                        onVolumeChange = { volume.value = it },
                    )
                }
            }

            // ── Volver al teléfono (si suena en la PC) ──
            if (isConnectedToDesktop && !dzCenter) {
                item(key = "phone_header") { SectionHeader(R.drawable.smartphone, "Este teléfono") }
                item(key = "phone_row") {
                    DeviceRow(
                        icon = R.drawable.smartphone,
                        title = exactPhoneName,
                        subtitle = "Toca para traer la música aquí",
                        state = DeviceRowState.Idle,
                        onClick = { bringBackToPhone() },
                    )
                }
            }

            // ── Suena en (Dezzy Connect, cuando este teléfono reparte la música) ──
            if (dzCenter && dzMembers.isNotEmpty()) {
                item(key = "dz_playing_header") { SectionHeader(R.drawable.graphic_eq, "Suena en") }
                item(key = "dz_here") {
                    val here = dzRole == DezzyConnectSession.Role.LEADER
                    DeviceRow(
                        icon = R.drawable.smartphone,
                        title = exactPhoneName,
                        subtitle = if (here) "Este teléfono · lleva el ritmo" else "Este teléfono · toca para traer la música sólo aquí",
                        state = if (here) DeviceRowState.Active else DeviceRowState.Idle,
                        // Como en los demás: tocar la fila es «sólo aquí»; la casilla, sumarse o no.
                        onClick = { if (!here) DezzyConnectSession.bringHere() },
                        trailing = {
                            Checkbox(
                                checked = here,
                                onCheckedChange = { DezzyConnectSession.setHere(it) },
                                modifier = Modifier.semantics {
                                    stateDescription = if (here) "Suena en este teléfono" else "No suena en este teléfono"
                                },
                            )
                        },
                    )
                }
                items(items = dzMembers, key = { "dz_member_${it.id}" }) { member ->
                    DezzyMemberRow(member = member)
                }
                item(key = "dz_control") {
                    DezzyControlSelector(
                        controlAll = dzControlAll,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            // ── En tu red ──
            item(key = "net_header") { SectionHeader(R.drawable.wifi, "En tu red") }

            if (nothingFound) {
                item(key = "empty") {
                    EmptyNetworkState(
                        onRetry = { linerManager.discoverDevices() },
                        onConnectByIp = { showIpDialog = true },
                    )
                }
            }

            items(
                items = dzDevices.filterNot { d -> dzMembers.any { it.id == d.id } || d.id == dzHub?.id },
                key = { "dezzy_${it.id}" },
            ) { device ->
                val connectingThis = dzBusy == device.id
                var menuOpen by remember { mutableStateOf(false) }
                val canUse = device.available && dzBusy == null
                DeviceRow(
                    icon = if (device.kind == "phone") R.drawable.smartphone else R.drawable.desktop_windows,
                    title = device.name,
                    subtitle = when {
                        connectingThis -> "Conectando…"
                        !device.available -> "No disponible ahora"
                        else -> "Toca para que suene sólo allí; marca la casilla para sumarlo"
                    },
                    badge = if (device.kind == "phone") "MÓVIL" else "PC",
                    state = when {
                        connectingThis -> DeviceRowState.Connecting
                        !device.available || dzBusy != null -> DeviceRowState.Unavailable
                        else -> DeviceRowState.Idle
                    },
                    onClick = { if (canUse) DezzyConnectSession.moveTo(device.id) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = false,
                                enabled = canUse,
                                onCheckedChange = { DezzyConnectSession.setPlaying(device.id, true) },
                                modifier = Modifier.semantics { stateDescription = "Que suene también en ${device.name}" },
                            )
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(painterResource(R.drawable.more_vert), contentDescription = "Opciones de ${device.name}")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Olvidar este dispositivo") },
                                        leadingIcon = { Icon(painterResource(R.drawable.delete), contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            DezzyConnectSession.forget(device.id)
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }

            items(
                items = linerDevices.filterNot { it.isConnected && isConnectedToDesktop },
                key = { "liner_${it.ip}" },
            ) { device ->
                val connectingThis = isConnecting && activeDeviceIp == device.ip
                var menuOpen by remember { mutableStateOf(false) }
                DeviceRow(
                    icon = R.drawable.desktop_windows,
                    title = device.name,
                    subtitle = when {
                        connectingThis -> "Conectando…"
                        device.isAvailable -> "Dezzy para PC"
                        else -> "No disponible ahora"
                    },
                    badge = "PC",
                    state = when {
                        connectingThis -> DeviceRowState.Connecting
                        !device.isAvailable -> DeviceRowState.Unavailable
                        isConnecting -> DeviceRowState.Unavailable
                        else -> DeviceRowState.Idle
                    },
                    onClick = { connectTo(device) },
                    trailing = {
                        if (connectingThis) {
                            TextButton(onClick = { linerManager.cancelConnect() }) { Text("Cancelar") }
                        } else {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(painterResource(R.drawable.more_vert), contentDescription = "Opciones de ${device.name}")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Olvidar este dispositivo") },
                                        leadingIcon = { Icon(painterResource(R.drawable.delete), contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            linerManager.forgetDevice(device)
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }

            items(items = cast.routes, key = { "cast_${it.id}" }) { route ->
                val selected = route.id == cast.selected?.id
                DeviceRow(
                    icon = if (selected) R.drawable.cast_connected else castRouteIcon(route),
                    title = route.name,
                    subtitle = if (selected) {
                        "Transmitiendo · toca para detener"
                    } else {
                        route.description?.toString()?.takeIf { it.isNotBlank() } ?: "Google Cast"
                    },
                    badge = "CAST",
                    state = when {
                        selected -> DeviceRowState.Active
                        isConnecting -> DeviceRowState.Unavailable
                        else -> DeviceRowState.Idle
                    },
                    onClick = {
                        when {
                            selected -> cast.stop()
                            !castBetaNoticeShown -> pendingCastRoute = route
                            else -> {
                                route.select()
                                onDismissRequest()
                            }
                        }
                    },
                )
            }

            if (!nothingFound) {
                item(key = "by_ip") {
                    TextButton(
                        onClick = { showIpDialog = true },
                        modifier = Modifier.padding(start = 12.dp),
                    ) { Text("Conectar con la IP") }
                }
            }

            // ── Bluetooth y otras salidas ──
            item(key = "bt_header") { SectionHeader(R.drawable.bluetooth, "Bluetooth y otras salidas") }
            when {
                bt.unsupported -> Unit
                bt.needsPermission -> item(key = "bt_permission") {
                    DeviceRow(
                        icon = R.drawable.bluetooth,
                        title = "Ver tus dispositivos Bluetooth",
                        subtitle = "Permite el acceso para elegirlos desde aquí",
                        state = DeviceRowState.Idle,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            }
                        },
                    )
                }
                bt.adapterOff -> item(key = "bt_off") {
                    DeviceRow(
                        icon = R.drawable.bluetooth,
                        title = "Bluetooth apagado",
                        subtitle = "Toca para encenderlo en Ajustes",
                        state = DeviceRowState.Idle,
                        onClick = { openBluetoothSettings(context) },
                    )
                }
                else -> items(items = bt.devices, key = { "bt_${it.address}" }) { device ->
                    DeviceRow(
                        icon = R.drawable.headphones,
                        title = device.name,
                        subtitle = when {
                            device.active -> "Sonando aquí"
                            device.connected -> "Conectado · toca para cambiar la salida"
                            else -> "Vinculado · toca para conectar"
                        },
                        badge = "BT",
                        state = if (device.active) DeviceRowState.Active else DeviceRowState.Idle,
                        // Android no deja a las apps conectar un audio Bluetooth por su cuenta:
                        // el selector de salida del sistema lo conecta y cambia la salida.
                        onClick = { openSystemOutputSwitcher(context) },
                    )
                }
            }
            item(key = "bt_row") {
                DeviceRow(
                    icon = R.drawable.speaker,
                    title = "Más salidas de audio",
                    subtitle = "Abrir el selector del sistema",
                    state = DeviceRowState.Idle,
                    onClick = { openSystemOutputSwitcher(context) },
                    trailing = {
                        Icon(
                            painterResource(R.drawable.navigate_next),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    },
                )
            }

            // ── Escuchar juntos ──
            if (onOpenListenTogether != null) {
                item(key = "together") {
                    ListenTogetherEntry(
                        onClick = onOpenListenTogether,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    )
                }
            }
        }
    }

    if (showIpDialog) {
        ConnectByIpDialog(
            onDismiss = { showIpDialog = false },
            onConnect = { ip, port ->
                showIpDialog = false
                connectTo(DiscoveredDevice(name = ip, ip = ip, port = port))
            },
        )
    }

    pendingCastRoute?.let { route ->
        AlertDialog(
            onDismissRequest = { pendingCastRoute = null },
            icon = { Icon(painterResource(R.drawable.cast), contentDescription = null) },
            title = { Text("Chromecast (Beta)") },
            text = {
                Text(
                    "La transmisión por Chromecast es una función en beta.\n\n" +
                        "• Reproduce en tu TV con letras sincronizadas y fondo dinámico.\n" +
                        "• Controla la reproducción y el volumen desde el teléfono.\n" +
                        "• Si algo no se ve bien, detén la transmisión y vuelve a conectar."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    setCastBetaNoticeShown(true)
                    pendingCastRoute = null
                    route.select()
                    onDismissRequest()
                }) { Text("Transmitir") }
            },
            dismissButton = {
                TextButton(onClick = { pendingCastRoute = null }) { Text("Ahora no") }
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Piezas de la hoja
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ScanStatusLine(
    isScanning: Boolean,
    isConnecting: Boolean,
    isConnected: Boolean,
    deviceName: String?,
    modifier: Modifier = Modifier,
) {
    val text = when {
        isConnecting -> "Conectando con ${deviceName ?: "el dispositivo"}…"
        isConnected -> "Conectado a ${deviceName ?: "tu PC"}"
        isScanning -> "Buscando en tu red…"
        else -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        if (isScanning || isConnecting) PulsingDot() else Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PulsingDot() {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val alpha = if (reduceMotion) 1f else {
        val transition = rememberInfiniteTransition(label = "scan")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
            label = "scanAlpha",
        ).value
    }
    Box(
        Modifier
            .size(10.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
    )
}

@Composable
private fun ErrorBanner(
    message: String,
    onRetry: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Icon(painterResource(R.drawable.error), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text("Reintentar") }
            }
            IconButton(onClick = onDismiss) {
                Icon(painterResource(R.drawable.close), contentDescription = "Cerrar aviso")
            }
        }
    }
}

@Composable
private fun NowPlayingOnCard(
    icon: Int,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)?,
    output: AudioOutput?,
    onOutputClick: () -> Unit = {},
    volume: Float,
    onVolumeChange: (Float) -> Unit,
) {
    val container = MaterialTheme.colorScheme.primaryContainer
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = container,
        contentColor = onContainer,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.graphic_eq), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "ESCUCHANDO EN",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(onContainer.copy(alpha = 0.12f)),
                ) {
                    Icon(painterResource(icon), contentDescription = null)
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) {},
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer.copy(alpha = 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                trailing?.invoke()
            }
            if (output != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 10.dp, end = 8.dp)
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(onContainer.copy(alpha = 0.08f))
                        .clickable(onClick = onOutputClick)
                        .padding(horizontal = 12.dp),
                ) {
                    Icon(painterResource(output.icon), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Salida: ${output.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(painterResource(R.drawable.navigate_next), contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            ) {
                Icon(painterResource(R.drawable.volume_off), contentDescription = null, modifier = Modifier.size(20.dp))
                var local by remember(volume) { mutableFloatStateOf(volume) }
                Slider(
                    value = local,
                    onValueChange = {
                        local = it
                        onVolumeChange(it)
                    },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = onContainer,
                        activeTrackColor = onContainer,
                        inactiveTrackColor = onContainer.copy(alpha = 0.24f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .semantics { stateDescription = "Volumen ${(local * 100).toInt()} %" },
                )
                Icon(painterResource(R.drawable.volume_up), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(icon: Int, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
    }
}

private enum class DeviceRowState { Idle, Active, Connecting, Unavailable }

@Composable
private fun DeviceRow(
    icon: Int,
    title: String,
    subtitle: String,
    state: DeviceRowState,
    onClick: () -> Unit,
    badge: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val active = state == DeviceRowState.Active
    val accent = MaterialTheme.colorScheme.primary
    val rowAlpha = if (state == DeviceRowState.Unavailable) 0.5f else 1f
    val description = when (state) {
        DeviceRowState.Active -> "Conectado"
        DeviceRowState.Connecting -> "Conectando"
        DeviceRowState.Unavailable -> "No disponible"
        DeviceRowState.Idle -> "Disponible"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = state == DeviceRowState.Idle || state == DeviceRowState.Active, onClick = onClick)
            .background(
                if (state == DeviceRowState.Connecting) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            .semantics { stateDescription = description },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .alpha(rowAlpha)
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (active) accent.copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                ),
        ) {
            if (state == DeviceRowState.Connecting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = accent)
            } else {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = if (active) accent else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(
            Modifier
                .weight(1f)
                .alpha(rowAlpha),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .alpha(rowAlpha)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (trailing != null) {
            trailing()
        } else {
            Spacer(Modifier.width(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyNetworkState(onRetry: () -> Unit, onConnectByIp: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Icon(
                painterResource(R.drawable.wifi),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "No encontramos dispositivos",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Revisa esto y vuelve a buscar:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Tip(R.drawable.wifi, "Los dos en la misma red Wi‑Fi")
                Tip(R.drawable.desktop_windows, "Dezzy abierto en la PC o la tele")
                Tip(R.drawable.info, "Las redes de invitados suelen bloquearlo")
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth(),
        ) {
            Button(onClick = onRetry) { Text("Buscar de nuevo") }
            OutlinedButton(onClick = onConnectByIp) { Text("Conectar con la IP") }
        }
    }
}

@Composable
private fun Tip(icon: Int, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ListenTogetherEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary),
            ) {
                Icon(
                    painterResource(R.drawable.group),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondary,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Escuchar juntos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    "Crea una sala o únete con un código",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
            }
            Icon(painterResource(R.drawable.navigate_next), contentDescription = null)
        }
    }
}

@Composable
private fun ConnectByIpDialog(onDismiss: () -> Unit, onConnect: (String, Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = remember(text) { parseHostPort(text.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.desktop_windows), contentDescription = null) },
        title = { Text("Conectar con la IP") },
        text = {
            Column {
                Text(
                    "Escribe la dirección que muestra Dezzy en la PC.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("192.168.1.20") },
                    isError = text.isNotBlank() && parsed == null,
                    supportingText = {
                        if (text.isNotBlank() && parsed == null) Text("Dirección no válida")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = { parsed?.let { (ip, port) -> onConnect(ip, port) } },
            ) { Text("Conectar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** «192.168.1.20» o «192.168.1.20:4884» → (ip, puerto). */
private fun parseHostPort(input: String): Pair<String, Int>? {
    if (input.isBlank()) return null
    val parts = input.split(":")
    if (parts.size > 2) return null
    val host = parts[0]
    val port = parts.getOrNull(1)?.toIntOrNull() ?: if (parts.size == 2) return null else 4884
    if (port !in 1..65535) return null
    val octets = host.split(".")
    val validIpv4 = octets.size == 4 && octets.all { o -> o.toIntOrNull()?.let { it in 0..255 } == true }
    val validHost = Regex("^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$").matches(host)
    return if (validIpv4 || (validHost && octets.size != 4)) host to port else null
}

// ─────────────────────────────────────────────────────────────────────────────
// Salida de audio, volumen y Cast
// ─────────────────────────────────────────────────────────────────────────────

private data class AudioOutput(val name: String, val icon: Int)

private val BLUETOOTH_TYPES = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    26, // TYPE_BLE_HEADSET (API 31)
    27, // TYPE_BLE_SPEAKER (API 31)
    30, // TYPE_BLE_BROADCAST (API 33)
)

private fun describeOutput(device: AudioDeviceInfo): AudioOutput? = when (device.type) {
    in BLUETOOTH_TYPES -> AudioOutput(
        device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth",
        R.drawable.headphones,
    )
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioOutput("Audífonos con cable", R.drawable.headphones)
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE -> AudioOutput(
        device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Audio USB",
        R.drawable.headphones,
    )
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioOutput("Altavoz del teléfono", R.drawable.speaker)
    else -> null
}

private fun currentAudioOutput(context: Context): AudioOutput {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        runCatching { am.getAudioDevicesForAttributes(attrs) }.getOrNull()
            ?.firstNotNullOfOrNull { describeOutput(it) }
            ?.let { return it }
    }
    // Versiones anteriores: la salida con más prioridad entre las conectadas.
    val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val priority = listOf(
        { d: AudioDeviceInfo -> d.type in BLUETOOTH_TYPES },
        { d: AudioDeviceInfo -> d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET },
        { d: AudioDeviceInfo -> d.type == AudioDeviceInfo.TYPE_USB_HEADSET || d.type == AudioDeviceInfo.TYPE_USB_DEVICE },
    )
    for (match in priority) {
        outputs.firstOrNull(match)?.let { d -> describeOutput(d)?.let { return it } }
    }
    return AudioOutput("Altavoz del teléfono", R.drawable.speaker)
}

/** Salida de audio actual; se actualiza al conectar o desconectar audífonos. */
@Composable
private fun rememberAudioOutput(): AudioOutput {
    val context = LocalContext.current
    var output by remember { mutableStateOf(currentAudioOutput(context)) }
    DisposableEffect(context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                output = currentAudioOutput(context)
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                output = currentAudioOutput(context)
            }
        }
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { am.unregisterAudioDeviceCallback(callback) }
    }
    return output
}

// ── Bluetooth dentro de la app ───────────────────────────────────────────────

private data class BtAudioDevice(
    val name: String,
    val address: String,
    val connected: Boolean,
    val active: Boolean,
)

private data class BtAudioState(
    val unsupported: Boolean = false,
    val needsPermission: Boolean = false,
    val adapterOff: Boolean = false,
    val devices: List<BtAudioDevice> = emptyList(),
)

private fun hasBluetoothConnectPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

/**
 * Dispositivos de audio vinculados (auriculares, bocinas, coche…) y cuáles están conectados.
 * «Conectado» sale de las salidas de audio actuales; «activo» es el que suena ahora.
 */
@SuppressLint("MissingPermission") // comprobado con hasBluetoothConnectPermission
private fun readBluetoothAudio(context: Context): BtAudioState {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        ?: return BtAudioState(unsupported = true)
    if (!hasBluetoothConnectPermission(context)) return BtAudioState(needsPermission = true)
    if (!runCatching { adapter.isEnabled }.getOrDefault(false)) return BtAudioState(adapterOff = true)

    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val btOutputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in BLUETOOTH_TYPES }
    val connectedAddresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        btOutputs.mapNotNull { it.address?.takeIf { a -> a.isNotBlank() }?.uppercase() }.toSet()
    } else emptySet()
    val connectedNames = btOutputs.mapNotNull { it.productName?.toString()?.takeIf { n -> n.isNotBlank() } }.toSet()
    val activeName = currentAudioOutput(context).name

    val bonded = runCatching { adapter.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
    return BtAudioState(
        devices = bonded.mapNotNull { d ->
            val name = runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val address = d.address.uppercase()
            val connected = address in connectedAddresses || name in connectedNames
            val cls = runCatching { d.bluetoothClass }.getOrNull()
            val isAudio = cls != null && (
                cls.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                    cls.hasService(BluetoothClass.Service.AUDIO) ||
                    cls.hasService(BluetoothClass.Service.RENDER)
                )
            if (!isAudio && !connected) return@mapNotNull null
            BtAudioDevice(name = name, address = address, connected = connected, active = connected && name == activeName)
        }.sortedWith(
            compareByDescending<BtAudioDevice> { it.active }
                .thenByDescending { it.connected }
                .thenBy { it.name.lowercase() }
        ),
    )
}

/** Estado de Bluetooth que se refresca solo al conectar, desconectar, vincular o apagar. */
@Composable
private fun rememberBluetoothAudio(): Pair<BtAudioState, () -> Unit> {
    val context = LocalContext.current
    var state by remember { mutableStateOf(readBluetoothAudio(context)) }
    val refresh: () -> Unit = remember(context) { { state = readBluetoothAudio(context) } }
    DisposableEffect(context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
        }
        am.registerAudioDeviceCallback(audioCallback, Handler(Looper.getMainLooper()))
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = refresh()
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            am.unregisterAudioDeviceCallback(audioCallback)
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return state to refresh
}

private fun openBluetoothSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Volumen multimedia del teléfono (0..1), leído y escrito en STREAM_MUSIC. */
@Composable
private fun rememberMusicVolume(): MutableState<Float> {
    val context = LocalContext.current
    val am = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember(am) { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    val state = remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) }
    return remember(state) {
        object : MutableState<Float> {
            override var value: Float
                get() = state.floatValue
                set(v) {
                    state.floatValue = v
                    val index = (v * max).toInt().coerceIn(0, max)
                    if (index != am.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
                    }
                }
            override fun component1() = value
            override fun component2(): (Float) -> Unit = { value = it }
        }
    }
}

/**
 * Abre el selector de salida de audio del sistema: el de Android 14+, el panel
 * «Salida multimedia» en Android 11–13, o los ajustes de Bluetooth como último recurso.
 */
private fun openSystemOutputSwitcher(context: Context) {
    if (Build.VERSION.SDK_INT >= 34) {
        val shown = runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
        if (shown) return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val panel = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
            .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(panel) }.isSuccess) return
    }
    runCatching {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private class CastRoutes(
    val routes: List<MediaRouter.RouteInfo>,
    val selected: MediaRouter.RouteInfo?,
    val stop: () -> Unit,
)

/** Icono según el tipo de aparato Cast: tele, altavoz o el genérico de Cast. */
private fun castRouteIcon(route: MediaRouter.RouteInfo): Int = when (route.deviceType) {
    MediaRouter.RouteInfo.DEVICE_TYPE_TV -> R.drawable.tv
    MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER,
    MediaRouter.RouteInfo.DEVICE_TYPE_GROUP -> R.drawable.speaker
    else -> R.drawable.cast
}

/** Rutas de Google Cast con búsqueda activa mientras la hoja está abierta. */
@Composable
private fun rememberCastRoutes(): CastRoutes {
    val context = LocalContext.current
    val mediaRouter = remember { MediaRouter.getInstance(context.applicationContext) }
    val selector = remember {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastOptionsProvider.APP_ID))
            .build()
    }
    var routes by remember { mutableStateOf(listOf<MediaRouter.RouteInfo>()) }
    var selected by remember { mutableStateOf<MediaRouter.RouteInfo?>(null) }

    fun refresh() {
        routes = mediaRouter.routes.filter { !it.isDefault && it.matchesSelector(selector) && it.isEnabled }
        selected = mediaRouter.selectedRoute.takeIf { !it.isDefault && it.matchesSelector(selector) }
    }

    DisposableEffect(Unit) {
        runCatching { CastContext.getSharedInstance(context.applicationContext, MoreExecutors.directExecutor()) }
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
        }
        mediaRouter.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
        onDispose { mediaRouter.removeCallback(callback) }
    }

    return CastRoutes(
        routes = routes,
        selected = selected,
        stop = { mediaRouter.unselect(MediaRouter.UNSELECT_REASON_STOPPED) },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Dezzy Connect (tus otros Dezzy en la red de casa)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DezzyActiveCard(
    role: DezzyConnectSession.Role,
    members: List<DezzyConnectSession.Member>,
    hub: DezzyConnectSession.Hub?,
    remote: DezzyConnectSession.Playback?,
    drift: Int?,
    phoneName: String,
    onOpenOutput: () -> Unit,
) {
    Column { when (role) {
        DezzyConnectSession.Role.REMOTE -> {
            val host = members.firstOrNull { it.host }
            val track = remote?.track
            NowPlayingOnCard(
                icon = if (host?.kind == "phone") R.drawable.smartphone else R.drawable.desktop_windows,
                title = host?.name ?: "Otro dispositivo",
                subtitle = track?.let { "${it.title} · ${it.artists}".trimEnd(' ', '·') }
                    ?: "Dezzy · controlas desde aquí",
                trailing = {
                    IconButton(onClick = { DezzyConnectSession.bringHere() }) {
                        Icon(painterResource(R.drawable.close), contentDescription = "Traer la música aquí y desconectar")
                    }
                },
                output = null,
                volume = remote?.vol?.toFloat() ?: 1f,
                onVolumeChange = { DezzyConnectSession.remoteVolume(it) },
            )
            DezzyRemoteControls(
                playing = remote?.playing == true,
                enabled = remote != null,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        DezzyConnectSession.Role.LEADER -> {
            val output = rememberAudioOutput()
            val volume = rememberMusicVolume()
            NowPlayingOnCard(
                icon = R.drawable.smartphone,
                title = phoneName,
                subtitle = "Suena aquí y en ${members.joinToString(", ") { it.name }}",
                trailing = null,
                output = output,
                onOutputClick = onOpenOutput,
                volume = volume.value,
                onVolumeChange = { volume.value = it },
            )
        }
        DezzyConnectSession.Role.FOLLOWER -> {
            val output = rememberAudioOutput()
            val volume = rememberMusicVolume()
            NowPlayingOnCard(
                icon = R.drawable.smartphone,
                title = phoneName,
                subtitle = hub?.let { "Suena a la vez que ${it.name}" } ?: "Suena a la vez",
                trailing = null,
                output = output,
                onOutputClick = onOpenOutput,
                volume = volume.value,
                onVolumeChange = { volume.value = it },
            )
            DezzyControlHint(hub = hub, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            DezzySyncCard(drift = drift, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            TextButton(
                onClick = { DezzyConnectSession.stopHere() },
                modifier = Modifier.padding(start = 12.dp),
            ) { Text("Dejar de sonar en este teléfono") }
        }
        DezzyConnectSession.Role.HOST -> {
            val output = rememberAudioOutput()
            val volume = rememberMusicVolume()
            NowPlayingOnCard(
                icon = R.drawable.smartphone,
                title = phoneName,
                subtitle = hub?.let { "Lo controla ${it.name}" } ?: "Lo controla otro dispositivo",
                trailing = {
                    IconButton(onClick = { DezzyConnectSession.release() }) {
                        Icon(painterResource(R.drawable.close), contentDescription = "Dejar de compartir el control")
                    }
                },
                output = output,
                onOutputClick = onOpenOutput,
                volume = volume.value,
                onVolumeChange = { volume.value = it },
            )
        }
    } }
}

/** Quién puede tocar la música, visto desde un dispositivo que sigue a otro. */
@Composable
private fun DezzyControlHint(hub: DezzyConnectSession.Hub?, modifier: Modifier = Modifier) {
    hub ?: return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Icon(
                painterResource(if (hub.controlAll) R.drawable.group else R.drawable.person),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (hub.controlAll) {
                    "Puedes pausar, saltar o mover la canción desde aquí; manda el último que toca."
                } else {
                    "Sólo ${hub.name} puede cambiar la música. Aquí puedes cambiar tu volumen."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DezzyRemoteControls(playing: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth(),
    ) {
        IconButton(onClick = { DezzyConnectSession.remotePrev() }, enabled = enabled) {
            Icon(painterResource(R.drawable.skip_previous), contentDescription = "Anterior")
        }
        FilledIconButton(
            onClick = { DezzyConnectSession.remoteToggle() },
            enabled = enabled,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                painterResource(if (playing) R.drawable.pause else R.drawable.play),
                contentDescription = if (playing) "Pausar" else "Reproducir",
            )
        }
        IconButton(onClick = { DezzyConnectSession.remoteNext() }, enabled = enabled) {
            Icon(painterResource(R.drawable.skip_next), contentDescription = "Siguiente")
        }
    }
}

/** Un dispositivo que suena con nosotros: casilla, volumen y, si sigue el paso, su sincronía. */
@Composable
private fun DezzyMemberRow(member: DezzyConnectSession.Member) {
    var showSync by remember { mutableStateOf(false) }
    Column {
        DeviceRow(
            icon = if (member.kind == "phone") R.drawable.smartphone else R.drawable.desktop_windows,
            title = member.name,
            subtitle = when {
                member.host -> "Suena aquí · lo controlas desde este teléfono"
                member.drift != null -> "Sincronizado · ${member.drift} ms"
                else -> "Sincronizando…"
            },
            state = DeviceRowState.Active,
            // Tocar la fila: que suene sólo allí (como en Spotify).
            onClick = { DezzyConnectSession.moveTo(member.id) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!member.host) {
                        IconButton(onClick = { showSync = !showSync }) {
                            Icon(painterResource(R.drawable.sync), contentDescription = "Sincronía con ${member.name}")
                        }
                    }
                    Checkbox(
                        checked = true,
                        onCheckedChange = { DezzyConnectSession.setPlaying(member.id, false) },
                        modifier = Modifier.semantics { stateDescription = "Suena en ${member.name}; toca para quitarlo" },
                    )
                }
            },
        )
        val volume = member.volume
        if (volume != null || member.host) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 72.dp, end = 24.dp),
            ) {
                Icon(
                    painterResource(R.drawable.volume_up),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                var value by remember { mutableFloatStateOf(volume ?: 1f) }
                LaunchedEffect(volume) { volume?.let { value = it } }
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = { DezzyConnectSession.setMemberVolume(member.id, value) },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { stateDescription = "Volumen de ${member.name} ${(value * 100).toInt()} %" },
                )
            }
        }
        if (showSync && !member.host) {
            DezzyOffsetCard(
                member = member,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/** «Quién puede controlar»: todos los conectados o sólo este móvil. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DezzyControlSelector(controlAll: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            "Quién puede controlar",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 8.dp, bottom = 6.dp)
                .semantics { heading() },
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = controlAll,
                onClick = { DezzyConnectSession.setControlAll(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = { SegmentedButtonDefaults.Icon(controlAll) { Icon(painterResource(R.drawable.group), null, Modifier.size(18.dp)) } },
            ) { Text("Todos") }
            SegmentedButton(
                selected = !controlAll,
                onClick = { DezzyConnectSession.setControlAll(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = { SegmentedButtonDefaults.Icon(!controlAll) { Icon(painterResource(R.drawable.person), null, Modifier.size(18.dp)) } },
            ) { Text("Sólo este teléfono") }
        }
        Text(
            if (controlAll) {
                "Cualquiera puede pausar, saltar o mover la canción. Manda el último que toca."
            } else {
                "Los demás sólo escuchan y cambian su propio volumen."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, top = 6.dp),
        )
    }
}

/** Cómo va el paso de este teléfono respecto al que reparte. */
@Composable
private fun DezzySyncCard(drift: Int?, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Icon(painterResource(R.drawable.sync), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("Sincronía", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            DriftChip(drift)
        }
    }
}

@Composable
private fun DriftChip(drift: Int?) {
    val warn = drift != null && kotlin.math.abs(drift) > 40
    Surface(
        shape = CircleShape,
        color = if (warn) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (warn) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = drift?.let { "${if (it > 0) "+" else ""}$it ms" } ?: "Midiendo…",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

/** Ajuste fino de sincronía de un dispositivo (se abre tocando su fila). */
@Composable
private fun DezzyOffsetCard(member: DezzyConnectSession.Member, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.sync), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Sincronía con ${member.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                DriftChip(member.drift)
            }
            var value by remember { mutableFloatStateOf(member.offset.toFloat()) }
            LaunchedEffect(member.offset) { value = member.offset.toFloat() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ajuste", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = value,
                    onValueChange = { value = (it / 10f).roundToInt() * 10f },
                    onValueChangeFinished = { DezzyConnectSession.setOffset(member.id, value.roundToInt()) },
                    valueRange = -300f..300f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .semantics { stateDescription = "Ajuste de sincronía ${value.roundToInt()} milisegundos" },
                )
                Text(
                    "${value.roundToInt()} ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 56.dp),
                    textAlign = TextAlign.End,
                )
            }
            Text(
                "Si oyes eco, mueve el ajuste hasta que desaparezca.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
