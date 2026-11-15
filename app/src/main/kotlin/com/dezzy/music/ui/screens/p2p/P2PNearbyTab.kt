/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.p2p

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.p2p.FollowState
import com.dezzy.music.p2p.P2PListening
import com.dezzy.music.p2p.P2PNearby
import com.dezzy.music.p2p.P2PNearbyPerson
import com.dezzy.music.p2p.P2PPresence
import com.dezzy.music.p2p.P2PSocial
import com.dezzy.music.p2p.P2PSong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** C1 · «Cerca»: quién escucha música a tu alrededor, por Bluetooth y sin compartir ubicación. */
@Composable
internal fun NearbyTab(navController: NavController, bottomPadding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val status by P2PNearby.status.collectAsState()
    val people by P2PNearby.people.collectAsState()
    val following by P2PSocial.following.collectAsState()
    var visible by remember { mutableStateOf(P2PSocial.nearbyVisible) }
    var listening by remember { mutableStateOf<Map<String, P2PListening>>(emptyMap()) }
    var restartKey by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        restartKey++
    }

    DisposableEffect(restartKey) {
        P2PNearby.start(context)
        onDispose { P2PNearby.stop(context) }
    }

    val nearbyKeys = people.map { it.pubkey }
    LaunchedEffect(nearbyKeys.toSet()) {
        if (nearbyKeys.isEmpty()) return@LaunchedEffect
        while (true) {
            listening = runCatching { P2PPresence.listeningOf(nearbyKeys) }.getOrDefault(listening)
            delay(30_000)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + 24.dp),
    ) {
        item(key = "radar") { Radar(people, status, onSelect = { selected = it }) }

        when (status) {
            P2PNearby.Status.NO_PERMISSION -> item(key = "perm") {
                P2PEmptyState(
                    R.drawable.bluetooth,
                    "Activa el radar",
                    "Dezzy usa Bluetooth para encontrar a quien escucha música a unos metros. No se comparte tu ubicación.",
                    Modifier.fillMaxWidth(),
                ) { Button(onClick = { permissionLauncher.launch(P2PNearby.requiredPermissions()) }) { Text("Permitir Bluetooth") } }
            }

            P2PNearby.Status.BLUETOOTH_OFF -> item(key = "off") {
                P2PEmptyState(
                    R.drawable.bluetooth,
                    "El Bluetooth está apagado",
                    "Enciéndelo para ver quién está cerca.",
                    Modifier.fillMaxWidth(),
                ) {
                    Button(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("Abrir ajustes de Bluetooth") }
                    FilledTonalButton(onClick = { restartKey++ }) { Text("Reintentar") }
                }
            }

            P2PNearby.Status.UNSUPPORTED -> item(key = "unsupported") {
                P2PEmptyState(R.drawable.bluetooth, "Sin Bluetooth", "Este dispositivo no permite el radar cercano.", Modifier.fillMaxWidth())
            }

            else -> {
                item(key = "count") {
                    Text(
                        when (people.size) {
                            0 -> "Nadie cerca todavía · sin datos"
                            1 -> "1 persona cerca · sin datos"
                            else -> "${people.size} personas cerca · sin datos"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(people, key = { it.pubkey }) { person ->
                    NearbyRow(
                        person = person,
                        listening = listening[person.pubkey],
                        isFollowing = person.pubkey in following,
                        onOpen = { selected = person.pubkey },
                        onPlay = { song -> playP2PSong(playerConnection, song) },
                        onFollow = { scope.launch { P2PSocial.setFollowing(person.pubkey, true) } },
                    )
                }
            }
        }

        item(key = "visibility") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = visible,
                        role = Role.Switch,
                        onValueChange = {
                            visible = it
                            P2PSocial.nearbyVisible = it
                            restartKey++
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Que me vean cerca", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Sólo se anuncia tu ID público mientras esta pestaña está abierta. Nunca tu ubicación.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = visible, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }

    selected?.let { pubkey ->
        NearbyPersonSheet(
            pubkey = pubkey,
            listening = listening[pubkey],
            onDismiss = { selected = null },
            onChat = {
                selected = null
                navController.openP2PChat(pubkey)
            },
            onProfile = {
                selected = null
                navController.openP2PProfile(pubkey)
            },
            onPlay = { song -> playP2PSong(playerConnection, song) },
        )
    }
}

@Composable
private fun Radar(people: List<P2PNearbyPerson>, status: P2PNearby.Status, onSelect: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val me = rememberP2PUser(P2PSocial.me.orEmpty())
    val transition = rememberInfiniteTransition(label = "radar")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(240.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val ringColor = scheme.primary
            val scanning = status == P2PNearby.Status.SCANNING
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height * 0.55f)
                val maxR = minOf(size.width, size.height) * 0.5f
                listOf(0.36f, 0.68f, 1f).forEach { f ->
                    drawCircle(ringColor.copy(alpha = 0.16f), radius = maxR * f, center = center, style = Stroke(width = 1.5.dp.toPx()))
                }
                if (scanning) {
                    drawCircle(
                        ringColor.copy(alpha = 0.22f * (1f - pulse)),
                        radius = maxR * pulse,
                        center = center,
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
            Row(
                Modifier.padding(start = 16.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(painterResource(R.drawable.bluetooth), null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                Text(
                    if (scanning) "Buscando a tu alrededor…" else "Radar en pausa",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
            val w = maxWidth
            val h = maxHeight
            val cx = w / 2
            val cy = h * 0.55f
            val maxR = minOf(w, h) * 0.5f
            Column(
                Modifier.offset(x = cx - 28.dp, y = cy - 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                P2PAvatar(me, size = 56.dp, ring = true)
            }
            people.take(8).forEach { person -> key(person.pubkey) {
                val user = rememberP2PUser(person.pubkey)
                val hash = person.pubkey.take(4).toInt(16)
                val angle = Math.toRadians((hash % 360).toDouble())
                // Más señal = más cerca del centro.
                val closeness = ((person.rssi + 100).coerceIn(0, 60)) / 60f
                val r = maxR * (0.95f - 0.55f * closeness)
                val x = cx + r * cos(angle).toFloat() - 24.dp
                val y = cy + r * sin(angle).toFloat() - 24.dp
                Column(
                    Modifier
                        .offset(x = x.coerceIn(0.dp, w - 72.dp), y = y.coerceIn(28.dp, h - 64.dp))
                        .width(72.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClickLabel = "Ver opciones") { onSelect(person.pubkey) }
                        .semantics(mergeDescendants = true) { contentDescription = user.displayName }
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    P2PAvatar(user, size = 44.dp, ring = true)
                    Text(
                        user.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            } }
        }
    }
}

@Composable
private fun NearbyRow(
    person: P2PNearbyPerson,
    listening: P2PListening?,
    isFollowing: Boolean,
    onOpen: () -> Unit,
    onPlay: (P2PSong) -> Unit,
    onFollow: () -> Unit,
) {
    val user = rememberP2PUser(person.pubkey)
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        P2PAvatar(user, size = 48.dp, ring = listening != null)
        Column(Modifier.weight(1f)) {
            Text(user.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listening?.let { it.song?.let { s -> "${s.title} — ${s.artist}" } ?: it.text } ?: "No está escuchando",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val song = listening?.song
        when {
            song != null -> FilledTonalButton(onClick = { onPlay(song) }) { Text("Escuchar") }
            !isFollowing -> IconButton(onClick = onFollow) {
                Icon(painterResource(R.drawable.person_add), contentDescription = "Seguir a ${user.displayName}")
            }
            else -> Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .padding(8.dp),
            ) { Icon(painterResource(R.drawable.check), contentDescription = "Ya la sigues", tint = scheme.primary) }
        }
    }
}

/**
 * Hoja de una persona encontrada por el radar: agregarla a amigos, escribirle o ver su perfil
 * sin salir de «Cerca».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NearbyPersonSheet(
    pubkey: String,
    listening: P2PListening?,
    onDismiss: () -> Unit,
    onChat: () -> Unit,
    onProfile: () -> Unit,
    onPlay: (P2PSong) -> Unit,
) {
    val user = rememberP2PUser(pubkey)
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val following by P2PSocial.following.collectAsState()
    var followState by remember(pubkey) { mutableStateOf<FollowState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(pubkey) { runCatching { P2PSocial.loadProfiles(listOf(pubkey)) } }
    LaunchedEffect(pubkey, pubkey in following, user.isPrivate) {
        followState = runCatching { P2PSocial.followState(pubkey) }.getOrDefault(FollowState.NONE)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            P2PAvatar(user, size = 88.dp, ring = listening != null)
            Text(
                user.displayName,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (user.isPrivate) "Cuenta privada · está cerca de ti" else "Está cerca de ti",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (user.about.isNotBlank()) {
                Text(
                    user.about,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val song = listening?.song
            val nowText = song?.let { "${it.title} — ${it.artist}" } ?: listening?.text
            if (!nowText.isNullOrBlank()) {
                Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(painterResource(R.drawable.graphic_eq), null, tint = scheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text("Escuchando ahora", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                            Text(nowText, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (song != null) {
                            FilledTonalButton(onClick = { onPlay(song) }) { Text("Escuchar") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            when (followState) {
                null -> Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }

                FollowState.NONE -> Button(
                    onClick = {
                        busy = true
                        failed = false
                        scope.launch {
                            val ok = runCatching { P2PSocial.setFollowing(pubkey, true) }.getOrDefault(false)
                            failed = !ok
                            followState = runCatching { P2PSocial.followState(pubkey) }.getOrDefault(FollowState.NONE)
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(painterResource(R.drawable.person_add), null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(if (user.isPrivate) "Enviar solicitud de amistad" else "Agregar a amigos")
                    }
                }

                FollowState.REQUESTED -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Solicitud enviada · esperando respuesta")
                }

                FollowState.FOLLOWING -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.check), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Ya está en tus amigos")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onChat, modifier = Modifier.weight(1f)) {
                    Icon(painterResource(R.drawable.nav_messages_outlined), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Escribir")
                }
                OutlinedButton(onClick = onProfile, modifier = Modifier.weight(1f)) {
                    Icon(painterResource(R.drawable.person), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Ver perfil")
                }
            }
            if (failed) {
                Text(
                    "No hay conexión con los relés. Inténtalo de nuevo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                "Los mensajes van cifrados de extremo a extremo.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
