package com.arturo254.opentune.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.arturo254.opentune.LocalPlayerConnection
import com.arturo254.opentune.R
import com.arturo254.opentune.extensions.togglePlayPause
import com.arturo254.opentune.extensions.toggleRepeatMode
import com.arturo254.opentune.ui.component.BottomSheetState
import com.arturo254.opentune.utils.makeTimeString
import com.arturo254.opentune.models.MediaMetadata
import com.arturo254.opentune.ui.component.LocalMenuState
import com.arturo254.opentune.ui.component.Lyrics
import com.arturo254.opentune.LocalDatabase
import kotlinx.coroutines.launch
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.arturo254.opentune.utils.rememberPreference
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SpotifyPlayer(
    state: BottomSheetState,
    navController: NavController,
    positionState: State<Long>,
    durationState: State<Long>,
    sliderPosition: Long?,
    onSliderPositionChange: (Long?) -> Unit,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    
    val position by positionState
    val duration by durationState

    val scrollState = rememberScrollState()
    val menuState = LocalMenuState.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val isExpanded = state.progress > 0.99f

    // Detect local songs
    val isLocalSong = mediaMetadata?.id?.startsWith("local_") == true

    // Dynamic gradient from album art — single dominant color fading to black
    var dominantColor by remember { mutableStateOf(Color(0xFF1A0A0A)) }
    val fallbackColor = Color(0xFF121212).toArgb()

    LaunchedEffect(mediaMetadata?.id) {
        val thumbnailUrl = mediaMetadata?.thumbnailUrl
        if (thumbnailUrl != null) {
            withContext(Dispatchers.IO) {
                val request = ImageRequest.Builder(context)
                    .data(thumbnailUrl)
                    .size(100, 100)
                    .allowHardware(false)
                    .build()
                val result = runCatching { context.imageLoader.execute(request) }.getOrNull()
                val bitmap = result?.image?.toBitmap()
                if (bitmap != null) {
                    val palette = withContext(Dispatchers.Default) {
                        Palette.from(bitmap).maximumColorCount(8).generate()
                    }
                    val dominant = palette.getDominantColor(fallbackColor)
                    withContext(Dispatchers.Main) {
                        dominantColor = Color(dominant).copy(alpha = 1f)
                    }
                }
            }
        }
    }

    var lyricsFullscreen by remember { mutableStateOf(false) }
    
    // Orientation for responsive layout
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val renderExtraCards: @Composable () -> Unit = {
        if (isLocalSong) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(16.dp)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.library_music),
                            contentDescription = null,
                            tint = Color(0xFF1DB954),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Música Local",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Esta pista se reproduce desde tu almacenamiento local.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }

        if (!isLocalSong) {
            // Letra Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.White.copy(alpha = 0.07f),
                                1.0f to Color.White.copy(alpha = 0.04f)
                            )
                        )
                    )
                    .padding(16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Letras",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { lyricsFullscreen = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.fullscreen),
                                    contentDescription = "Expandir letra",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.heightIn(max = 400.dp)) {
                        Lyrics(
                            lyricsSyncOffset = 0,
                            sliderPositionProvider = { sliderPosition },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(dominantColor, Color.Black)
                )
            )
    ) {
        if (!isLandscape) {
            // Portrait Layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { state.collapseSoft() }) {
                        Icon(
                            painter = painterResource(R.drawable.expand_more),
                            contentDescription = "Cerrar",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Text(
                        text = "Estilo Spotify",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(onClick = onQueueClick) {
                        Icon(
                            painter = painterResource(R.drawable.queue_music),
                            contentDescription = "Queue",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Big Artwork
                AsyncImage(
                    model = mediaMetadata?.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Song Title & Artist info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mediaMetadata?.title ?: "Unknown Title",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = mediaMetadata?.artists?.joinToString { it.name } ?: "Unknown Artist",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    IconButton(onClick = { playerConnection.toggleLike() }) {
                        val currentSongLiked = false // mock or get from state
                        Icon(
                            painter = painterResource(if (currentSongLiked) R.drawable.favorite else R.drawable.favorite_border),
                            contentDescription = "Like",
                            tint = if (currentSongLiked) Color(0xFF1DB954) else Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Seekbar / Slider
                Slider(
                    value = (sliderPosition ?: position).toFloat(),
                    valueRange = 0f..maxOf(duration.toFloat(), 1f),
                    onValueChange = { onSliderPositionChange(it.toLong()) },
                    onValueChangeFinished = {
                        sliderPosition?.let { playerConnection.player.seekTo(it) }
                        onSliderPositionChange(null)
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = makeTimeString(sliderPosition ?: position), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    Text(text = makeTimeString(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Playback Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled }) {
                        Icon(
                            painter = painterResource(R.drawable.shuffle),
                            contentDescription = "Shuffle",
                            tint = if (shuffleModeEnabled) Color(0xFF1DB954) else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    IconButton(onClick = { playerConnection.seekToPrevious() }) {
                        Icon(
                            painter = painterResource(R.drawable.skip_previous),
                            contentDescription = "Previous",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable { playerConnection.player.togglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                            contentDescription = "Play/Pause",
                            tint = Color.Black,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(onClick = { playerConnection.seekToNext() }) {
                        Icon(
                            painter = painterResource(R.drawable.skip_next),
                            contentDescription = "Next",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(onClick = { playerConnection.player.toggleRepeatMode() }) {
                        Icon(
                            painter = painterResource(
                                when (repeatMode) {
                                    Player.REPEAT_MODE_ONE -> R.drawable.repeat_one
                                    else -> R.drawable.repeat
                                }
                            ),
                            contentDescription = "Repeat",
                            tint = if (repeatMode != Player.REPEAT_MODE_OFF) Color(0xFF1DB954) else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                renderExtraCards()
            }
        } else {
            // Landscape Layout
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Column: Large Artwork
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = mediaMetadata?.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }

                // Right Column: Controls and Details
                Column(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mediaMetadata?.title ?: "Unknown Title",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = mediaMetadata?.artists?.joinToString { it.name } ?: "Unknown Artist",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 16.sp,
                                maxLines = 1
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Seekbar
                    Slider(
                        value = (sliderPosition ?: position).toFloat(),
                        valueRange = 0f..maxOf(duration.toFloat(), 1f),
                        onValueChange = { onSliderPositionChange(it.toLong()) },
                        onValueChangeFinished = {
                            sliderPosition?.let { playerConnection.player.seekTo(it) }
                            onSliderPositionChange(null)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = makeTimeString(sliderPosition ?: position), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                        Text(text = makeTimeString(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Controls row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled }) {
                            Icon(
                                painter = painterResource(R.drawable.shuffle),
                                contentDescription = "Shuffle",
                                tint = if (shuffleModeEnabled) Color(0xFF1DB954) else Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(onClick = { playerConnection.seekToPrevious() }) {
                            Icon(painter = painterResource(R.drawable.skip_previous), contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .clickable { playerConnection.player.togglePlayPause() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                                contentDescription = "Play/Pause",
                                tint = Color.Black,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        IconButton(onClick = { playerConnection.seekToNext() }) {
                            Icon(painter = painterResource(R.drawable.skip_next), contentDescription = "Next", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        IconButton(onClick = { playerConnection.player.toggleRepeatMode() }) {
                            Icon(
                                painter = painterResource(
                                    when (repeatMode) {
                                        Player.REPEAT_MODE_ONE -> R.drawable.repeat_one
                                        else -> R.drawable.repeat
                                    }
                                ),
                                contentDescription = "Repeat",
                                tint = if (repeatMode != Player.REPEAT_MODE_OFF) Color(0xFF1DB954) else Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    renderExtraCards()
                }
            }
        }
    }
}
