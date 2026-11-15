/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.p2p

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.p2p.FollowState
import com.dezzy.music.p2p.ListeningVisibility
import com.dezzy.music.p2p.P2PIdentity
import com.dezzy.music.p2p.P2PListening
import com.dezzy.music.p2p.P2PMessaging
import com.dezzy.music.p2p.P2PPost
import com.dezzy.music.p2p.P2PPresence
import com.dezzy.music.p2p.P2PSocial
import com.dezzy.music.p2p.P2PUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val HEX_KEY = Regex("[0-9a-f]{64}")

/**
 * B2 · Perfil al estilo Instagram: público o privado, seguidores, publicaciones y «escuchando ahora».
 * [pubkey] nulo = mi perfil (con edición y ajustes de la cuenta).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P2PProfileScreen(navController: NavController, pubkey: String?, startEditing: Boolean = false) {
    val keys by P2PSocial.keys.collectAsState()
    val myKey = keys?.publicKey
    val target = when {
        pubkey == null -> myKey
        else -> P2PSocial.parsePublicKey(pubkey) ?: pubkey.lowercase().takeIf { HEX_KEY.matches(it) }
    }

    when {
        // Sin cuenta todavía: el perfil propio empieza por el onboarding.
        myKey == null -> {
            P2PWelcomeScreen(onDone = {}, onBack = { navController.navigateUp() })
            return
        }

        target == null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                P2PEmptyState(
                    R.drawable.person,
                    "Perfil no disponible",
                    "Este enlace era de la red social anterior. Pídele a esa persona su nuevo enlace de Dezzy.",
                ) { FilledTonalButton(onClick = { navController.navigateUp() }) { Text("Volver") } }
            }
            return
        }
    }
    ProfileContent(navController, target!!, isMe = target == myKey, startEditing = startEditing && target == myKey)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileContent(navController: NavController, target: String, isMe: Boolean, startEditing: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val bottomInset = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom).asPaddingValues()
    val user = rememberP2PUser(target)
    val following by P2PSocial.following.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var version by remember { mutableIntStateOf(0) }
    var canSee by remember { mutableStateOf<Boolean?>(null) }
    var followState by remember { mutableStateOf(FollowState.NONE) }
    var counts by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var posts by remember { mutableStateOf<List<P2PPost>?>(null) }
    var listening by remember { mutableStateOf<P2PListening?>(null) }
    var commentsFor by remember { mutableStateOf<P2PPost?>(null) }
    var editing by rememberSaveable { mutableStateOf(startEditing) }
    var showSettings by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var busyFollow by remember { mutableStateOf(false) }
    var words by remember { mutableStateOf<List<String>?>(null) }
    var photoViewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }

    LaunchedEffect(target, version, user.isPrivate, following.contains(target)) {
        runCatching { P2PSocial.loadProfiles(listOf(target), force = version == 0) }
        followState = P2PSocial.followState(target)
        val visible = P2PSocial.canSee(target)
        canSee = visible
        posts = if (visible) runCatching { P2PSocial.userPosts(target) }.getOrDefault(posts.orEmpty()) else emptyList()
    }
    LaunchedEffect(target, version) {
        counts = runCatching { P2PSocial.followCounts(target) }.getOrNull()
        listening = runCatching { P2PPresence.listeningOf(listOf(target))[target] }.getOrNull()
    }

    fun replace(updated: P2PPost) {
        posts = posts?.map { if (it.id == updated.id) updated else it }
    }

    // Las 12 palabras se muestran a pantalla completa y protegidas contra capturas.
    words?.let { list ->
        BackHandler { words = null }
        P2PBackupWordsContent(
            words = list,
            onConfirm = {
                P2PIdentity.markBackedUp(context)
                words = null
            },
            confirmLabel = "Listo",
        )
        return
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (user.isPrivate) Icon(painterResource(R.drawable.lock), contentDescription = "Cuenta privada", modifier = Modifier.size(18.dp))
                        Text(user.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    if (isMe) {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(painterResource(R.drawable.settings), contentDescription = "Ajustes de la cuenta")
                        }
                    } else {
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(painterResource(R.drawable.more_vert), contentDescription = "Más opciones")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Compartir perfil") },
                                    leadingIcon = { Icon(painterResource(R.drawable.share), null) },
                                    onClick = {
                                        menu = false
                                        shareText(context, "${user.displayName} en Dezzy: https://dezzy.space/?npub=${user.npub}", "Compartir perfil")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Ver código de verificación") },
                                    leadingIcon = { Icon(painterResource(R.drawable.security), null) },
                                    onClick = {
                                        menu = false
                                        navController.openP2PChat(target)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (isMe) {
                ExtendedFloatingActionButton(
                    onClick = { composing = true },
                    icon = { Icon(painterResource(R.drawable.edit), null) },
                    text = { Text("Publicar") },
                    modifier = Modifier.padding(bottom = bottomInset.calculateBottomPadding()),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = bottomInset.calculateBottomPadding() + 96.dp),
        ) {
            item(key = "header") {
                ProfileHeader(
                    user = user,
                    posts = posts?.size,
                    counts = counts,
                    onEdit = if (isMe) {
                        { editing = true }
                    } else null,
                    onOpenPhoto = user.picture?.let {
                        {
                            val photos = listOfNotNull(user.picture, user.banner?.takeIf { b -> b.startsWith("https://") }) +
                                posts.orEmpty().mapNotNull { p -> p.imageUrl }
                            photoViewer = photos.distinct() to 0
                        }
                    },
                )
            }
            listening?.song?.let { song ->
                item(key = "listening") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (isMe) "Estás escuchando" else "Escuchando ahora",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        P2PSongCard(song = song, onPlay = { playP2PSong(playerConnection, song) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item(key = "actions") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isMe) {
                        FilledTonalButton(onClick = { editing = true }, modifier = Modifier.weight(1f)) { Text("Editar perfil") }
                        OutlinedButton(
                            onClick = { P2PSocial.myInviteLink()?.let { shareText(context, "Agrégame en Dezzy: $it", "Compartir mi enlace") } },
                            modifier = Modifier.weight(1f),
                        ) { Text("Compartir") }
                    } else {
                        val onFollow: () -> Unit = {
                            busyFollow = true
                            scope.launch {
                                val follow = followState == FollowState.NONE
                                val ok = runCatching { P2PSocial.setFollowing(target, follow) }.getOrDefault(false)
                                if (!ok) snackbar.showSnackbar("No hay conexión con los relés. Inténtalo de nuevo.")
                                followState = P2PSocial.followState(target)
                                busyFollow = false
                                version++
                            }
                        }
                        when (followState) {
                            FollowState.NONE -> Button(onClick = onFollow, enabled = !busyFollow, modifier = Modifier.weight(1f)) {
                                Text(if (user.isPrivate) "Solicitar seguir" else "Seguir")
                            }

                            FollowState.REQUESTED -> FilledTonalButton(onClick = onFollow, enabled = !busyFollow, modifier = Modifier.weight(1f)) {
                                Text("Solicitado")
                            }

                            FollowState.FOLLOWING -> OutlinedButton(onClick = onFollow, enabled = !busyFollow, modifier = Modifier.weight(1f)) {
                                Text("Siguiendo")
                            }
                        }
                        OutlinedButton(onClick = { navController.openP2PChat(target) }, modifier = Modifier.weight(1f)) {
                            Icon(painterResource(R.drawable.chat_bubble), null, Modifier.size(18.dp))
                            Text("  Mensaje")
                        }
                    }
                }
            }
            if (isMe) {
                item(key = "requests") { FollowRequestsSection(onOpenProfile = { navController.openP2PProfile(it) }) }
            }
            item(key = "divider") { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) }

            val list = posts
            when {
                canSee == false -> item(key = "private") {
                    P2PEmptyState(
                        R.drawable.lock,
                        "Esta cuenta es privada",
                        if (followState == FollowState.REQUESTED) {
                            "Tu solicitud está enviada. Cuando ${user.displayName} la apruebe verás sus publicaciones."
                        } else {
                            "Solicita seguirla para ver sus publicaciones. Le llegará cifrada, sólo a ella."
                        },
                        Modifier.fillMaxWidth(),
                    )
                }

                list == null -> item(key = "loading") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }

                list.isEmpty() -> item(key = "empty") {
                    P2PEmptyState(
                        R.drawable.social_feed,
                        if (isMe) "Aún no has publicado" else "Sin publicaciones",
                        if (isMe) "Comparte lo que escuchas: una canción, una foto o lo que sientes." else "${user.displayName} todavía no ha compartido nada.",
                        Modifier.fillMaxWidth(),
                    ) { if (isMe) FilledTonalButton(onClick = { composing = true }) { Text("Publicar") } }
                }

                else -> items(list, key = { it.id }) { post ->
                    Column {
                        P2PPostCard(
                            post = post,
                            onOpenProfile = { if (it != target) navController.openP2PProfile(it) },
                            onReact = { emoji ->
                                replace(post.withMyReaction(emoji))
                                scope.launch {
                                    val result = runCatching { P2PSocial.react(post, emoji) }.getOrDefault(post.myReaction)
                                    if (result != emoji) replace(post.withMyReaction(result))
                                }
                            },
                            onComments = { commentsFor = post },
                            onDelete = if (isMe) {
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
            onCommented = {
                posts?.firstOrNull { it.id == post.id }?.let { current -> replace(current.copy(comments = current.comments + 1)) }
            },
        )
    }
    if (composing) {
        P2PComposerSheet(
            onDismiss = { composing = false },
            onPosted = {
                composing = false
                version++
            },
        )
    }
    photoViewer?.let { (photos, start) ->
        P2PPhotoViewer(photos = photos, initialPage = start, onDismiss = { photoViewer = null }, title = user.displayName)
    }
    if (editing && isMe) {
        EditProfileSheet(
            user = user,
            onDismiss = { editing = false },
            onSaved = { ok ->
                editing = false
                scope.launch {
                    snackbar.showSnackbar(if (ok) "Perfil actualizado" else "Guardado en tu teléfono. Se publicará al reconectar.")
                }
                version++
            },
        )
    }
    if (showSettings && isMe) {
        AccountSettingsSheet(
            user = user,
            onDismiss = { showSettings = false },
            onShowWords = { list ->
                showSettings = false
                words = list
            },
            onSignedOut = {
                showSettings = false
                navController.navigateUp()
            },
        )
    }
}

private val BannerHeight = 168.dp
private val HeaderAvatarSize = 112.dp

/**
 * Cabecera estilo Facebook: portada grande con degradado (o foto) y la foto de perfil
 * montada encima. En mi perfil, la portada y la foto llevan botón para editarlas.
 */
@Composable
private fun ProfileHeader(
    user: P2PUser,
    posts: Int?,
    counts: Pair<Int, Int>?,
    onEdit: (() -> Unit)?,
    onOpenPhoto: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Box(Modifier.fillMaxWidth()) {
            P2PProfileBanner(
                user.banner,
                Modifier
                    .fillMaxWidth()
                    .height(BannerHeight),
            )
            if (onEdit != null) {
                // Sobre la portada siempre hay fondo oscuro detrás: blanco sobre negro al 55 %.
                Surface(
                    onClick = onEdit,
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = BannerHeight - 48.dp, end = 12.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(painterResource(R.drawable.image), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Editar portada", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Box(Modifier.padding(start = 16.dp, top = BannerHeight - HeaderAvatarSize / 2)) {
                Box(
                    Modifier
                        .size(HeaderAvatarSize)
                        .clip(CircleShape)
                        .background(scheme.surface)
                        .then(
                            if (onOpenPhoto != null) {
                                Modifier.clickable(onClickLabel = "Ver foto de perfil", onClick = onOpenPhoto)
                            } else {
                                Modifier
                            },
                        )
                        .padding(4.dp),
                ) {
                    P2PAvatar(user, size = HeaderAvatarSize - 8.dp, ring = true)
                }
                if (onEdit != null) {
                    Surface(
                        onClick = onEdit,
                        shape = CircleShape,
                        color = scheme.secondaryContainer,
                        contentColor = scheme.onSecondaryContainer,
                        border = androidx.compose.foundation.BorderStroke(2.dp, scheme.surface),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .semantics { contentDescription = "Cambiar foto de perfil" },
                    ) {
                        Icon(painterResource(R.drawable.edit), contentDescription = null, modifier = Modifier.padding(8.dp).size(18.dp))
                    }
                }
            }
        }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                user.displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            if (user.about.isNotBlank()) {
                Text(user.about, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                user.fingerprint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ProfileCount(posts?.let { if (it >= 30) "30+" else it.toString() } ?: "–", "Publicaciones")
            ProfileCount(counts?.first?.let { if (it >= 500) "500+" else it.toString() } ?: "–", "Seguidores")
            ProfileCount(counts?.second?.toString() ?: "–", "Siguiendo")
        }
    }
    }
}

@Composable
private fun ProfileCount(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Solicitudes de seguimiento recibidas (cuenta privada). */
@Composable
private fun FollowRequestsSection(onOpenProfile: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val requests by P2PSocial.pendingFollowRequests.collectAsState(initial = emptyList())
    if (requests.isEmpty()) return
    LaunchedEffect(requests.size) { P2PSocial.loadProfiles(requests.map { it.pubkey }) }
    Column(Modifier.padding(vertical = 4.dp)) {
        P2PSectionTitle("Solicitudes para seguirte (${requests.size})")
        requests.forEach { request ->
            val requester = rememberP2PUser(request.pubkey)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(onClick = { onOpenProfile(request.pubkey) }, shape = RoundedCornerShape(50)) {
                    P2PAvatar(requester)
                }
                Text(
                    requester.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = { scope.launch { P2PMessaging.approveFollower(request.pubkey) } }) { Text("Aprobar") }
                IconButton(onClick = { scope.launch { P2PMessaging.rejectFollower(request.pubkey) } }) {
                    Icon(painterResource(R.drawable.close), contentDescription = "Rechazar a ${requester.displayName}")
                }
            }
        }
    }
}

/** Editar nombre, biografía, foto y si la cuenta es privada. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProfileSheet(user: P2PUser, onDismiss: () -> Unit, onSaved: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(user.name) }
    var about by rememberSaveable { mutableStateOf(user.about) }
    var picture by remember { mutableStateOf(user.picture) }
    var isPrivate by rememberSaveable { mutableStateOf(user.isPrivate) }
    var banner by rememberSaveable { mutableStateOf(user.banner) }
    var bannerBusy by remember { mutableStateOf(false) }
    var bannerError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // La foto de portada se comprime y se sube a Blossom (como las fotos de las publicaciones).
    val bannerPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            bannerBusy = true
            bannerError = null
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { P2PSocial.compressPhoto(context, uri, maxSide = 1600) }
                val url = bytes?.let { runCatching { P2PSocial.uploadPhoto(it) }.getOrNull() }
                if (url != null) banner = url else bannerError = "No se pudo subir la foto. Revisa la conexión e inténtalo otra vez."
                bannerBusy = false
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                withContext(Dispatchers.IO) { P2PSocial.avatarDataUri(context, uri) }?.let { picture = it }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Editar perfil", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                P2PProfileBanner(
                    banner,
                    Modifier
                        .fillMaxWidth()
                        .height(112.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
                if (bannerBusy) CircularProgressIndicator(color = Color.White)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Portada", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(
                    "Elige un degradado o sube una foto. Los degradados no suben nada.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val selectedPreset = if (banner?.startsWith("https://") == true) null else P2PBannerPreset.of(banner)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    P2PBannerPreset.entries.forEach { preset ->
                        val selected = preset == selectedPreset
                        val shape = RoundedCornerShape(12.dp)
                        Box(
                            Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(shape)
                                .drawBehind { drawP2PBannerPreset(preset) }
                                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                                .selectable(
                                    selected = selected,
                                    enabled = !bannerBusy,
                                    role = Role.RadioButton,
                                    onClick = { banner = preset.value },
                                )
                                .semantics { contentDescription = preset.label },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(painterResource(R.drawable.check), contentDescription = null, tint = Color.White)
                        }
                    }
                }
                OutlinedButton(
                    onClick = { bannerPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !bannerBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(painterResource(R.drawable.image), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Subir foto de portada")
                }
                bannerError?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                P2PAvatar(user.copy(name = name, picture = picture), size = 72.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text("Cambiar foto")
                    }
                    if (picture != null) TextButton(onClick = { picture = null }) { Text("Quitar foto") }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Nombre") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = about,
                onValueChange = { about = it.take(160) },
                label = { Text("Sobre ti") },
                supportingText = { Text("${about.length}/160") },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            P2PSettingRow(
                icon = R.drawable.lock,
                title = "Cuenta privada",
                subtitle = if (isPrivate) {
                    "Sólo quien apruebes verá tus publicaciones. Se envían cifradas a cada seguidor."
                } else {
                    "Cualquiera puede ver tus publicaciones en Comunidad."
                },
                onClick = { isPrivate = !isPrivate },
                trailing = { Switch(checked = isPrivate, onCheckedChange = null) },
            )
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        val ok = runCatching { P2PSocial.updateMyProfile(name, about, picture, isPrivate, banner) }.getOrDefault(false)
                        saving = false
                        onSaved(ok)
                    }
                },
                enabled = !saving && !bannerBusy && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Guardar")
            }
        }
    }
}

/** Ajustes de la cuenta: privacidad, copia de seguridad y cerrar sesión. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
private fun AccountSettingsSheet(user: P2PUser, onDismiss: () -> Unit, onShowWords: (List<String>) -> Unit, onSignedOut: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var listeningPublic by remember { mutableStateOf(P2PSocial.listeningVisibility == ListeningVisibility.EVERYONE) }
    var nearbyVisible by remember { mutableStateOf(P2PSocial.nearbyVisible) }
    var noWords by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val backedUp = remember { P2PIdentity.isBackedUp(context) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
        ) {
            Text(
                "Tu cuenta",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "Vive sólo en este teléfono. No hay servidor con tu contraseña: tus 12 palabras son la única llave.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            P2PSectionTitle("Privacidad", Modifier.padding(top = 8.dp))
            P2PSettingRow(
                icon = R.drawable.headphones,
                title = "Mostrar lo que escucho",
                subtitle = if (listeningPublic) "Tus amigos ven tu canción en «Escuchando ahora»." else "Nadie ve lo que escuchas.",
                onClick = {
                    listeningPublic = !listeningPublic
                    P2PSocial.listeningVisibility = if (listeningPublic) ListeningVisibility.EVERYONE else ListeningVisibility.NOBODY
                    if (!listeningPublic) P2PPresence.onNowPlaying(null)
                },
                trailing = { Switch(checked = listeningPublic, onCheckedChange = null) },
            )
            P2PSettingRow(
                icon = R.drawable.bluetooth,
                title = "Que me vean cerca",
                subtitle = "Aparecer en el radar de quien tenga Dezzy a tu lado.",
                onClick = {
                    nearbyVisible = !nearbyVisible
                    P2PSocial.nearbyVisible = nearbyVisible
                },
                trailing = { Switch(checked = nearbyVisible, onCheckedChange = null) },
            )
            P2PSectionTitle("Copia de seguridad", Modifier.padding(top = 8.dp))
            P2PSettingRow(
                icon = R.drawable.backup,
                title = "Ver mis 12 palabras",
                subtitle = if (backedUp) "Guárdalas en papel. Con ellas recuperas tu cuenta." else "Aún no confirmaste que las guardaste.",
                onClick = {
                    val list = P2PIdentity.backupWords(context)
                    if (list.isNullOrEmpty()) noWords = true else onShowWords(list)
                },
            )
            P2PSettingRow(
                icon = if (copied) R.drawable.check else R.drawable.content_copy,
                title = if (copied) "ID copiado" else "Copiar mi ID",
                subtitle = user.npub.take(20) + "…",
                onClick = {
                    clipboard.setText(AnnotatedString(user.npub))
                    copied = true
                },
            )
            P2PSettingRow(
                icon = R.drawable.share,
                title = "Compartir mi enlace",
                onClick = { P2PSocial.myInviteLink()?.let { shareText(context, "Agrégame en Dezzy: $it", "Compartir mi enlace") } },
            )
            P2PSectionTitle("Sesión", Modifier.padding(top = 8.dp))
            P2PSettingRow(
                icon = R.drawable.logout,
                title = "Cerrar sesión en este teléfono",
                subtitle = "Borra tu cuenta, tus chats y tus claves de este dispositivo.",
                onClick = { confirmSignOut = true },
            )
        }
    }

    if (noWords) {
        AlertDialog(
            onDismissRequest = { noWords = false },
            icon = { Icon(painterResource(R.drawable.info), null) },
            title = { Text("Sin palabras guardadas") },
            text = { Text("Entraste con una clave privada (nsec), así que no hay 12 palabras. Guarda esa clave en un lugar seguro.") },
            confirmButton = { TextButton(onClick = { noWords = false }) { Text("Entendido") } },
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            icon = { Icon(painterResource(R.drawable.logout), null) },
            title = { Text("¿Cerrar sesión?") },
            text = {
                Text(
                    if (backedUp) {
                        "Se borrarán de este teléfono tu cuenta, tus chats y tus claves. Podrás volver a entrar con tus 12 palabras."
                    } else {
                        "No has confirmado que guardaste tus 12 palabras. Si cierras sesión sin ellas perderás esta cuenta para siempre."
                    },
                    textAlign = TextAlign.Start,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    scope.launch {
                        P2PSocial.signOut()
                        onSignedOut()
                    }
                }) { Text("Cerrar sesión", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancelar") } },
        )
    }
}
