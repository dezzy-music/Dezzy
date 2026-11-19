/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.innertube.YouTube

import com.dezzy.music.innertube.models.comment.CommentResponse
import com.dezzy.music.innertube.models.comment.CommentRenderer
import com.dezzy.music.ui.screens.social.DezzyTrackCommentsPanel
import com.dezzy.music.utils.SimpleTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentSheet(
    videoId: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val comments = remember { mutableStateListOf<CommentResponse.CommentThread>() }
    var commentsContinuation by remember { mutableStateOf<String?>(null) }
    var isPaging by remember { mutableStateOf(false) }
    var retryKey by remember { mutableStateOf(0) }

    val lazyListState = rememberLazyListState()

    // Pestaña activa. Los comentarios de YouTube son de solo lectura (publicar
    // exige sus endpoints autenticados), así que escribir vive en la pestaña de
    // Dezzy, que sí es nuestra.
    var showDezzyComments by remember { mutableStateOf(false) }

    // Load initial comments
    LaunchedEffect(videoId, retryKey) {
        isLoading = true
        error = null
        comments.clear()
        
        val continuationResult = withContext(Dispatchers.IO) {
            YouTube.getVideoCommentsContinuation(videoId)
        }
        
        continuationResult.onSuccess { token ->
            if (token != null) {
                val commentsResult = withContext(Dispatchers.IO) {
                    YouTube.getComments(token)
                }
                commentsResult.onSuccess { cRes ->
                    comments.addAll(cRes.items)
                    commentsContinuation = cRes.continuation
                    isLoading = false
                }.onFailure { err ->
                    error = err.localizedMessage ?: "Error loading comments"
                    isLoading = false
                }
            } else {
                error = "Comments are disabled for this video"
                isLoading = false
            }
        }.onFailure { err ->
            error = err.localizedMessage ?: "Error loading comments token"
            isLoading = false
        }
    }

    // Load more comments (pagination)
    val shouldLoadMore = remember {
        derivedStateOf {
            val lastVisibleItemIndex = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItemsCount = lazyListState.layoutInfo.totalItemsCount
            commentsContinuation != null && !isPaging && lastVisibleItemIndex >= totalItemsCount - 5
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        val token = commentsContinuation
        if (shouldLoadMore.value && token != null) {
            isPaging = true
            val commentsResult = withContext(Dispatchers.IO) {
                YouTube.getComments(token)
            }
            commentsResult.onSuccess { cRes ->
                comments.addAll(cRes.items)
                commentsContinuation = cRes.continuation
            }
            isPaging = false
        }
    }

    // Boceto A «Hoja limpia con filtros»: cabecera con la canción, selector YouTube/Dezzy,
    // filtros, minuto citado que salta en la canción y un compositor que cita el minuto.
    val playerConnection = LocalPlayerConnection.current
    val nowPlaying = playerConnection?.mediaMetadata?.collectAsState()?.value
    val track = nowPlaying?.takeIf { it.id == videoId }
    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(track?.id) {
        if (track == null) return@LaunchedEffect
        while (true) {
            positionMs = (playerConnection?.player?.currentPosition ?: 0L).coerceAtLeast(0L)
            delay(1_000)
        }
    }
    val onSeek: ((Long) -> Unit)? =
        if (track != null && playerConnection != null) {
            { ms -> playerConnection.player.seekTo(ms) }
        } else {
            null
        }

    var filter by remember { mutableStateOf(CommentFilter.Top) }
    val visibleComments by remember {
        derivedStateOf {
            when (filter) {
                CommentFilter.Top -> comments.toList()
                CommentFilter.Recent -> comments.sortedBy {
                    commentAgeMinutes(it.commentThreadRenderer?.comment?.commentRenderer?.publishedTime().orEmpty())
                }
                CommentFilter.WithMinute -> comments.filter {
                    findCommentTimestamp(it.commentThreadRenderer?.comment?.commentRenderer?.body().orEmpty()) != null
                }
            }
        }
    }

    var composerText by remember { mutableStateOf("") }
    var isPostingYtComment by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
    ) {
        // Cabecera: portada, título y artista · comentarios
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (track?.thumbnailUrl != null) {
                AsyncImage(
                    model = track.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track?.title ?: stringResource(R.string.comments),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val countLabel = when {
                    comments.isEmpty() -> stringResource(R.string.comments)
                    commentsContinuation != null -> "${comments.size}+ comentarios"
                    else -> "${comments.size} comentarios"
                }
                Text(
                    text = listOfNotNull(
                        track?.artists?.joinToString { it.name }?.takeIf { it.isNotBlank() },
                        countLabel,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    painter = painterResource(R.drawable.close),
                    contentDescription = "Cerrar",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Selector segmentado YouTube / Dezzy
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(4.dp)
        ) {
            listOf(false to "YouTube", true to "Dezzy").forEach { (dezzy, label) ->
                val selected = showDezzyComments == dezzy
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { showDezzyComments = dezzy }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (showDezzyComments) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )
            DezzyTrackCommentsPanel(
                videoId = videoId,
                maxHeight = null,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            return@Column
        }

        // Filtros: Top · Recientes · Con minuto
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CommentFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option.label) },
                    leadingIcon = if (option.icon != null) {
                        {
                            Icon(
                                painter = painterResource(option.icon),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            } else if (error != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = {
                        // Trigger reload by incrementing retryKey
                        retryKey++
                        isLoading = true
                        error = null
                        comments.clear()
                    }) {
                        Text("Reintentar")
                    }
                }
            } else if (visibleComments.isEmpty()) {
                Text(
                    text = if (filter == CommentFilter.WithMinute && comments.isNotEmpty()) {
                        "Nadie ha citado un minuto todavía"
                    } else {
                        stringResource(R.string.no_comments)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Key must be unique per item: the old hashCode-sum key crashed with
                    // 'Key "0" was already used' whenever two threads hashed alike (null bodies
                    // hash to 0, and pagination can repeat identical entries). Prefixing the
                    // index makes every key unique by construction.
                    itemsIndexed(
                        visibleComments,
                        key = { index, thread ->
                            "${filter.name}_${index}_${thread.commentThreadRenderer?.comment?.commentRenderer?.body().hashCode()}"
                        }
                    ) { _, thread ->
                        val threadRenderer = thread.commentThreadRenderer
                        val commentRenderer = threadRenderer?.comment?.commentRenderer
                        if (commentRenderer != null && threadRenderer != null) {
                            CommentItem(
                                comment = commentRenderer,
                                replies = threadRenderer.replies,
                                videoId = videoId,
                                onSeek = onSeek,
                                onReply = { mention -> composerText = mention },
                            )
                        }
                    }

                    if (isPaging) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

        // Reacciones rápidas
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("🔥", "🥹", "❤️", "😭", "👏", "🎶").forEach { emoji ->
                Text(
                    text = emoji,
                    fontSize = 22.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { composerText += emoji }
                        .padding(6.dp)
                )
            }
        }

        // Compositor: tu inicial, campo que cita el minuto y enviar
        val minuteLabel = formatCommentTimestamp(positionMs)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, bottom = 8.dp)
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "T",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            OutlinedTextField(
                value = composerText,
                onValueChange = { composerText = it },
                placeholder = {
                    Text(if (track != null) "Comenta en $minuteLabel…" else "Escribe un comentario…")
                },
                trailingIcon = if (track != null) {
                    {
                        IconButton(onClick = {
                            val stamp = "$minuteLabel "
                            if (!composerText.startsWith(stamp)) composerText = stamp + composerText
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.timer),
                                contentDescription = "Citar el minuto actual",
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                )
            )
            FilledIconButton(
                onClick = {
                    if (composerText.isNotBlank() && !isPostingYtComment) {
                        val textToPost = composerText.trim()
                        composerText = ""
                        isPostingYtComment = true
                        coroutineScope.launch {
                            // 1. Enviar vía API de InnerTube a YouTube
                            runCatching {
                                YouTube.postComment(textToPost)
                            }
                            // 2. Enviar a DezzySocial para que compute en 'Pistas más comentadas'
                            runCatching {
                                com.dezzy.music.social.DezzySocial.addTrackComment(
                                    videoId,
                                    findCommentTimestamp(textToPost)?.second?.toInt() ?: 0,
                                    textToPost
                                )
                            }
                            // 3. Añadir a la lista local inmediatamente
                            val newRenderer = CommentRenderer(
                                authorText = CommentRenderer.TextRuns(simpleText = "Tú"),
                                contentText = CommentRenderer.TextRuns(simpleText = textToPost),
                                publishedTimeText = CommentRenderer.TextRuns(simpleText = "Ahora mismo"),
                                voteCount = CommentRenderer.TextRuns(simpleText = "0"),
                                authorIsChannelOwner = false,
                            )
                            comments.add(
                                0,
                                CommentResponse.CommentThread(
                                    commentThreadRenderer = CommentResponse.CommentThreadRenderer(
                                        comment = CommentResponse.CommentThreadRenderer.Comment(commentRenderer = newRenderer)
                                    )
                                )
                            )
                            isPostingYtComment = false
                        }
                    }
                },
                enabled = composerText.isNotBlank() && !isPostingYtComment,
                shape = CircleShape,
                modifier = Modifier.size(44.dp)
            ) {
                if (isPostingYtComment) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.send),
                        contentDescription = "Enviar",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private enum class CommentFilter(val label: String, val icon: Int?) {
    Top("Top", R.drawable.fire),
    Recent("Recientes", null),
    WithMinute("Con minuto", R.drawable.timer),
}

private val COMMENT_TIMESTAMP_RE = Regex("""(?<![\d:])(\d{1,2}):([0-5]\d)(?::([0-5]\d))?(?![\d:])""")

/** Primer minuto citado en un comentario («1:42», «1:02:10») como texto y milisegundos. */
internal fun findCommentTimestamp(text: String): Pair<String, Long>? {
    val match = COMMENT_TIMESTAMP_RE.find(text) ?: return null
    val (a, b, c) = match.destructured
    val seconds =
        if (c.isNotEmpty()) a.toLong() * 3600 + b.toLong() * 60 + c.toLong()
        else a.toLong() * 60 + b.toLong()
    return match.value to seconds * 1000L
}

internal fun formatCommentTimestamp(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * Antigüedad aproximada en minutos a partir del texto relativo de YouTube («hace 3 días»,
 * «2 weeks ago»). Lo que no se entiende va al final.
 */
private fun commentAgeMinutes(published: String): Long {
    val t = published.lowercase(java.util.Locale.ROOT)
    if (t.contains("ahora") || t.contains("now") || t.contains("just")) return 0
    val n = Regex("""\d+""").find(t)?.value?.toLongOrNull() ?: 1L
    val unit = when {
        t.contains("seg") || t.contains("sec") -> 0L
        t.contains("min") -> 1L
        t.contains("hora") || t.contains("hour") -> 60L
        t.contains("día") || t.contains("dia") || t.contains("day") -> 1_440L
        t.contains("semana") || t.contains("week") -> 10_080L
        t.contains("mes") || t.contains("month") -> 43_200L
        t.contains("año") || t.contains("ano") || t.contains("year") -> 525_600L
        else -> return Long.MAX_VALUE
    }
    return n * unit
}

@Composable
fun CommentItem(
    comment: CommentRenderer,
    replies: CommentResponse.CommentThreadRenderer.Replies?,
    videoId: String,
    modifier: Modifier = Modifier,
    onSeek: ((Long) -> Unit)? = null,
    onReply: (String) -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    var showReplies by remember { mutableStateOf(false) }
    val replyList = remember { mutableStateListOf<CommentResponse.CommentThread>() }
    var repliesContinuation by remember { mutableStateOf<String?>(null) }
    var isLoadingReplies by remember { mutableStateOf(false) }

    val authorName = comment.authorName()
    val bodyText = comment.body()
    val likesCount = comment.likes()
    val publishedTime = comment.publishedTime()
    val avatarUrl = comment.authorThumbnail?.bestUrl()

    val hasReplies = replies != null && (comment.replyCount?.replyCount ?: 0) > 0

    // Bumped by the "Translate" action to toggle the inline translation of this comment.
    var translate by remember { mutableStateOf(0) }

    // Fetch replies if not loaded yet
    val fetchReplies: () -> Unit = {
        val token = replies?.commentRepliesRenderer?.continuations?.firstOrNull()?.nextContinuationData?.continuation
        if (token != null && replyList.isEmpty()) {
            isLoadingReplies = true
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    YouTube.getCommentReplies(token)
                }
                result.onSuccess { rRes ->
                    replyList.addAll(rRes.items)
                    repliesContinuation = rRes.continuation
                }
                isLoadingReplies = false
            }
        }
        showReplies = !showReplies
    }

    // Next page of replies (the continuation used to be stored but never consumed,
    // so only the first page of replies was ever reachable).
    val loadMoreReplies: () -> Unit = {
        val token = repliesContinuation
        if (token != null && !isLoadingReplies) {
            isLoadingReplies = true
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    YouTube.getCommentReplies(token)
                }
                result.onSuccess { rRes ->
                    replyList.addAll(rRes.items)
                    repliesContinuation = rRes.continuation
                }
                isLoadingReplies = false
            }
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
        )

        Column(modifier = Modifier.weight(1f)) {
            // Author + Time + actions menu
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = authorName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (comment.authorIsChannelOwner) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = publishedTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                CommentActionsMenu(
                    text = bodyText,
                    commentId = comment.commentId,
                    videoId = videoId,
                    onTranslate = { translate++ },
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Minuto citado: píldora que salta a ese punto de la canción
            val stamp = remember(bodyText) { findCommentTimestamp(bodyText) }
            if (stamp != null) {
                Row(
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f))
                        .clickable(enabled = onSeek != null) { onSeek?.invoke(stamp.second) }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = "Ir al minuto",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = stamp.first,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            // Body (with inline, in-app translation)
            TranslatableCommentBody(
                text = bodyText,
                translateTrigger = translate,
                textStyle = MaterialTheme.typography.bodyMedium,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Acciones: me gusta, no me gusta, responder
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.thumbup),
                        contentDescription = "Likes",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = likesCount,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.thumbdown),
                    contentDescription = "No me gusta",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Responder",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onReply("@${authorName.removePrefix("@")} ") }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            // Replies section
            if (hasReplies) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .clickable { fetchReplies() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    )
                    Text(
                        text = if (showReplies) {
                            stringResource(R.string.comment_hide_replies)
                        } else {
                            stringResource(R.string.comment_show_replies, comment.replyCount?.replyCount ?: 0)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (showReplies) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        replyList.forEach { replyThread ->
                            val replyRenderer = replyThread.commentRenderer
                            if (replyRenderer != null) {
                                ReplyItem(
                                    reply = replyRenderer,
                                    videoId = videoId,
                                )
                            }
                        }

                        if (isLoadingReplies) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (repliesContinuation != null) {
                            Text(
                                text = stringResource(R.string.comment_more_replies),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { loadMoreReplies() }
                                    .padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyItem(
    reply: CommentRenderer,
    videoId: String,
) {
    var translate by remember { mutableStateOf(0) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AsyncImage(
            model = reply.authorThumbnail?.bestUrl(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = reply.authorName(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (reply.authorIsChannelOwner) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = reply.publishedTime(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                CommentActionsMenu(
                    text = reply.body(),
                    commentId = reply.commentId,
                    videoId = videoId,
                    onTranslate = { translate++ },
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            TranslatableCommentBody(
                text = reply.body(),
                translateTrigger = translate,
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Renders a comment/reply body and, when the "Translate" action fires, shows an inline
 * in-app translation directly beneath it (no external app). Tapping again toggles back
 * to the original. Uses [SimpleTranslator] (key-less, device-language target).
 */
@Composable
private fun TranslatableCommentBody(
    text: String,
    translateTrigger: Int,
    textStyle: androidx.compose.ui.text.TextStyle,
) {
    var translated by remember(text) { mutableStateOf<String?>(null) }
    var showTranslated by remember(text) { mutableStateOf(false) }
    var loading by remember(text) { mutableStateOf(false) }
    var error by remember(text) { mutableStateOf(false) }

    // React to the actions-menu trigger. The first trigger translates + shows; further
    // triggers just toggle between original and translated.
    LaunchedEffect(translateTrigger) {
        if (translateTrigger == 0) return@LaunchedEffect
        if (translated != null) {
            showTranslated = !showTranslated
            return@LaunchedEffect
        }
        loading = true
        error = false
        val result = SimpleTranslator.translate(text)
        loading = false
        result.onSuccess {
            translated = it
            showTranslated = true
        }.onFailure { error = true }
    }

    Text(
        text = if (showTranslated && translated != null) translated!! else text,
        style = textStyle,
        color = MaterialTheme.colorScheme.onSurface,
    )

    when {
        loading -> {
            Text(
                text = stringResource(R.string.comment_translating),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        error -> {
            Text(
                text = stringResource(R.string.comment_translate_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        showTranslated && translated != null -> {
            Text(
                text = stringResource(R.string.comment_show_original),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable { showTranslated = false },
            )
        }
    }
}

/**
 * Per-comment/per-reply actions: copy and translate resolve fully in-app;
 * reply and report deep-link to the exact comment on YouTube (posting/flagging
 * require YouTube's authenticated endpoints, so the official surface handles them).
 */
@Composable
private fun CommentActionsMenu(
    text: String,
    commentId: String?,
    videoId: String,
    onTranslate: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.more_vert),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.comment_copy)) },
                onClick = {
                    expanded = false
                    clipboard.setText(AnnotatedString(text))
                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.translate)) },
                onClick = {
                    expanded = false
                    onTranslate()
                }
            )
            if (!commentId.isNullOrBlank()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.comment_reply)) },
                    onClick = {
                        expanded = false
                        openCommentOnYouTube(context, videoId, commentId)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.comment_report)) },
                    onClick = {
                        expanded = false
                        openCommentOnYouTube(context, videoId, commentId)
                    }
                )
            }
        }
    }
}

/** Deep-links the exact comment (`lc=` anchor) so YouTube opens its reply/report UI. */
private fun openCommentOnYouTube(context: Context, videoId: String, commentId: String) {
    val url = "https://www.youtube.com/watch?v=$videoId&lc=$commentId"
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
