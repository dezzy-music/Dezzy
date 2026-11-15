/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.dezzy.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.Text
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.models.toMediaMetadata
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material3.MaterialTheme
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dezzy.music.innertube.utils.parseCookieString
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.constants.InnerTubeCookieKey
import com.dezzy.music.security.SessionCrypto
import com.dezzy.music.constants.DisableBlurKey
import com.dezzy.music.constants.ShowHomeCategoryChipsKey
import com.dezzy.music.ui.component.ChipsRow
import com.dezzy.music.ui.component.LocalBottomSheetPageState
import com.dezzy.music.ui.component.LocalMenuState
import com.dezzy.music.ui.component.NavigationTitle
import com.dezzy.music.ui.component.SpotifyTrackShelf
import com.dezzy.music.ui.component.rememberSpotifyTrackPlayer
import com.dezzy.music.ui.utils.SnapLayoutInfoProvider
import com.dezzy.music.utils.rememberPreference
import com.dezzy.music.viewmodels.HomeViewModel
import androidx.datastore.preferences.core.edit
import com.dezzy.music.utils.dataStore
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val bottomSheetPageState = LocalBottomSheetPageState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val haptic = LocalHapticFeedback.current

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val quickPicksState by viewModel.quickPicks.collectAsState()
    val speedDialSongs by viewModel.speedDialSongs.collectAsState()
    val quickPicks = quickPicksState?.takeIf { it.isNotEmpty() } ?: speedDialSongs
    val recentPlaylists by viewModel.recentPlaylistsDb.collectAsState()
    val pinnedPlaylist1 by viewModel.pinnedPlaylist1.collectAsState()
    val pinnedPlaylist2 by viewModel.pinnedPlaylist2.collectAsState()
    var showPinnedPlaylistSelector by remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    // Boceto A: chips de filtro, hoja "Más de Dezzy" y última canción de "Sorpréndeme"
    var homeFilter by rememberSaveable { mutableStateOf(HomeFilter.ForYou) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var surprisePick by remember { mutableStateOf<com.dezzy.music.db.entities.Song?>(null) }
    val forgottenFavorites by viewModel.forgottenFavorites.collectAsState()
    val keepListening by viewModel.keepListening.collectAsState()
    val homePage by viewModel.homePage.collectAsState()
    val spotifyTopTracks by viewModel.spotifyTopTracks.collectAsState()
    val spotifyRecentLikes by viewModel.spotifyRecentLikes.collectAsState()
    val spotifyHomeOnly by viewModel.spotifyHomeOnly.collectAsState()
    val spotifyPlayer = rememberSpotifyTrackPlayer()
    val allItemsMetadata by viewModel.allItemsMetadata.collectAsState()

    val selectedChip by viewModel.selectedChip.collectAsState()

    val lastListenEvent by viewModel.lastListenEvent.collectAsState()
    val listenStreakDays by viewModel.listenStreakDays.collectAsState()
    val todayListenMinutes by viewModel.todayListenMinutes.collectAsState()
    val genreMixes by viewModel.genreMixes.collectAsState()

    val isLoading: Boolean by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val pullRefreshState = rememberPullToRefreshState()

    val forgottenFavoritesLazyGridState = rememberLazyGridState()

    val accountName by viewModel.accountName.collectAsState()
    val accountImageUrl by viewModel.accountImageUrl.collectAsState()
    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    val (disableBlur) = rememberPreference(DisableBlurKey, false)
    val (showHomeCategoryChips) = rememberPreference(ShowHomeCategoryChipsKey, true)
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(SessionCrypto.decrypt(innerTubeCookie))
    }
    val url = if (isLoggedIn) accountImageUrl else null

    // Filtros disponibles: "Música" sólo si no está activo "Spotify exclusivo de
    // inicio"; "Spotify" sólo si hay estanterías de Spotify que mostrar.
    val hasSpotifyShelves = spotifyTopTracks.isNotEmpty() || spotifyRecentLikes.isNotEmpty()
    val homeFilters = remember(hasSpotifyShelves, spotifyHomeOnly) {
        HomeFilter.entries.filter { filter ->
            when (filter) {
                HomeFilter.Music -> !spotifyHomeOnly
                HomeFilter.Spotify -> hasSpotifyShelves
                else -> true
            }
        }
    }
    LaunchedEffect(homeFilters) {
        if (homeFilter !in homeFilters) homeFilter = HomeFilter.ForYou
    }

    val scope = rememberCoroutineScope()
    val lazylistState = rememberLazyListState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop =
        backStackEntry?.savedStateHandle?.getStateFlow("scrollToTop", false)?.collectAsState()

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            lazylistState.animateScrollToItem(0)
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { lazylistState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                val len = lazylistState.layoutInfo.totalItemsCount
                if (homeFilter == HomeFilter.Music && lastVisibleIndex != null && lastVisibleIndex >= len - 3) {
                    viewModel.loadMoreYouTubeItems(homePage?.continuation)
                }
            }
    }

    if (selectedChip != null) {
        BackHandler {
            // if a chip is selected, go back to the normal homepage first
            viewModel.toggleChip(selectedChip)
        }
    }

    LaunchedEffect(showHomeCategoryChips, selectedChip) {
        if (!showHomeCategoryChips && selectedChip != null) {
            viewModel.toggleChip(selectedChip)
        }
    }

    LaunchedEffect(forgottenFavorites) {
        forgottenFavoritesLazyGridState.scrollToItem(0)
    }

    val (enableWallpaperTheme) = rememberPreference(com.dezzy.music.constants.EnableWallpaperThemeKey, defaultValue = false)
    val (enableVideoWallpaper) = rememberPreference(com.dezzy.music.constants.EnableVideoWallpaperKey, defaultValue = false)
    val isWallpaperActive = enableWallpaperTheme || enableVideoWallpaper

    val (homeInactivityFade) = rememberPreference(com.dezzy.music.constants.HomeInactivityFadeKey, defaultValue = true)
    val contentAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
    var lastInteractionTime by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }

    val resetInactivity = {
        lastInteractionTime = System.currentTimeMillis()
    }

    LaunchedEffect(isWallpaperActive, homeInactivityFade, lastInteractionTime) {
        if (isWallpaperActive && homeInactivityFade) {
            contentAlpha.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(300))
            delay(120_000L) // 2 minutes inactivity
            contentAlpha.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(1000))
        } else {
            contentAlpha.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(300))
        }
    }

    // Capture M3 Expressive colors from theme outside drawBehind
    val color1 = MaterialTheme.colorScheme.primary
    val color2 = MaterialTheme.colorScheme.secondary
    val color3 = MaterialTheme.colorScheme.tertiary
    val color4 = MaterialTheme.colorScheme.primaryContainer
    val color5 = MaterialTheme.colorScheme.secondaryContainer
    val surfaceColor = MaterialTheme.colorScheme.surface
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        resetInactivity()
                    }
                }
            }
    ) {
        // M3E Mesh gradient background layer at the top
        if (!disableBlur && !isWallpaperActive) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxSize(0.7f) // Cover top 70% of screen
                    .align(Alignment.TopCenter)
                    .zIndex(-1f) // Place behind all content
                    .drawWithCache {
                        val width = this.size.width
                        val height = this.size.height

                        // Create mesh gradient with 5 color blobs for more variation
                        // First color blob - top left
                        val brush1 = Brush.radialGradient(
                            colors = listOf(
                                color1.copy(alpha = 0.38f),
                                color1.copy(alpha = 0.24f),
                                color1.copy(alpha = 0.14f),
                                color1.copy(alpha = 0.06f),
                                Color.Transparent
                            ),
                            center = Offset(width * 0.15f, height * 0.1f),
                            radius = width * 0.55f
                        )

                        // Second color blob - top right
                        val brush2 = Brush.radialGradient(
                            colors = listOf(
                                color2.copy(alpha = 0.34f),
                                color2.copy(alpha = 0.2f),
                                color2.copy(alpha = 0.11f),
                                color2.copy(alpha = 0.05f),
                                Color.Transparent
                            ),
                            center = Offset(width * 0.85f, height * 0.2f),
                            radius = width * 0.65f
                        )

                        // Third color blob - middle left
                        val brush3 = Brush.radialGradient(
                            colors = listOf(
                                color3.copy(alpha = 0.3f),
                                color3.copy(alpha = 0.17f),
                                color3.copy(alpha = 0.09f),
                                color3.copy(alpha = 0.04f),
                                Color.Transparent
                            ),
                            center = Offset(width * 0.3f, height * 0.45f),
                            radius = width * 0.6f
                        )

                        // Fourth color blob - middle right
                        val brush4 = Brush.radialGradient(
                            colors = listOf(
                                color4.copy(alpha = 0.26f),
                                color4.copy(alpha = 0.14f),
                                color4.copy(alpha = 0.08f),
                                color4.copy(alpha = 0.03f),
                                Color.Transparent
                            ),
                            center = Offset(width * 0.7f, height * 0.5f),
                            radius = width * 0.7f
                        )

                        // Fifth color blob - bottom center (helps with smooth fade)
                        val brush5 = Brush.radialGradient(
                            colors = listOf(
                                color5.copy(alpha = 0.22f),
                                color5.copy(alpha = 0.12f),
                                color5.copy(alpha = 0.06f),
                                color5.copy(alpha = 0.02f),
                                Color.Transparent
                            ),
                            center = Offset(width * 0.5f, height * 0.75f),
                            radius = width * 0.8f
                        )

                        // Add a final vertical gradient overlay to ensure smooth bottom fade
                        val overlayBrush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Transparent,
                                surfaceColor.copy(alpha = 0.22f),
                                surfaceColor.copy(alpha = 0.55f),
                                surfaceColor
                            ),
                            startY = height * 0.4f,
                            endY = height
                        )

                        onDrawBehind {
                            drawRect(brush = brush1)
                            drawRect(brush = brush2)
                            drawRect(brush = brush3)
                            drawRect(brush = brush4)
                            drawRect(brush = brush5)
                            drawRect(brush = overlayBrush)
                        }
                    }
            ) {}
        }
        
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha.value }
                .pullToRefresh(
                    state = pullRefreshState,
                    isRefreshing = isRefreshing,
                    onRefresh = viewModel::refresh
                )
        ) {
            val horizontalLazyGridItemWidthFactor = if (maxWidth * 0.475f >= 320.dp) 0.475f else 0.9f
            val horizontalLazyGridItemWidth = maxWidth * horizontalLazyGridItemWidthFactor
            val forgottenFavoritesSnapLayoutInfoProvider = remember(forgottenFavoritesLazyGridState) {
                SnapLayoutInfoProvider(
                    lazyGridState = forgottenFavoritesLazyGridState,
                    positionInLayout = { layoutSize, itemSize ->
                        (layoutSize * horizontalLazyGridItemWidthFactor / 2f - itemSize / 2f)
                    }
                )
            }

            val playerInsets = LocalPlayerAwareWindowInsets.current
            val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val bottomPadding = playerInsets.asPaddingValues().calculateBottomPadding()

            LazyColumn(
                state = lazylistState,
                contentPadding = PaddingValues(
                    top = statusBarPadding + 8.dp,
                    bottom = bottomPadding
                )
            ) {
                // ── Barra superior: saludo, racha, novedades y cuenta ──
                item(key = "home_top_bar") {
                    DezzyHomeTopBar(
                        streakDays = listenStreakDays,
                        todayMinutes = todayListenMinutes,
                        accountImageUrl = url,
                        onNotificationsClick = { navController.navigate("new_release") },
                        onAccountClick = { navController.navigate("settings/accounts") },
                    )
                }

                // ── Chips de filtro: reparten las secciones ──
                item(key = "home_filters") {
                    HomeFilterChips(
                        filters = homeFilters,
                        selected = homeFilter,
                        onSelect = { homeFilter = it },
                    )
                }

                when (homeFilter) {
                    HomeFilter.ForYou -> {
                        // ── Accesos rápidos 2 × 3 ──
                        item(key = "home_shortcuts") {
                            val context = LocalContext.current
                            SpotifyGreetingGrid(
                                quickPicks = quickPicks,
                                fallbackSongs = forgottenFavorites,
                                pinnedPlaylist1 = pinnedPlaylist1,
                                pinnedPlaylist2 = pinnedPlaylist2,
                                onAddPlaylistClick = { slotIndex ->
                                    showPinnedPlaylistSelector = slotIndex
                                },
                                onRemovePlaylistClick = { slotIndex ->
                                    scope.launch {
                                        val key = if (slotIndex == 1) com.dezzy.music.constants.PinnedPlaylist1Key else com.dezzy.music.constants.PinnedPlaylist2Key
                                        context.dataStore.edit { it.remove(key) }
                                    }
                                },
                                playerConnection = playerConnection,
                                navController = navController,
                                accountImageUrl = url,
                                onAccountClick = { navController.navigate("settings/accounts") }
                            )
                        }

                        // ── Sigue donde ibas ──
                        item(key = "home_resume") {
                            DezzyHomeHero(
                                accountName = accountName,
                                streakDays = listenStreakDays,
                                todayMinutes = todayListenMinutes,
                                lastEvent = lastListenEvent,
                                genreMixes = genreMixes,
                                quickPicks = quickPicks,
                                navController = navController,
                            )
                        }

                        quickPicks?.takeIf { it.isNotEmpty() }?.let { picks ->
                            item(key = "quick_picks_title") {
                                HomeSectionHeader(
                                    title = stringResource(R.string.quick_picks),
                                    modifier = Modifier.animateItem()
                                )
                            }

                            item(key = "quick_picks") {
                                QuickPicksSection(
                                    quickPicks = picks,
                                    mediaMetadata = mediaMetadata,
                                    isPlaying = isPlaying,
                                    navController = navController,
                                    playerConnection = playerConnection,
                                    menuState = menuState,
                                    haptic = haptic,
                                    metadataMap = allItemsMetadata
                                )
                            }
                        }

                        // ── Herramientas Dezzy (2 × 2) + "Más" ──
                        item(key = "dezzy_tools") {
                            DezzyToolsSection(
                                surpriseSubtitle = surprisePick?.let { "Te tocó: ${it.song.title}" }
                                    ?: "Una canción al azar de tu música",
                                onSurprise = {
                                    quickPicks?.randomOrNull()?.let { pick ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        surprisePick = pick
                                        playerConnection.playQueue(YouTubeQueue.radio(pick.toMediaMetadata()))
                                    }
                                },
                                onStats = { navController.navigate("stats") },
                                onMusicMap = { navController.navigate("music_map") },
                                onYearInMusic = { navController.navigate("year_in_music") },
                                onMore = { navController.navigate("experiences") },
                            )
                        }

                        // ── Tus mixes ──
                        if (genreMixes.isNotEmpty()) {
                            item(key = "home_mixes") {
                                DezzyMixesCarousel(genreMixes = genreMixes)
                            }
                        }

                        speedDialSongs.takeIf { it.isNotEmpty() }?.let { songs ->
                            item(key = "speed_dial_title") {
                                HomeSectionHeader(
                                    title = stringResource(R.string.speed_dial),
                                    modifier = Modifier.animateItem()
                                )
                            }

                            item(key = "speed_dial") {
                                SpeedDialSection(
                                    speedDialSongs = songs,
                                    mediaMetadata = mediaMetadata,
                                    isPlaying = isPlaying,
                                    navController = navController,
                                    playerConnection = playerConnection,
                                    menuState = menuState,
                                    haptic = haptic,
                                    metadataMap = allItemsMetadata
                                )
                            }
                        }

                        keepListening?.takeIf { it.isNotEmpty() }?.let { items ->
                            item(key = "keep_listening_title") {
                                HomeSectionHeader(
                                    title = stringResource(R.string.keep_listening),
                                    modifier = Modifier.animateItem()
                                )
                            }

                            item(key = "keep_listening") {
                                KeepListeningSection(
                                    keepListening = items,
                                    mediaMetadata = mediaMetadata,
                                    isPlaying = isPlaying,
                                    navController = navController,
                                    playerConnection = playerConnection,
                                    menuState = menuState,
                                    haptic = haptic,
                                    scope = scope,
                                    metadataMap = allItemsMetadata
                                )
                            }
                        }

                        AccountPlaylistsContainer(
                            viewModel = viewModel,
                            accountName = accountName,
                            accountImageUrl = url,
                            mediaMetadata = mediaMetadata,
                            isPlaying = isPlaying,
                            navController = navController,
                            playerConnection = playerConnection,
                            menuState = menuState,
                            haptic = haptic,
                            scope = scope
                        )

                        forgottenFavorites?.takeIf { it.isNotEmpty() }?.let { favorites ->
                            item(key = "forgotten_favorites_title") {
                                HomeSectionHeader(
                                    title = stringResource(R.string.forgotten_favorites),
                                    modifier = Modifier.animateItem()
                                )
                            }

                            item(key = "forgotten_favorites") {
                                ForgottenFavoritesSection(
                                    forgottenFavorites = favorites,
                                    mediaMetadata = mediaMetadata,
                                    isPlaying = isPlaying,
                                    horizontalLazyGridItemWidth = horizontalLazyGridItemWidth,
                                    lazyGridState = forgottenFavoritesLazyGridState,
                                    snapLayoutInfoProvider = forgottenFavoritesSnapLayoutInfoProvider,
                                    navController = navController,
                                    playerConnection = playerConnection,
                                    menuState = menuState,
                                    haptic = haptic,
                                    metadataMap = allItemsMetadata
                                )
                            }
                        }

                        SimilarRecommendationsContainer(
                            viewModel = viewModel,
                            mediaMetadata = mediaMetadata,
                            isPlaying = isPlaying,
                            navController = navController,
                            playerConnection = playerConnection,
                            menuState = menuState,
                            haptic = haptic,
                            scope = scope
                        )

                        if (isLoading) {
                            item(key = "home_loading") {
                                HomeLoadingShimmer(modifier = Modifier.animateItem())
                            }
                        }
                    }

                    HomeFilter.Music -> {
                        // Secciones de YouTube Music ("Spotify exclusivo de inicio" oculta este filtro).
                        homePage?.sections?.takeUnless { spotifyHomeOnly }?.forEach { section ->
                            item {
                                HomePageSectionTitle(
                                    section = section,
                                    navController = navController,
                                    modifier = Modifier.animateItem()
                                )
                            }

                            item {
                                HomePageSectionContent(
                                    section = section,
                                    mediaMetadata = mediaMetadata,
                                    isPlaying = isPlaying,
                                    navController = navController,
                                    playerConnection = playerConnection,
                                    menuState = menuState,
                                    haptic = haptic,
                                    scope = scope,
                                    metadataMap = allItemsMetadata
                                )
                            }
                        }

                        if (isLoading || !spotifyHomeOnly && homePage?.continuation != null && homePage?.sections?.isNotEmpty() == true) {
                            item(key = "home_loading") {
                                HomeLoadingShimmer(modifier = Modifier.animateItem())
                            }
                        }
                    }

                    HomeFilter.Spotify -> {
                        // ── Spotify (Ajustes > Spotify > Usar para la pantalla de inicio) ──
                        if (spotifyTopTracks.isNotEmpty()) {
                            item(key = "spotify_top_tracks") {
                                SpotifyTrackShelf(
                                    title = "Tus favoritas en Spotify",
                                    tracks = spotifyTopTracks,
                                    player = spotifyPlayer,
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                        if (spotifyRecentLikes.isNotEmpty()) {
                            item(key = "spotify_recent_likes") {
                                SpotifyTrackShelf(
                                    title = "Tus Me gusta recientes",
                                    tracks = spotifyRecentLikes,
                                    player = spotifyPlayer,
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }

                    HomeFilter.Community -> {
                        item(key = "community_hint") {
                            Text(
                                text = "Las canciones más comentadas por la comunidad, según lo que tú escuchas.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        // ── Pistas más comentadas (sin sesión o sin datos no ocupa nada) ──
                        item(key = "top_commented") {
                            com.dezzy.music.ui.screens.social.TopCommentedTracksSection()
                        }
                    }
                }
            }

            Indicator(
                isRefreshing = isRefreshing,
                state = pullRefreshState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()),
            )
        }

        if (contentAlpha.value == 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) {
                        resetInactivity()
                    }
            )
        }

        showPinnedPlaylistSelector?.let { slotIndex ->
            val context = LocalContext.current
            com.dezzy.music.ui.menu.PinnedPlaylistDialog(
                onDismiss = { showPinnedPlaylistSelector = null },
                onPlaylistSelected = { playlist ->
                    scope.launch {
                        val key = if (slotIndex == 1) com.dezzy.music.constants.PinnedPlaylist1Key else com.dezzy.music.constants.PinnedPlaylist2Key
                        context.dataStore.edit { it[key] = playlist.id }
                    }
                }
            )
        }

        // ── Hoja "Más de Dezzy" (botón "Más" de Herramientas Dezzy) ──
        if (showMoreSheet) {
            SpotifyMenuDrawerSheet(
                navController = navController,
                accountImageUrl = url,
                onAccountClick = { navController.navigate("settings/accounts") },
                onDismiss = { showMoreSheet = false }
            )
        }

    }
}

