package com.dezzy.music.playback

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.dezzy.music.db.MusicDatabase
import com.dezzy.music.db.entities.SongEntity
import com.dezzy.music.db.entities.Song
import com.dezzy.music.db.entities.LyricsEntity
import com.dezzy.music.lyrics.LyricsHelper
import com.dezzy.music.models.toMediaMetadata
import com.dezzy.music.utils.reportException
import com.dezzy.music.ui.utils.resize
import com.dezzy.music.utils.toArtworkHash
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.disk.DiskCache
import okio.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.firstOrNull
import okhttp3.OkHttpClient
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import com.dezzy.music.utils.dataStore
import java.util.Locale
import com.dezzy.music.ui.player.fetchCanvasArtworkForPlayback
import com.dezzy.music.constants.EnableSpotifyCanvasKey
import com.dezzy.music.innertube.YouTube

import com.dezzy.music.constants.TranslateLyricsKey
import com.dezzy.music.constants.AiProviderKey
import com.dezzy.music.constants.OpenRouterApiKey
import com.dezzy.music.constants.DeeplApiKey
import com.dezzy.music.constants.OpenRouterBaseUrlKey
import com.dezzy.music.constants.OpenRouterModelKey
import com.dezzy.music.constants.TranslateModeKey
import com.dezzy.music.constants.TranslateLanguageKey
import com.dezzy.music.lyrics.LyricsTranslationHelper
import kotlinx.coroutines.flow.first

@Singleton
class DownloadExporter
@Inject
constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val lyricsHelper: LyricsHelper,
    @com.dezzy.music.di.DownloadCache private val downloadCache: androidx.media3.datasource.cache.Cache,
) {
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(YouTube.streamProxy)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * Processes a completed download:
     * 1. Fetches lyrics and saves them to the database (Global Lyrics).
     * 2. If [exportToFile] is true, exports the song to the public Music folder with tags and .lrc file.
     */
    suspend fun processDownload(songId: String, exportToFile: Boolean) = withContext(Dispatchers.IO) {
        try {
            Timber.tag(TAG).d("Starting processDownload for songId: $songId, exportToFile: $exportToFile")
            val isVideo = false
            val originalSongId = songId
            // The DownloadManager.Listener callback that triggers this fires from
            // DownloadUtil's async database.transaction { insert(...) } — that write can still be
            // in flight when a download completes near-instantly (e.g. audio already sat in the
            // player cache from prior playback), so the row may not be visible yet. Retry briefly
            // instead of silently abandoning all lyrics/artwork/export post-processing for this song.
            var songLookup = database.getSongById(originalSongId)
            if (songLookup == null) {
                Timber.tag(TAG).w("Song $originalSongId not found on first lookup, retrying...")
                for (attempt in 0 until 3) {
                    delay(300L * (attempt + 1))
                    songLookup = database.getSongById(originalSongId)
                    if (songLookup != null) break
                }
            }
            if (songLookup == null) {
                Timber.tag(TAG).e("Song $originalSongId still not found after retries, abandoning post-download processing")
                return@withContext
            }
            val song = songLookup

            var lyricsFetched: String? = null

            // 1. Global Lyrics: Fetch and save to database (Only for audio!)
            if (!isVideo) {
                val lyricsResult = lyricsHelper.getLyrics(song.toMediaMetadata())
                if (lyricsResult.isNotEmpty() && lyricsResult != LyricsEntity.LYRICS_NOT_FOUND) {
                    database.query {
                        upsert(
                            LyricsEntity(
                                id = originalSongId,
                                lyrics = lyricsResult
                            )
                        )
                    }
                    lyricsFetched = lyricsResult
                    Timber.tag(TAG).d("Global lyrics saved to database for $originalSongId")

                    // AI Lyrics Translation download / pre-fetch
                    try {
                        val prefs = context.dataStore.data.first()
                        val translateEnabled = prefs[TranslateLyricsKey] ?: false
                        if (translateEnabled) {
                            val aiProvider = prefs[AiProviderKey] ?: "OpenRouter"
                            val openRouterApiKey = prefs[OpenRouterApiKey] ?: ""
                            val deeplApiKey = prefs[DeeplApiKey] ?: ""
                            val apiKey = if (aiProvider == "DeepL") deeplApiKey else openRouterApiKey
                            
                            if (apiKey.isNotBlank()) {
                                val openRouterBaseUrl = prefs[OpenRouterBaseUrlKey] ?: "https://openrouter.ai/api/v1/chat/completions"
                                val openRouterModel = prefs[OpenRouterModelKey] ?: "mistralai/mistral-small-3.1-24b-instruct:free"
                                val translateMode = prefs[TranslateModeKey] ?: "Literal"
                                val translateLanguage = prefs[TranslateLanguageKey] ?: "en"

                                val parsedLines = com.dezzy.music.lyrics.LyricsUtils.parseLyrics(lyricsResult)
                                if (parsedLines.isNotEmpty()) {
                                    val lyricsTextForCache = parsedLines.filter { it.text.isNotBlank() }.joinToString("\n") { it.text }
                                    
                                    val modesToPrefetch = mutableSetOf("Literal", "Emotional")
                                    if (translateMode != "Literal" && translateMode != "Emotional") {
                                        modesToPrefetch.add(translateMode)
                                    }

                                    for (mode in modesToPrefetch) {
                                        val cacheKey = "${lyricsTextForCache.hashCode()}_${mode}_$translateLanguage"
                                        val existingTranslation = database.getLyricsTranslation(cacheKey)
                                        if (existingTranslation == null) {
                                            Timber.tag(TAG).d("Pre-fetching AI lyrics translation ($mode) for $originalSongId")
                                            try {
                                                LyricsTranslationHelper.prefetchTranslation(
                                                    lyrics = parsedLines,
                                                    targetLanguage = translateLanguage,
                                                    apiKey = openRouterApiKey,
                                                    baseUrl = openRouterBaseUrl,
                                                    model = openRouterModel,
                                                    mode = mode,
                                                    context = context,
                                                    database = database,
                                                    provider = aiProvider,
                                                    deeplApiKey = deeplApiKey,
                                                    deeplFormality = "default",
                                                    customSystemPrompt = ""
                                                )
                                            } catch (e: Exception) {
                                                Timber.tag(TAG).w(e, "Failed to pre-fetch translation ($mode) for $originalSongId")
                                            }
                                        } else {
                                            Timber.tag(TAG).d("AI lyrics translation ($mode) already exists in cache for $originalSongId")
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Failed to pre-fetch AI lyrics translation for $originalSongId")
                    }
                }
            }

            // 2. Always cache artwork locally for offline mini player display.
            if (!isVideo) {
                val songEntity = song.song
                val hash = songEntity.thumbnailUrl?.toArtworkHash()
                if (hash != null) {
                    val artworkDir = File(context.filesDir, "artwork").also { it.mkdirs() }
                    val artworkFile = File(artworkDir, "$hash.jpg")
                    if (!artworkFile.exists() || artworkFile.length() == 0L) {
                        songEntity.thumbnailUrl?.resize(512, 512)?.let { url ->
                            val bytes = getArtworkBytes(context, url)
                            if (bytes != null) {
                                try {
                                    artworkFile.writeBytes(bytes)
                                    Timber.tag(TAG).d("Artwork cached locally for offline use: ${artworkFile.absolutePath}")
                                } catch (e: Exception) {
                                    Timber.tag(TAG).w(e, "Failed to write artwork file locally for $originalSongId")
                                }
                            }
                        }
                    }
                }
            }

            // 3. Download and cache the Canvas video file for offline playback
            if (!isVideo) {
                try {
                    val localCanvasFile = File(context.filesDir, "canvas/$originalSongId.mp4")
                    if (!localCanvasFile.exists() || localCanvasFile.length() == 0L) {
                        val storefront = run {
                            val country = Locale.getDefault().country
                            if (country.length == 2) country.lowercase(Locale.ROOT) else "us"
                        }
                        val prefs = context.dataStore.data.first()
                        val canvasActiveForSpotify = prefs[EnableSpotifyCanvasKey] == true
                        val songEntity = song.song
                        val artistName = song.artists.joinToString { it.name }.ifEmpty { "Unknown Artist" }
                        
                        val canvasArtwork = fetchCanvasArtworkForPlayback(
                            songTitleRaw = songEntity.title,
                            artistNameRaw = artistName,
                            storefront = storefront,
                            requireVertical = false,
                            albumTitle = song.album?.title,
                            durationSeconds = songEntity.duration.takeIf { it > 0 },
                            spotifyCanvasEnabled = canvasActiveForSpotify
                        )
                        
                        val canvasUrl = canvasArtwork?.preferredVerticalAnimationUrl ?: canvasArtwork?.preferredAnimationUrl
                        if (!canvasUrl.isNullOrBlank()) {
                            Timber.tag(TAG).d("Found canvas URL for offline pre-fetch: $canvasUrl")
                            if (!canvasUrl.contains(".m3u8", ignoreCase = true)) {
                                val request = okhttp3.Request.Builder()
                                    .url(canvasUrl)
                                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                    .build()
                                okHttpClient.newCall(request).execute().use { response ->
                                    if (response.isSuccessful) {
                                        val body = response.body
                                        if (body != null) {
                                            localCanvasFile.parentFile?.mkdirs()
                                            localCanvasFile.outputStream().use { output ->
                                                body.byteStream().copyTo(output)
                                            }
                                            Timber.tag(TAG).d("Canvas downloaded successfully to ${localCanvasFile.absolutePath}")
                                        }
                                    } else {
                                        Timber.tag(TAG).w("Failed to download canvas, response code: ${response.code}")
                                    }
                                }
                            } else {
                                Timber.tag(TAG).d("Canvas is HLS (.m3u8), skipping direct file download.")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to download canvas for $originalSongId")
                }
            }


            if (!exportToFile) return@withContext

            // 2. Export to Device Folder
            val songEntity = song.song
            val artistName = song.artists.joinToString { it.name }.ifEmpty { "Unknown Artist" }

            // Reuse the locally cached artwork file
            val hash = songEntity.thumbnailUrl?.toArtworkHash() ?: originalSongId
            val cachedArtworkFile = File(context.filesDir, "artwork/$hash.jpg")
            
            // Prepare destination directory
            val musicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "Dezzy"
            )
            if (!musicDir.exists()) musicDir.mkdirs()

            // Prepare safe filename
            val safeArtist = artistName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val safeTitle = songEntity.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val baseName = "$safeArtist - $safeTitle"
            
            // Determine proper extension
            val formatEntity = database.format(songId).firstOrNull()
            val isWebm = formatEntity?.mimeType?.contains("webm") == true
            val isMp4 = formatEntity?.mimeType?.contains("mp4") == true
            val extension = when {
                isVideo -> "mp4"
                isWebm -> "webm"
                isMp4 -> "m4a"
                else -> "m4a"
            }
            
            val destFile = File(musicDir, "$baseName.$extension")
            val lrcFile = File(musicDir, "$baseName.lrc")
            // Proper MediaStore MIME (audio/m4a is non-standard; m4a is an MP4 container).
            val audioMime = when (extension) {
                "m4a" -> "audio/mp4"
                "webm" -> "audio/webm"
                "mp4" -> if (isVideo) "video/mp4" else "audio/mp4"
                else -> "audio/mpeg"
            }

            val upstreamFactory = OkHttpDataSource.Factory(
                OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
            ).apply {
                setDefaultRequestProperties(mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                ))
            }

            val dataSource = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(downloadCache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .createDataSource()

            val streamUrl = formatEntity?.playbackUrl?.takeIf { it.isNotBlank() }
                ?: "https://youtube.com/watch?v=$songId"

            val dataSpec = androidx.media3.datasource.DataSpec.Builder()
                .setUri(Uri.parse(streamUrl))
                .setKey(songId)
                .build()

            // Write to a PRIVATE temp file first, so we can embed ID3 tags + cover art
            // BEFORE publishing. On Android Q+ the public file is a MediaStore content
            // URI that jaudiotagger can't open by path — the old code tagged a raw public
            // path that doesn't exist there, so exported files had no tags/cover at all.
            val tempFile = File(context.cacheDir, "export_${System.currentTimeMillis()}.$extension")
            try {
                dataSource.open(dataSpec)
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (dataSource.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
                dataSource.close()

                // Resolve artwork once (embedded tag + sidecar .jpg).
                var artworkBytes: ByteArray? = if (!isVideo && cachedArtworkFile.exists()) {
                    cachedArtworkFile.readBytes().takeIf { it.isNotEmpty() }
                } else null
                if (!isVideo && artworkBytes == null) {
                    songEntity.thumbnailUrl?.resize(1200, 1200)?.let { url ->
                        artworkBytes = getArtworkBytes(context, url)
                    }
                }

                // Embed tags + cover into the temp file before publishing.
                if (!isVideo) tagFile(tempFile, song, artworkBytes)

                // Publish the tagged file to the public Music/Dezzy folder.
                val published: Boolean = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.$extension")
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, audioMime)
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Dezzy")
                    }
                    val collection = if (isVideo)
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    val uri = context.contentResolver.insert(collection, contentValues)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            tempFile.inputStream().use { it.copyTo(out) }
                        }
                        true
                    } else false
                } else {
                    if (!musicDir.exists()) musicDir.mkdirs()
                    tempFile.copyTo(destFile, overwrite = true)
                    true
                }

                if (!isVideo && published) {
                    // Save .jpg cover sidecar
                    artworkBytes?.let { bytes ->
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            val values = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.jpg")
                                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Dezzy")
                            }
                            context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            }
                        } else {
                            File(musicDir, "$baseName.jpg").writeBytes(bytes)
                        }
                    }

                    // Save .lrc file
                    lyricsFetched?.let { lyrics ->
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            val values = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.lrc")
                                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Dezzy")
                            }
                            try {
                                context.contentResolver.insert(android.provider.MediaStore.Files.getContentUri("external"), values)?.let { uri ->
                                    context.contentResolver.openOutputStream(uri)?.use { it.write(lyrics.toByteArray()) }
                                }
                            } catch (e: Exception) {
                                lrcFile.writeText(lyrics)
                            }
                        } else {
                            lrcFile.writeText(lyrics)
                        }
                    }
                }

                Timber.tag(TAG).i("File $songId exported successfully (published=$published)")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Descarga guardada en Música/Dezzy", android.widget.Toast.LENGTH_SHORT).show()
                }

                // Pre-Q writes go straight to the filesystem, so a media scan surfaces them.
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                    val filesToScan = mutableListOf(destFile.absolutePath)
                    if (!isVideo) {
                        File(musicDir, "$baseName.jpg").takeIf { it.exists() }?.let { filesToScan.add(it.absolutePath) }
                        lrcFile.takeIf { it.exists() }?.let { filesToScan.add(it.absolutePath) }
                    }
                    MediaScannerConnection.scanFile(context, filesToScan.toTypedArray(), null) { path, uri ->
                        Timber.tag(TAG).d("MediaScanner indexed: $path -> $uri")
                    }
                }

            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed during file export for $songId")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Error exportando archivo: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
                reportException(e)
            } finally {
                runCatching { dataSource.close() }
                runCatching { if (tempFile.exists()) tempFile.delete() }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to process download $songId")
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Error procesando descarga: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            reportException(e)
        }
    }

    private suspend fun tagFile(audioFile: File, song: Song, artworkBytes: ByteArray?) {
        try {
            val f = AudioFileIO.read(audioFile)
            val tag = f.tag ?: f.createDefaultTag()
            
            val songEntity = song.song
            val album = song.album
            val artistName = song.artists.joinToString { it.name }
            
            tag.setField(FieldKey.TITLE, songEntity.title)
            tag.setField(FieldKey.ARTIST, artistName)
            tag.setField(FieldKey.ALBUM, album?.title ?: "")
            
            // Embed Artwork if available
            artworkBytes?.let { bytes ->
                try {
                    val artwork = org.jaudiotagger.tag.images.AndroidArtwork()
                    artwork.binaryData = bytes
                    artwork.mimeType = "image/jpeg"
                    artwork.pictureType = org.jaudiotagger.tag.reference.PictureTypes.DEFAULT_ID
                    artwork.description = "Cover"
                    tag.setField(artwork)
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to embed artwork into tag for ${songEntity.id}")
                }
            }
            
            f.tag = tag
            f.commit()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to tag file for ${song.id} (likely unsupported format for tagging)")
            reportException(e)
        }
    }

    private suspend fun getArtworkBytes(context: Context, url: String): ByteArray? {
        try {
            val secureUrl = url.replace("http://", "https://")
            
            // 1. Try to fetch from Coil's disk cache
            val imageLoader = context.imageLoader
            val diskCache = imageLoader.diskCache
            if (diskCache != null) {
                val snapshot = diskCache.openSnapshot(secureUrl) ?: diskCache.openSnapshot(url)
                snapshot?.use { snap ->
                    val bytes = snap.data.toFile().readBytes()
                    if (bytes.isNotEmpty()) {
                        Timber.tag(TAG).d("Artwork loaded from Coil disk cache: $url")
                        return bytes
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d("Could not load artwork from Coil disk cache: ${e.message}")
        }

        // 2. Fallback to network request via shared OkHttpClient (respects proxy, consistent with rest of app)
        try {
            val secureUrl = url.replace("http://", "https://")
            val request = okhttp3.Request.Builder()
                .url(secureUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .build()
            val bytes = okHttpClient.newCall(request).execute().use { response ->
                response.body?.bytes()
            }
            if (bytes != null && bytes.isNotEmpty()) return bytes
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to download artwork from network: $url")
        }
        return null
    }

    companion object {
        private const val TAG = "DownloadExporter"
    }
}
