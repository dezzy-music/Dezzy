/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.p2p

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dezzy.music.R
import com.dezzy.music.p2p.P2PSocial
import com.dezzy.music.p2p.P2PUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * «Añadir amigo»: compartir mi enlace, copiar mi ID o buscar a alguien por nombre / enlace.
 * (En el radar «Cerca» se añade a quien está al lado.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
fun P2PAddFriendSheet(onDismiss: () -> Unit, onOpenProfile: (String) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val following by P2PSocial.following.collectAsState()
    val me = rememberP2PUser(P2PSocial.me.orEmpty())
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<P2PUser>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = null
            return@LaunchedEffect
        }
        delay(450)
        searching = true
        results = runCatching { P2PSocial.searchUsers(q) }.getOrDefault(emptyList())
        searching = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Añadir amigo",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        P2PAvatar(me, size = 48.dp)
                        Column(Modifier.weight(1f)) {
                            Text(me.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                me.fingerprint,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        "Comparte tu enlace: quien lo abra con Dezzy te verá y podrá escribirte cifrado.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { P2PSocial.myInviteLink()?.let { shareText(context, "Agrégame en Dezzy 🎧 $it", "Compartir mi enlace") } },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(painterResource(R.drawable.share), null, Modifier.size(18.dp))
                            Text("  Compartir enlace")
                        }
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(me.npub))
                                copied = true
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(painterResource(if (copied) R.drawable.check else R.drawable.content_copy), null, Modifier.size(18.dp))
                            Text(if (copied) "  Copiado" else "  Copiar ID")
                        }
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Nombre, enlace o ID (npub…)") },
                leadingIcon = { Icon(painterResource(R.drawable.search), null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { query = query.trim() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            val list = results
            when {
                searching -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                list != null && list.isEmpty() -> Text(
                    "No encontramos a nadie con «${query.trim()}». Prueba con su enlace o su ID.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                list != null -> LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                ) {
                    items(list, key = { it.pubkey }) { found ->
                        val user = rememberP2PUser(found.pubkey).let { if (it.name.isBlank()) found else it }
                        val isFollowing = found.pubkey in following
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenProfile(found.pubkey) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            P2PAvatar(user)
                            Column(Modifier.weight(1f)) {
                                Text(user.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    user.about.ifBlank { user.fingerprint },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { onMessage(found.pubkey) }) {
                                Icon(painterResource(R.drawable.chat_bubble), contentDescription = "Mensaje a ${user.displayName}")
                            }
                            if (isFollowing) {
                                Icon(
                                    painterResource(R.drawable.check),
                                    contentDescription = "Ya la sigues",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(12.dp),
                                )
                            } else {
                                IconButton(onClick = { scope.launch { P2PSocial.setFollowing(found.pubkey, true) } }) {
                                    Icon(painterResource(R.drawable.person_add), contentDescription = "Seguir a ${user.displayName}")
                                }
                            }
                        }
                    }
                }

                else -> Text(
                    "Los perfiles se comprueban por su clave: nadie puede hacerse pasar por otra persona.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Box(Modifier.size(8.dp))
        }
    }
}
