/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



@file:Suppress("DEPRECATION")

package com.dezzy.music.playback

import android.app.PendingIntent
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothClass
import android.content.pm.PackageManager
import android.database.SQLException
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.MediaCodecList
import android.media.audiofx.Virtualizer
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Binder
import android.os.PowerManager
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.wav.WavExtractor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.lyrics.LyricsPreloadManager
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.MainActivity
import com.dezzy.music.R
import com.dezzy.music.constants.AudioNormalizationKey
import com.dezzy.music.constants.AudioOffload
import com.dezzy.music.constants.AudioCrossfadeDurationKey
import com.dezzy.music.constants.AudioQualityKey
import com.dezzy.music.constants.AutoLoadMoreKey
import com.dezzy.music.constants.AutoDownloadOnLikeKey
import com.dezzy.music.constants.AutoSkipNextOnErrorKey
import com.dezzy.music.constants.AutoStartOnBluetoothKey
import com.dezzy.music.constants.InnerTubeCookieKey
import com.dezzy.music.constants.DiscordTokenKey
import com.dezzy.music.constants.EqualizerBandLevelsMbKey
import com.dezzy.music.constants.EqualizerBassBoostEnabledKey
import com.dezzy.music.constants.EqualizerBassBoostStrengthKey
import com.dezzy.music.constants.EqualizerEnabledKey
import com.dezzy.music.constants.EqualizerAutoGenreKey
import com.dezzy.music.constants.EqualizerDetectedGenreKey
import com.dezzy.music.constants.EqualizerOutputGainEnabledKey
import com.dezzy.music.constants.EqualizerOutputGainMbKey
import com.dezzy.music.constants.EqualizerSelectedProfileIdKey
import com.dezzy.music.constants.EqualizerVirtualizerEnabledKey
import com.dezzy.music.constants.EqualizerVirtualizerStrengthKey
import com.dezzy.music.constants.EnableDiscordRPCKey
import com.dezzy.music.constants.HideExplicitKey
import com.dezzy.music.constants.HideVideoKey
import com.dezzy.music.constants.HistoryDuration
import com.dezzy.music.constants.MediaSessionConstants.CommandToggleLike
import com.dezzy.music.constants.MediaSessionConstants.CommandToggleStartRadio
import com.dezzy.music.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.dezzy.music.constants.MediaSessionConstants.CommandToggleShuffle
import com.dezzy.music.constants.PauseListenHistoryKey
import com.dezzy.music.constants.PauseOnDeviceMuteKey
import com.dezzy.music.constants.PermanentShuffleKey
import com.dezzy.music.constants.PersistentQueueKey
import com.dezzy.music.constants.PlayerStreamClient
import com.dezzy.music.constants.PlayerStreamClientKey
import com.dezzy.music.constants.PlayerVolumeKey
import com.dezzy.music.constants.RepeatModeKey
import com.dezzy.music.constants.ShowLyricsKey
import com.dezzy.music.constants.SkipSilenceKey
import com.dezzy.music.constants.MaxSongCacheSizeKey
import com.dezzy.music.constants.SmartTrimmerKey
import com.dezzy.music.constants.StopMusicOnTaskClearKey
import com.dezzy.music.constants.WakelockKey
import com.dezzy.music.constants.YtmSyncKey
import com.dezzy.music.db.MusicDatabase
import com.dezzy.music.db.entities.Event
import com.dezzy.music.db.entities.FormatEntity
import com.dezzy.music.db.entities.LyricsEntity
import com.dezzy.music.db.entities.RelatedSongMap
import com.dezzy.music.db.entities.Song
import com.dezzy.music.db.entities.SongEntity
import com.dezzy.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.dezzy.music.db.entities.ArtistEntity
import com.dezzy.music.db.entities.AlbumEntity
import com.dezzy.music.di.DownloadCache
import com.dezzy.music.di.PlayerCache
import com.dezzy.music.extensions.SilentHandler
import com.dezzy.music.extensions.collect
import com.dezzy.music.extensions.collectLatest
import com.dezzy.music.extensions.currentMetadata
import com.dezzy.music.extensions.directorySizeBytes
import com.dezzy.music.extensions.findNextMediaItemById
import com.dezzy.music.extensions.mediaItems
import com.dezzy.music.extensions.metadata
import com.dezzy.music.extensions.setOffloadEnabled
import com.dezzy.music.extensions.toMediaItem
import com.dezzy.music.extensions.toPersistQueue
import com.dezzy.music.extensions.toQueue
import com.dezzy.music.lyrics.LyricsHelper
import com.dezzy.music.models.PersistQueue
import com.dezzy.music.models.PersistPlayerState
import com.dezzy.music.models.toMediaMetadata
import com.dezzy.music.playback.queues.EmptyQueue
import com.dezzy.music.playback.queues.Queue
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.playback.queues.filterExplicit
import com.dezzy.music.playback.queues.filterVideo
import com.dezzy.music.utils.CoilBitmapLoader
import com.dezzy.music.utils.GenreClassifier
import com.dezzy.music.social.PresencePublisher
import com.dezzy.music.widget.MusicWidgetProvider
import com.dezzy.music.utils.DiscordRPC
import com.dezzy.music.ui.screens.settings.DiscordPresenceManager
import com.dezzy.music.utils.SyncUtils
import com.dezzy.music.utils.YTPlayerUtils
import com.dezzy.music.utils.StreamClientUtils
import com.dezzy.music.utils.dataStore
import com.dezzy.music.utils.enumPreference
import com.dezzy.music.utils.get
import com.dezzy.music.utils.getAsync
import com.dezzy.music.utils.getPresenceIntervalMillis
import com.dezzy.music.utils.reportException
import com.dezzy.music.utils.NetworkConnectivityObserver
import dagger.hilt.android.AndroidEntryPoint
import com.dezzy.music.ui.screens.settings.ListenBrainzManager
import com.dezzy.music.constants.ListenBrainzEnabledKey
import com.dezzy.music.constants.ListenBrainzTokenKey
import com.dezzy.music.constants.OmegaMathUserIdKey
import com.dezzy.music.constants.MusicMapShareLocationKey
import com.dezzy.music.constants.AccountNameKey
import com.dezzy.music.lastfm.LastFM
import com.dezzy.music.constants.EnableLastFMScrobblingKey
import com.dezzy.music.constants.LastFMUseNowPlaying
import com.dezzy.music.constants.ScrobbleDelayPercentKey
import com.dezzy.music.constants.ScrobbleMinSongDurationKey
import com.dezzy.music.constants.ScrobbleDelaySecondsKey
import com.dezzy.music.constants.TogetherClientIdKey
import com.dezzy.music.constants.EnableBubbleKey
import coil3.imageLoader
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds
import timber.log.Timber
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.os.Build
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat

/** Prefijo de mediaId para reproducción de streams lossless (FLAC del resolver). */
const val FLAC_STREAM_PREFIX = "flacstream_"

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@AndroidEntryPoint
class MusicService :
    MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isBubbleEnabled = false
    private var lastAudioFocusState = AudioManager.AUDIOFOCUS_NONE
    private var wasPlayingBeforeAudioFocusLoss = false
    private var pendingTransientPauseJob: Job? = null
    private var pauseOnDeviceMuteEnabled = false
    private var wasAutoPausedByDeviceMute = false
    private var hasAudioFocus = false
    private var autoStartOnBluetoothEnabled = false
    private var bluetoothReceiverRegistered = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wakelockEnabled = true

    private var scopeJob = Job()
    private var scope = CoroutineScope(Dispatchers.Main + scopeJob)
    private var ioScope = CoroutineScope(Dispatchers.IO + scopeJob)
    private val binder = MusicBinder()
    private var hasBoundClients = false
    private var idleStopJob: Job? = null
    private var networkRetryJob: Job? = null
    private var networkRetryAttempt = 0

    private lateinit var connectivityManager: ConnectivityManager
    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(false)

    private val audioQuality by enumPreference(
        this,
        AudioQualityKey,
        com.dezzy.music.constants.AudioQuality.AUTO
    )
    private val preferredStreamClient by enumPreference(
        this,
        PlayerStreamClientKey,
        PlayerStreamClient.ANDROID_VR
    )
    private val playbackUrlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val streamRecoveryState = ConcurrentHashMap<String, Pair<Int, Long>>()
    @Volatile
    private var pendingStreamRefreshValidationMediaId: String? = null
    @Volatile
    private var refreshValidatedPlayingMediaId: String? = null
    private val avoidStreamCodecs: Set<String> by lazy {
        if (deviceSupportsMimeType("audio/opus")) emptySet() else setOf("opus")
    }
    private val mediaOkHttpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .proxy(YouTube.streamProxy)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val isYouTubeMediaHost =
                    host.endsWith("googlevideo.com") ||
                        host.endsWith("googleusercontent.com") ||
                        host.endsWith("youtube.com") ||
                        host.endsWith("youtube-nocookie.com") ||
                        host.endsWith("ytimg.com")

                if (!isYouTubeMediaHost) return@addInterceptor chain.proceed(request)

                val clientParam = request.url.queryParameter("c")?.trim().orEmpty()

                val userAgent = StreamClientUtils.resolveUserAgent(clientParam)
                val originReferer = StreamClientUtils.resolveOriginReferer(clientParam)

                val builder = request.newBuilder().header("User-Agent", userAgent)
                originReferer.origin?.let { builder.header("Origin", it) }
                originReferer.referer?.let { builder.header("Referer", it) }

                chain.proceed(builder.build())
            }.build()
    }

    private var currentQueue: Queue = EmptyQueue
    var queueTitle: String? = null
    private val persistentStateLock = Any()
    @Volatile
    private var suppressAutoPlayback = false
    private var lastPresenceToken: String? = null
    @Volatile
    private var lastPresenceUpdateTime = 0L
    @Volatile
    private var lastLoginRecoveryPrompt: Pair<String, Long>? = null

    val currentMediaMetadata = MutableStateFlow<com.dezzy.music.models.MediaMetadata?>(null)
    val queueRestoreCompleted = MutableStateFlow(false)
    private val currentSong =
        currentMediaMetadata
            .flatMapLatest { mediaMetadata ->
                database.song(mediaMetadata?.id)
            }.flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.Lazily, null)
    private val currentFormat =
        currentMediaMetadata.flatMapLatest { mediaMetadata ->
            database.format(mediaMetadata?.id)
        }.flowOn(Dispatchers.IO)

    private val normalizeFactor = MutableStateFlow(1f)
    var playerVolume = MutableStateFlow(1f)
    private val audioFocusVolumeFactor = MutableStateFlow(1f)
    private val playbackFadeFactor = MutableStateFlow(1f)
    private val crossfadeDurationMs = MutableStateFlow(0)
    private val audioNormalizationEnabled = MutableStateFlow(true)
    private var crossfadeAudio: CrossfadeAudio? = null

    /** mediaId ("flacstream_…") → URL de stream FLAC del resolver, para reproducción lossless. */
    val flacStreamUrls = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var lyricsPreloadManager: LyricsPreloadManager? = null

    // ---- Music video playback ("watch the video" mode) ----
    // Deliberately a second, standalone ExoPlayer instead of swapping the main player's stream:
    // the main player's ResolvingDataSource/SimpleCache pipeline is heavily tuned for audio-only
    // playback (bot detection, stream recovery, chunked caching keyed by mediaId) and reusing it
    // for a muxed video URL under the same cache key would corrupt cached bytes when switching
    // modes. A second isolated player can't touch any of that no matter what goes wrong.
    private var videoPlayer: ExoPlayer? = null
    private var videoModeMediaId: String? = null

    // Al tocar un "video similar" en modo video, la cola salta a esa canción y,
    // cuando la transición llega a este id, se re-activa el modo video solo.
    var pendingVideoModeMediaId: String? = null
    val videoModeActive = MutableStateFlow(false)
    val videoModeLoading = MutableStateFlow(false)
    val videoModeError = MutableStateFlow<String?>(null)
    val videoPlayerInstance = MutableStateFlow<ExoPlayer?>(null)

    /** Resolves a muxed video stream for the current song and starts the standalone video player. */
    fun enableVideoMode() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (videoModeLoading.value) return
        if (videoModeActive.value && videoModeMediaId == mediaId) return
        videoModeError.value = null
        videoModeLoading.value = true
        val resumePositionMs = player.currentPosition
        val wasPlaying = player.playWhenReady
        // Canción local importada: si el archivo trae pista de video (MP4),
        // se reproduce el propio archivo — no hay nada que resolver en YouTube.
        if (com.dezzy.music.utils.LocalMusicImporter.isLocalId(mediaId)) {
            scope.launch {
                val file = com.dezzy.music.utils.LocalMusicImporter.audioFileFor(this@MusicService, mediaId)
                val hasVideo = file != null && withContext(Dispatchers.IO) {
                    com.dezzy.music.utils.LocalMusicImporter.hasVideoTrack(file)
                }
                if (file != null && hasVideo) {
                    videoModeMediaId = mediaId
                    videoModeActive.value = true
                    videoModeLoading.value = false
                    val finalPositionMs = player.currentPosition
                    val finalWasPlaying = player.playWhenReady
                    startVideoPlayer(android.net.Uri.fromFile(file).toString(), null, finalPositionMs, finalWasPlaying)
                } else {
                    videoModeLoading.value = false
                    videoModeError.value = "Esta canción importada no tiene pista de video"
                }
            }
            return
        }
        scope.launch {
            val maxHeight = runCatching {
                com.dezzy.music.constants.VideoQuality.valueOf(
                    dataStore.get(
                        com.dezzy.music.constants.VideoMaxQualityKey,
                        com.dezzy.music.constants.VideoQuality.P720.name,
                    ),
                )
            }.getOrDefault(com.dezzy.music.constants.VideoQuality.P720).maxHeight
            val currentTitle = player.mediaMetadata.title?.toString()
            val currentArtist = player.mediaMetadata.artist?.toString()
            val result = withContext(Dispatchers.IO) {
                val isValidUrl: suspend (String) -> Boolean = { url ->
                    try {
                        val request = okhttp3.Request.Builder().url(url).get().header("Range", "bytes=0-0").build()
                        val response = mediaOkHttpClient.newCall(request).execute()
                        val isSuccess = response.isSuccessful
                        android.util.Log.e("MusicService", "isValidUrl check: HTTP ${response.code} for URL ${url.take(60)}... success=$isSuccess")
                        response.close()
                        isSuccess
                    } catch (e: Exception) {
                        android.util.Log.e("MusicService", "isValidUrl exception: ${e.message}")
                        false
                    }
                }
                val direct = YTPlayerUtils.videoPlaybackDataForPlayback(mediaId, maxHeight = maxHeight, isValidUrl = isValidUrl)
                if (direct.isSuccess) {
                    direct
                } else {
                    val query = if (!currentTitle.isNullOrBlank()) "$currentTitle $currentArtist".trim() else null
                    val searchVidId = if (!query.isNullOrBlank()) {
                        runCatching {
                            val searchRes = com.dezzy.music.innertube.YouTube.search(
                                query = query,
                                filter = com.dezzy.music.innertube.YouTube.SearchFilter.FILTER_VIDEO
                            ).getOrNull()
                            searchRes?.items?.firstOrNull()?.id
                        }.getOrNull()
                    } else null

                    if (!searchVidId.isNullOrBlank()) {
                        YTPlayerUtils.videoPlaybackDataForPlayback(searchVidId, maxHeight = maxHeight, isValidUrl = isValidUrl)
                    } else {
                        direct
                    }
                }
            }
            videoModeLoading.value = false
            result.onSuccess { data ->
                // The song changed while we were resolving — don't attach a stale video.
                if (player.currentMediaItem?.mediaId != mediaId) return@onSuccess
                val finalPositionMs = player.currentPosition
                val finalWasPlaying = player.playWhenReady
                val vidUrl = data.streamUrl ?: data.adaptiveVideoUrl ?: return@onSuccess
                startVideoPlayer(vidUrl, data.adaptiveAudioUrl, finalPositionMs, finalWasPlaying)
                videoModeMediaId = mediaId
                videoModeActive.value = true
            }.onFailure { e ->
                videoModeError.value = "No hay video musical disponible para esta canción."
                reportException(e)
            }
        }
    }

    /**
     * "Compromete" cualquier crossfade en curso antes de una navegación manual, para
     * que el player quede en la pista que realmente suena y next/prev/shuffle no
     * queden desfasados (bug de "suena la canción anterior"). Seguro de llamar
     * siempre; si no hay crossfade activo, no hace nada perceptible.
     */
    fun commitCrossfadeForManualNav() {
        crossfadeAudio?.commitCrossfade()
    }

    /**
     * Punto único para "siguiente" manual. Todas las rutas (UI, notificación,
     * Bluetooth/Android Auto, burbuja, Listen Together) deben pasar por aquí:
     *
     * - Compromete el crossfade en curso; si no, el ExoPlayer ya avanzó internamente
     *   a la pista N+1 mientras el oído sigue en la N, y el salto "repite" la canción.
     * - Ignora REPEAT_MODE_ONE: un skip explícito del usuario siempre debe avanzar
     *   (Timeline.getNextWindowIndex devuelve el mismo índice en repeat-one).
     */
    fun skipToNextManual() = navigateManual(forward = true)

    /** Contraparte de [skipToNextManual] para "anterior". */
    fun skipToPreviousManual() = navigateManual(forward = false)

    /**
     * Navegación manual entre pistas, con UN SOLO cambio de índice.
     *
     * La versión anterior hacía dos: primero `commitCrossfade()` (que a su vez hace
     * `seekTo` al objetivo del fundido) y después `seekToNext()`. Encadenar ambos
     * dejaba la navegación en una pista equivocada y producía el salto que
     * retrocedía al pulsar "siguiente" desde la notificación o el Bluetooth.
     *
     * Ahora se calcula el destino una vez, partiendo del índice que realmente se
     * está OYENDO (que durante un fundido no es el del player), se aborta el
     * fundido sin mover nada, y se salta directo.
     */
    private fun navigateManual(forward: Boolean) {
        val count = player.mediaItemCount
        if (count == 0) return

        // Punto de partida real: durante un crossfade el audio va una pista por
        // delante del índice del player.
        val audible = crossfadeAudio?.audiblyPlayingIndex() ?: C.INDEX_UNSET
        val from = if (audible != C.INDEX_UNSET) audible else player.currentMediaItemIndex

        val repeatModeForSkip = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else player.repeatMode
        val timeline = player.currentTimeline

        val target = when {
            forward -> timeline.getNextWindowIndex(from, repeatModeForSkip, player.shuffleModeEnabled)
                .let { if (it == androidx.media3.common.C.INDEX_UNSET) return else it }
            // "Anterior" reinicia la pista si ya avanzó lo suficiente, como todos
            // los reproductores; si no, va a la anterior.
            player.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS && audible == androidx.media3.common.C.INDEX_UNSET -> from
            else -> timeline.getPreviousWindowIndex(from, repeatModeForSkip, player.shuffleModeEnabled)
                .let { if (it == androidx.media3.common.C.INDEX_UNSET) from else it }
        }

        val before = player.currentMediaItemIndex
        crossfadeAudio?.abortCrossfade()
        player.seekTo(target, 0L)
        player.prepare()
        player.playWhenReady = true

        Timber.tag("SkipDebug").i(
            "%s from=%d (idx=%d, audible=%d) -> %d  repeat=%d count=%d",
            if (forward) "NEXT" else "PREV",
            from, before, audible, target, player.repeatMode, count,
        )
    }

    /** Stops the video player and resumes normal audio playback at the same position. */
    fun disableVideoMode() {
        if (!videoModeActive.value && videoPlayer == null) return
        val vp = videoPlayer
        val positionMs = vp?.currentPosition ?: player.currentPosition
        val wasPlaying = vp?.playWhenReady ?: player.playWhenReady
        stopVideoPlayerInternal()
        videoModeActive.value = false
        videoModeMediaId = null
        if (player.currentMediaItem != null) {
            player.seekTo(positionMs)
            player.playWhenReady = wasPlaying
        }
    }

    private fun startVideoPlayer(videoUrl: String, audioUrl: String?, positionMs: Long, autoPlay: Boolean) {
        stopVideoPlayerInternal()
        player.pause()
        
        val dataSourceFactory = createCacheDataSource()
        
        videoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory))
            .build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    videoModeError.value = "Error al reproducir el video: ${error.errorCodeName}"
                }
            })
            
            if (audioUrl != null) {
                val factory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                val videoSource = factory.createMediaSource(MediaItem.fromUri(videoUrl))
                
                val audioSource = factory.createMediaSource(MediaItem.fromUri(audioUrl))
                
                setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(true, videoSource, audioSource))
            } else {
                setMediaItem(MediaItem.fromUri(videoUrl))
            }
            
            prepare()
            seekTo(positionMs)
            playWhenReady = autoPlay
        }
        videoPlayerInstance.value = videoPlayer
    }

    private fun stopVideoPlayerInternal() {
        videoPlayer?.let {
            it.stop()
            it.release()
        }
        videoPlayer = null
        videoPlayerInstance.value = null
    }

    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        return appProcesses.any { processInfo ->
            processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                processInfo.processName == packageName
        }
    }

    private fun promptLoginRecovery(mediaId: String, targetUrl: String) {
        if (!isAppInForeground()) return

        val now = System.currentTimeMillis()
        val lastPrompt = lastLoginRecoveryPrompt
        if (lastPrompt?.first == mediaId && now - lastPrompt.second < 10000L) return
        lastLoginRecoveryPrompt = mediaId to now

        val deepLink = Uri.parse("Dezzy://login?url=${Uri.encode(targetUrl)}")
        val intent = Intent(Intent.ACTION_VIEW, deepLink, this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        runCatching {
            startActivity(intent)
        }.onFailure {
            Timber.e(it, "Failed to open login recovery for %s", mediaId)
        }
    }

    lateinit var sleepTimer: SleepTimer

    @Inject
    @PlayerCache
    lateinit var playerCache: Cache

    @Inject
    @DownloadCache
    lateinit var downloadCache: Cache

    lateinit var player: ExoPlayer
    /** Thread-safe snapshot of player.repeatMode for use from loader/IO threads. */
    @Volatile var cachedRepeatMode: Int = Player.REPEAT_MODE_OFF
    private lateinit var mediaSession: MediaLibrarySession
    @Volatile private var currentPlayingMediaId: String? = null

    private val castController: com.dezzy.music.playback.cast.CastSessionController by lazy {
        com.dezzy.music.playback.cast.CastSessionController(
            this,
            scope,
            resolveStream = { mediaId -> resolveCastStream(mediaId) },
            resolveLyrics = { mediaId ->
                database.lyrics(mediaId).first()?.lyrics
                    ?.takeIf { it.isNotBlank() && it != LYRICS_NOT_FOUND }
            },
            resolveUiPrefs = { buildCastUiPrefsJson() },
            resolvePalette = { thumbnailUrl -> buildCastPaletteJson(thumbnailUrl) },
        )
    }

    /**
     * Two dominant artwork colors as `[[r,g,b],[r,g,b]]` for the Cast receiver's gradient.
     * Extracted on the phone (Coil + Palette) because the web receiver usually can't read the
     * artwork pixels (cross-origin canvas taint). Returns null when the art can't be decoded,
     * in which case the receiver falls back to its own extraction / generic gradient.
     */
    private suspend fun buildCastPaletteJson(thumbnailUrl: String?): String? {
        val url = thumbnailUrl ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = coil3.request.ImageRequest.Builder(this@MusicService)
                    .data(url)
                    .size(100, 100)
                    .allowHardware(false)
                    .build()
                val bitmap = this@MusicService.imageLoader.execute(request)
                    .image?.toBitmap() ?: return@runCatching null
                val palette = androidx.palette.graphics.Palette.from(bitmap)
                    .maximumColorCount(8).generate()
                val primary = palette.getVibrantColor(
                    palette.getDominantColor(android.graphics.Color.rgb(50, 25, 40))
                )
                val secondary = palette.getDarkVibrantColor(
                    palette.getMutedColor(
                        palette.getLightVibrantColor(
                            android.graphics.Color.rgb(
                                (android.graphics.Color.red(primary) * 0.7f).toInt(),
                                (android.graphics.Color.green(primary) * 0.7f).toInt(),
                                (android.graphics.Color.blue(primary) * 0.7f).toInt(),
                            )
                        )
                    )
                )
                fun rgbArray(c: Int) = org.json.JSONArray().apply {
                    put(android.graphics.Color.red(c))
                    put(android.graphics.Color.green(c))
                    put(android.graphics.Color.blue(c))
                }
                org.json.JSONArray().apply {
                    put(rgbArray(primary))
                    put(rgbArray(secondary))
                }.toString()
            }.getOrNull()
        }
    }

    /** Appearance prefs the web receiver mirrors: lyrics align/style, background, slider. */
    private suspend fun buildCastUiPrefsJson(): String {
        val prefs = dataStore.data.first()
        val rawAlign = prefs[com.dezzy.music.constants.LyricsTextPositionKey]
        // On widescreen TV (Chromecast), LEFT alignment leaves a massive dark void on the right half.
        // We map LEFT/null to CENTER for a balanced TV presentation unless explicitly set to RIGHT.
        val tvAlign = if (rawAlign == "RIGHT") "RIGHT" else "CENTER"
        return org.json.JSONObject().apply {
            put("lyricsAlign", tvAlign)
            put("lyricsV2", prefs[com.dezzy.music.constants.UseLyricsV2Key] ?: false)
            put("lyricsV2Style", prefs[com.dezzy.music.constants.LyricsV2StyleKey] ?: "LIQUID_FLOW")
            put("background", prefs[com.dezzy.music.constants.PlayerBackgroundStyleKey] ?: "DEFAULT")
            put("slider", prefs[com.dezzy.music.constants.SliderStyleKey] ?: "Aurora")
        }.toString()
    }
    val castConnected get() = castController.isConnected
    val castDeviceName get() = castController.deviceName

    private suspend fun resolveCastStream(
        mediaId: String,
    ): com.dezzy.music.playback.cast.CastSessionController.ResolvedStream? {
        // Opt-in video casting: send the muxed music-video stream so the receiver
        // shows the actual video. Falls back to the audio path when the song has
        // no video or resolution fails.
        if (dataStore.get(com.dezzy.music.constants.CastVideoKey, false)) {
            val maxHeight = runCatching {
                com.dezzy.music.constants.VideoQuality.valueOf(
                    dataStore.get(
                        com.dezzy.music.constants.VideoMaxQualityKey,
                        com.dezzy.music.constants.VideoQuality.P720.name,
                    ),
                )
            }.getOrDefault(com.dezzy.music.constants.VideoQuality.P720).maxHeight
            YTPlayerUtils.videoPlaybackDataForPlayback(mediaId, maxHeight = maxHeight, isValidUrl = { url ->
                try {
                    val request = okhttp3.Request.Builder().url(url).get().header("Range", "bytes=0-0").build()
                    val response = mediaOkHttpClient.newCall(request).execute()
                    response.use { it.isSuccessful }
                } catch (e: Exception) {
                    false
                }
            })
                .getOrNull()
                ?.let { video ->
                    video.streamUrl?.let { url ->
                        return com.dezzy.music.playback.cast.CastSessionController.ResolvedStream(
                            url,
                            "video/mp4",
                        )
                    }
                }
        }

        val cached = playbackUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }
        if (cached != null) {
            val mimeType = database.format(mediaId).first()?.mimeType
            return com.dezzy.music.playback.cast.CastSessionController.ResolvedStream(cached.first, mimeType)
        }
        val result = YTPlayerUtils.playerResponseForPlayback(
            mediaId,
            audioQuality = audioQuality,
            connectivityManager = connectivityManager,
            preferredStreamClient = preferredStreamClient,
            avoidCodecs = avoidStreamCodecs,
            isCancelled = { false },
        ).getOrNull() ?: return null
        playbackUrlCache[mediaId] =
            result.streamUrl to System.currentTimeMillis() + (result.streamExpiresInSeconds * 1000L)
        val mimeType = runCatching { result.format.mimeType.split(";")[0] }.getOrNull()
        return com.dezzy.music.playback.cast.CastSessionController.ResolvedStream(result.streamUrl, mimeType)
    }

    fun stopCasting() = castController.stopCasting()

    private var isAudioEffectSessionOpened = false
    private var openedAudioSessionId: Int? = null
    val eqCapabilities = MutableStateFlow<EqCapabilities?>(null)
    private var genreEqJob: Job? = null
    private var widgetUpdateJob: Job? = null
    private val desiredEqSettings =
        MutableStateFlow(
            EqSettings(
                enabled = false,
                bandLevelsMb = emptyList(),
                outputGainEnabled = false,
                outputGainMb = 0,
                bassBoostEnabled = false,
                bassBoostStrength = 0,
                virtualizerEnabled = false,
                virtualizerStrength = 0,
            ),
        )

    private var audioEffectsSessionId: Int? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private var discordRpc: DiscordRPC? = null
    private var lastDiscordUpdateTime = 0L

    private var scrobbleManager: com.dezzy.music.utils.ScrobbleManager? = null

    val automixItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val automixLoading = MutableStateFlow(false)
    val automixError = MutableStateFlow<String?>(null)
    private var automixJob: Job? = null
    private var automixSeedMediaId: String? = null

    val autoAddedMediaIds: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    private var consecutivePlaybackErr = 0

    val maxSafeGainFactor = 1.414f // +3 dB
    @Volatile
    private var hasCalledStartForeground = false

    val togetherSessionState = MutableStateFlow<com.dezzy.music.together.TogetherSessionState>(
        com.dezzy.music.together.TogetherSessionState.Idle,
    )
    private var togetherServer: com.dezzy.music.together.TogetherServer? = null
    private var togetherOnlineHost: com.dezzy.music.together.TogetherOnlineHost? = null
    private var togetherClient: com.dezzy.music.together.TogetherClient? = null
    private var togetherBroadcastJob: Job? = null
    private var togetherOnlineConnectJob: Job? = null
    private var togetherClientEventsJob: Job? = null
    private var togetherHeartbeatJob: Job? = null
    private var togetherClock: com.dezzy.music.together.TogetherClock? = null
    private var togetherSelfParticipantId: String? = null
    private var togetherLastAppliedQueueHash: String? = null
    private var togetherIsOnlineSession: Boolean = false
    @Volatile
    private var togetherApplyingRemote: Boolean = false
    @Volatile
    private var togetherSuppressEchoUntilElapsedMs: Long = 0L
    @Volatile
    private var togetherLastAppliedRoomStateSentAtElapsedMs: Long = 0L
    @Volatile
    private var togetherLastRemoteAppliedPlayWhenReady: Boolean? = null
    @Volatile
    private var togetherLastRemoteAppliedIndex: Int = -1
    @Volatile
    private var togetherLastSentControlAtElapsedMs: Long = 0L
    @Volatile
    private var togetherLastSentControlAction: com.dezzy.music.together.ControlAction? = null
    @Volatile
    private var togetherPendingGuestControl: TogetherPendingGuestControl? = null

    private fun isTogetherApplyingRemote(): Boolean = togetherApplyingRemote
    private val togetherHostId: String = "host"
    private var lastTogetherNoticeAtElapsedMs: Long = 0L
    private var lastTogetherNoticeKey: String? = null

    private data class TogetherPendingGuestControl(
        val desiredIsPlaying: Boolean? = null,
        val desiredIndex: Int? = null,
        val desiredTrackId: String? = null,
        val requestedAtElapsedMs: Long,
        val expiresAtElapsedMs: Long,
    )

    private fun showTogetherNotice(message: String, key: String? = null) {
        val now = android.os.SystemClock.elapsedRealtime()
        val normalizedKey = key ?: message
        if (normalizedKey == lastTogetherNoticeKey && now - lastTogetherNoticeAtElapsedMs < 1200L) return
        lastTogetherNoticeKey = normalizedKey
        lastTogetherNoticeAtElapsedMs = now
        scope.launch(SilentHandler) {
            Toast.makeText(this@MusicService, message, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun getOrCreateTogetherClientId(): String {
        val existing = dataStore.getAsync(TogetherClientIdKey)?.trim().orEmpty()
        if (existing.isNotBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString()
        dataStore.edit { prefs -> prefs[TogetherClientIdKey] = generated }
        return generated
    }

    private fun ensureStartedAsForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (hasCalledStartForeground) return

        val notification =
            try {
                val contentIntent =
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )

                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.star)
                    .setContentTitle(getString(R.string.music_player))
                    .setContentText(getString(R.string.app_name))
                    .setContentIntent(contentIntent)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            } catch (e: Exception) {
                reportException(e)
                return
            }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            hasCalledStartForeground = true
        } catch (e: Exception) {
            reportException(e)
        }
    }

    private fun promoteToStartedService() {
        runCatching { startService(Intent(this, MusicService::class.java)) }
            .onFailure { reportException(it) }
    }

    private fun cancelIdleStop() {
        idleStopJob?.cancel()
        idleStopJob = null
    }

    private fun stopForegroundAndSelf() {
        cancelIdleStop()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
        }
        hasCalledStartForeground = false
        stopSelf()
    }

    private fun scheduleStopIfIdle() {
        if (hasBoundClients) return
        val state = player.playbackState
        val keepAlive =
            player.isPlaying ||
                (player.playWhenReady && (state == Player.STATE_BUFFERING || state == Player.STATE_READY))
        if (keepAlive) {
            cancelIdleStop()
            return
        }
        val togetherIdle = togetherSessionState.value is com.dezzy.music.together.TogetherSessionState.Idle
        if (!togetherIdle) {
            cancelIdleStop()
            return
        }

        // Si el usuario tiene activo las recomendaciones automáticas (AutoLoadMore), permitir tiempo a Automix
        val isAutoLoadMoreActive = dataStore.get(AutoLoadMoreKey, true) && state == Player.STATE_ENDED
        if (isAutoLoadMoreActive) {
            cancelIdleStop()
            return
        }

        val delayMs =
            when (state) {
                Player.STATE_READY -> 5 * 60_000L
                Player.STATE_ENDED, Player.STATE_IDLE -> 30_000L
                else -> 60_000L
            }

        cancelIdleStop()
        idleStopJob =
            scope.launch {
                delay(delayMs)
                if (hasBoundClients) return@launch
                val currentState = player.playbackState
                val shouldKeep =
                    player.isPlaying ||
                        (player.playWhenReady && (currentState == Player.STATE_BUFFERING || currentState == Player.STATE_READY))
                if (shouldKeep) return@launch
                if (togetherSessionState.value !is com.dezzy.music.together.TogetherSessionState.Idle) return@launch
                stopForegroundAndSelf()
            }
    }

    override fun onCreate() {
        super.onCreate()
        ensureScopesActive()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.music_player),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        } catch (e: Exception) {
            reportException(e)
        }

        player =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(createMediaSourceFactory())
                .setRenderersFactory(createRenderersFactory())
                .setLoadControl(createLoadControl())
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false,
                ).setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                .setDeviceVolumeControlEnabled(true)
                .build()
                .apply {
                    addListener(this@MusicService)
                    sleepTimer = SleepTimer(scope, this)
                    addListener(sleepTimer)
                    addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))
                    setOffloadEnabled(false)
                    currentPlayingMediaId = currentMediaItem?.mediaId
                }

        castController.initialize()
        castController.onRemotePlayWhenReadyChanged = { playWhenReady ->
            player.playWhenReady = playWhenReady
        }
        scope.launch {
            castController.isConnected.collect { connected ->
                if (connected) {
                    player.currentMetadata?.let {
                        castController.loadCurrent(it, player.currentPosition, player.playWhenReady)
                    }
                    castController.setRepeatMode(player.repeatMode)
                }
            }
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Dezzy:Playback")
            .also { it.setReferenceCounted(false) }
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)?.createWifiLock(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            },
            "Dezzy:PlaybackWifiLock"
        )?.also { it.setReferenceCounted(false) }
        setupAudioFocusRequest()

        mediaLibrarySessionCallback.apply {
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
            onPlaybackResumptionHandler = { session ->
                if (!queueRestoreCompleted.value) {
                    runCatching {
                        kotlinx.coroutines.withTimeout(3000) {
                            queueRestoreCompleted.first { it }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    if (player.mediaItemCount > 0 && player.playbackState == Player.STATE_IDLE) {
                        player.prepare()
                    }
                    player.playWhenReady = true
                }
                val items = player.mediaItems
                val index = player.currentMediaItemIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
                val pos = player.currentPosition.coerceAtLeast(0L)
                MediaSession.MediaItemsWithStartPosition(
                    items,
                    index,
                    pos,
                )
            }
        }
        // Envoltorio que intercepta los skips que llegan desde fuera de la app
        // (notificación, Bluetooth/auriculares, Android Auto, Wear) y los redirige
        // al camino manual, que compromete el crossfade y respeta el skip en repeat-one.
        val sessionPlayer =
            object : ForwardingPlayer(player) {
                /**
                 * Los controles externos llaman a `play()` pelado, y `play()` sobre un
                 * player en STATE_IDLE (tras un error, p. ej. el AudioTrack que muere al
                 * cambiar de ruta Bluetooth SCO↔A2DP al colgar una llamada) no hace NADA:
                 * el botón de reanudar queda "bloqueado" para siempre. La UI interna no lo
                 * sufría porque usa `togglePlayPause()`, que sí re-prepara.
                 */
                override fun play() {
                    if (player.mediaItemCount == 0) {
                        scope.launch(Dispatchers.Main) {
                            if (!queueRestoreCompleted.value) {
                                runCatching { kotlinx.coroutines.withTimeout(3000) { queueRestoreCompleted.first { it } } }
                            }
                            if (player.mediaItemCount > 0) {
                                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                                player.play()
                            }
                        }
                        return
                    }
                    if (playbackState == Player.STATE_IDLE) prepare()
                    super.play()
                }

                override fun setPlayWhenReady(playWhenReady: Boolean) {
                    if (playWhenReady && player.mediaItemCount == 0) {
                        scope.launch(Dispatchers.Main) {
                            if (!queueRestoreCompleted.value) {
                                runCatching { kotlinx.coroutines.withTimeout(3000) { queueRestoreCompleted.first { it } } }
                            }
                            if (player.mediaItemCount > 0) {
                                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                                player.playWhenReady = true
                            }
                        }
                        return
                    }
                    if (playWhenReady && playbackState == Player.STATE_IDLE) prepare()
                    super.setPlayWhenReady(playWhenReady)
                }

                override fun seekToNext() {
                    Timber.tag("SkipDebug").i("sessionPlayer.seekToNext()")
                    skipToNextManual()
                }

                override fun seekToNextMediaItem() {
                    Timber.tag("SkipDebug").i("sessionPlayer.seekToNextMediaItem()")
                    skipToNextManual()
                }

                override fun seekToPrevious() = skipToPreviousManual()

                override fun seekToPreviousMediaItem() = skipToPreviousManual()
            }
        mediaSession =
            MediaLibrarySession
                .Builder(this, sessionPlayer, mediaLibrarySessionCallback)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setBitmapLoader(
                    CoilBitmapLoader(this, scope, onArtworkUnusable = ::repairBrokenArtwork)
                )
                .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player
            ).apply {
                setSmallIcon(R.drawable.star)
            }
        )
        
        updateNotification()
        player.repeatMode = REPEAT_MODE_OFF

        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())
        scope.launch(Dispatchers.IO) {
            val prefs = dataStore.data.first()
            val repeatMode = prefs[RepeatModeKey] ?: REPEAT_MODE_OFF
            val volume = (prefs[PlayerVolumeKey] ?: 1f).coerceIn(0f, 1f)
            val offload = prefs[AudioOffload] ?: false
            withContext(Dispatchers.Main) {
                player.repeatMode = repeatMode
                playerVolume.value = volume
                updateAudioOffload(offload)
            }
        }

        connectivityManager = getSystemService()!!
        connectivityObserver = NetworkConnectivityObserver(this)

        scope.launch {
            connectivityObserver.networkStatus.collect { isConnected ->
                isNetworkConnected.value = isConnected
                if (isConnected && waitingForNetworkConnection.value) {
                    resumePlaybackAfterNetworkError()
                }
            }
        }

        combine(
            playerVolume,
            normalizeFactor,
            audioFocusVolumeFactor,
            playbackFadeFactor,
            castController.isConnected,
        ) { playerVolume, normalizeFactor, audioFocusVolumeFactor, playbackFadeFactor, casting ->
            // Muted locally while casting: audio actually plays on the Cast receiver, not the phone.
            if (casting) 0f else playerVolume * normalizeFactor * audioFocusVolumeFactor * playbackFadeFactor
        }.collectLatest(scope) { finalVolume ->
            player.volume = finalVolume
        }

        playerVolume.debounce(1000).collect(ioScope) { volume ->
            dataStore.edit { settings ->
                settings[PlayerVolumeKey] = volume
            }
        }

        currentSong.debounce(300).collect(scope) { song ->
            updateNotification()
            if (song != null && player.playWhenReady && player.playbackState == Player.STATE_READY) {
                ensurePresenceManager()
            } else {
                discordRpc?.closeRPC()
            }
        }

        combine(
            currentMediaMetadata.distinctUntilChangedBy { it?.id },
            // Fetch the CURRENT song's lyrics not only when the classic player's lyrics view
            // is open (ShowLyricsKey), but whenever lyrics preloading is enabled (default on).
            // The standalone players (Spotify/Deezer/Dezzy/iPod) read lyrics straight from the
            // DB and never trigger a fetch themselves — without this they only got lyrics
            // late, once the queue preloader happened to bring them in as a "next song".
            dataStore.data.map {
                (it[ShowLyricsKey] ?: false) ||
                    (it[com.dezzy.music.constants.PreloadQueueLyricsEnabledKey] ?: true)
            }.distinctUntilChanged(),
        ) { mediaMetadata, showLyrics ->
            mediaMetadata to showLyrics
        }.collectLatest(ioScope) { (mediaMetadata, showLyrics) ->
            if (showLyrics && mediaMetadata != null) {
                val currentLyrics = database.lyrics(mediaMetadata.id).first()
                if (currentLyrics == null || currentLyrics.lyrics == LYRICS_NOT_FOUND) {
                    val lyrics = lyricsHelper.getLyrics(mediaMetadata)
                    if (lyrics != LYRICS_NOT_FOUND) {
                        database.query {
                            upsert(
                                LyricsEntity(
                                    id = mediaMetadata.id,
                                    lyrics = lyrics,
                                ),
                            )
                        }
                    } else if (currentLyrics == null) {
                        // Optionally still cache LYRICS_NOT_FOUND temporarily? 
                        // The user said "cache of original lyrics never gets lost", maybe we shouldn't cache the not found state.
                        // We will cache it but maybe we need a way to clear it.
                        // Actually, if we don't cache it, it will fetch every time if it's not found, which is better than being stuck offline.
                    }
                }
            }
        }

        dataStore.data
            .map { it[SkipSilenceKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                player.skipSilenceEnabled = it
            }

        dataStore.data
            .map { it[PauseOnDeviceMuteKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                pauseOnDeviceMuteEnabled = enabled
                if (!enabled) {
                    wasAutoPausedByDeviceMute = false
                } else {
                    handleDeviceMuteStateChanged()
                }
            }

        dataStore.data
            .map { it[AutoStartOnBluetoothKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                autoStartOnBluetoothEnabled = enabled
                if (enabled) {
                    registerBluetoothReceiver()
                } else {
                    unregisterBluetoothReceiver()
                }
            }

        dataStore.data
            .map { it[AudioOffload] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                updateAudioOffload(enabled)
                if (enabled) {
                    val skipSilenceEnabled = dataStore.get(SkipSilenceKey, false)
                    if (skipSilenceEnabled) {
                        dataStore.edit { it[SkipSilenceKey] = false }
                        player.skipSilenceEnabled = false
                    }
                    val crossfadeSeconds = dataStore.get(AudioCrossfadeDurationKey, 0)
                    if (crossfadeSeconds != 0) {
                        dataStore.edit { it[AudioCrossfadeDurationKey] = 0 }
                    }
                }
            }
        
        dataStore.data
            .map { (it[AudioCrossfadeDurationKey] ?: 0) * 1000 }
            .distinctUntilChanged()
            .collectLatest(scope) {
                crossfadeDurationMs.value = it
            }

        dataStore.data
            .map { it[WakelockKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                wakelockEnabled = enabled
                updateWakeLock()
            }

        crossfadeAudio =
            CrossfadeAudio(
                player = player,
                database = database,
                crossfadeDurationMs = crossfadeDurationMs,
                playbackFadeFactor = playbackFadeFactor,
                playerVolume = playerVolume,
                audioFocusVolumeFactor = audioFocusVolumeFactor,
                audioNormalizationEnabled = audioNormalizationEnabled,
                maxSafeGainFactor = maxSafeGainFactor,
                overlapPlayerFactory = {
                    ExoPlayer
                        .Builder(this)
                        .setMediaSourceFactory(createMediaSourceFactory())
                        .setRenderersFactory(createRenderersFactory())
                        .setHandleAudioBecomingNoisy(false)
                        .setWakeMode(C.WAKE_MODE_NETWORK)
                        .setAudioAttributes(
                            AudioAttributes
                                .Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                                .build(),
                            false,
                        ).setSeekBackIncrementMs(5000)
                        .setSeekForwardIncrementMs(5000)
                        .build()
                },
                onCrossfadeStart = { mediaItem ->
                    val metadata = mediaItem.metadata
                    // NO adelantamos currentMediaMetadata a la canción entrante aquí:
                    // el player sigue en la canción saliente hasta el handoff, y
                    // adelantar la metadata dejaba la UI un índice por delante del
                    // player → al navegar manualmente sonaba la canción anterior.
                    // La metadata se re-sincroniza en onCrossfadeEnd (al terminar).
                    // immediate update when media item transitions to avoid stale presence
                    scope.launch {
                        try {
                            val token = dataStore.get(DiscordTokenKey, "")
                            if (token.isNotBlank() && DiscordPresenceManager.isRunning()) {
                                val mediaId = mediaItem.mediaId
                                val song = if (mediaId != null) withContext(Dispatchers.IO) { database.song(mediaId).first() } else null
                                val finalSong = song ?: metadata?.let { createTransientSongFromMedia(it) }

                                if (canUpdatePresence()) {
                                    DiscordPresenceManager.updateNow(
                                        context = this@MusicService,
                                        token = token,
                                        song = finalSong,
                                        positionMs = 0L,
                                        isPaused = false
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                },
                onCrossfadeEnd = {
                    // El fundido/handoff terminó: la metadata (suprimida durante el
                    // crossfade en onEvents) se re-sincroniza con la canción REAL del
                    // player, evitando que se quede pegada en la anterior.
                    currentMediaMetadata.value = player.currentMetadata
                },
            ).also { it.start(scope) }

        // Register bubble command receiver — el widget de inicio usa el mismo canal
        // y el mismo vocabulario de comandos ("PLAY_PAUSE"/"NEXT"/"PREVIOUS").
        val bubbleFilter = IntentFilter("com.dezzy.music.BUBBLE_COMMAND").apply {
            addAction(MusicWidgetProvider.ACTION_COMMAND)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bubbleCommandReceiver, bubbleFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bubbleCommandReceiver, bubbleFilter)
        }

        // Initialize lyrics pre-load manager
        lyricsPreloadManager = LyricsPreloadManager(
            context = this,
            database = database,
            networkConnectivity = connectivityObserver,
        )

        dataStore.data
            .map(::readEqSettingsFromPrefs)
            .distinctUntilChanged()
            .collectLatest(scope) { settings ->
                desiredEqSettings.value = settings
                applyEqSettingsToEffects(settings)
            }

        // Ecualización automática por género: se recalcula en cada cambio de canción.
        currentMediaMetadata
            .distinctUntilChanged { old, new -> old?.id == new?.id }
            .collectLatest(scope) { metadata ->
                applyGenreEqForCurrentSong(metadata)
            }

        combine(
            currentFormat,
            dataStore.data
                .map { it[AudioNormalizationKey] ?: true }
                .distinctUntilChanged(),
        ) { format, normalizeAudio ->
            format to normalizeAudio
        }.collectLatest(scope) { (format, normalizeAudio) ->
            audioNormalizationEnabled.value = normalizeAudio
            Timber.tag("AudioNormalization").d("Audio normalization enabled: $normalizeAudio")
            Timber.tag("AudioNormalization").d("Format loudnessDb: ${format?.loudnessDb}, perceptualLoudnessDb: ${format?.perceptualLoudnessDb}")
            
            normalizeFactor.value =
                if (normalizeAudio) {
                    // Use loudnessDb if available, otherwise fall back to perceptualLoudnessDb
                    val loudness = format?.loudnessDb ?: format?.perceptualLoudnessDb
                    
                    if (loudness != null) {
                        val loudnessDb = loudness.toFloat()
                        var factor = 10f.pow(-loudnessDb / 20)
                        
                        Timber.tag("AudioNormalization").d("Calculated raw normalization factor: $factor (from loudness: $loudnessDb)")
                        
                        if (factor > 1f) {
                            factor = min(factor, maxSafeGainFactor)
                            Timber.tag("AudioNormalization").d("Factor capped at maxSafeGainFactor: $factor")
                        }
                        
                        Timber.tag("AudioNormalization").i("Applying normalization factor: $factor")
                        factor
                    } else if (format == null) {
                        // The new song's format row isn't stored yet (stream still resolving).
                        // Jumping to 1.0 here caused an audible volume spike on every song
                        // change; hold the previous factor until real loudness data arrives.
                        normalizeFactor.value
                    } else {
                        Timber.tag("AudioNormalization").w("Normalization enabled but no loudness data available - no normalization applied")
                        1f
                    }
                } else {
                    Timber.tag("AudioNormalization").d("Normalization disabled - using factor 1.0")
                    1f
                }
        }

        dataStore.data
            .map { it[EnableBubbleKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enableBubble ->
                isBubbleEnabled = enableBubble
                if (enableBubble && android.provider.Settings.canDrawOverlays(this@MusicService)) {
                    startService(Intent(this@MusicService, BubbleService::class.java))
                    sendBubbleUpdate(player.isPlaying)
                } else {
                    stopService(Intent(this@MusicService, BubbleService::class.java))
                }
            }

        dataStore.data
            .map { it[DiscordTokenKey] to (it[EnableDiscordRPCKey] ?: true) }
            .debounce(300)
            .distinctUntilChanged()
            .collectLatest(scope) { (key, enabled) ->
                val newRpc =
                    withContext(Dispatchers.IO) {
                        if (!key.isNullOrBlank() && enabled) {
                            runCatching { DiscordRPC(this@MusicService, key) }
                                .onFailure { Timber.tag("MusicService").e(it, "failed to create DiscordRPC client") }
                                .getOrNull()
                        } else {
                            null
                        }
                    }

                try {
                    if (discordRpc?.isRpcRunning() == true) {
                        withContext(Dispatchers.IO) { discordRpc?.closeRPC() }
                    }
                } catch (_: Exception) {}
                discordRpc = newRpc

                if (discordRpc != null) {
                    if (player.playbackState == Player.STATE_READY && player.playWhenReady) {
                        currentSong.value?.let {
                            ensurePresenceManager()
                        }
                    }
                } else {
                    try { DiscordPresenceManager.stop() } catch (_: Exception) {}
                }
            }

        dataStore.data
            .map { prefs ->
                (prefs[SmartTrimmerKey] ?: false) to (prefs[MaxSongCacheSizeKey] ?: -1)
            }
            .debounce(300)
            .distinctUntilChanged()
            .collectLatest(ioScope) { (enabled, maxSongCacheSizeMb) ->
                if (!enabled) return@collectLatest
                if (maxSongCacheSizeMb <= 0 || maxSongCacheSizeMb == -1) return@collectLatest
                val bytesPerMb = 1024L * 1024L
                val safeSizeMb = maxSongCacheSizeMb.toLong().coerceAtMost(Long.MAX_VALUE / bytesPerMb)
                val limitBytes = safeSizeMb * bytesPerMb
                trimPlayerCacheToBytes(limitBytes)
            }

        // Last.fm ScrobbleManager setup
        dataStore.data
            .map { it[EnableLastFMScrobblingKey] ?: false }
            .debounce(300)
            .distinctUntilChanged()
            .collect(scope) { enabled ->
                if (enabled && scrobbleManager == null) {
                    val delayPercent = dataStore.get(ScrobbleDelayPercentKey, LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT)
                    val minSongDuration = dataStore.get(ScrobbleMinSongDurationKey, LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION)
                    val delaySeconds = dataStore.get(ScrobbleDelaySecondsKey, LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS)
                    
                    scrobbleManager = com.dezzy.music.utils.ScrobbleManager(
                        ioScope,
                        minSongDuration = minSongDuration,
                        scrobbleDelayPercent = delayPercent,
                        scrobbleDelaySeconds = delaySeconds
                    )
                    scrobbleManager?.useNowPlaying = dataStore.get(LastFMUseNowPlaying, false)
                } else if (!enabled && scrobbleManager != null) {
                    scrobbleManager?.destroy()
                    scrobbleManager = null
                }
            }

        dataStore.data
            .map { it[LastFMUseNowPlaying] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                scrobbleManager?.useNowPlaying = it
            }

        dataStore.data
            .map { prefs ->
                Triple(
                    prefs[ScrobbleDelayPercentKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT,
                    prefs[ScrobbleMinSongDurationKey] ?: LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION,
                    prefs[ScrobbleDelaySecondsKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS
                )
            }
            .distinctUntilChanged()
            .collect(scope) { (delayPercent, minSongDuration, delaySeconds) ->
                scrobbleManager?.let {
                    it.scrobbleDelayPercent = delayPercent
                    it.minSongDuration = minSongDuration
                    it.scrobbleDelaySeconds = delaySeconds
                }
            }

        scope.launch(Dispatchers.IO) {
            if (dataStore.get(PersistentQueueKey, true)) {
                readPersistentObject<PersistQueue>(PERSISTENT_QUEUE_FILE)
                    ?.let { persistedQueue ->
                    restorePersistentQueue(persistedQueue)
                }
                readPersistentObject<PersistQueue>(PERSISTENT_AUTOMIX_FILE)
                    ?.let { persistedAutomix ->
                    val items = persistedAutomix.items.map { it.toMediaItem() }
                    withContext(Dispatchers.Main) {
                        automixItems.value = items
                        automixSeedMediaId = player.currentMetadata?.id?.trim()?.takeIf { it.isNotBlank() }
                    }
                }
                
                readPersistentObject<PersistPlayerState>(PERSISTENT_PLAYER_STATE_FILE)
                    ?.let { playerState ->
                    withContext(Dispatchers.Main) {
                        player.repeatMode = playerState.repeatMode
                        player.shuffleModeEnabled = playerState.shuffleModeEnabled
                        playerVolume.value = playerState.volume
                        
                        if (player.mediaItemCount > 0) {
                            val index =
                                if (playerState.currentMediaItemIndex in 0 until player.mediaItemCount) {
                                    playerState.currentMediaItemIndex
                                } else {
                                    player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
                                }
                            player.seekTo(index, playerState.currentPosition)
                        }
                        
                        currentMediaMetadata.value = player.currentMetadata
                        updateNotification()
                    }
                }
            }
            withContext(Dispatchers.Main) {
                queueRestoreCompleted.value = true
            }
        }

        // Save queue periodically to prevent queue loss from crash or force kill
        // Save queue periodically to prevent queue loss from crash or force kill
        scope.launch {
            while (isActive) {
                val interval = if (player.isPlaying) 10.seconds else 30.seconds
                delay(interval)
                val shouldSave = withContext(Dispatchers.IO) { dataStore.get(PersistentQueueKey, true) }
                if (shouldSave) {
                    saveQueueToDisk()
                }
            }
        }
    }

    private fun ensureScopesActive() {
        if (!scopeJob.isActive) {
            scopeJob = Job()
        }
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + scopeJob)
        }
        if (!ioScope.isActive) {
            ioScope = CoroutineScope(Dispatchers.IO + scopeJob)
        }
    }

    private suspend fun restorePersistentQueue(persistedQueue: PersistQueue) {
        val restoredQueue = persistedQueue.toQueue()
        val hideExplicit = dataStore.get(HideExplicitKey, false)
        val hideVideo = dataStore.get(HideVideoKey, false)
        val initialStatus =
            restoredQueue
                .getInitialStatus()
                .filterExplicit(hideExplicit)
                .filterVideo(hideVideo)

        withContext(Dispatchers.Main) {
            currentQueue = restoredQueue
            queueTitle = initialStatus.title

            val items = initialStatus.items
            if (items.isEmpty()) {
                return@withContext
            }

            val fullIndex = initialStatus.mediaItemIndex.coerceIn(0, items.lastIndex)
            val windowStart = (fullIndex - 20).coerceAtLeast(0)
            val windowEnd = (fullIndex + 50).coerceAtMost(items.size)

            val initialChunk = items.subList(windowStart, windowEnd)
            val relativeIndex = (fullIndex - windowStart).coerceIn(0, initialChunk.lastIndex)

            player.setMediaItems(
                initialChunk,
                relativeIndex,
                initialStatus.position,
            )
            player.prepare()
            player.playWhenReady = player.playWhenReady || false
            currentMediaMetadata.value = player.currentMetadata
            updateNotification()

            if (items.size > initialChunk.size) {
                scope.launch(SilentHandler) {
                    delay(2000)
                    if (!isActive || player.mediaItemCount == 0) return@launch
                    if (windowStart > 0) {
                        player.addMediaItems(0, items.subList(0, windowStart))
                    }
                    if (windowEnd < items.size) {
                        player.addMediaItems(items.subList(windowEnd, items.size))
                    }
                }
            }
        }
    }

    private fun ensurePresenceManager() {
        if (DiscordPresenceManager.isRunning() && lastPresenceToken != null) return

        // Launch in scope to avoid blocking
        scope.launch {
            // Don't start if Discord RPC is disabled in settings
            if (!dataStore.get(EnableDiscordRPCKey, true)) {
                if (DiscordPresenceManager.isRunning()) {
                    Timber.tag("MusicService").d("Discord RPC disabled → stopping presence manager")
                    try { DiscordPresenceManager.stop() } catch (_: Exception) {}
                    lastPresenceToken = null
                }
                return@launch
            }

            val key: String = dataStore.get(DiscordTokenKey, "")
            if (key.isNullOrBlank()) {
                if (DiscordPresenceManager.isRunning()) {
                    Timber.tag("MusicService").d("No Discord token → stopping presence manager")
                    try { DiscordPresenceManager.stop() } catch (_: Exception) {}
                    lastPresenceToken = null
                }
                return@launch
            }

            if (DiscordPresenceManager.isRunning() && lastPresenceToken == key) {
                // try {
                //     if (DiscordPresenceManager.restart()) {
                //         Timber.tag("MusicService").d("Presence manager restarted with same token")
                //     }
                // } catch (ex: Exception) {
                //     Timber.tag("MusicService").e(ex, "Failed to restart presence manager")
                // }
                return@launch
            }

            try {
                DiscordPresenceManager.stop()
                DiscordPresenceManager.start(
                    context = this@MusicService,
                    token = key,
                    songProvider = { player.currentMetadata?.let { createTransientSongFromMedia(it) } ?: currentSong.value },
                    positionProvider = { player.currentPosition },
                    isPausedProvider = { !player.isPlaying },
                    intervalProvider = { getPresenceIntervalMillis(this@MusicService) }
                )
                Timber.tag("MusicService").d("Presence manager started with token=$key")
                lastPresenceToken = key
            } catch (ex: Exception) {
                Timber.tag("MusicService").e(ex, "Failed to start presence manager")
            }
        }
    }

    private fun canUpdatePresence(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(this) {
            return if (now - lastPresenceUpdateTime > MIN_PRESENCE_UPDATE_INTERVAL) {
                lastPresenceUpdateTime = now
                true
            } else false
        }
    }

    private fun setupAudioFocusRequest() {
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                handleAudioFocusChange(focusChange)
            }
            .setAcceptsDelayedFocusGain(true)
            .build()
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                audioFocusVolumeFactor.value = 1f
                pendingTransientPauseJob?.cancel()
                pendingTransientPauseJob = null

                if (wasPlayingBeforeAudioFocusLoss && !player.isPlaying) {
                    resumeAfterFocusRegained()
                }
                wasPlayingBeforeAudioFocusLoss = false

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                audioFocusVolumeFactor.value = 1f
                pendingTransientPauseJob?.cancel()
                pendingTransientPauseJob = null

                // Recordamos que estábamos sonando y NO abandonamos la petición de foco:
                // muchas apps de teléfono notifican LOSS (no LOSS_TRANSIENT) al entrar la
                // llamada y devuelven el foco con GAIN al colgar. Si abandonáramos aquí,
                // ese GAIN nunca llegaría y la música no volvería sola.
                if (player.isPlaying) {
                    wasPlayingBeforeAudioFocusLoss = true
                    player.pause()
                }

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                audioFocusVolumeFactor.value = 1f
                if (!wasPlayingBeforeAudioFocusLoss) {
                    wasPlayingBeforeAudioFocusLoss = player.isPlaying
                }
                lastAudioFocusState = focusChange

                // Task-switch gestures / launcher UI sounds often grab focus for a fraction
                // of a second and hand it right back — pausing immediately here produced an
                // audible pause-then-resume hiccup on every app switch. Give focus a brief
                // grace period to come back on its own before actually pausing; a genuine
                // longer loss (call, another player) still pauses normally after the delay.
                pendingTransientPauseJob?.cancel()
                pendingTransientPauseJob = scope.launch {
                    delay(TRANSIENT_FOCUS_LOSS_GRACE_MS)
                    if (lastAudioFocusState == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && player.isPlaying) {
                        player.pause()
                    }
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {

                hasAudioFocus = false
                pendingTransientPauseJob?.cancel()
                pendingTransientPauseJob = null

                wasPlayingBeforeAudioFocusLoss = player.isPlaying

                audioFocusVolumeFactor.value = 0.2f

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {

                hasAudioFocus = true
                audioFocusVolumeFactor.value = 1f
                pendingTransientPauseJob?.cancel()
                pendingTransientPauseJob = null

                if (wasPlayingBeforeAudioFocusLoss && !player.isPlaying) {
                    resumeAfterFocusRegained()
                }
                wasPlayingBeforeAudioFocusLoss = false

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                hasAudioFocus = true
                audioFocusVolumeFactor.value = 1f
                pendingTransientPauseJob?.cancel()
                pendingTransientPauseJob = null

                lastAudioFocusState = focusChange
            }
        }
    }

    /**
     * Reanuda tras recuperar el foco. Si el player quedó en STATE_IDLE (error de
     * AudioTrack durante la llamada) hay que re-preparar: `play()` a secas no haría nada.
     */
    private fun resumeAfterFocusRegained() {
        if (player.playbackState == STATE_IDLE) player.prepare()
        player.play()
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        audioFocusRequest?.let { request ->
            val result = audioManager.requestAudioFocus(request)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (hasAudioFocus) {
                // Recuperamos el foco: cualquier pausa transitoria programada por un
                // LOSS_TRANSIENT previo quedó obsoleta. Sin cancelarla, ese job diferido
                // podía dispararse DESPUÉS de que el usuario reanudara, pausando la
                // reproducción de forma involuntaria justo tras darle play.
                pendingTransientPauseJob?.cancel()
                pendingTransientPauseJob = null
                lastAudioFocusState = AudioManager.AUDIOFOCUS_GAIN
            }
            return hasAudioFocus
        }
        return false
    }

    private fun abandonAudioFocus() {
        // OJO: sin condicionar a `hasAudioFocus`. Ese flag se pone a false en cuanto
        // perdemos el foco, así que el guard anterior convertía esta función en un no-op
        // justo en los casos en los que sí seguíamos registrados ante el AudioManager.
        audioFocusRequest?.let { request ->
            audioManager.abandonAudioFocusRequest(request)
        }
        hasAudioFocus = false
    }

    fun hasAudioFocusForPlayback(): Boolean {
        return hasAudioFocus
    }

    private fun isDeviceMutedNow(): Boolean {
        return player.isDeviceMuted || player.deviceVolume <= 0
    }

    private fun isTogetherGuestSession(): Boolean {
        val joined = togetherSessionState.value as? com.dezzy.music.together.TogetherSessionState.Joined
        return joined?.role is com.dezzy.music.together.TogetherRole.Guest
    }

    private fun handleDeviceMuteStateChanged() {
        if (!pauseOnDeviceMuteEnabled || isTogetherGuestSession()) {
            wasAutoPausedByDeviceMute = false
            return
        }

        if (isDeviceMutedNow()) {
            val canPauseNow =
                player.currentMediaItem != null &&
                    player.playWhenReady &&
                    player.playbackState != Player.STATE_IDLE &&
                    player.playbackState != Player.STATE_ENDED

            if (canPauseNow) {
                player.pause()
                wasAutoPausedByDeviceMute = true
            }
            return
        }

        if (!wasAutoPausedByDeviceMute) return

        wasAutoPausedByDeviceMute = false
        val canResumeNow =
            player.currentMediaItem != null &&
                player.playbackState != Player.STATE_IDLE &&
                player.playbackState != Player.STATE_ENDED
        if (canResumeNow) {
            player.play()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
            if (!autoStartOnBluetoothEnabled) return

            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return

            val isAudioDevice = try {
                val majorClass = device.bluetoothClass?.majorDeviceClass
                majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                    majorClass == BluetoothClass.Device.Major.WEARABLE
            } catch (_: SecurityException) {
                true
            }

            if (!isAudioDevice) return

            scope.launch {
                delay(1500)
                handleBluetoothAutoStart()
            }
        }
    }

    private fun handleBluetoothAutoStart() {
        if (isTogetherGuestSession()) return

        if (player.currentMediaItem != null &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        ) {
            if (!player.playWhenReady) {
                player.play()
            }
            return
        }

        if (player.mediaItemCount > 0) {
            player.prepare()
            player.play()
        }
    }

    @Suppress("DEPRECATION")
    private fun registerBluetoothReceiver() {
        if (bluetoothReceiverRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return

        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
        bluetoothReceiverRegistered = true
    }

    private fun unregisterBluetoothReceiver() {
        if (!bluetoothReceiverRegistered) return
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {}
        bluetoothReceiverRegistered = false
    }

    private fun waitOnNetworkError() {
        waitingForNetworkConnection.value = true
        scheduleNetworkErrorRetry()
    }

    /**
     * Reintento activo tras un error de red. El collect de networkStatus solo se
     * entera cuando la conectividad CAMBIA (onAvailable/onLost); si el error fue
     * un corte transitorio con la red aún "conectada" — reset TCP, o la carrera
     * típica al cambiar WiFi↔datos donde onAvailable llega antes que el error del
     * socket — no llega ninguna emisión nueva y la reproducción quedaría detenida
     * para siempre. Este sondeo con backoff cubre ese hueco.
     */
    private fun scheduleNetworkErrorRetry() {
        networkRetryJob?.cancel()
        networkRetryJob = scope.launch {
            while (isActive && waitingForNetworkConnection.value) {
                val backoffMs = NETWORK_RETRY_BACKOFF_MS[
                    networkRetryAttempt.coerceIn(0, NETWORK_RETRY_BACKOFF_MS.lastIndex),
                ]
                networkRetryAttempt++
                delay(backoffMs)
                if (!waitingForNetworkConnection.value) return@launch
                if (connectivityObserver.isCurrentlyConnected()) {
                    resumePlaybackAfterNetworkError()
                    return@launch
                }
            }
        }
    }

    private fun resumePlaybackAfterNetworkError() {
        networkRetryJob?.cancel()
        networkRetryJob = null
        waitingForNetworkConnection.value = false
        if (player.currentMediaItem != null && player.playWhenReady &&
            player.playbackState == Player.STATE_IDLE
        ) {
            player.prepare()
            player.play()
        }
    }

    private fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 2
        val nextWindowIndex = player.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            player.play()
            return
        }

        player.pause()
        consecutivePlaybackErr = 0
    }

    private fun stopOnError() {
        player.pause()
    }

    private fun updateNotification() {
        try {
            val customLayout = listOf(
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            if (currentSong.value?.song?.liked == true) {
                                R.string.action_remove_like
                            } else {
                                R.string.action_like
                            },
                        ),
                    )
                    .setIconResId(if (currentSong.value?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border)
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> R.string.repeat_mode_off
                            },
                        ),
                    ).setIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat
                            REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> R.drawable.repeat
                        },
                    ).setSessionCommand(CommandToggleRepeatMode)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(getString(if (player.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle)
                    .setSessionCommand(CommandToggleShuffle)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName(getString(R.string.start_radio))
                    .setIconResId(R.drawable.radio)
                    .setSessionCommand(CommandToggleStartRadio)
                    .setEnabled(currentSong.value != null)
                    .build(),
            )
            mediaSession.setCustomLayout(customLayout)
        } catch (e: Exception) {
            reportException(e)
        }
    }

    fun refreshPlaybackNotification() {
        updateNotification()
        runCatching { super.onUpdateNotification(mediaSession, player.isPlaying) }
            .onFailure { reportException(it) }
    }

    private suspend fun recoverSong(
        mediaId: String,
        playbackData: YTPlayerUtils.PlaybackData? = null
    ) {
        val song = database.song(mediaId).first()
        val mediaMetadata = withContext(Dispatchers.Main) {
            player.findNextMediaItemById(mediaId)?.metadata
        } ?: return

        // ── Guard: skip all network calls when offline to avoid playback delays ──
        val isOnline = isNetworkConnected.value
        if (!isOnline) {
            // Only update duration in DB from local metadata (no network needed)
            val localDuration = song?.song?.duration?.takeIf { it != -1 }
                ?: mediaMetadata.duration.takeIf { it != -1 }
                ?: playbackData?.videoDetails?.lengthSeconds?.toInt()
                ?: -1
            database.query {
                if (song == null) insert(mediaMetadata.copy(duration = localDuration))
                else if (song.song.duration == -1 && localDuration != -1) update(song.song.copy(duration = localDuration))
            }
            return
        }

        val duration = song?.song?.duration?.takeIf { it != -1 }
            ?: mediaMetadata.duration.takeIf { it != -1 }
            ?: (playbackData?.videoDetails ?: YTPlayerUtils.playerResponseForMetadata(mediaId)
                .getOrNull()?.videoDetails)?.lengthSeconds?.toInt()
            ?: -1
        database.query {
            if (song == null) insert(mediaMetadata.copy(duration = duration))
            else if (song.song.duration == -1) update(song.song.copy(duration = duration))
        }
        if (!database.hasRelatedSongs(mediaId)) {
            val relatedEndpoint =
                YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint
                    ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id
                        )
                    }
                    .forEach(::insert)
            }
        }
    }

    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
    ) {
        val joined = togetherSessionState.value as? com.dezzy.music.together.TogetherSessionState.Joined
        if (!isTogetherApplyingRemote() && joined?.role is com.dezzy.music.together.TogetherRole.Guest) {
            if (!joined.roomState.settings.allowGuestsToControlPlayback) {
                showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_PLAYQUEUE_DISABLED")
                return
            }
            ensureScopesActive()
            scope.launch(SilentHandler) {
                val initialStatus =
                    withContext(Dispatchers.IO) {
                        queue.getInitialStatus()
                            .filterExplicit(dataStore.get(HideExplicitKey, false))
                            .filterVideo(dataStore.get(HideVideoKey, false))
                    }

                val targetItem =
                    initialStatus.items.getOrNull(initialStatus.mediaItemIndex)
                        ?: queue.preloadItem?.toMediaItem()

                val meta = targetItem?.metadata
                val trackId =
                    meta?.id?.trim().orEmpty().ifBlank {
                        targetItem?.mediaId?.trim().orEmpty()
                    }
                if (trackId.isBlank()) {
                    showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_PLAYQUEUE_NO_TRACK")
                    return@launch
                }

                val track =
                    com.dezzy.music.together.TogetherTrack(
                        id = trackId,
                        title = meta?.title ?: trackId,
                        artists = meta?.artists?.map { it.name }.orEmpty(),
                        durationSec = meta?.duration ?: -1,
                        thumbnailUrl = meta?.thumbnailUrl,
                    )

                val ops =
                    com.dezzy.music.together.TogetherGuestPlaybackPlanner.planPlayTrackNow(
                        roomState = joined.roomState,
                        track = track,
                        positionMs = initialStatus.position,
                        playWhenReady = playWhenReady,
                    )

                if (ops.isEmpty()) {
                    showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_PLAYQUEUE_BLOCKED")
                    return@launch
                }

                showTogetherNotice(getString(R.string.together_requesting_song_change), key = "GUEST_PLAYQUEUE_REQUEST")
                ops.forEach { op ->
                    when (op) {
                        is com.dezzy.music.together.TogetherGuestOp.Control -> requestTogetherControl(op.action)
                        is com.dezzy.music.together.TogetherGuestOp.AddTrack -> requestTogetherAddTrack(op.track, op.mode)
                    }
                }
            }
            return
        }
        if (playWhenReady) {
            cancelIdleStop()
            promoteToStartedService()
            ensureStartedAsForeground()
        }
        ensureScopesActive()
        suppressAutoPlayback = false
        currentQueue = queue
        queueTitle = null
        val permanentShuffle = dataStore.get(PermanentShuffleKey, false)
        player.shuffleModeEnabled = permanentShuffle
        
        clearAutomix()
        automixSeedMediaId = null
        autoAddedMediaIds.clear()
        if (queue.preloadItem != null) {
            player.setMediaItem(queue.preloadItem!!.toMediaItem())
            player.prepare()
            player.playWhenReady = playWhenReady
        }
        scope.launch(SilentHandler) {
            val initialStatus =
                withContext(Dispatchers.IO) {
                    queue.getInitialStatus().filterExplicit(dataStore.get(HideExplicitKey, false)).filterVideo(dataStore.get(HideVideoKey, false))
                }
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            if (initialStatus.items.isEmpty()) return@launch
            if (queue.preloadItem != null) {
                player.addMediaItems(
                    0,
                    initialStatus.items.subList(0, initialStatus.mediaItemIndex)
                )
                player.addMediaItems(
                    initialStatus.items.subList(
                        initialStatus.mediaItemIndex + 1,
                        initialStatus.items.size
                    )
                )
                if (player.shuffleModeEnabled) {
                    applyCurrentFirstShuffleOrder()
                }
            } else {
                val items = initialStatus.items
                val index = initialStatus.mediaItemIndex
                
                // Chunk Loading: Only load a window around the current item initially
                // to prevent blocking the Main Thread for seconds with large queues.
                val windowStart = (index - 20).coerceAtLeast(0)
                val windowEnd = (index + 50).coerceAtMost(items.size)
                
                val initialChunk = items.subList(windowStart, windowEnd)
                val relativeIndex = index - windowStart
                
                player.setMediaItems(
                    initialChunk,
                    if (relativeIndex > 0) relativeIndex else 0,
                    initialStatus.position,
                )
                player.prepare()
                player.playWhenReady = playWhenReady
                if (player.shuffleModeEnabled) {
                    applyCurrentFirstShuffleOrder()
                }
                
                // Defer loading the rest of the queue
                if (items.size > initialChunk.size) {
                    scope.launch(SilentHandler) {
                        try {
                            delay(2000) // Allow UI to settle
                            if (!isActive) return@launch
                            
                            // Add preceding items
                            if (windowStart > 0) {
                                val startChunk = items.subList(0, windowStart)
                                player.addMediaItems(0, startChunk)
                            }
                            
                            // Add succeeding items
                            if (windowEnd < items.size) {
                                val endChunk = items.subList(windowEnd, items.size)
                                player.addMediaItems(endChunk)
                            }

                            if (player.shuffleModeEnabled) {
                                applyCurrentFirstShuffleOrder()
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to load deferred queue items")
                        }
                    }
                }
            }
        }
    }

    private fun applyCurrentFirstShuffleOrder() {
        val count = player.mediaItemCount
        if (count <= 1) return
        val currentIndex = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val shuffledIndices = IntArray(count) { it }
        shuffledIndices.shuffle()
        val currentPos = shuffledIndices.indexOf(currentIndex)
        if (currentPos >= 0) {
            shuffledIndices[currentPos] = shuffledIndices[0]
        }
        shuffledIndices[0] = currentIndex
        player.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
    }

    fun startRadioSeamlessly() {
        val joined = togetherSessionState.value as? com.dezzy.music.together.TogetherSessionState.Joined
        if (!isTogetherApplyingRemote() && joined?.role is com.dezzy.music.together.TogetherRole.Guest) {
            if (!joined.roomState.settings.allowGuestsToControlPlayback) {
                showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_RADIO_DISABLED")
                return
            }
            showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_RADIO_UNSUPPORTED")
            return
        }
        suppressAutoPlayback = false
        val currentMediaMetadata = player.currentMetadata ?: return

        val currentIndex = player.currentMediaItemIndex
        val currentMediaId = currentMediaMetadata.id

        scope.launch(SilentHandler) {
            val radioQueue = YouTubeQueue(
                endpoint = WatchEndpoint(videoId = currentMediaId)
            )
            val initialStatus = withContext(Dispatchers.IO) {
                radioQueue.getInitialStatus().filterExplicit(dataStore.get(HideExplicitKey, false)).filterVideo(dataStore.get(HideVideoKey, false))
            }

            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }

            val radioItems = initialStatus.items.filter { item ->
                item.mediaId != currentMediaId
            }
            
            if (radioItems.isNotEmpty()) {
                val itemCount = player.mediaItemCount
                
                if (itemCount > currentIndex + 1) {
                    player.removeMediaItems(currentIndex + 1, itemCount)
                }
                
                player.addMediaItems(currentIndex + 1, radioItems)
            }

            currentQueue = radioQueue
        }
    }

    fun getAutomixAlbum(albumId: String) {
        scope.launch(Dispatchers.IO + SilentHandler) {
            YouTube
                .album(albumId)
                .onSuccess {
                    getAutomix(it.album.playlistId)
                }
        }
    }

    fun getAutomix(playlistId: String) {
        if (dataStore.get(AutoLoadMoreKey, true) && 
            player.repeatMode == REPEAT_MODE_OFF) {
            scope.launch(Dispatchers.IO + SilentHandler) {
                val seedAtRequest =
                    withContext(Dispatchers.Main) {
                        player.currentMetadata?.id?.trim()?.takeIf { it.isNotBlank() }
                    }
                YouTube
                    .next(WatchEndpoint(playlistId = playlistId))
                    .onSuccess {
                        YouTube
                            .next(WatchEndpoint(playlistId = it.endpoint.playlistId))
                            .onSuccess {
                                val mediaItems = it.items.map { song -> song.toMediaItem() }
                                withContext(Dispatchers.Main) {
                                    val currentSeed =
                                        player.currentMetadata?.id?.trim()?.takeIf { it.isNotBlank() }
                                    if (seedAtRequest != null && currentSeed != seedAtRequest) return@withContext
                                    automixItems.value = mediaItems
                                    automixSeedMediaId = currentSeed
                                }
                            }
                    }
            }
        }
    }

    fun addToQueueAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        addToQueue(listOf(item))
    }

    fun playNextAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        playNext(listOf(item))
    }

    fun clearAutomix() {
        automixJob?.cancel()
        automixJob = null
        automixItems.value = emptyList()
        automixLoading.value = false
        automixError.value = null
        automixSeedMediaId = null
    }

    private fun refreshAutomixForCurrentMedia(force: Boolean) {
        if (!dataStore.get(AutoLoadMoreKey, true)) return
        if (player.repeatMode != REPEAT_MODE_OFF) return
        if (suppressAutoPlayback) return
        if (player.mediaItemCount == 0) return

        val currentMeta = player.currentMetadata ?: return
        val seedMediaId = currentMeta.id.trim().ifBlank { return }

        if (!force && automixSeedMediaId == seedMediaId && automixItems.value.isNotEmpty() && automixJob?.isActive == true) return

        automixJob?.cancel()
        automixJob = null
        automixItems.value = emptyList()
        automixLoading.value = true
        automixError.value = null
        automixSeedMediaId = seedMediaId

        val hideExplicit = dataStore.get(HideExplicitKey, false)
        val hideVideo = dataStore.get(HideVideoKey, false)

        automixJob = scope.launch {
            try {
                val nextResult = withContext(Dispatchers.IO) {
                    YouTube.next(WatchEndpoint(videoId = seedMediaId))
                }

                nextResult
                    .onSuccess { result ->
                        if (automixSeedMediaId != seedMediaId) {
                            automixLoading.value = false
                            return@onSuccess
                        }

                        val queueIds =
                            (0 until player.mediaItemCount)
                                .map { player.getMediaItemAt(it).mediaId }
                                .toSet()

                        val fromNext =
                            result.items
                                .map { it.toMediaItem() }
                                .filter { it.mediaId !in queueIds }
                                .filterExplicit(hideExplicit)
                                .filterVideo(hideVideo)

                        val relatedCandidates =
                            result.relatedEndpoint
                                ?.let { endpoint ->
                                    withContext(Dispatchers.IO) { YouTube.related(endpoint) }
                                        .getOrNull()
                                        ?.songs
                                        .orEmpty()
                                }
                                .orEmpty()

                        val related =
                            relatedCandidates
                                .map { it.toMediaItem() }
                                .filter { it.mediaId !in queueIds }
                                .filterExplicit(hideExplicit)
                                .filterVideo(hideVideo)

                        val poolBase =
                            (fromNext + related)
                                .asSequence()
                                .distinctBy { it.mediaId }
                                .take(50)
                                .toList()

                        val pool =
                            if (poolBase.size >= 25 || result.endpoint.playlistId.isNullOrBlank()) {
                                poolBase
                            } else {
                                val playlistId = result.endpoint.playlistId
                                val extra =
                                    withContext(Dispatchers.IO) {
                                        YouTube.next(WatchEndpoint(playlistId = playlistId))
                                    }.getOrNull()
                                        ?.items
                                        .orEmpty()
                                        .map { it.toMediaItem() }
                                        .filter { it.mediaId !in queueIds }
                                        .filterExplicit(hideExplicit)
                                        .filterVideo(hideVideo)

                                (poolBase + extra)
                                    .asSequence()
                                    .distinctBy { it.mediaId }
                                    .take(75)
                                    .toList()
                            }

                        if (automixSeedMediaId != seedMediaId) {
                            automixLoading.value = false
                            return@onSuccess
                        }

                        automixItems.value = pool
                        if (pool.isEmpty()) {
                            automixError.value = getString(R.string.error_no_similar_songs)
                        }
                        automixLoading.value = false
                    }
                    .onFailure { throwable ->
                        if (automixSeedMediaId == seedMediaId) {
                            automixLoading.value = false
                            automixError.value =
                                throwable.localizedMessage ?: getString(R.string.error_automix_failed)
                        }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (automixSeedMediaId == seedMediaId) {
                    automixLoading.value = false
                    automixError.value = e.localizedMessage ?: getString(R.string.error_automix_failed)
                }
            }
        }
    }

    fun onInfiniteQueueDisabled() {
        automixJob?.cancel()
        automixJob = null
        automixLoading.value = false
        automixError.value = null
        val currentIndex = player.currentMediaItemIndex
        val idsToRemove = synchronized(autoAddedMediaIds) { autoAddedMediaIds.toSet() }
        if (idsToRemove.isEmpty()) {
            clearAutomix()
            return
        }
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (i == currentIndex) continue
            val item = player.getMediaItemAt(i)
            if (item.mediaId in idsToRemove) {
                player.removeMediaItem(i)
            }
        }
        autoAddedMediaIds.clear()
        clearAutomix()
    }

    fun onInfiniteQueueEnabled() {
        val currentMeta = player.currentMetadata
        if (currentMeta == null) {
            automixError.value = getString(R.string.error_no_song_playing)
            return
        }

        automixJob?.cancel()
        automixLoading.value = true
        automixError.value = null
        automixItems.value = emptyList()
        automixSeedMediaId = currentMeta.id.trim().ifBlank { null }

        val hideExplicit = dataStore.get(HideExplicitKey, false)
        val hideVideo = dataStore.get(HideVideoKey, false)

        automixJob = scope.launch {
            try {
                val nextResult = withContext(Dispatchers.IO) {
                    YouTube.next(WatchEndpoint(videoId = currentMeta.id))
                }

                nextResult
                    .onSuccess { result ->
                        if (suppressAutoPlayback || player.playbackState == STATE_IDLE || player.mediaItemCount == 0) {
                            automixLoading.value = false
                            return@onSuccess
                        }
                        val initialQueueIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                        val filteredFromNext =
                            result.items
                                .map { it.toMediaItem() }
                                .filter { it.mediaId !in initialQueueIds }
                                .filterExplicit(hideExplicit)
                                .filterVideo(hideVideo)

                        val addedNow = ArrayList<MediaItem>(32)

                        if (filteredFromNext.isNotEmpty()) {
                            val toAdd = filteredFromNext.take(25)
                            player.addMediaItems(toAdd)
                            toAdd.forEach { autoAddedMediaIds.add(it.mediaId) }
                            addedNow.addAll(toAdd)
                        }

                        val queueIdsAfterNext = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                        val relatedCandidates =
                            result.relatedEndpoint?.let { relatedEndpoint ->
                                withContext(Dispatchers.IO) {
                                    YouTube.related(relatedEndpoint)
                                }.getOrNull()?.songs.orEmpty()
                            }.orEmpty()

                        val filteredRelated =
                            relatedCandidates
                                .map { it.toMediaItem() }
                                .filter { it.mediaId !in queueIdsAfterNext }
                                .filterExplicit(hideExplicit)
                                .filterVideo(hideVideo)

                        if (addedNow.isEmpty() && filteredRelated.isNotEmpty()) {
                            val toAdd = filteredRelated.take(25)
                            player.addMediaItems(toAdd)
                            toAdd.forEach { autoAddedMediaIds.add(it.mediaId) }
                            addedNow.addAll(toAdd)
                        }

                        val queueIdsAfterAdds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                        val playlistId = result.endpoint.playlistId
                        val automixCandidates =
                            if (playlistId.isNullOrBlank()) {
                                emptyList()
                            } else {
                                withContext(Dispatchers.IO) {
                                    YouTube.next(WatchEndpoint(playlistId = playlistId))
                                }.getOrNull()?.items.orEmpty()
                            }

                        val filteredAutomix =
                            automixCandidates
                                .map { it.toMediaItem() }
                                .filter { it.mediaId !in queueIdsAfterAdds }
                                .filterExplicit(hideExplicit)
                                .filterVideo(hideVideo)

                        val addedIds = addedNow.map { it.mediaId }.toSet()
                        val pool =
                            (filteredFromNext + filteredRelated + filteredAutomix)
                                .asSequence()
                                .distinctBy { it.mediaId }
                                .filter { it.mediaId !in addedIds }
                                .take(75)
                                .toList()

                        automixItems.value = pool

                        if (addedNow.isEmpty() && pool.isEmpty()) {
                            automixError.value = getString(R.string.error_no_similar_songs)
                        }
                        automixLoading.value = false
                    }
                    .onFailure { throwable ->
                        automixLoading.value = false
                        automixError.value = throwable.localizedMessage ?: getString(R.string.error_automix_failed)
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                automixLoading.value = false
                automixError.value = e.localizedMessage ?: getString(R.string.error_automix_failed)
            }
        }
    }

    fun stopAndClearPlayback() {
        suppressAutoPlayback = true
        clearAutomix()
        currentQueue = EmptyQueue
        queueTitle = null
        clearStreamRefreshGuards()
        waitingForNetworkConnection.value = false
        currentMediaMetadata.value = null
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        abandonAudioFocus()
        closeAudioEffectSession()
        consecutivePlaybackErr = 0
    }

    fun playNext(items: List<MediaItem>) {
        val joined = togetherSessionState.value as? com.dezzy.music.together.TogetherSessionState.Joined
        if (joined?.role is com.dezzy.music.together.TogetherRole.Guest) {
            if (!joined.roomState.settings.allowGuestsToAddTracks) {
                return
            }
            val tracks =
                items.mapNotNull { it.metadata }.map { meta ->
                    com.dezzy.music.together.TogetherTrack(
                        id = meta.id,
                        title = meta.title,
                        artists = meta.artists.map { it.name },
                        durationSec = meta.duration,
                        thumbnailUrl = meta.thumbnailUrl,
                    )
                }
            tracks.asReversed().forEach { track ->
                requestTogetherAddTrack(track, com.dezzy.music.together.AddTrackMode.PLAY_NEXT)
            }
            return
        }
        suppressAutoPlayback = false
        player.addMediaItems(
            if (player.mediaItemCount == 0) 0 else player.currentMediaItemIndex + 1,
            items
        )
        player.prepare()
    }

    fun addToQueue(items: List<MediaItem>) {
        val joined = togetherSessionState.value as? com.dezzy.music.together.TogetherSessionState.Joined
        if (joined?.role is com.dezzy.music.together.TogetherRole.Guest) {
            if (!joined.roomState.settings.allowGuestsToAddTracks) {
                return
            }
            val tracks =
                items.mapNotNull { it.metadata }.map { meta ->
                    com.dezzy.music.together.TogetherTrack(
                        id = meta.id,
                        title = meta.title,
                        artists = meta.artists.map { it.name },
                        durationSec = meta.duration,
                        thumbnailUrl = meta.thumbnailUrl,
                    )
                }
            tracks.forEach { track ->
                requestTogetherAddTrack(track, com.dezzy.music.together.AddTrackMode.ADD_TO_QUEUE)
            }
            return
        }
        suppressAutoPlayback = false
        player.addMediaItems(items)
        player.prepare()
    }

    fun startTogetherHost(
        port: Int,
        displayName: String,
        settings: com.dezzy.music.together.TogetherRoomSettings,
    ) {
        ensureScopesActive()
        scope.launch(SilentHandler) {
            togetherSessionState.value = com.dezzy.music.together.TogetherSessionState.Idle
        }

        ioScope.launch(SilentHandler) {
            stopTogetherInternal()
            togetherIsOnlineSession = true // using new ws server
            
            val client = com.dezzy.music.listentogether.ListenTogetherClient.getInstance() 
                ?: com.dezzy.music.listentogether.ListenTogetherClient(applicationContext).also {
                    com.dezzy.music.listentogether.ListenTogetherClient.setInstance(it)
                }
                
            client.createRoom(displayName.trim().ifBlank { getString(R.string.app_name) })
            
            togetherClientEventsJob?.cancel()
            togetherClientEventsJob = ioScope.launch(SilentHandler) {
                client.events.collect { event ->
                    when (event) {
                        is com.dezzy.music.listentogether.ListenTogetherEvent.RoomCreated -> {
                            togetherSelfParticipantId = event.userId
                            scope.launch(SilentHandler) {
                                togetherSessionState.value = com.dezzy.music.together.TogetherSessionState.HostingOnline(
                                    sessionId = event.roomCode,
                                    code = event.roomCode,
                                    settings = settings,
                                    roomState = com.dezzy.music.together.TogetherRoomState(
                                        sessionId = event.roomCode,
                                        hostId = event.userId,
                                        participants = listOf(
                                            com.dezzy.music.together.TogetherParticipant(
                                                id = event.userId,
                                                name = displayName,
                                                isHost = true,
                                                isConnected = true
                                            )
                                        ),
                                        settings = settings
                                    )
                                )
                            }
                        }
                        is com.dezzy.music.listentogether.ListenTogetherEvent.Disconnected -> {
                            scope.launch(SilentHandler) {
                                togetherSessionState.value = com.dezzy.music.together.TogetherSessionState.Error("Disconnected from server", true)
                            }
                        }
                        is com.dezzy.music.listentogether.ListenTogetherEvent.ConnectionError -> {
                            scope.launch(SilentHandler) {
                                val friendlyError = if (event.error.contains("200 OK") || event.error.contains("101")) {
                                    "El servidor está inactivo (despertando). Por favor, reintenta en 1-2 minutos."
                                } else {
                                    event.error
                                }
                                togetherSessionState.value = com.dezzy.music.together.TogetherSessionState.Error(friendlyError, true)
                            }
                        }
                        is com.dezzy.music.listentogether.ListenTogetherEvent.PlaybackSync -> {
                            // Guest control requests relayed by the server: the host's player is
                            // the source of truth, so apply here (its own relay hooks then
                            // broadcast the authoritative result). Echoes of the host's own
                            // actions are no-ops thanks to applyListenTogetherSync's guards.
                            val hosting = togetherSessionState.value
                                as? com.dezzy.music.together.TogetherSessionState.HostingOnline
                            val roomSettings = hosting?.settings ?: settings
                            val permitted = when (event.action.action) {
                                com.dezzy.music.listentogether.PlaybackActions.QUEUE_ADD ->
                                    roomSettings.allowGuestsToAddTracks
                                com.dezzy.music.listentogether.PlaybackActions.PLAY,
                                com.dezzy.music.listentogether.PlaybackActions.PAUSE,
                                com.dezzy.music.listentogether.PlaybackActions.SEEK,
                                com.dezzy.music.listentogether.PlaybackActions.CHANGE_TRACK ->
                                    roomSettings.allowGuestsToControlPlayback
                                else -> false
                            }
                            if (permitted) applyListenTogetherSync(event.action)
                        }
                        // Handle other ListenTogetherEvent as needed
                        else -> {}
                    }
                }
            }
        }
    }

    private fun togetherOnlineErrorMessage(t: Throwable): String {
        if (t is com.dezzy.music.together.TogetherOnlineApiException) {
            val code = t.statusCode
            return when {
                code == 404 -> getString(R.string.together_session_not_found)
                code != null && code in 500..599 -> getString(R.string.together_server_error)
                else -> t.message ?: getString(R.string.network_unavailable)
            }
        }
        val root = generateSequence(t) { it.cause }.lastOrNull() ?: t
        return when (root) {
            is UnknownHostException -> getString(R.string.together_server_unreachable)
            is ConnectException -> getString(R.string.together_server_unreachable)
            is SocketTimeoutException -> getString(R.string.together_connection_timed_out)
            is javax.net.ssl.SSLHandshakeException -> getString(R.string.together_server_unreachable)
            else -> getString(R.string.network_unavailable)
        }
    }

    fun startTogetherOnlineHost(
        displayName: String,
        settings: com.dezzy.music.together.TogetherRoomSettings,
    ) {
        ensureScopesActive()
        scope.launch(SilentHandler) {
            togetherSessionState.value = com.dezzy.music.together.TogetherSessionState.Idle
        }

        ioScope.launch(SilentHandler) {
            stopTogetherInternal()
            togetherIsOnlineSession = true

            val baseUrl = com.dezzy.music.together.TogetherOnlineEndpoint.baseUrlOrNull(dataStore)
            if (baseUrl == null) {
                scope.launch(SilentHandler) {
                    togetherSessionState.value =
                        com.dezzy.music.together.TogetherSessionState.Error(
                            message = getString(R.string.together_online_not_configured),
                            recoverable = true,
                        )
                }
                return@launch
            }

            val togetherToken = com.dezzy.music.BuildConfig.TOGETHER_BEARER_TOKEN.trim().takeIf { it.isNotBlank() }
            if (togetherToken == null) {
                scope.launch(SilentHandler) {
                    togetherSessionState.value =
                        com.dezzy.music.together.TogetherSessionState.Error(
                            message = getString(R.string.together_token_missing),
                            recoverable = true,
                        )
                }
                return@launch
            }

            val api = com.dezzy.music.together.TogetherOnlineApi(baseUrl = baseUrl, bearerToken = togetherToken)
            val hostName = displayName.trim().ifBlank { getString(R.string.app_name) }

            val created =
                runCatching {
                    api.createSession(
                        hostDisplayName = hostName,
                        settings = settings,
                    )
                }.getOrElse { t ->
                    scope.launch(SilentHandler) {
                        togetherSessionState.value =
                            com.dezzy.music.together.TogetherSessionState.Error(
                                message = togetherOnlineErrorMessage(t),
                                recoverable = true,
                            )
                    }
                    reportException(t)
                    return@launch
                }

            val onlineHost =
                com.dezzy.music.together.TogetherOnlineHost(
                    externalScope = ioScope,
                    sessionId = created.sessionId,
                    sessionKey = created.hostKey,
                    hostId = togetherHostId,
                    hostDisplayName = hostName,
                    initialSettings = created.settings,
                    clientId = getOrCreateTogetherClientId(),
                    bearerToken = togetherToken,
                )

            onlineHost.onEvent = { event ->
                ioScope.launch(SilentHandler) {
                    handleTogetherHostEvent(event) { onlineHost.currentSettings() }
                }
            }

            togetherOnlineHost = onlineHost

            scope.launch(SilentHandler) {
                togetherSessionState.value =
                    com.dezzy.music.together.TogetherSessionState.HostingOnline(
                        sessionId = created.sessionId,
                        code = created.code,
                        settings = created.settings,
                        roomState = null,
                    )
            }

            val wsUrl =
                com.dezzy.music.together.TogetherOnlineEndpoint.onlineWebSocketUrlOrNull(
                    rawWsUrl = created.wsUrl,
                    baseUrl = baseUrl,
                )
            if (wsUrl == null) {
                scope.launch(SilentHandler) {
                    togetherSessionState.value =
                        com.dezzy.music.together.TogetherSessionState.Error(
                            message = "Connection failed: Invalid server websocket URL",
                            recoverable = true,
                        )
                }
                ioScope.launch(SilentHandler) { stopTogetherInternal() }
                return@launch
            }

            togetherOnlineConnectJob?.cancel()
            togetherOnlineConnectJob =
                ioScope.launch(SilentHandler) {
                    onlineHost.connect(wsUrl)
                }

            togetherBroadcastJob =
                ioScope.launch(SilentHandler) {
                    while (togetherOnlineHost === onlineHost) {
                        val state =
                            buildTogetherRoomState(
                                sessionId = created.sessionId,
                                hostId = togetherHostId,
                            )
                        onlineHost.broadcastRoomState(state)
                        scope.launch(SilentHandler) {
                            val hosting =
                                togetherSessionState.value as? com.dezzy.music.together.TogetherSessionState.HostingOnline
                            if (hosting?.sessionId == created.sessionId) {
                                val currentSettings = onlineHost.currentSettings()
                                togetherSessionState.value =
                                    hosting.copy(
                                        settings = currentSettings,
                                        roomState =
                                            state.copy(
                                                participants = onlineHost.currentParticipants(),
                                                settings = currentSettings,
                                            ),
                                    )
                            }
                        }
                        kotlinx.coroutines.delay(750)
                    }
                }
        }
    }

    fun joinTogether(
        rawLink: String, // For the new system, rawLink will just be the 6 character code
        displayName: String,
    ) {
        ensureScopesActive()
        val trimmedCode = rawLink.trim()
        if (trimmedCode.isBlank()) {
            scope.launch(SilentHandler) {
                togetherSessionState.value =
                    com.dezzy.music.together.TogetherSessionState.Error(
                        message = getString(R.string.invalid_code),
                        recoverable = true,
                    )
            }
            return
        }

        scope.launch(SilentHandler) {
            togetherSessionState.value = com.dezzy.music.together.TogetherSessionState.JoiningOnline(trimmedCode)
        }

        ioScope.launch(SilentHandler) {
            stopTogetherInternal()
            togetherIsOnlineSession = true
            
            val client = com.dezzy.music.listentogether.ListenTogetherClient.getInstance() 
                ?: com.dezzy.music.listentogether.ListenTogetherClient(applicationContext).also {
                    com.dezzy.music.listentogether.ListenTogetherClient.setInstance(it)
                }
                
            client.joinRoom(trimmedCode, displayName.trim().ifBlank { getString(R.string.together_role_guest) })
            
            togetherClientEventsJob?.cancel()
            togetherClientEventsJob = ioScope.launch(SilentHandler) {
                client.events.collect { event ->
                    when (event) {
                        is com.dezzy.music.listentogether.ListenTogetherEvent.JoinApproved -> {
                            togetherSelfParticipantId = event.userId
                            scope.launch(SilentHandler) {
                                togetherSessionState.value = com.dezzy.music.together.TogetherSessionState.Joined(
                                    role = com.dezzy.music.together.TogetherRole.Guest,
                                    sessionId = event.roomCode,
                                    selfParticipantId = event.userId,
                                    roomState = com.dezzy.music.together.TogetherRoomState(
                                        sessionId = event.roomCode,
                                        hostId = event.state.hostId,
                                        participants = event.state.users.map {
                                            com.dezzy.music.together.TogetherParticipant(
                                                id = it.userId,
                                                name = it.username,
                                                isHost = it.isHost,
                                                isConnected = it.isConnected
                                            )
                                        },
                                        // Permissive on purpose: this WS protocol carries no room
                                        // settings, and the defaults (control=false) both blocked
                                        // guest requests locally and force-reverted every remote
                                        // play state this client applied. The host enforces the
                                        // real room settings when it receives each request.
                                        settings = com.dezzy.music.together.TogetherRoomSettings(
                                            allowGuestsToAddTracks = true,
                                            allowGuestsToControlPlayback = true,
                                        )
                                    )
                                )
                            }
                            // Initial sync: mirror whatever the room is already playing,
                            // extrapolating the position for the time since the host reported it.
                            event.state.currentTrack?.let { track ->
                                val extra = if (event.state.isPlaying) {
                                    (System.currentTimeMillis() - event.state.lastUpdate)
                                        .coerceIn(0L, 30_000L)
                                } else 0L
                                applyListenTogetherSync(
                                    com.dezzy.music.listentogether.PlaybackActionPayload(
                                        action = com.dezzy.music.listentogether.PlaybackActions.CHANGE_TRACK,
                                        trackInfo = track,
                                        position = event.state.position + extra,
                                    )
                                )
                                if (!event.state.isPlaying) {
                                    applyListenTogetherSync(
                                        com.dezzy.music.listentogether.PlaybackActionPayload(
                                            action = com.dezzy.music.listentogether.PlaybackActions.PAUSE,
                                            position = event.state.position,
                                        )
                                    )
                                }
                            }
                        }
                        is com.dezzy.music.listentogether.ListenTogetherEvent.PlaybackSync -> {
                            // Mirror the host (guests apply everything the server relays; the
                            // guards inside make our own echoed requests no-ops).
                            if (com.dezzy.music.listentogether.ListenTogetherClient.getInstance()?.isHost != true) {
                                applyListenTogetherSync(event.action)
                            }
                        }
                        is com.dezzy.music.listentogether.ListenTogetherEvent.JoinRejected -> {
                            scope.launch(SilentHandler) {
                                togetherSessionState.value = com.dezzy.music.together.TogetherSessionState.Error(event.reason, true)
                            }
                        }
                        is com.dezzy.music.listentogether.ListenTogetherEvent.Disconnected -> {
                            scope.launch(SilentHandler) {
                                togetherSessionState.value = com.dezzy.music.together.TogetherSessionState.Error("Disconnected from server", true)
                            }
                        }
                        is com.dezzy.music.listentogether.ListenTogetherEvent.ConnectionError -> {
                            scope.launch(SilentHandler) {
                                val friendlyError = if (event.error.contains("200 OK") || event.error.contains("101")) {
                                    "El servidor está inactivo (despertando). Por favor, reintenta en 1-2 minutos."
                                } else {
                                    event.error
                                }
                                togetherSessionState.value = com.dezzy.music.together.TogetherSessionState.Error(friendlyError, true)
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    fun joinTogetherOnline(
        code: String,
        displayName: String,
    ) {
        ensureScopesActive()
        val trimmedCode = code.trim()
        if (trimmedCode.isBlank()) {
            scope.launch(SilentHandler) {
                togetherSessionState.value =
                    com.dezzy.music.together.TogetherSessionState.Error(
                        message = getString(R.string.invalid_code),
                        recoverable = true,
                    )
            }
            return
        }

        scope.launch(SilentHandler) {
            togetherSessionState.value = com.dezzy.music.together.TogetherSessionState.JoiningOnline(trimmedCode)
        }

        ioScope.launch(SilentHandler) {
            stopTogetherInternal()
            togetherIsOnlineSession = true

            val baseUrl = com.dezzy.music.together.TogetherOnlineEndpoint.baseUrlOrNull(dataStore)
            if (baseUrl == null) {
                scope.launch(SilentHandler) {
                    togetherSessionState.value =
                        com.dezzy.music.together.TogetherSessionState.Error(
                            message = getString(R.string.together_online_not_configured),
                            recoverable = true,
                        )
                }
                return@launch
            }

            val togetherToken = com.dezzy.music.BuildConfig.TOGETHER_BEARER_TOKEN.trim().takeIf { it.isNotBlank() }
            if (togetherToken == null) {
                scope.launch(SilentHandler) {
                    togetherSessionState.value =
                        com.dezzy.music.together.TogetherSessionState.Error(
                            message = getString(R.string.together_token_missing),
                            recoverable = true,
                        )
                }
                return@launch
            }

            val api = com.dezzy.music.together.TogetherOnlineApi(baseUrl = baseUrl, bearerToken = togetherToken)
            val resolved =
                runCatching { api.resolveCode(trimmedCode) }
                    .getOrElse { t ->
                        scope.launch(SilentHandler) {
                            togetherSessionState.value =
                                com.dezzy.music.together.TogetherSessionState.Error(
                                    message = togetherOnlineErrorMessage(t),
                                    recoverable = true,
                                )
                        }
                        reportException(t)
                        return@launch
                    }

            val client =
                com.dezzy.music.together.TogetherClient(
                    ioScope,
                    clientId = getOrCreateTogetherClientId(),
                    bearerToken = togetherToken,
                )
            togetherClient = client
            togetherClock = com.dezzy.music.together.TogetherClock()
            togetherSelfParticipantId = null
            togetherLastAppliedQueueHash = null

            togetherClientEventsJob?.cancel()
            togetherClientEventsJob =
                ioScope.launch(SilentHandler) {
                    client.events.collect { event ->
                        when (event) {
                            is com.dezzy.music.together.TogetherClientEvent.Welcome -> {
                                togetherSelfParticipantId = event.welcome.participantId
                                scope.launch(SilentHandler) {
                                    val state = togetherSessionState.value
                                    if (state is com.dezzy.music.together.TogetherSessionState.JoiningOnline) {
                                        val selfName = displayName.trim().ifBlank { getString(R.string.together_role_guest) }
                                        val initial =
                                            com.dezzy.music.together.TogetherRoomState(
                                                sessionId = resolved.sessionId,
                                                hostId = togetherHostId,
                                                participants =
                                                    listOf(
                                                        com.dezzy.music.together.TogetherParticipant(
                                                            id = event.welcome.participantId,
                                                            name = selfName,
                                                            isHost = false,
                                                            isPending = event.welcome.isPending,
                                                            isConnected = true,
                                                        ),
                                                    ),
                                                settings = event.welcome.settings,
                                                queue = emptyList(),
                                                queueHash = "",
                                                currentIndex = 0,
                                                isPlaying = false,
                                                positionMs = 0L,
                                                repeatMode = 0,
                                                shuffleEnabled = false,
                                                sentAtElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
                                            )
                                        togetherSessionState.value =
                                            com.dezzy.music.together.TogetherSessionState.Joined(
                                                role = com.dezzy.music.together.TogetherRole.Guest,
                                                sessionId = resolved.sessionId,
                                                selfParticipantId = event.welcome.participantId,
                                                roomState = initial,
                                            )
                                    }
                                }
                                startTogetherHeartbeat(resolved.sessionId, client)
                            }

                            is com.dezzy.music.together.TogetherClientEvent.RoomState -> {
                                applyRemoteRoomState(event.state)
                            }

                            is com.dezzy.music.together.TogetherClientEvent.JoinDecision -> {
                                if (!event.decision.approved) {
                                    scope.launch(SilentHandler) {
                                        togetherSessionState.value =
                                            com.dezzy.music.together.TogetherSessionState.Error(
                                                message = getString(R.string.not_allowed),
                                                recoverable = true,
                                            )
                                    }
                                    ioScope.launch(SilentHandler) { stopTogetherInternal() }
                                }
                            }

                            is com.dezzy.music.together.TogetherClientEvent.ServerIssue -> {
                                Timber.tag("Together").w("server issue (online) code=${event.code.orEmpty()} message=${event.message}")
                                when (event.code) {
                                    "GUEST_CONTROL_DISABLED" -> {
                                        showTogetherNotice(event.message, key = "GUEST_CONTROL_DISABLED")
                                        val joined =
                                            togetherSessionState.value as? com.dezzy.music.together.TogetherSessionState.Joined
                                        if (joined?.role is com.dezzy.music.together.TogetherRole.Guest) {
                                            togetherPendingGuestControl = null
                                            togetherLastSentControlAction = null
                                            scope.launch(SilentHandler) { applyRemoteRoomState(joined.roomState) }
                                        }
                                    }

                                    "GUEST_ADD_DISABLED" -> {
                                        showTogetherNotice(event.message, key = "GUEST_ADD_DISABLED")
                                    }

                                    "HOST_OFFLINE" -> {
                                        showTogetherNotice(event.message, key = "HOST_OFFLINE")
                                    }

                                    else -> {
                                        scope.launch(SilentHandler) {
                                            togetherSessionState.value =
                                                com.dezzy.music.together.TogetherSessionState.Error(
                                                    message = event.message,
                                                    recoverable = true,
                                                )
                                        }
                                        ioScope.launch(SilentHandler) { stopTogetherInternal() }
                                    }
                                }
                            }

                            is com.dezzy.music.together.TogetherClientEvent.HeartbeatPong -> {
                                val clock = togetherClock ?: return@collect
                                clock.onPong(
                                    sentAtElapsedMs = event.pong.clientElapsedRealtimeMs,
                                    receivedAtElapsedMs = event.receivedAtElapsedRealtimeMs,
                                    serverElapsedMs = event.pong.serverElapsedRealtimeMs,
                                )
                            }

                            is com.dezzy.music.together.TogetherClientEvent.Error -> {
                                scope.launch(SilentHandler) {
                                    togetherSessionState.value =
                                        com.dezzy.music.together.TogetherSessionState.Error(
                                            message = event.message,
                                            recoverable = true,
                                        )
                                }
                                ioScope.launch(SilentHandler) { stopTogetherInternal() }
                            }

                            com.dezzy.music.together.TogetherClientEvent.Disconnected -> {
                                val current = togetherSessionState.value
                                if (current is com.dezzy.music.together.TogetherSessionState.Idle) return@collect
                                scope.launch(SilentHandler) {
                                    val currentState = togetherSessionState.value
                                    togetherSessionState.value =
                                        com.dezzy.music.together.TogetherSessionState.Error(
                                            message =
                                                if (currentState is com.dezzy.music.together.TogetherSessionState.Joined &&
                                                    currentState.role is com.dezzy.music.together.TogetherRole.Guest
                                                ) {
                                                    getString(R.string.together_host_left_session)
                                                } else {
                                                    getString(R.string.network_unavailable)
                                                },
                                            recoverable = true,
                                        )
                                }
                                ioScope.launch(SilentHandler) { stopTogetherInternal() }
                            }
                        }
                    }
                }

            val wsUrl =
                com.dezzy.music.together.TogetherOnlineEndpoint.onlineWebSocketUrlOrNull(
                    rawWsUrl = resolved.wsUrl,
                    baseUrl = baseUrl,
                )
            if (wsUrl == null) {
                scope.launch(SilentHandler) {
                    togetherSessionState.value =
                        com.dezzy.music.together.TogetherSessionState.Error(
                            message = "Connection failed: Invalid server websocket URL",
                            recoverable = true,
                        )
                }
                ioScope.launch(SilentHandler) { stopTogetherInternal() }
                return@launch
            }

            client.connect(
                wsUrl = wsUrl,
                sessionId = resolved.sessionId,
                sessionKey = resolved.guestKey,
                displayName = displayName.trim().ifBlank { getString(R.string.together_role_guest) },
            )
        }
    }

    fun leaveTogether() {
        ensureScopesActive()
        scope.launch(SilentHandler) {
            togetherSessionState.value = com.dezzy.music.together.TogetherSessionState.Idle
        }
        ioScope.launch(SilentHandler) { stopTogetherInternal() }
    }

    fun updateTogetherSettings(settings: com.dezzy.music.together.TogetherRoomSettings) {
        val server = togetherServer
        val onlineHost = togetherOnlineHost
        if (server == null && onlineHost == null) return
        ioScope.launch(SilentHandler) {
            server?.updateSettings(settings)
            onlineHost?.updateSettings(settings)
        }
    }

    fun approveTogetherParticipant(participantId: String, approved: Boolean) {
        val server = togetherServer
        val onlineHost = togetherOnlineHost
        if (server == null && onlineHost == null) return
        ioScope.launch(SilentHandler) {
            server?.approveParticipant(participantId, approved)
            onlineHost?.approveParticipant(participantId, approved)
        }
    }

    fun kickTogetherParticipant(participantId: String, reason: String? = null) {
        val onlineHost = togetherOnlineHost ?: return
        ioScope.launch(SilentHandler) {
            onlineHost.kickParticipant(participantId, reason)
        }
    }

    fun banTogetherParticipant(participantId: String, reason: String? = null) {
        val onlineHost = togetherOnlineHost ?: return
        ioScope.launch(SilentHandler) {
            onlineHost.banParticipant(participantId, reason)
        }
    }

    fun requestTogetherControl(action: com.dezzy.music.together.ControlAction) {
        val client = com.dezzy.music.listentogether.ListenTogetherClient.getInstance() ?: return
        val state = togetherSessionState.value as? com.dezzy.music.together.TogetherSessionState.Joined ?: return
        if (state.role !is com.dezzy.music.together.TogetherRole.Guest) return
        if (!state.roomState.settings.allowGuestsToControlPlayback) {
            Timber.tag("Together").i("control blocked locally")
            showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_CONTROL_DISABLED_LOCAL")
            return
        }

        // Convert ControlAction to PlaybackAction string
        val actionStr = when (action) {
            com.dezzy.music.together.ControlAction.Play -> com.dezzy.music.listentogether.PlaybackActions.PLAY
            com.dezzy.music.together.ControlAction.Pause -> com.dezzy.music.listentogether.PlaybackActions.PAUSE
            is com.dezzy.music.together.ControlAction.SeekToIndex -> com.dezzy.music.listentogether.PlaybackActions.SEEK
            is com.dezzy.music.together.ControlAction.SeekToTrack -> com.dezzy.music.listentogether.PlaybackActions.CHANGE_TRACK
            else -> return
        }
        
        val trackId = if (action is com.dezzy.music.together.ControlAction.SeekToTrack) action.trackId else null
        val position = if (action is com.dezzy.music.together.ControlAction.SeekToIndex) action.positionMs else if (action is com.dezzy.music.together.ControlAction.SeekToTrack) action.positionMs else null

        client.sendPlaybackAction(actionStr, trackId = trackId, position = position)
    }

    fun requestTogetherAddTrack(
        track: com.dezzy.music.together.TogetherTrack,
        mode: com.dezzy.music.together.AddTrackMode,
    ) {
        val client = com.dezzy.music.listentogether.ListenTogetherClient.getInstance() ?: return
        val state = togetherSessionState.value as? com.dezzy.music.together.TogetherSessionState.Joined ?: return
        if (state.role !is com.dezzy.music.together.TogetherRole.Guest) return
        if (!state.roomState.settings.allowGuestsToAddTracks) {
            showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_ADD_DISABLED_LOCAL")
            return
        }

        val trackInfo = com.dezzy.music.listentogether.TrackInfo(
            id = track.id,
            title = track.title,
            artist = track.artists.joinToString(", "),
            duration = track.durationSec * 1000L,
            thumbnail = track.thumbnailUrl
        )

        val actionStr = when (mode) {
            com.dezzy.music.together.AddTrackMode.PLAY_NEXT -> com.dezzy.music.listentogether.PlaybackActions.QUEUE_ADD
            com.dezzy.music.together.AddTrackMode.ADD_TO_QUEUE -> com.dezzy.music.listentogether.PlaybackActions.QUEUE_ADD
        }
        
        val insertNext = mode == com.dezzy.music.together.AddTrackMode.PLAY_NEXT

        client.sendPlaybackAction(actionStr, trackInfo = trackInfo, insertNext = insertNext)
    }

    private suspend fun handleTogetherHostEvent(
        event: com.dezzy.music.together.TogetherServerEvent,
        currentSettings: suspend () -> com.dezzy.music.together.TogetherRoomSettings,
    ) {
        when (event) {
            is com.dezzy.music.together.TogetherServerEvent.ControlRequested -> {
                val settings = currentSettings()
                if (!settings.allowGuestsToControlPlayback) return
                applyHostControl(event.request.action)
            }

            is com.dezzy.music.together.TogetherServerEvent.AddTrackRequested -> {
                val settings = currentSettings()
                if (!settings.allowGuestsToAddTracks) return
                applyHostAddTrack(event.request.track, event.request.mode)
            }

            is com.dezzy.music.together.TogetherServerEvent.Error -> {
                val current = togetherSessionState.value
                if (current is com.dezzy.music.together.TogetherSessionState.Idle) return
                togetherSessionState.value =
                    com.dezzy.music.together.TogetherSessionState.Error(
                        message = event.message,
                        recoverable = true,
                    )
                ioScope.launch(SilentHandler) { stopTogetherInternal() }
            }

            else -> Unit
        }
    }

    private suspend fun applyHostControl(action: com.dezzy.music.together.ControlAction) {
        withContext(Dispatchers.Main) {
            when (action) {
                com.dezzy.music.together.ControlAction.Play -> {
                    if (!player.playWhenReady) {
                        player.prepare()
                        player.playWhenReady = true
                    }
                }

                com.dezzy.music.together.ControlAction.Pause -> {
                    if (player.playWhenReady) {
                        player.playWhenReady = false
                    }
                }

                is com.dezzy.music.together.ControlAction.SeekTo -> {
                    player.seekTo(action.positionMs.coerceAtLeast(0L))
                    player.prepare()
                }

                com.dezzy.music.together.ControlAction.SkipNext -> {
                    if (player.hasNextMediaItem()) {
                        skipToNextManual()
                    }
                }

                com.dezzy.music.together.ControlAction.SkipPrevious -> {
                    if (player.hasPreviousMediaItem()) {
                        skipToPreviousManual()
                    }
                }

                is com.dezzy.music.together.ControlAction.SeekToTrack -> {
                    val trackId = action.trackId.trim()
                    if (trackId.isNotBlank()) {
                        val idx =
                            player.mediaItems.indexOfFirst {
                                val metaId = it.metadata?.id
                                it.mediaId == trackId || metaId == trackId
                            }
                        if (idx >= 0 && idx < player.mediaItemCount) {
                            player.seekTo(idx, action.positionMs.coerceAtLeast(0L))
                            player.prepare()
                        }
                    }
                }

                is com.dezzy.music.together.ControlAction.SeekToIndex -> {
                    val idx = action.index.coerceAtLeast(0)
                    if (idx < player.mediaItemCount) {
                        player.seekTo(idx, action.positionMs.coerceAtLeast(0L))
                        player.prepare()
                    }
                }

                is com.dezzy.music.together.ControlAction.SetRepeatMode -> {
                    if (player.repeatMode != action.repeatMode) {
                        player.repeatMode = action.repeatMode
                    }
                }

                is com.dezzy.music.together.ControlAction.SetShuffleEnabled -> {
                    if (player.shuffleModeEnabled != action.shuffleEnabled) {
                        player.shuffleModeEnabled = action.shuffleEnabled
                    }
                }
            }
        }
    }

    private suspend fun applyHostAddTrack(
        track: com.dezzy.music.together.TogetherTrack,
        mode: com.dezzy.music.together.AddTrackMode,
    ) {
        val mediaItem = track.toMediaMetadata().toMediaItem()
        withContext(Dispatchers.Main) {
            when (mode) {
                com.dezzy.music.together.AddTrackMode.PLAY_NEXT -> playNext(listOf(mediaItem))
                com.dezzy.music.together.AddTrackMode.ADD_TO_QUEUE -> addToQueue(listOf(mediaItem))
            }
        }
    }

    private suspend fun buildTogetherRoomState(
        sessionId: String,
        hostId: String,
    ): com.dezzy.music.together.TogetherRoomState {
        return withContext(Dispatchers.Main) {
            val tracks =
                player.mediaItems.mapNotNull { it.metadata }.map { meta ->
                    com.dezzy.music.together.TogetherTrack(
                        id = meta.id,
                        title = meta.title,
                        artists = meta.artists.map { it.name },
                        durationSec = meta.duration,
                        thumbnailUrl = meta.thumbnailUrl,
                    )
                }

            val queueHash = com.dezzy.music.utils.md5(tracks.joinToString(separator = "|") { it.id })

            com.dezzy.music.together.TogetherRoomState(
                sessionId = sessionId,
                hostId = hostId,
                settings = com.dezzy.music.together.TogetherRoomSettings(),
                participants = emptyList(),
                queue = tracks,
                queueHash = queueHash,
                currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                isPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                repeatMode = player.repeatMode,
                shuffleEnabled = player.shuffleModeEnabled,
                sentAtElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
            )
        }
    }

    private suspend fun applyRemoteRoomState(state: com.dezzy.music.together.TogetherRoomState) {
        val pid = togetherSelfParticipantId ?: return
        val now = android.os.SystemClock.elapsedRealtime()

        val pending = togetherPendingGuestControl
        if (pending != null) {
            val currentTrackId = state.queue.getOrNull(state.currentIndex.coerceAtLeast(0))?.id
            val mismatch =
                (pending.desiredIsPlaying != null && state.isPlaying != pending.desiredIsPlaying) ||
                    (pending.desiredIndex != null && state.currentIndex != pending.desiredIndex) ||
                    (pending.desiredTrackId != null && currentTrackId != pending.desiredTrackId)
            if (now >= pending.expiresAtElapsedMs) {
                if ((pending.desiredIndex != null || pending.desiredTrackId != null) &&
                    now - pending.requestedAtElapsedMs >= 1200L &&
                    mismatch
                ) {
                    showTogetherNotice(getString(R.string.together_song_change_failed), key = "GUEST_SEEK_TIMEOUT")
                }
                togetherPendingGuestControl = null
            } else {
                if (mismatch) return
                togetherPendingGuestControl = null
            }
        }

        val lastSentAt = togetherLastAppliedRoomStateSentAtElapsedMs
        val sentAt = state.sentAtElapsedRealtimeMs
        if (sentAt > 0L && lastSentAt > 0L && sentAt <= lastSentAt) return

        val offset = if (togetherIsOnlineSession) 0L else (togetherClock?.snapshot()?.estimatedOffsetMs ?: 0L)
        val correctedSentAt = sentAt + offset
        val estimatedOnlineLatency = if (togetherIsOnlineSession) 1200L else 0L
        val delta = if (togetherIsOnlineSession) estimatedOnlineLatency else (now - correctedSentAt).coerceAtLeast(0L)
        val targetPos =
            if (state.isPlaying) (state.positionMs + delta).coerceAtLeast(0L) else state.positionMs.coerceAtLeast(0L)

        withContext(Dispatchers.Main) {
            togetherApplyingRemote = true
            togetherSuppressEchoUntilElapsedMs = android.os.SystemClock.elapsedRealtime() + 450L
            try {
                val desiredItems = state.queue.map { it.toMediaMetadata().toMediaItem() }
                val desiredIds = state.queue.map { it.id }
                val desiredHash = state.queueHash
                val localIds = player.mediaItems.mapNotNull { it.metadata?.id ?: it.mediaId }.filter { it.isNotBlank() }
                val localHash = if (localIds.isEmpty()) "" else com.dezzy.music.utils.md5(localIds.joinToString(separator = "|"))
                val needsRebuild =
                    desiredItems.isNotEmpty() &&
                        (
                            (desiredHash.isNotBlank() && desiredHash != localHash) ||
                                (desiredHash.isBlank() && desiredIds != localIds)
                        )

                if (desiredItems.isNotEmpty() && needsRebuild) {
                    togetherLastAppliedQueueHash = desiredHash.ifBlank { localHash }
                    val startIndex = state.currentIndex.coerceIn(0, desiredItems.lastIndex)
                    suppressAutoPlayback = false
                    currentQueue =
                        com.dezzy.music.playback.queues.ListQueue(
                            title = getString(R.string.music_player),
                            items = desiredItems,
                            startIndex = startIndex,
                            position = targetPos,
                        )
                    queueTitle = null
                    player.setMediaItems(desiredItems, startIndex, targetPos)
                    player.prepare()
                    player.repeatMode = state.repeatMode
                    player.shuffleModeEnabled = state.shuffleEnabled
                    player.playWhenReady = state.isPlaying
                    togetherLastRemoteAppliedIndex = startIndex
                } else {
                    val index = state.currentIndex.coerceAtLeast(0)
                    val indexChanged = player.mediaItemCount > 0 && index != player.currentMediaItemIndex
                    val stateChanged =
                        player.repeatMode != state.repeatMode ||
                            player.shuffleModeEnabled != state.shuffleEnabled ||
                            player.playWhenReady != state.isPlaying

                    if (indexChanged) {
                        player.seekTo(index.coerceAtMost(player.mediaItemCount - 1), targetPos)
                        player.prepare()
                        player.playWhenReady = state.isPlaying
                    } else if (stateChanged) {
                        if (player.repeatMode != state.repeatMode) player.repeatMode = state.repeatMode
                        if (player.shuffleModeEnabled != state.shuffleEnabled) player.shuffleModeEnabled = state.shuffleEnabled
                        if (player.playWhenReady != state.isPlaying) {
                            player.playWhenReady = state.isPlaying
                            val drift = kotlin.math.abs(player.currentPosition - targetPos)
                            if (drift > 100) {
                                player.seekTo(targetPos)
                                player.prepare()
                            }
                        }
                    } else {
                        val drift = kotlin.math.abs(player.currentPosition - targetPos)
                        val seekThreshold = if (togetherIsOnlineSession) 4000L else 2000L
                        val threshold = if (state.isPlaying) seekThreshold else 200L
                        
                        if (drift > threshold) {
                            player.seekTo(targetPos)
                            player.prepare()
                        }
                    }
                    togetherLastRemoteAppliedIndex = index
                }
                togetherLastRemoteAppliedPlayWhenReady = state.isPlaying
                togetherLastAppliedRoomStateSentAtElapsedMs = sentAt

                togetherSessionState.value =
                    com.dezzy.music.together.TogetherSessionState.Joined(
                        role = com.dezzy.music.together.TogetherRole.Guest,
                        sessionId = state.sessionId,
                        selfParticipantId = pid,
                        roomState = state,
                    )
            } finally {
                togetherApplyingRemote = false
            }
        }
    }

    private fun startTogetherHeartbeat(sessionId: String, client: com.dezzy.music.together.TogetherClient) {
        togetherHeartbeatJob?.cancel()
        togetherHeartbeatJob =
            ioScope.launch(SilentHandler) {
                var pingId = 0L
                while (togetherClient === client) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    client.sendHeartbeat(sessionId = sessionId, pingId = pingId++, clientElapsedRealtimeMs = now)
                    kotlinx.coroutines.delay(2000)
                }
            }
    }

    private suspend fun stopTogetherInternal() {
        togetherBroadcastJob?.cancel()
        togetherBroadcastJob = null

        togetherOnlineConnectJob?.cancel()
        togetherOnlineConnectJob = null

        togetherClientEventsJob?.cancel()
        togetherClientEventsJob = null

        togetherHeartbeatJob?.cancel()
        togetherHeartbeatJob = null

        togetherClock = null
        togetherSelfParticipantId = null
        togetherLastAppliedQueueHash = null
        togetherIsOnlineSession = false
        togetherApplyingRemote = false
        togetherSuppressEchoUntilElapsedMs = 0L
        togetherLastAppliedRoomStateSentAtElapsedMs = 0L
        togetherLastRemoteAppliedPlayWhenReady = null
        togetherLastRemoteAppliedIndex = -1
        togetherLastSentControlAtElapsedMs = 0L
        togetherLastSentControlAction = null
        togetherPendingGuestControl = null

        try {
            togetherClient?.disconnect()
        } catch (_: Exception) {}
        togetherClient = null

        try {
            togetherOnlineHost?.disconnect()
        } catch (_: Exception) {}
        togetherOnlineHost = null

        try {
            togetherServer?.stop()
        } catch (_: Exception) {}
        togetherServer = null
    }

    private fun com.dezzy.music.together.TogetherTrack.toMediaMetadata(): com.dezzy.music.models.MediaMetadata {
        return com.dezzy.music.models.MediaMetadata(
            id = id,
            title = title,
            artists = artists.map { name -> com.dezzy.music.models.MediaMetadata.Artist(id = null, name = name) },
            duration = durationSec,
            thumbnailUrl = thumbnailUrl,
            album = null,
            setVideoId = null,
            explicit = false,
            liked = false,
            likedDate = null,
            inLibrary = null,
        )
    }

    // ─── Listen Together (WS) playback bridge ────────────────────────────────
    // The UI's active session path (startTogetherHost / joinTogether) speaks through
    // ListenTogetherClient. These helpers wire actual playback into that channel: the host
    // publishes its player state (CHANGE_TRACK / PLAY / PAUSE / SEEK) and everyone applies
    // incoming SYNC_PLAYBACK with echo guards. Without them, rooms connected but never synced.

    /** The active WS client while this device hosts a Listen Together room, or null. */
    private fun listenTogetherHostClient(): com.dezzy.music.listentogether.ListenTogetherClient? =
        com.dezzy.music.listentogether.ListenTogetherClient.getInstance()
            ?.takeIf { it.isInRoom && it.isHost }

    /** Host → room: publish a local playback change so guests can mirror it. */
    private fun relayListenTogetherPlayback(
        action: String,
        position: Long? = null,
        trackId: String? = null,
        trackInfo: com.dezzy.music.listentogether.TrackInfo? = null,
    ) {
        val client = listenTogetherHostClient() ?: return
        client.sendPlaybackAction(action, trackId = trackId, position = position, trackInfo = trackInfo)
    }

    private fun com.dezzy.music.models.MediaMetadata.toListenTogetherTrackInfo() =
        com.dezzy.music.listentogether.TrackInfo(
            id = id,
            title = title,
            artist = artists.joinToString(", ") { it.name },
            duration = duration * 1000L,
            thumbnail = thumbnailUrl,
        )

    private fun com.dezzy.music.listentogether.TrackInfo.toMediaMetadata() =
        com.dezzy.music.models.MediaMetadata(
            id = id,
            title = title,
            artists = listOf(com.dezzy.music.models.MediaMetadata.Artist(id = null, name = artist)),
            duration = (duration / 1000L).toInt(),
            thumbnailUrl = thumbnail,
            album = null,
            setVideoId = null,
            explicit = false,
            liked = false,
            likedDate = null,
            inLibrary = null,
        )

    /**
     * Applies a relayed SYNC_PLAYBACK action to the local player. Used by guests to mirror the
     * host and by the host to honor permitted guest requests. Every branch is idempotent or
     * guarded (track-id check, seek threshold), so the server echoing a device's own action
     * back to it is harmless.
     */
    private fun applyListenTogetherSync(payload: com.dezzy.music.listentogether.PlaybackActionPayload) {
        scope.launch(SilentHandler) {
            withContext(Dispatchers.Main) {
                togetherApplyingRemote = true
                togetherSuppressEchoUntilElapsedMs = android.os.SystemClock.elapsedRealtime() + 800L
                try {
                    when (payload.action) {
                        com.dezzy.music.listentogether.PlaybackActions.PLAY -> {
                            payload.position?.let { pos ->
                                if (kotlin.math.abs(player.currentPosition - pos) > 1500) player.seekTo(pos)
                            }
                            if (player.playbackState == Player.STATE_IDLE) player.prepare()
                            togetherLastRemoteAppliedPlayWhenReady = true
                            player.playWhenReady = true
                        }

                        com.dezzy.music.listentogether.PlaybackActions.PAUSE -> {
                            togetherLastRemoteAppliedPlayWhenReady = false
                            player.playWhenReady = false
                            payload.position?.let { pos ->
                                if (kotlin.math.abs(player.currentPosition - pos) > 1500) player.seekTo(pos)
                            }
                        }

                        com.dezzy.music.listentogether.PlaybackActions.SEEK -> {
                            payload.position?.let { pos ->
                                if (kotlin.math.abs(player.currentPosition - pos) > 800) {
                                    player.seekTo(pos)
                                    player.prepare()
                                }
                            }
                        }

                        com.dezzy.music.listentogether.PlaybackActions.CHANGE_TRACK -> {
                            val ti = payload.trackInfo ?: return@withContext
                            val currentId = player.currentMetadata?.id
                            if (currentId == ti.id) return@withContext
                            suppressAutoPlayback = false
                            player.setMediaItems(
                                listOf(ti.toMediaMetadata().toMediaItem()),
                                0,
                                payload.position ?: 0L,
                            )
                            player.prepare()
                            togetherLastRemoteAppliedPlayWhenReady = true
                            player.playWhenReady = true
                        }

                        com.dezzy.music.listentogether.PlaybackActions.QUEUE_ADD -> {
                            val ti = payload.trackInfo ?: return@withContext
                            val item = ti.toMediaMetadata().toMediaItem()
                            if (payload.insertNext == true && player.mediaItemCount > 0) {
                                player.addMediaItem(player.currentMediaItemIndex + 1, item)
                            } else {
                                player.addMediaItem(item)
                            }
                            player.prepare()
                        }

                        else -> {}
                    }
                } finally {
                    togetherApplyingRemote = false
                }
            }
        }
    }

    private fun getLocalIpv4Address(): String? {
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { it.isNotBlank() && it != "127.0.0.1" }
        }.getOrNull()
    }

    private fun toggleLibrary() {
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLibrary())
            }
        }
    }

    fun toggleLike() {
         database.query {
             // Fiabilidad: si la canción actual todavía NO está en la tabla `song`
             // (recién reproducida, sin evento registrado aún), `currentSong.value`
             // es null y antes el "me gusta" no hacía nada ("a veces no funciona").
             // En ese caso se inserta desde la metadata ya marcada como favorita.
             val existing = currentSong.value?.song
             val song: SongEntity? =
                 if (existing != null) {
                     existing.toggleLike().also { update(it) }
                 } else {
                     val meta = currentMediaMetadata.value ?: return@query
                     var inserted: SongEntity? = null
                     insert(meta) { orig -> orig.toggleLike().also { inserted = it } }
                     inserted
                 }
             song ?: return@query
             syncUtils.likeSong(song)

             // Check if auto-download on like is enabled and the song is now liked
             if (dataStore.get(AutoDownloadOnLikeKey, true) && song.liked) {
                 // Trigger download for the liked song
                 val downloadRequest = androidx.media3.exoplayer.offline.DownloadRequest
                     .Builder(song.id, song.id.toUri())
                     .setCustomCacheKey(song.id)
                     .setData(song.title.toByteArray())
                     .build()
                 androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                     this@MusicService,
                     ExoDownloadService::class.java,
                     downloadRequest,
                     false
                 )
             }
         }
     }

    fun toggleStartRadio() {
        startRadioSeamlessly()
    }

    private fun decodeBandLevelsMb(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { EqualizerJson.json.decodeFromString<List<Int>>(raw) }.getOrNull() ?: emptyList()
    }

    private fun encodeBandLevelsMb(levelsMb: List<Int>): String {
        return runCatching { EqualizerJson.json.encodeToString(levelsMb) }.getOrNull().orEmpty()
    }

    private fun readEqSettingsFromPrefs(prefs: Preferences): EqSettings {
        val levels = decodeBandLevelsMb(prefs[EqualizerBandLevelsMbKey])
        return EqSettings(
            enabled = prefs[EqualizerEnabledKey] ?: false,
            bandLevelsMb = levels,
            outputGainEnabled = prefs[EqualizerOutputGainEnabledKey] ?: false,
            outputGainMb = prefs[EqualizerOutputGainMbKey] ?: 0,
            bassBoostEnabled = prefs[EqualizerBassBoostEnabledKey] ?: false,
            bassBoostStrength = (prefs[EqualizerBassBoostStrengthKey] ?: 0).coerceIn(0, 1000),
            virtualizerEnabled = prefs[EqualizerVirtualizerEnabledKey] ?: false,
            virtualizerStrength = (prefs[EqualizerVirtualizerStrengthKey] ?: 0).coerceIn(0, 1000),
        )
    }

    /**
     * Modo automático: detecta el género de la canción en curso y aplica su curva de
     * ecualización. La detección reusa `GenreClassifier` (heurística local sobre
     * título/álbum/artista y, si no basta, los keywords de InnerTube) con la misma
     * caché en disco que la biblioteca, así que normalmente no cuesta ni una petición.
     *
     * Solo escribe las bandas: el resto de ajustes (bass boost, virtualizador, ganancia)
     * quedan como los dejó el usuario.
     */
    private fun applyGenreEqForCurrentSong(metadata: com.dezzy.music.models.MediaMetadata?) {
        val mediaId = metadata?.id ?: return
        genreEqJob?.cancel()
        genreEqJob = ioScope.launch {
            if (!dataStore.get(EqualizerAutoGenreKey, false)) return@launch
            if (!dataStore.get(EqualizerEnabledKey, false)) return@launch

            val cache = GenreClassifier.loadCache(this@MusicService)
            val genre = cache[mediaId]
                ?: GenreClassifier.classifyText(
                    metadata.title,
                    metadata.album?.title,
                    metadata.artists.joinToString(" ") { it.name },
                )
                ?: GenreClassifier.classifyWithInnerTube(mediaId)

            if (genre == null || !GenreEqPresets.hasCurve(genre)) {
                Timber.tag("EqAutoGenre").d("Sin curva para ${metadata.title} (género=$genre)")
                return@launch
            }
            if (cache[mediaId] != genre) {
                GenreClassifier.saveCache(this@MusicService, cache + (mediaId to genre))
            }

            val caps = eqCapabilities.value
            val centerFreqs = caps?.centerFreqHz.orEmpty()
            if (centerFreqs.isEmpty()) return@launch
            val levels = GenreEqPresets.bandLevelsForCurve(
                genreKey = genre,
                centerFreqHz = centerFreqs,
                minMb = caps?.minBandLevelMb ?: -1500,
                maxMb = caps?.maxBandLevelMb ?: 1500,
            ) ?: return@launch

            val encoded = encodeBandLevelsMb(levels)
            if (encoded.isBlank()) return@launch
            Timber.tag("EqAutoGenre").i("Aplicando curva '$genre' a ${metadata.title}")
            dataStore.edit { prefs ->
                prefs[EqualizerBandLevelsMbKey] = encoded
                prefs[EqualizerSelectedProfileIdKey] = "genre:$genre"
                prefs[EqualizerDetectedGenreKey] = genre
            }
        }
    }

    fun applyEqFlatPreset() {
        ioScope.launch {
            val caps = eqCapabilities.value
            val bandCount = caps?.bandCount ?: runCatching { equalizer?.numberOfBands?.toInt() }.getOrNull() ?: 0
            val encoded = encodeBandLevelsMb(List(bandCount.coerceAtLeast(0)) { 0 })
            dataStore.edit { prefs ->
                prefs[EqualizerEnabledKey] = true
                prefs[EqualizerBandLevelsMbKey] = encoded
                prefs[EqualizerSelectedProfileIdKey] = "flat"
            }
        }
    }

    fun applySystemEqPreset(presetIndex: Int) {
        scope.launch {
            ensureAudioEffects(player.audioSessionId)
            val eq = equalizer ?: return@launch
            val maxPreset = runCatching { eq.numberOfPresets.toInt() }.getOrNull() ?: 0
            if (presetIndex !in 0 until maxPreset) return@launch

            runCatching { eq.usePreset(presetIndex.toShort()) }.getOrNull() ?: return@launch

            val bandCount = runCatching { eq.numberOfBands.toInt() }.getOrNull() ?: 0
            val levels =
                (0 until bandCount).map { band ->
                    runCatching { eq.getBandLevel(band.toShort()).toInt() }.getOrNull() ?: 0
                }

            val encoded = encodeBandLevelsMb(levels)
            if (encoded.isBlank()) return@launch

            ioScope.launch {
                dataStore.edit { prefs ->
                    prefs[EqualizerEnabledKey] = true
                    prefs[EqualizerBandLevelsMbKey] = encoded
                    prefs[EqualizerSelectedProfileIdKey] = "system:$presetIndex"
                }
            }
        }
    }

    private fun resampleLevelsByIndex(levelsMb: List<Int>, targetCount: Int): List<Int> {
        if (targetCount <= 0) return emptyList()
        if (levelsMb.isEmpty()) return List(targetCount) { 0 }
        if (levelsMb.size == targetCount) return levelsMb
        if (targetCount == 1) return listOf(levelsMb.sum() / levelsMb.size)

        val lastIndex = levelsMb.lastIndex.toFloat().coerceAtLeast(1f)
        return List(targetCount) { i ->
            val pos = i.toFloat() * lastIndex / (targetCount - 1).toFloat()
            val lo = kotlin.math.floor(pos).toInt().coerceIn(0, levelsMb.lastIndex)
            val hi = kotlin.math.ceil(pos).toInt().coerceIn(0, levelsMb.lastIndex)
            val t = (pos - lo.toFloat()).coerceIn(0f, 1f)
            val a = levelsMb[lo]
            val b = levelsMb[hi]
            (a + ((b - a) * t)).toInt()
        }
    }

    private fun updateEqCapabilitiesFromEffect(eq: Equalizer) {
        val bandCount = eq.numberOfBands.toInt().coerceAtLeast(0)
        val range = runCatching { eq.bandLevelRange }.getOrNull()
        val minMb = range?.getOrNull(0)?.toInt() ?: -1500
        val maxMb = range?.getOrNull(1)?.toInt() ?: 1500
        val center =
            (0 until bandCount).map { band ->
                (runCatching { eq.getCenterFreq(band.toShort()) }.getOrNull() ?: 0) / 1000
            }
        val presets =
            (0 until eq.numberOfPresets.toInt()).map { idx ->
                runCatching { eq.getPresetName(idx.toShort()).toString() }.getOrNull() ?: "Preset ${idx + 1}"
            }
        eqCapabilities.value =
            EqCapabilities(
                bandCount = bandCount,
                minBandLevelMb = minMb,
                maxBandLevelMb = maxMb,
                centerFreqHz = center,
                systemPresets = presets,
            )
    }

    private fun releaseAudioEffects() {
        audioEffectsSessionId = null
        try {
            equalizer?.release()
        } catch (_: Exception) {
        }
        try {
            bassBoost?.release()
        } catch (_: Exception) {
        }
        try {
            virtualizer?.release()
        } catch (_: Exception) {
        }
        try {
            loudnessEnhancer?.release()
        } catch (_: Exception) {
        }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        eqCapabilities.value = null
    }

    private fun ensureAudioEffects(sessionId: Int) {
        if (sessionId <= 0) return
        if (audioEffectsSessionId == sessionId && equalizer != null) return

        releaseAudioEffects()
        audioEffectsSessionId = sessionId

        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, sessionId) }.getOrNull()
        virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
        loudnessEnhancer = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()

        equalizer?.let(::updateEqCapabilitiesFromEffect)
        applyEqSettingsToEffects(desiredEqSettings.value)
    }

    private fun applyEqSettingsToEffects(settings: EqSettings) {
        val eq = equalizer ?: return
        val caps = eqCapabilities.value
        val bandCount = caps?.bandCount ?: eq.numberOfBands.toInt()
        val minMb = caps?.minBandLevelMb ?: runCatching { eq.bandLevelRange.getOrNull(0)?.toInt() }.getOrNull() ?: -1500
        val maxMb = caps?.maxBandLevelMb ?: runCatching { eq.bandLevelRange.getOrNull(1)?.toInt() }.getOrNull() ?: 1500

        val levels = resampleLevelsByIndex(settings.bandLevelsMb, bandCount)
        runCatching { eq.enabled = settings.enabled }

        for (band in 0 until bandCount) {
            val levelMb = levels.getOrNull(band)?.coerceIn(minMb, maxMb) ?: 0
            runCatching { eq.setBandLevel(band.toShort(), levelMb.toShort()) }
        }

        bassBoost?.let { bb ->
            runCatching { bb.enabled = settings.bassBoostEnabled }
            runCatching { bb.setStrength(settings.bassBoostStrength.toShort()) }
        }

        virtualizer?.let { v ->
            runCatching { v.enabled = settings.virtualizerEnabled }
            runCatching { v.setStrength(settings.virtualizerStrength.toShort()) }
        }

        loudnessEnhancer?.let { le ->
            val gainMb = if (settings.outputGainEnabled) settings.outputGainMb.coerceIn(-1500, 1500) else 0
            runCatching { le.setTargetGain(gainMb) }
            runCatching { le.enabled = settings.outputGainEnabled }
        }
    }

    private fun openAudioEffectSession() {
        if (isAudioEffectSessionOpened) return
        val sessionId = player.audioSessionId
        if (sessionId <= 0) return
        isAudioEffectSessionOpened = true
        openedAudioSessionId = sessionId
        ensureAudioEffects(sessionId)
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }

    private fun closeAudioEffectSession() {
        if (!isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = false
        val sessionId = openedAudioSessionId ?: player.audioSessionId
        openedAudioSessionId = null
        releaseAudioEffects()
        if (sessionId <= 0) return
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            },
        )
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    super.onMediaItemTransition(mediaItem, reason)
    currentPlayingMediaId = mediaItem?.mediaId

    // Video mode doesn't carry over to the next song automatically — skipping while watching a
    // video just resumes normal audio playback on whatever comes next. Note this is NOT the same
    // as disableVideoMode(): the player's current item has already transitioned to the new song
    // by the time this fires, so there is no old-song position to restore — just tear down the
    // video player and let audio resume on whatever we landed on.
    if (videoModeActive.value && videoModeMediaId != mediaItem?.mediaId) {
        stopVideoPlayerInternal()
        videoModeActive.value = false
        videoModeMediaId = null
        player.playWhenReady = true
        // Keep watching videos across song changes instead of dropping to audio (true by default)
        if (dataStore.get(com.dezzy.music.constants.VideoKeepModeOnSongChangeKey, true)) {
            enableVideoMode()
        }
    }

    // Salto desde "videos similares": al llegar la transición al id pedido,
    // re-activar el modo video automáticamente para esa canción.
    if (pendingVideoModeMediaId != null && pendingVideoModeMediaId == mediaItem?.mediaId) {
        pendingVideoModeMediaId = null
        if (!videoModeActive.value) {
            enableVideoMode()
        }
    }

    clearStreamRefreshGuards(
        mediaItem?.mediaId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: player.currentMediaItem?.mediaId
    )
    // La presencia se publica ahora con el JWT del usuario (ver PresencePublisher):
    // OmegaMathIntegration escribía con la clave anónima y un user_id enviado por
    // el cliente, lo que permitía publicar en nombre de cualquiera.
    publishSocialPresence()

    crossfadeAudio?.onMediaItemTransition(mediaItem, reason)

    // Pre-load lyrics for upcoming songs in queue
    val currentIndex = player.currentMediaItemIndex
    // Convert media items to MediaMetadata for lyrics pre-loading
    val queue = player.mediaItems.mapNotNull { it.metadata }
    if (queue.isNotEmpty()) {
        lyricsPreloadManager?.onSongChanged(currentIndex, queue)
    }

    val joined = togetherSessionState.value as? com.dezzy.music.together.TogetherSessionState.Joined
    if (joined?.role is com.dezzy.music.together.TogetherRole.Guest &&
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
    ) {
        if (!joined.roomState.settings.allowGuestsToControlPlayback) {
            scope.launch(SilentHandler) { applyRemoteRoomState(joined.roomState) }
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val isEcho =
            isTogetherApplyingRemote() ||
                (now < togetherSuppressEchoUntilElapsedMs && togetherLastRemoteAppliedIndex == index)
        if (!isEcho) {
            val trackId = (mediaItem?.metadata ?: player.currentMetadata)?.id?.trim().orEmpty()
            requestTogetherControl(
                if (trackId.isBlank()) {
                    com.dezzy.music.together.ControlAction.SeekToIndex(
                        index = index,
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                    )
                } else {
                    com.dezzy.music.together.ControlAction.SeekToTrack(
                        trackId = trackId,
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                    )
                },
            )
        }
    }

    val timelineEmpty = player.currentTimeline.isEmpty || player.mediaItemCount == 0 || player.currentMediaItem == null
    currentMediaMetadata.value = if (timelineEmpty) null else (mediaItem?.metadata ?: player.currentMetadata)

    if (!timelineEmpty) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
            // Same song looping (repeat-one): the receiver loops on its own via its
            // mirrored REPEAT_ONE mode — just re-align it to the start.
            castController.seekTo(0L)
        } else {
            castController.onLocalMediaItemChanged(currentMediaMetadata.value, 0L, player.playWhenReady)
            // Listen Together host: publish the track change so guests can mirror it.
            currentMediaMetadata.value?.let { meta ->
                relayListenTogetherPlayback(
                    com.dezzy.music.listentogether.PlaybackActions.CHANGE_TRACK,
                    position = 0L,
                    trackId = meta.id,
                    trackInfo = meta.toListenTogetherTrackInfo(),
                )
                if (player.playWhenReady) {
                    relayListenTogetherPlayback(
                        com.dezzy.music.listentogether.PlaybackActions.PLAY,
                        position = 0L,
                    )
                }
            }
        }
    }

    scrobbleManager?.onSongStop()

    if (!timelineEmpty &&
        dataStore.get(AutoLoadMoreKey, true) &&
        reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
        player.repeatMode == REPEAT_MODE_OFF
    ) {
        val isNearEndWithoutPaging =
            player.mediaItemCount - player.currentMediaItemIndex <= 3 && !currentQueue.hasNextPage()

        if (!isNearEndWithoutPaging) {
            val force =
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED

            val currentId = (mediaItem?.metadata ?: player.currentMetadata)?.id?.trim().orEmpty()
            if (force || (currentId.isNotBlank() && automixSeedMediaId != currentId)) {
                refreshAutomixForCurrentMedia(force = force)
            }
        }
    }

    // Auto-load more from queue if available
    if (!suppressAutoPlayback &&
        !timelineEmpty &&
        dataStore.get(AutoLoadMoreKey, true) &&
        reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
        player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
        currentQueue.hasNextPage() &&
        player.repeatMode == REPEAT_MODE_OFF
    ) {
        scope.launch(SilentHandler) {
            val mediaItems =
                currentQueue.nextPage().filterExplicit(dataStore.get(HideExplicitKey, false)).filterVideo(dataStore.get(HideVideoKey, false))
            if (player.playbackState != STATE_IDLE) {
                player.addMediaItems(mediaItems.drop(1))
            } else {
                scope.launch { discordRpc?.stopActivity() }
            }
        }
    }
    
    if (!suppressAutoPlayback &&
        !timelineEmpty &&
        dataStore.get(AutoLoadMoreKey, true) &&
        reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
        player.repeatMode == REPEAT_MODE_OFF &&
        player.mediaItemCount - player.currentMediaItemIndex <= 3 &&
        !currentQueue.hasNextPage()
    ) {
        scope.launch(SilentHandler) {
            if (suppressAutoPlayback || player.mediaItemCount == 0) return@launch
            val queueIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            val currentMediaMetadata = player.currentMetadata
            val currentMediaId = currentMediaMetadata?.id?.trim().orEmpty()
            val existingSeed = automixSeedMediaId?.trim().orEmpty()
            val existingAutomix =
                if (currentMediaId.isNotBlank() && existingSeed == currentMediaId) {
                    automixItems.value
                } else {
                    if (automixItems.value.isNotEmpty()) {
                        clearAutomix()
                    }
                    emptyList()
                }
            if (existingAutomix.isNotEmpty()) {
                if (player.playbackState == STATE_IDLE) return@launch
                val filteredAutomix = existingAutomix.filter { it.mediaId !in queueIds }
                if (filteredAutomix.isNotEmpty()) {
                    player.addMediaItems(filteredAutomix)
                    filteredAutomix.forEach { autoAddedMediaIds.add(it.mediaId) }
                }
                clearAutomix()
            } else {
                if (currentMediaMetadata != null) {
                    refreshAutomixForCurrentMedia(force = true)
                }
            }
        }
    }

    if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
        scrobbleManager?.onSongStart(player.currentMetadata, duration = player.duration)
    }

    scope.launch {
        val shouldSave = withContext(Dispatchers.IO) { dataStore.get(PersistentQueueKey, true) }
        if (shouldSave) {
            saveQueueToDisk()
        }
    }
    ensurePresenceManager()
}

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
    super.onPlaybackStateChanged(playbackState)

    val activeMediaId = player.currentMediaItem?.mediaId
    clearStreamRefreshGuards(activeMediaId)
    if (
        playbackState == Player.STATE_READY &&
        player.playWhenReady &&
        player.isPlaying &&
        activeMediaId != null &&
        pendingStreamRefreshValidationMediaId == activeMediaId
    ) {
        refreshValidatedPlayingMediaId = activeMediaId
        pendingStreamRefreshValidationMediaId = null
        streamRecoveryState.remove(activeMediaId)
        Timber.tag("MusicService").i("Stream refresh validated and playback resumed for $activeMediaId")
    }

    scope.launch {
        val shouldSave = withContext(Dispatchers.IO) { dataStore.get(PersistentQueueKey, true) }
        if (shouldSave) {
            saveQueueToDisk()
        }
    }

    if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
        crossfadeAudio?.stop(resetMainFade = true)
        scrobbleManager?.onSongStop()
    }
    
    // Auto-start recommendations when playback ends
    if (!suppressAutoPlayback &&
        playbackState == Player.STATE_ENDED &&
        dataStore.get(AutoLoadMoreKey, true) &&
        player.repeatMode == REPEAT_MODE_OFF &&
        player.currentMediaItem != null
    ) {
        scope.launch(SilentHandler) {
            if (suppressAutoPlayback || player.playbackState == STATE_IDLE || player.mediaItemCount == 0) return@launch
            val lastMediaMetadata = player.currentMetadata
            val existingAutomix = automixItems.value
            if (existingAutomix.isNotEmpty()) {
                val filteredAutomix = existingAutomix.filter { it.mediaId != lastMediaMetadata?.id }
                if (filteredAutomix.isNotEmpty()) {
                    autoAddedMediaIds.clear()
                    player.setMediaItems(filteredAutomix, 0, 0)
                    player.prepare()
                    player.play()
                    filteredAutomix.forEach { autoAddedMediaIds.add(it.mediaId) }
                }
                clearAutomix()
            } else {
                if (lastMediaMetadata != null) {
                    withContext(Dispatchers.IO) {
                        YouTube.next(WatchEndpoint(videoId = lastMediaMetadata.id))
                    }.onSuccess { nextResult ->
                        if (suppressAutoPlayback || player.playbackState == STATE_IDLE || player.mediaItemCount == 0) return@onSuccess
                        val hideExplicit = dataStore.get(HideExplicitKey, false)
                        val hideVideo = dataStore.get(HideVideoKey, false)
                        val radioItems = nextResult.items
                            .map { it.toMediaItem() }
                            .filter { it.mediaId != lastMediaMetadata.id }
                            .filterExplicit(hideExplicit)
                            .filterVideo(hideVideo)

                        if (radioItems.isNotEmpty()) {
                            autoAddedMediaIds.clear()
                            player.setMediaItems(radioItems, 0, 0)
                            player.prepare()
                            player.play()
                            radioItems.forEach { autoAddedMediaIds.add(it.mediaId) }

                            withContext(Dispatchers.IO) {
                                YouTube.next(WatchEndpoint(playlistId = nextResult.endpoint.playlistId))
                            }.onSuccess { automixResult ->
                                if (suppressAutoPlayback || player.playbackState == STATE_IDLE) return@onSuccess
                                automixItems.value = automixResult.items
                                    .map { it.toMediaItem() }
                                    .filter { it.mediaId != lastMediaMetadata.id }
                                    .filterExplicit(hideExplicit)
                                    .filterVideo(hideVideo)
                            }
                        }
                    }
                }
            }
        }
    }

    ensurePresenceManager()
    scope.launch {
        try {
            val token = withContext(Dispatchers.IO) { dataStore.get(DiscordTokenKey, "") }
            if (token.isNotBlank() && DiscordPresenceManager.isRunning()) {
                // Obtain the freshest Song from DB using current media item id to avoid stale currentSong.value
                val mediaId = player.currentMediaItem?.mediaId
                val song = if (mediaId != null) withContext(Dispatchers.IO) { database.song(mediaId).first() } else null
                val finalSong = song ?: player.currentMetadata?.let { createTransientSongFromMedia(it) }

                if (canUpdatePresence()) {
                    val success = withContext(Dispatchers.IO) {
                        DiscordPresenceManager.updateNow(
                            context = this@MusicService,
                            token = token,
                            song = finalSong,
                            positionMs = player.currentPosition,
                            isPaused = !player.playWhenReady,
                        )
                    }
                    if (!success) {
                        Timber.tag("MusicService").w("immediate presence update returned false — attempting restart")
                        if (DiscordPresenceManager.isRunning()) {
                            try {
                                if (DiscordPresenceManager.restart()) {
                                    Timber.tag("MusicService").d("presence manager restarted after failed update")
                                }
                            } catch (ex: Exception) {
                                Timber.tag("MusicService").e(ex, "restart after failed presence update threw")
                            }
                        }
                    }

                    try {
                        val lbEnabled = withContext(Dispatchers.IO) { dataStore.get(ListenBrainzEnabledKey, false) }
                        val lbToken = withContext(Dispatchers.IO) { dataStore.get(ListenBrainzTokenKey, "") }
                        if (lbEnabled && !lbToken.isNullOrBlank()) {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    ListenBrainzManager.submitPlayingNow(this@MusicService, lbToken, finalSong, player.currentPosition)
                                } catch (ie: Exception) {
                                    Timber.tag("MusicService").v(ie, "ListenBrainz playing_now submit failed")
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Timber.tag("MusicService").v(e, "immediate presence update failed")
        }
    }
}


    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
            if (crossfadeAudio?.isCrossfading() != true) {
                currentMediaMetadata.value = player.currentMetadata
            }
        }
        // Coordinated audio/video flow: entering video mode pauses this player, but the media
        // notification can still resume it — which used to double the audio (song + video at
        // once). If the song starts playing while the video overlay is up, hand playback back
        // to the song at the video's position and close the video.
        if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) &&
            this.player.playWhenReady &&
            videoModeActive.value
        ) {
            disableVideoMode()
            // disableVideoMode restores the VIDEO's play state; the user's intent here was
            // explicitly "play", so keep the song playing.
            this.player.playWhenReady = true
        }
        if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
            events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
            events.contains(Player.EVENT_REPEAT_MODE_CHANGED) ||
            events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
            events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
        ) {
            scope.launch {
                val shouldSave = withContext(Dispatchers.IO) { dataStore.get(PersistentQueueKey, true) }
                if (shouldSave) {
                    saveQueueToDisk()
                }
            }
        }
    if (castController.isConnected.value) {
        if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
            castController.setPlayWhenReady(this.player.playWhenReady)
        }
        if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) &&
            !events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
        ) {
            castController.seekTo(this.player.currentPosition)
        }
    }
    // Listen Together host: publish play/pause and seeks so guests stay in sync.
    if (listenTogetherHostClient() != null) {
        if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
            relayListenTogetherPlayback(
                if (this.player.playWhenReady) com.dezzy.music.listentogether.PlaybackActions.PLAY
                else com.dezzy.music.listentogether.PlaybackActions.PAUSE,
                position = this.player.currentPosition,
            )
        }
        if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) &&
            !events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
        ) {
            relayListenTogetherPlayback(
                com.dezzy.music.listentogether.PlaybackActions.SEEK,
                position = this.player.currentPosition,
            )
        }
    }
    val joined = togetherSessionState.value as? com.dezzy.music.together.TogetherSessionState.Joined
    if (joined?.role is com.dezzy.music.together.TogetherRole.Guest &&
        events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)
    ) {
        if (!joined.roomState.settings.allowGuestsToControlPlayback) {
            scope.launch(SilentHandler) { applyRemoteRoomState(joined.roomState) }
        } else {
            val now = android.os.SystemClock.elapsedRealtime()
            val playWhenReady = this.player.playWhenReady
            val isEcho =
                isTogetherApplyingRemote() ||
                    (now < togetherSuppressEchoUntilElapsedMs &&
                        togetherLastRemoteAppliedPlayWhenReady != null &&
                        togetherLastRemoteAppliedPlayWhenReady == playWhenReady)
            if (!isEcho) {
                val action =
                    if (playWhenReady) {
                        com.dezzy.music.together.ControlAction.Play
                    } else {
                        com.dezzy.music.together.ControlAction.Pause
                    }
                requestTogetherControl(action)
            }
        }
    }
    if (events.contains(Player.EVENT_DEVICE_VOLUME_CHANGED)) {
        handleDeviceMuteStateChanged()
        // Sync device volume to Cast receiver
        if (castController.isConnected.value) {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val normalizedVolume = (currentVolume.toFloat() / maxVolume).coerceIn(0f, 1f)
            castController.setVolume(normalizedVolume)
        }
    }
    if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && isDeviceMutedNow() && this.player.playWhenReady) {
        handleDeviceMuteStateChanged()
    }
    if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
        (this.player.playbackState == Player.STATE_IDLE || this.player.playbackState == Player.STATE_ENDED)
    ) {
        wasAutoPausedByDeviceMute = false
    }
    if (events.contains(Player.EVENT_AUDIO_SESSION_ID)) {
        val newSessionId = this.player.audioSessionId
        val oldSessionId = openedAudioSessionId
        // Un cambio de sesión de audio implica que el AudioSink se reconfiguró (típico
        // al conectar/desconectar audífonos o Bluetooth). Si había un fundido/handoff en
        // curso, lo cerramos ya para no dejar el player principal mudo esperando a
        // estabilizarse. No-op si no hay fundido activo.
        crossfadeAudio?.onAudioRouteChanged()
        if (isAudioEffectSessionOpened && newSessionId > 0 && oldSessionId != null && oldSessionId > 0 && oldSessionId != newSessionId) {
            sendBroadcast(
                Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, oldSessionId)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                },
            )
            openedAudioSessionId = newSessionId
            ensureAudioEffects(newSessionId)
            sendBroadcast(
                Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, newSessionId)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                    putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                },
            )
        }
    }
    if (events.containsAny(
            Player.EVENT_PLAYBACK_STATE_CHANGED,
            Player.EVENT_PLAY_WHEN_READY_CHANGED
        )
    ) {
        val playbackState = player.playbackState
        if (playbackState == Player.STATE_READY) {
            // La reproducción se recuperó: sin esto, los contadores de error se
            // acumulan durante toda la vida del servicio y una racha de errores
            // repartida en horas termina pausando en vez de saltar/reintentar.
            networkRetryAttempt = 0
            consecutivePlaybackErr = 0
        }
        val keepAudioEffectSessionOpen =
            playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY
        if (player.playWhenReady && keepAudioEffectSessionOpen) {
            requestAudioFocus()
        }
        if (keepAudioEffectSessionOpen) {
            openAudioEffectSession()
        } else {
            closeAudioEffectSession()
        }
        updateWakeLock()
        if (player.playWhenReady && keepAudioEffectSessionOpen) {
            cancelIdleStop()
            promoteToStartedService()
            ensureStartedAsForeground()
        } else {
            scheduleStopIfIdle()
        }
    }

       if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            if (crossfadeAudio?.isCrossfading() != true) {
                currentMediaMetadata.value = player.currentMetadata
            }
            // immediate update when media item transitions to avoid stale presence
            scope.launch {
                try {
                    val token = dataStore.get(DiscordTokenKey, "")
                    if (token.isNotBlank() && DiscordPresenceManager.isRunning()) {
                        val mediaId = player.currentMediaItem?.mediaId
                        val song = if (mediaId != null) withContext(Dispatchers.IO) { database.song(mediaId).first() } else null
                        val finalSong = song ?: player.currentMetadata?.let { createTransientSongFromMedia(it) }

                        if (canUpdatePresence()) {
                            val success = DiscordPresenceManager.updateNow(
                                context = this@MusicService,
                                token = token,
                                song = finalSong,
                                positionMs = player.currentPosition,
                                isPaused = !player.isPlaying,
                            )
                            if (!success) {
                                Timber.tag("MusicService").w("transition immediate presence update failed — attempting restart")
                                try { DiscordPresenceManager.stop(); DiscordPresenceManager.start(this@MusicService, dataStore.get(DiscordTokenKey, ""), { song }, { player.currentPosition }, { !player.isPlaying }, { getPresenceIntervalMillis(this@MusicService) }) } catch (_: Exception) {}
                            }
                            try {
                                val lbEnabled = dataStore.get(ListenBrainzEnabledKey, false)
                                val lbToken = dataStore.get(ListenBrainzTokenKey, "")
                                if (lbEnabled && !lbToken.isNullOrBlank()) {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            ListenBrainzManager.submitPlayingNow(this@MusicService, lbToken, finalSong, player.currentPosition)
                                        } catch (ie: Exception) {
                                            Timber.tag("MusicService").v(ie, "ListenBrainz playing_now submit failed on transition")
                                        }
                                    }
                                }
                                
                                // Last.fm now playing - handled by ScrobbleManager
                            } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("MusicService").v(e, "immediate presence update failed on transition")
                }
            }
        }

        // Also handle immediate update for play state and media item transition events explicitly
        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                currentMediaMetadata.value = player.currentMetadata
            }
            // Capture player state on Main thread
            val currentMediaId = player.currentMediaItem?.mediaId
            val currentMetadata = player.currentMetadata
            val currentPosition = player.currentPosition
            val isPlaying = player.isPlaying

            scope.launch {
                try {
                    val token = withContext(Dispatchers.IO) { dataStore.get(DiscordTokenKey, "") }
                    if (token.isNotBlank() && DiscordPresenceManager.isRunning()) {
                        val song = if (currentMediaId != null) withContext(Dispatchers.IO) { database.song(currentMediaId).first() } else null
                        val finalSong = song ?: currentMetadata?.let { createTransientSongFromMedia(it) }

                        if (canUpdatePresence()) {
                            // Run update on IO if possible, assuming updateNow is thread-safe or handles its own threading correctly
                            // If updateNow touches Views, this might break. Assuming it's network/logic.
                            val success = withContext(Dispatchers.IO) {
                                DiscordPresenceManager.updateNow(
                                    context = this@MusicService,
                                    token = token,
                                    song = finalSong,
                                    positionMs = currentPosition,
                                    isPaused = !isPlaying,
                                )
                            }
                            if (!success) {
                                Timber.tag("MusicService").w("isPlaying/mediaTransition immediate presence update failed — restarting manager")
                                if (DiscordPresenceManager.isRunning()) {
                                    try { DiscordPresenceManager.stop(); DiscordPresenceManager.restart() } catch (_: Exception) {}
                                }
                            }
                            try {
                                val lbEnabled = withContext(Dispatchers.IO) { dataStore.get(ListenBrainzEnabledKey, false) }
                                val lbToken = withContext(Dispatchers.IO) { dataStore.get(ListenBrainzTokenKey, "") }
                                if (lbEnabled && !lbToken.isNullOrBlank()) {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            ListenBrainzManager.submitPlayingNow(this@MusicService, lbToken, finalSong, currentPosition)
                                        } catch (ie: Exception) {
                                            Timber.tag("MusicService").v(ie, "ListenBrainz playing_now submit failed for isPlaying/mediaTransition")
                                        }
                                    }
                                }
                                
                                // Last.fm now playing - handled by ScrobbleManager
                            } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("MusicService").v(e, "immediate presence update failed for isPlaying/mediaTransition")
                }
            }
        }

   if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED)) {
        ensurePresenceManager()
        // Scrobble: Track play/pause state
        scrobbleManager?.onPlayerStateChanged(player.isPlaying, player.currentMetadata, duration = player.duration)
    } else if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
        ensurePresenceManager()
    } else {
        ensurePresenceManager()
    }
    sendBubbleUpdate(player.isPlaying)
    updateHomeWidget(player.isPlaying)
    publishSocialPresence()
  }


    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateNotification()
        val joined = togetherSessionState.value as? com.dezzy.music.together.TogetherSessionState.Joined
        if (joined?.role is com.dezzy.music.together.TogetherRole.Guest) {
            if (!isTogetherApplyingRemote()) {
                if (!joined.roomState.settings.allowGuestsToControlPlayback) {
                    scope.launch(SilentHandler) { applyRemoteRoomState(joined.roomState) }
                    return
                }
                requestTogetherControl(
                    com.dezzy.music.together.ControlAction.SetShuffleEnabled(
                        shuffleEnabled = shuffleModeEnabled,
                    ),
                )
            }
            return
        }
        if (shuffleModeEnabled) {
            // Fija el crossfade en la pista real antes de reordenar la cola, para
            // que el "actual" del shuffle sea la canción que de verdad suena.
            crossfadeAudio?.commitCrossfade()
            applyCurrentFirstShuffleOrder()
        }

        // Save state when shuffle mode changes - must be on Main thread to access player
        scope.launch {
            dataStore.edit { it[PermanentShuffleKey] = shuffleModeEnabled }
            if (dataStore.get(PersistentQueueKey, true)) {
                saveQueueToDisk()
            }
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        cachedRepeatMode = repeatMode
        updateNotification()
        castController.setRepeatMode(repeatMode)
        val joined = togetherSessionState.value as? com.dezzy.music.together.TogetherSessionState.Joined
        if (joined?.role is com.dezzy.music.together.TogetherRole.Guest) {
            if (!isTogetherApplyingRemote()) {
                if (!joined.roomState.settings.allowGuestsToControlPlayback) {
                    scope.launch(SilentHandler) { applyRemoteRoomState(joined.roomState) }
                    return
                }
                requestTogetherControl(
                    com.dezzy.music.together.ControlAction.SetRepeatMode(
                        repeatMode = repeatMode,
                    ),
                )
            }
            return
        }
        scope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }
        
        // Save state when repeat mode changes - must be on Main thread to access player
        scope.launch {
            if (dataStore.get(PersistentQueueKey, true)) {
                saveQueueToDisk()
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        val isConnectionError = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                ((error.cause?.cause is PlaybackException) &&
                (error.cause?.cause as PlaybackException).errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

        if (!isNetworkConnected.value || isConnectionError) {
            waitOnNetworkError()
            return
        }

        val currentMediaId = player.currentMediaItem?.mediaId
        val httpStatusCode = error.httpStatusCodeOrNull()

        if (currentMediaId != null && YTPlayerUtils.isBotDetectionException(error)) {
            if (markAndCheckRecoveryAllowance(currentMediaId)) {
                Timber.tag("MusicService").w(
                    "Bot detection error for $currentMediaId — clearing caches and retrying with fresh stream"
                )
                YTPlayerUtils.invalidateCachedStreamUrls(currentMediaId)
                playbackUrlCache.remove(currentMediaId)
                pendingStreamRefreshValidationMediaId = currentMediaId
                player.prepare()
                player.playWhenReady = true
                return
            }
        }

        val shouldAttemptStreamRefresh =
            currentMediaId != null && (
                error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
                    httpStatusCode in setOf(403, 404, 410, 416, 429, 500, 502, 503)
                )

        if (currentMediaId != null && error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
            scope.launch(Dispatchers.IO) {
                runCatching { downloadCache.removeResource(currentMediaId) }
                runCatching { playerCache.removeResource(currentMediaId) }
            }
        }

        if (shouldAttemptStreamRefresh && currentMediaId != null && shouldSkipRedundantStreamRefresh(currentMediaId)) {
            Timber.tag("MusicService").w(
                "Skipping redundant stream refresh for $currentMediaId after validated recovery; resuming playback without URL refresh"
            )
            refreshValidatedPlayingMediaId = null
            player.prepare()
            player.playWhenReady = true
            return
        }

        if (shouldAttemptStreamRefresh && currentMediaId != null && markAndCheckRecoveryAllowance(currentMediaId)) {
            val failingStreamClientKey =
                playbackUrlCache[currentMediaId]
                    ?.first
                    ?.toHttpUrlOrNull()
                    ?.queryParameter("c")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            Timber.tag("MusicService").w(
                "Attempting stream refresh for $currentMediaId (http=$httpStatusCode, code=${error.errorCode}, client=${failingStreamClientKey ?: "unknown"})"
            )
            YTPlayerUtils.markStreamClientFailed(currentMediaId, failingStreamClientKey, httpStatusCode)
            YTPlayerUtils.markPreferredClientFailed(currentMediaId, preferredStreamClient, httpStatusCode)
            YTPlayerUtils.invalidateCachedStreamUrls(currentMediaId)
            playbackUrlCache.remove(currentMediaId)
            pendingStreamRefreshValidationMediaId = currentMediaId
            player.prepare()
            player.playWhenReady = true
            return
        }

        // El AudioTrack muere al cambiar de ruta de salida (colgar una llamada devuelve
        // el audio de SCO a A2DP, desconectar/reconectar auriculares…). No es un fallo
        // del stream: basta con re-preparar en la misma posición.
        val isAudioTrackError =
            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED
        if (isAudioTrackError) {
            consecutivePlaybackErr += 1
            if (consecutivePlaybackErr <= MAX_CONSECUTIVE_ERR) {
                Timber.tag("MusicService").w("AudioTrack error (${error.errorCode}) — re-preparando en la misma posición")
                player.prepare()
                player.playWhenReady = true
                return
            }
            Timber.tag("MusicService").e("Demasiados errores de AudioTrack seguidos; deteniendo")
            stopOnError()
            return
        }

        val skipSilenceCurrentlyEnabled = dataStore.get(SkipSilenceKey, false)
        val causeText = (error.cause?.stackTraceToString() ?: error.stackTraceToString()).lowercase()
        val looksLikeSilenceProcessor = skipSilenceCurrentlyEnabled && (
            "silenceskippingaudioprocessor" in causeText || "silence" in causeText
        )

        if (looksLikeSilenceProcessor) {
            scope.launch {
                try {
                    dataStore.edit { settings ->
                        settings[SkipSilenceKey] = false
                    }
                    player.skipSilenceEnabled = false
                    val currentPos = player.currentPosition
                    val targetPos = min(currentPos + 1500L, if (player.duration > 0) player.duration - 1000L else currentPos + 1500L)
                    player.seekTo(targetPos)
                    player.prepare()
                    player.play()
                    return@launch
                } catch (t: Throwable) {
                    Timber.tag("MusicService").e(t, "failed to recover from silence-skipper error")
                }
                if (dataStore.get(AutoSkipNextOnErrorKey, false)) {
                    skipOnError()
                } else {
                    stopOnError()
                }
            }

            return
        }

        Timber.tag("MusicService").w(error, "Playback error encountered — auto skipping to next track to preserve playback session")
        skipOnError()
    }

    private suspend fun trimPlayerCacheToBytes(limitBytes: Long) {
        if (limitBytes <= 0L) return

        withContext(Dispatchers.IO) {
            val cacheDir = filesDir.resolve("exoplayer")
            val currentSpace = runCatching { playerCache.cacheSpace }.getOrNull() ?: 0L
            var totalBytes = if (currentSpace > 0L) currentSpace else cacheDir.directorySizeBytes()
            if (totalBytes <= limitBytes) return@withContext

            data class Candidate(
                val key: String,
                val lastTouchTimestamp: Long,
                val sizeBytes: Long,
            )

            val candidates =
                runCatching {
                    playerCache.keys.mapNotNull { key ->
                        runCatching {
                            val spans = playerCache.getCachedSpans(key)
                            if (spans.isEmpty()) return@runCatching null
                            val oldestTouch = spans.minOf { it.lastTouchTimestamp }
                            val sizeBytes = spans.sumOf { it.length }
                            Candidate(key = key, lastTouchTimestamp = oldestTouch, sizeBytes = sizeBytes)
                        }.getOrNull()
                    }.sortedBy { it.lastTouchTimestamp }
                }.getOrNull().orEmpty()

            for (candidate in candidates) {
                if (totalBytes <= limitBytes) break
                val removedSize = candidate.sizeBytes.coerceAtLeast(0L)
                runCatching { playerCache.removeResource(candidate.key) }
                totalBytes -= removedSize
            }
        }
    }

    private fun createCacheDataSource(): CacheDataSource.Factory =
        CacheDataSource
            .Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                CacheDataSource
                    .Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        DefaultDataSource.Factory(
                            this,
                            OkHttpDataSource.Factory(
                                mediaOkHttpClient,
                            ),
                        ),
                    )
                    .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
            ).setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

    private fun createDataSourceFactory(): DataSource.Factory {
        val remoteFactory = ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")

            // STREAMING LOSSLESS (FLAC): las pistas de la búsqueda lossless se
            // reproducen con un mediaId "flacstream_..." cuya URL de stream (FLAC
            // real del resolver) se registró al pulsar reproducir. Aquí solo
            // sustituimos la URI por la real; el resto del pipeline (cache + http)
            // reproduce el FLAC progresivamente. Sin pasar por YouTube.
            if (mediaId.startsWith(FLAC_STREAM_PREFIX)) {
                val url = flacStreamUrls[mediaId]
                    ?: throw DataSourceException(
                        "Stream lossless no disponible (reintenta)",
                        null,
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    )
                return@Factory dataSpec.withUri(url.toUri())
            }

            val wasActiveAtStart = currentPlayingMediaId == mediaId
            if (wasActiveAtStart && currentPlayingMediaId != mediaId) {
                throw DataSourceException(
                    "Playback skipped to another media item before resolution.",
                    null,
                    PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
                )
            }



            // ── Fast path 2: recently resolved URL still valid (in-memory, zero I/O) ──
            playbackUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                val length = if (dataSpec.length >= 0) minOf(dataSpec.length, CHUNK_LENGTH) else CHUNK_LENGTH
                return@Factory dataSpec.withUri(it.first.toUri()).subrange(dataSpec.uriPositionOffset, length)
            }

            // ── Fast path 3: fully cached in player cache (loop mode or known length) ──
            val requiredCachedLength =
                if (dataSpec.length >= 0) {
                    dataSpec.length
                } else {
                    val contentLength =
                        runBlocking(Dispatchers.IO) {
                            database.formatOnce(mediaId)?.contentLength
                        } ?: runCatching {
                            downloadCache
                                .getContentMetadata(mediaId)
                                .get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
                        }.getOrNull()?.takeIf { it > 0L } ?: runCatching {
                            playerCache
                                .getContentMetadata(mediaId)
                                .get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
                        }.getOrNull()?.takeIf { it > 0L }

                    contentLength?.let { nonNullContentLength ->
                        (nonNullContentLength - dataSpec.position).takeIf { it > 0L }
                    }
                }

            val cachedSpans = playerCache.getCachedSpans(mediaId)
            val isLoopingOrCached = cachedRepeatMode == Player.REPEAT_MODE_ONE && cachedSpans.isNotEmpty()
            if (requiredCachedLength != null || isLoopingOrCached) {
                val isFullyCached =
                    isLoopingOrCached ||
                        downloadCache.isCached(mediaId, dataSpec.position, requiredCachedLength ?: 1L) ||
                        playerCache.isCached(mediaId, dataSpec.position, requiredCachedLength ?: 1L)
                if (isFullyCached) {
                    scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                    return@Factory dataSpec
                }
            }

            if (!isNetworkConnected.value) {
                // If offline and the media is not fully cached, we must not call YTPlayerUtils.playerResponseForPlayback
                // which would block the player thread using runBlocking for a long network timeout.
                val isPartiallyCached = requiredCachedLength != null && playerCache.isCached(mediaId, dataSpec.position, requiredCachedLength)
                if (isPartiallyCached) {
                    scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                    return@Factory dataSpec
                }
                
                throw DataSourceException(
                    "No internet connection and song is not cached offline.",
                    null,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                )
            }

            val playbackDataResult = runBlocking(Dispatchers.IO) {
                YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    preferredStreamClient = preferredStreamClient,
                    avoidCodecs = avoidStreamCodecs,
                    isCancelled = { wasActiveAtStart && currentPlayingMediaId != mediaId }
                )
            }

            if (wasActiveAtStart && currentPlayingMediaId != mediaId) {
                throw DataSourceException(
                    "Playback skipped to another media item during resolution.",
                    null,
                    PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
                )
            }

            val playbackData = playbackDataResult.getOrElse { throwable ->
                if (throwable is kotlin.coroutines.cancellation.CancellationException || (wasActiveAtStart && currentPlayingMediaId != mediaId)) {
                    throw DataSourceException(
                        "Playback cancelled/skipped to another media item.",
                        throwable,
                        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
                    )
                }
                when (throwable) {
                    is YTPlayerUtils.LoginRequiredForPlaybackException -> {
                        promptLoginRecovery(mediaId, throwable.targetUrl)
                        throw DataSourceException(
                            getString(R.string.playback_requires_youtube_music_confirmation),
                            throwable,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR
                        )
                    }

                    is PlaybackException -> {
                        throw DataSourceException(
                            throwable.message,
                            throwable,
                            throwable.errorCode
                        )
                    }

                    is java.net.ConnectException, is java.net.UnknownHostException -> {
                        throw DataSourceException(
                            getString(R.string.error_no_internet),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                        )
                    }

                    is java.net.SocketTimeoutException -> {
                        throw DataSourceException(
                            getString(R.string.error_timeout),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                        )
                    }

                    else -> throw DataSourceException(
                        throwable.message ?: getString(R.string.error_unknown),
                        throwable,
                        PlaybackException.ERROR_CODE_REMOTE_ERROR
                    )
                }
            }

            val nonNullPlayback = requireNotNull(playbackData) {
                getString(R.string.error_unknown)
            }
            run {
                val format = nonNullPlayback.format
                val loudnessDb = nonNullPlayback.audioConfig?.loudnessDb
                val perceptualLoudnessDb = nonNullPlayback.audioConfig?.perceptualLoudnessDb
                
                Timber.tag("AudioNormalization").d("Storing format for $mediaId with loudnessDb: $loudnessDb, perceptualLoudnessDb: $perceptualLoudnessDb")
                if (loudnessDb == null && perceptualLoudnessDb == null) {
                    Timber.tag("AudioNormalization").w("No loudness data available from YouTube for video: $mediaId")
                }

                database.query {
                    upsert(
                        FormatEntity(
                            id = mediaId,
                            itag = format.itag,
                            mimeType = format.mimeType.split(";")[0],
                            codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                            bitrate = format.bitrate,
                            sampleRate = format.audioSampleRate,
                            contentLength = format.contentLength!!,
                            loudnessDb = loudnessDb,
                            perceptualLoudnessDb = perceptualLoudnessDb,
                            playbackUrl = nonNullPlayback.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                        )
                    )
                }
                scope.launch(Dispatchers.IO) { recoverSong(mediaId, nonNullPlayback) }

                val streamUrl = nonNullPlayback.streamUrl

                playbackUrlCache[mediaId] =
                    streamUrl to System.currentTimeMillis() + (nonNullPlayback.streamExpiresInSeconds * 1000L)
                val length = if (dataSpec.length >= 0) minOf(dataSpec.length, CHUNK_LENGTH) else CHUNK_LENGTH
                return@Factory dataSpec.withUri(streamUrl.toUri()).subrange(dataSpec.uriPositionOffset, length)
            }
        }
        // Las canciones locales importadas (LOCAL_*) se rutean a archivo antes de
        // entrar al pipeline remoto; el resto pasa por él sin cambios.
        return DataSource.Factory {
            LocalFileRoutingDataSource(remoteFactory.createDataSource(), this)
        }
    }

    fun retryCurrentFromFreshStream() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        clearStreamRefreshGuards(mediaId)
        YTPlayerUtils.invalidateCachedStreamUrls(mediaId)
        playbackUrlCache.remove(mediaId)
        pendingStreamRefreshValidationMediaId = mediaId
        player.prepare()
        player.playWhenReady = true
    }

    private fun PlaybackException.httpStatusCodeOrNull(): Int? {
        var t: Throwable? = cause
        while (t != null) {
            if (t is HttpDataSource.InvalidResponseCodeException) return t.responseCode
            t = t.cause
        }
        return null
    }

    private fun markAndCheckRecoveryAllowance(mediaId: String): Boolean {
        val now = System.currentTimeMillis()
        val (count, lastAt) = streamRecoveryState[mediaId] ?: (0 to 0L)
        val nextCount = if (now - lastAt > 45_000L) 1 else count + 1
        if (nextCount > 2) return false
        streamRecoveryState[mediaId] = nextCount to now
        return true
    }

    private fun shouldSkipRedundantStreamRefresh(mediaId: String): Boolean {
        if (refreshValidatedPlayingMediaId != mediaId) return false
        val expiresAt = playbackUrlCache[mediaId]?.second ?: return false
        if (expiresAt <= System.currentTimeMillis()) {
            refreshValidatedPlayingMediaId = null
            return false
        }
        return true
    }

    private fun clearStreamRefreshGuards(activeMediaId: String? = null) {
        val normalizedActiveMediaId = activeMediaId?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedActiveMediaId == null || refreshValidatedPlayingMediaId != normalizedActiveMediaId) {
            refreshValidatedPlayingMediaId = null
        }
        if (normalizedActiveMediaId == null || pendingStreamRefreshValidationMediaId != normalizedActiveMediaId) {
            pendingStreamRefreshValidationMediaId = null
        }
    }

    private fun deviceSupportsMimeType(mimeType: String): Boolean {
        return runCatching {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            codecList.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    /**
     * Búferes amplios para que la reproducción nunca se corte: el reproductor mantiene
     * hasta ~10 min de audio por delante, de modo que un atasco al reabrir un chunk del
     * stream de YouTube no agota el búfer. prioritizeTimeOverSizeThresholds asegura que se
     * priorice el tiempo de audio acumulado sobre el tamaño en bytes (audio = poco peso).
     */
    private fun createLoadControl(): LoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 60_000,
                /* maxBufferMs = */ 600_000,
                /* bufferForPlaybackMs = */ 2_000,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(/* backBufferDurationMs = */ 30_000, /* retainBackBufferFromKeyframe = */ true)
            .build()

    private fun createMediaSourceFactory() =
        DefaultMediaSourceFactory(
            createDataSourceFactory(),
            ExtractorsFactory {
                // Mp4/FragmentedMp4/Matroska cubren los streams de YouTube;
                // los demás dan soporte a la música local importada (MP3, OGG…).
                arrayOf(
                    Mp4Extractor(),
                    FragmentedMp4Extractor(),
                    MatroskaExtractor(),
                    Mp3Extractor(),
                    OggExtractor(),
                    WavExtractor(),
                    FlacExtractor(),
                )
            },
        )

    private fun updateAudioOffload(enabled: Boolean) {
        runCatching {
            val builder = player.trackSelectionParameters.buildUpon()
            val audioOffloadPrefsClass = Class.forName("androidx.media3.common.AudioOffloadPreferences")
            val audioOffloadPrefsBuilderClass = Class.forName("androidx.media3.common.AudioOffloadPreferences\$Builder")

            val modeFieldName = if (enabled) "AUDIO_OFFLOAD_MODE_ENABLED" else "AUDIO_OFFLOAD_MODE_DISABLED"
            val mode = audioOffloadPrefsClass.getField(modeFieldName).getInt(null)

            val prefsBuilder = audioOffloadPrefsBuilderClass.getDeclaredConstructor().newInstance()
            audioOffloadPrefsBuilderClass.getMethod("setAudioOffloadMode", Int::class.javaPrimitiveType).invoke(prefsBuilder, mode)
            val prefs = audioOffloadPrefsBuilderClass.getMethod("build").invoke(prefsBuilder)

            val setMethod =
                builder.javaClass.methods.firstOrNull { method ->
                    method.name == "setAudioOffloadPreferences" && method.parameterTypes.size == 1
                }
            if (setMethod != null) {
                setMethod.invoke(builder, prefs)
                player.trackSelectionParameters = builder.build()
            }
        }
        player.setOffloadEnabled(enabled)
    }

    private fun updateWakeLock() {
        val s = player.playbackState
        val shouldHold = player.isPlaying || (player.playWhenReady && (s == Player.STATE_BUFFERING || s == Player.STATE_READY))

        wakeLock?.let { wl ->
            if (shouldHold && !wl.isHeld) {
                runCatching { wl.acquire() }
            } else if (!shouldHold && wl.isHeld) {
                runCatching { wl.release() }
            }
        }

        wifiLock?.let { wfl ->
            if (shouldHold && !wfl.isHeld) {
                runCatching { wfl.acquire() }
            } else if (!shouldHold && wfl.isHeld) {
                runCatching { wfl.release() }
            }
        }
    }

    private fun createRenderersFactory() =
        object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ) = DefaultAudioSink
                .Builder(this@MusicService)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        SilenceSkippingAudioProcessor(
                            1_500_000L,
                            0.35f,
                            500_000L,
                            10,
                            150.toShort(),
                        ),
                        SonicAudioProcessor(),
                    ),
                ).build()
        }

    // Salvaguarda de carátulas: URLs ya tratadas en esta sesión, para que una
    // carátula irrecuperable (p. ej. video borrado) no degenere en un bucle de
    // reintentos de red. El tope acota el peor caso.
    private val repairedArtworkUrls =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Se llama cuando la notificación no pudo cargar una carátula o solo obtuvo
     * el placeholder gris de YouTube: refresca la metadata de las canciones que
     * usan esa URL (misma vía que el "Refetch" del menú de canción), guarda la
     * carátula nueva en la BD y, si la canción está en la cola, actualiza su
     * MediaItem al vuelo (solo metadata → media3 no interrumpe la reproducción)
     * para que la notificación se corrija sola.
     */
    private fun repairBrokenArtwork(brokenUrl: String) {
        if (!brokenUrl.startsWith("http")) return
        if (repairedArtworkUrls.size >= 50) return
        if (!repairedArtworkUrls.add(brokenUrl)) return
        scope.launch(Dispatchers.IO) {
            try {
                database.songsByThumbnailUrlBlocking(brokenUrl).forEach { song ->
                    val fresh = YouTube.queue(listOf(song.id)).getOrNull()?.firstOrNull()
                        ?: return@forEach
                    val metadata = fresh.toMediaMetadata()
                    val newUrl = metadata.thumbnailUrl
                    if (newUrl.isNullOrBlank() || newUrl == brokenUrl) return@forEach
                    database.query { update(song, metadata) }
                    withContext(Dispatchers.Main) {
                        for (i in 0 until player.mediaItemCount) {
                            val item = player.getMediaItemAt(i)
                            if (item.mediaId == song.id &&
                                item.mediaMetadata.artworkUri?.toString() == brokenUrl
                            ) {
                                player.replaceMediaItem(
                                    i,
                                    item.buildUpon()
                                        .setMediaMetadata(
                                            item.mediaMetadata.buildUpon()
                                                .setArtworkUri(newUrl.toUri())
                                                .build(),
                                        )
                                        .build(),
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                reportException(e)
            }
        }
    }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats,
    ) {
        val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem

        if (playbackStats.totalPlayTimeMs >= (
                dataStore[HistoryDuration]?.times(1000f)
                    ?: 30000f
            ) &&
            !dataStore.get(PauseListenHistoryKey, false)
        ) {
            database.query {
                incrementTotalPlayTime(mediaItem.mediaId, playbackStats.totalPlayTimeMs)
                try {
                    insert(
                        Event(
                            songId = mediaItem.mediaId,
                            timestamp = LocalDateTime.now(),
                            playTime = playbackStats.totalPlayTimeMs,
                        ),
                    )
                } catch (_: SQLException) {
                }
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val song = database.song(mediaItem.mediaId).first()
                        ?: return@launch

                    val lbEnabled = dataStore.get(ListenBrainzEnabledKey, false)
                    val lbToken = dataStore.get(ListenBrainzTokenKey, "")
                    if (lbEnabled && !lbToken.isNullOrBlank()) {
                        val endMs = System.currentTimeMillis()
                        val startMs = endMs - playbackStats.totalPlayTimeMs
                        try {
                            ListenBrainzManager.submitFinished(this@MusicService, lbToken, song, startMs, endMs)
                        } catch (ie: Exception) {
                            Timber.tag("MusicService").v(ie, "ListenBrainz finished submit failed")
                        }
                    }
                } catch (_: Exception) {
                }
            }

            CoroutineScope(Dispatchers.IO).launch {
                runCatching { registerRemoteListeningHistory(mediaItem.mediaId) }
            }
        }
    }

    private suspend fun registerRemoteListeningHistory(mediaId: String) {
        if (!isRemoteHistorySyncAllowed()) return

        val attemptedUrls = LinkedHashSet<String>()

        suspend fun registerTrackingUrl(url: String): Boolean {
            attemptedUrls += url
            return YouTube.registerPlayback(playbackTracking = url)
                .onFailure {
                    reportException(it)
                }.isSuccess
        }

        val cachedPlaybackUrl = database.format(mediaId).first()?.playbackUrl
        val cachedSuccess = cachedPlaybackUrl?.let { registerTrackingUrl(it) } == true
        if (cachedSuccess) return

        val playbackTracking =
            YTPlayerUtils.playerResponseForMetadata(mediaId, null)
                .getOrNull()
                ?.playbackTracking
                ?: return

        for (
            trackingUrl in listOfNotNull(
                playbackTracking.videostatsPlaybackUrl?.baseUrl,
                playbackTracking.videostatsWatchtimeUrl?.baseUrl,
            )
        ) {
            if (trackingUrl.isBlank() || trackingUrl in attemptedUrls) continue
            registerTrackingUrl(trackingUrl)
        }
    }

    private suspend fun isRemoteHistorySyncAllowed(): Boolean {
        if (!dataStore.getAsync(YtmSyncKey, true)) return false
        val cookie = dataStore.getAsync(InnerTubeCookieKey, "")
        return cookie.isNotBlank() && cookie.contains("SAPISID")
    }

    // Create a transient Song object from current Player MediaMetadata when the DB doesn't have it.
    private fun createTransientSongFromMedia(media: com.dezzy.music.models.MediaMetadata): Song {
        val songEntity = SongEntity(
            id = media.id,
            title = media.title,
            duration = media.duration,
            thumbnailUrl = media.thumbnailUrl,
            albumId = media.album?.id,
            albumName = media.album?.title,
            explicit = media.explicit,
        )

        val artists = media.artists.map { artist ->
            ArtistEntity(
                id = artist.id ?: "LA_unknown_${artist.name}",
                name = artist.name,
                thumbnailUrl = if (!artist.thumbnailUrl.isNullOrBlank()) artist.thumbnailUrl else media.thumbnailUrl,
            )
        }

        val album = media.album?.let { alb ->
            AlbumEntity(
                id = alb.id,
                playlistId = null,
                title = alb.title,
                year = null,
                thumbnailUrl = media.thumbnailUrl,
                themeColor = null,
                songCount = 1,
                duration = media.duration,
            )
        }

        return Song(
            song = songEntity,
            artists = artists,
            album = album,
            format = null,
        )
    }

    private inline fun <reified T> readPersistentObject(fileName: String): T? {
        val persistentFile = filesDir.resolve(fileName)
        if (!persistentFile.exists() || !persistentFile.isFile) return null

        return synchronized(persistentStateLock) {
            runCatching {
                persistentFile.inputStream().use { fis ->
                    ObjectInputStream(fis).use { input ->
                        input.readObject() as? T
                    }
                }
            }.onFailure {
                Timber.tag("MusicService").w(it, "Failed to read persistent file: $fileName")
                runCatching { persistentFile.delete() }
            }.getOrNull()
        }
    }

    private fun writePersistentObject(fileName: String, payload: Serializable) {
        val persistentFile = filesDir.resolve(fileName)
        val tempFile = filesDir.resolve("$fileName.tmp")

        synchronized(persistentStateLock) {
            runCatching {
                FileOutputStream(tempFile).use { fos ->
                    ObjectOutputStream(fos).use { output ->
                        output.writeObject(payload)
                        output.flush()
                    }
                    fos.fd.sync()
                }

                if (persistentFile.exists() && !persistentFile.delete()) {
                    error("Could not replace $fileName")
                }
                if (!tempFile.renameTo(persistentFile)) {
                    error("Could not atomically move $fileName")
                }
            }.onFailure {
                runCatching { tempFile.delete() }
                reportException(it)
            }
        }
    }

    private fun MediaItem.toPersistableMetadata(): com.dezzy.music.models.MediaMetadata? {
        val tagged = metadata
        if (tagged != null) return tagged

        val id =
            mediaId.trim().ifBlank {
                localConfiguration?.uri?.toString()?.trim().orEmpty()
            }.takeIf { it.isNotBlank() } ?: return null

        val title =
            mediaMetadata.title?.toString()?.trim().takeIf { !it.isNullOrBlank() }
                ?: id

        val artistText =
            mediaMetadata.artist?.toString()?.trim().takeIf { !it.isNullOrBlank() }
                ?: mediaMetadata.subtitle?.toString()?.trim().takeIf { !it.isNullOrBlank() }

        val artists =
            artistText
                ?.split(",")
                ?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
                ?.map { name -> com.dezzy.music.models.MediaMetadata.Artist(id = null, name = name) }
                .orEmpty()

        val thumbnailUrl = mediaMetadata.artworkUri?.toString()
        val albumTitle = mediaMetadata.albumTitle?.toString()?.trim().takeIf { !it.isNullOrBlank() }
        val album =
            albumTitle?.let { titleValue ->
                com.dezzy.music.models.MediaMetadata.Album(id = titleValue, title = titleValue)
            }

        return com.dezzy.music.models.MediaMetadata(
            id = id,
            title = title,
            artists = artists,
            duration = -1,
            thumbnailUrl = thumbnailUrl,
            album = album,
            explicit = false,
            liked = false,
            likedDate = null,
            inLibrary = null,
        )
    }

    private suspend fun saveQueueToDisk() {
        val mediaItemsSnapshot = player.mediaItems.mapNotNull { it.toPersistableMetadata() }
        if (mediaItemsSnapshot.isEmpty()) return

        val currentMediaItemIndex = player.currentMediaItemIndex
        val currentPosition = player.currentPosition
        val automixSnapshot = automixItems.value.mapNotNull { it.metadata }
        val playWhenReady = player.playWhenReady
        val repeatMode = player.repeatMode
        val shuffleModeEnabled = player.shuffleModeEnabled
        val volume = playerVolume.value
        val playbackState = player.playbackState

        withContext(Dispatchers.IO) {
            // Save current queue with proper type information
            val persistQueue = currentQueue.toPersistQueue(
                title = queueTitle,
                items = mediaItemsSnapshot,
                mediaItemIndex = currentMediaItemIndex,
                position = currentPosition
            )
            
            val persistAutomix =
                PersistQueue(
                    title = "automix",
                    items = automixSnapshot,
                    mediaItemIndex = 0,
                    position = 0,
                )
                
            // Save player state
            val persistPlayerState = PersistPlayerState(
                playWhenReady = playWhenReady,
                repeatMode = repeatMode,
                shuffleModeEnabled = shuffleModeEnabled,
                volume = volume,
                currentPosition = currentPosition,
                currentMediaItemIndex = currentMediaItemIndex, // Redundant but part of data class
                playbackState = playbackState
            )
            
            writePersistentObject(PERSISTENT_QUEUE_FILE, persistQueue)
            writePersistentObject(PERSISTENT_AUTOMIX_FILE, persistAutomix)
            writePersistentObject(PERSISTENT_PLAYER_STATE_FILE, persistPlayerState)
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        stopVideoPlayerInternal()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        if (dataStore.get(PersistentQueueKey, true) && player.mediaItemCount > 0) {
            runCatching { runBlocking { saveQueueToDisk() } }
        }
        try {
            castController.release()
        } catch (_: Exception) {}
        unregisterBluetoothReceiver()
        try {
            unregisterReceiver(bubbleCommandReceiver)
        } catch (_: Exception) {}
        try {
            scope.launch { stopTogetherInternal() }
        } catch (_: Exception) {}
        try {
            DiscordPresenceManager.stop()
        } catch (_: Exception) {}
        try {
            discordRpc?.closeRPC()
        } catch (_: Exception) {}
        discordRpc = null
        try {
            connectivityObserver.unregister()
        } catch (_: Exception) {}
        abandonAudioFocus()
        try {
            releaseAudioEffects()
        } catch (_: Exception) {}
        try {
            if (dataStore.get(PersistentQueueKey, true) && player.mediaItemCount > 0) {
                val mediaItemsSnapshot = player.mediaItems.mapNotNull { it.metadata }
                val currentMediaItemIndex = player.currentMediaItemIndex
                val currentPosition = player.currentPosition
                val automixSnapshot = automixItems.value.mapNotNull { it.metadata }
                val repeatMode = player.repeatMode
                val shuffleModeEnabled = player.shuffleModeEnabled
                val volume = playerVolume.value
                val playbackState = player.playbackState
                val playWhenReady = player.playWhenReady
                runBlocking(Dispatchers.IO) {
                    val persistQueue = currentQueue.toPersistQueue(
                        title = queueTitle,
                        items = mediaItemsSnapshot,
                        mediaItemIndex = currentMediaItemIndex,
                        position = currentPosition
                    )
                    val persistAutomix = PersistQueue(
                        title = "automix",
                        items = automixSnapshot,
                        mediaItemIndex = 0,
                        position = 0,
                    )
                    val persistPlayerState = PersistPlayerState(
                        playWhenReady = playWhenReady,
                        repeatMode = repeatMode,
                        shuffleModeEnabled = shuffleModeEnabled,
                        volume = volume,
                        currentPosition = currentPosition,
                        currentMediaItemIndex = currentMediaItemIndex,
                        playbackState = playbackState
                    )

                    writePersistentObject(PERSISTENT_QUEUE_FILE, persistQueue)
                    writePersistentObject(PERSISTENT_AUTOMIX_FILE, persistAutomix)
                    writePersistentObject(PERSISTENT_PLAYER_STATE_FILE, persistPlayerState)
                }
            }
        } catch (_: Exception) {}
        try {
            mediaSession.release()
        } catch (_: Exception) {}
        try {
            crossfadeAudio?.release()
            crossfadeAudio = null
        } catch (_: Exception) {}
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            player.removeListener(this)
            player.removeListener(sleepTimer)
            player.release()
        } catch (_: Exception) {}
        scopeJob.cancel()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? {
        hasBoundClients = true
        cancelIdleStop()
        val result = super.onBind(intent) ?: binder
        if (player.mediaItemCount > 0 && player.currentMediaItem != null) {
            currentMediaMetadata.value = player.currentMetadata
            scope.launch {
                delay(50)
                updateNotification()
            }
        }
        return result
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hasBoundClients = false
        scheduleStopIfIdle()
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent?) {
        hasBoundClients = true
        cancelIdleStop()
        super.onRebind(intent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // When the user clears the app from Recents, ensure we clear Discord rich presence
        try {
            scope.launch {
                try { discordRpc?.stopActivity() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        try {
            if (discordRpc?.isRpcRunning() == true) {
                try { discordRpc?.closeRPC() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        discordRpc = null
        try { DiscordPresenceManager.stop() } catch (_: Exception) {}
        lastPresenceToken = null

        val stopMusicOnTaskClearEnabled = dataStore.get(StopMusicOnTaskClearKey, false)

        try {
            val state = togetherSessionState.value
            val isHostSessionActive =
                state is com.dezzy.music.together.TogetherSessionState.Hosting ||
                    state is com.dezzy.music.together.TogetherSessionState.HostingOnline ||
                    (state is com.dezzy.music.together.TogetherSessionState.Joined &&
                        state.role is com.dezzy.music.together.TogetherRole.Host)

            val isPlaybackInactive = player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 0

            if (shouldStopServiceOnTaskRemoved(stopMusicOnTaskClearEnabled, isHostSessionActive, isPlaybackInactive)) {
                if (isHostSessionActive && isPlaybackInactive) {
                    runCatching { scope.launch { stopTogetherInternal() } }
                    runCatching { togetherSessionState.value = com.dezzy.music.together.TogetherSessionState.Idle }
                    stopSelf()
                    return
                }

                if (stopMusicOnTaskClearEnabled) {
                    if (dataStore.get(PersistentQueueKey, true) && player.mediaItemCount > 0) {
                        runBlocking { saveQueueToDisk() }
                    }
                    runCatching { stopAndClearPlayback() }
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        } else {
                            stopForeground(true)
                        }
                    }
                    stopSelf()
                    return
                }
            }
        } catch (_: Exception) {}
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return if (::mediaSession.isInitialized) mediaSession else null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (startInForegroundRequired) ensureStartedAsForeground()
        runCatching { super.onUpdateNotification(session, startInForegroundRequired) }
            .onFailure { reportException(it) }
    }

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    private val bubbleCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra("command")) {
                // prepare() si quedó en IDLE: play() a secas no reanudaría (ver el fix
                // del botón bloqueado tras una llamada).
                "PLAY_PAUSE" -> if (player.isPlaying) player.pause() else resumeAfterFocusRegained()
                "NEXT" -> skipToNextManual()
                "PREVIOUS" -> skipToPreviousManual()
            }
        }
    }

    private fun sendBubbleUpdate(isPlaying: Boolean) {
        if (!isBubbleEnabled || !android.provider.Settings.canDrawOverlays(this)) return
        val song = player.currentMediaItem?.mediaMetadata
        val songData = currentMediaMetadata.value
        val intent = Intent(BubbleService.ACTION_UPDATE).apply {
            putExtra(BubbleService.EXTRA_TITLE, songData?.title ?: song?.title?.toString() ?: "")
            putExtra(BubbleService.EXTRA_ARTIST, songData?.artists?.joinToString(", ") { it.name } ?: song?.artist?.toString() ?: "")
            putExtra(BubbleService.EXTRA_IS_PLAYING, isPlaying)
            putExtra(BubbleService.EXTRA_ART_URL, songData?.thumbnailUrl ?: song?.artworkUri?.toString() ?: "")
            putExtra(BubbleService.EXTRA_POSITION, player.currentPosition)
            putExtra(BubbleService.EXTRA_DURATION, player.duration)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    /**
     * Refresca el widget de la pantalla de inicio. La carátula se carga con Coil en
     * segundo plano y solo si hay algún widget colocado (el propio provider descarta
     * la actualización si la lista de ids está vacía).
     */
    /**
     * Publica "escuchando ahora" para el panel de actividad de amigos y el Music
     * Map. `PresencePublisher` regula la frecuencia, así que es seguro llamarla
     * en cada evento del reproductor.
     */
    private fun publishSocialPresence() {
        val shareLocation = dataStore.get(MusicMapShareLocationKey, false)
        PresencePublisher.publish(
            context = this,
            scope = scope,
            metadata = currentMediaMetadata.value,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(0L),
            lat = if (shareLocation) OmegaMathIntegration.latitude else null,
            lng = if (shareLocation) OmegaMathIntegration.longitude else null,
        )
    }

    private fun updateHomeWidget(isPlaying: Boolean) {
        val meta = currentMediaMetadata.value
        val title = meta?.title
        val artist = meta?.artists?.joinToString(", ") { it.name }
        val artUrl = meta?.thumbnailUrl
        widgetUpdateJob?.cancel()
        widgetUpdateJob = scope.launch {
            val bitmap = artUrl?.let { url ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        val request = coil3.request.ImageRequest.Builder(this@MusicService)
                            .data(url)
                            .size(256, 256)
                            .allowHardware(false)
                            .build()
                        this@MusicService.imageLoader.execute(request).image?.toBitmap()
                    }.getOrNull()
                }
            }
            MusicWidgetProvider.update(this@MusicService, title, artist, isPlaying, bitmap)
        }
    }

    companion object {
        internal fun shouldStopServiceOnTaskRemoved(
            stopMusicOnTaskClearEnabled: Boolean,
            isHostSessionActive: Boolean,
            isPlaybackInactive: Boolean,
        ): Boolean = (isHostSessionActive && isPlaybackInactive) || stopMusicOnTaskClearEnabled

        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"

        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888
        // How long to wait after AUDIOFOCUS_LOSS_TRANSIENT before actually pausing — absorbs
        // the sub-second focus blips some launchers/OS gestures fire on every app switch.
        const val TRANSIENT_FOCUS_LOSS_GRACE_MS = 400L
        /** Pasado este punto, "anterior" reinicia la pista en vez de retroceder. */
        const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
        // Backoff del sondeo de reintento tras un error de red (ver scheduleNetworkErrorRetry).
        // Crece por intento fallido y se resetea al volver a STATE_READY.
        val NETWORK_RETRY_BACKOFF_MS = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L)
        const val ERROR_CODE_NO_STREAM = 1000001
        // 4 MB por chunk: la mayoría de canciones caben en 1-2 peticiones, minimizando
        // reaperturas del DataSource (cada reapertura es un punto potencial de corte).
        const val CHUNK_LENGTH = 4 * 1024 * 1024L
        const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
        const val PERSISTENT_AUTOMIX_FILE = "persistent_automix.data"
        const val PERSISTENT_PLAYER_STATE_FILE = "persistent_player_state.data"
        const val MAX_CONSECUTIVE_ERR = 5
        const val MIN_PRESENCE_UPDATE_INTERVAL = 20_000L
    }
}
