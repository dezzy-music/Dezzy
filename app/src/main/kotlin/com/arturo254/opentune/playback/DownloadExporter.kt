package com.arturo254.opentune.playback

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.arturo254.opentune.db.MusicDatabase
import com.arturo254.opentune.db.entities.SongEntity
import com.arturo254.opentune.db.entities.Song
import com.arturo254.opentune.db.entities.LyricsEntity
import com.arturo254.opentune.lyrics.LyricsHelper
import com.arturo254.opentune.models.toMediaMetadata
import com.arturo254.opentune.utils.reportException
import com.arturo254.opentune.ui.utils.resize
import com.arturo254.opentune.utils.toArtworkHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.firstOrNull
import okhttp3.OkHttpClient
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadExporter
@Inject
constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val lyricsHelper: LyricsHelper,
    @com.arturo254.opentune.di.DownloadCache private val downloadCache: androidx.media3.datasource.cache.Cache,
) {
    /**
     * Processes a completed download:
     * 1. Fetches lyrics and saves them to the database (Global Lyrics).
     * 2. If [exportToFile] is true, exports the song to the public Music folder with tags and .lrc file.
     */
    suspend fun processDownload(songId: String, exportToFile: Boolean) = withContext(Dispatchers.IO) {
        try {
            Timber.tag("DownloadExporter").e("Starting processDownload for songId: $songId, exportToFile: $exportToFile")
            val isVideo = songId.endsWith("_video")
            val originalSongId = if (isVideo) songId.removeSuffix("_video") else songId
            val song = database.getSongById(originalSongId) ?: return@withContext
            
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
                }
            }

            // 2. Always cache artwork locally for offline mini player display.
            if (!isVideo) {
                val songEntity = song.song
                songEntity.thumbnailUrl?.resize(512, 512)?.let { url ->
                    try {
                        val secureUrl = url.replace("http://", "https://")
                        val connection = URL(secureUrl).openConnection().apply {
                            setRequestProperty(
                                "User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
                            )
                            connectTimeout = 10000
                            readTimeout = 10000
                        }
                        val bytes = connection.getInputStream().use { it.readBytes() }
                        val artworkDir = File(context.filesDir, "artwork").also { it.mkdirs() }
                        val hash = songEntity.thumbnailUrl!!.toArtworkHash()
                        val artworkFile = File(artworkDir, "$hash.jpg")
                        artworkFile.writeBytes(bytes)
                        Timber.tag(TAG).d("Artwork cached locally for offline use: ${artworkFile.absolutePath}")
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Failed to cache artwork locally for $originalSongId")
                    }
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

            try {
                dataSource.open(dataSpec)
                
                val outputStream = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, destFile.name)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/$extension" else "audio/$extension")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Dezzy")
                    }
                    val uri = context.contentResolver.insert(
                        if (isVideo) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI 
                        else android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, 
                        contentValues
                    )
                    if (uri != null) context.contentResolver.openOutputStream(uri) else FileOutputStream(destFile)
                } else {
                    FileOutputStream(destFile)
                }
                
                outputStream?.use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (dataSource.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
                
                if (!isVideo) {
                    var artworkBytes: ByteArray? = if (cachedArtworkFile.exists()) {
                        cachedArtworkFile.readBytes()
                    } else {
                        null
                    }

                    if (artworkBytes == null) {
                        songEntity.thumbnailUrl?.resize(1200, 1200)?.let { url ->
                            try {
                                val secureUrl = url.replace("http://", "https://")
                                val connection = URL(secureUrl).openConnection().apply {
                                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                                    connectTimeout = 10000
                                    readTimeout = 10000
                                }
                                artworkBytes = connection.getInputStream().use { it.readBytes() }
                            } catch (e: Exception) {
                                Timber.tag(TAG).w(e, "Failed to download artwork for ${songEntity.id}")
                            }
                        }
                    }

                    // Save .jpg cover file
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
                            val coverFile = File(musicDir, "$baseName.jpg")
                            coverFile.writeBytes(bytes)
                        }
                    }

                    // Set ID3 Tags and Embed Cover Art
                    tagFile(destFile, song, artworkBytes)

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
                                // Fallback to direct file write if MediaStore fails
                                lrcFile.writeText(lyrics)
                            }
                        } else {
                            lrcFile.writeText(lyrics)
                        }
                    }
                }
                
                Timber.tag(TAG).i("File $songId exported successfully to ${destFile.absolutePath}")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Descarga guardada en Música/Dezzy", android.widget.Toast.LENGTH_SHORT).show()
                }

                val filesToScan = mutableListOf(destFile.absolutePath)
                if (!isVideo) {
                    File(musicDir, "$baseName.jpg").takeIf { it.exists() }?.let { filesToScan.add(it.absolutePath) }
                    lrcFile.takeIf { it.exists() }?.let { filesToScan.add(it.absolutePath) }
                }
                MediaScannerConnection.scanFile(
                    context,
                    filesToScan.toTypedArray(),
                    null
                ) { path, uri ->
                    Timber.tag(TAG).d("MediaScanner indexed: $path -> $uri")
                }

            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed during file export for $songId")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Error exportando archivo: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
                reportException(e)
            } finally {
                dataSource.close()
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

    companion object {
        private const val TAG = "DownloadExporter"
    }
}
