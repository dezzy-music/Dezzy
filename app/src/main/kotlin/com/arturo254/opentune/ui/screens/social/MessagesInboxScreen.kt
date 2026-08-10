/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.ui.screens.social

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.arturo254.opentune.R
import com.arturo254.opentune.social.Conversation
import com.arturo254.opentune.social.FriendRequest
import com.arturo254.opentune.social.MessagesRepository
import com.arturo254.opentune.social.SocialRepository
import com.arturo254.opentune.social.SupabaseAuth
import kotlinx.coroutines.launch

/**
 * Bandeja de mensajes estilo Instagram DMs: título grande, buscador, pestañas
 * "Bandeja de entrada" / "Solicitudes", y la lista de conversaciones con la
 * última línea y cuándo fue.
 *
 * Reutiliza el backend 1:1 que ya existía; antes solo se llegaba al chat entrando
 * amigo por amigo, sin una vista de conversaciones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesInboxScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val userId by SupabaseAuth.currentUserId.collectAsState()

    var query by remember { mutableStateOf("") }
    var showRequests by remember { mutableStateOf(false) }
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var requests by remember { mutableStateOf<List<FriendRequest>>(emptyList()) }

    suspend fun reload() {
        conversations = MessagesRepository.getConversations()
        requests = SocialRepository.getFriendRequests()
    }

    // Recargar al volver a la pantalla (mensajes nuevos, solicitudes aceptadas…).
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(userId, resumeTick) {
        if (userId != null) reload()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mensajes", fontWeight = FontWeight.Bold, fontSize = 26.sp) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate("social") }) {
                        Icon(painterResource(R.drawable.social_edit), contentDescription = "Nuevo mensaje")
                    }
                },
            )
        },
    ) { padding ->
        if (userId == null) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                DezzyAuthScreen(onAuthenticated = {})
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            // Buscador
            DezzySearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Buscar",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )

            // Pestañas
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InboxTab("Bandeja de entrada", selected = !showRequests) { showRequests = false }
                InboxTab(
                    text = if (requests.isEmpty()) "Solicitudes" else "Solicitudes (${requests.size})",
                    selected = showRequests,
                ) { showRequests = true }
            }

            if (showRequests) {
                RequestList(requests, onRespond = { id, accept ->
                    scope.launch {
                        SocialRepository.respondFriendRequest(id, accept)
                        reload()
                    }
                })
            } else {
                val filtered = remember(conversations, query) {
                    val q = query.trim().lowercase()
                    if (q.isBlank()) conversations
                    else conversations.filter {
                        it.name.lowercase().contains(q) || it.username?.lowercase()?.contains(q) == true
                    }
                }
                if (filtered.isEmpty()) {
                    InboxEmptyState()
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(filtered, key = { it.friendId }) { conv ->
                            ConversationRow(conv) {
                                val name = java.net.URLEncoder.encode(conv.name, "UTF-8")
                                navController.navigate("chat/${conv.friendId}/$name")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest
        else Color.Transparent,
        border = if (selected) null
        else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ConversationRow(conv: Conversation, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(url = conv.avatarUrl, size = 56.dp, iconSize = 28.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = conv.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val preview = (if (conv.lastFromMe) "Tú: " else "") + conv.lastMessage
            Text(
                text = "$preview · ${shortAgo(conv.lastAtMs)}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RequestList(requests: List<FriendRequest>, onRespond: (String, Boolean) -> Unit) {
    if (requests.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No tienes solicitudes",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(requests, key = { it.requestId }) { req ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(url = req.avatarUrl, size = 52.dp, iconSize = 26.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(req.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                    Text(
                        "Quiere chatear contigo",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    onClick = { onRespond(req.requestId, true) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.social_check),
                            contentDescription = "Aceptar",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = { onRespond(req.requestId, false) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.social_close),
                            contentDescription = "Rechazar",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(76.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.social_chat),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Aún no tienes mensajes", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Abre el perfil de un amigo o toca el lápiz para empezar una conversación.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** Formato corto tipo Instagram: "5 min", "3 h", "11 sem". */
private fun shortAgo(epochMs: Long): String {
    val diff = (System.currentTimeMillis() - epochMs).coerceAtLeast(0)
    val min = diff / 60_000
    val h = diff / 3_600_000
    val d = diff / 86_400_000
    val w = d / 7
    return when {
        min < 1 -> "ahora"
        h < 1 -> "$min min"
        d < 1 -> "$h h"
        w < 1 -> "$d d"
        w < 52 -> "$w sem"
        else -> "${w / 52} a"
    }
}
