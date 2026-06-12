/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.arturo254.opentune.playback

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
import com.arturo254.opentune.innertube.YouTube
import com.arturo254.opentune.innertube.models.YouTubeClient
import com.arturo254.opentune.constants.AudioQuality
import com.arturo254.opentune.constants.AudioQualityKey
import com.arturo254.opentune.constants.PlayerStreamClient
import com.arturo254.opentune.constants.PlayerStreamClientKey
import com.arturo254.opentune.db.MusicDatabase
import com.arturo254.opentune.db.entities.FormatEntity
import com.arturo254.opentune.db.entities.SongEntity
import com.arturo254.opentune.di.DownloadCache
import com.arturo254.opentune.di.PlayerCache
import com.arturo254.opentune.utils.YTPlayerUtils
import com.arturo254.opentune.utils.StreamClientUtils
import com.arturo254.opentune.utils.enumPreference
import com.arturo254.opentune.constants.NetworkMeteredKey
import com.arturo254.opentune.utils.dataStore
import com.arturo254.opentune.utils.get
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

    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    OkHttpDataSource.Factory(
                        mediaOkHttpClient,
                    ),
                ),
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            val length = if (dataSpec.length >= 0) dataSpec.length else 1
            if (playerCache.cacheSpace > 500 * 1024 * 1024L) {
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    playerCache.keys.shuffled().take(10).forEach { key ->
                        playerCache.getCachedSpans(key).sumOf { it.length }
                    }
                }
            }
            if (playerCache.isCached(mediaId, dataSpec.position, length)) {
                return@Factory dataSpec
            }
            songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                return@Factory dataSpec.withUri(it.first.toUri())
            }
            // withTimeout(30s) prevents indefinite thread blocking when YouTube is slow (P2 fix)
            val playbackData = runBlocking(Dispatchers.IO) {
                withTimeout(30_000L) {
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
                        codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                        bitrate = format.bitrate,
                        sampleRate = format.audioSampleRate,
                        contentLength = format.contentLength!!,
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                        perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                        playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
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
                        com.arturo254.opentune.db.entities.ArtistEntity(
                            id = author,
                            name = author
                        )
                    )
                    insert(
                        com.arturo254.opentune.db.entities.SongArtistMap(
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
            maxParallelDownloads = context.dataStore.get(com.arturo254.opentune.constants.MaxParallelDownloadsKey, 3)
            // 3 retries — enough for transient failures without waiting too long
            minRetryCount = 3
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        downloads.update { map ->
                            map.toMutableMap().apply {
                                set(download.request.id, download)
                            }
                        }
                        if (download.state == Download.STATE_COMPLETED) {
                            // Use stable internalScope instead of fire-and-forget CoroutineScope (P5 fix)
                            internalScope.launch {
                                downloadExporter.get().processDownload(download.request.id, false)
                            }
                        }
                    }
                }
            )
        }

    init {
        CoroutineScope(Dispatchers.IO).launch {
            val result = mutableMapOf<String, Download>()
            val cursor = downloadManager.downloadIndex.getDownloads()
            while (cursor.moveToNext()) {
                result[cursor.download.request.id] = cursor.download
            }
            downloads.value = result
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
