/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.dezzy.music

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.datastore.preferences.core.edit
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.crossfade
import com.dezzy.music.constants.*
import com.dezzy.music.extensions.*
import com.dezzy.music.ui.screens.settings.ThemePalettes
import com.dezzy.music.ui.screens.settings.DarkMode

import com.dezzy.music.ui.theme.ThemeSeedPalette
import com.dezzy.music.ui.theme.ThemeSeedPaletteCodec
import com.dezzy.music.utils.dataStore
import com.dezzy.music.utils.PreferenceStore
import com.dezzy.music.utils.get
import com.dezzy.music.utils.reportException
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.YouTubeLocale
import com.dezzy.music.kugou.KuGou
import com.dezzy.music.lastfm.LastFM
import com.dezzy.music.spotify.SpotifyTokenManager
import com.dezzy.music.ui.player.CanvasArtworkPlaybackCache
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import android.content.Intent
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess
import timber.log.Timber
import java.net.Proxy
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import com.dezzy.music.utils.OfflineArtworkInterceptor
import com.dezzy.music.utils.toArtworkHash
import dagger.hilt.android.EntryPointAccessors
import com.dezzy.music.di.DatabaseEntryPoint

@HiltAndroidApp
class App : Application(), SingletonImageLoader.Factory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var isInitialized = false
    private val didRunImageCacheTrim = AtomicBoolean(false)

    private fun currentProcessName(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val pid = android.os.Process.myPid()
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }
    }


    
    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        if (currentProcessName()?.endsWith(":crash") == true) {
            Timber.plant(Timber.DebugTree())
            return
        }
        PreferenceStore.start(this)
        com.dezzy.music.audio.DezzyEqManager.initAndLoad(this)
        // Logcat logging only in debug builds — in release every Timber call still paid
        // string formatting + a logcat write. GlobalLogTree stays: it feeds the in-app log viewer.
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        try {
            Timber.plant(com.dezzy.music.utils.GlobalLogTree())
        } catch (_: Exception) {}

        initializeCriticalSync()
        initializeDeferredAsync()
    }

    private fun initializeCriticalSync() {
        SpotifyTokenManager.init(dataStore)
        CanvasArtworkPlaybackCache.init(this)
        // Load the Spotify→YouTube match cache at startup (not only during preload) so the
        // Spotify Canvas can resolve a track's Spotify id in any session, not just the one
        // where the library was preloaded.
        com.dezzy.music.spotify.SpotifyMatchFileCache.init(this)
        // Bring up the genuine BotGuard poToken pipeline so playback is not blocked by
        // "Sign in to confirm you're not a bot". Safe to call before the deferred init runs.
        com.dezzy.music.playback.potoken.PlaybackPoTokenController.init(this, dataStore)

        val locale = Locale.getDefault()
        val languageTag = locale.toLanguageTag().replace("-Hant", "")
        YouTube.locale = YouTubeLocale(
            gl = locale.country.takeIf { it in CountryCodeToName } ?: "US",
            hl = locale.language.takeIf { it in LanguageCodeToName }
                ?: languageTag.takeIf { it in LanguageCodeToName }
                ?: "en"
        )
        if (languageTag == "zh-TW") {
            KuGou.useTraditionalChinese = true
        }
        LastFM.initialize(
            apiKey = BuildConfig.LASTFM_API_KEY,
            secret = BuildConfig.LASTFM_SECRET
        )
    }

    private fun initializeDeferredAsync() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                val prefs = dataStore.data.first()

                // Initialize default preferences on first launch
                val isFirstLaunchInitialized = prefs[FirstLaunchInitializedKey] ?: false
                if (!isFirstLaunchInitialized) {
                    dataStore.edit { settings ->
                        settings[FirstLaunchInitializedKey] = true
                        settings[DarkModeKey] = DarkMode.ON.name
                        settings[PureBlackKey] = true
                        settings[FontFamilyKey] = "LORA"
                        settings[KeepPlayerScreenAwakeKey] = true
                        settings[UseNewMiniPlayerDesignKey] = true
                        settings[DynamicMiniPlayerColorKey] = true
                        settings[PlayerDesignStyleKey] = PlayerDesignStyle.SPOTIFY.name
                        settings[PlayerBackgroundStyleKey] = PlayerBackgroundStyle.GLOW_ANIMATED.name
                        settings[MiniPlayerBackgroundStyleKey] = "LIVE_MESH"
                        settings[CanvasModeKey] = CanvasMode.APPLE_WITH_FALLBACK.name
                        settings[RotatingThumbnailKey] = true
                        settings[ShowCommentButtonKey] = true
                        settings[ShowCodecOnPlayerKey] = true
                        settings[SliderStyleKey] = SliderStyle.Waveform.name
                        settings[UseLyricsV2Key] = true
                        settings[LyricsV2StyleKey] = LyricsV2Style.CHROMATIC.name
                        settings[LyricsAnimationStyleKey] = LyricsAnimationStyle.APPLE.name
                        settings[AodAutoActivationKey] = 0
                        settings[ShowArtistVideoKey] = true
                        settings[AudioQualityKey] = AudioQuality.HIGHEST.name
                        settings[AutoDownloadOnLikeKey] = true
                    }
                }

                
                prefs[ContentCountryKey]?.takeIf { it != SYSTEM_DEFAULT }?.let { country ->
                    YouTube.locale = YouTube.locale.copy(gl = country)
                }
                prefs[ContentLanguageKey]?.takeIf { it != SYSTEM_DEFAULT }?.let { lang ->
                    YouTube.locale = YouTube.locale.copy(hl = lang)
                }
                
                LastFM.sessionKey = prefs[LastFMSessionKey]

                // Restore the native social-network session (Supabase JWT) on startup.
                com.dezzy.music.social.SupabaseAuth.load()
                // Keep the social identity in lockstep with the YouTube/Google login: links the
                // same Google account (headless when consent already exists, falling back to the
                // per-device anonymous identity) and signs the social session out when the user
                // logs out of YouTube.
                com.dezzy.music.social.GoogleIdentityBridge.startAutoSync(this@App, applicationScope)

                if (prefs[ProxyEnabledKey] == true) {
                    try {
                        YouTube.proxy = Proxy(
                            prefs[ProxyTypeKey].toEnum(defaultValue = Proxy.Type.HTTP),
                            prefs[ProxyUrlKey]!!.toInetSocketAddress()
                        )
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@App, "Failed to parse proxy url.", LENGTH_SHORT).show()
                        }
                        reportException(e)
                    }
                    YouTube.streamBypassProxy = prefs[StreamBypassProxyKey] == true
                }

                if (prefs[UseLoginForBrowse] != false) {
                    YouTube.useLoginForBrowse = true
                }
                
                // Apply random theme on startup if enabled
                if (prefs[RandomThemeOnStartupKey] == true) {
                    val randomPalette = ThemePalettes.generateRandomPalette()
                    val seedPalette = ThemeSeedPalette(
                        primary = randomPalette.primary,
                        secondary = randomPalette.secondary,
                        tertiary = randomPalette.tertiary,
                        neutral = randomPalette.neutral
                    )
                    val encodedPalette = ThemeSeedPaletteCodec.encodeForPreference(seedPalette, "Random")
                    dataStore.edit { settings ->
                        settings[CustomThemeColorKey] = encodedPalette
                    }
                }
                
                isInitialized = true
            } catch (e: Exception) {
                Timber.e(e, "Error during deferred initialization")
                reportException(e)
            }
        }

        migrateOfflineArtworks()

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[VisitorDataKey] }
                .distinctUntilChanged()
                .collect { visitorData ->
                    YouTube.visitorData = visitorData
                        ?.takeIf { it != "null" }
                        ?: YouTube.visitorData().onFailure {
                            reportException(it)
                        }.getOrNull()?.also { newVisitorData ->
                            dataStore.edit { settings ->
                                settings[VisitorDataKey] = newVisitorData
                            }
                        }
                }
        }

        try {
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val sw = StringWriter()
                    val pw = PrintWriter(sw)
                    throwable.printStackTrace(pw)
                    val stack = sw.toString()

                    val intent = Intent(this@App, DebugActivity::class.java).apply {
                        putExtra(DebugActivity.EXTRA_STACK_TRACE, stack)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    startActivity(intent)
                    try { Thread.sleep(100) } catch (_: InterruptedException) {}
                } catch (e: Exception) {
                    reportException(e)
                } finally {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(2)
                }
            }
        } catch (e: Exception) {
            reportException(e)
        }
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[DataSyncIdKey] }
                .distinctUntilChanged()
                .collect { dataSyncId ->
                    YouTube.dataSyncId = dataSyncId?.let {
                        it.takeIf { !it.contains("||") }
                            ?: it.takeIf { it.endsWith("||") }?.substringBefore("||")
                            ?: it.substringAfter("||")
                    }
                }
        }
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[InnerTubeCookieKey] }
                .distinctUntilChanged()
                .collect { cookie ->
                    try {
                        YouTube.cookie = cookie
                    } catch (e: Exception) {
                        Timber.e("Could not parse cookie. Clearing existing cookie. %s", e.message)
                        forgetAccount(this@App)
                    }
                }
        }
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[WebClientPoTokenEnabledKey] ?: false }
                .distinctUntilChanged()
                .collect { enabled ->
                    YouTube.webClientPoTokenEnabled = enabled
                }
        }
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[PoTokenKey] }
                .distinctUntilChanged()
                .collect { token ->
                    YouTube.poToken = token?.takeIf { it.isNotBlank() }
                }
        }
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[PoTokenGvsKey] }
                .distinctUntilChanged()
                .collect { token ->
                    YouTube.poTokenGvs = token?.takeIf { it.isNotBlank() }
                }
        }
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[PoTokenPlayerKey] }
                .distinctUntilChanged()
                .collect { token ->
                    YouTube.poTokenPlayer = token?.takeIf { it.isNotBlank() }
                }
        }
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[LastFMSessionKey] }
                .distinctUntilChanged()
                .collect { sessionKey ->
                    LastFM.sessionKey = sessionKey
                }
        }
    }

    private fun migrateOfflineArtworks() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                val artworkDir = java.io.File(filesDir, "artwork")
                if (!artworkDir.exists()) return@launch

                val database = EntryPointAccessors.fromApplication(
                    this@App, DatabaseEntryPoint::class.java
                ).musicDatabase()

                artworkDir.listFiles()?.forEach { file ->
                    val name = file.name
                    // Migration: Original files were saved as {songId}.jpg (usually length 11 + 4). MD5 is 32 + 4 = 36.
                    if (name.endsWith(".jpg") && name.length != 36) {
                        val songId = name.removeSuffix(".jpg")
                        val song = database.getSongById(songId)
                        if (song != null) {
                            val thumbnailUrl = song.song.thumbnailUrl
                            if (thumbnailUrl != null) {
                                val hash = thumbnailUrl.toArtworkHash()
                                val newFile = java.io.File(artworkDir, "$hash.jpg")
                                if (!newFile.exists()) {
                                    file.renameTo(newFile)
                                    Timber.tag("ArtworkMigration").d("Migrated artwork for $songId to hash $hash")
                                } else {
                                    file.delete()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag("ArtworkMigration").e(e, "Failed to migrate old artwork files")
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val smartTrimmer = dataStore[SmartTrimmerKey] ?: false
        val imageCacheConfig = resolveImageDiskCacheConfig(dataStore[MaxImageCacheSizeKey])

        val diskCache = DiskCache.Builder()
            .directory(cacheDir.resolve("coil"))
            .maxSizeBytes(imageCacheConfig.maxSizeBytes)
            .build()

        if (smartTrimmer && imageCacheConfig.policy == CachePolicy.ENABLED && didRunImageCacheTrim.compareAndSet(false, true)) {
            applicationScope.launch(Dispatchers.IO) { trimImageDiskCache(diskCache) }
        }

        val maxHeapBytes = Runtime.getRuntime().maxMemory()
        val memoryCacheSize = (maxHeapBytes * 0.15).toLong()

        return ImageLoader.Builder(this)
            .components {
                add(OfflineArtworkInterceptor(this@App))
            }
            .crossfade(true)
            .allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            .memoryCache {
                coil3.memory.MemoryCache.Builder()
                    .maxSizeBytes(memoryCacheSize)
                    .build()
            }
            .diskCache(diskCache)
            .diskCachePolicy(imageCacheConfig.policy)
            .build()
    }

    private fun trimImageDiskCache(diskCache: DiskCache) {
        try {
            val limitBytes = diskCache.maxSize
            if (limitBytes <= 0L || limitBytes == Long.MAX_VALUE) return

            val dir = java.io.File(diskCache.directory.toString())
            if (!dir.exists()) return

            val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
            var currentSize = files.sumOf { it.length() }
            if (currentSize <= limitBytes) return

            for (file in files) {
                if (currentSize <= limitBytes) break
                val size = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) currentSize -= size
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        lateinit var instance: App
            private set

        fun forgetAccount(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                context.dataStore.edit { settings ->
                    settings.remove(InnerTubeCookieKey)
                    settings.remove(PoTokenKey)
                    settings.remove(VisitorDataKey)
                    settings.remove(DataSyncIdKey)
                    settings.remove(AccountNameKey)
                    settings.remove(AccountEmailKey)
                    settings.remove(AccountChannelHandleKey)
                }
            }
        }
    }
}

internal data class ImageDiskCacheConfig(
    val policy: CachePolicy,
    val maxSizeBytes: Long,
)

internal fun resolveImageDiskCacheConfig(maxImageCacheSizeMb: Int?): ImageDiskCacheConfig {
    val sizeMb = maxImageCacheSizeMb ?: -1
    if (sizeMb == 0) return ImageDiskCacheConfig(policy = CachePolicy.DISABLED, maxSizeBytes = 1L)
    if (sizeMb < 0) return ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = Long.MAX_VALUE)
    val bytesPerMb = 1024L * 1024L
    val safeSizeMb = sizeMb.toLong().coerceAtMost(Long.MAX_VALUE / bytesPerMb)
    return ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = safeSizeMb * bytesPerMb)
}
