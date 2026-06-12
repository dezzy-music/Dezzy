/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.arturo254.opentune.LocalPlayerConnection
import com.arturo254.opentune.R
import com.arturo254.opentune.listentogether.ChatMessagePayload
import com.arturo254.opentune.listentogether.ListenTogetherClient
import com.arturo254.opentune.listentogether.RoomState
import com.arturo254.opentune.models.MediaMetadata
import com.arturo254.opentune.playback.queues.YouTubeQueue
import com.arturo254.opentune.ui.component.IconButton
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.util.Base64
import android.os.Build
import java.io.File
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenTogetherChatScreen(
    manager: ListenTogetherClient,
    onDismiss: () -> Unit
) {
    val messages: List<ChatMessagePayload> by manager.messages.collectAsState(emptyList())
    val typingUsers: Set<String> by manager.typingUsers.collectAsState(emptySet())
    val userId: String? by manager.userId.collectAsState(null)
    val roomState: RoomState? by manager.roomState.collectAsState(null)
    val playerConnection = LocalPlayerConnection.current
    val listState = rememberLazyListState()
    
    var textInput by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<ChatMessagePayload?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessagePayload?>(null) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    
    // Scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    // Manage typing indicator timeouts
    LaunchedEffect(textInput) {
        if (textInput.isNotEmpty()) {
            manager.sendTyping(true)
        } else {
            manager.sendTyping(false)
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            manager.sendTyping(false)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Chat de la sala",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (typingUsers.isNotEmpty()) {
                                val typingText = remember(typingUsers) {
                                    if (typingUsers.size == 1) {
                                        "${typingUsers.first()} is typing..."
                                    } else {
                                        "Multiple users are typing..."
                                    }
                                }
                                Text(
                                    text = typingText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    text = roomState?.roomCode?.let { "Room: $it" } ?: "Chat",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            bottomBar = {
                ChatInputArea(
                    manager = manager,
                    text = textInput,
                    replyingTo = replyingTo,
                    editingMessage = editingMessage,
                    onTextChange = { textInput = it },
                    onSend = {
                        if (editingMessage != null) {
                            manager.sendEditMessage(editingMessage!!.messageId, textInput.trim())
                            editingMessage = null
                        } else {
                            manager.sendChat(
                                message = textInput.trim(),
                                replyToId = replyingTo?.messageId,
                                replyToText = replyingTo?.message
                            )
                            replyingTo = null
                        }
                        textInput = ""
                    },
                    onShareCurrent = {
                        playerConnection?.mediaMetadata?.value?.let { meta ->
                            val songJson = """
                                {
                                    "type": "song",
                                    "id": "${meta.id}",
                                    "title": "${meta.title}",
                                    "artist": "${meta.artists.joinToString { it.name }}",
                                    "thumbnail": "${meta.thumbnailUrl ?: ""}"
                                }
                            """.trimIndent()
                            manager.sendChat(songJson, type = "text")
                        }
                    },
                    onCloseReply = { replyingTo = null },
                    onCloseEdit = { editingMessage = null },
                    onToggleEmojiPicker = { showEmojiPicker = !showEmojiPicker }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (messages.isEmpty()) {
                    EmptyChatPlaceholder()
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(messages, key = { it.messageId.ifEmpty { it.timestamp.toString() } }) { message ->
                            val isMe = message.userId == userId
                            ChatMessageRow(
                                message = message,
                                isMe = isMe,
                                onSongClick = { meta ->
                                    playerConnection?.playQueue(YouTubeQueue.radio(meta))
                                },
                                onReply = { replyingTo = message },
                                onEdit = {
                                    editingMessage = message
                                    textInput = message.message
                                }
                            )
                        }
                    }
                }
                
                if (showEmojiPicker) {
                    EmojiPickerBottomSheet(
                        onDismissRequest = { showEmojiPicker = false },
                        onEmojiSelected = { emoji ->
                            manager.sendChat(emoji, type = "sticker")
                            showEmojiPicker = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatMessageRow(
    message: ChatMessagePayload,
    isMe: Boolean,
    onSongClick: (MediaMetadata) -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit
) {
    val alignment = if (isMe) Alignment.End else Alignment.Start
    val textColor = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = if (isMe) {
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    }

    var showMenu by remember { mutableStateOf(false) }
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeString = remember(message.timestamp) { formatter.format(Date(message.timestamp)) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = alignment
        ) {
            Box {
                Text(
                    text = if (isMe) "Tú" else message.username,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMe) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(
                        start = if (isMe) 0.dp else 12.dp,
                        end = if (isMe) 12.dp else 0.dp,
                        bottom = 4.dp
                    )
                )
                
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.align(if (isMe) Alignment.TopEnd else Alignment.TopStart)
                ) {
                    DropdownMenuItem(
                        text = { Text("Responder") },
                        onClick = {
                            showMenu = false
                            onReply()
                        },
                        leadingIcon = { Icon(painterResource(R.drawable.arrow_back), null) }
                    )
                    if (isMe) {
                        DropdownMenuItem(
                            text = { Text("Editar mensaje") },
                            onClick = {
                                showMenu = false
                                onEdit()
                            },
                            leadingIcon = { Icon(painterResource(R.drawable.edit), null) }
                        )
                    }
                }
            }

            // Check if message is a JSON representing a song
            val songInfo = remember(message.message) {
                try {
                    if (message.message.startsWith("{") && message.message.contains("\"type\":\"song\"")) {
                        Json.parseToJsonElement(message.message).jsonObject
                    } else null
                } catch (e: Exception) {
                    null
                }
            }

            if (songInfo != null) {
                SongSharedCard(
                    title = songInfo["title"]?.jsonPrimitive?.content ?: "Unknown",
                    artist = songInfo["artist"]?.jsonPrimitive?.content ?: "Unknown",
                    thumbnail = songInfo["thumbnail"]?.jsonPrimitive?.content ?: "",
                    onClick = {
                        onSongClick(
                            MediaMetadata(
                                id = songInfo["id"]?.jsonPrimitive?.content ?: "",
                                title = songInfo["title"]?.jsonPrimitive?.content ?: "Unknown",
                                artists = listOf(
                                    MediaMetadata.Artist(
                                        id = null,
                                        name = songInfo["artist"]?.jsonPrimitive?.content ?: "Unknown"
                                    )
                                ),
                                duration = -1,
                                thumbnailUrl = songInfo["thumbnail"]?.jsonPrimitive?.content ?: ""
                            )
                        )
                    }
                )
            } else if (message.type == "audio") {
                Box(modifier = Modifier.combinedClickable(
                    onClick = {}, onLongClick = { showMenu = true }
                ).padding(vertical = 4.dp)) {
                    AudioMessageBubble(base64Audio = message.message, isMe = isMe)
                }
            } else if (message.type == "sticker") {
                Box(modifier = Modifier.combinedClickable(
                    onClick = {}, onLongClick = { showMenu = true }
                )) {
                    Text(
                        text = message.message,
                        fontSize = 48.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            } else {
                val backgroundModifier = if (isMe) {
                    Modifier.background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            )
                        ),
                        shape = shape
                    )
                } else {
                    Modifier.background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        shape = shape
                    )
                }

                Box(
                    modifier = backgroundModifier
                        .clip(shape)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { showMenu = true }
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Column {
                        // Reply Preview
                        if (!message.replyToText.isNullOrEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.padding(bottom = 6.dp).fillMaxWidth(0.8f)
                            ) {
                                Row(modifier = Modifier.padding(6.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .fillMaxHeight()
                                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = message.replyToText,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = textColor.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }

                        // Message Content
                        Text(
                            text = message.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor
                        )
                    }
                }
            }
            
            // Time and Edited stamp
            Row(
                modifier = Modifier.padding(top = 2.dp, start = 12.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (message.editedAt != null) {
                    Text(
                        text = "editado",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                Text(
                    text = timeString,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                if (isMe) {
                    Spacer(modifier = Modifier.width(4.dp))
                    val statusIcon = when (message.status) {
                        "pending" -> painterResource(R.drawable.history)
                        else -> painterResource(R.drawable.check)
                    }
                    val statusTint = if (message.status == "read") Color(0xFF34B7F1) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

                    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                        if (message.status == "delivered" || message.status == "read") {
                            Icon(
                                painter = statusIcon,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = statusTint
                            )
                        }
                        Icon(
                            painter = statusIcon,
                            contentDescription = message.status,
                            modifier = Modifier.size(14.dp),
                            tint = statusTint
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SongSharedCard(
    title: String,
    artist: String,
    thumbnail: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentScale = ContentScale.Crop
                )
                // Overlay for play button
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChatInputArea(
    manager: ListenTogetherClient,
    text: String,
    replyingTo: ChatMessagePayload?,
    editingMessage: ChatMessagePayload?,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onShareCurrent: () -> Unit,
    onCloseReply: () -> Unit,
    onCloseEdit: () -> Unit,
    onToggleEmojiPicker: () -> Unit
) {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableIntStateOf(0) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            // Permission denied
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDuration = 0
            while (isRecording && recordingDuration < 30) {
                delay(1000)
                recordingDuration++
            }
            if (recordingDuration >= 30) {
                // max 30s
                isRecording = false
            }
        }
    }

    // Stop recording logic
    val stopRecording = {
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaRecorder?.release()
        mediaRecorder = null

        audioFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                val bytes = file.readBytes()
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                manager.sendChat(
                    message = base64,
                    replyToId = replyingTo?.messageId,
                    replyToText = replyingTo?.message,
                    type = "audio"
                )
                onCloseReply()
            }
            file.delete()
        }
        audioFile = null
    }

    if (!isRecording && audioFile != null) {
        stopRecording()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(bottom = 8.dp).navigationBarsPadding()) {
            
            // Reply / Edit Context Bar
            AnimatedVisibility(visible = replyingTo != null || editingMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(if (editingMessage != null) R.drawable.edit else R.drawable.arrow_back),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (editingMessage != null) "Editar mensaje" else "Responder",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = editingMessage?.message ?: replyingTo?.message ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = if (editingMessage != null) onCloseEdit else onCloseReply) {
                            Icon(painterResource(R.drawable.close), null, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isRecording) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.mic),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "Grabando 0:${recordingDuration.toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isRecording) {
                    IconButton(onClick = onShareCurrent) {
                        Icon(
                            painter = painterResource(R.drawable.share),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    IconButton(onClick = onToggleEmojiPicker) {
                        Text(
                            text = "😀",
                            fontSize = 20.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    TextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp)),
                        placeholder = { Text("Escribe un mensaje...") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        maxLines = 4
                    )

                    Spacer(modifier = Modifier.width(8.dp))
                }

                AnimatedVisibility(
                    visible = text.isNotBlank(),
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    FloatingActionButton(
                        onClick = onSend,
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_forward),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                AnimatedVisibility(
                    visible = text.isBlank(),
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer,
                                shape = CircleShape
                            )
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val down = awaitFirstDown()
                                        val hasPermission = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (hasPermission) {
                                            isRecording = true
                                            try {
                                                audioFile = File(context.cacheDir, "audio_msg_${System.currentTimeMillis()}.m4a")
                                                mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                    MediaRecorder(context)
                                                } else {
                                                    @Suppress("DEPRECATION")
                                                    MediaRecorder()
                                                }
                                                mediaRecorder?.apply {
                                                    setAudioSource(MediaRecorder.AudioSource.MIC)
                                                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                                    setOutputFile(audioFile!!.absolutePath)
                                                    prepare()
                                                    start()
                                                }
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                                isRecording = false
                                            }
                                        } else {
                                            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }

                                        waitForUpOrCancellation()
                                        isRecording = false
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.mic),
                            contentDescription = "Mantén presionado para grabar",
                            tint = if (isRecording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiPickerBottomSheet(
    onDismissRequest: () -> Unit,
    onEmojiSelected: (String) -> Unit
) {
    val emojis = listOf(
        "🎵", "🎶", "🎸", "🥁", "🎹", "🎤", "🎧", "🔊", 
        "🔥", "❤️", "💯", "🤯", "😎", "🤩", "🙌", "👏",
        "💃", "🕺", "💿", "📻", "🌟", "✨", "🎉", "🎊",
        "💀", "🤠", "👽", "👾", "🤘", "🤌", "🎵", "🎶"
    )

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, top = 8.dp)
        ) {
            Text(
                text = "Stickers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)
            )
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(48.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(emojis) { emoji ->
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable { onEmojiSelected(emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = emoji,
                            fontSize = 28.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioMessageBubble(base64Audio: String, isMe: Boolean) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(base64Audio) {
        val bytes = Base64.decode(base64Audio, Base64.DEFAULT)
        val tempFile = File.createTempFile("audio", ".m4a", context.cacheDir)
        tempFile.writeBytes(bytes)

        val player = MediaPlayer().apply {
            try {
                setDataSource(tempFile.absolutePath)
                prepare()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        player.setOnCompletionListener {
            isPlaying = false
        }
        
        mediaPlayer = player

        onDispose {
            player.release()
            tempFile.delete()
        }
    }

    val shape = if (isMe) {
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    }

    val backgroundModifier = if (isMe) {
        Modifier.background(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
            ),
            shape = shape
        )
    } else {
        Modifier.background(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = shape
        )
    }

    Box(
        modifier = backgroundModifier
            .clip(shape)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .width(180.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (isPlaying) {
                        mediaPlayer?.pause()
                        isPlaying = false
                    } else {
                        mediaPlayer?.start()
                        isPlaying = true
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                    contentDescription = null,
                    tint = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // Fake waveform
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                val waveColor = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                listOf(0.3f, 0.6f, 0.9f, 0.4f, 0.7f, 0.5f, 0.8f, 0.4f, 0.6f, 0.3f).forEach { heightPercent ->
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(24.dp * heightPercent)
                            .background(waveColor.copy(alpha = 0.8f), CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyChatPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.person),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No hay mensajes en esta sala aún",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}
