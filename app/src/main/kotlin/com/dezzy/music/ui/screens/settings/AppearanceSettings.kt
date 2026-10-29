/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.dezzy.music.ui.screens.settings

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.R
import com.dezzy.music.constants.ChipSortTypeKey
import com.dezzy.music.constants.DarkModeKey
import com.dezzy.music.constants.DefaultOpenTabKey
import com.dezzy.music.constants.DjNameKey
import com.dezzy.music.constants.DynamicThemeKey
import com.dezzy.music.constants.GridItemSize
import com.dezzy.music.constants.GridItemsSizeKey
import com.dezzy.music.constants.LibraryFilter
import com.dezzy.music.constants.LyricsClickKey
import com.dezzy.music.constants.LyricsScrollKey
import com.dezzy.music.constants.LyricsTextPositionKey
import com.dezzy.music.constants.PlayerDesignStyle
import com.dezzy.music.constants.PlayerDesignStyleKey
import com.dezzy.music.constants.UseNewMiniPlayerDesignKey
import com.dezzy.music.constants.DynamicMiniPlayerColorKey
import com.dezzy.music.constants.PlayerBackgroundStyle
import com.dezzy.music.constants.PlayerBackgroundStyleKey
import com.dezzy.music.constants.PureBlackKey
import com.dezzy.music.constants.RandomThemeOnStartupKey
import com.dezzy.music.constants.UseSystemFontKey
import com.dezzy.music.constants.PlayerButtonsStyle
import com.dezzy.music.constants.PlayerButtonsStyleKey
import com.dezzy.music.constants.LyricsAnimationStyleKey
import com.dezzy.music.constants.LyricsAnimationStyle
import com.dezzy.music.constants.LyricsTextSizeKey
import com.dezzy.music.constants.LyricsLineSpacingKey
import com.dezzy.music.constants.SliderStyle
import com.dezzy.music.constants.SliderStyleKey
import com.dezzy.music.constants.SlimNavBarKey
import com.dezzy.music.constants.ShowLikedPlaylistKey
import com.dezzy.music.constants.ShowDownloadedPlaylistKey
import com.dezzy.music.constants.ShowHomeCategoryChipsKey
import com.dezzy.music.constants.ShowTopPlaylistKey
import com.dezzy.music.constants.ShowCachedPlaylistKey
import com.dezzy.music.constants.ShowTagsInLibraryKey
import com.dezzy.music.constants.SwipeThumbnailKey
import com.dezzy.music.constants.SwipeSensitivityKey
import com.dezzy.music.constants.SwipeToSongKey
import com.dezzy.music.constants.HidePlayerThumbnailKey
import com.dezzy.music.constants.CanvasModeKey
import com.dezzy.music.constants.CanvasMode
import com.dezzy.music.constants.ThumbnailCornerRadiusKey
import com.dezzy.music.constants.CropThumbnailToSquareKey
import com.dezzy.music.constants.DisableBlurKey
import com.dezzy.music.constants.LiquidGlassNavBarKey
import com.dezzy.music.constants.UseLyricsV2Key
import com.dezzy.music.constants.LyricsV2Style
import com.dezzy.music.constants.LyricsV2StyleKey
import com.dezzy.music.constants.FontFamilyKey
import com.dezzy.music.constants.EnableBubbleKey
import com.dezzy.music.constants.ShowCodecOnPlayerKey
import com.dezzy.music.constants.ShowCommentButtonKey
import com.dezzy.music.constants.HideStatusBarOnFullscreenKey
import com.dezzy.music.constants.RotatingThumbnailKey
import com.dezzy.music.constants.AppleMusicLyricsBlurKey
import com.dezzy.music.constants.LyricsStandardBlurKey
import com.dezzy.music.constants.MiniPlayerBackgroundStyleKey
import com.dezzy.music.ui.component.DefaultDialog
import com.dezzy.music.ui.component.EditTextPreference
import com.dezzy.music.ui.component.EnumListPreference
import com.dezzy.music.ui.component.IconButton
import com.dezzy.music.ui.component.ListPreference
import com.dezzy.music.ui.component.PreferenceEntry
import com.dezzy.music.ui.component.PreferenceGroupTitle
import com.dezzy.music.ui.component.SwitchPreference
import com.dezzy.music.ui.component.ThumbnailCornerRadiusSelectorButton
import com.dezzy.music.ui.player.StyledPlaybackSlider
import com.dezzy.music.ui.utils.backToMain
import com.dezzy.music.ui.utils.rememberAdaptiveSidePadding
import com.dezzy.music.utils.rememberEnumPreference
import com.dezzy.music.utils.rememberPreference
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import com.dezzy.music.constants.EnableWallpaperThemeKey
import com.dezzy.music.constants.WallpaperUriKey
import com.dezzy.music.constants.WallpaperLandscapeUriKey
import com.dezzy.music.constants.WallpaperOpacityKey
import com.dezzy.music.constants.EnableVideoWallpaperKey
import com.dezzy.music.constants.VideoWallpaperUriKey
import com.dezzy.music.constants.VideoWallpaperLandscapeUriKey
import com.dezzy.music.constants.VideoWallpaperOpacityKey
import com.dezzy.music.constants.KeepPlayerScreenAwakeKey
import com.dezzy.music.constants.HomeInactivityFadeKey
import com.dezzy.music.constants.ShowArtistVideoKey
import com.dezzy.music.constants.ShowArtistBackgroundVideoKey
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.alpha
import com.dezzy.music.ui.component.VideoWallpaperPlayer
import kotlin.math.roundToInt
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    val (enableWallpaperTheme, onEnableWallpaperThemeChange) = rememberPreference(
        EnableWallpaperThemeKey,
        defaultValue = false
    )
    val (wallpaperUri, onWallpaperUriChange) = rememberPreference(
        WallpaperUriKey,
        defaultValue = ""
    )
    val (wallpaperLandscapeUri, onWallpaperLandscapeUriChange) = rememberPreference(
        WallpaperLandscapeUriKey,
        defaultValue = ""
    )
    val (wallpaperOpacity, onWallpaperOpacityChange) = rememberPreference(
        WallpaperOpacityKey,
        defaultValue = 0.5f
    )
    val (enableVideoWallpaper, onEnableVideoWallpaperChange) = rememberPreference(
        EnableVideoWallpaperKey,
        defaultValue = false
    )
    val (videoWallpaperUri, onVideoWallpaperUriChange) = rememberPreference(
        VideoWallpaperUriKey,
        defaultValue = ""
    )
    val (videoWallpaperLandscapeUri, onVideoWallpaperLandscapeUriChange) = rememberPreference(
        VideoWallpaperLandscapeUriKey,
        defaultValue = ""
    )
    val (videoWallpaperOpacity, onVideoWallpaperOpacityChange) = rememberPreference(
        VideoWallpaperOpacityKey,
        defaultValue = 0.5f
    )

    // Launchers for picking images and videos
    val verticalImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                Timber.e(e, "Error taking persistable permission")
            }
            onWallpaperUriChange(it.toString())
        }
    }

    val landscapeImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                Timber.e(e, "Error taking persistable permission")
            }
            onWallpaperLandscapeUriChange(it.toString())
        }
    }

    val verticalVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                Timber.e(e, "Error taking persistable permission")
            }
            onVideoWallpaperUriChange(it.toString())
        }
    }

    val landscapeVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                Timber.e(e, "Error taking persistable permission")
            }
            onVideoWallpaperLandscapeUriChange(it.toString())
        }
    }

    val (dynamicTheme, onDynamicThemeChange) = rememberPreference(
        DynamicThemeKey,
        defaultValue = false
    )
    val (randomThemeOnStartup, onRandomThemeOnStartupChange) = rememberPreference(
        RandomThemeOnStartupKey,
        defaultValue = false
    )
    val (darkMode, onDarkModeChange) = rememberEnumPreference(
        DarkModeKey,
        defaultValue = DarkMode.AUTO
    )
    val (playerDesignStyle, onPlayerDesignStyleChange) = rememberEnumPreference(
        PlayerDesignStyleKey,
        defaultValue = PlayerDesignStyle.V4
    )
    val (useNewMiniPlayerDesign, onUseNewMiniPlayerDesignChange) = rememberPreference(
        UseNewMiniPlayerDesignKey,
        defaultValue = true
    )
    val (dynamicMiniPlayerColor, onDynamicMiniPlayerColorChange) = rememberPreference(
        DynamicMiniPlayerColorKey,
        defaultValue = true
    )

    val (hidePlayerThumbnail, onHidePlayerThumbnailChange) = rememberPreference(
        HidePlayerThumbnailKey,
        defaultValue = false
    )

    val (canvasMode, onCanvasModeChange) = rememberEnumPreference(
        CanvasModeKey,
        defaultValue = CanvasMode.APPLE
    )
    val (artistCanvasMode, onArtistCanvasModeChange) = rememberEnumPreference(
        com.dezzy.music.constants.ArtistCanvasModeKey,
        defaultValue = com.dezzy.music.constants.ArtistCanvasMode.APPLE
    )
    val (thumbnailCornerRadius, onThumbnailCornerRadiusChange) = rememberPreference(
        key = ThumbnailCornerRadiusKey,
        defaultValue = 16f // default dp
    )
    val (cropThumbnailToSquare, onCropThumbnailToSquareChange) = rememberPreference(
        CropThumbnailToSquareKey,
        defaultValue = false
    )
    val (playerBackground, onPlayerBackgroundChange) =
        rememberEnumPreference(
            PlayerBackgroundStyleKey,
            defaultValue = PlayerBackgroundStyle.DEFAULT,
        )
    val (pureBlack, onPureBlackChange) = rememberPreference(PureBlackKey, defaultValue = true)
    val (disableBlur, onDisableBlurChange) = rememberPreference(DisableBlurKey, defaultValue = false)
    val (useSystemFont, onUseSystemFontChange) = rememberPreference(UseSystemFontKey, defaultValue = false)
    val (fontFamily, onFontFamilyChange) = rememberPreference(FontFamilyKey, defaultValue = "LORA")
    val (enableBubble, onEnableBubbleChange) = rememberPreference(EnableBubbleKey, defaultValue = false)
    val (defaultOpenTab, onDefaultOpenTabChange) = rememberEnumPreference(
        DefaultOpenTabKey,
        defaultValue = NavigationTab.HOME
    )
    val (playerButtonsStyle, onPlayerButtonsStyleChange) = rememberEnumPreference(
        PlayerButtonsStyleKey,
        defaultValue = PlayerButtonsStyle.DEFAULT
    )
    val (lyricsPosition, onLyricsPositionChange) = rememberEnumPreference(
        LyricsTextPositionKey,
        defaultValue = LyricsPosition.CENTER
    )
    val (lyricsAnimation, onLyricsAnimationChange) = rememberEnumPreference<LyricsAnimationStyle>(
        key = LyricsAnimationStyleKey,
        defaultValue = LyricsAnimationStyle.APPLE
    )
    val (lyricsClick, onLyricsClickChange) = rememberPreference(LyricsClickKey, defaultValue = true)
    val (lyricsScroll, onLyricsScrollChange) = rememberPreference(LyricsScrollKey, defaultValue = true)
    val (lyricsTextSize, onLyricsTextSizeChange) = rememberPreference(LyricsTextSizeKey, defaultValue = 26f)
    val (lyricsLineSpacing, onLyricsLineSpacingChange) = rememberPreference(LyricsLineSpacingKey, defaultValue = 1.3f)
    val (useLyricsV2, onUseLyricsV2Change) = rememberPreference(UseLyricsV2Key, defaultValue = false)
    val (lyricsV2Style, onLyricsV2StyleChange) = rememberEnumPreference(
        LyricsV2StyleKey,
        defaultValue = LyricsV2Style.LIQUID_FLOW
    )
    val (keepPlayerScreenAwake, onKeepPlayerScreenAwakeChange) = rememberPreference(KeepPlayerScreenAwakeKey, defaultValue = false)
    val (homeInactivityFade, onHomeInactivityFadeChange) = rememberPreference(HomeInactivityFadeKey, defaultValue = true)
    val (showArtistVideo, onShowArtistVideoChange) = rememberPreference(
        ShowArtistVideoKey,
        defaultValue = true
    )
    val (showArtistBackgroundVideo, onShowArtistBackgroundVideoChange) = rememberPreference(
        ShowArtistBackgroundVideoKey,
        defaultValue = true
    )

    val (showCodecOnPlayer, onShowCodecOnPlayerChange) = rememberPreference(
        ShowCodecOnPlayerKey,
        defaultValue = true
    )
    val (showCommentButton, onShowCommentButtonChange) = rememberPreference(
        ShowCommentButtonKey,
        defaultValue = true
    )
    val (hideStatusBarOnFullscreen, onHideStatusBarOnFullscreenChange) = rememberPreference(
        HideStatusBarOnFullscreenKey,
        defaultValue = false
    )
    val (rotatingThumbnail, onRotatingThumbnailChange) = rememberPreference(
        RotatingThumbnailKey,
        defaultValue = false
    )
    val (appleMusicLyricsBlur, onAppleMusicLyricsBlurChange) = rememberPreference(
        AppleMusicLyricsBlurKey,
        defaultValue = true
    )
    val (lyricsStandardBlur, onLyricsStandardBlurChange) = rememberPreference(
        LyricsStandardBlurKey,
        defaultValue = false
    )
    val (miniPlayerBackgroundStyle, onMiniPlayerBackgroundStyleChange) = rememberPreference(
        MiniPlayerBackgroundStyleKey,
        defaultValue = "DEFAULT"
    )

    val (sliderStyle, onSliderStyleChange) = rememberEnumPreference(
        SliderStyleKey,
        defaultValue = SliderStyle.Standard
    )
    val (swipeThumbnail, onSwipeThumbnailChange) = rememberPreference(
        SwipeThumbnailKey,
        defaultValue = true
    )
    val (swipeSensitivity, onSwipeSensitivityChange) = rememberPreference(
        SwipeSensitivityKey,
        defaultValue = 0.73f
    )
    val (gridItemSize, onGridItemSizeChange) = rememberEnumPreference(
        GridItemsSizeKey,
        defaultValue = GridItemSize.SMALL
    )

    val (slimNav, onSlimNavChange) = rememberPreference(
        SlimNavBarKey,
        defaultValue = false
    )

    val (swipeToSong, onSwipeToSongChange) = rememberPreference(
        SwipeToSongKey,
        defaultValue = false
    )

    val (showLikedPlaylist, onShowLikedPlaylistChange) = rememberPreference(
        ShowLikedPlaylistKey,
        defaultValue = true
    )
    val (showDownloadedPlaylist, onShowDownloadedPlaylistChange) = rememberPreference(
        ShowDownloadedPlaylistKey,
        defaultValue = true
    )
    val (showTopPlaylist, onShowTopPlaylistChange) = rememberPreference(
        ShowTopPlaylistKey,
        defaultValue = true
    )
    val (showCachedPlaylist, onShowCachedPlaylistChange) = rememberPreference(
        ShowCachedPlaylistKey,
        defaultValue = true
    )
    val (showTagsInLibrary, onShowTagsInLibraryChange) = rememberPreference(
        ShowTagsInLibraryKey,
        defaultValue = true
    )
    val (showHomeCategoryChips, onShowHomeCategoryChipsChange) = rememberPreference(
        ShowHomeCategoryChipsKey,
        defaultValue = true
    )

    val availableBackgroundStyles = PlayerBackgroundStyle.entries.filter {
        it != PlayerBackgroundStyle.BLUR || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    val (djName, onDjNameChange) = rememberPreference(
        DjNameKey,
        defaultValue = ""
    )

    val (username, onUsernameChange) = rememberPreference(
        com.dezzy.music.constants.UsernameKey,
        defaultValue = ""
    )

    val (djLanguage, onDjLanguageChange) = rememberPreference(
        com.dezzy.music.constants.DjLanguageKey,
        defaultValue = "auto"
    )

    val (liquidGlassNavBar, onLiquidGlassNavBarChange) = rememberPreference(
        LiquidGlassNavBarKey,
        defaultValue = false
    )

    val isSystemInDarkTheme = isSystemInDarkTheme()
    val useDarkTheme =
        remember(darkMode, isSystemInDarkTheme) {
            if (darkMode == DarkMode.AUTO) isSystemInDarkTheme else darkMode == DarkMode.ON
        }

    val (defaultChip, onDefaultChipChange) = rememberEnumPreference(
        key = ChipSortTypeKey,
        defaultValue = LibraryFilter.LIBRARY
    )

    var showSliderOptionDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showSliderOptionDialog) {
        val sliderStyles = remember {
            listOf(
                SliderStyle.Standard,
                SliderStyle.Wavy,
                SliderStyle.Thick,
                SliderStyle.Circular,
                SliderStyle.Simple,
                SliderStyle.NeonGlow,
                SliderStyle.Capsule,
                SliderStyle.DoubleLine,
                SliderStyle.Dotted,
                SliderStyle.Waveform,
                SliderStyle.ShootingStar,
                SliderStyle.Aurora
            )
        }
        DefaultDialog(
            buttons = {
                TextButton(
                    onClick = { showSliderOptionDialog = false }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
            onDismiss = {
                showSliderOptionDialog = false
            }
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sliderStyles.chunked(3).forEach { styleRow ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        styleRow.forEach { style ->
                            SliderStyleOptionCard(
                                sliderStyle = style,
                                selected = sliderStyle == style,
                                onClick = {
                                    onSliderStyleChange(style)
                                    showSliderOptionDialog = false
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - styleRow.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = rememberAdaptiveSidePadding()),
    ) {
        PreferenceGroupTitle(
            title = stringResource(R.string.theme),
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.enable_dynamic_theme)) },
            icon = { Icon(painterResource(R.drawable.palette), null) },
            checked = dynamicTheme,
            onCheckedChange = onDynamicThemeChange,
        )

        AnimatedVisibility(visible = !dynamicTheme || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            SwitchPreference(
                title = { Text(stringResource(R.string.random_theme_on_startup)) },
                description = stringResource(R.string.random_theme_on_startup_desc),
                icon = { Icon(painterResource(R.drawable.shuffle), null) },
                checked = randomThemeOnStartup,
                onCheckedChange = onRandomThemeOnStartupChange,
            )
        }

        AnimatedVisibility(visible = !dynamicTheme || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.color_palette)) },
                description = stringResource(R.string.customize_theme_colors),
                icon = { Icon(painterResource(R.drawable.format_paint), null) },
                onClick = { navController.navigate("settings/appearance/palette_picker") }
            )
        }

        EnumListPreference(
            title = { Text(stringResource(R.string.dark_theme)) },
            icon = { Icon(painterResource(R.drawable.dark_mode), null) },
            selectedValue = darkMode,
            onValueSelected = onDarkModeChange,
            valueText = {
                when (it) {
                    DarkMode.ON -> stringResource(R.string.dark_theme_on)
                    DarkMode.OFF -> stringResource(R.string.dark_theme_off)
                    DarkMode.AUTO -> stringResource(R.string.dark_theme_follow_system)
                }
            },
        )

        AnimatedVisibility(useDarkTheme) {
            SwitchPreference(
                title = { Text(stringResource(R.string.pure_black)) },
                icon = { Icon(painterResource(R.drawable.contrast), null) },
                checked = pureBlack,
                onCheckedChange = onPureBlackChange,
            )
        }

        SwitchPreference(
            title = { Text(stringResource(R.string.disable_blur)) },
            description = stringResource(R.string.disable_blur_desc),
            icon = { Icon(painterResource(R.drawable.blur_off), null) },
            checked = disableBlur,
            onCheckedChange = onDisableBlurChange,
        )

        ListPreference(
            title = { Text(stringResource(R.string.app_font)) },
            icon = { Icon(painterResource(R.drawable.text_fields), null) },
            selectedValue = fontFamily,
            onValueSelected = onFontFamilyChange,
                values = listOf(
                    "POPPINS", "SF_PRO", "SERIF", "MONOSPACE", "SYSTEM",
                    "INTER", "ROBOTO", "MONTSERRAT", "LORA", "PLAYFAIR_DISPLAY",
                    "NUNITO", "RUBIK", "KANIT", "COMFORTAA", "PACIFICO",
                    "LOBSTER", "DANCING_SCRIPT", "CAVEAT", "GREAT_VIBES", "CINZEL",
                    "OUTFIT", "SYNE", "SPACE_GROTESK", "MERRIWEATHER", "OSWALD"
                ),
                valueText = {
                    when (it) {
                        "POPPINS" -> "Poppins (Predeterminada)"
                        "SF_PRO" -> "SF Pro Display (Premium)"
                        "SERIF" -> "Serif"
                        "MONOSPACE" -> "Monospace"
                        "SYSTEM" -> "Sistema"
                        "INTER" -> "Inter"
                        "ROBOTO" -> "Roboto"
                        "MONTSERRAT" -> "Montserrat"
                        "LORA" -> "Lora"
                        "PLAYFAIR_DISPLAY" -> "Playfair Display"
                        "NUNITO" -> "Nunito"
                        "RUBIK" -> "Rubik"
                        "KANIT" -> "Kanit"
                        "COMFORTAA" -> "Comfortaa"
                        "PACIFICO" -> "Pacifico"
                        "LOBSTER" -> "Lobster"
                        "DANCING_SCRIPT" -> "Dancing Script"
                        "CAVEAT" -> "Caveat"
                        "GREAT_VIBES" -> "Great Vibes"
                        "CINZEL" -> "Cinzel"
                        "OUTFIT" -> "Outfit"
                        "SYNE" -> "Syne"
                        "SPACE_GROTESK" -> "Space Grotesk"
                        "MERRIWEATHER" -> "Merriweather"
                        "OSWALD" -> "Oswald"
                        else -> it
                    }
                },
                itemTextStyle = { com.dezzy.music.ui.theme.getTypography(it).bodyLarge }
            )

        PreferenceGroupTitle(
            title = stringResource(R.string.player),
        )



        SwitchPreference(
            title = { Text(stringResource(R.string.keep_screen_on)) },
            description = stringResource(R.string.keep_screen_on_desc),
            icon = { Icon(painterResource(R.drawable.motion_photos_on), null) },
            checked = keepPlayerScreenAwake,
            onCheckedChange = onKeepPlayerScreenAwakeChange,
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.new_mini_player_design)) },
            icon = { Icon(painterResource(R.drawable.nav_bar), null) },
            checked = useNewMiniPlayerDesign,
            onCheckedChange = onUseNewMiniPlayerDesignChange,
        )

        AnimatedVisibility(visible = useNewMiniPlayerDesign) {
            SwitchPreference(
                title = { Text(stringResource(R.string.dynamic_color_mini_player)) },
                description = stringResource(R.string.dynamic_color_mini_player_desc),
                icon = { Icon(painterResource(R.drawable.palette), null) },
                checked = dynamicMiniPlayerColor,
                onCheckedChange = onDynamicMiniPlayerColorChange,
            )
        }



        EnumListPreference(
            title = { Text(stringResource(R.string.player_design_style)) },
            icon = { Icon(painterResource(R.drawable.play), null) },
            selectedValue = playerDesignStyle,
            onValueSelected = onPlayerDesignStyleChange,
            valueText = {
                when (it) {
                    PlayerDesignStyle.V1 -> stringResource(R.string.player_design_v1)
                    PlayerDesignStyle.V2 -> stringResource(R.string.player_design_v2)
                    PlayerDesignStyle.V3 -> stringResource(R.string.player_design_v3)
                    PlayerDesignStyle.V4 -> stringResource(R.string.player_design_v4)
                    PlayerDesignStyle.V5 -> stringResource(R.string.player_design_v5)
                    PlayerDesignStyle.V6 -> stringResource(R.string.player_design_v6)
                    PlayerDesignStyle.V7 -> "Deezer"
                    PlayerDesignStyle.IPOD -> "iPod"
                    PlayerDesignStyle.SPOTIFY -> "Spotify"
                    PlayerDesignStyle.CAR -> "Car Mode"
                    PlayerDesignStyle.APPLE_MUSIC -> stringResource(R.string.player_background_apple_music)
                    PlayerDesignStyle.LIVE_MESH -> "Dezzy Style"
                }
            },
        )

        // Standalone player styles (Deezer, iPod, Spotify, Car Mode, Apple Music, Live Mesh)
        // draw their own fixed backgrounds by design, so the background-style option is hidden
        // for them — it can't be personalized.
        val standaloneBackgroundStyles = setOf(
            PlayerDesignStyle.V7,
            PlayerDesignStyle.IPOD,
            PlayerDesignStyle.SPOTIFY,
            PlayerDesignStyle.CAR,
            PlayerDesignStyle.APPLE_MUSIC,
            PlayerDesignStyle.LIVE_MESH,
        )
        AnimatedVisibility(visible = playerDesignStyle !in standaloneBackgroundStyles) {
            Column {
                EnumListPreference(
                    title = { Text(stringResource(R.string.player_background_style)) },
                    icon = { Icon(painterResource(R.drawable.gradient), null) },
                    selectedValue = playerBackground,
                    onValueSelected = onPlayerBackgroundChange,
                    valueText = {
                        when (it) {
                            PlayerBackgroundStyle.DEFAULT -> stringResource(R.string.follow_theme)
                            PlayerBackgroundStyle.GRADIENT -> stringResource(R.string.gradient)
                            PlayerBackgroundStyle.CUSTOM -> stringResource(R.string.custom)
                            PlayerBackgroundStyle.BLUR -> stringResource(R.string.player_background_blur)
                            PlayerBackgroundStyle.COLORING -> stringResource(R.string.coloring)
                            PlayerBackgroundStyle.BLUR_GRADIENT -> stringResource(R.string.blur_gradient)
                            PlayerBackgroundStyle.GLOW -> stringResource(R.string.glow)
                            PlayerBackgroundStyle.GLOW_ANIMATED -> "Glow Animated"
                        }
                    },
                )

                // When custom background is selected, show a direct link to customize it
                if (playerBackground == PlayerBackgroundStyle.CUSTOM) {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.customized_background)) },
                        icon = { Icon(painterResource(R.drawable.image), null) },
                        onClick = { navController.navigate("customize_background") }
                    )
                }
            }
        }

        ListPreference(
            title = { Text(stringResource(R.string.miniplayer_background_style)) },
            icon = { Icon(painterResource(R.drawable.gradient), null) },
            selectedValue = miniPlayerBackgroundStyle,
            values = listOf("DEFAULT", "BLUR", "GRADIENT", "GLOW_ANIMATED", "LIVE_MESH"),
            valueText = {
                when (it) {
                    "DEFAULT" -> "Default"
                    "BLUR" -> "Blur"
                    "GRADIENT" -> "Gradient"
                    "GLOW_ANIMATED" -> "Glow Animated"
                    "LIVE_MESH" -> "Dezzy Style"
                    else -> it
                }
            },
            onValueSelected = onMiniPlayerBackgroundStyleChange,
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.hide_player_thumbnail)) },
            description = stringResource(R.string.hide_player_thumbnail_desc),
            icon = { Icon(painterResource(R.drawable.hide_image), null) },
            checked = hidePlayerThumbnail,
            onCheckedChange = onHidePlayerThumbnailChange
        )

        // Canvas selector — available for every player style that renders a canvas
        // (all except the iPod and Car Mode players, which have no canvas surface).
        val canvasCapable = playerDesignStyle != PlayerDesignStyle.IPOD &&
            playerDesignStyle != PlayerDesignStyle.CAR
        AnimatedVisibility(visible = canvasCapable) {
            Column {
                EnumListPreference(
                    title = { Text(stringResource(R.string.song_canvas)) },
                    icon = { Icon(painterResource(R.drawable.motion_photos_on), null) },
                    selectedValue = canvasMode,
                    onValueSelected = onCanvasModeChange,
                    valueText = {
                        when (it) {
                            CanvasMode.APPLE_WITH_FALLBACK -> stringResource(R.string.apple_music_video_fallback)
                            CanvasMode.APPLE -> stringResource(R.string.apple_music)
                            CanvasMode.SPOTIFY_WITH_FALLBACK -> stringResource(R.string.spotify_video_fallback)
                            CanvasMode.SPOTIFY -> stringResource(R.string.spotify)
                            CanvasMode.NONE -> stringResource(R.string.disabled)
                        }
                    }
                )
                AnimatedVisibility(visible = canvasMode == CanvasMode.SPOTIFY || canvasMode == CanvasMode.SPOTIFY_WITH_FALLBACK) {
                    Text(
                        text = "El Canvas de Spotify solo funciona si has iniciado sesión en Spotify.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp)
                    )
                }

                Spacer(Modifier.height(4.dp))

                EnumListPreference(
                    title = { Text(stringResource(R.string.artist_canvas)) },
                    icon = { Icon(painterResource(R.drawable.artist), null) },
                    selectedValue = artistCanvasMode,
                    onValueSelected = onArtistCanvasModeChange,
                    valueText = {
                        when (it) {
                            com.dezzy.music.constants.ArtistCanvasMode.APPLE_WITH_FALLBACK -> stringResource(R.string.apple_music_video_fallback)
                            com.dezzy.music.constants.ArtistCanvasMode.APPLE -> stringResource(R.string.apple_music)
                            com.dezzy.music.constants.ArtistCanvasMode.NONE -> stringResource(R.string.disabled)
                        }
                    }
                )
            }
        }

        SwitchPreference(
            title = { Text(stringResource(R.string.rotating_thumbnail)) },
            description = stringResource(R.string.rotating_thumbnail_desc),
            icon = { Icon(painterResource(R.drawable.motion_photos_on), null) },
            checked = rotatingThumbnail,
            onCheckedChange = onRotatingThumbnailChange
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.show_comment_button)) },
            description = stringResource(R.string.show_comment_button_description),
            icon = { Icon(painterResource(R.drawable.lyrics_bubble), null) },
            checked = showCommentButton,
            onCheckedChange = onShowCommentButtonChange
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.show_codec_on_player)) },
            description = stringResource(R.string.show_codec_on_player_desc),
            icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
            checked = showCodecOnPlayer,
            onCheckedChange = onShowCodecOnPlayerChange
        )
      

        ThumbnailCornerRadiusSelectorButton(
            modifier = Modifier.padding(16.dp),
            onRadiusSelected = { selectedRadius ->
                Timber.tag("Thumbnail").d("Radius Selector: $selectedRadius")
            }
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.crop_thumbnail_to_square)) },
            description = stringResource(R.string.crop_thumbnail_to_square_desc),
            icon = { Icon(painterResource(R.drawable.image), null) },
            checked = cropThumbnailToSquare,
            onCheckedChange = onCropThumbnailToSquareChange
        )


        EnumListPreference(
            title = { Text(stringResource(R.string.player_buttons_style)) },
            icon = { Icon(painterResource(R.drawable.palette), null) },
            selectedValue = playerButtonsStyle,
            onValueSelected = onPlayerButtonsStyleChange,
            valueText = {
                when (it) {
                    PlayerButtonsStyle.DEFAULT -> stringResource(R.string.default_style)
                    PlayerButtonsStyle.SECONDARY -> stringResource(R.string.secondary_color_style)
                }
            },
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.player_slider_style)) },
            description = sliderStyleLabel(sliderStyle),
            icon = { Icon(painterResource(R.drawable.sliders), null) },
            onClick = {
                showSliderOptionDialog = true
            },
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.enable_swipe_thumbnail)) },
            icon = { Icon(painterResource(R.drawable.swipe), null) },
            checked = swipeThumbnail,
            onCheckedChange = onSwipeThumbnailChange,
        )

        AnimatedVisibility(swipeThumbnail) {
            var showSensitivityDialog by rememberSaveable { mutableStateOf(false) }
            
            if (showSensitivityDialog) {
                var tempSensitivity by remember { mutableFloatStateOf(swipeSensitivity) }
                
                DefaultDialog(
                    onDismiss = { 
                        tempSensitivity = swipeSensitivity
                        showSensitivityDialog = false 
                    },
                    buttons = {
                        TextButton(
                            onClick = { 
                                tempSensitivity = 0.73f
                            }
                        ) {
                            Text(stringResource(R.string.reset))
                        }
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        TextButton(
                            onClick = { 
                                tempSensitivity = swipeSensitivity
                                showSensitivityDialog = false 
                            }
                        ) {
                            Text(stringResource(android.R.string.cancel))
                        }
                        TextButton(
                            onClick = { 
                                onSwipeSensitivityChange(tempSensitivity)
                                showSensitivityDialog = false 
                            }
                        ) {
                            Text(stringResource(android.R.string.ok))
                        }
                    }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.swipe_sensitivity),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
    
                        Text(
                            text = stringResource(R.string.sensitivity_percentage, (tempSensitivity * 100).roundToInt()),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
    
                        Slider(
                            value = tempSensitivity,
                            onValueChange = { tempSensitivity = it },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            
            PreferenceEntry(
                title = { Text(stringResource(R.string.swipe_sensitivity)) },
                description = stringResource(R.string.sensitivity_percentage, (swipeSensitivity * 100).roundToInt()),
                icon = { Icon(painterResource(R.drawable.tune), null) },
                onClick = { showSensitivityDialog = true }
            )
        }

        PreferenceGroupTitle(
            title = stringResource(R.string.lyrics),
        )

        SwitchPreference(
            title = { Text("Lyrics V2 (Experimental)") },
            description = "Use the new fluid word-synced lyrics engine",
            icon = { Icon(painterResource(R.drawable.lyrics), null) },
            checked = useLyricsV2,
            onCheckedChange = onUseLyricsV2Change,
        )

        if (useLyricsV2) {
            EnumListPreference(
                title = { Text(stringResource(R.string.lyrics_v2_style)) },
                icon = { Icon(painterResource(R.drawable.lyrics), null) },
                selectedValue = lyricsV2Style,
                onValueSelected = onLyricsV2StyleChange,
                valueText = {
                    when (it) {
                        LyricsV2Style.LIQUID_FLOW -> stringResource(R.string.lyrics_v2_style_liquid_flow)
                        LyricsV2Style.SPOTLIGHT -> stringResource(R.string.lyrics_v2_style_spotlight)
                        LyricsV2Style.DEPTH_3D -> stringResource(R.string.lyrics_v2_style_depth_3d)
                        LyricsV2Style.CHROMATIC -> stringResource(R.string.lyrics_v2_style_chromatic)
                        LyricsV2Style.GLASS_CARDS -> stringResource(R.string.lyrics_v2_style_glass_cards)
                        LyricsV2Style.GEOMETRIC -> stringResource(R.string.lyrics_v2_style_geometric)
                        LyricsV2Style.TYPEWRITER -> stringResource(R.string.lyrics_v2_style_typewriter)
                        LyricsV2Style.HEAT_MAP -> stringResource(R.string.lyrics_v2_style_heat_map)
                        LyricsV2Style.MINIMAL_FADE -> stringResource(R.string.lyrics_v2_style_minimal_fade)
                        LyricsV2Style.BOUNCE -> stringResource(R.string.lyrics_v2_style_bounce)
                    }
                }
            )
        }

        EnumListPreference(
            title = { Text(stringResource(R.string.lyrics_text_position)) },
            icon = { Icon(painterResource(R.drawable.lyrics), null) },
            selectedValue = lyricsPosition,
            onValueSelected = onLyricsPositionChange,
            valueText = {
                when (it) {
                    LyricsPosition.LEFT -> stringResource(R.string.left)
                    LyricsPosition.CENTER -> stringResource(R.string.center)
                    LyricsPosition.RIGHT -> stringResource(R.string.right)
                }
            },
        )

        EnumListPreference(
            title = { Text("Lyrics Animation Style") },
            icon = { Icon(painterResource(R.drawable.lyrics), null) },
            selectedValue = lyricsAnimation,
            onValueSelected = onLyricsAnimationChange,
            valueText = {
                it.name.lowercase().replaceFirstChar { char -> char.uppercase() }
            },
        )
        SwitchPreference(
            title = { Text(stringResource(R.string.lyrics_click_change)) },
            icon = { Icon(painterResource(R.drawable.lyrics), null) },
            checked = lyricsClick,
            onCheckedChange = onLyricsClickChange,
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.lyrics_auto_scroll)) },
            icon = { Icon(painterResource(R.drawable.lyrics), null) },
            checked = lyricsScroll,
            onCheckedChange = onLyricsScrollChange,
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.apple_music_lyrics_blur)) },
            description = stringResource(R.string.apple_music_lyrics_blur_desc),
            icon = { Icon(painterResource(R.drawable.blur_on), null) },
            checked = appleMusicLyricsBlur,
            onCheckedChange = onAppleMusicLyricsBlurChange,
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.standard_lyrics_blur)) },
            description = stringResource(R.string.standard_lyrics_blur_desc),
            icon = { Icon(painterResource(R.drawable.blur_on), null) },
            checked = lyricsStandardBlur,
            onCheckedChange = onLyricsStandardBlurChange,
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.hide_status_bar_on_fullscreen)) },
            description = stringResource(R.string.hide_status_bar_on_fullscreen_desc),
            icon = { Icon(painterResource(R.drawable.fullscreen), null) },
            checked = hideStatusBarOnFullscreen,
            onCheckedChange = onHideStatusBarOnFullscreenChange,
        )

        var showLyricsTextSizeDialog by rememberSaveable { mutableStateOf(false) }
        
        if (showLyricsTextSizeDialog) {
            var tempTextSize by remember { mutableFloatStateOf(lyricsTextSize) }
            
            DefaultDialog(
                onDismiss = { 
                    tempTextSize = lyricsTextSize
                    showLyricsTextSizeDialog = false 
                },
                buttons = {
                    TextButton(
                        onClick = { 
                            tempTextSize = 24f
                        }
                    ) {
                        Text(stringResource(R.string.reset))
                    }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    TextButton(
                        onClick = { 
                            tempTextSize = lyricsTextSize
                            showLyricsTextSizeDialog = false 
                        }
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(
                        onClick = { 
                            onLyricsTextSizeChange(tempTextSize)
                            showLyricsTextSizeDialog = false 
                        }
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.lyrics_text_size),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Text(
                        text = "${tempTextSize.roundToInt()} sp",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Slider(
                        value = tempTextSize,
                        onValueChange = { tempTextSize = it },
                        valueRange = 16f..36f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        
        PreferenceEntry(
            title = { Text(stringResource(R.string.lyrics_text_size)) },
            description = "${lyricsTextSize.roundToInt()} sp",
            icon = { Icon(painterResource(R.drawable.text_fields), null) },
            onClick = { showLyricsTextSizeDialog = true }
        )
        
        var showLyricsLineSpacingDialog by rememberSaveable { mutableStateOf(false) }
        
        if (showLyricsLineSpacingDialog) {
            var tempLineSpacing by remember { mutableFloatStateOf(lyricsLineSpacing) }
            
            DefaultDialog(
                onDismiss = { 
                    tempLineSpacing = lyricsLineSpacing
                    showLyricsLineSpacingDialog = false 
                },
                buttons = {
                    TextButton(
                        onClick = { 
                            tempLineSpacing = 1.3f
                        }
                    ) {
                        Text(stringResource(R.string.reset))
                    }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    TextButton(
                        onClick = { 
                            tempLineSpacing = lyricsLineSpacing
                            showLyricsLineSpacingDialog = false 
                        }
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(
                        onClick = { 
                            onLyricsLineSpacingChange(tempLineSpacing)
                            showLyricsLineSpacingDialog = false 
                        }
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.lyrics_line_spacing),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Text(
                        text = "${String.format("%.1f", tempLineSpacing)}x",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Slider(
                        value = tempLineSpacing,
                        onValueChange = { tempLineSpacing = it },
                        valueRange = 1.0f..2.0f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        
        PreferenceEntry(
            title = { Text(stringResource(R.string.lyrics_line_spacing)) },
            description = "${String.format("%.1f", lyricsLineSpacing)}x",
            icon = { Icon(painterResource(R.drawable.text_fields), null) },
            onClick = { showLyricsLineSpacingDialog = true }
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.misc),
        )

        EditTextPreference(
            title = { Text("Nombre del DJ") },
            icon = { Icon(painterResource(R.drawable.person), null) },
            value = djName,
            onValueChange = onDjNameChange
        )

        EditTextPreference(
            title = { Text("Tu Nombre (Usuario)") },
            icon = { Icon(painterResource(R.drawable.person), null) },
            value = username,
            onValueChange = onUsernameChange
        )

        ListPreference(
            title = { Text("Idioma del DJ") },
            icon = { Icon(painterResource(R.drawable.language), null) },
            selectedValue = djLanguage,
            values = listOf("auto", "es", "en"),
            valueText = {
                when (it) {
                    "es" -> "Español"
                    "en" -> "Inglés"
                    else -> "Automático"
                }
            },
            onValueSelected = onDjLanguageChange
        )

        EnumListPreference(
            title = { Text(stringResource(R.string.default_open_tab)) },
            icon = { Icon(painterResource(R.drawable.nav_bar), null) },
            selectedValue = defaultOpenTab,
            onValueSelected = onDefaultOpenTabChange,
            valueText = {
                when (it) {
                    NavigationTab.HOME -> stringResource(R.string.home)
                    NavigationTab.SEARCH -> stringResource(R.string.search)
                    NavigationTab.LIBRARY -> stringResource(R.string.filter_library)
                }
            },
        )

        ListPreference(
            title = { Text(stringResource(R.string.default_lib_chips)) },
            icon = { Icon(painterResource(R.drawable.tab), null) },
            selectedValue = defaultChip,
            values = listOf(
                LibraryFilter.LIBRARY, LibraryFilter.PLAYLISTS, LibraryFilter.SONGS,
                LibraryFilter.ALBUMS, LibraryFilter.ARTISTS, LibraryFilter.GENRES
            ),
            valueText = {
                when (it) {
                    LibraryFilter.SONGS -> stringResource(R.string.songs)
                    LibraryFilter.ARTISTS -> stringResource(R.string.artists)
                    LibraryFilter.ALBUMS -> stringResource(R.string.albums)
                    LibraryFilter.PLAYLISTS -> stringResource(R.string.playlists)
                    LibraryFilter.GENRES -> "Géneros"
                    LibraryFilter.LIBRARY -> stringResource(R.string.filter_library)
                }
            },
            onValueSelected = onDefaultChipChange,
        )


        PreferenceEntry(
            title = { Text("Always On Display") },
            description = "Estilos, formas y opciones de personalización",
            icon = { Icon(painterResource(R.drawable.dark_mode), null) },
            onClick = { navController.navigate("settings/appearance/always_on_display") }
        )

        val context = LocalContext.current
        SwitchPreference(
            title = { Text(stringResource(R.string.floating_bubble)) },
            description = stringResource(R.string.floating_bubble_desc),
            icon = { Icon(painterResource(R.drawable.motion_photos_on), null) },
            checked = enableBubble,
            onCheckedChange = { isChecked ->
                if (isChecked && !android.provider.Settings.canDrawOverlays(context)) {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                    onEnableBubbleChange(false)
                } else {
                    onEnableBubbleChange(isChecked)
                }
            },
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.show_home_category_chips)) },
            description = stringResource(R.string.show_home_category_chips_desc),
            icon = { Icon(painterResource(R.drawable.home_outlined), null) },
            checked = showHomeCategoryChips,
            onCheckedChange = onShowHomeCategoryChipsChange,
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.show_tags_in_library)) },
            description = stringResource(R.string.show_tags_in_library_desc),
            icon = { Icon(painterResource(R.drawable.filter_alt), null) },
            checked = showTagsInLibrary,
            onCheckedChange = onShowTagsInLibraryChange,
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.swipe_song_to_add)) },
            icon = { Icon(painterResource(R.drawable.swipe), null) },
            checked = swipeToSong,
            onCheckedChange = onSwipeToSongChange
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.slim_navbar)) },
            icon = { Icon(painterResource(R.drawable.nav_bar), null) },
            checked = slimNav,
            onCheckedChange = onSlimNavChange
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.liquid_glass_navbar)) },
            icon = { Icon(painterResource(R.drawable.blur_on), null) },
            checked = liquidGlassNavBar,
            onCheckedChange = onLiquidGlassNavBarChange,
        )


        EnumListPreference(
            title = { Text(stringResource(R.string.grid_cell_size)) },
            icon = { Icon(painterResource(R.drawable.grid_view), null) },
            selectedValue = gridItemSize,
            onValueSelected = onGridItemSizeChange,
            valueText = {
                when (it) {
                    GridItemSize.BIG -> stringResource(R.string.big)
                    GridItemSize.SMALL -> stringResource(R.string.small)
                }
            },
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.auto_playlists)
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.show_liked_playlist)) },
            icon = { Icon(painterResource(R.drawable.favorite), null) },
            checked = showLikedPlaylist,
            onCheckedChange = onShowLikedPlaylistChange
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.show_downloaded_playlist)) },
            icon = { Icon(painterResource(R.drawable.offline), null) },
            checked = showDownloadedPlaylist,
            onCheckedChange = onShowDownloadedPlaylistChange
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.show_top_playlist)) },
            icon = { Icon(painterResource(R.drawable.trending_up), null) },
            checked = showTopPlaylist,
            onCheckedChange = onShowTopPlaylistChange
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.show_cached_playlist)) },
            icon = { Icon(painterResource(R.drawable.cached), null) },
            checked = showCachedPlaylist,
            onCheckedChange = onShowCachedPlaylistChange
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.artist_profile_settings)
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.show_artist_video)) },
            description = stringResource(R.string.show_artist_video_desc),
            icon = { Icon(painterResource(R.drawable.motion_photos_on), null) },
            checked = showArtistVideo,
            onCheckedChange = onShowArtistVideoChange,
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.show_artist_background_video)) },
            description = stringResource(R.string.show_artist_background_video_desc),
            icon = { Icon(painterResource(R.drawable.slow_motion_video), null) },
            checked = showArtistBackgroundVideo,
            onCheckedChange = onShowArtistBackgroundVideoChange,
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.wallpaper_and_video)
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.enable_image_wallpaper)) },
            icon = { Icon(painterResource(R.drawable.image), null) },
            checked = enableWallpaperTheme,
            onCheckedChange = { checked ->
                onEnableWallpaperThemeChange(checked)
                if (checked) {
                    onEnableVideoWallpaperChange(false)
                }
            }
        )

        AnimatedVisibility(enableWallpaperTheme) {
            Column {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.select_vertical_image)) },
                    description = if (wallpaperUri.isEmpty()) stringResource(R.string.no_image_selected) else stringResource(R.string.vertical_image_set),
                    icon = { Icon(painterResource(R.drawable.android_cell), null) },
                    onClick = { verticalImageLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                )

                PreferenceEntry(
                    title = { Text(stringResource(R.string.select_horizontal_image)) },
                    description = if (wallpaperLandscapeUri.isEmpty()) stringResource(R.string.no_image_selected) else stringResource(R.string.horizontal_image_set),
                    icon = { Icon(painterResource(R.drawable.desktop_windows), null) },
                    onClick = { landscapeImageLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                )

                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.image_opacity, (wallpaperOpacity * 100).roundToInt()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = wallpaperOpacity,
                        onValueChange = onWallpaperOpacityChange,
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        SwitchPreference(
            title = { Text(stringResource(R.string.enable_video_wallpaper)) },
            icon = { Icon(painterResource(R.drawable.motion_photos_on), null) },
            checked = enableVideoWallpaper,
            onCheckedChange = { checked ->
                onEnableVideoWallpaperChange(checked)
                if (checked) {
                    onEnableWallpaperThemeChange(false)
                }
            }
        )

        AnimatedVisibility(enableVideoWallpaper) {
            Column {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.select_vertical_video)) },
                    description = if (videoWallpaperUri.isEmpty()) stringResource(R.string.no_video_selected) else stringResource(R.string.vertical_video_set),
                    icon = { Icon(painterResource(R.drawable.android_cell), null) },
                    onClick = { verticalVideoLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly)) }
                )

                PreferenceEntry(
                    title = { Text(stringResource(R.string.select_horizontal_video)) },
                    description = if (videoWallpaperLandscapeUri.isEmpty()) stringResource(R.string.no_video_selected) else stringResource(R.string.horizontal_video_set),
                    icon = { Icon(painterResource(R.drawable.desktop_windows), null) },
                    onClick = { landscapeVideoLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly)) }
                )

                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.video_opacity, (videoWallpaperOpacity * 100).roundToInt()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = videoWallpaperOpacity,
                        onValueChange = onVideoWallpaperOpacityChange,
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        SwitchPreference(
            title = { Text(stringResource(R.string.inactivity_fade)) },
            description = stringResource(R.string.inactivity_fade_desc),
            icon = { Icon(painterResource(R.drawable.image), null) },
            checked = homeInactivityFade,
            onCheckedChange = onHomeInactivityFadeChange,
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.appearance)) },
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
        }
    )
}

@Composable
private fun SliderStyleOptionCard(
    sliderStyle: SliderStyle,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Dynamic, self-animating preview so each style feels "alive" in the picker.
    val infiniteTransition = rememberInfiniteTransition(label = "sliderPreview")
    val animatedValue by infiniteTransition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sliderPreviewValue",
    )

    val scale by animateFloatAsState(
        targetValue = if (selected) 1.03f else 1f,
        animationSpec = tween(220),
        label = "cardScale",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        animationSpec = tween(220),
        label = "cardBorder",
    )
    val borderWidth = if (selected) 2.dp else 1.dp

    val containerBrush = if (selected) {
        Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.colorScheme.surfaceContainer,
            )
        )
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(containerBrush)
            .border(borderWidth, borderColor, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            StyledPlaybackSlider(
                sliderStyle = sliderStyle,
                value = animatedValue,
                valueRange = 0f..1f,
                onValueChange = {},
                onValueChangeFinished = {},
                activeColor = MaterialTheme.colorScheme.primary,
                isPlaying = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            Text(
                text = sliderStyleLabel(sliderStyle),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Transparent overlay so a tap anywhere selects the card, even over the
        // (non-interactive) slider preview which would otherwise swallow the touch.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = onClick)
        )

        AnimatedVisibility(
            visible = selected,
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun sliderStyleLabel(sliderStyle: SliderStyle): String {
    return when (sliderStyle) {
        SliderStyle.Standard -> stringResource(R.string.slider_style_standard)
        SliderStyle.Wavy -> stringResource(R.string.slider_style_wavy)
        SliderStyle.Thick -> stringResource(R.string.slider_style_thick)
        SliderStyle.Circular -> stringResource(R.string.slider_style_circular)
        SliderStyle.Simple -> stringResource(R.string.slider_style_simple)
        SliderStyle.NeonGlow -> stringResource(R.string.slider_style_neon_glow)
        SliderStyle.Capsule -> stringResource(R.string.slider_style_capsule)
        SliderStyle.DoubleLine -> stringResource(R.string.slider_style_double_line)
        SliderStyle.Dotted -> stringResource(R.string.slider_style_dotted)
        SliderStyle.Waveform -> stringResource(R.string.slider_style_waveform)
        SliderStyle.ShootingStar -> stringResource(R.string.slider_style_shooting_star)
        SliderStyle.Aurora -> stringResource(R.string.slider_style_aurora)
    }
}

enum class DarkMode {
    ON,
    OFF,
    AUTO,
}

enum class NavigationTab {
    HOME,
    SEARCH,
    LIBRARY,
}

enum class LyricsPosition {
    LEFT,
    CENTER,
    RIGHT,
}

enum class PlayerTextAlignment {
    SIDED,
    CENTER,
}
