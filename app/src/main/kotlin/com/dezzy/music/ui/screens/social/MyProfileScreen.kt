/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.social

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.models.toMediaMetadata
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.social.DezzySocial
import com.dezzy.music.social.MusicPost
import com.dezzy.music.social.MusicProfile
import com.dezzy.music.social.SuggestedUser
import com.dezzy.music.social.SupabaseAuth
import kotlinx.coroutines.launch

/**
 * Perfil PROPIO estilo Threads: nombre grande + @usuario, seguidores/siguiendo,
 * botones Editar/Compartir, "Sugerencias para ti" y tus publicaciones ("Hilos").
 *
 * La edición del perfil vive en `social/profile/edit` (la pantalla anterior), a la
 * que lleva "Editar perfil".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyProfileScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val myUserId by SupabaseAuth.currentUserId.collectAsState()
    val playerConnection = LocalPlayerConnection.current

    var profile by remember { mutableStateOf<MusicProfile?>(null) }
    var suggestions by remember { mutableStateOf<List<SuggestedUser>>(emptyList()) }
    var posts by remember { mutableStateOf<List<MusicPost>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    suspend fun reload(uid: String) {
        profile = DezzySocial.getProfile(uid)
        posts = DezzySocial.getUserPosts(uid)
        suggestions = DezzySocial.suggestions()
        loading = false
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(myUserId, resumeTick) {
        val uid = myUserId
        if (uid != null) reload(uid) else loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Perfil", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        when {
            myUserId == null -> Box(Modifier.fillMaxSize().padding(padding)) {
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
                item {
                    ProfileHeaderThreads(
                        profile = profile,
                        onEdit = { navController.navigate("social/profile/edit") },
                        onShare = { shareProfile(context, profile) },
                    )
                }

                if (suggestions.isNotEmpty()) {
                    item {
                        Text(
                            "Sugerencias para ti",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 6.dp),
                        )
                        SuggestionsRow(
                            suggestions = suggestions,
                            onOpen = { navController.navigate("social/profile/${it.userId}") },
                            onFollow = { u ->
                                scope.launch {
                                    DezzySocial.follow(u.userId, true)
                                    suggestions = suggestions.filterNot { it.userId == u.userId }
                                }
                            },
                        )
                    }
                }

                item {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                    Text(
                        "Hilos",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                if (posts.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Todavía no publicaste ningún hilo.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                            )
                        }
                    }
                } else {
                    items(posts, key = { it.postId }) { post ->
                        MyPostRow(post) {
                            playVideo(scope, playerConnection, post.entityId)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeaderThreads(
    profile: MusicProfile?,
    onEdit: () -> Unit,
    onShare: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = profile?.name ?: "Tu perfil",
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                profile?.username?.let {
                    Text(
                        text = "@$it",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            ListeningAvatar(
                avatarUrl = profile?.avatarUrl,
                isPlaying = !profile?.nowPlaying.isNullOrBlank(),
                size = 72.dp,
            )
        }

        if (!profile?.bio.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(profile!!.bio!!, fontSize = 14.sp)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "${profile?.followers ?: 0} seguidores · ${profile?.following ?: 0} siguiendo",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                Text("Editar perfil")
            }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                Text("Compartir perfil")
            }
        }
    }
}

@Composable
private fun SuggestionsRow(
    suggestions: List<SuggestedUser>,
    onOpen: (SuggestedUser) -> Unit,
    onFollow: (SuggestedUser) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(suggestions, key = { it.userId }) { u ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.width(150.dp).clickable { onOpen(u) },
            ) {
                Column(
                    Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Avatar(url = u.avatarUrl, size = 64.dp, iconSize = 32.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        u.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    u.username?.let {
                        Text(
                            "@$it",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onFollow(u) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 6.dp),
                    ) {
                        Text("Seguir", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MyPostRow(post: MusicPost, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkThumb(url = post.artworkUrl, size = 56.dp, corner = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (!post.caption.isNullOrBlank()) {
                Text(post.caption, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
            }
            Text(
                post.entityTitle,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            post.entitySubtitle?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = listOfNotNull(
                    relativeTime(post.createdAt).takeIf { it.isNotBlank() },
                    "${post.likesCount} me gusta".takeIf { post.likesCount > 0 },
                ).joinToString(" · "),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun shareProfile(context: android.content.Context, profile: MusicProfile?) {
    val handle = profile?.username?.let { "@$it" } ?: profile?.name.orEmpty()
    val text = "Sígueme en Dezzy: $handle"
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Compartir perfil"))
}

private fun playVideo(
    scope: kotlinx.coroutines.CoroutineScope,
    playerConnection: com.dezzy.music.playback.PlayerConnection?,
    videoId: String,
) {
    val connection = playerConnection ?: return
    scope.launch {
        val song = runCatching {
            YouTube.queue(listOf(videoId)).getOrNull()?.firstOrNull()
        }.getOrNull()
        connection.playQueue(
            YouTubeQueue(
                endpoint = WatchEndpoint(videoId = videoId),
                preloadItem = song?.toMediaMetadata(),
            )
        )
    }
}
