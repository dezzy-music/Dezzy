package com.dezzy.music.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.models.toMediaMetadata
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.ui.component.EmptyPlaceholder
import com.dezzy.music.ui.component.NavigationTitle
import com.dezzy.music.ui.component.shimmer.ListItemPlaceHolder
import com.dezzy.music.ui.component.shimmer.ShimmerHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TimeMachineScreen(
    navController: NavController
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var activeDecade by remember { mutableStateOf<Int?>(null) }
    var activeYear by remember { mutableStateOf<Int?>(null) }
    var isTimeTraveling by remember { mutableStateOf(false) }
    var songs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isLoadingSongs by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val handleBackPress = {
        if (activeYear != null || activeDecade != null) {
            activeYear = null
            activeDecade = null
            songs = emptyList()
        } else {
            navController.navigateUp()
        }
    }

    var currentPortalYear by remember { mutableStateOf(2026) }
    LaunchedEffect(isTimeTraveling) {
        if (isTimeTraveling) {
            val target = activeYear ?: (activeDecade ?: 1990)
            val step = if (target < 2026) -1 else 1
            val delayDuration = 40L
            while (currentPortalYear != target) {
                currentPortalYear += step
                delay(delayDuration)
            }
            delay(500)
            isTimeTraveling = false

            isLoadingSongs = true
            error = null
            scope.launch {
                val query = if (activeYear != null) {
                    "hits $activeYear"
                } else {
                    "${activeDecade}s pop music hits"
                }

                withContext(Dispatchers.IO) {
                    YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                }.onSuccess { result ->
                    songs = result.items.filterIsInstance<SongItem>()
                    isLoadingSongs = false
                }.onFailure { err ->
                    error = err.localizedMessage
                    isLoadingSongs = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Máquina del Tiempo",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { handleBackPress() }) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = "Atrás"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = when (activeDecade) {
                        2000 -> Color.Black
                        2010, 2020 -> Color.White
                        else -> MaterialTheme.colorScheme.onBackground
                    },
                    navigationIconContentColor = when (activeDecade) {
                        2000 -> Color.Black
                        2010, 2020 -> Color.White
                        else -> MaterialTheme.colorScheme.onBackground
                    }
                )
            )
        },
        containerColor = when {
            isTimeTraveling -> Color(0xFF0F0B1E)
            activeDecade == 1980 -> Color(0xFF140D2B)
            activeDecade == 1990 -> Color(0xFF1E272C)
            activeDecade == 2000 -> Color(0xFFEAEDED)
            activeDecade == 2010 -> Color(0xFF0D1B2A)
            activeDecade == 2020 -> Color(0xFF080710)
            else -> MaterialTheme.colorScheme.background
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isTimeTraveling -> {
                    TimeTravelPortal(year = currentPortalYear)
                }
                activeYear == null -> {
                    DecadeSelector(
                        onSelectDecade = { decade ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            activeDecade = decade
                        },
                        onSelectYear = { decade, year ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            activeDecade = decade
                            activeYear = year
                            currentPortalYear = 2026
                            isTimeTraveling = true
                        }
                    )
                }
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when (activeDecade) {
                                1980 -> CassetteSynthwavePlayer(
                                    songTitle = mediaMetadata?.title ?: "Insert Cassette",
                                    artist = mediaMetadata?.artists?.joinToString { it.name } ?: "Time Travel Radio",
                                    isPlaying = isPlaying,
                                    artworkUrl = mediaMetadata?.thumbnailUrl,
                                    onPlayPause = { playerConnection.player.togglePlayPause() }
                                )
                                1990 -> CdGrungePlayer(
                                    songTitle = mediaMetadata?.title ?: "Insert CD",
                                    artist = mediaMetadata?.artists?.joinToString { it.name } ?: "Time Travel Radio",
                                    isPlaying = isPlaying,
                                    artworkUrl = mediaMetadata?.thumbnailUrl,
                                    onPlayPause = { playerConnection.player.togglePlayPause() }
                                )
                                2000 -> ClassicMp3Player(
                                    songTitle = mediaMetadata?.title ?: "Welcome to Dezzy MP3",
                                    artist = mediaMetadata?.artists?.joinToString { it.name } ?: "Retro Radio",
                                    isPlaying = isPlaying,
                                    onPlayPause = { playerConnection.player.togglePlayPause() },
                                    onNext = { playerConnection.player.seekToNext() },
                                    onPrev = { playerConnection.player.seekToPrevious() }
                                )
                                2010 -> SmartPhoneRetroPlayer(
                                    songTitle = mediaMetadata?.title ?: "No Song Playing",
                                    artist = mediaMetadata?.artists?.joinToString { it.name } ?: "Unknown Artist",
                                    isPlaying = isPlaying,
                                    artworkUrl = mediaMetadata?.thumbnailUrl,
                                    onPlayPause = { playerConnection.player.togglePlayPause() },
                                    onNext = { playerConnection.player.seekToNext() },
                                    onPrev = { playerConnection.player.seekToPrevious() }
                                )
                                2020 -> HolographicSpeakerPlayer(
                                    songTitle = mediaMetadata?.title ?: "Smart Speaker Offline",
                                    artist = mediaMetadata?.artists?.joinToString { it.name } ?: "Activate AI Voice",
                                    isPlaying = isPlaying,
                                    artworkUrl = mediaMetadata?.thumbnailUrl,
                                    onPlayPause = { playerConnection.player.togglePlayPause() },
                                    onNext = { playerConnection.player.seekToNext() },
                                    onPrev = { playerConnection.player.seekToPrevious() }
                                )
                            }
                        }

                        Text(
                            text = "Seleccionar año" + " ($activeYear Hits)",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = when (activeDecade) {
                                    1980 -> Color(0xFFFF007F)
                                    2000 -> Color.Black
                                    2010 -> Color(0xFF3498DB)
                                    2020 -> Color(0xFFF72585)
                                    else -> MaterialTheme.colorScheme.onBackground
                                }
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )

                        if (isLoadingSongs) {
                            ShimmerHost {
                                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                    items(8) {
                                        ListItemPlaceHolder()
                                    }
                                }
                            }
                        } else if (error != null) {
                            EmptyPlaceholder(
                                icon = R.drawable.sync,
                                text = error ?: "Unknown error"
                            )
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                items(songs) { song ->
                                    val isActive = mediaMetadata?.id == song.id
                                    val textColor = when {
                                        activeDecade == 2000 -> if (isActive) Color(0xFF005580) else Color.Black
                                        activeDecade == 2010 -> if (isActive) Color(0xFF3498DB) else Color.White
                                        activeDecade == 2020 -> if (isActive) Color(0xFFF72585) else Color.White
                                        isActive && activeDecade == 1980 -> Color(0xFFFF007F)
                                        isActive && activeDecade == 1990 -> Color(0xFF00FFCC)
                                        isActive -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onBackground
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                playerConnection.playQueue(
                                                    YouTubeQueue(
                                                        WatchEndpoint(videoId = song.id),
                                                        song.toMediaMetadata()
                                                    )
                                                )
                                            }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current)
                                                .data(song.thumbnail)
                                                .memoryCachePolicy(CachePolicy.ENABLED)
                                                .build(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color.DarkGray)
                                        )

                                        Spacer(modifier = Modifier.width(16.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = song.title,
                                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                                color = textColor,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = song.artists.joinToString { it.name },
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (activeDecade == 2000) Color.DarkGray else if (activeDecade == 2010 || activeDecade == 2020) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        if (isActive && isPlaying) {
                                            Icon(
                                                painter = painterResource(R.drawable.pause),
                                                contentDescription = null,
                                                tint = textColor,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        } else {
                                            Icon(
                                                painter = painterResource(R.drawable.play),
                                                contentDescription = null,
                                                tint = textColor.copy(alpha = 0.8f),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DecadeSelector(
    onSelectDecade: (Int) -> Unit,
    onSelectYear: (Int, Int) -> Unit
) {
    var expandedDecade by remember { mutableStateOf<Int?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Seleccionar Década",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        item {
            DecadeCard(
                decadeTitle = "Años 80",
                decadeDesc = "80s Neon Synthwave & Electropop",
                startColor = Color(0xFF8E2DE2),
                endColor = Color(0xFF4A00E0),
                isExpanded = expandedDecade == 1980,
                onClick = {
                    expandedDecade = if (expandedDecade == 1980) null else 1980
                },
                years = (1980..1989).toList(),
                onSelectYear = { year -> onSelectYear(1980, year) }
            )
        }

        item {
            DecadeCard(
                decadeTitle = "Años 90",
                decadeDesc = "90s CD Grunge & Eurodance Hits",
                startColor = Color(0xFF1F1C2C),
                endColor = Color(0xFF928DAB),
                isExpanded = expandedDecade == 1990,
                onClick = {
                    expandedDecade = if (expandedDecade == 1990) null else 1990
                },
                years = (1990..1999).toList(),
                onSelectYear = { year -> onSelectYear(1990, year) }
            )
        }

        item {
            DecadeCard(
                decadeTitle = "Años 2000",
                decadeDesc = "2000s MP3 iPod Classic & Winamp Era",
                startColor = Color(0xFF00C9FF),
                endColor = Color(0xFF92FE9D),
                isExpanded = expandedDecade == 2000,
                onClick = {
                    expandedDecade = if (expandedDecade == 2000) null else 2000
                },
                years = (2000..2009).toList(),
                onSelectYear = { year -> onSelectYear(2000, year) }
            )
        }

        item {
            DecadeCard(
                decadeTitle = "Años 2010",
                decadeDesc = "2010s Glassmorphic Smartphone & Streaming Era",
                startColor = Color(0xFF1E3C72),
                endColor = Color(0xFF2A5298),
                isExpanded = expandedDecade == 2010,
                onClick = {
                    expandedDecade = if (expandedDecade == 2010) null else 2010
                },
                years = (2010..2019).toList(),
                onSelectYear = { year -> onSelectYear(2010, year) }
            )
        }

        item {
            DecadeCard(
                decadeTitle = "Años 2020",
                decadeDesc = "2020s Cyberpunk AI, Wearables & Holographic Sounds",
                startColor = Color(0xFF7B2CBF),
                endColor = Color(0xFFE0AAFF),
                isExpanded = expandedDecade == 2020,
                onClick = {
                    expandedDecade = if (expandedDecade == 2020) null else 2020
                },
                years = (2020..2026).toList(),
                onSelectYear = { year -> onSelectYear(2020, year) }
            )
        }
    }
}

@Composable
fun DecadeCard(
    decadeTitle: String,
    decadeDesc: String,
    startColor: Color,
    endColor: Color,
    isExpanded: Boolean,
    onClick: () -> Unit,
    years: List<Int>,
    onSelectYear: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .shadow(8.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(startColor, endColor)))
                .padding(20.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = decadeTitle,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = decadeDesc,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        )
                    }

                    Icon(
                        painter = painterResource(
                            if (isExpanded) R.drawable.close else R.drawable.star
                        ),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Seleccionar año",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val rows = (years.size + 3) / 4
                        for (rowIndex in 0 until rows) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (colIndex in 0..3) {
                                    val index = rowIndex * 4 + colIndex
                                    if (index < years.size) {
                                        val year = years[index]
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(vertical = 4.dp)
                                                .background(
                                                    Color.White.copy(alpha = 0.2f),
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .clickable { onSelectYear(year) }
                                                .padding(vertical = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = year.toString(),
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TimeTravelPortal(year: Int) {
    val infiniteTransition = rememberInfiniteTransition()

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val scaleMultiplier by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070412)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(320.dp)
                .graphicsLayer(
                    rotationZ = rotationAngle,
                    scaleX = scaleMultiplier,
                    scaleY = scaleMultiplier
                )
                .drawBehind {
                    val brushColors = listOf(Color(0xFFFF007F), Color(0xFF00F0FF), Color(0xFF9000FF))
                    drawCircle(
                        brush = Brush.sweepGradient(brushColors),
                        radius = size.width / 2,
                        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                    )

                    drawCircle(
                        brush = Brush.sweepGradient(brushColors.reversed()),
                        radius = (size.width / 2) - 30.dp.toPx(),
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "VIAJANDO EN EL TIEMPO",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF00F0FF),
                    letterSpacing = 4.sp
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = year.toString(),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 72.sp,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Cargando nostalgia...",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color.White.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace
                )
            )
        }
    }
}

@Composable
fun CassetteSynthwavePlayer(
    songTitle: String,
    artist: String,
    isPlaying: Boolean,
    artworkUrl: String?,
    onPlayPause: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition()

    val wheelRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val neonPulse by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        )
    )

    Card(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .shadow(16.dp, RoundedCornerShape(16.dp))
            .border(
                2.dp,
                Brush.linearGradient(listOf(Color(0xFFFF007F), Color(0xFF00F0FF))),
                RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C071E))
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "📼 SYNTHWAVE CASSETTE (1980s)",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color(0xFFFF007F),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                ),
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color(0xFF16112C), RoundedCornerShape(12.dp))
                    .border(2.dp, Color(0xFF00F0FF).copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val columns = 10
                    val rows = 6
                    val colWidth = size.width / columns
                    val rowHeight = size.height / rows
                    for (i in 1 until columns) {
                        drawLine(Color(0x1F00F0FF), Offset(i * colWidth, 0f), Offset(i * colWidth, size.height), strokeWidth = 1f)
                    }
                    for (i in 1 until rows) {
                        drawLine(Color(0x1F00F0FF), Offset(0f, i * rowHeight), Offset(size.width, i * rowHeight), strokeWidth = 1f)
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1.1f)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFFFF007F).copy(alpha = 0.25f), Color(0xFF7209B7).copy(alpha = 0.25f))
                                ),
                                RoundedCornerShape(6.dp)
                            )
                            .border(1.dp, Color(0xFFFF007F).copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(artworkUrl)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.5.dp, Color(0xFF00F0FF), RoundedCornerShape(6.dp))
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = songTitle,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = artist,
                                color = Color(0xFF00F0FF),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        
                        Text(
                            text = "TDK-90",
                            color = Color(0xFFFF007F),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.9f)
                            .background(Color(0xFF0A0515), RoundedCornerShape(8.dp))
                            .border(1.5.dp, Color(0xFFFF007F).copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CassetteReel(rotationAngle = if (isPlaying) wheelRotation else 0f)

                        Box(
                            modifier = Modifier
                                .width(90.dp)
                                .height(38.dp)
                                .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF00F0FF).copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Canvas(modifier = Modifier.size(24.dp, 10.dp)) {
                                    val count = 4
                                    val step = size.width / count
                                    for (i in 0 until count) {
                                        drawLine(Color(0xFF00F0FF), Offset(i * step, 0f), Offset(i * step, size.height), strokeWidth = 2f)
                                    }
                                }
                                Text(
                                    text = "NR",
                                    color = Color(0xFFFF007F),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Canvas(modifier = Modifier.size(24.dp, 10.dp)) {
                                    val count = 4
                                    val step = size.width / count
                                    for (i in 0 until count) {
                                        drawLine(Color(0xFF00F0FF), Offset(i * step, 0f), Offset(i * step, size.height), strokeWidth = 2f)
                                    }
                                }
                            }
                        }

                        CassetteReel(rotationAngle = if (isPlaying) wheelRotation else 0f)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(66.dp)
                        .graphicsLayer(scaleX = neonPulse, scaleY = neonPulse)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFFFF007F), Color(0xFF7209B7))
                            ),
                            CircleShape
                        )
                        .border(2.dp, Color.White, CircleShape)
                        .clickable { onPlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(
                            if (isPlaying) R.drawable.pause else R.drawable.play
                        ),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CassetteReel(rotationAngle: Float) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer(rotationZ = rotationAngle)
            .drawBehind {
                drawCircle(
                    color = Color.Black,
                    radius = size.width / 2
                )
                drawCircle(
                    color = Color(0xFF00F0FF),
                    radius = size.width / 3,
                    style = Stroke(width = 2.dp.toPx())
                )
                val center = Offset(size.width / 2, size.height / 2)
                for (i in 0 until 6) {
                    val angle = Math.toRadians((i * 60).toDouble())
                    val endX = center.x + (size.width / 3) * Math.cos(angle).toFloat()
                    val endY = center.y + (size.width / 3) * Math.sin(angle).toFloat()
                    drawLine(
                        color = Color(0xFF00F0FF),
                        start = center,
                        end = Offset(endX, endY),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
    )
}

@Composable
fun CdGrungePlayer(
    songTitle: String,
    artist: String,
    isPlaying: Boolean,
    artworkUrl: String?,
    onPlayPause: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition()

    val cdRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val iridescentShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Card(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .shadow(20.dp, RoundedCornerShape(24.dp))
            .border(
                1.5.dp,
                Brush.verticalGradient(listOf(Color(0xFF8A95A5), Color(0xFF2C3E50))),
                RoundedCornerShape(24.dp)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF181C1F))
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "💿 VINTAGE DISCMAN (1990s)",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color(0xFF3498DB),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                ),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Box(
                modifier = Modifier
                    .size(220.dp)
                    .background(Color(0xFF0F1215), CircleShape)
                    .border(4.dp, Color(0xFF2C3E50), CircleShape)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(rotationZ = if (isPlaying) cdRotation else 0f)
                        .drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFFEEEEEE),
                                        Color(0xFFCCCCCC),
                                        Color(0xFF999999),
                                        Color(0xFFBBBBBB)
                                    ),
                                    center = center,
                                    radius = size.width / 2
                                ),
                                radius = size.width / 2
                            )

                            val numLines = 6
                            for (i in 0 until numLines) {
                                val baseAngle = (i * (360f / numLines) + iridescentShift) % 360f
                                val startRad = Math.toRadians(baseAngle.toDouble())
                                val endRad = Math.toRadians((baseAngle + 30f).toDouble())

                                val x1 = center.x + (size.width / 2) * Math.cos(startRad).toFloat()
                                val y1 = center.y + (size.width / 2) * Math.sin(startRad).toFloat()
                                val x2 = center.x + (size.width / 2) * Math.cos(endRad).toFloat()
                                val y2 = center.y + (size.width / 2) * Math.sin(endRad).toFloat()

                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(center.x, center.y)
                                    lineTo(x1, y1)
                                    lineTo(x2, y2)
                                    close()
                                }
                                drawPath(
                                    path = path,
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFFFF007F).copy(alpha = 0.25f),
                                            Color(0xFF00FFFF).copy(alpha = 0.25f),
                                            Color(0xFFFFFF00).copy(alpha = 0.25f),
                                            Color.Transparent
                                        ),
                                        start = center,
                                        end = Offset(x1, y1)
                                    )
                                )
                            }

                            for (r in 1..4) {
                                drawCircle(
                                    color = Color.Black.copy(alpha = 0.08f),
                                    radius = (size.width / 2) * (r * 0.2f),
                                    style = Stroke(width = 1.dp.toPx())
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(artworkUrl)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape)
                            .border(3.dp, Color(0xFF181C1F), CircleShape)
                    )

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color(0xFF0F1215), CircleShape)
                            .border(3.dp, Color(0xFF2C3E50), CircleShape)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.CenterEnd)
                        .padding(end = 24.dp)
                        .graphicsLayer(scaleX = if (isPlaying) 1.2f else 1f, scaleY = if (isPlaying) 1.2f else 1f)
                        .background(Color(0xFFFF0000).copy(alpha = 0.7f), CircleShape)
                        .border(1.5.dp, Color.White, CircleShape)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0C190D), RoundedCornerShape(8.dp))
                    .border(2.dp, Color(0xFF1E351A), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "TRACK 01",
                            color = Color(0xFF39FF14),
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "CD-DA",
                            color = Color(0xFF39FF14),
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = songTitle,
                        color = Color(0xFF39FF14),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artist,
                        color = Color(0xFF39FF14).copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF7F8C8D), Color(0xFF34495E))),
                            CircleShape
                        )
                        .border(1.5.dp, Color.White, CircleShape)
                        .clickable { onPlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(
                            if (isPlaying) R.drawable.pause else R.drawable.play
                        ),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ClassicMp3Player(
    songTitle: String,
    artist: String,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .shadow(16.dp, RoundedCornerShape(24.dp))
            .border(2.dp, Color(0xFFBDC3C7), RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF2F4F4))
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .background(Color(0xFF9BC1B7), RoundedCornerShape(8.dp))
                    .border(2.dp, Color(0xFF7B9990), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Dezzy Classic",
                            color = Color(0xFF2C3E50),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Icon(
                            painter = painterResource(
                                if (isPlaying) R.drawable.play else R.drawable.pause
                            ),
                            contentDescription = null,
                            tint = Color(0xFF2C3E50),
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = songTitle,
                        color = Color(0xFF1B2631),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = artist,
                        color = Color(0xFF2C3E50),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Box(
                modifier = Modifier
                    .size(160.dp)
                    .background(Color.White, CircleShape)
                    .border(2.dp, Color(0xFFBDC3C7), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {},
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 4.dp)
                    ) {
                        Text(
                            text = "MENU",
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onPrev) {
                            Icon(
                                painter = painterResource(R.drawable.arrow_back),
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color(0xFFE5E8E8), CircleShape)
                                .border(1.dp, Color(0xFFBDC3C7), CircleShape)
                                .clickable { onPlayPause() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (isPlaying) R.drawable.pause else R.drawable.play
                                ),
                                contentDescription = null,
                                tint = Color.DarkGray,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(onClick = onNext) {
                            Icon(
                                painter = painterResource(R.drawable.star),
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Text(
                        text = "PLAY / PAUSE",
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        fontSize = 9.sp,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SmartPhoneRetroPlayer(
    songTitle: String,
    artist: String,
    isPlaying: Boolean,
    artworkUrl: String?,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .shadow(24.dp, RoundedCornerShape(32.dp))
            .border(
                3.dp,
                Brush.verticalGradient(listOf(Color(0xFFE2E8F0), Color(0xFF475569))),
                RoundedCornerShape(32.dp)
            ),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "📱 RETRO SMARTPHONE (2010s)",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                ),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Box(
                modifier = Modifier
                    .size(190.dp)
                    .background(Color.Black, RoundedCornerShape(20.dp))
                    .border(2.dp, Color(0xFF334155), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(artworkUrl)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp))
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width, size.height * 0.45f)
                        quadraticTo(size.width * 0.5f, size.height * 0.55f, 0f, size.height * 0.45f)
                        close()
                    }
                    drawPath(
                        path = path,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.35f),
                                Color.White.copy(alpha = 0.05f)
                            )
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A))),
                        RoundedCornerShape(14.dp)
                    )
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = songTitle,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = artist,
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(Color(0xFF020617), CircleShape)
                        .border(1.5.dp, Color(0xFF334155), CircleShape)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .fillMaxHeight()
                            .background(
                                Brush.verticalGradient(listOf(Color(0xFF38BDF8), Color(0xFF0284C7))),
                                CircleShape
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPrev,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF334155), Color(0xFF1E293B))),
                            CircleShape
                        )
                        .border(1.5.dp, Color(0xFF475569), CircleShape)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF38BDF8), Color(0xFF0284C7))),
                            CircleShape
                        )
                        .border(2.dp, Color.White, CircleShape)
                ) {
                    Icon(
                        painter = painterResource(
                            if (isPlaying) R.drawable.pause else R.drawable.play
                        ),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(
                    onClick = onNext,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF334155), Color(0xFF1E293B))),
                            CircleShape
                        )
                        .border(1.5.dp, Color(0xFF475569), CircleShape)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.star),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun HolographicSpeakerPlayer(
    songTitle: String,
    artist: String,
    isPlaying: Boolean,
    artworkUrl: String?,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition()

    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        )
    )

    val hologramRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val reverseRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Card(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .shadow(24.dp, RoundedCornerShape(28.dp))
            .border(
                1.5.dp,
                Brush.linearGradient(
                    listOf(
                        Color(0xFFF72585),
                        Color(0xFF7209B7),
                        Color(0xFF4CC9F0)
                    )
                ),
                RoundedCornerShape(28.dp)
            ),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x9905010D))
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "✨ HOLOGRAM SPEAKER (2020s)",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color(0xFFF72585),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                ),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Box(
                modifier = Modifier
                    .size(190.dp)
                    .graphicsLayer(scaleX = glowScale, scaleY = glowScale),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF7209B7).copy(alpha = 0.45f),
                                    Color(0xFF3F37C9).copy(alpha = 0.15f),
                                    Color.Transparent
                                )
                            ),
                            CircleShape
                        )
                )

                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .graphicsLayer(rotationZ = hologramRotation)
                        .drawBehind {
                            drawCircle(
                                brush = Brush.linearGradient(
                                    listOf(Color(0xFFF72585), Color(0xFF7209B7), Color(0xFF4CC9F0))
                                ),
                                radius = size.width / 2,
                                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                )

                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .graphicsLayer(rotationZ = reverseRotation)
                        .drawBehind {
                            drawCircle(
                                brush = Brush.linearGradient(
                                    listOf(Color(0xFF4CC9F0), Color(0xFF4895EF), Color(0xFF7209B7))
                                ),
                                radius = size.width / 2,
                                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                )

                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(artworkUrl)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .border(
                            2.5.dp,
                            Brush.linearGradient(listOf(Color(0xFF4CC9F0), Color(0xFFF72585))),
                            CircleShape
                        )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = songTitle,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = artist,
                color = Color(0xFF4CC9F0),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(30.dp)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val waveHeights = listOf(0.4f, 0.8f, 0.5f, 0.9f, 0.3f, 0.7f, 0.6f, 0.8f, 0.4f)
                waveHeights.forEachIndexed { idx, height ->
                    val waveScale by infiniteTransition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = height,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600 + (idx * 100), easing = EaseInOutSine),
                            repeatMode = RepeatMode.Reverse
                        )
                    )
                    Box(
                        modifier = Modifier
                            .width(5.dp)
                            .fillMaxHeight(if (isPlaying) waveScale else 0.2f)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFFF72585), Color(0xFF4CC9F0))
                                ),
                                RoundedCornerShape(3.dp)
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPrev,
                    modifier = Modifier
                        .size(46.dp)
                        .background(Color(0xFF7209B7).copy(alpha = 0.2f), CircleShape)
                        .border(1.5.dp, Color(0xFF7209B7), CircleShape)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(60.dp)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFFF72585), Color(0xFF7209B7))),
                            CircleShape
                        )
                        .border(2.dp, Color.White, CircleShape)
                ) {
                    Icon(
                        painter = painterResource(
                            if (isPlaying) R.drawable.pause else R.drawable.play
                        ),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                IconButton(
                    onClick = onNext,
                    modifier = Modifier
                        .size(46.dp)
                        .background(Color(0xFF7209B7).copy(alpha = 0.2f), CircleShape)
                        .border(1.5.dp, Color(0xFF7209B7), CircleShape)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.star),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
