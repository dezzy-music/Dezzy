/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.dezzy.music.playback

import android.content.Context
import android.media.MediaCodecList
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.YouTubeClient
import com.dezzy.music.constants.AudioQuality
import com.dezzy.music.constants.AudioQualityKey
import com.dezzy.music.constants.PlayerStreamClient
import com.dezzy.music.constants.PlayerStreamClientKey
import com.dezzy.music.db.MusicDatabase
import com.dezzy.music.db.entities.FormatEntity
import com.dezzy.music.db.entities.SongEntity
import com.dezzy.music.di.DownloadCache
import com.dezzy.music.di.PlayerCache
import com.dezzy.music.utils.YTPlayerUtils
import com.dezzy.music.utils.StreamClientUtils
import com.dezzy.music.utils.enumPreference
import com.dezzy.music.constants.NetworkMeteredKey
import com.dezzy.music.utils.dataStore
import com.dezzy.music.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import androidx.media3.exoplayer.offline.DownloadRequest

@Singleton
class DownloadUtil
@Inject
constructor(
    @ApplicationContext context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: Cache,
    @PlayerCache val playerCache: Cache,
    private val downloadExporter: dagger.Lazy<DownloadExporter>,
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val preferredStreamClient by enumPreference(context, PlayerStreamClientKey, PlayerStreamClient.ANDROID_VR)
    // ConcurrentHashMap is thread-safe: multiple download threads can read/write simultaneously (P1 fix)
    private val songUrlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    // Stable scope for post-download processing — survives across DownloadManager listener calls (P5 fix)
    private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val avoidStreamCodecs: Set<String> by lazy {
        if (deviceSupportsMimeType("audio/opus")) emptySet() else setOf("opus")
    }
    private val mediaOkHttpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .proxy(YouTube.streamProxy)
            .followRedirects(true)
            .followSslRedirects(true)
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    // OkHttp defaults to 5 concurrent requests per host, which would silently
                    // serialize most of ParallelRangeDataSource's chunk requests to googlevideo.
                    maxRequestsPerHost = 12
                    maxRequests = 24
                },
            )
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

                val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                val originReferer = StreamClientUtils.resolveOriginReferer(clientParam)

                val builder = request.newBuilder().header("User-Agent", userAgent)
                originReferer.origin?.let { builder.header("Origin", it) }
                originReferer.referer?.let { builder.header("Referer", it) }

                chain.proceed(builder.build())
            }.build()
    }

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    // Source of truth for download states. The listener below mutates this map in place and
    // pokes `downloadsDirty`; a single collector publishes throttled immutable snapshots into
    // `downloads`. The old code did `map.toMutableMap()` on EVERY onDownloadChanged event —
    // with thousands of queued downloads (Spotify preload) that's an O(N) full-map copy per
    // event on DownloadManager's main-thread handler, which OOM'd low-RAM devices
    // (java.lang.OutOfMemoryError in HashMap.resize on a 128 MB heap).
    private val downloadsMap = ConcurrentHashMap<String, Download>()
    // replay = 1 so a tick emitted before the collector subscribes isn't lost.
    private val downloadsDirty = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    // Downloads specifically (not live playback) fetch each song as several
                    // concurrent byte-range requests — googlevideo throttles per-connection, so
                    // this multiplies real throughput instead of hitting one connection's cap.
                    ParallelRangeDataSource.Factory(
                        mediaOkHttpClient,
                        context.cacheDir,
                    ),
                ),
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            val length = if (dataSpec.length >= 0) dataSpec.length else 1
            if (playerCache.isCached(mediaId, dataSpec.position, length)) {
                return@Factory dataSpec
            }
            songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                return@Factory dataSpec.withUri(it.first.toUri())
            }
            val playbackData = runBlocking(Dispatchers.IO) {
                withTimeout(15_000L) {
                    val networkMeteredPref = context.dataStore.get(NetworkMeteredKey, true)
                    YTPlayerUtils.playerResponseForPlayback(
                        mediaId,
                        audioQuality = audioQuality,
                        preferredStreamClient = preferredStreamClient,
                        connectivityManager = connectivityManager,
                        networkMetered = networkMeteredPref,
                        avoidCodecs = avoidStreamCodecs,
                    )
                }
            }.getOrThrow()
            val format = playbackData.format

            database.query {
                upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = format.itag,
                        mimeType = format.mimeType.split(";")[0],
                        codecs = format.mimeType.split("codecs=").getOrNull(1)?.removeSurrounding("\"") ?: "",
                        bitrate = format.bitrate,
                        sampleRate = format.audioSampleRate,
                        contentLength = format.contentLength ?: 0L,
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                        perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                        playbackUrl = playbackData.streamUrl
                    ),
                )

                val now = LocalDateTime.now()
                val existing = getSongByIdBlocking(mediaId)?.song

                val updatedSong = if (existing != null) {
                    if (existing.dateDownload == null) existing.copy(dateDownload = now) else existing
                } else {
                    SongEntity(
                        id = mediaId,
                        title = playbackData.videoDetails?.title ?: "Unknown",
                        duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                        thumbnailUrl = playbackData.videoDetails?.thumbnail?.getHighestResUrl(),
                        dateDownload = now
                    )
                }

                val author = playbackData.videoDetails?.author
                if (existing == null && author != null) {
                    insert(
                        com.dezzy.music.db.entities.ArtistEntity(
                            id = author,
                            name = author
                        )
                    )
                    insert(
                        com.dezzy.music.db.entities.SongArtistMap(
                            songId = mediaId,
                            artistId = author,
                            position = 0
                        )
                    )
                }
                upsert(updatedSong)
            }

            val streamUrl = playbackData.streamUrl

            songUrlCache[mediaId] = streamUrl to (System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L))
            dataSpec.withUri(streamUrl.toUri())
        }

    val downloadNotificationHelper =
        DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            // Thread pool size = 5 for faster concurrent resolution
            Executors.newFixedThreadPool(5)
        ).apply {
            // 3 parallel downloads by default — good balance between speed and stability
            maxParallelDownloads = context.dataStore.get(com.dezzy.music.constants.MaxParallelDownloadsKey, 3)
            // 3 retries — enough for transient failures without waiting too long
            minRetryCount = 3
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        // O(1) and allocation-free on the DownloadManager handler thread;
                        // snapshot publishing happens throttled on internalScope.
                        downloadsMap[download.request.id] = download
                        downloadsDirty.tryEmit(Unit)
                        if (download.state == Download.STATE_COMPLETED) {
                            internalScope.launch {
                                val exportEnabled = context.dataStore.get(
                                    com.dezzy.music.constants.ExportDownloadsToMusicKey, false
                                )
                                downloadExporter.get().processDownload(download.request.id, exportEnabled)
                            }
                        }
                    }

                    override fun onDownloadRemoved(
                        downloadManager: DownloadManager,
                        download: Download,
                    ) {
                        downloadsMap.remove(download.request.id)
                        downloadsDirty.tryEmit(Unit)
                    }
                }
            )
        }

    init {
        internalScope.launch {
            val cursor = downloadManager.downloadIndex.getDownloads()
            while (cursor.moveToNext()) {
                downloadsMap[cursor.download.request.id] = cursor.download
            }
            downloadsDirty.tryEmit(Unit)
        }
        // Coalesces bursts of download events into at most ~2 snapshots per second.
        // The 1-slot DROP_OLDEST buffer means any events arriving during the delay
        // collapse into a single pending tick, so the last state is always published.
        internalScope.launch {
            downloadsDirty.collect {
                downloads.value = HashMap(downloadsMap)
                delay(500)
            }
        }
    }

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    /**
     * Centralised factory for DownloadRequests — prevents duplicated construction logic (P6 fix).
     * Encodes the song title as the request data so notifications can display it.
     */
    fun String.toDownloadRequest(title: String): DownloadRequest =
        DownloadRequest.Builder(this, this.toUri())
            .setCustomCacheKey(this)
            .setData(title.toByteArray())
            .build()

    private fun deviceSupportsMimeType(mimeType: String): Boolean {
        return runCatching {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            codecList.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }
}
