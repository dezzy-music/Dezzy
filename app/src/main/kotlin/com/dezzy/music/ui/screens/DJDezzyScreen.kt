package com.dezzy.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.models.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow

private val DeepPurpleBackground = Color(0xFF0C041A)
private val DarkPurpleOverlay = Color(0xFF140728)
private val NeonPurpleAccent = Color(0xFFA855F7)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DJDezzyScreen(
    navController: NavController,
    viewModel: DJDezzyViewModel = hiltViewModel()
) {
    var showBottomSheet by remember { mutableStateOf(false) }

    val playerConnection = LocalPlayerConnection.current
    val uiState by viewModel.uiState.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val mediaMetadata by (playerConnection?.mediaMetadata ?: MutableStateFlow<MediaMetadata?>(null)).collectAsState()
    val isPlaying by (playerConnection?.isPlaying ?: MutableStateFlow(false)).collectAsState()

    val (customDjName) = com.dezzy.music.utils.rememberPreference(com.dezzy.music.constants.DjNameKey, "")
    val djName = if (customDjName.isNotBlank()) "DJ $customDjName" else "DJ"
    val isDjTalking = mediaMetadata?.artists?.any { it.name == "Mezcla Especial" } == true || 
                      mediaMetadata?.title == djName || 
                      uiState == DJUiState.SPEAKING

    LaunchedEffect(playerConnection) {
        if (playerConnection != null && uiState == DJUiState.IDLE) {
            viewModel.startDjSession(playerConnection)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        DeepPurpleBackground,
                        DarkPurpleOverlay,
                        Color(0xFF0A0316)
                    )
                )
            )
    ) {
        // Dynamic 3D Particle Sphere Visualizer
        DJVisualizerBox(
            modifier = Modifier.fillMaxSize(),
            playerConnection = playerConnection,
            mediaMetadata = mediaMetadata,
            isDjTalking = isDjTalking,
            transparentBackground = true
        )

        // Spotify DJ Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    painter = painterResource(R.drawable.expand_more),
                    contentDescription = "Cerrar",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "DJ",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.75f),
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Lo que has estado escuchando",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = { /* 3-dots action menu */ }) {
                Icon(
                    painter = painterResource(R.drawable.more_vert),
                    contentDescription = "Opciones",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // DJ Status Toast Pill when DJ is talking or generating
        AnimatedVisibility(
            visible = statusText.isNotBlank() && (isDjTalking || uiState == DJUiState.GENERATING),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 70.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, NeonPurpleAccent.copy(alpha = 0.6f))
            ) {
                Text(
                    text = statusText,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        // Spotify DJ Bottom Playback Bar with Floating DJ FAB
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            // Centered Playback controls: Previous, Play/Pause White Hero, Next
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { playerConnection?.seekToPrevious() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.skip_previous),
                        contentDescription = "Anterior",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(28.dp)
                    )
                }

                // White Solid Circle Play/Pause Hero Button
                Surface(
                    onClick = { playerConnection?.player?.togglePlayPause() },
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 10.dp,
                    modifier = Modifier.size(66.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                            contentDescription = "Play/Pause",
                            tint = Color.Black,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                IconButton(
                    onClick = { playerConnection?.seekToNext() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.skip_next),
                        contentDescription = "Siguiente",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Spotify DJ Floating Action Button (FAB) at bottom-right
            Surface(
                onClick = { showBottomSheet = true },
                shape = CircleShape,
                color = Color(0xFF1E0A38),
                border = BorderStroke(1.5.dp, NeonPurpleAccent),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // DJ circular neon symbol
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .border(BorderStroke(2.5.dp, NeonPurpleAccent), CircleShape)
                    )
                }
            }
        }

        // Draggable Spotify-authentic DJ Bottom Sheet
        if (showBottomSheet) {
            DJInteractionBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                viewModel = viewModel,
                playerConnection = playerConnection,
                mediaMetadata = mediaMetadata
            )
        }
    }
}
