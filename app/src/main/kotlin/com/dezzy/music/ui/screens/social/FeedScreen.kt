/*
 * Dezzy Project (2026)
 * Instagram-style feed: stories, posts, likes, create post. Backed by Supabase (see FeedRepository).
 */

package com.dezzy.music.ui.screens.social

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dezzy.music.R
import com.dezzy.music.social.FeedPost
import com.dezzy.music.social.FeedRepository
import com.dezzy.music.social.Story
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var posts by remember { mutableStateOf<List<FeedPost>>(emptyList()) }
    var stories by remember { mutableStateOf<List<Story>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showCreate by remember { mutableStateOf(false) }
    var viewingStory by remember { mutableStateOf<Story?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun reload() {
        posts = FeedRepository.getFeed()
        stories = FeedRepository.getStories()
        loading = false
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(tick) { reload() }

    // Add a story: pick image → upload → create_story → refresh.
    val storyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val bytes = readScaledJpeg(context, uri, 1080)
            val url = bytes?.let { FeedRepository.uploadImage(it, kind = "story") }
            if (url != null) FeedRepository.createStory(url)
            reload()
            busy = false
        }
    }

    fun onToggleLike(post: FeedPost) {
        // optimistic
        posts = posts.map {
            if (it.postId == post.postId)
                it.copy(likedByMe = !it.likedByMe, likesCount = it.likesCount + if (it.likedByMe) -1 else 1)
            else it
        }
        scope.launch {
            val res = FeedRepository.toggleLike(post.postId)
            if (res != null) {
                posts = posts.map {
                    if (it.postId == post.postId) it.copy(likedByMe = res.first, likesCount = res.second) else it
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Feed", fontWeight = FontWeight.Bold) },
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
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(painterResource(R.drawable.social_add), contentDescription = "Crear publicación")
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    item {
                        StoriesBar(
                            stories = stories,
                            onAdd = { storyPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            onOpen = { viewingStory = it },
                        )
                    }
                    if (posts.isEmpty()) {
                        item { FeedEmptyState() }
                    } else {
                        items(posts, key = { it.postId }) { post ->
                            PostCard(post = post, onLike = { onToggleLike(post) })
                        }
                    }
                }
            }
            if (busy) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        }
    }

    if (showCreate) {
        CreatePostSheet(
            onDismiss = { showCreate = false },
            onPublish = { text, uri ->
                showCreate = false
                scope.launch {
                    busy = true
                    val imageUrl = uri?.let { u ->
                        readScaledJpeg(context, u, 1440)?.let { FeedRepository.uploadImage(it, kind = "post") }
                    }
                    FeedRepository.createPost(text, imageUrl)
                    reload()
                    busy = false
                }
            },
        )
    }

    viewingStory?.let { story ->
        Dialog(onDismissRequest = { viewingStory = null }) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Avatar(url = story.authorAvatar, size = 34.dp, iconSize = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(story.authorName, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                    AsyncImage(
                        model = story.mediaUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StoriesBar(stories: List<Story>, onAdd: () -> Unit, onOpen: (Story) -> Unit) {
    // Group by author so each person shows once.
    val byUser = remember(stories) { stories.groupBy { it.userId }.map { it.value.first() } }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onAdd)) {
                Box(
                    Modifier
                        .size(66.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.social_add),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("Tu historia", fontSize = 11.sp, maxLines = 1)
            }
        }
        items(byUser, key = { it.userId }) { story ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onOpen(story) }) {
                Box(
                    Modifier
                        .size(66.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Avatar(url = story.authorAvatar, size = 60.dp, iconSize = 28.dp)
                }
                Spacer(Modifier.height(4.dp))
                Text(story.authorName, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(66.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun PostCard(post: FeedPost, onLike: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Avatar(url = post.authorAvatar, size = 40.dp, iconSize = 22.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(post.authorName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (post.authorUsername?.let { "@$it · " } ?: "") + relativeTime(post.createdAtMs),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        post.imageUrl?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onLike) {
                Icon(
                    painterResource(if (post.likedByMe) R.drawable.social_heart_filled else R.drawable.social_heart),
                    contentDescription = "Me gusta",
                    tint = if (post.likedByMe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (post.likesCount > 0) {
                Text("${post.likesCount}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        post.content?.let {
            Text(
                it,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun FeedEmptyState() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.social_heart), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Aún no hay publicaciones", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Text("Sé el primero en publicar con el botón +", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatePostSheet(
    onDismiss: () -> Unit,
    onPublish: (text: String, image: android.net.Uri?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<android.net.Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) image = uri
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(16.dp)) {
            Text("Nueva publicación", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            DezzyTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = "¿Qué estás escuchando?",
                singleLine = false,
                minLines = 4,
                maxLines = 8,
                maxLength = 500,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            image?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(painterResource(R.drawable.social_invite), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (image == null) "Agregar foto" else "Cambiar foto")
                }
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.Button(
                    onClick = { if (text.isNotBlank() || image != null) onPublish(text, image) },
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Publicar") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun relativeTime(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    val min = diff / 60000
    return when {
        min < 1 -> "ahora"
        min < 60 -> "hace ${min}m"
        min < 1440 -> "hace ${min / 60}h"
        else -> "hace ${min / 1440}d"
    }
}

private fun readScaledJpeg(context: android.content.Context, uri: android.net.Uri, maxDim: Int): ByteArray? {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bmp = BitmapFactory.decodeStream(input) ?: return null
            val scale = maxDim.toFloat() / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
            val out = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true
                )
            } else bmp
            java.io.ByteArrayOutputStream().apply {
                out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, this)
            }.toByteArray()
        }
    }.getOrNull()
}
