/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.p2p

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.p2p.P2PConversation
import com.dezzy.music.p2p.P2PIdentity
import com.dezzy.music.p2p.P2PListening
import com.dezzy.music.p2p.P2PMessaging
import com.dezzy.music.p2p.P2PPost
import com.dezzy.music.p2p.P2PPresence
import com.dezzy.music.p2p.P2PSocial
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

enum class SocialTab(val label: String, val icon: Int) {
    FRIENDS("Amigos", R.drawable.social_friends),
    NEARBY("Cerca", R.drawable.bluetooth),
    COMMUNITY("Comunidad", R.drawable.resource_public),
}

fun NavController.openP2PChat(pubkey: String) {
    val name = P2PSocial.user(pubkey).displayName
    navigate("chat/$pubkey/${Uri.encode(name)}")
}

fun NavController.openP2PProfile(pubkey: String) {
    if (pubkey == P2PSocial.me) navigate("social/profile") else navigate("social/profile/$pubkey")
}

/**
 * Social en tres anillos: Amigos (A2) · Cerca (C1) · Comunidad (B1).
 * Si todavía no hay identidad en el teléfono, muestra la bienvenida (A1).
 */
@Composable
fun SocialHubScreen(navController: NavController, initialTab: SocialTab = SocialTab.FRIENDS) {
    val loaded by P2PIdentity.loaded.collectAsState()
    val keys by P2PIdentity.keys.collectAsState()
    when {
        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        keys == null -> P2PWelcomeScreen(onDone = {}, onBack = { navController.navigateUp() })
        else -> SocialHubContent(navController, initialTab)
    }
}

@Composable
private fun SocialHubContent(navController: NavController, initialTab: SocialTab) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var showAddFriend by remember { mutableStateOf(false) }
    var showComposer by remember { mutableStateOf(false) }
    var feedVersion by remember { mutableStateOf(0) }
    val me = rememberP2PUser(P2PSocial.me.orEmpty())
    val playerInsets = LocalPlayerAwareWindowInsets.current

    LaunchedEffect(Unit) { P2PSocial.onSignedIn() }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Social", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { showAddFriend = true }) {
                    Icon(painterResource(R.drawable.search), contentDescription = "Buscar personas")
                }
                IconButton(onClick = { navController.navigate("social/profile") }) {
                    P2PAvatar(me, size = 32.dp)
                }
            }
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SocialTab.entries.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = tab == item,
                        onClick = { tab = item },
                        shape = SegmentedButtonDefaults.itemShape(index, SocialTab.entries.size),
                        icon = { Icon(painterResource(item.icon), null, Modifier.size(18.dp)) },
                    ) {
                        Text(item.label, maxLines = 1)
                    }
                }
            }
            val bottomPadding = playerInsets.only(WindowInsetsSides.Bottom).asPaddingValues()
            when (tab) {
                SocialTab.FRIENDS -> FriendsTab(navController, bottomPadding, onAddFriend = { showAddFriend = true })
                SocialTab.NEARBY -> NearbyTab(navController, bottomPadding)
                SocialTab.COMMUNITY -> CommunityTab(navController, bottomPadding, feedVersion, onCompose = { showComposer = true })
            }
        }
        val fabModifier = Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(playerInsets.only(WindowInsetsSides.Bottom))
            .padding(16.dp)
        when (tab) {
            SocialTab.FRIENDS -> ExtendedFloatingActionButton(
                onClick = { showAddFriend = true },
                icon = { Icon(painterResource(R.drawable.person_add), null) },
                text = { Text("Añadir amigo") },
                modifier = fabModifier,
            )
            SocialTab.COMMUNITY -> FloatingActionButton(onClick = { showComposer = true }, modifier = fabModifier) {
                Icon(painterResource(R.drawable.edit), contentDescription = "Publicar")
            }
            SocialTab.NEARBY -> Unit
        }
    }

    if (showAddFriend) {
        P2PAddFriendSheet(
            onDismiss = { showAddFriend = false },
            onOpenProfile = {
                showAddFriend = false
                navController.openP2PProfile(it)
            },
            onMessage = {
                showAddFriend = false
                navController.openP2PChat(it)
            },
        )
    }
    if (showComposer) {
        P2PComposerSheet(
            onDismiss = { showComposer = false },
            onPosted = {
                showComposer = false
                feedVersion++
            },
        )
    }
}

// ───────────────────────────── Amigos (A2) ─────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendsTab(navController: NavController, bottomPadding: PaddingValues, onAddFriend: () -> Unit) {
    val scope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val conversations by P2PMessaging.conversations.collectAsState(initial = null)
    val requests by P2PSocial.pendingFollowRequests.collectAsState(initial = emptyList())
    val following by P2PSocial.following.collectAsState()
    var listening by remember { mutableStateOf<List<P2PListening>>(emptyList()) }

    LaunchedEffect(following) {
        while (true) {
            listening = runCatching { P2PPresence.friendsListening() }.getOrDefault(listening)
            delay(45_000)
        }
    }
    LaunchedEffect(conversations) {
        conversations?.let { list -> P2PSocial.loadProfiles(list.map { it.peer }) }
    }
    LaunchedEffect(following) { P2PSocial.loadProfiles(following) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + 88.dp),
    ) {
        item(key = "chip") { EncryptedChip(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

        if (requests.isNotEmpty()) {
            item(key = "requests_title") { P2PSectionTitle("Solicitudes para seguirte") }
            items(requests, key = { "req_" + it.pubkey }) { request ->
                val user = rememberP2PUser(request.pubkey)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { navController.openP2PProfile(request.pubkey) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    P2PAvatar(user)
                    Column(Modifier.weight(1f)) {
                        Text(user.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(user.fingerprint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { scope.launch { P2PMessaging.rejectFollower(request.pubkey) } }) { Text("Rechazar") }
                    FilledTonalButton(onClick = { scope.launch { P2PMessaging.approveFollower(request.pubkey) } }) { Text("Aceptar") }
                }
            }
        }

        if (listening.isNotEmpty()) {
            item(key = "listening_title") { P2PSectionTitle("Escuchando ahora") }
            item(key = "listening_row") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(listening, key = { it.pubkey }) { item ->
                        val user = rememberP2PUser(item.pubkey)
                        Column(
                            Modifier
                                .width(76.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = { item.song?.let { playP2PSong(playerConnection, it) } ?: navController.openP2PProfile(item.pubkey) },
                                    onLongClick = { navController.openP2PProfile(item.pubkey) },
                                    onClickLabel = "Escuchar lo mismo",
                                    onLongClickLabel = "Ver perfil",
                                )
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            P2PAvatar(user, size = 60.dp, ring = true)
                            Text(user.displayName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                item.song?.artist ?: item.text,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        item(key = "chats_title") { P2PSectionTitle("Chats") }
        val list = conversations
        when {
            list == null -> item(key = "chats_loading") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }

            list.isEmpty() -> item(key = "chats_empty") {
                P2PEmptyState(
                    R.drawable.social_chat,
                    "Aún no tienes chats",
                    "Añade a tus amigos con tu enlace o encuéntralos cerca. Los mensajes se cifran en tu teléfono.",
                    Modifier.fillMaxWidth(),
                ) {
                    FilledTonalButton(onClick = onAddFriend) { Text("Añadir amigo") }
                }
            }

            else -> items(list, key = { "chat_" + it.peer }) { conversation ->
                ConversationRow(conversation, onClick = { navController.openP2PChat(conversation.peer) })
            }
        }

        val chatPeers = list.orEmpty().map { it.peer }.toSet()
        val others = following.filter { it !in chatPeers }.take(30)
        if (others.isNotEmpty()) {
            item(key = "following_title") { P2PSectionTitle("Personas que sigues") }
            items(others, key = { "f_$it" }) { pubkey ->
                val user = rememberP2PUser(pubkey)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { navController.openP2PProfile(pubkey) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
                    IconButton(onClick = { navController.openP2PChat(pubkey) }) {
                        Icon(painterResource(R.drawable.chat_bubble), contentDescription = "Enviar mensaje a ${user.displayName}")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: P2PConversation, onClick: () -> Unit) {
    val user = rememberP2PUser(conversation.peer)
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        P2PAvatar(user, size = 52.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    user.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (conversation.unread > 0) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    chatTime(conversation.lastAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (conversation.unread > 0) scheme.primary else scheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.lastFromMe) {
                    Icon(painterResource(R.drawable.done), null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    conversation.lastText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (conversation.unread > 0) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conversation.unread > 0) {
                    Badge(containerColor = scheme.primary, contentColor = scheme.onPrimary) {
                        Text("${conversation.unread}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// ───────────────────────────── Comunidad (B1) ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunityTab(navController: NavController, bottomPadding: PaddingValues, feedVersion: Int, onCompose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val following by P2PSocial.following.collectAsState()
    var mode by rememberSaveable { mutableStateOf(if (P2PSocial.following.value.isEmpty()) P2PSocial.FeedMode.DISCOVER else P2PSocial.FeedMode.FOLLOWING) }
    var posts by remember { mutableStateOf<List<P2PPost>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var commentsFor by remember { mutableStateOf<P2PPost?>(null) }
    val listState = rememberLazyListState()
    val me = rememberP2PUser(P2PSocial.me.orEmpty())

    suspend fun reload() {
        refreshing = true
        endReached = false
        posts = runCatching { P2PSocial.feed(mode) }.getOrDefault(posts.orEmpty())
        refreshing = false
    }

    LaunchedEffect(mode, feedVersion, following.size) { reload() }

    // Paginación: al acercarse al final, pide lo anterior a la última publicación.
    LaunchedEffect(listState, mode) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { last -> last >= listState.layoutInfo.totalItemsCount - 4 }
            .collect {
                val current = posts ?: return@collect
                if (loadingMore || endReached || current.isEmpty()) return@collect
                loadingMore = true
                val more = runCatching { P2PSocial.feed(mode, until = current.last().createdAt - 1) }.getOrDefault(emptyList())
                val known = current.map { it.id }.toHashSet()
                val fresh = more.filter { it.id !in known }
                if (fresh.isEmpty()) endReached = true else posts = current + fresh
                loadingMore = false
            }
    }

    fun replace(updated: P2PPost) {
        posts = posts?.map { if (it.id == updated.id) updated else it }
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { reload() } }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + 88.dp),
        ) {
            item(key = "modes") {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = mode == P2PSocial.FeedMode.FOLLOWING, onClick = { mode = P2PSocial.FeedMode.FOLLOWING }, label = { Text("Siguiendo") })
                    FilterChip(selected = mode == P2PSocial.FeedMode.DISCOVER, onClick = { mode = P2PSocial.FeedMode.DISCOVER }, label = { Text("Descubrir") })
                }
            }
            item(key = "composer") {
                Surface(
                    onClick = onCompose,
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        P2PAvatar(me, size = 36.dp)
                        Text(
                            "¿Qué estás escuchando?",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text("Publicar", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
            val list = posts
            when {
                list == null -> item(key = "loading") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }

                list.isEmpty() -> item(key = "empty") {
                    if (mode == P2PSocial.FeedMode.FOLLOWING) {
                        P2PEmptyState(
                            R.drawable.social_feed,
                            "Tu feed está tranquilo",
                            "Sigue a gente en Descubrir o añade a tus amigos para ver aquí lo que publican.",
                            Modifier.fillMaxWidth(),
                        ) { FilledTonalButton(onClick = { mode = P2PSocial.FeedMode.DISCOVER }) { Text("Ir a Descubrir") } }
                    } else {
                        P2PEmptyState(
                            R.drawable.social_feed,
                            "Todavía no hay publicaciones",
                            "Sé la primera persona en compartir lo que escucha.",
                            Modifier.fillMaxWidth(),
                        ) { FilledTonalButton(onClick = onCompose) { Text("Publicar") } }
                    }
                }

                else -> {
                    items(list, key = { it.id }) { post ->
                        Column {
                            P2PPostCard(
                                post = post,
                                onOpenProfile = { navController.openP2PProfile(it) },
                                onToggleLike = {
                                    val optimistic = post.copy(
                                        likedByMe = !post.likedByMe,
                                        likes = (post.likes + if (post.likedByMe) -1 else 1).coerceAtLeast(0),
                                    )
                                    replace(optimistic)
                                    scope.launch {
                                        val liked = runCatching { P2PSocial.toggleLike(post) }.getOrDefault(post.likedByMe)
                                        if (liked != optimistic.likedByMe) replace(post)
                                    }
                                },
                                onComments = { commentsFor = post },
                                onDelete = if (post.author == P2PSocial.me) {
                                    {
                                        posts = posts?.filterNot { it.id == post.id }
                                        scope.launch { P2PSocial.deletePost(post) }
                                    }
                                } else {
                                    null
                                },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                    if (loadingMore) {
                        item(key = "more") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator(Modifier.size(24.dp)) }
                        }
                    }
                }
            }
        }
    }

    commentsFor?.let { post ->
        P2PCommentsSheet(
            post = post,
            onDismiss = { commentsFor = null },
            onOpenProfile = {
                commentsFor = null
                navController.openP2PProfile(it)
            },
            onCommented = { posts = posts?.map { if (it.id == post.id) it.copy(comments = it.comments + 1) else it } },
        )
    }
}
