/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.p2p

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.p2p.P2PComment
import com.dezzy.music.p2p.P2PPost
import com.dezzy.music.p2p.P2PSocial
import com.dezzy.music.p2p.P2PSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Publicación al estilo Threads/Instagram: autor, texto, foto, canción y acciones. */
@Composable
fun P2PPostCard(
    post: P2PPost,
    onOpenProfile: (String) -> Unit,
    /** Reaccionar con un emoji, o quitar la reacción con null. */
    onReact: (String?) -> Unit,
    onComments: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val author = rememberP2PUser(post.author)
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var viewingPhoto by remember { mutableStateOf(false) }

    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenProfile(post.author) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                P2PAvatar(author, size = 40.dp)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            author.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (post.isPrivate) {
                            Icon(
                                painterResource(R.drawable.lock),
                                contentDescription = "Sólo seguidores",
                                modifier = Modifier.size(14.dp),
                                tint = scheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(timeAgo(post.createdAt), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(R.drawable.more_vert), contentDescription = "Más opciones")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Ver perfil") },
                        leadingIcon = { Icon(painterResource(R.drawable.person), null) },
                        onClick = {
                            menu = false
                            onOpenProfile(post.author)
                        },
                    )
                    if (post.text.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("Compartir texto") },
                            leadingIcon = { Icon(painterResource(R.drawable.share), null) },
                            onClick = {
                                menu = false
                                shareText(context, post.text + (post.song?.let { "\n${it.title} · ${it.artist}\n${it.url}" } ?: ""))
                            },
                        )
                    }
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text("Borrar publicación") },
                            leadingIcon = { Icon(painterResource(R.drawable.delete), null) },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
        if (post.text.isNotBlank()) {
            Text(
                post.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 52.dp, top = 4.dp),
            )
        }
        Column(Modifier.padding(start = 52.dp)) {
            post.imageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = "Foto de la publicación",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClickLabel = "Ver foto a pantalla completa") { viewingPhoto = true },
                )
                if (viewingPhoto) {
                    P2PPhotoViewer(
                        photos = listOf(url),
                        onDismiss = { viewingPhoto = false },
                        title = author.displayName,
                        caption = post.text,
                    )
                }
            }
            post.song?.let { song ->
                P2PSongCard(
                    song = song,
                    onPlay = { playP2PSong(playerConnection, song) },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(),
                )
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ReactionAction(post = post, onReact = onReact)
                PostAction(R.drawable.chat_bubble, post.comments, "Comentarios", false, onComments)
                PostAction(R.drawable.share, 0, "Compartir", false) {
                    val link = post.song?.url
                    shareText(context, listOfNotNull(post.text.takeIf { it.isNotBlank() }, link).joinToString("\n"))
                }
            }
        }
    }
}

/**
 * Reacciones al estilo Facebook: un toque pone o quita ❤️; mantener pulsado abre los seis
 * emojis. Con TalkBack, cada emoji está también en el menú de acciones.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReactionAction(post: P2PPost, onReact: (String?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var picker by remember { mutableStateOf(false) }
    val mine = post.myReaction
    val tint = if (mine != null) scheme.primary else scheme.onSurfaceVariant
    val others = post.topReactions.filter { it != mine }.take(2)
    val summary = if (post.likes > 0) {
        "${post.likes} " + (if (post.likes == 1) "reacción" else "reacciones") +
            (mine?.let { ", la tuya $it" } ?: "")
    } else {
        "Sin reacciones"
    }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .combinedClickable(
                    onClickLabel = if (mine != null) "Quitar tu reacción" else "Reaccionar con ${P2PSocial.DEFAULT_REACTION}",
                    onLongClickLabel = "Elegir reacción",
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        picker = true
                    },
                    onClick = { onReact(if (mine != null) null else P2PSocial.DEFAULT_REACTION) },
                )
                .heightIn(min = 48.dp)
                .padding(horizontal = 10.dp)
                .semantics {
                    contentDescription = "Reacciones"
                    stateDescription = summary
                    customActions = P2PSocial.REACTIONS.map { emoji ->
                        CustomAccessibilityAction(if (emoji == mine) "Quitar $emoji" else "Reaccionar con $emoji") {
                            onReact(if (emoji == mine) null else emoji)
                            true
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (mine != null) {
                Text(mine, fontSize = 18.sp)
            } else {
                Icon(painterResource(R.drawable.favorite_border), null, Modifier.size(20.dp), tint = tint)
            }
            if (others.isNotEmpty()) Text(others.joinToString(""), fontSize = 14.sp)
            if (post.likes > 0) Text("${post.likes}", style = MaterialTheme.typography.labelLarge, color = tint)
        }
        DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                P2PSocial.REACTIONS.forEach { emoji ->
                    val isMine = emoji == mine
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (isMine) scheme.secondaryContainer else Color.Transparent)
                            .clickable {
                                picker = false
                                onReact(if (isMine) null else emoji)
                            }
                            .semantics {
                                contentDescription = if (isMine) "Quitar $emoji" else "Reaccionar con $emoji"
                                selected = isMine
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emoji, fontSize = 26.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PostAction(icon: Int, count: Int, label: String, active: Boolean, onClick: () -> Unit) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp)
            .semantics {
                contentDescription = label
                if (count > 0) stateDescription = "$count"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = tint)
        if (count > 0) Text("$count", style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/** Hoja de comentarios de una publicación. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P2PCommentsSheet(post: P2PPost, onDismiss: () -> Unit, onOpenProfile: (String) -> Unit, onCommented: () -> Unit) {
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<P2PComment>?>(null) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var replyingTo by remember { mutableStateOf<P2PComment?>(null) }
    LaunchedEffect(post.id) { comments = runCatching { P2PSocial.comments(post) }.getOrDefault(emptyList()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Text(
                "Comentarios",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (post.isPrivate) {
                Text(
                    "Cifrados: sólo los ves tú y quien publicó.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            HorizontalDivider(Modifier.padding(top = 12.dp))
            val list = comments
            when {
                list == null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                list.isEmpty() -> P2PEmptyState(
                    R.drawable.chat_bubble,
                    "Sin comentarios todavía",
                    "Sé la primera persona en decir algo.",
                    Modifier.fillMaxWidth(),
                )

                else -> LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(list, key = { it.id }) { c ->
                        val user = rememberP2PUser(c.author)
                        val isReply = c.replyTo != null
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = if (isReply) 64.dp else 16.dp, end = 16.dp, top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            P2PAvatar(
                                user,
                                size = if (isReply) 28.dp else 36.dp,
                                modifier = Modifier.clickable(onClickLabel = "Ver perfil de ${user.displayName}") { onOpenProfile(c.author) },
                            )
                            Column(Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        user.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Text(timeAgo(c.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(c.text, style = MaterialTheme.typography.bodyMedium)
                                TextButton(
                                    onClick = { replyingTo = c },
                                    contentPadding = PaddingValues(horizontal = 0.dp),
                                ) {
                                    Text("Responder", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
            replyingTo?.let { target ->
                val targetUser = rememberP2PUser(target.author)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Respondiendo a ${targetUser.displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { replyingTo = null }) {
                        Icon(painterResource(R.drawable.close), contentDescription = "Cancelar respuesta")
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(1000) },
                    placeholder = { Text(if (replyingTo != null) "Escribe tu respuesta…" else "Añade un comentario…") },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    enabled = input.isNotBlank() && !sending,
                    onClick = {
                        sending = true
                        val text = input
                        val target = replyingTo
                        scope.launch {
                            val added = runCatching { P2PSocial.addComment(post, text, target) }.getOrNull()
                            sending = false
                            if (added != null) {
                                input = ""
                                replyingTo = null
                                comments = P2PSocial.threadComments(comments.orEmpty() + added)
                                onCommented()
                            }
                        }
                    },
                ) {
                    if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(painterResource(R.drawable.send), contentDescription = "Enviar comentario")
                }
            }
        }
    }
}

/** Hoja para publicar: texto, lo que suena ahora y una foto opcional. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P2PComposerSheet(onDismiss: () -> Unit, onPosted: () -> Unit, initialSong: P2PSong? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val nowPlaying = playerConnection?.mediaMetadata?.collectAsState()?.value
    val isPrivate = P2PSocial.myProfile?.isPrivate == true
    var text by remember { mutableStateOf("") }
    var song by remember { mutableStateOf(initialSong ?: nowPlaying?.toP2PSong()) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var posting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) photo = uri }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Nueva publicación", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
            if (isPrivate) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.lock), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(
                            "Cuenta privada: se cifra sólo para tus seguidores aprobados." + if (photo != null) " La foto sí queda en un servidor público por su enlace." else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(2000) },
                placeholder = { Text("¿Qué estás escuchando?") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            song?.let { s ->
                InputChip(
                    selected = true,
                    onClick = { song = null },
                    label = { Text("${s.title} · ${s.artist}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(painterResource(R.drawable.music_note), null, Modifier.size(18.dp)) },
                    trailingIcon = { Icon(painterResource(R.drawable.close), contentDescription = "Quitar canción", modifier = Modifier.size(18.dp)) },
                )
            } ?: nowPlaying?.let { meta ->
                AssistChip(
                    onClick = { song = meta.toP2PSong() },
                    label = { Text("Añadir «${meta.title}»", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(painterResource(R.drawable.music_note), null, Modifier.size(18.dp)) },
                )
            }
            photo?.let { uri ->
                Box {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Foto elegida",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(16.dp)),
                    )
                    IconButton(onClick = { photo = null }, modifier = Modifier.align(Alignment.TopEnd)) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)) {
                            Icon(painterResource(R.drawable.close), contentDescription = "Quitar foto", modifier = Modifier.padding(6.dp))
                        }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(painterResource(R.drawable.image), contentDescription = "Añadir foto")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = !posting && (text.isNotBlank() || song != null || photo != null),
                    onClick = {
                        posting = true
                        error = null
                        scope.launch {
                            val bytes = photo?.let { uri -> withContext(Dispatchers.IO) { P2PSocial.compressPhoto(context, uri) } }
                            val ok = runCatching { P2PSocial.createPost(text.trim(), song, bytes) }.getOrDefault(false)
                            posting = false
                            if (ok) onPosted() else error = "No se pudo publicar. Revisa tu conexión e inténtalo de nuevo."
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    if (posting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Publicar")
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}
