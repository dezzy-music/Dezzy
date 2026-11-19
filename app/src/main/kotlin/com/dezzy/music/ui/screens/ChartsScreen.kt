/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.dezzy.music.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dezzy.music.R
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.constants.ListItemHeight
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.models.toMediaMetadata
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.ui.component.LocalMenuState
import com.dezzy.music.ui.component.IconButton
import com.dezzy.music.ui.component.NavigationTitle
import com.dezzy.music.ui.component.YouTubeGridItem
import com.dezzy.music.ui.component.YouTubeListItem
import com.dezzy.music.ui.component.shimmer.GridItemPlaceHolder
import com.dezzy.music.ui.component.shimmer.ShimmerHost
import com.dezzy.music.ui.component.shimmer.TextPlaceholder
import com.dezzy.music.ui.menu.YouTubeSongMenu
import com.dezzy.music.ui.utils.backToMain
import com.dezzy.music.ui.utils.SnapLayoutInfoProvider
import com.dezzy.music.viewmodels.ChartsViewModel
import com.dezzy.music.LocalDatabase
import com.dezzy.music.ui.screens.musicmap.ConnectSpotifyCard
import com.dezzy.music.ui.screens.musicmap.HistoryPeakBadge
import com.dezzy.music.ui.screens.musicmap.RankingSongRow
import com.dezzy.music.ui.screens.musicmap.RankingsSearchField
import com.dezzy.music.ui.screens.musicmap.RankingsSectionTitle
import com.dezzy.music.ui.screens.musicmap.historySubtitle
import com.dezzy.music.ui.screens.musicmap.rememberCatalogSearch
import com.dezzy.music.ui.screens.musicmap.rememberHistoryRankings
import com.dezzy.music.ui.screens.musicmap.songStatsRoute

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChartsScreen(
    navController: NavController,
    scrollToVideos: Boolean = false,
    viewModel: ChartsViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val chartsPage by viewModel.chartsPage.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val lazyListState = rememberLazyListState()

    // Acceso «Videos» de la pestaña Buscar: «Top music videos» son los dos últimos ítems.
    var scrolledToVideos by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(chartsPage, isLoading) {
        if (!scrollToVideos || scrolledToVideos || isLoading) return@LaunchedEffect
        if (chartsPage?.sections?.any { it.title == "Top music videos" } != true) return@LaunchedEffect
        val total = snapshotFlow { lazyListState.layoutInfo.totalItemsCount }.first { it >= 2 }
        lazyListState.animateScrollToItem(total - 2)
        scrolledToVideos = true
    }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (chartsPage == null) {
            viewModel.loadCharts()
        }
    }

    // Buscador de cualquier canción y tus canciones más escuchadas con su mejor puesto en las listas.
    var songQuery by rememberSaveable { mutableStateOf("") }
    val catalog = rememberCatalogSearch(songQuery)
    val database = LocalDatabase.current
    val topSongs by remember(database) { database.mostPlayedSongs(fromTimeStamp = 0L, limit = 10) }
        .collectAsState(initial = emptyList())
    val history = rememberHistoryRankings(topSongs)
    fun openStats(title: String, artist: String, thumbnail: String?, videoId: String, artistId: String?) {
        navController.navigate(songStatsRoute(title, artist, thumbnail, videoId, artistId))
    }
    fun playVideo(id: String, preload: com.dezzy.music.models.MediaMetadata?) {
        if (id == mediaMetadata?.id) {
            playerConnection.player.togglePlayPause()
        } else {
            playerConnection.playQueue(YouTubeQueue(endpoint = WatchEndpoint(videoId = id), preloadItem = preload))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.charts)) },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.navigateUp() },
                        onLongClick = { navController.backToMain() }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
                    }
                },
            )
        }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (isLoading || chartsPage == null) {
                ShimmerHost(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        TextPlaceholder(
                            height = 36.dp,
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(0.5f),
                        )
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val horizontalLazyGridItemWidthFactor = if (maxWidth * 0.475f >= 320.dp) 0.475f else 0.9f
                            val horizontalLazyGridItemWidth = maxWidth * horizontalLazyGridItemWidthFactor

                            LazyHorizontalGrid(
                                rows = GridCells.Fixed(4),
                                contentPadding = PaddingValues(start = 4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(ListItemHeight * 4),
                            ) {
                                items(4) {
                                    Row(
                                        modifier = Modifier
                                            .width(horizontalLazyGridItemWidth)
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(ListItemHeight - 16.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(MaterialTheme.colorScheme.onSurface),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(
                                            modifier = Modifier.fillMaxHeight(),
                                            verticalArrangement = Arrangement.Center,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .height(16.dp)
                                                    .width(120.dp)
                                                    .background(MaterialTheme.colorScheme.onSurface),
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Box(
                                                modifier = Modifier
                                                    .height(12.dp)
                                                    .width(80.dp)
                                                    .background(MaterialTheme.colorScheme.onSurface),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        TextPlaceholder(
                            height = 36.dp,
                            modifier = Modifier
                                .padding(vertical = 12.dp, horizontal = 12.dp)
                                .width(250.dp),
                        )
                        Row {
                            repeat(2) {
                                GridItemPlaceHolder()
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = LocalPlayerAwareWindowInsets.current
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                        .asPaddingValues(),
                ) {
                    item(key = "rk_search") {
                        RankingsSearchField(songQuery, onQueryChange = { songQuery = it })
                    }
                    if (songQuery.isNotBlank()) {
                        if (catalog.loading) {
                            item(key = "rk_loading") {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        } else if (catalog.songs.isEmpty() && catalog.query.length >= 2) {
                            item(key = "rk_empty") {
                                Text(
                                    "No encontré canciones para «${catalog.query}».",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                )
                            }
                        }
                        items(catalog.songs, key = { "rk_c_${it.id}" }) { song ->
                            val artist = song.artists.joinToString { it.name }
                            RankingSongRow(
                                title = song.title,
                                subtitle = artist,
                                thumbnail = song.thumbnail,
                                isActive = song.id == mediaMetadata?.id,
                                onPlay = { playVideo(song.id, song.toMediaMetadata()) },
                                onStats = { openStats(song.title, artist, song.thumbnail, song.id, song.artists.firstOrNull()?.id) },
                            )
                        }
                    } else {
                    if (topSongs.isNotEmpty()) {
                        item(key = "rk_hist_title") {
                            RankingsSectionTitle(
                                "Tus canciones en los rankings",
                                "Las que más escuchas y su mejor puesto en las listas de Spotify",
                            )
                        }
                        if (history.hasSpotify == false) {
                            item(key = "rk_connect") {
                                ConnectSpotifyCard(onConnect = { navController.navigate("settings/spotify/login") })
                            }
                        }
                        itemsIndexed(topSongs, key = { _, s -> "rk_h_${s.song.id}" }) { index, song ->
                            val peak = history.peaks[song.song.id]
                            val artist = song.artists.joinToString { it.name }
                            RankingSongRow(
                                title = song.song.title,
                                subtitle = historySubtitle(song, peak),
                                thumbnail = song.song.thumbnailUrl,
                                isActive = song.song.id == mediaMetadata?.id,
                                onPlay = { playVideo(song.song.id, song.toMediaMetadata()) },
                                onStats = { openStats(song.song.title, artist, song.song.thumbnailUrl, song.song.id, song.artists.firstOrNull()?.id) },
                                rank = index + 1,
                                trailing = { HistoryPeakBadge(peak) },
                            )
                        }
                    }
                    chartsPage?.sections?.filter { it.title != "Top music videos" }?.forEach { section ->
                        item {
                            NavigationTitle(
                                title = when (section.title) {
                                    "Trending" -> stringResource(R.string.trending)
                                    else -> section.title ?: stringResource(R.string.charts)
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                        item {
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                val horizontalLazyGridItemWidthFactor = if (maxWidth * 0.475f >= 320.dp) 0.475f else 0.9f
                                val horizontalLazyGridItemWidth = maxWidth * horizontalLazyGridItemWidthFactor

                                val lazyGridState = rememberLazyGridState()
                                val snapLayoutInfoProvider = remember(lazyGridState) {
                                    SnapLayoutInfoProvider(
                                        lazyGridState = lazyGridState,
                                        positionInLayout = { layoutSize, itemSize ->
                                            (layoutSize * horizontalLazyGridItemWidthFactor / 2f - itemSize / 2f)
                                        },
                                    )
                                }

                                LazyHorizontalGrid(
                                    state = lazyGridState,
                                    rows = GridCells.Fixed(4),
                                    flingBehavior = rememberSnapFlingBehavior(snapLayoutInfoProvider),
                                    contentPadding = WindowInsets.systemBars
                                        .only(WindowInsetsSides.Horizontal)
                                        .asPaddingValues(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(ListItemHeight * 4)
                                        .animateItem(),
                                ) {
                                    items(
                                        items = section.items.filterIsInstance<SongItem>().distinctBy { it.id },
                                        key = { it.id },
                                    ) { song ->
                                        YouTubeListItem(
                                            item = song,
                                            isActive = song.id == mediaMetadata?.id,
                                            isPlaying = isPlaying,
                                            isSwipeable = false,
                                            trailingContent = {
                                                IconButton(
                                                    onClick = {
                                                        menuState.show {
                                                            YouTubeSongMenu(
                                                                song = song,
                                                                navController = navController,
                                                                onDismiss = menuState::dismiss,
                                                            )
                                                        }
                                                    },
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.more_vert),
                                                        contentDescription = null,
                                                    )
                                                }
                                            },
                                            modifier = Modifier
                                                .width(horizontalLazyGridItemWidth)
                                                .combinedClickable(
                                                    onClick = {
                                                        if (song.id == mediaMetadata?.id) {
                                                            playerConnection.player.togglePlayPause()
                                                        } else {
                                                            playerConnection.playQueue(
                                                                YouTubeQueue(
                                                                    endpoint = WatchEndpoint(videoId = song.id),
                                                                    preloadItem = song.toMediaMetadata(),
                                                                ),
                                                            )
                                                        }
                                                    },
                                                    onLongClick = {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        menuState.show {
                                                            YouTubeSongMenu(
                                                                song = song,
                                                                navController = navController,
                                                                onDismiss = menuState::dismiss,
                                                            )
                                                        }
                                                    },
                                                ),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    chartsPage?.sections?.find { it.title == "Top music videos" }?.let { topVideosSection ->
                        item {
                            NavigationTitle(
                                title = stringResource(R.string.top_music_videos),
                                modifier = Modifier.animateItem(),
                            )
                        }
                        item {
                            LazyRow(
                                contentPadding = WindowInsets.systemBars
                                    .only(WindowInsetsSides.Horizontal)
                                    .asPaddingValues(),
                                modifier = Modifier.animateItem(),
                            ) {
                                items(
                                    items = topVideosSection.items.filterIsInstance<SongItem>().distinctBy { it.id },
                                    key = { it.id },
                                ) { video ->
                                    YouTubeGridItem(
                                        item = video,
                                        isActive = video.id == mediaMetadata?.id,
                                        isPlaying = isPlaying,
                                        coroutineScope = coroutineScope,
                                        modifier = Modifier
                                            .combinedClickable(
                                                onClick = {
                                                    if (video.id == mediaMetadata?.id) {
                                                        playerConnection.player.togglePlayPause()
                                                    } else {
                                                        playerConnection.playQueue(
                                                            YouTubeQueue(
                                                                endpoint = WatchEndpoint(videoId = video.id),
                                                                preloadItem = video.toMediaMetadata(),
                                                            ),
                                                        )
                                                    }
                                                },
                                                onLongClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    menuState.show {
                                                        YouTubeSongMenu(
                                                            song = video,
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
                }
            }
        }
    }
}
