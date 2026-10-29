package com.dezzy.music.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dezzy.music.R
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.models.MediaMetadata
import com.dezzy.music.playback.PlayerConnection

private val PurpleBackground = Color(0xFF110624)
private val PurpleCard = Color(0xFF220E3D)
private val PurpleCardSpecial = Color(0xFF2E1352)
private val PurpleBorder = Color(0xFF4C1D95)
private val NeonPurple = Color(0xFFA855F7)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DJInteractionBottomSheet(
    onDismissRequest: () -> Unit,
    viewModel: DJDezzyViewModel,
    playerConnection: PlayerConnection?,
    mediaMetadata: MediaMetadata?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val (customDjName) = com.dezzy.music.utils.rememberPreference(com.dezzy.music.constants.DjNameKey, "")
    val djName = if (customDjName.isNotBlank()) "DJ $customDjName" else "DJ Dezzy"

    val dynamicCards by viewModel.dynamicVibeCards.collectAsState()
    val isPlaying by (playerConnection?.isPlaying ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()

    fun submitRequest(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotBlank() && playerConnection != null) {
            viewModel.handleUserRequest(trimmed, playerConnection)
            inputText = ""
            onDismissRequest()
        }
    }

    // Speech Recognizer Launcher for the Mic button
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                submitRequest(spoken)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = PurpleBackground,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.35f))
            )
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Mini player banner at the top of sheet (as in Spotify DJ prompt sheet)
            if (mediaMetadata != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.05f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = mediaMetadata.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mediaMetadata.title ?: "Cargando...",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = mediaMetadata.artists.joinToString { it.name }.ifEmpty { "Desconocido" },
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { playerConnection?.player?.togglePlayPause() }) {
                                Icon(
                                    painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                                    contentDescription = "Play/Pause",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Disclaimer / Information row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "No compartas datos confidenciales con nuestro DJ con IA. Aprender sobre cómo se administran y comparten tus datos.",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    painter = painterResource(R.drawable.info),
                    contentDescription = "Info",
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // Big Bold Title: "Soy tu DJ Dezzy. ¿Qué quieres escuchar?"
            Text(
                text = "Soy tu $djName. ¿Qué quieres escuchar?",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 34.sp
            )

            Spacer(Modifier.height(20.dp))

            // Horizontal Carousel of Spotify-style Vibe Cards
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Card 1: "Elige tú, Dezzy"
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = PurpleCardSpecial,
                        border = BorderStroke(1.5.dp, NeonPurple.copy(alpha = 0.8f)),
                        modifier = Modifier
                            .width(160.dp)
                            .height(115.dp)
                            .clickable {
                                submitRequest("Elige tú una vibra fresca y variada que me sorprenda")
                            }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Circular DJ Ring icon
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.4f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(NeonPurple)
                                )
                            }
                            Text(
                                text = "Elige tú, ${customDjName.ifBlank { "Dezzy" }}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }

                // Dynamic Vibe cards generated by Gemini
                items(dynamicCards) { cardText ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = PurpleCard,
                        border = BorderStroke(1.dp, PurpleBorder.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .width(190.dp)
                            .height(115.dp)
                            .clickable { submitRequest(cardText) }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp),
                            contentAlignment = Alignment.BottomStart
                        ) {
                            Text(
                                text = cardText,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                lineHeight = 19.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Bottom Input Capsule with Mic button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xFF1B0B33))
                    .border(BorderStroke(1.dp, NeonPurple.copy(alpha = 0.35f)), RoundedCornerShape(32.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            text = "Escribe una solicitud en Español",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 14.sp
                        )
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = NeonPurple
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitRequest(inputText) })
                )

                // White circular button with Microphone
                Surface(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            submitRequest(inputText)
                        } else {
                            // Launch native speech recognizer
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Dile a $djName qué quieres escuchar...")
                            }
                            try {
                                speechLauncher.launch(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Reconocimiento de voz no disponible", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    shape = CircleShape,
                    color = Color.White,
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (inputText.isNotBlank()) {
                            Text(
                                text = "➤",
                                color = Color.Black,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.mic),
                                contentDescription = "Hablar con el DJ",
                                tint = Color.Black,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
