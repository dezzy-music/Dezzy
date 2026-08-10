/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.arturo254.opentune.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.LocalDateTime
import java.time.ZoneOffset

val DynamicThemeKey = booleanPreferencesKey("dynamicTheme")
val CustomThemeColorKey = stringPreferencesKey("customThemeColor")
val RandomThemeOnStartupKey = booleanPreferencesKey("randomThemeOnStartup")
val DarkModeKey = stringPreferencesKey("darkMode")
val PureBlackKey = booleanPreferencesKey("pureBlack")
val UseSystemFontKey = booleanPreferencesKey("useSystemFont")
val DefaultOpenTabKey = stringPreferencesKey("defaultOpenTab")
val SlimNavBarKey = booleanPreferencesKey("slimNavBar")
val GridItemsSizeKey = stringPreferencesKey("gridItemSize")
val SliderStyleKey = stringPreferencesKey("sliderStyle")
val SwipeToSongKey = booleanPreferencesKey("SwipeToSong")
val PlayerDesignStyleKey = stringPreferencesKey("playerDesignStyle")
val UseNewLibraryDesignKey = booleanPreferencesKey("useNewLibraryDesign")
val UseNewMiniPlayerDesignKey = booleanPreferencesKey("useNewMiniPlayerDesign")
val DynamicMiniPlayerColorKey = booleanPreferencesKey("dynamicMiniPlayerColor")
val HidePlayerThumbnailKey = booleanPreferencesKey("hidePlayerThumbnail")
val OpenTuneCanvasKey = booleanPreferencesKey("OpenTuneCanvas")
val EnableSpotifyCanvasKey = booleanPreferencesKey("enableSpotifyCanvas")

/** Mutually-exclusive canvas source selector */
val CanvasModeKey = stringPreferencesKey("canvasMode")
enum class CanvasMode {
    APPLE_WITH_FALLBACK,
    APPLE,
    SPOTIFY_WITH_FALLBACK,
    SPOTIFY,
    NONE,
}

val ArtistCanvasModeKey = stringPreferencesKey("artistCanvasMode")
enum class ArtistCanvasMode {
    APPLE_WITH_FALLBACK,
    APPLE,
    NONE,
}
val ThumbnailCornerRadiusKey = floatPreferencesKey("thumbnailCornerRadius")
val CropThumbnailToSquareKey = booleanPreferencesKey("cropThumbnailToSquare")
val SeekExtraSeconds = booleanPreferencesKey("seekExtraSeconds")
val DisableBlurKey = booleanPreferencesKey("disableBlur")
val BlurRadiusKey = floatPreferencesKey("blurRadius")
val MiniPlayerLastAnchorKey = intPreferencesKey("miniPlayerLastAnchor")
val MiniPlayerStyleKey = stringPreferencesKey("miniPlayerStyle")

enum class MiniPlayerStyle {
    PILL,   // default: rounded pill with surfaceContainer
    CARD,   // elevated card with shadow and softer corners
    GLASS,  // glassmorphism: blur + frosted overlay + accent border
    COMPACT, // minimal: artwork + thin progress bar only, controls on tap
    GLOW_ANIMATED // animated glow background style
}

// Wallpaper Keys
val EnableWallpaperThemeKey = booleanPreferencesKey("enableWallpaperTheme")
val WallpaperUriKey = stringPreferencesKey("wallpaperUri")
val WallpaperLandscapeUriKey = stringPreferencesKey("wallpaperLandscapeUri")
val WallpaperOpacityKey = floatPreferencesKey("wallpaperOpacity")
val EnableVideoWallpaperKey = booleanPreferencesKey("enableVideoWallpaper")
val VideoWallpaperLandscapeUriKey = stringPreferencesKey("videoWallpaperLandscapeUri")
val VideoWallpaperOpacityKey = floatPreferencesKey("videoWallpaperOpacity")

// Video mode (music-video playback) keys
val VideoMaxQualityKey = stringPreferencesKey("videoMaxQuality")
val VideoResizeModeKey = stringPreferencesKey("videoResizeMode")
val VideoKeepModeOnSongChangeKey = booleanPreferencesKey("videoKeepModeOnSongChange")
val CastVideoKey = booleanPreferencesKey("castVideo")

enum class VideoQuality(val maxHeight: Int) {
    P360(360),
    P480(480),
    P720(720),
    P1080(1080),
}

enum class VideoResizeMode {
    FIT,
    CROP,
    FILL,
}

enum class SliderStyle {
    Standard,
    Wavy,
    Thick,
    Circular,
    Simple,
    NeonGlow,
    Capsule,
    DoubleLine,
    Dotted,
    Waveform,
    ShootingStar,
    Aurora,
}

const val SYSTEM_DEFAULT = "SYSTEM_DEFAULT"
val AppLanguageKey = stringPreferencesKey("appLanguage")
val ContentLanguageKey = stringPreferencesKey("contentLanguage")
val ContentCountryKey = stringPreferencesKey("contentCountry")
val EnableKugouKey = booleanPreferencesKey("enableKugou")
val EnableLrcLibKey = booleanPreferencesKey("enableLrclib")
val EnableBetterLyricsKey = booleanPreferencesKey("enableBetterLyrics")
val EnableSimpMusicLyricsKey = booleanPreferencesKey("enableSimpMusicLyrics")
val HideExplicitKey = booleanPreferencesKey("hideExplicit")
val HideVideoKey = booleanPreferencesKey("hideVideo")
val ProxyEnabledKey = booleanPreferencesKey("proxyEnabled")
val ProxyUrlKey = stringPreferencesKey("proxyUrl")
val ProxyTypeKey = stringPreferencesKey("proxyType")
val StreamBypassProxyKey = booleanPreferencesKey("streamBypassProxy")
val YtmSyncKey = booleanPreferencesKey("ytmSync")
val SelectedYtmPlaylistsKey = stringPreferencesKey("ytm_selected_playlists")

val TogetherDisplayNameKey = stringPreferencesKey("together_display_name")
val TogetherClientIdKey = stringPreferencesKey("together_client_id")
val TogetherDefaultPortKey = intPreferencesKey("together_default_port")
val TogetherAllowGuestsToAddTracksKey = booleanPreferencesKey("together_allow_guests_add_tracks")
val TogetherAllowGuestsToControlPlaybackKey = booleanPreferencesKey("together_allow_guests_control_playback")
val TogetherRequireHostApprovalToJoinKey = booleanPreferencesKey("together_require_host_approval_to_join")
val TogetherLastJoinLinkKey = stringPreferencesKey("together_last_join_link")
val TogetherWelcomeShownKey = booleanPreferencesKey("together_welcome_shown")
    
// ListenBrainz scrobbling
val ListenBrainzEnabledKey = booleanPreferencesKey("listenbrainz_enabled")
val ListenBrainzTokenKey = stringPreferencesKey("listenbrainz_token")

// Last.fm scrobbling
val LastFMSessionKey = stringPreferencesKey("lastfmSession")
val LastFMUsernameKey = stringPreferencesKey("lastfmUsername")
val EnableLastFMScrobblingKey = booleanPreferencesKey("lastfmScrobblingEnable")
val LastFMUseNowPlaying = booleanPreferencesKey("lastfmUseNowPlaying")
val ScrobbleDelayPercentKey = floatPreferencesKey("scrobbleDelayPercent")
val ScrobbleMinSongDurationKey = intPreferencesKey("scrobbleMinSongDuration")
val ScrobbleDelaySecondsKey = intPreferencesKey("scrobbleDelaySeconds")

val OmegaMathUserIdKey = stringPreferencesKey("omega_math_user_id")
val MusicMapShareLocationKey = booleanPreferencesKey("musicMapShareLocation")
// Native Supabase session (social network: auth, friends, messaging)
val SupabaseAccessTokenKey = stringPreferencesKey("supabaseAccessToken")
val SupabaseRefreshTokenKey = stringPreferencesKey("supabaseRefreshToken")
val SupabaseTokenExpiryKey = longPreferencesKey("supabaseTokenExpiry")
val PendingReferralKey = stringPreferencesKey("pendingReferralInviterId")
val SocialMyPhoneKey = stringPreferencesKey("socialMyPhoneDisplay")

val AudioQualityKey = stringPreferencesKey("audioQuality")

val NetworkMeteredKey = booleanPreferencesKey("networkMetered")

enum class AudioQuality {
    AUTO,
    HIGH,
    HIGHEST,
    LOW,
}

val PlayerStreamClientKey = stringPreferencesKey("playerStreamClient")

enum class PlayerStreamClient {
    ANDROID_VR,
    WEB_REMIX,
    IOS,
    TVHTML5,
    ANDROID_MUSIC,
}

val PersistentQueueKey = booleanPreferencesKey("persistentQueue")
val PermanentShuffleKey = booleanPreferencesKey("permanentShuffle")
val SkipSilenceKey = booleanPreferencesKey("skipSilence")
val AudioNormalizationKey = booleanPreferencesKey("audioNormalization")
val AudioOffload = booleanPreferencesKey("audioOffload")
val AudioCrossfadeDurationKey = intPreferencesKey("audioCrossfadeDuration")
val AutoLoadMoreKey = booleanPreferencesKey("autoLoadMore")
val AutoDownloadOnLikeKey = booleanPreferencesKey("autoDownloadOnLike")
val AutoSkipNextOnErrorKey = booleanPreferencesKey("autoSkipNextOnError")
val PauseOnDeviceMuteKey = booleanPreferencesKey("pauseOnDeviceMute")
val AutoStartOnBluetoothKey = booleanPreferencesKey("autoStartOnBluetooth")
val StopMusicOnTaskClearKey = booleanPreferencesKey("stopMusicOnTaskClear")
val WakelockKey = booleanPreferencesKey("wakelock")
val ArtistSeparatorsKey = stringPreferencesKey("artistSeparators")
val ExternalDownloaderEnabledKey = booleanPreferencesKey("externalDownloaderEnabled")
val ExternalDownloaderPackageKey = stringPreferencesKey("externalDownloaderPackage")
val PlaylistTagsFilterKey = stringPreferencesKey("playlistTagsFilter")
val ShowHomeCategoryChipsKey = booleanPreferencesKey("showHomeCategoryChips")
val ShowTagsInLibraryKey = booleanPreferencesKey("showTagsInLibrary")
val LiquidGlassNavBarKey = booleanPreferencesKey("liquid_glass_nav_bar")

val EqualizerEnabledKey = booleanPreferencesKey("equalizerEnabled")
val EqualizerBandLevelsMbKey = stringPreferencesKey("equalizerBandLevelsMb")
val EqualizerOutputGainEnabledKey = booleanPreferencesKey("equalizerOutputGainEnabled")
val EqualizerOutputGainMbKey = intPreferencesKey("equalizerOutputGainMb")
val EqualizerBassBoostEnabledKey = booleanPreferencesKey("equalizerBassBoostEnabled")
val EqualizerBassBoostStrengthKey = intPreferencesKey("equalizerBassBoostStrength")
val EqualizerVirtualizerEnabledKey = booleanPreferencesKey("equalizerVirtualizerEnabled")
val EqualizerVirtualizerStrengthKey = intPreferencesKey("equalizerVirtualizerStrength")
val EqualizerSelectedProfileIdKey = stringPreferencesKey("equalizerSelectedProfileId")
val EqualizerCustomProfilesJsonKey = stringPreferencesKey("equalizerCustomProfilesJson")

/** Compartir "escuchando ahora" con la red social. */
val SocialShareActivityKey = booleanPreferencesKey("socialShareActivity")
/** Quién ve mi actividad: public | friends | private. */
val SocialVisibilityKey = stringPreferencesKey("socialVisibility")

/** Ajusta el ecualizador solo según el género detectado de la canción en curso. */
val EqualizerAutoGenreKey = booleanPreferencesKey("equalizerAutoGenre")
/** Género aplicado actualmente por el modo automático (solo lectura para la UI). */
val EqualizerDetectedGenreKey = stringPreferencesKey("equalizerDetectedGenre")

val MaxImageCacheSizeKey = intPreferencesKey("maxImageCacheSize")
val SmartTrimmerKey = booleanPreferencesKey("smartTrimmer")
val MaxSongCacheSizeKey = intPreferencesKey("maxSongCacheSize")
val MaxCanvasCacheSizeKey = intPreferencesKey("maxCanvasCacheSize")
val MaxParallelDownloadsKey = intPreferencesKey("maxParallelDownloads")
val ExportDownloadsToMusicKey = booleanPreferencesKey("exportDownloadsToMusic")

val PauseListenHistoryKey = booleanPreferencesKey("pauseListenHistory")
val PauseSearchHistoryKey = booleanPreferencesKey("pauseSearchHistory")
val DisableScreenshotKey = booleanPreferencesKey("disableScreenshot")

val DiscordTokenKey = stringPreferencesKey("discordToken")
val DiscordInfoDismissedKey = booleanPreferencesKey("discordInfoDismissed")
val DiscordUsernameKey = stringPreferencesKey("discordUsername")
val DiscordNameKey = stringPreferencesKey("discordName")
val EnableDiscordRPCKey = booleanPreferencesKey("discordRPCEnable")
// Discord activity customization keys
val DiscordActivityNameKey = stringPreferencesKey("discordActivityName")
val DiscordActivityDetailsKey = stringPreferencesKey("discordActivityDetails")
val DiscordActivityStateKey = stringPreferencesKey("discordActivityState")
// Custom button labels and urls for Discord activity buttons
val DiscordActivityButton1LabelKey = stringPreferencesKey("discordActivityButton1Label")
val DiscordActivityButton1UrlSourceKey = stringPreferencesKey("discordActivityButton1UrlSource")
val DiscordActivityButton1CustomUrlKey = stringPreferencesKey("discordActivityButton1CustomUrl")
val DiscordActivityButton2LabelKey = stringPreferencesKey("discordActivityButton2Label")
val DiscordActivityButton2UrlSourceKey = stringPreferencesKey("discordActivityButton2UrlSource")
val DiscordActivityButton2CustomUrlKey = stringPreferencesKey("discordActivityButton2CustomUrl")
val DiscordActivityButton1EnabledKey = booleanPreferencesKey("discordActivityButton1Enabled")
val DiscordActivityButton2EnabledKey = booleanPreferencesKey("discordActivityButton2Enabled")
val DiscordShowWhenPausedKey = booleanPreferencesKey("discordShowWhenPaused")
// Activity type for Discord presence (PLAYING, STREAMING, LISTENING, WATCHING, COMPETING)
val DiscordActivityTypeKey = stringPreferencesKey("discordActivityType")
val DiscordPresenceIntervalValueKey = intPreferencesKey("discordPresenceIntervalValue")
val DiscordPresenceIntervalUnitKey = stringPreferencesKey("discordPresenceIntervalUnit") // "S", "M", "H"
val DiscordPresenceStatusKey = stringPreferencesKey("discordPresenceStatus") // "ONLINE", "IDLE", "DND", "INVISIBLE"

// Discord image selection keys
// Values for type keys: "thumbnail", "artist", "appicon", "custom"
val DiscordLargeImageTypeKey = stringPreferencesKey("discordLargeImageType")
val DiscordLargeTextSourceKey = stringPreferencesKey("discordLargeTextSource")
val DiscordLargeTextCustomKey = stringPreferencesKey("discordLargeTextCustom")
val DiscordLargeImageCustomUrlKey = stringPreferencesKey("discordLargeImageCustomUrl")
val DiscordSmallImageTypeKey = stringPreferencesKey("discordSmallImageType")
val DiscordSmallImageCustomUrlKey = stringPreferencesKey("discordSmallImageCustomUrl")
// Activity platform (discord client platform) selection
val DiscordActivityPlatformKey = stringPreferencesKey("discordActivityPlatform")

val TranslatorContextsKey = stringPreferencesKey("translatorContexts")
val TranslatorTargetLangKey = stringPreferencesKey("translatorTargetLang")
val EnableTranslatorKey = booleanPreferencesKey("enableTranslator")

val ChipSortTypeKey = stringPreferencesKey("chipSortType")
val SongSortTypeKey = stringPreferencesKey("songSortType")
val SongSortDescendingKey = booleanPreferencesKey("songSortDescending")
val PlaylistSongSortTypeKey = stringPreferencesKey("playlistSongSortType")
val PlaylistSongSortDescendingKey = booleanPreferencesKey("playlistSongSortDescending")
val AutoPlaylistSongSortTypeKey = stringPreferencesKey("autoPlaylistSongSortType")
val AutoPlaylistSongSortDescendingKey = booleanPreferencesKey("autoPlaylistSongSortDescending")
val ArtistSortTypeKey = stringPreferencesKey("artistSortType")
val ArtistSortDescendingKey = booleanPreferencesKey("artistSortDescending")
val AlbumSortTypeKey = stringPreferencesKey("albumSortType")
val AlbumSortDescendingKey = booleanPreferencesKey("albumSortDescending")
val PlaylistSortTypeKey = stringPreferencesKey("playlistSortType")
val PlaylistSortDescendingKey = booleanPreferencesKey("playlistSortDescending")
val ArtistSongSortTypeKey = stringPreferencesKey("artistSongSortType")
val ArtistSongSortDescendingKey = booleanPreferencesKey("artistSongSortDescending")
val MixSortTypeKey = stringPreferencesKey("mixSortType")
val MixSortDescendingKey = booleanPreferencesKey("albumSortDescending")

val SongFilterKey = stringPreferencesKey("songFilter")
val ArtistFilterKey = stringPreferencesKey("artistFilter")
val AlbumFilterKey = stringPreferencesKey("albumFilter")
val AudioCrossfadeGaplessKey = booleanPreferencesKey("audio_crossfade_gapless")

// Dezzy Style (tornamesa de vinilo): tema de color, estilo del vinilo y velocidad de giro
val DezzyVinylThemeKey = stringPreferencesKey("dezzy_vinyl_theme")
val DezzyVinylStyleKey = stringPreferencesKey("dezzy_vinyl_style")
val DezzyVinylSpeedKey = stringPreferencesKey("dezzy_vinyl_speed")

val ArtistViewTypeKey = stringPreferencesKey("artistViewType")
val AlbumViewTypeKey = stringPreferencesKey("albumViewType")
val PlaylistViewTypeKey = stringPreferencesKey("playlistViewType")

val PlaylistEditLockKey = booleanPreferencesKey("playlistEditLock")
val QuickPicksKey = stringPreferencesKey("discover")
val SpeedDialSongIdsKey = stringPreferencesKey("speedDialSongIds")
val PreferredLyricsProviderKey = stringPreferencesKey("lyricsProvider")
val QueueEditLockKey = booleanPreferencesKey("queueEditLock")

val ShowLikedPlaylistKey = booleanPreferencesKey("show_liked_playlist")
val ShowDownloadedPlaylistKey = booleanPreferencesKey("show_downloaded_playlist")
val ShowTopPlaylistKey = booleanPreferencesKey("show_top_playlist")
val ShowCachedPlaylistKey = booleanPreferencesKey("show_cached_playlist")

enum class LibraryViewType {
    LIST,
    GRID,
    ;

    fun toggle() =
        when (this) {
            LIST -> GRID
            GRID -> LIST
        }
}

enum class SongFilter {
    LIBRARY,
    LIKED,
    DOWNLOADED
}

enum class ArtistFilter {
    LIBRARY,
    LIKED
}

enum class AlbumFilter {
    LIBRARY,
    LIKED,
    DOWNLOADED,
    DOWNLOADED_FULL
}

enum class SongSortType {
    CREATE_DATE,
    NAME,
    ARTIST,
    PLAY_TIME,
}

enum class PlaylistSongSortType {
    CUSTOM,
    CREATE_DATE,
    NAME,
    ARTIST,
    PLAY_TIME,
}

enum class AutoPlaylistSongSortType {
    CREATE_DATE,
    NAME,
    ARTIST,
    PLAY_TIME,
}

enum class ArtistSortType {
    CREATE_DATE,
    NAME,
    SONG_COUNT,
    PLAY_TIME,
}

enum class ArtistSongSortType {
    CREATE_DATE,
    NAME,
    PLAY_TIME,
}

enum class AlbumSortType {
    CREATE_DATE,
    NAME,
    ARTIST,
    YEAR,
    SONG_COUNT,
    LENGTH,
    PLAY_TIME,
}

enum class PlaylistSortType {
    CREATE_DATE,
    NAME,
    SONG_COUNT,
    LAST_UPDATED,
    CUSTOM,
}

enum class MixSortType {
    CREATE_DATE,
    NAME,
    LAST_UPDATED,
}

enum class GridItemSize {
    BIG,
    SMALL,
}

enum class MyTopFilter {
    ALL_TIME,
    DAY,
    WEEK,
    MONTH,
    YEAR,
    ;

    fun toTimeMillis(): Long =
        when (this) {
            DAY ->
                LocalDateTime
                    .now()
                    .minusDays(1)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()

            WEEK ->
                LocalDateTime
                    .now()
                    .minusWeeks(1)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()

            MONTH ->
                LocalDateTime
                    .now()
                    .minusMonths(1)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()

            YEAR ->
                LocalDateTime
                    .now()
                    .minusMonths(12)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()

            ALL_TIME -> 0
        }
}

enum class QuickPicks {
    QUICK_PICKS,
    LAST_LISTEN,
}

enum class PreferredLyricsProvider {
    LRCLIB,
    KUGOU,
    BETTER_LYRICS,
    SIMPMUSIC,
}

enum class PlayerButtonsStyle {
    DEFAULT,
    SECONDARY,
}

enum class PlayerDesignStyle {
    V1,
    V2,
    V3,
    V4,
    V5,
    V6,
    V7,
    IPOD,
    SPOTIFY,
    CAR,
    APPLE_MUSIC,
    LIVE_MESH,
}

enum class PlayerBackgroundStyle {
    DEFAULT,
    GRADIENT,
    CUSTOM,
    BLUR,
    COLORING,
    BLUR_GRADIENT,
    GLOW,
    GLOW_ANIMATED,
}

// Keys for customized background
val PlayerCustomImageUriKey = stringPreferencesKey("playerCustomImageUri")
val PlayerCustomBlurKey = floatPreferencesKey("playerCustomBlur")
val PlayerCustomContrastKey = floatPreferencesKey("playerCustomContrast")
val PlayerCustomBrightnessKey = floatPreferencesKey("playerCustomBrightness")


val LyricsAnimationStyleKey = stringPreferencesKey("lyricsAnimationStyle")
enum class LyricsAnimationStyle {
    NONE,
    FADE,
    GLOW,
    SLIDE,
    KARAOKE,
    APPLE,
    APPLE_V2,
}

val LyricsV2StyleKey = stringPreferencesKey("lyricsV2Style")
enum class LyricsV2Style {
    LIQUID_FLOW,    // Flotar hacia arriba con blur al desaparecer
    SPOTLIGHT,      // Fondo oscuro, foco de luz en línea activa
    DEPTH_3D,       // Perspectiva 3D: futuras pequeñas/lejos, activa grande
    CHROMATIC,      // Color dinámico del gradiente en línea activa
    GLASS_CARDS,    // Tarjetas glassmorphism, activa elevada y brillante
    GEOMETRIC,      // Wipe diagonal/circular al activar línea
    TYPEWRITER,     // Línea activa se escribe carácter a carácter
    HEAT_MAP,       // Karaoke con gradiente azul→naranja→blanco
    MINIMAL_FADE,   // Solo activa al 100%, resto casi transparente
    BOUNCE,         // Spring bounce al activar, resto estático
}


val ShowCodecOnPlayerKey = booleanPreferencesKey("showCodecOnPlayer")
val ShowCommentButtonKey = booleanPreferencesKey("show_comment_button")
val HideStatusBarOnFullscreenKey = booleanPreferencesKey("hideStatusBarOnFullscreen")
val RotatingThumbnailKey = booleanPreferencesKey("rotatingThumbnail")
val CanvasThumbnailAnimationKey = booleanPreferencesKey("canvasThumbnailAnimation")
val AppleMusicLyricsBlurKey = booleanPreferencesKey("appleMusicLyricsBlur")
val LyricsStandardBlurKey = booleanPreferencesKey("lyricsStandardBlur")
val MiniPlayerBackgroundStyleKey = stringPreferencesKey("miniPlayerBackgroundStyle")

val LyricsTextSizeKey = floatPreferencesKey("lyricsTextSize")
val LyricsLineSpacingKey = floatPreferencesKey("lyricsLineSpacing")

val TopSize = stringPreferencesKey("topSize")
val HistoryDuration = floatPreferencesKey("historyDuration")

val PlayerButtonsStyleKey = stringPreferencesKey("player_buttons_style")
val PlayerBackgroundStyleKey = stringPreferencesKey("playerBackgroundStyle")
val ShowLyricsKey = booleanPreferencesKey("showLyrics")
val LyricsTextPositionKey = stringPreferencesKey("lyricsTextPosition")
val LyricsClickKey = booleanPreferencesKey("lyricsClick")
val LyricsScrollKey = booleanPreferencesKey("lyricsScrollKey")
val LyricsRomanizeJapaneseKey = booleanPreferencesKey("lyricsRomanizeJapanese")
val LyricsRomanizeKoreanKey = booleanPreferencesKey("lyricsRomanizeKorean")
val TranslateLyricsKey = booleanPreferencesKey("translateLyrics")
val UseLyricsV2Key = booleanPreferencesKey("useLyricsV2")

// Queue lyrics pre-load settings
val PreloadQueueLyricsEnabledKey = booleanPreferencesKey("preload_queue_lyrics_enabled")
val QueueLyricsPreloadCountKey = intPreferencesKey("queue_lyrics_preload_count")

val PlayerVolumeKey = floatPreferencesKey("playerVolume")
val RepeatModeKey = intPreferencesKey("repeatMode")

val SearchSourceKey = stringPreferencesKey("searchSource")
val SwipeThumbnailKey = booleanPreferencesKey("swipeThumbnail")
val SwipeSensitivityKey = floatPreferencesKey("swipeSensitivity")

enum class SearchSource {
    LOCAL,
    ONLINE,
    LOSSLESS,
    ;

    fun toggle() =
        when (this) {
            LOCAL -> ONLINE
            ONLINE -> LOCAL
            LOSSLESS -> ONLINE
        }

    /**
     * Siguiente fuente en el ciclo del botón de la barra de búsqueda. LOSSLESS
     * solo entra en el ciclo si el usuario tiene la función activada en Ajustes.
     */
    fun next(losslessEnabled: Boolean): SearchSource =
        when (this) {
            ONLINE -> LOCAL
            LOCAL -> if (losslessEnabled) LOSSLESS else ONLINE
            LOSSLESS -> ONLINE
        }
}

val VisitorDataKey = stringPreferencesKey("visitorData")
val DataSyncIdKey = stringPreferencesKey("dataSyncId")
val InnerTubeCookieKey = stringPreferencesKey("innerTubeCookie")
val PoTokenKey = stringPreferencesKey("poToken")
val AccountNameKey = stringPreferencesKey("accountName")
val AccountEmailKey = stringPreferencesKey("accountEmail")
val AccountChannelHandleKey = stringPreferencesKey("accountChannelHandle")
val UseLoginForBrowse = booleanPreferencesKey("useLoginForBrowse")

val WebClientPoTokenEnabledKey = booleanPreferencesKey("webClientPoTokenEnabled")
val PoTokenGvsKey = stringPreferencesKey("poTokenGvs")
val PoTokenPlayerKey = stringPreferencesKey("poTokenPlayer")
val UseVisitorDataKey = booleanPreferencesKey("useVisitorData")
val PoTokenSourceUrlKey = stringPreferencesKey("poTokenSourceUrl")

val LanguageCodeToName =
    mapOf(
        "en" to "English (US)",
        "en-GB" to "English (UK)",
        "ja" to "日本語",
        "ko" to "한국어",
        "vi" to "Tiếng Việt",
        "zh" to "中文",
        "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
        "fr" to "Français",
        "de" to "Deutsch",
        "es" to "Español",
        "pt" to "Português",
        "pt-BR" to "Português (Brasil)",
        "ru" to "Русский",
        "it" to "Italiano",
        "nl" to "Nederlands",
        "pl" to "Polski",
        "tr" to "Türkçe",
        "ar" to "العربية",
        "hi" to "हिन्दी",
        "th" to "ไทย",
        "id" to "Bahasa Indonesia",
        "ms" to "Bahasa Melayu",
        "uk" to "Українська",
        "cs" to "Čeština",
        "el" to "Ελληνικά",
        "he" to "עברית",
        "hu" to "Magyar",
        "ro" to "Română",
        "fi" to "Suomi",
        "da" to "Dansk",
        "no" to "Norsk",
        "sv" to "Svenska",
        "sk" to "Slovenčina",
        "bg" to "Български",
        "hr" to "Hrvatski",
        "sr" to "Срpsки",
        "lt" to "Lietuvių",
        "lv" to "Latviešu",
        "et" to "Eesti",
    )

val CountryCodeToName =
    mapOf(
        "JP" to "Japan",
        "KR" to "South Korea",
        "US" to "United States",
        "GB" to "United Kingdom",
        "CN" to "China",
        "TW" to "Taiwan",
        "HK" to "Hong Kong",
        "FR" to "France",
        "DE" to "Germany",
        "ES" to "Spain",
        "MX" to "Mexico",
        "BR" to "Brazil",
        "RU" to "Russia",
        "IT" to "Italy",
        "NL" to "Netherlands",
        "PL" to "Poland",
        "TR" to "Turkey",
        "AU" to "Australia",
        "CA" to "Canada",
        "IN" to "India",
        "ID" to "Indonesia",
        "TH" to "Thailand",
        "VN" to "Vietnam",
        "PH" to "Philippines",
        "MY" to "Malaysia",
        "SG" to "Singapore",
        "AR" to "Argentina",
        "CL" to "Chile",
        "CO" to "Colombia",
        "PE" to "Peru",
        "ZA" to "South Africa",
        "EG" to "Egypt",
        "SA" to "Saudi Arabia",
        "AE" to "United Arab Emirates",
    )

// App rating / star prompt preferences
val LaunchCountKey = intPreferencesKey("launch_count")
val HasPressedStarKey = booleanPreferencesKey("has_pressed_star")
val RemindAfterKey = intPreferencesKey("remind_after")

// Update settings
val EnableUpdateNotificationKey = booleanPreferencesKey("enableUpdateNotification")
val UpdateChannelKey = stringPreferencesKey("updateChannel")
val LastUpdateCheckKey = longPreferencesKey("lastUpdateCheck")
val LastNotifiedVersionKey = stringPreferencesKey("lastNotifiedVersion")

val GitHubContributorsEtagKey = stringPreferencesKey("github_contributors_etag")
val GitHubContributorsJsonKey = stringPreferencesKey("github_contributors_json")
val GitHubContributorsLastCheckedAtKey = longPreferencesKey("github_contributors_last_checked_at")

val GitHubReleasesEtagKey = stringPreferencesKey("github_releases_etag")
val GitHubReleasesJsonKey = stringPreferencesKey("github_releases_json")
val GitHubReleasesLastCheckedAtKey = longPreferencesKey("github_releases_last_checked_at")
val GitHubReleasesFingerprintKey = stringPreferencesKey("github_releases_fingerprint")

val TogetherOnlineEndpointCacheKey = stringPreferencesKey("together_online_endpoint_cache")
val TogetherOnlineEndpointLastCheckedAtKey = longPreferencesKey("together_online_endpoint_last_checked_at")

enum class UpdateChannel {
    STABLE,
    NIGHTLY,
}


// Always On Display

enum class AodStyle {
    CLASSIC,
    BACKGROUND,
    MINIMAL,
    LARGE,
    SPOTLIGHT,
}

enum class AodArtShape {
    ROUNDED,
    CIRCLE,
    SQUIRCLE,
    DIAMOND,
    HEXAGON,
    STAR,
    ARCH,
    PETAL,
}

enum class AodControlStyle {
    ROUNDED,
    SQUARE,
    ACCENT,
    MINIMAL_FLAT,
}

val AodStyleKey = stringPreferencesKey("aod_style")
val AodArtShapeKey = stringPreferencesKey("aod_art_shape")

val AodDarknessKey = floatPreferencesKey("aod_darkness")
val AodArtSizeKey = floatPreferencesKey("aod_art_size")

val AodShowTitleKey = booleanPreferencesKey("aod_show_title")
val AodShowArtistKey = booleanPreferencesKey("aod_show_artist")
val AodShowTimeKey = booleanPreferencesKey("aod_show_time_labels")
val AodShowProgressKey = booleanPreferencesKey("aod_show_progress")
val AodShowControlsKey = booleanPreferencesKey("aod_show_controls")

// AOD Auto-activation timeout (seconds): 0 = never, 15, 30, 60, 120
val AodAutoActivationKey = intPreferencesKey("aod_auto_activation_seconds")

// AOD Fullscreen mode (hide system UI)
val AodFullscreenKey = booleanPreferencesKey("aod_fullscreen_mode")

/** Intensidad del spotlight (0.0 – 1.0) */
val AodSpotlightIntensityKey = floatPreferencesKey("aod_spotlight_intensity")

/** Pulso / breathing del spotlight */
val AodSpotlightPulseKey = booleanPreferencesKey("aod_spotlight_pulse")

/** Duración de las transiciones entre canciones en ms */
val AodTransitionDurationKey = intPreferencesKey("aod_transition_duration")

/** Estilo visual de los botones de control */
val AodControlStyleKey = stringPreferencesKey("aod_control_style")

/** Escala de texto global (0.8 – 1.4) */
val AodTextScaleKey = floatPreferencesKey("aod_text_scale")

/** Mostrar reloj del sistema en el AOD */
val AodShowClockKey = booleanPreferencesKey("aod_show_clock")

/** Formato de 24h para el reloj (true = 24h, false = 12h AM/PM) */
val AodClockFormatKey = booleanPreferencesKey("aod_clock_24h")

val LyricsLineBlurKey = booleanPreferencesKey("lyricsLineBlur")

val LyricsSyncOffsetKey = intPreferencesKey("lyrics_sync_offset")

// Video Wallpaper
val VideoWallpaperEnabledKey = booleanPreferencesKey("videoWallpaperEnabled")
val VideoWallpaperUriKey = stringPreferencesKey("videoWallpaperUri")
val SplashVideoEnabledKey = booleanPreferencesKey("splashVideoEnabled")

// Download lyrics
val EmbedLyricsOnDownloadKey = booleanPreferencesKey("embedLyricsOnDownload")

// Font selection
val FontFamilyKey = stringPreferencesKey("fontFamily")

// Spotify login & configurations
val SpotifyAccessTokenKey = stringPreferencesKey("spotifyAccessToken")
val SpotifySpDcKey = stringPreferencesKey("spotifySpDc")
val SpotifySpKeyKey = stringPreferencesKey("spotifySpKey")
val SpotifyTokenExpiryKey = longPreferencesKey("spotifyTokenExpiry")
val SpotifyUsernameKey = stringPreferencesKey("spotifyUsername")
val SpotifyUserIdKey = stringPreferencesKey("spotifyUserId")
val EnableSpotifyKey = booleanPreferencesKey("enableSpotify")
val SpotifySyncLikesKey = booleanPreferencesKey("spotifySyncLikes")
val UseSpotifySearchKey = booleanPreferencesKey("useSpotifySearch")
val UseSpotifyHomeKey = booleanPreferencesKey("useSpotifyHome")
val SpotifyHomeOnlyKey = booleanPreferencesKey("spotifyHomeOnly")
// When enabled, the Spotify preload also downloads each resolved song for offline playback.
val SpotifyPreloadDownloadKey = booleanPreferencesKey("spotifyPreloadDownload")
// true (default): Spotify liked songs are saved ONLY as a "Me gusta de Spotify" playlist.
// false: they are also inserted into the app's native Liked songs (may duplicate YT Music likes).
val SpotifyPreloadLikedAsPlaylistKey = booleanPreferencesKey("spotifyPreloadLikedAsPlaylist")

// Home welcome/orientation card for first-time users; true once the user dismisses it.
val WelcomeCardDismissedKey = booleanPreferencesKey("welcomeCardDismissed")


// Floating Bubble
val EnableBubbleKey = booleanPreferencesKey("enableBubble")

// Sleep timer & crossfade
val CrossfadeEnabledKey = booleanPreferencesKey("crossfadeEnabled")
val CrossfadeDurationKey = floatPreferencesKey("crossfadeDuration")
val CrossfadeGaplessKey = booleanPreferencesKey("crossfadeGapless")
val SleepTimerFadeOutKey = booleanPreferencesKey("sleepTimerFadeOut")

// Dezzy IA
val OpenRouterApiKey = stringPreferencesKey("openRouterApiKey")
val AiProviderKey = stringPreferencesKey("aiProvider")
val OpenRouterBaseUrlKey = stringPreferencesKey("openRouterBaseUrl")
val OpenRouterModelKey = stringPreferencesKey("openRouterModel")
val AiSystemPromptKey = stringPreferencesKey("aiSystemPrompt")

// AI Canvas (phase 2): let the LLM choose per-line emphasis words + song mood.
val CanvasAiEnhanceKey = booleanPreferencesKey("canvasAiEnhance")
val DeeplApiKey = stringPreferencesKey("deeplApiKey")
val DeeplFormalityKey = stringPreferencesKey("deeplFormality")
val AutoTranslateLyricsKey = booleanPreferencesKey("autoTranslateLyrics")
val AutoTranslateLyricsMismatchKey = booleanPreferencesKey("autoTranslateLyricsMismatch")
val TranslateLanguageKey = stringPreferencesKey("translateLanguage")
val TranslateModeKey = stringPreferencesKey("translateMode")


// Listen Together
val ListenTogetherServerUrlKey = stringPreferencesKey("listenTogetherServerUrl")
val ListenTogetherUsernameKey = stringPreferencesKey("listenTogetherUsername")
val EnableListenTogetherKey = booleanPreferencesKey("enableListenTogether")
val ListenTogetherAutoApprovalKey = booleanPreferencesKey("listenTogetherAutoApproval")
val ListenTogetherSyncVolumeKey = booleanPreferencesKey("listenTogetherSyncVolume")
val ListenTogetherBlockedUsersKey = stringPreferencesKey("listenTogetherBlockedUsers")
val ListenTogetherInTopBarKey = booleanPreferencesKey("listenTogetherInTopBar")
val ListenTogetherSessionTokenKey = stringPreferencesKey("listenTogetherSessionToken")
val ListenTogetherRoomCodeKey = stringPreferencesKey("listenTogetherRoomCode")
val ListenTogetherUserIdKey = stringPreferencesKey("listenTogetherUserId")
val ListenTogetherIsHostKey = booleanPreferencesKey("listenTogetherIsHost")
val ListenTogetherSessionTimestampKey = longPreferencesKey("listenTogetherSessionTimestamp")

// iPod Theme Mode
enum class IpodThemeMode {
    CLASSIC,
    DYNAMIC,
}
val IpodThemeModeKey = stringPreferencesKey("ipodThemeMode")

val ShowArtistVideoKey = booleanPreferencesKey("showArtistVideo")
val ShowArtistBackgroundVideoKey = booleanPreferencesKey("showArtistBackgroundVideo")

val KeepPlayerScreenAwakeKey = booleanPreferencesKey("keepPlayerScreenAwake")
val HomeInactivityFadeKey = booleanPreferencesKey("homeInactivityFade")

val FirstLaunchInitializedKey = booleanPreferencesKey("firstLaunchInitialized")

// One-time user guidance flags
/** Set after the "Chromecast is in beta" notice was acknowledged. */
val CastBetaNoticeShownKey = booleanPreferencesKey("castBetaNoticeShown")
/** Set after the one-time player tips dialog was dismissed. */
val PlayerTipsShownKey = booleanPreferencesKey("playerTipsShown")




