/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.dezzy.music.ui.screens

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.models.MediaMetadata
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.constants.StatPeriod
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.extensions.toMediaItem
import com.dezzy.music.models.toMediaMetadata
import com.dezzy.music.playback.queues.ListQueue
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.ui.component.ChoiceChipsRow
import com.dezzy.music.ui.component.HideOnScrollFAB
import com.dezzy.music.ui.component.IconButton
import com.dezzy.music.ui.component.LocalAlbumsGrid
import com.dezzy.music.ui.component.LocalArtistsGrid
import com.dezzy.music.ui.component.LocalMenuState
import com.dezzy.music.ui.component.NavigationTitle
import com.dezzy.music.ui.menu.AlbumMenu
import com.dezzy.music.ui.menu.ArtistMenu
import com.dezzy.music.ui.menu.SongMenu
import com.dezzy.music.ui.utils.backToMain
import com.dezzy.music.utils.joinByBullet
import com.dezzy.music.utils.makeTimeString
import com.dezzy.music.utils.rememberPreference
import com.dezzy.music.constants.DisableBlurKey
import com.dezzy.music.ui.component.ListItem
import com.dezzy.music.ui.component.ItemThumbnail
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.GenericShape
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.dezzy.music.db.entities.Artist
import com.dezzy.music.db.entities.Song
import com.dezzy.music.db.entities.SongWithStats
import com.dezzy.music.viewmodels.StatsViewModel
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StatsScreen(
    navController: NavController,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val context = LocalContext.current

    val indexChips by viewModel.indexChips.collectAsState()
    val mostPlayedSongs by viewModel.mostPlayedSongs.collectAsState()
    val mostPlayedSongsStats by viewModel.mostPlayedSongsStats.collectAsState()
    val mostPlayedArtists by viewModel.mostPlayedArtists.collectAsState()
    val mostPlayedAlbums by viewModel.mostPlayedAlbums.collectAsState()
    val firstEvent by viewModel.firstEvent.collectAsState()
    val currentDate = LocalDateTime.now()

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    val selectedOption by viewModel.selectedOption.collectAsState()

    val weeklyDates =
        if (currentDate != null && firstEvent != null) {
            generateSequence(currentDate) { it.minusWeeks(1) }
                .takeWhile { it.isAfter(firstEvent?.event?.timestamp?.minusWeeks(1)) }
                .mapIndexed { index, date ->
                    val endDate = date.plusWeeks(1).minusDays(1).coerceAtMost(currentDate)
                    val formatter = DateTimeFormatter.ofPattern("dd MMM")

                    val startDateFormatted = formatter.format(date)
                    val endDateFormatted = formatter.format(endDate)

                    val startMonth = date.month
                    val endMonth = endDate.month
                    val startYear = date.year
                    val endYear = endDate.year

                    val text =
                        when {
                            startYear != currentDate.year -> "$startDateFormatted, $startYear - $endDateFormatted, $endYear"
                            startMonth != endMonth -> "$startDateFormatted - $endDateFormatted"
                            else -> "${date.dayOfMonth} - $endDateFormatted"
                        }
                    Pair(index, text)
                }.toList()
        } else {
            emptyList()
        }

    val monthlyDates =
        if (currentDate != null && firstEvent != null) {
            generateSequence(
                currentDate.plusMonths(1).withDayOfMonth(1).minusDays(1)
            ) { it.minusMonths(1) }
                .takeWhile {
                    it.isAfter(
                        firstEvent
                            ?.event
                            ?.timestamp
                            ?.withDayOfMonth(1),
                    )
                }.mapIndexed { index, date ->
                    val formatter = DateTimeFormatter.ofPattern("MMM")
                    val formattedDate = formatter.format(date)
                    val text =
                        if (date.year != currentDate.year) {
                            "$formattedDate ${date.year}"
                        } else {
                            formattedDate
                        }
                    Pair(index, text)
                }.toList()
        } else {
            emptyList()
        }

    val yearlyDates =
        if (currentDate != null && firstEvent != null) {
            generateSequence(
                currentDate
                    .plusYears(1)
                    .withDayOfYear(1)
                    .minusDays(1),
            ) { it.minusYears(1) }
                .takeWhile {
                    it.isAfter(
                        firstEvent
                            ?.event
                            ?.timestamp,
                    )
                }.mapIndexed { index, date ->
                    Pair(index, "${date.year}")
                }.toList()
        } else {
            emptyList()
        }

    val listening by viewModel.listening.collectAsState()
    var showAllSongs by rememberSaveable { mutableStateOf(false) }

    val periodChips =
        when (selectedOption) {
            OptionStats.WEEKS -> weeklyDates
            OptionStats.MONTHS -> monthlyDates
            OptionStats.YEARS -> yearlyDates
            OptionStats.CONTINUOUS -> {
                listOf(
                    StatPeriod.WEEK_1.ordinal to pluralStringResource(
                        R.plurals.n_week,
                        1,
                        1
                    ),
                    StatPeriod.MONTH_1.ordinal to pluralStringResource(
                        R.plurals.n_month,
                        1,
                        1
                    ),
                    StatPeriod.MONTH_3.ordinal to pluralStringResource(
                        R.plurals.n_month,
                        3,
                        3
                    ),
                    StatPeriod.MONTH_6.ordinal to pluralStringResource(
                        R.plurals.n_month,
                        6,
                        6
                    ),
                    StatPeriod.YEAR_1.ordinal to pluralStringResource(
                        R.plurals.n_year,
                        1,
                        1
                    ),
                    StatPeriod.ALL.ordinal to stringResource(R.string.filter_all),
                )
            }
        }
    val periodLabel = periodChips.firstOrNull { it.first == indexChips }?.second?.lowercase().orEmpty()

    val playStatsSong: (SongWithStats) -> Unit = { song ->
        if (song.id == mediaMetadata?.id) {
            playerConnection.player.togglePlayPause()
        } else {
            val matchedSong = mostPlayedSongs.firstOrNull { it.id == song.id }
            val preloadItem = matchedSong?.toMediaMetadata() ?: MediaMetadata(
                id = song.id,
                title = song.title,
                artists = emptyList(),
                duration = (song.timeListened?.toInt() ?: -1),
                thumbnailUrl = song.thumbnailUrl
            )
            playerConnection.playQueue(
                YouTubeQueue(
                    endpoint = WatchEndpoint(song.id),
                    preloadItem = preloadItem,
                ),
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                .asPaddingValues(),
            modifier = Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)
            )
        ) {
            item {
                ChoiceChipsRow(
                    chips = periodChips,
                    options =
                    listOf(
                        OptionStats.CONTINUOUS to stringResource(id = R.string.continuous),
                        OptionStats.WEEKS to stringResource(R.string.weeks),
                        OptionStats.MONTHS to stringResource(R.string.months),
                        OptionStats.YEARS to stringResource(R.string.years),
                    ),
                    selectedOption = selectedOption,
                    onSelectionChange = {
                        viewModel.selectedOption.value = it
                        viewModel.indexChips.value = 0
                    },
                    currentValue = indexChips,
                    onValueUpdate = { viewModel.indexChips.value = it },
                )
            }

            item(key = "listening") {
                StatsListeningCard(
                    periodLabel = periodLabel,
                    listening = listening,
                    summary = "${mostPlayedSongsStats.size} canciones, ${mostPlayedArtists.size} artistas",
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .animateItem(),
                )
            }

            item(key = "favorites") {
                val topSong = mostPlayedSongsStats.firstOrNull()
                val topArtist = mostPlayedArtists.firstOrNull()
                StatsFavoritesRow(
                    songTitle = topSong?.title,
                    songSubtitle = topSong?.let {
                        pluralStringResource(R.plurals.n_time, it.songCountListened, it.songCountListened)
                    },
                    songThumbnail = topSong?.thumbnailUrl,
                    onSongClick = { topSong?.let(playStatsSong) },
                    artistName = topArtist?.artist?.name,
                    artistSubtitle = topArtist?.let { makeTimeString(it.timeListened?.toLong()) },
                    artistThumbnail = topArtist?.artist?.thumbnailUrl,
                    onArtistClick = { topArtist?.let { navController.navigate("artist/${it.id}") } },
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .animateItem(),
                )
            }

            item(key = "mostPlayedSongsHeader") {
                NavigationTitle(
                    title = "Canciones más escuchadas",
                    modifier = Modifier.animateItem(),
                )
            }

            itemsIndexed(
                items = if (showAllSongs) mostPlayedSongsStats else mostPlayedSongsStats.take(10),
                key = { _, song -> song.id },
            ) { index, song ->
                ListItem(
                    title = song.title,
                    subtitle = joinByBullet(
                        pluralStringResource(
                            R.plurals.n_time,
                            song.songCountListened,
                            song.songCountListened,
                        ),
                        makeTimeString(song.timeListened),
                    ),
                    thumbnailContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatsRankNumber(rank = index + 1)
                            ItemThumbnail(
                                thumbnailUrl = song.thumbnailUrl,
                                isActive = song.id == mediaMetadata?.id,
                                isPlaying = isPlaying,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { playStatsSong(song) },
                            onLongClick = {
                                val originalSong = mostPlayedSongs.getOrNull(index)
                                    ?.takeIf { it.id == song.id }
                                    ?: mostPlayedSongs.firstOrNull { it.id == song.id }
                                if (originalSong != null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuState.show {
                                        SongMenu(
                                            originalSong = originalSong,
                                            navController = navController,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }
                                }
                            },
                        )
                        .animateItem()
                )
            }

            if (mostPlayedSongsStats.size > 10) {
                item(key = "songsToggle") {
                    TextButton(
                        onClick = { showAllSongs = !showAllSongs },
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .animateItem(),
                    ) {
                        Text(if (showAllSongs) "Ver menos" else "Ver todo (${mostPlayedSongsStats.size})")
                    }
                }
            }

            item(key = "mostPlayedArtists") {
                NavigationTitle(
                    title = "Artistas más escuchados",
                    modifier = Modifier.animateItem(),
                )
            }

            itemsIndexed(
                items = mostPlayedArtists.chunked(2),
                key = { _, rowArtists -> rowArtists.first().id },
            ) { _, rowArtists ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowArtists.forEach { artist ->
                        LocalArtistsGrid(
                            title = artist.artist.name,
                            subtitle = joinByBullet(
                                pluralStringResource(
                                    R.plurals.n_time,
                                    artist.songCount,
                                    artist.songCount
                                ),
                                makeTimeString(artist.timeListened?.toLong()),
                            ),
                            thumbnailUrl = artist.artist.thumbnailUrl,
                            modifier = Modifier
                                .weight(1f)
                                .combinedClickable(
                                    onClick = {
                                        navController.navigate("artist/${artist.id}")
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        menuState.show {
                                            ArtistMenu(
                                                originalArtist = artist,
                                                coroutineScope = coroutineScope,
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    },
                                )
                                .animateItem(),
                        )
                    }
                    // Add spacers for incomplete rows if needed to maintain grid alignment
                    repeat(2 - rowArtists.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            item(key = "mostPlayedAlbums") {
                NavigationTitle(
                    title = "Álbumes más escuchados",
                    modifier = Modifier.animateItem(),
                )

                if (mostPlayedAlbums.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier,
                    ) {
                        itemsIndexed(
                            items = mostPlayedAlbums,
                            key = { _, album -> album.id },
                        ) { index, album ->
                            LocalAlbumsGrid(
                                title = "${index + 1}. ${album.album.title}",
                                subtitle =
                                joinByBullet(
                                    pluralStringResource(
                                        R.plurals.n_time,
                                        album.songCountListened ?: 0,
                                        album.songCountListened ?: 0,
                                    ),
                                    makeTimeString(album.timeListened?.toLong()),
                                ),
                                thumbnailUrl = album.album.thumbnailUrl,
                                isActive = album.id == mediaMetadata?.album?.id,
                                isPlaying = isPlaying,
                                modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            navController.navigate("album/${album.id}")
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuState.show {
                                                AlbumMenu(
                                                    originalAlbum = album,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                    )
                                    .animateItem(),
                            )
                        }
                    }
                }
            }


        }

        // FAB to shuffle most played songs
        if (mostPlayedSongs.isNotEmpty()) {
            HideOnScrollFAB(
                visible = true,
                lazyListState = lazyListState,
                icon = R.drawable.shuffle,
                onClick = {
                    playerConnection.playQueue(
                        ListQueue(
                            title = context.getString(R.string.most_played_songs),
                            items = mostPlayedSongs.map { it.toMediaMetadata().toMediaItem() }.shuffled()
                        )
                    )
                }
            )
        }

        TopAppBar(
            title = { Text(stringResource(R.string.stats)) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = { navController.navigate("year_in_music") },
                    onLongClick = { }
                ) {
                    Icon(
                        painterResource(R.drawable.auto_awesome),
                        contentDescription = stringResource(R.string.year_in_music),
                    )
                }
            },
        )
    }
}

@Composable
fun ArtistPieChart(
    artists: List<Artist>,
    modifier: Modifier = Modifier
) {
    val totalTime = artists.sumOf { it.timeListened?.toLong() ?: 0L }
    if (totalTime == 0L) return

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Pie Chart
        Box(
            modifier = Modifier.size(160.dp),
            contentAlignment = Alignment.Center
        ) {
            var startAngle = -90f

            artists.forEach { artist ->
                val time = artist.timeListened?.toLong() ?: 0L
                val sweepAngle = (time.toFloat() / totalTime) * 360f
                
                // Only draw significant slices
                if (sweepAngle > 1f) {
                    AsyncImage(
                        model = artist.artist.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(PieSliceShape(startAngle, sweepAngle))
                    )
                    startAngle += sweepAngle
                }
            }
            
            // Optional: Inner circle for donut effect or just overlay style
            // For now, keeping it as a full pie chart of images as requested
        }

        // Text Info
        Column {
            Text(
                text = "Total Time Listened",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = makeTimeString(totalTime),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

fun PieSliceShape(startAngle: Float, sweepAngle: Float): GenericShape {
    return GenericShape { size, _ ->
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.width / 2
        
        moveTo(center.x, center.y)
        
        // Arc start
        val startRad = Math.toRadians(startAngle.toDouble())
        val endRad = Math.toRadians((startAngle + sweepAngle).toDouble())
        
        lineTo(
            (center.x + radius * cos(startRad)).toFloat(),
            (center.y + radius * sin(startRad)).toFloat()
        )
        
        // Add arc
        arcTo(
            rect = androidx.compose.ui.geometry.Rect(
                center = center,
                radius = radius
            ),
            startAngleDegrees = startAngle,
            sweepAngleDegrees = sweepAngle,
            forceMoveTo = false
        )
        
        lineTo(center.x, center.y)
        close()
    }
}

@Composable
fun StatsHighlightsSection(
    topArtist: Artist?,
    topSong: SongWithStats?,
    topSongEntity: Song?,
    navController: NavController
) {
    if (topArtist == null && topSong == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (topArtist != null) {
            StatsHighlightCard(
                title = "Your Favourite Artist",
                mainText = topArtist.artist.name,
                subText = "${topArtist.songCount} songs played • ${makeTimeString(topArtist.timeListened?.toLong())}",
                imageUrl = topArtist.artist.thumbnailUrl,
                onClick = { navController.navigate("artist/${topArtist.id}") }
            )
        }

        if (topSong != null && topSongEntity != null) {
            StatsHighlightCard(
                title = "Your Favourite Song",
                mainText = topSong.title,
                subText = "${topSong.songCountListened} plays • ${makeTimeString(topSong.timeListened)}",
                imageUrl = topSong.thumbnailUrl,
                onClick = { /* Navigate to player or something? For now just no-op or maybe play? */ }
            )
        }
    }
}

@Composable
fun StatsHighlightCard(
    title: String,
    mainText: String,
    subText: String,
    imageUrl: String?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
            )

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = mainText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

enum class OptionStats { WEEKS, MONTHS, YEARS, CONTINUOUS }
