package com.arturo254.opentune.ui.player

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.ripple
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.imageLoader
import com.arturo254.opentune.LocalPlayerConnection
import com.arturo254.opentune.R
import com.arturo254.opentune.extensions.togglePlayPause
import com.arturo254.opentune.constants.IpodThemeMode
import com.arturo254.opentune.constants.IpodThemeModeKey
import com.arturo254.opentune.utils.rememberPreference
import com.arturo254.opentune.utils.makeTimeString
import com.arturo254.opentune.ui.component.BottomSheetState
import com.arturo254.opentune.ui.component.Lyrics
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IpodPlayer(
    state: BottomSheetState,
    navController: NavController,
    positionState: State<Long>,
    durationState: State<Long>,
    sliderPosition: Long?,
    onSliderPositionChange: (Long?) -> Unit,
    onQueueClick: () -> Unit,
    onMenuClick: () -> Unit,
    onVisualizerClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()

    // Compartir directo (tarjeta Canción/Letra/Canvas)
    var showShareSheet by remember { mutableStateOf(false) }
    val shareLyricsEntity by playerConnection.currentLyrics.collectAsState(initial = null)
    if (showShareSheet) {
        com.arturo254.opentune.ui.component.ShareBottomSheet(
            title = mediaMetadata?.title ?: "",
            subtitle = mediaMetadata?.artists?.joinToString { it.name } ?: "",
            thumbnailUrl = mediaMetadata?.thumbnailUrl,
            shareLink = "https://music.youtube.com/watch?v=${mediaMetadata?.id ?: ""}",
            lyrics = shareLyricsEntity?.lyrics,
            mediaId = mediaMetadata?.id,
            onDismiss = { showShareSheet = false }
        )
    }
    
    val position by positionState
    val duration by durationState

    val (ipodThemeModeVal) = rememberPreference(
        IpodThemeModeKey,
        defaultValue = IpodThemeMode.CLASSIC.name
    )
    val ipodThemeMode = remember(ipodThemeModeVal) {
        runCatching { IpodThemeMode.valueOf(ipodThemeModeVal) }
            .getOrDefault(IpodThemeMode.CLASSIC)
    }

    val context = LocalContext.current
    var dominantColor by remember { mutableStateOf(Color(0xFFEAEDED)) }
    val coverUrl = mediaMetadata?.thumbnailUrl
    LaunchedEffect(coverUrl) {
        if (coverUrl != null) {
            withContext(Dispatchers.IO) {
                try {
                    val loader = context.imageLoader
                    val req = coil3.request.ImageRequest.Builder(context)
                        .data(coverUrl)
                        .allowHardware(false)
                        .size(120, 120)
                        .build()
                    val result = loader.execute(req)
                    val bmp = result.image?.toBitmap()
                    if (bmp != null) {
                        val palette = androidx.palette.graphics.Palette.from(bmp).generate()
                        val colorVal = palette.getVibrantColor(
                            palette.getDominantColor(0xFFEAEDED.toInt())
                        )
                        dominantColor = Color(colorVal)
                    }
                } catch (_: Exception) {}
            }
        } else {
            dominantColor = Color(0xFFEAEDED)
        }
    }

    val bodyColor = if (ipodThemeMode == IpodThemeMode.DYNAMIC) dominantColor else Color(0xFFEAEDED)
    val isDarkBody = remember(bodyColor) {
        val red = bodyColor.red
        val green = bodyColor.green
        val blue = bodyColor.blue
        (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f
    }

    val isLocalSong = mediaMetadata?.id?.startsWith("local_") == true
    var showLyrics by remember { mutableStateOf(false) }

    // Auto-disable lyrics if a local song starts
    LaunchedEffect(mediaMetadata?.id) {
        if (isLocalSong) {
            showLyrics = false
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val screenWidthDp = configuration.screenWidthDp.dp
    val screenHeightDp = configuration.screenHeightDp.dp

    // Dynamic LCD height transition — clamped to the screen so it never overflows
    // on short/small devices while keeping the tuned size on typical phones.
    val lcdHeight by animateDpAsState(
        targetValue = (if (isLandscape) 190.dp else if (showLyrics) 245.dp else 210.dp)
            .coerceAtMost(screenHeightDp * 0.42f),
        animationSpec = tween(durationMillis = 300),
        label = "LcdHeightAnimation"
    )

    // Click wheel scales down on narrow screens so it always fits; unchanged on
    // typical/large phones.
    val clickWheelSize = (if (isLandscape) 185.dp else 240.dp)
        .coerceAtMost(screenWidthDp * 0.72f)

    val topBarColor = if (isDarkBody) Color.White else Color.Black
    val topBar = @Composable {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { state.collapseSoft() }) {
                Icon(
                    painter = painterResource(R.drawable.expand_more),
                    contentDescription = "Collapse",
                    tint = topBarColor,
                    modifier = Modifier.size(32.dp)
                )
            }
            
            Text(
                text = if (ipodThemeMode == IpodThemeMode.DYNAMIC) "iPod Dinámico" else "iPod Classic",
                color = topBarColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = MaterialTheme.typography.bodyLarge.fontFamily
            )

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.arturo254.opentune.ui.component.CastButton(tint = topBarColor)

                // Video musical · visualizador · compartir
                IconButton(onClick = { playerConnection.enableVideoMode() }) {
                    Icon(
                        painter = painterResource(R.drawable.slow_motion_video),
                        contentDescription = "Video",
                        tint = topBarColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onVisualizerClick) {
                    Icon(
                        painter = painterResource(R.drawable.auto_awesome),
                        contentDescription = "Visualizador",
                        tint = topBarColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = { showShareSheet = true }) {
                    Icon(
                        painter = painterResource(R.drawable.share),
                        contentDescription = "Compartir",
                        tint = topBarColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Lyrics Button (Only visible for online songs)
                if (!isLocalSong) {
                    IconButton(onClick = { showLyrics = !showLyrics }) {
                        Icon(
                            painter = painterResource(R.drawable.lyrics),
                            contentDescription = "Lyrics",
                            tint = if (showLyrics) (if (isDarkBody) Color(0xFFC2D9D2) else Color(0xFF1B2631)) else topBarColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                IconButton(onClick = onQueueClick) {
                    Icon(
                        painter = painterResource(R.drawable.queue_music),
                        contentDescription = "Queue",
                        tint = topBarColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }

    val lcdScreenCard = @Composable {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(lcdHeight)
                .shadow(8.dp, RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF566573), RoundedCornerShape(8.dp))
                .padding(1.dp)
                .border(3.dp, Color(0xFFBDC3C7), RoundedCornerShape(7.dp)),
            shape = RoundedCornerShape(7.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Vignette Backlight Backing
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFC2D9D2), // Lighter center
                                    Color(0xFF9FB2AB)  // Darker vignette edges
                                )
                            )
                        )
                )

                val batteryInfo by rememberBatteryInfo()
                Column(modifier = Modifier.fillMaxSize()) {
                    // Top LCD status bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Tiny Play/Pause Status Icon
                        Icon(
                            painter = painterResource(if (isPlaying) R.drawable.play else R.drawable.pause),
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(10.dp)
                        )

                        // Center Now Playing title
                        Text(
                            text = stringResource(R.string.now_playing),
                            color = Color.Black,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily
                        )

                        // Battery Indicator
                        BatteryIndicator(
                            level = batteryInfo.first,
                            isCharging = batteryInfo.second
                        )
                    }

                    // Retro Divider line below status bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0xFF7B9990).copy(alpha = 0.6f))
                    )

                    // Main LCD Screen Area Content
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        AnimatedContent(
                            targetState = showLyrics,
                            transitionSpec = {
                                androidx.compose.animation.fadeIn(animationSpec = tween(300)) togetherWith
                                androidx.compose.animation.fadeOut(animationSpec = tween(300))
                            },
                            label = "LcdContentTransition"
                        ) { targetShowLyrics ->
                            if (targetShowLyrics) {
                                // Split Dual-Pane layout: Cover/Metadata/Time (Left) + Sync Lyrics (Right)
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(if (isLandscape) 4.dp else 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .width(if (isLandscape) 95.dp else 105.dp)
                                            .fillMaxHeight(),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current)
                                                .data(mediaMetadata?.thumbnailUrl)
                                                .build(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(if (isLandscape) 56.dp else 68.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .border(1.dp, Color(0xFF7B9990), RoundedCornerShape(3.dp))
                                        )

                                        Spacer(modifier = Modifier.height(if (isLandscape) 2.dp else 6.dp))

                                        Text(
                                            text = mediaMetadata?.title ?: "No Title",
                                            color = Color.Black,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                                            fontSize = if (isLandscape) 7.sp else 8.5.sp,
                                            maxLines = if (isLandscape) 1 else 2,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center
                                        )

                                        Spacer(modifier = Modifier.height(1.dp))

                                        Text(
                                            text = mediaMetadata?.artists?.joinToString { it.name } ?: "Unknown",
                                            color = Color(0xFF2C3E50),
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                                            fontSize = if (isLandscape) 6.sp else 7.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center
                                        )

                                        Spacer(modifier = Modifier.height(if (isLandscape) 3.dp else 8.dp))

                                        // Small Progress Bar
                                        val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(3.dp)
                                                .background(Color(0xFF7B9990).copy(alpha = 0.5f), RoundedCornerShape(1.5.dp))
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                                                    .fillMaxHeight()
                                                    .background(Color(0xFF1B2631), RoundedCornerShape(1.5.dp))
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(2.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = makeTimeString(position),
                                                color = Color(0xFF2C3E50),
                                                fontSize = 7.sp,
                                                fontFamily = MaterialTheme.typography.bodyLarge.fontFamily
                                            )
                                            Text(
                                                text = makeTimeString(duration),
                                                color = Color(0xFF2C3E50),
                                                fontSize = 7.sp,
                                                fontFamily = MaterialTheme.typography.bodyLarge.fontFamily
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(if (isLandscape) 4.dp else 8.dp))

                                    // Retro screen divider line
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(1.dp)
                                            .background(Color(0xFF7B9990).copy(alpha = 0.35f))
                                    )

                                    Spacer(modifier = Modifier.width(if (isLandscape) 4.dp else 8.dp))

                                    // Sync Scrolling Lyrics block (reutilizando Lyrics de OpenTune adaptado)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    ) {
                                        Lyrics(
                                            lyricsSyncOffset = 0,
                                            sliderPositionProvider = { sliderPosition },
                                            modifier = Modifier.fillMaxSize(),
                                            // Proporcional a la tipografía retro del iPod
                                            // (título 8.5sp / artista 7.5sp) en vez del
                                            // tamaño global de letras (26sp por defecto).
                                            textSizeOverride = if (isLandscape) 8f else 10f,
                                        )
                                    }
                                }
                            } else {
                                // Standard Layout: Cover Art (Left) + Text/Progress (Right)
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp, vertical = if (isLandscape) 4.dp else 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(mediaMetadata?.thumbnailUrl)
                                            .build(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(if (isLandscape) 90.dp else 105.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .border(1.dp, Color(0xFF7B9990), RoundedCornerShape(3.dp))
                                    )

                                    Spacer(modifier = Modifier.width(16.dp))

                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = mediaMetadata?.title ?: "No Title",
                                            color = Color.Black,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                                            fontSize = if (isLandscape) 9.sp else 10.5.sp,
                                            maxLines = if (isLandscape) 1 else 2,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Spacer(modifier = Modifier.height(if (isLandscape) 3.dp else 6.dp))

                                        Text(
                                            text = mediaMetadata?.artists?.joinToString { it.name } ?: "Unknown Artist",
                                            color = Color(0xFF2C3E50),
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                                            fontSize = if (isLandscape) 7.5.sp else 8.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Spacer(modifier = Modifier.height(if (isLandscape) 8.dp else 14.dp))

                                        val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(if (isLandscape) 4.dp else 6.dp)
                                                .background(Color(0xFF7B9990).copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                                                    .fillMaxHeight()
                                                    .background(Color(0xFF1B2631), RoundedCornerShape(3.dp))
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(if (isLandscape) 3.dp else 6.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = makeTimeString(position),
                                                color = Color(0xFF2C3E50),
                                                fontSize = if (isLandscape) 7.5.sp else 8.5.sp,
                                                fontFamily = MaterialTheme.typography.bodyLarge.fontFamily
                                            )
                                            Text(
                                                text = makeTimeString(duration),
                                                color = Color(0xFF2C3E50),
                                                fontSize = if (isLandscape) 7.5.sp else 8.5.sp,
                                                fontFamily = MaterialTheme.typography.bodyLarge.fontFamily
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Diagonal Glass Glare Reflection Overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.15f),
                                    Color.White.copy(alpha = 0.05f),
                                    Color.Transparent,
                                    Color.Transparent
                                ),
                                start = Offset(0f, 0f),
                                end = Offset(1000f, 1000f)
                            )
                        )
                )
            }
        }
    }

    val wheelBodyColor = if (isDarkBody) Color.White.copy(alpha = 0.15f) else Color(0xFFF2F4F4)
    val wheelBorderColor = if (isDarkBody) Color.White.copy(alpha = 0.25f) else Color(0xFFBDC3C7)
    val centerButtonBodyColor = if (isDarkBody) Color.White.copy(alpha = 0.22f) else Color(0xFFEAEDED)
    val wheelTextColor = if (isDarkBody) Color.White else Color(0xFF5D6D7E)

    val clickWheel = @Composable {
        Box(
            modifier = Modifier
                .size(clickWheelSize)
                .drawBehind {
                    // Draw a completely smooth circular circular shadow
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = if (isDarkBody) 0.12f else 0.18f),
                                Color.Black.copy(alpha = if (isDarkBody) 0.05f else 0.08f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = size.width / 2f
                        ),
                        radius = size.width / 2f
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerOffset = center
                val outerRadius = size.minDimension / 2f
                val innerRadius = (if (isLandscape) 32.dp else 40.dp).toPx()

                // Outer light gray click wheel body
                drawCircle(
                    color = wheelBodyColor,
                    radius = outerRadius
                )

                // Outer border rim for elevation depth
                drawCircle(
                    color = wheelBorderColor,
                    radius = outerRadius,
                    style = Stroke(width = 1.dp.toPx())
                )

                // Central action button recess border
                drawCircle(
                    color = wheelBorderColor,
                    radius = innerRadius,
                    style = Stroke(width = 1.dp.toPx())
                )

                // Central action button body
                drawCircle(
                    color = centerButtonBodyColor,
                    radius = innerRadius - 0.5f
                )
            }

            // Central Select Button text or action indicator (authentic completely blank button)
            Box(
                modifier = Modifier
                    .size(if (isLandscape) 64.dp else 80.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = if (isDarkBody) Color.White.copy(alpha = 0.3f) else Color(0xFFBDC3C7), radius = if (isLandscape) 32.dp else 40.dp)
                    ) {
                        playerConnection.player.togglePlayPause()
                    }
            )

            // Click Wheel Label Buttons
            Box(modifier = Modifier.fillMaxSize()) {
                // MENU (Top Button)
                Text(
                    text = "MENU",
                    color = wheelTextColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isLandscape) 10.sp else 11.sp,
                    fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (isLandscape) 10.dp else 16.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onMenuClick()
                        }
                )

                // PLAY/PAUSE (Bottom Button)
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (isLandscape) 10.dp else 16.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            playerConnection.player.togglePlayPause()
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = "Play",
                        tint = wheelTextColor,
                        modifier = Modifier.size(if (isLandscape) 11.dp else 14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(R.drawable.pause),
                        contentDescription = "Pause",
                        tint = wheelTextColor,
                        modifier = Modifier.size(if (isLandscape) 11.dp else 14.dp)
                    )
                }

                // PREVIOUS (Left Button)
                IconButton(
                    onClick = { playerConnection.seekToPrevious() },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = if (isLandscape) 8.dp else 14.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.skip_previous),
                        contentDescription = "Previous",
                        tint = wheelTextColor,
                        modifier = Modifier.size(if (isLandscape) 22.dp else 28.dp)
                    )
                }

                // NEXT (Right Button)
                IconButton(
                    onClick = { playerConnection.seekToNext() },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = if (isLandscape) 8.dp else 14.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.skip_next),
                        contentDescription = "Next",
                        tint = wheelTextColor,
                        modifier = Modifier.size(if (isLandscape) 22.dp else 28.dp)
                    )
                }
            }
        }
    }

    // Premium iPod Classic Outer Body
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bodyColor) // Elegant light gray / off-white or dynamic iPod chassis
            .padding(
                horizontal = if (isLandscape) 32.dp else 24.dp, 
                vertical = if (isLandscape) 8.dp else 16.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .statusBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1.3f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    topBar()
                    Spacer(modifier = Modifier.height(4.dp))
                    lcdScreenCard()
                }
                
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    clickWheel()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .statusBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                topBar()
                Spacer(modifier = Modifier.height(16.dp))
                lcdScreenCard()
                Spacer(modifier = Modifier.weight(1f))
                clickWheel()
                Spacer(modifier = Modifier.weight(1.5f))
            }
        }
    }
}

@Composable
private fun rememberBatteryInfo(): State<Pair<Int, Boolean>> {
    val context = LocalContext.current
    val batteryInfo = remember { mutableStateOf(Pair(100, false)) }
    
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                if (level >= 0 && scale > 0) {
                    batteryInfo.value = Pair((level * 100) / scale, isCharging)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(receiver, filter)
        
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
    
    return batteryInfo
}

@Composable
private fun BatteryIndicator(
    level: Int,
    isCharging: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Text(
            text = "$level%",
            fontSize = 8.sp,
            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = Modifier.padding(end = 4.dp)
        )
        
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(9.dp)
                .border(1.dp, Color.Black, RoundedCornerShape(1.dp))
                .padding(1.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(level / 100f)
                    .background(
                        if (isCharging) Color(0xFF1E8449) 
                        else if (level <= 20) Color(0xFFC0392B)
                        else Color.Black, 
                        RoundedCornerShape(0.5.dp)
                    )
            )
        }
        
        Box(
            modifier = Modifier
                .width(1.5.dp)
                .height(3.dp)
                .background(Color.Black, RoundedCornerShape(topEnd = 0.5.dp, bottomEnd = 0.5.dp))
        )
    }
}
