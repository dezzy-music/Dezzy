/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.p2p

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.listentogether.ListenTogetherClient
import com.dezzy.music.p2p.P2PMessage
import com.dezzy.music.p2p.P2PMessaging
import com.dezzy.music.p2p.P2PSocial
import com.dezzy.music.p2p.P2PSong
import com.dezzy.music.p2p.crypto.CryptoUtils
import com.dezzy.music.ui.screens.Screens
import kotlinx.coroutines.launch
import java.math.BigInteger

/** Código de seguridad común a los dos (como en WhatsApp/Signal): si coincide, nadie se coló. */
private fun safetyCode(a: String, b: String): String {
    val (x, y) = if (a < b) a to b else b to a
    val digest = CryptoUtils.sha256((x + y).toByteArray(Charsets.UTF_8))
    val digits = BigInteger(1, digest).toString().padStart(30, '0').take(30)
    return digits.chunked(5).joinToString(" ")
}

/** A3 · Chat cifrado de extremo a extremo, con canciones e invitaciones a «Juntos». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P2PChatScreen(navController: NavController, peer: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val nowPlaying = playerConnection?.mediaMetadata?.collectAsState()?.value
    val user = rememberP2PUser(peer)
    val myKey = P2PSocial.me
    val valid = peer.length == 64 && myKey != null
    val messages by remember(peer) { P2PMessaging.messages(peer) }.collectAsState(initial = emptyList())
    var input by rememberSaveable { mutableStateOf("") }
    var attachSong by remember { mutableStateOf(false) }
    var showSafety by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(peer) {
        if (valid) {
            P2PSocial.loadProfiles(listOf(peer))
            P2PMessaging.start()
        }
    }
    LaunchedEffect(messages.size) {
        if (valid) P2PMessaging.markRead(peer)
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
    }

    fun send(text: String, song: P2PSong?, juntos: String?) {
        scope.launch {
            val ok = P2PMessaging.sendMessage(peer, text, song, juntos)
            if (!ok) snackbar.showSnackbar("No se pudo entregar. Se reintentará al reconectar con los relés.")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = "Volver")
                    }
                },
                title = {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { navController.openP2PProfile(peer) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        P2PAvatar(user, size = 36.dp)
                        Column {
                            Text(user.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(painterResource(R.drawable.lock), null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                                Text("Cifrado de extremo a extremo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val code = ListenTogetherClient.getInstance()?.roomState?.value?.roomCode?.takeIf { it.isNotBlank() }
                        if (code != null) {
                            send("", null, "dezzy://together?code=$code")
                        } else {
                            scope.launch { snackbar.showSnackbar("Crea o entra en una sala y vuelve a tocar para invitar.") }
                            navController.navigate(Screens.Together.route)
                        }
                    }) {
                        Icon(painterResource(R.drawable.headphones), contentDescription = "Invitar a escuchar juntos")
                    }
                },
            )
        },
        bottomBar = {
            if (valid) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    if (attachSong && nowPlaying != null) {
                        P2PSongCard(
                            song = nowPlaying.toP2PSong(),
                            onPlay = { attachSong = false },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconToggleButton(
                            checked = attachSong,
                            onCheckedChange = { attachSong = it },
                            enabled = nowPlaying != null,
                        ) {
                            Icon(
                                painterResource(R.drawable.music_note),
                                contentDescription = if (attachSong) "Quitar canción que suena" else "Adjuntar canción que suena",
                                tint = if (attachSong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.take(4000) },
                            placeholder = { Text("Mensaje") },
                            maxLines = 5,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.weight(1f),
                        )
                        FilledIconButton(
                            enabled = input.isNotBlank() || (attachSong && nowPlaying != null),
                            onClick = {
                                val song = if (attachSong) nowPlaying?.toP2PSong() else null
                                send(input, song, null)
                                input = ""
                                attachSong = false
                            },
                        ) {
                            Icon(painterResource(R.drawable.send), contentDescription = "Enviar")
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (!valid) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                P2PEmptyState(
                    R.drawable.social_chat,
                    "Chat no disponible",
                    "Este chat era de la red social anterior. Añade de nuevo a esta persona con su enlace de Dezzy.",
                )
            }
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "e2e") {
                Surface(
                    onClick = { showSafety = true },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(R.drawable.lock), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(
                            "Los mensajes y canciones se cifran en tu teléfono. Ni los relés ni Dezzy pueden leerlos. Toca para verificar el código de seguridad.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
            items(messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    onPlay = { playP2PSong(playerConnection, it) },
                    onJoin = { openDezzyLink(context, it) },
                )
            }
        }
    }

    if (showSafety && myKey != null) {
        AlertDialog(
            onDismissRequest = { showSafety = false },
            icon = { Icon(painterResource(R.drawable.security), null) },
            title = { Text("Código de seguridad") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Compáralo con el de ${user.displayName} en persona o por llamada. Si coincide, nadie puede estar en medio de vuestra conversación.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        safetyCode(myKey, peer),
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${user.displayName}: ${user.fingerprint}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { showSafety = false }) { Text("Listo") } },
        )
    }
}

@Composable
private fun MessageBubble(message: P2PMessage, onPlay: (P2PSong) -> Unit, onJoin: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val mine = message.fromMe
    val bubbleColor = if (mine) scheme.primaryContainer else scheme.surfaceContainerHigh
    val textColor = if (mine) scheme.onPrimaryContainer else scheme.onSurface
    val shape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (mine) 20.dp else 6.dp,
        bottomEnd = if (mine) 6.dp else 20.dp,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(shape = shape, color = bubbleColor, modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                message.song?.let { song ->
                    P2PSongCard(song = song, onPlay = { onPlay(song) }, container = scheme.surfaceContainerLowest.copy(alpha = 0.6f))
                }
                message.juntosLink?.let { link ->
                    Surface(shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainerLowest.copy(alpha = 0.6f)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(painterResource(R.drawable.headphones), null, tint = scheme.primary)
                                Column {
                                    Text("Escuchar juntos", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text("Sala privada", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                }
                            }
                            if (!mine) FilledTonalButton(onClick = { onJoin(link) }) { Text("¿Te unes?") }
                        }
                    }
                }
                if (message.text.isNotBlank()) {
                    Text(message.text, style = MaterialTheme.typography.bodyLarge, color = textColor)
                }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    Text(messageTime(message.createdAt), style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.7f))
                    if (mine) {
                        Spacer(Modifier.width(4.dp))
                        Icon(painterResource(R.drawable.done), contentDescription = "Enviado", modifier = Modifier.size(14.dp), tint = textColor.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}
