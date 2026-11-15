/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.p2p

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dezzy.music.R
import com.dezzy.music.p2p.P2PMessaging
import com.dezzy.music.p2p.P2PSocial
import com.dezzy.music.p2p.P2PSong
import kotlinx.coroutines.launch

private enum class SendState { IDLE, SENDING, SENT, FAILED }

/**
 * «Enviar a un amigo»: manda la canción cifrada al chat de quien elijas
 * (chats recientes primero y luego la gente que sigues).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P2PShareSongSheet(song: P2PSong, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keys by P2PSocial.keys.collectAsState()
    val conversations by P2PMessaging.conversations.collectAsState(initial = emptyList())
    val following by P2PSocial.following.collectAsState()
    var note by remember { mutableStateOf("") }
    val states = remember { mutableStateMapOf<String, SendState>() }
    val people = remember(conversations, following) {
        (conversations.map { it.peer } + following).distinct().filter { it.length == 64 && it != P2PSocial.me }
    }
    LaunchedEffect(people) { if (people.isNotEmpty()) P2PSocial.loadProfiles(people) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Enviar a un amigo",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            P2PSongCard(
                song = song,
                onPlay = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            when {
                keys == null -> P2PEmptyState(
                    R.drawable.social_friends,
                    "Activa tu cuenta social",
                    "Abre la pestaña Social para crear tu cuenta. Vive en tu teléfono y no necesita correo ni contraseña.",
                    Modifier.fillMaxWidth(),
                )

                people.isEmpty() -> P2PEmptyState(
                    R.drawable.person_add,
                    "Aún no tienes amigos aquí",
                    "Añade a alguien desde Social con su enlace, su nombre o el radar «Cerca».",
                    Modifier.fillMaxWidth(),
                ) {
                    FilledTonalButton(onClick = { shareText(context, "${song.title} · ${song.artist}\n${song.url}", "Compartir canción") }) {
                        Text("Compartir fuera de Dezzy")
                    }
                }

                else -> {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(500) },
                        placeholder = { Text("Añade un mensaje (opcional)") },
                        maxLines = 3,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                    ) {
                        items(people, key = { it }) { pubkey ->
                            val user = rememberP2PUser(pubkey)
                            val state = states[pubkey] ?: SendState.IDLE
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                P2PAvatar(user)
                                Text(
                                    user.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(contentAlignment = Alignment.Center) {
                                    when (state) {
                                        SendState.SENDING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                        SendState.SENT -> Icon(
                                            painterResource(R.drawable.check),
                                            contentDescription = "Enviada a ${user.displayName}",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(12.dp),
                                        )

                                        else -> FilledTonalButton(onClick = {
                                            states[pubkey] = SendState.SENDING
                                            scope.launch {
                                                val ok = runCatching { P2PMessaging.sendMessage(pubkey, note.trim(), song) }.getOrDefault(false)
                                                states[pubkey] = if (ok) SendState.SENT else SendState.FAILED
                                            }
                                        }) { Text(if (state == SendState.FAILED) "Reintentar" else "Enviar") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Box(Modifier.size(8.dp))
        }
    }
}
