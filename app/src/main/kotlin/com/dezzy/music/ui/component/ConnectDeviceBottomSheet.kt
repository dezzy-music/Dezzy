/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0
 */

package com.dezzy.music.ui.component

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.connect.ConnectState
import com.dezzy.music.connect.LinerConnectManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectDeviceBottomSheet(
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val linerManager = remember { LinerConnectManager.getInstance(context) }

    val connectionState by linerManager.connectionState.collectAsState()
    val connectedDeviceName by linerManager.connectedDeviceName.collectAsState()
    val discoveredDevices by linerManager.discoveredDevices.collectAsState()
    val desktopState by linerManager.desktopState.collectAsState()

    val isConnectedToDesktop = connectionState == ConnectState.CONNECTED
    val isPlayingLocal = playerConnection?.isPlaying?.value == true && !isConnectedToDesktop

    // Nombre exacto asignado al celular (ej. "Galaxy de Dezzy", "Samsung S24 Ultra", etc.)
    val exactPhoneName = remember(context) { LinerConnectManager.getExactPhoneDeviceName(context) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.speaker),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Dispositivos de reproducción",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "Escucha en cualquier lugar y controla la reproducción desde tu dispositivo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Sección 1: Celular local con su nombre exacto
            DeviceOptionRow(
                icon = R.drawable.headphones,
                title = exactPhoneName,
                subtitle = if (isPlayingLocal) "Este dispositivo • Reproduciendo aquí" else "Este dispositivo",
                isActive = isPlayingLocal,
                onClick = {
                    if (isConnectedToDesktop) {
                        linerManager.disconnect()
                        playerConnection?.player?.play()
                    }
                }
            )

            // Sección 2: Computadoras / Laptops (nombre exacto como "Dezzy", "DESKTOP-XXXXXX", etc.)
            if (discoveredDevices.isNotEmpty()) {
                discoveredDevices.forEach { device ->
                    val isThisConnected = isConnectedToDesktop && connectedDeviceName == device.name
                    DeviceOptionRow(
                        icon = R.drawable.speaker,
                        title = device.name,
                        subtitle = if (isThisConnected) "Reproduciendo en ${device.name}" else "En red local (${device.ip})",
                        isActive = isThisConnected,
                        onClick = {
                            if (!isThisConnected) {
                                linerManager.connectToHost(device.ip, device.port, device.name)
                                playerConnection?.currentMetadata?.value?.let { meta ->
                                    playerConnection.player.pause()
                                    linerManager.handoffQueueToDesktop(
                                        title = meta.title,
                                        artist = meta.artists.joinToString(", ") { it.name },
                                        coverUrl = meta.thumbnailUrl
                                    )
                                }
                            }
                        }
                    )
                }
            } else {
                val targetDesktopName = connectedDeviceName ?: linerManager.lastDeviceName
                val desktopSubtitle = when (connectionState) {
                    ConnectState.DISCONNECTED -> "Toca para conectar en red local"
                    ConnectState.DISCOVERING -> "Buscando $targetDesktopName en tu red WiFi…"
                    ConnectState.CONNECTING -> "Conectando con $targetDesktopName…"
                    ConnectState.CONNECTED -> "Reproduciendo en $targetDesktopName"
                }

                DeviceOptionRow(
                    icon = R.drawable.speaker,
                    title = targetDesktopName,
                    subtitle = desktopSubtitle,
                    isActive = isConnectedToDesktop,
                    isLoading = connectionState == ConnectState.DISCOVERING || connectionState == ConnectState.CONNECTING,
                    onClick = {
                        if (!isConnectedToDesktop) {
                            linerManager.startDiscoveryAndConnect()
                            playerConnection?.currentMetadata?.value?.let { meta ->
                                playerConnection.player.pause()
                                linerManager.handoffQueueToDesktop(
                                    title = meta.title,
                                    artist = meta.artists.joinToString(", ") { it.name },
                                    coverUrl = meta.thumbnailUrl
                                )
                            }
                        }
                    }
                )
            }

            // Controles remotos en vivo si la computadora está conectada
            AnimatedVisibility(visible = isConnectedToDesktop) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Control remoto • ${connectedDeviceName ?: linerManager.lastDeviceName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )

                        desktopState?.track?.let { t ->
                            Text(
                                text = "${t.title} • ${t.artist}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Play / Pause / Skip
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { linerManager.skipPrevious() }) {
                                Icon(painter = painterResource(R.drawable.skip_previous), contentDescription = "Anterior")
                            }
                            FilledIconButton(onClick = { linerManager.togglePlayPause() }) {
                                Icon(
                                    painter = painterResource(
                                        if (desktopState?.isPlaying == true) R.drawable.pause else R.drawable.play
                                    ),
                                    contentDescription = "Play/Pause"
                                )
                            }
                            IconButton(onClick = { linerManager.skipNext() }) {
                                Icon(painter = painterResource(R.drawable.skip_next), contentDescription = "Siguiente")
                            }
                        }

                        // Control de volumen remoto en vivo
                        var remoteVolume by remember(desktopState?.volume) {
                            mutableFloatStateOf(desktopState?.volume ?: 1f)
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.volume_off),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Slider(
                                value = remoteVolume,
                                onValueChange = { newVol ->
                                    remoteVolume = newVol
                                    linerManager.setVolume(newVol)
                                },
                                valueRange = 0f..1f,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            Icon(
                                painter = painterResource(R.drawable.volume_up),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        // Desconectar / Regresar audio al celular
                        TextButton(
                            onClick = {
                                linerManager.disconnect()
                                playerConnection?.player?.play()
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("Cambiar reproducción de vuelta a $exactPhoneName")
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Sección 3: Otros destinos de audio (Google Cast / Bluetooth)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CastButton(modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Google Cast / TV",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Transmitir a Chromecast o Android TV",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                    .padding(vertical = 10.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.bluetooth),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Configuración Bluetooth",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Vincular bocinas, audífonos o estéreo del auto",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceOptionRow(
    icon: Int,
    title: String,
    subtitle: String,
    isActive: Boolean,
    isLoading: Boolean = false,
    onClick: () -> Unit,
) {
    val activeColor = Color(0xFF1DB954) // Verde Spotify Connect

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) activeColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isActive) activeColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = if (isActive) activeColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isActive) activeColor else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isActive) activeColor.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (isActive) {
                // Indicador de ecualizador / conectado
                Icon(
                    painter = painterResource(R.drawable.volume_up),
                    contentDescription = "Activo",
                    tint = activeColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
