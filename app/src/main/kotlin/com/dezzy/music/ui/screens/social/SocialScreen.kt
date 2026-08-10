/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.social

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dezzy.music.R
import com.dezzy.music.social.ContactsRepository
import com.dezzy.music.social.DeviceContact
import com.dezzy.music.social.Friend
import com.dezzy.music.social.FriendRequest
import com.dezzy.music.social.GoogleIdentityBridge
import com.dezzy.music.social.MyProfile
import com.dezzy.music.social.SocialRepository
import com.dezzy.music.social.SocialResult
import com.dezzy.music.social.SocialUser
import com.dezzy.music.social.SupabaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialScreen(navController: NavController) {
    val userId by SupabaseAuth.currentUserId.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Amigos", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
                actions = {
                    // Bandeja de mensajes (estilo Instagram): la lista de
                    // conversaciones, en vez de tener que entrar amigo por amigo.
                    IconButton(onClick = { navController.navigate("messages") }) {
                        Icon(painterResource(R.drawable.social_chat), contentDescription = "Mensajes")
                    }
                    // El feed musical (actividad + música compartida) sustituye al
                    // antiguo feed de fotos, que sigue accesible en la ruta "feed".
                    IconButton(onClick = { navController.navigate("community") }) {
                        Icon(painterResource(R.drawable.social_feed), contentDescription = "Comunidad")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Puerta de entrada: cuenta propia de Dezzy (correo + contraseña).
            // Google dejó de ser el camino obligatorio; sigue disponible desde el
            // banner de vinculación una vez dentro.
            if (userId == null) {
                DezzyAuthScreen(onAuthenticated = { /* currentUserId emite y recompone */ })
            } else {
                SocialHub(navController)
            }
        }
    }
}

private fun openChat(navController: NavController, friendId: String, friendName: String) {
    val name = java.net.URLEncoder.encode(friendName, "UTF-8")
    navController.navigate("chat/$friendId/$name")
}

@Composable
private fun SocialHub(navController: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SocialUser>>(emptyList()) }
    var requests by remember { mutableStateOf<List<FriendRequest>>(emptyList()) }
    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var myProfile by remember { mutableStateOf<MyProfile?>(null) }

    val inviterId by SupabaseAuth.currentUserId.collectAsState()
    var contactsOnApp by remember { mutableStateOf<List<SocialUser>>(emptyList()) }
    var contactsToInvite by remember { mutableStateOf<List<DeviceContact>>(emptyList()) }
    var contactsSynced by remember { mutableStateOf(false) }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scope.launch {
            val result = ContactsRepository.sync(context)
            contactsOnApp = result.onApp
            contactsToInvite = result.toInvite
            contactsSynced = true
        }
    }

    suspend fun reload() {
        SocialRepository.consumePendingReferral()
        myProfile = SocialRepository.getMyProfile()
        requests = SocialRepository.getFriendRequests()
        friends = SocialRepository.getFriends()
    }

    // Reload every time the screen resumes so profile edits (name, @usuario, foto) and new
    // requests/friends show up immediately when returning from another screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumeTick) { reload() }

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        results = SocialRepository.searchUsers(query.trim())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ProfileHeaderCard(
                profile = myProfile,
                onClick = { navController.navigate("social/profile") },
            )
        }

        if (SupabaseAuth.isAnonymous) {
            item { GoogleConnectBanner(onClick = { GoogleIdentityBridge.launchGoogleLink(context) }) }
        }

        item {
            DezzySearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Buscar por @usuario o nombre…",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        status?.let {
            item {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        if (!contactsSynced) {
            item {
                OutlinedButton(
                    onClick = { contactsPermissionLauncher.launch(android.Manifest.permission.READ_CONTACTS) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(painterResource(R.drawable.social_friends), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Conectar contactos para encontrar amigos")
                }
            }
        }

        if (contactsOnApp.isNotEmpty()) {
            item { SectionTitle("Contactos en Dezzy") }
            items(contactsOnApp, key = { "c_${it.id}" }) { user ->
                UserRow(name = user.name, subtitle = user.username?.let { "@$it" }, avatar = user.avatarUrl) {
                    when (user.relationship) {
                        "friends" -> RelationTag("Amigo")
                        "request_sent" -> RelationTag("Pendiente")
                        else -> AddButton {
                            scope.launch {
                                SocialRepository.sendFriendRequest(user.id)
                                val r = ContactsRepository.sync(context)
                                contactsOnApp = r.onApp; contactsToInvite = r.toInvite
                            }
                        }
                    }
                }
            }
        }

        if (contactsToInvite.isNotEmpty()) {
            item { SectionTitle("Invitar a Dezzy") }
            items(contactsToInvite, key = { "i_${it.name}" }) { contact ->
                UserRow(name = contact.name, subtitle = "No está en Dezzy aún", avatar = null) {
                    OutlinedButton(
                        onClick = {
                            val intent = ContactsRepository.buildInviteIntent(contact.name, inviterId)
                            context.startActivity(android.content.Intent.createChooser(intent, "Invitar a Dezzy"))
                        },
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(painterResource(R.drawable.social_invite), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Invitar")
                    }
                }
            }
        }

        if (results.isNotEmpty()) {
            item { SectionTitle("Resultados") }
            items(results, key = { it.id }) { user ->
                UserRow(
                    name = user.name,
                    subtitle = user.username?.let { "@$it" } ?: user.rank,
                    avatar = user.avatarUrl,
                ) {
                    when (user.relationship) {
                        "friends" -> RelationTag("Amigo")
                        "request_sent" -> RelationTag("Pendiente")
                        "request_received" -> RelationTag("Te invitó")
                        else -> AddButton {
                            scope.launch {
                                status = when (val r = SocialRepository.sendFriendRequest(user.id)) {
                                    is SocialResult.Success -> "Solicitud enviada"
                                    is SocialResult.Error -> r.message
                                }
                                results = SocialRepository.searchUsers(query.trim())
                            }
                        }
                    }
                }
            }
        }

        if (requests.isNotEmpty()) {
            item { SectionTitle("Solicitudes") }
            items(requests, key = { it.requestId }) { req ->
                UserRow(name = req.name, subtitle = "Quiere ser tu amigo", avatar = req.avatarUrl) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalButton(
                            onClick = { scope.launch { SocialRepository.respondFriendRequest(req.requestId, true); reload() } },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(painterResource(R.drawable.social_check), contentDescription = "Aceptar", modifier = Modifier.size(18.dp))
                        }
                        OutlinedButton(
                            onClick = { scope.launch { SocialRepository.respondFriendRequest(req.requestId, false); reload() } },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(painterResource(R.drawable.social_close), contentDescription = "Rechazar", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        item { SectionTitle("Mis amigos") }
        if (friends.isEmpty()) {
            item { FriendsEmptyState() }
        } else {
            items(friends, key = { it.id }) { f ->
                // Tocar el amigo abre su PERFIL musical (seguir, qué suena ahora,
                // su top) — antes lo único accesible era el chat. El botón de
                // chat se conserva como acción secundaria a la derecha.
                UserRow(
                    name = f.name,
                    subtitle = f.username?.let { "@$it" } ?: f.rank,
                    avatar = f.avatarUrl,
                    onClick = { navController.navigate("social/profile/${f.id}") },
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { openChat(navController, f.id, f.name) },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(R.drawable.social_chat),
                                contentDescription = "Chatear",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ProfileHeaderCard(profile: MyProfile?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Avatar(url = profile?.avatarUrl, size = 60.dp, iconSize = 32.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = profile?.name?.ifBlank { null } ?: "Configura tu perfil",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = profile?.username?.let { "@$it" }
                Text(
                    text = sub ?: "Toca para completar tu perfil",
                    fontSize = 13.sp,
                    color = if (sub != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                profile?.bio?.ifBlank { null }?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.social_edit),
                        contentDescription = "Editar perfil",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun Avatar(url: String?, size: androidx.compose.ui.unit.Dp, iconSize: androidx.compose.ui.unit.Dp) {
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.social_profile),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun GoogleConnectBanner(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Conecta con Google",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Guarda tu cuenta y úsala en todos tus dispositivos.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "Conectar",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp),
    )
}

@Composable
private fun RelationTag(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun AddButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Icon(painterResource(R.drawable.social_add), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Agregar")
    }
}

@Composable
private fun FriendsEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.social_friends),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Aún no tienes amigos",
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Búscalos por @usuario arriba o conéctate desde tus contactos.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun UserRow(
    name: String,
    subtitle: String?,
    avatar: String?,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Avatar(url = avatar, size = 46.dp, iconSize = 24.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun AutoConnectView() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var connecting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (connecting) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Conectando tu cuenta…", fontSize = 14.sp)
            return@Column
        }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(84.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.social_friends),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(42.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Únete a la red social", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Conéctate con tu cuenta de Google para tener la misma identidad, amigos y chats en todos tus dispositivos.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { GoogleIdentityBridge.launchGoogleLink(context) },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continuar con Google", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = {
                scope.launch {
                    connecting = true
                    failed = false
                    val ok = SupabaseAuth.ensureSession()
                    connecting = false
                    failed = !ok
                }
            },
        ) {
            Text("Usar solo en este dispositivo", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (failed) {
            Spacer(Modifier.height(8.dp))
            Text(
                "No se pudo conectar. Revisa tu conexión e intenta de nuevo.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

