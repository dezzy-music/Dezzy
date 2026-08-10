/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Downloads a resource as several concurrent byte-range requests instead of one continuous
 * connection, then serves reads from the fully-assembled local file.
 *
 * Google's video CDN (`googlevideo.com`) throttles sustained throughput *per TCP connection*,
 * not per file or per account — this is the same server-side behavior yt-dlp/aria2/IDM work
 * around with concurrent-fragment downloads. A single-connection download of a song is capped
 * well below the CDN's actual per-IP allowance; splitting it into N range requests each gets its
 * own throttle bucket, multiplying real-world throughput roughly by N.
 *
 * Only used for the download pipeline (see [DownloadUtil]) — live playback keeps the simple
 * single-connection [androidx.media3.datasource.okhttp.OkHttpDataSource] since it needs low
 * latency to first byte, not maximum total throughput, and mid-stream seeks would be awkward to
 * reconcile with in-flight parallel chunks.
 */
class ParallelRangeDataSource(
    private val client: OkHttpClient,
    private val cacheDir: File,
    private val chunkCount: Int = 6,
    private val minBytesForParallelism: Long = 4L * 1024 * 1024,
) : BaseDataSource(/* isNetwork= */ true) {

    private var uri: Uri? = null
    private var tempFile: File? = null
    private var randomAccessFile: RandomAccessFile? = null
    private var bytesRemaining: Long = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val url = dataSpec.uri.toString()
        val startPosition = dataSpec.position

        try {
            val totalLength = probeTotalLength(url)
            val requestedLength =
                if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    dataSpec.length
                } else if (totalLength != null) {
                    totalLength - startPosition
                } else {
                    C.LENGTH_UNSET.toLong()
                }

            val file = File.createTempFile("dl_chunk_", ".part", cacheDir)
            file.deleteOnExit()

            if (totalLength == null || requestedLength < minBytesForParallelism) {
                // Server doesn't support ranges, or too little data left to bother splitting —
                // fall back to one plain sequential fetch into the same temp file.
                fetchRangeToFile(url, startPosition, null, file, destOffset = 0L)
                val actualLength = file.length()
                randomAccessFile = RandomAccessFile(file, "r")
                tempFile = file
                bytesRemaining = actualLength
                transferStarted(dataSpec)
                return bytesRemaining
            }

            // Pre-size the file so concurrent positional writes from different chunks never race
            // on extending it — each chunk writes to its own disjoint region of an already-sized file.
            RandomAccessFile(file, "rw").use { it.setLength(requestedLength) }
            downloadChunksInParallel(url, startPosition, requestedLength, file)

            randomAccessFile = RandomAccessFile(file, "r")
            tempFile = file
            bytesRemaining = requestedLength
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Failed to open parallel range download for $url", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val bytesToRead = minOf(length.toLong(), bytesRemaining).toInt()
        val read = randomAccessFile!!.read(buffer, offset, bytesToRead)
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            randomAccessFile?.close()
        } catch (_: Exception) {
        } finally {
            randomAccessFile = null
            tempFile?.delete()
            tempFile = null
            transferEnded()
        }
    }

    /** Cheap probe: fetches only the first byte to read `Content-Range: bytes 0-0/TOTAL`. */
    private fun probeTotalLength(url: String): Long? {
        val request = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val contentRange = response.header("Content-Range") ?: return null
            return contentRange.substringAfterLast('/').toLongOrNull()
        }
    }

    private fun downloadChunksInParallel(
        url: String,
        startPosition: Long,
        totalLength: Long,
        destFile: File,
    ) {
        val chunkSize = totalLength / chunkCount
        val ranges = (0 until chunkCount).map { i ->
            val chunkStart = startPosition + i * chunkSize
            val chunkEnd = if (i == chunkCount - 1) startPosition + totalLength - 1 else chunkStart + chunkSize - 1
            Triple(chunkStart, chunkEnd, chunkStart - startPosition)
        }

        val futures = ranges.map { (chunkStart, chunkEnd, destOffset) ->
            chunkExecutor.submit(
                Callable {
                    fetchRangeWithRetry(url, chunkStart, chunkEnd, destFile, destOffset)
                },
            )
        }

        var firstError: Exception? = null
        futures.forEach { future ->
            try {
                future.get()
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        firstError?.let { throw it }
    }

    private fun fetchRangeWithRetry(
        url: String,
        start: Long,
        end: Long,
        destFile: File,
        destOffset: Long,
        attempts: Int = 3,
    ) {
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                fetchRangeToFile(url, start, end, destFile, destOffset)
                return
            } catch (e: Exception) {
                lastError = e
                Timber.tag(TAG).w(e, "Chunk [$start-$end] attempt ${attempt + 1}/$attempts failed")
            }
        }
        throw lastError ?: IllegalStateException("Chunk download failed with no exception recorded")
    }

    /** Fetches one byte range (or the whole remaining body when [end] is null) into [destFile] at [destOffset]. */
    private fun fetchRangeToFile(
        url: String,
        start: Long,
        end: Long?,
        destFile: File,
        destOffset: Long,
    ) {
        val rangeHeader = if (end != null) "bytes=$start-$end" else "bytes=$start-"
        val request = Request.Builder().url(url).header("Range", rangeHeader).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("Unexpected HTTP ${response.code} for range $rangeHeader")
            }
            val body = response.body ?: throw java.io.IOException("Empty body for range $rangeHeader")
            RandomAccessFile(destFile, "rw").use { raf ->
                raf.channel.use { channel: FileChannel ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var position = destOffset
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            channel.write(java.nio.ByteBuffer.wrap(buffer, 0, read), position)
                            position += read
                        }
                    }
                }
            }
        }
    }

    class Factory(
        private val client: OkHttpClient,
        private val cacheDir: File,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ParallelRangeDataSource(client, cacheDir)
    }

    private companion object {
        const val TAG = "ParallelRangeDataSource"

        // Shared, bounded across every simultaneous download so total concurrent chunk requests
        // stays sane regardless of how many songs download at once (maxParallelDownloads x
        // chunkCount could otherwise fan out to dozens of connections).
        val chunkExecutor: ExecutorService = Executors.newFixedThreadPool(12)
    }
}
