/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens
import com.dezzy.music.ui.screens.DownloadQueueScreen

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import com.dezzy.music.LocalPlayerConnection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.dezzy.music.R
import com.dezzy.music.constants.DarkModeKey
import com.dezzy.music.constants.PureBlackKey
import com.dezzy.music.ui.component.BottomSheet
import com.dezzy.music.ui.component.BottomSheetMenu
import com.dezzy.music.ui.component.LocalMenuState
import com.dezzy.music.ui.component.rememberBottomSheetState
import com.dezzy.music.ui.screens.BrowseScreen
import com.dezzy.music.ui.screens.artist.ArtistAlbumsScreen
import com.dezzy.music.ui.screens.artist.ArtistItemsScreen
import com.dezzy.music.ui.screens.artist.ArtistScreen
import com.dezzy.music.ui.screens.artist.ArtistSongsScreen
import com.dezzy.music.ui.screens.library.LibraryScreen
import com.dezzy.music.ui.screens.playlist.AutoPlaylistScreen
import com.dezzy.music.ui.screens.playlist.LocalPlaylistScreen
import com.dezzy.music.ui.screens.playlist.OnlinePlaylistScreen
import com.dezzy.music.ui.screens.playlist.TopPlaylistScreen
import com.dezzy.music.ui.screens.playlist.CachePlaylistScreen
import com.dezzy.music.ui.screens.search.OnlineSearchResult
import com.dezzy.music.ui.screens.settings.AboutScreen
import com.dezzy.music.ui.screens.settings.AccountSettings
import com.dezzy.music.ui.screens.settings.AppearanceSettings
import com.dezzy.music.ui.screens.settings.CustomizeBackground
import com.dezzy.music.ui.screens.settings.BackupAndRestore
import com.dezzy.music.ui.screens.settings.ChangelogScreen
import com.dezzy.music.ui.screens.settings.ContentSettings
import com.dezzy.music.ui.screens.settings.DarkMode
import com.dezzy.music.ui.screens.settings.DiscordLoginScreen
import com.dezzy.music.ui.screens.settings.DiscordSettings
import com.dezzy.music.ui.screens.settings.DebugSettings
import com.dezzy.music.ui.screens.settings.IntegrationScreen
import com.dezzy.music.ui.screens.settings.LastFMSettings
import com.dezzy.music.ui.screens.settings.LosslessSettings
import com.dezzy.music.ui.screens.settings.MusicTogetherScreen
import com.dezzy.music.ui.screens.settings.PalettePickerScreen
import com.dezzy.music.ui.screens.settings.PlayerSettings
import com.dezzy.music.ui.screens.settings.PoTokenScreen
import com.dezzy.music.ui.screens.settings.PrivacySettings
import com.dezzy.music.ui.screens.settings.SettingsScreen
import com.dezzy.music.ui.screens.settings.SpotifySettings
import com.dezzy.music.ui.screens.settings.StorageSettings
import com.dezzy.music.ui.screens.settings.ThemeCreatorScreen
import com.dezzy.music.ui.screens.settings.UpdateScreen
import com.dezzy.music.ui.screens.musicrecognition.MusicRecognitionRoute
import com.dezzy.music.ui.screens.musicrecognition.MusicRecognitionScreen
import com.dezzy.music.ui.screens.settings.AODSettings
import com.dezzy.music.ui.screens.settings.AiSettings
import com.dezzy.music.ui.screens.SpotifyLoginScreen
import com.dezzy.music.ui.screens.MusicMapScreen
import com.dezzy.music.ui.screens.settings.PublicRoomsScreen

import com.dezzy.music.ui.utils.ShowMediaInfo
import com.dezzy.music.utils.rememberEnumPreference
import com.dezzy.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
fun NavGraphBuilder.navigationBuilder(
    navController: NavHostController,
    scrollBehavior: TopAppBarScrollBehavior,
    latestVersionName: String,
) {

    
    composable(Screens.Home.route) {
        // Share the Activity-scoped HomeViewModel that MainActivity already creates
        // (for the shuffle button and account avatar). Scoping this one to the nav
        // entry spawned a SECOND instance whose init re-ran the entire home feed
        // load (network + DB) on every cold start.
        val activity = generateSequence(androidx.compose.ui.platform.LocalContext.current) {
            (it as? android.content.ContextWrapper)?.baseContext
        }.filterIsInstance<androidx.activity.ComponentActivity>().firstOrNull()
        HomeScreen(
            navController,
            viewModel = if (activity != null) {
                androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(viewModelStoreOwner = activity)
            } else {
                androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
            },
        )
    }
    composable(
        Screens.Library.route,
    ) {
        LibraryScreen(navController)
    }
    composable("history") {
        HistoryScreen(navController)
    }
    composable("stats") {
        StatsScreen(navController)
    }
    composable("year_in_music") {
        com.dezzy.music.ui.screens.YearInMusicScreen(navController)
    }
    composable("search") {
        MoodAndGenresScreen(navController)
    }
    composable("dj_dezzy") {
        com.dezzy.music.ui.screens.DJDezzyScreen(navController)
    }
    composable(MusicRecognitionRoute) {
        MusicRecognitionScreen(navController)
    }
    composable(Screens.MoodAndGenres.route) {
        MoodAndGenresScreen(navController)
    }
    composable("account") {
        AccountScreen(navController, scrollBehavior)
    }
    composable("new_release") {
        NewReleaseScreen(navController, scrollBehavior)
    }
    composable("charts_screen") {
        ChartsScreen(navController)
    }
    composable("music_map") {
        com.dezzy.music.ui.screens.musicmap.MusicMapScreen(navController)
    }
    composable("social") {
        com.dezzy.music.ui.screens.social.SocialScreen(navController)
    }
    composable("feed") {
        com.dezzy.music.ui.screens.social.FeedScreen(navController)
    }
    // Red social musical: actividad de amigos en vivo + feed de música compartida.
    composable("community") {
        com.dezzy.music.ui.screens.social.MusicFeedScreen(navController)
    }
    // Bandeja de mensajes (estilo Instagram DMs).
    composable("messages") {
        com.dezzy.music.ui.screens.social.MessagesInboxScreen(navController)
    }
    // Perfil propio estilo Threads (vista); la edición vive en /edit.
    composable("social/profile") {
        com.dezzy.music.ui.screens.social.MyProfileScreen(navController)
    }
    composable("social/profile/edit") {
        com.dezzy.music.ui.screens.social.ProfileScreen(navController)
    }
    // Perfil musical de otro usuario (top artistas/canciones, seguir).
    composable("social/profile/{userId}") { backStackEntry ->
        com.dezzy.music.ui.screens.social.MusicProfileScreen(
            navController = navController,
            userId = backStackEntry.arguments?.getString("userId").orEmpty(),
        )
    }
    composable("chat/{friendId}/{friendName}") { backStackEntry ->
        val friendId = backStackEntry.arguments?.getString("friendId").orEmpty()
        val friendName = backStackEntry.arguments?.getString("friendName")
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .orEmpty()
        com.dezzy.music.ui.screens.social.ChatScreen(navController, friendId, friendName)
    }
    composable("public_rooms") {
        PublicRoomsScreen(navController, scrollBehavior)
    }
    composable(
        route = "browse/{browseId}",
        arguments = listOf(
            navArgument("browseId") {
                type = NavType.StringType
            }
        )
    ) {
        BrowseScreen(
            navController,
            scrollBehavior,
            it.arguments?.getString("browseId")
        )
    }
    composable(
        route = "search/{query}",
        arguments =
            listOf(
                navArgument("query") {
                    type = NavType.StringType
                },
            ),
        enterTransition = {
            fadeIn(tween(250))
        },
        exitTransition = {
            if (targetState.destination.route?.startsWith("search/") == true) {
                fadeOut(tween(200))
            } else {
                fadeOut(tween(200)) + slideOutHorizontally { -it / 2 }
            }
        },
        popEnterTransition = {
            if (initialState.destination.route?.startsWith("search/") == true) {
                fadeIn(tween(250))
            } else {
                fadeIn(tween(250)) + slideInHorizontally { -it / 2 }
            }
        },
        popExitTransition = {
            fadeOut(tween(200))
        },
    ) {
        OnlineSearchResult(navController)
    }
    composable(
        route = "album/{albumId}",
        arguments =
            listOf(
                navArgument("albumId") {
                    type = NavType.StringType
                },
            ),
    ) {
        AlbumScreen(navController, scrollBehavior)
    }
    composable(
        route = "artist/{artistId}",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        ArtistScreen(navController, scrollBehavior)
    }
    composable(
        route = "songdna/{artistId}",
        arguments = listOf(
            navArgument("artistId") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        com.dezzy.music.ui.screens.SongDnaScreen(
            navController = navController,
            artistId = backStackEntry.arguments?.getString("artistId").orEmpty(),
        )
    }
    composable(
        route = "artist/{artistId}/songs",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        ArtistSongsScreen(navController, scrollBehavior)
    }
    composable(
        route = "artist/{artistId}/albums",
        arguments = listOf(
            navArgument("artistId") {
                type = NavType.StringType
            }
        )
    ) {
        ArtistAlbumsScreen(navController, scrollBehavior)
    }
    composable(
        route = "artist/{artistId}/items?browseId={browseId}&params={params}",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
                navArgument("browseId") {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument("params") {
                    type = NavType.StringType
                    nullable = true
                },
            ),
    ) {
        ArtistItemsScreen(navController, scrollBehavior)
    }
    composable(
        route = "online_playlist/{playlistId}",
        arguments =
            listOf(
                navArgument("playlistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        OnlinePlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "local_playlist/{playlistId}",
        arguments =
            listOf(
                navArgument("playlistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        LocalPlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "auto_playlist/{playlist}",
        arguments =
            listOf(
                navArgument("playlist") {
                    type = NavType.StringType
                },
            ),
    ) {
        AutoPlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "cache_playlist/{playlist}",
        arguments =
            listOf(
                navArgument("playlist") {
                    type = NavType.StringType
                },
            ),
    ) {
        CachePlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "top_playlist/{top}",
        arguments =
            listOf(
                navArgument("top") {
                    type = NavType.StringType
                },
            ),
    ) {
        TopPlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "youtube_browse/{browseId}?params={params}",
        arguments =
            listOf(
                navArgument("browseId") {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument("params") {
                    type = NavType.StringType
                    nullable = true
                },
            ),
    ) {
        YouTubeBrowseScreen(navController)
    }
    composable("settings") {
        SettingsScreen(navController, scrollBehavior, latestVersionName)
    }
    composable("settings/appearance") {
        AppearanceSettings(navController, scrollBehavior)
    }
    composable("settings/appearance/palette_picker") {
        PalettePickerScreen(navController)
    }
    composable("settings/appearance/theme_creator") {
        ThemeCreatorScreen(navController)
    }
    composable(
        route = "settings/appearance/always_on_display",
        enterTransition = {
            fadeIn(tween(300)) + slideInHorizontally { it / 3 }
        },
        exitTransition = {
            fadeOut(tween(200)) + slideOutHorizontally { -it / 3 }
        },
        popEnterTransition = {
            fadeIn(tween(300)) + slideInHorizontally { -it / 3 }
        },
        popExitTransition = {
            fadeOut(tween(200)) + slideOutHorizontally { it / 3 }
        },
    ) {
        AODSettings(navController, scrollBehavior)
    }
    composable("settings/content") {
        ContentSettings(navController, scrollBehavior)
    }
    composable("settings/ai") {
        AiSettings(navController, scrollBehavior)
    }
    composable("settings/player") {
        PlayerSettings(navController, scrollBehavior)
    }
    composable("settings/storage") {
        StorageSettings(navController, scrollBehavior)
    }
    composable("settings/privacy") {
        PrivacySettings(navController, scrollBehavior)
    }
    composable("settings/backup_restore") {
        BackupAndRestore(navController, scrollBehavior)
    }
    composable("settings/discord") {
        DiscordSettings(navController, scrollBehavior)
    }
    composable("settings/integration") {
        IntegrationScreen(navController, scrollBehavior)
    }
    composable("settings/lossless") {
        LosslessSettings(navController, scrollBehavior)
    }
    composable(
        "lossless?query={query}",
        arguments = listOf(
            navArgument("query") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { entry ->
        LosslessScreen(
            navController = navController,
            scrollBehavior = scrollBehavior,
            initialQuery = entry.arguments?.getString("query").orEmpty(),
        )
    }
    composable("lossless/downloads") {
        LosslessDownloadsScreen(navController, scrollBehavior)
    }
    composable("settings/spotify") {
        SpotifySettings(navController, scrollBehavior)
    }
    composable("settings/spotify/preload") {
        com.dezzy.music.ui.screens.settings.SpotifyPreloadScreen(navController)
    }
    composable("settings/music_together") {
        MusicTogetherScreen(navController, scrollBehavior)
    }
    composable("listen_together_chat") {
        val context = androidx.compose.ui.platform.LocalContext.current
        val togetherClient = remember(context) {
            com.dezzy.music.listentogether.ListenTogetherClient.getInstance()
                ?: com.dezzy.music.listentogether.ListenTogetherClient(context.applicationContext)
        }
        ListenTogetherChatScreen(
            manager = togetherClient,
            onDismiss = { navController.navigateUp() }
        )
    }
    composable("settings/lastfm") {
        LastFMSettings(navController, scrollBehavior)
    }
    composable("settings/discord/experimental") {
        com.dezzy.music.ui.screens.settings.DiscordExperimental(navController)
    }
    composable("settings/misc") {
        DebugSettings(navController)
    }
    composable("settings/update") {
        UpdateScreen(navController, scrollBehavior)
    }
    composable("settings/changelog") {
        ChangelogScreen(navController, scrollBehavior)
    }
    composable("settings/discord/login") {
        DiscordLoginScreen(navController)
    }
    composable("settings/about") {
        AboutScreen(navController, scrollBehavior)
    }
    composable("settings/spotify/login") {
        SpotifyLoginScreen(navController)
    }
    composable(Screens.DownloadQueue.route) {
        DownloadQueueScreen(navController)
    }
    composable("settings/po_token") {
        PoTokenScreen(navController, scrollBehavior)
    }
    composable("customize_background") {
        CustomizeBackground(navController)
    }
    composable(
        route = "$LOGIN_ROUTE?$LOGIN_URL_ARGUMENT={$LOGIN_URL_ARGUMENT}",
        arguments = listOf(
            navArgument(LOGIN_URL_ARGUMENT) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) { backStackEntry ->
        LoginScreen(
            navController,
            startUrl = backStackEntry.arguments?.getString(LOGIN_URL_ARGUMENT)?.let(Uri::decode)
        )
    }


// ─────────────────────────────────────────────────────────────────────────
// Always On Display — como diálogo que cubre completamente
// ─────────────────────────────────────────────────────────────────────────
    composable(
        route = "always_on_display",
        // Esto la hace un diálogo que se superpone
    ) { backStackEntry ->
        Dialog(
            onDismissRequest = { navController.navigateUp() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            AlwaysOnDisplayScreen(navController)
        }
    }

}