/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.ui.screens.social

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.navigation.NavController
import com.arturo254.opentune.LocalPlayerConnection
import com.arturo254.opentune.R
import com.arturo254.opentune.innertube.YouTube
import com.arturo254.opentune.innertube.models.WatchEndpoint
import com.arturo254.opentune.models.toMediaMetadata
import com.arturo254.opentune.playback.queues.YouTubeQueue
import com.arturo254.opentune.social.DezzySocial
import com.arturo254.opentune.social.FriendActivity
import com.arturo254.opentune.social.MusicPost
import com.arturo254.opentune.social.PostComment
import com.arturo254.opentune.social.SupabaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ACTIVITY_REFRESH_MS = 30_000L

/**
 * Pantalla central de la red social musical: lo que escuchan tus amigos ahora
 * mismo, y debajo el feed de música que comparten.
 *
 * Sustituye al feed de fotos anterior. Aquí el contenido SIEMPRE es música: cada
 * tarjeta referencia una canción/álbum/playlist real y se puede reproducir de
 * inmediato, que es lo que separa esto de un Instagram con carátulas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicFeedScreen(navController: NavController) {
    val userId by SupabaseAuth.currentUserId.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val nowPlaying by (playerConnection?.mediaMetadata
        ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()

    var activity by remember { mutableStateOf<List<FriendActivity>>(emptyList()) }
    var posts by remember { mutableStateOf<List<MusicPost>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showDiscover by remember { mutableStateOf(false) }
    var showComposer by remember { mutableStateOf(false) }
    var commentsPost by remember { mutableStateOf<MusicPost?>(null) }

    suspend fun reload() {
        activity = DezzySocial.friendActivity()
        posts = if (showDiscover) DezzySocial.getDiscover() else DezzySocial.getFeed()
        loading = false
    }

    // La actividad caduca: se refresca sola mientras la pantalla está visible.
    LaunchedEffect(userId, showDiscover) {
        if (userId == null) { loading = false; return@LaunchedEffect }
        loading = true
        reload()
        while (true) {
            delay(ACTIVITY_REFRESH_MS)
            activity = DezzySocial.friendActivity()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Comunidad", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate("social") }) {
                        Icon(painterResource(R.drawable.social_friends), contentDescription = "Amigos")
                    }
                },
            )
        },
    ) { padding ->
        when {
            // Sin sesión se muestra directamente el acceso, no un aviso que
            // obligue a dar otro salto de navegación.
            userId == null -> Box(Modifier.padding(padding)) {
                DezzyAuthScreen(onAuthenticated = {})
            }

            loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                // ── Composer estilo Threads: comparte lo que suena ahora ──
                item {
                    ComposerRow(
                        avatarUrl = null,
                        enabled = nowPlaying != null,
                        onClick = { showComposer = true },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                }

                // ── Pistas más comentadas (estilo YouTube Music) ──
                item {
                    TopCommentedTracksSection(
                        header = { SectionHeader("Pistas más comentadas") },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                }

                // ── Amigos escuchando ahora ──
                if (activity.isNotEmpty()) {
                    item {
                        SectionHeader("Escuchando ahora")
                        FriendActivityStrip(activity) { friend ->
                            friend.videoId?.let { playVideo(scope, playerConnection, it) }
                        }
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                    }
                }

                // ── Selector Siguiendo / Descubrir ──
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = !showDiscover,
                            onClick = { showDiscover = false },
                            label = { Text("Siguiendo") },
                        )
                        FilterChip(
                            selected = showDiscover,
                            onClick = { showDiscover = true },
                            label = { Text("Descubrir") },
                        )
                    }
                }

                if (posts.isEmpty()) {
                    item {
                        SocialEmptyState(
                            iconRes = R.drawable.social_feed,
                            title = if (showDiscover) "Todavía no hay nada" else "Tu feed está vacío",
                            description = if (showDiscover) {
                                "Cuando la comunidad comparta música, aparecerá aquí."
                            } else {
                                "Sigue a más gente o comparte una canción desde el reproductor para empezar."
                            },
                        )
                    }
                } else {
                    items(posts, key = { it.postId }) { post ->
                        MusicPostCard(
                            post = post,
                            onPlay = { playVideo(scope, playerConnection, post.entityId) },
                            onLike = {
                                scope.launch {
                                    val nowLiked = DezzySocial.toggleLike(post.postId)
                                    if (nowLiked != null) {
                                        posts = posts.map {
                                            if (it.postId == post.postId) {
                                                it.copy(
                                                    likedByMe = nowLiked,
                                                    likesCount = (it.likesCount + if (nowLiked) 1 else -1)
                                                        .coerceAtLeast(0),
                                                )
                                            } else it
                                        }
                                    }
                                }
                            },
                            onOpenProfile = { navController.navigate("social/profile/${post.userId}") },
                            onComment = { commentsPost = post },
                            onShare = { sharePost(context, post) },
                        )
                    }
                }
            }
        }
    }

    // ── Composer: publicar la canción actual con un texto opcional ──
    if (showComposer) {
        val meta = nowPlaying
        ShareCurrentSongDialog(
            title = meta?.title,
            artist = meta?.artists?.joinToString(", ") { it.name },
            artworkUrl = meta?.thumbnailUrl,
            onDismiss = { showComposer = false },
            onPublish = { caption ->
                showComposer = false
                val m = meta ?: return@ShareCurrentSongDialog
                scope.launch {
                    val id = DezzySocial.createPost(
                        entityType = "song",
                        entityId = m.id,
                        entityTitle = m.title,
                        entitySubtitle = m.artists.joinToString(", ") { it.name }.ifBlank { null },
                        artworkUrl = m.thumbnailUrl,
                        caption = caption.ifBlank { null },
                    )
                    if (id != null && !showDiscover) posts = DezzySocial.getFeed()
                }
            },
        )
    }

    // ── Comentarios de una publicación ──
    commentsPost?.let { post ->
        CommentsSheet(
            post = post,
            onDismiss = { commentsPost = null },
        )
    }
}

/** Fila superior tipo Threads: avatar + "¿Qué novedades tienes?". */
@Composable
private fun ComposerRow(avatarUrl: String?, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ListeningAvatar(avatarUrl = avatarUrl, isPlaying = false, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (enabled) "¿Qué novedades tienes?" else "Reproduce algo para compartirlo",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.social_feed),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareCurrentSongDialog(
    title: String?,
    artist: String?,
    artworkUrl: String?,
    onDismiss: () -> Unit,
    onPublish: (String) -> Unit,
) {
    var caption by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compartir en Comunidad") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArtworkThumb(url = artworkUrl, size = 52.dp, corner = 8.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title ?: "Nada sonando",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!artist.isNullOrBlank()) {
                            Text(
                                text = artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                DezzyTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    placeholder = "Escribe algo (opcional)",
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    maxLength = 280,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = title != null, onClick = { onPublish(caption.trim()) }) {
                Text("Publicar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentsSheet(post: MusicPost, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var comments by remember { mutableStateOf<List<PostComment>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(post.postId) {
        comments = DezzySocial.getComments(post.postId)
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
        ) {
            Text("Comentarios", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                comments.isEmpty() -> Text(
                    "Sé el primero en comentar.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                else -> Column {
                    comments.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            ListeningAvatar(avatarUrl = c.authorAvatar, isPlaying = false, size = 34.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(c.authorName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                                Text(c.content, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            DezzyComposerField(
                value = input,
                onValueChange = { input = it },
                onSend = { text ->
                    scope.launch {
                        DezzySocial.addComment(post.postId, text)
                        comments = DezzySocial.getComments(post.postId)
                    }
                },
                placeholder = "Añade un comentario…",
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
            )
        }
    }
}

/** Comparte la canción de un post al sistema (WhatsApp, etc.) por enlace de YouTube. */
private fun sharePost(context: android.content.Context, post: MusicPost) {
    val url = "https://music.youtube.com/watch?v=${post.entityId}"
    val text = "${post.entityTitle}${post.entitySubtitle?.let { " — $it" } ?: ""}\n$url"
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Compartir"))
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * Tira horizontal de amigos escuchando — el equivalente al panel lateral de
 * Spotify, adaptado a móvil. Horizontal para que no empuje el feed hacia abajo.
 */
@Composable
private fun FriendActivityStrip(
    activity: List<FriendActivity>,
    onClick: (FriendActivity) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(activity, key = { it.userId }) { friend ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(76.dp)
                    .clickable { onClick(friend) },
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    ListeningAvatar(
                        avatarUrl = friend.avatarUrl,
                        isPlaying = friend.isPlaying,
                        size = 62.dp,
                    )
                    // Carátula pequeña encima del avatar: se ve QUÉ escucha sin
                    // tener que leer el texto.
                    ArtworkThumb(
                        url = friend.artworkUrl,
                        size = 24.dp,
                        corner = 6.dp,
                        modifier = Modifier.offset(x = 2.dp, y = 2.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = friend.name.substringBefore(' '),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = friend.title.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Tarjeta del feed. La carátula manda: es lo que hace reconocible la música de
 * un vistazo, igual que en la app.
 */
@Composable
private fun MusicPostCard(
    post: MusicPost,
    onPlay: () -> Unit,
    onLike: () -> Unit,
    onOpenProfile: () -> Unit,
    onComment: () -> Unit,
    onShare: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        // Autor
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenProfile)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ListeningAvatar(avatarUrl = post.authorAvatar, isPlaying = false, size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = post.authorName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                val meta = listOfNotNull(
                    post.authorUsername?.let { "@$it" },
                    relativeTime(post.createdAt).takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }

        if (!post.caption.isNullOrBlank()) {
            Text(
                text = post.caption,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // Entidad musical — el corazón de la tarjeta
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clickable(onClick = onPlay),
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkThumb(url = post.artworkUrl, size = 64.dp, corner = 10.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = post.entityTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!post.entitySubtitle.isNullOrBlank()) {
                        Text(
                            text = post.entitySubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = when (post.entityType) {
                            "album" -> "Álbum"
                            "playlist" -> "Playlist"
                            "artist" -> "Artista"
                            else -> "Canción"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = "Reproducir",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // Acciones estilo Threads: me gusta · comentar · compartir
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 16.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PostAction(
                iconRes = if (post.likedByMe) R.drawable.social_heart_filled else R.drawable.social_heart,
                tint = if (post.likedByMe) NowPlayingGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                count = post.likesCount,
                onClick = onLike,
            )
            PostAction(
                iconRes = R.drawable.social_chat,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                count = post.commentsCount,
                onClick = onComment,
            )
            PostAction(
                iconRes = R.drawable.social_send,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                count = 0,
                onClick = onShare,
            )
        }
    }
}

@Composable
private fun PostAction(iconRes: Int, tint: Color, count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Ver [playVideoId] en TopCommentedSection.kt: resolver + reproducir compartido. */
private fun playVideo(
    scope: kotlinx.coroutines.CoroutineScope,
    playerConnection: com.arturo254.opentune.playback.PlayerConnection?,
    videoId: String,
) = playVideoId(scope, playerConnection, videoId)
