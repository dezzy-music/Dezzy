/**
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.arturo254.opentune.LocalPlayerAwareWindowInsets
import com.arturo254.opentune.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.arturo254.opentune.ui.component.LaTeXText
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.arturo254.opentune.LocalPlayerConnection
import com.arturo254.opentune.api.DezzyAgent
import org.json.JSONObject
import timber.log.Timber
import com.arturo254.opentune.innertube.models.SongItem
import com.arturo254.opentune.LocalDatabase
import androidx.compose.foundation.lazy.LazyRow
import coil3.compose.AsyncImage
import androidx.compose.ui.text.style.TextOverflow
import com.arturo254.opentune.playback.queues.YouTubeQueue
import androidx.compose.foundation.clickable
import com.arturo254.opentune.models.toMediaMetadata

import android.speech.tts.TextToSpeech
import java.util.Locale
import com.arturo254.opentune.utils.VoiceRecorder
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val recommendations: List<SongItem> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DezzyIAScreen(
    navController: NavController,
) {
    val context = LocalContext.current
    val windowInsets = LocalPlayerAwareWindowInsets.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val dezzyAgent = remember(playerConnection, coroutineScope, database) { 
        playerConnection?.let { DezzyAgent(it, database, coroutineScope) } 
    }
    
    var inputText by rememberSaveable { mutableStateOf("") }
    var isTyping by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    val voiceRecorder = remember { VoiceRecorder(context) }

    // TTS Implementation
    var tts: TextToSpeech? by remember { mutableStateOf(null) }
    LaunchedEffect(Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val systemLocale = context.resources.configuration.locales[0]
                tts?.language = systemLocale
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tts?.stop()
            tts?.shutdown()
            voiceRecorder.release()
        }
    }

    val currentTimestamp = System.currentTimeMillis()
    val topSongs by database.mostPlayedSongs(fromTimeStamp = 0L, limit = 10, offset = 0, toTimeStamp = currentTimestamp).collectAsState(initial = emptyList())
    val topArtists by database.mostPlayedArtists(fromTimeStamp = 0L, limit = 10, offset = 0, toTimeStamp = currentTimestamp).collectAsState(initial = emptyList())
    val likedSongs by database.likedSongsByCreateDateAsc().collectAsState(initial = emptyList())
    val currentlyPlaying by playerConnection?.currentSong?.collectAsState(initial = null) ?: mutableStateOf(null)

    val userContext = remember(topSongs, topArtists, likedSongs, currentlyPlaying) {
        buildString {
            append("Contexto del usuario musical en TIEMPO REAL:\n")
            if (currentlyPlaying != null) {
                append("- MÚSICA ACTUALMENTE EN REPRODUCCIÓN: \"${currentlyPlaying!!.song.title}\" de ${currentlyPlaying!!.artists.joinToString { it.name }}.\n\n")
            }
            if (topArtists.isNotEmpty()) {
                append("- Artistas más escuchados: ")
                append(topArtists.joinToString { it.artist.name })
                append(".\n")
            }
            if (topSongs.isNotEmpty()) {
                append("- Canciones más escuchadas: ")
                append(topSongs.joinToString { "${it.title} de ${it.artists.joinToString { a -> a.name }}" })
                append(".\n")
            }
            if (likedSongs.isNotEmpty()) {
                append("- Le gustan: ")
                append(likedSongs.take(10).joinToString { "${it.song.title} de ${it.artists.joinToString { a -> a.name }}" })
                append(".\n")
            }
        }
    }

    val welcomeMessage = "¡Hola! Soy Dezzy, tu asistente musical con IA. ¿De qué humor estás hoy o qué te gustaría escuchar?"
    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                text = welcomeMessage,
                isUser = false
            )
        )
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    fun sendMessage(customText: String? = null) {
        val text = customText ?: inputText.trim()
        if (text.isEmpty()) return

        messages.add(ChatMessage(text = text, isUser = true))
        inputText = ""
        keyboardController?.hide()

        coroutineScope.launch {
            listState.animateScrollToItem(messages.size - 1)
            isTyping = true

            val contextList = mutableListOf(Pair("system", userContext))
            contextList.addAll(messages.takeLast(10).map { 
                (if (it.isUser) "user" else "assistant") to it.text 
            })

            val result = com.arturo254.opentune.api.GroqService.chatCompletion(context, contextList)
            
            isTyping = false
            
            result.onSuccess { response ->
                val jsonMatch = Regex("\\{.*?\\}").findAll(response).lastOrNull()
                var cleanResponse = response
                
                if (jsonMatch != null) {
                    val jsonAction = jsonMatch.value
                    try {
                        val actionResult = dezzyAgent?.executeAction(context, jsonAction)
                        cleanResponse = response.replace(jsonAction, "").trim()
                        
                        if (actionResult != null) {
                            messages.add(ChatMessage(text = cleanResponse, isUser = false, recommendations = actionResult.recommendations))
                        } else {
                            messages.add(ChatMessage(text = cleanResponse, isUser = false))
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error parsing AI JSON action")
                        messages.add(ChatMessage(text = cleanResponse, isUser = false))
                    }
                } else {
                    messages.add(ChatMessage(text = cleanResponse, isUser = false))
                }
                
                speak(cleanResponse)

            }.onFailure { error ->
                messages.add(ChatMessage(
                    text = "Error de conexión con la IA: ${error.message ?: "Desconocido"}", 
                    isUser = false
                ))
            }
            
            delay(100)
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isRecording = true
            voiceRecorder.startRecording()
        }
    }

    fun toggleRecording() {
        if (isRecording) {
            isRecording = false
            voiceRecorder.stopRecording()
            val audioFile = File(context.cacheDir, "ai_voice_input.m4a")
            if (audioFile.exists()) {
                coroutineScope.launch {
                    isTyping = true
                    com.arturo254.opentune.api.GroqService.transcribeAudio(context, audioFile).onSuccess { transcription ->
                        isTyping = false
                        sendMessage(transcription)
                    }.onFailure {
                        isTyping = false
                        messages.add(ChatMessage(text = "Error al procesar la nota de voz: ${it.message ?: "Desconocido"}", isUser = false))
                    }
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                isRecording = true
                voiceRecorder.startRecording()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val isKeyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(isKeyboardVisible) {
        if (isKeyboardVisible && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(windowInsets)
            .imePadding()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                ChatBubble(message = message)
            }

            if (isTyping) {
                item {
                    TypingIndicator()
                }
            }
        }

        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { toggleRecording() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(if (isRecording) R.drawable.pause else R.drawable.mic),
                        contentDescription = "Grabar nota de voz",
                        tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (isRecording) "Grabando nota de voz..." else "Pregúntame algo...") },
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    )
                )

                FilledIconButton(
                    onClick = { sendMessage() },
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_forward),
                        contentDescription = "Enviar mensaje",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val bubbleShape = if (message.isUser) {
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!message.isUser) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.star),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Surface(
                shape = bubbleShape,
                color = if (message.isUser) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                tonalElevation = if (message.isUser) 0.dp else 1.dp
            ) {
                LaTeXText(
                    text = message.text,
                    modifier = Modifier.padding(12.dp),
                    color = if (message.isUser) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (!message.isUser) {
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(message.text))
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.content_copy),
                        contentDescription = "Copiar al portapapeles",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }

    if (!message.isUser && message.recommendations.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(start = 40.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(message.recommendations) { songItem ->
                SongRecommendationCard(songItem, LocalPlayerConnection.current)
            }
        }
    }
}

@Composable
private fun SongRecommendationCard(songItem: SongItem, playerConnection: com.arturo254.opentune.playback.PlayerConnection?) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .width(160.dp)
            .clickable {
                playerConnection?.playQueue(YouTubeQueue.radio(songItem.toMediaMetadata()))
            }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = songItem.thumbnail,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            )
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = songItem.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = songItem.artists.joinToString { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.star),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(3) { index ->
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        delay(index * 200L)
                        while (true) {
                            visible = true
                            delay(600)
                            visible = false
                            delay(600)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (visible) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                }
                            )
                    )
                }
            }
        }
    }
}
