package com.dezzy.music.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Descarga el videoclip que está sonando, CON audio, a Películas/Dezzy.
 *
 * - Si hay video y audio separados en MP4 (avc1 + AAC), los baja y los une con MediaMuxer
 *   (sin recodificar: rápido y sin pérdida).
 * - Si sólo hay un progresivo con audio incluido, lo baja tal cual.
 * - Si sólo hay HLS (streaming), avisa de que no se puede guardar.
 *
 * La UI observa [state] para mostrar el progreso (sin Toasts).
 */
object MusicVideoDownloader {

    sealed interface State {
        data object Idle : State
        /** [progress] en 0f..1f, o null si aún no se conoce el tamaño. */
        data class Running(val title: String, val progress: Float?) : State
        data class Done(val message: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()

    /** Tamaño de cada petición por rangos: YouTube corta conexiones largas sin Range. */
    private const val CHUNK_BYTES = 9_500_000L

    fun reportUnavailable(message: String) {
        _state.value = State.Failed(message)
    }

    /** La UI lo llama al cerrar el aviso de resultado. */
    fun clearResult() {
        val s = _state.value
        if (s is State.Done || s is State.Failed) _state.value = State.Idle
    }

    suspend fun download(context: Context, video: MusicVideoResolver.ResolvedVideo, title: String?, artist: String?) {
        if (mutex.isLocked) {
            _state.value = State.Failed("Ya hay una descarga de video en curso")
            return
        }
        mutex.withLock {
            val niceTitle = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title?.takeIf { it.isNotBlank() })
                .joinToString(" - ")
                .ifBlank { video.title ?: video.videoId }
            _state.value = State.Running(niceTitle, null)
            val result = runCatching { withContext(Dispatchers.IO) { downloadInternal(context, video, niceTitle) } }
            _state.value = result.fold(
                onSuccess = { State.Done("Video guardado en Películas/Dezzy") },
                onFailure = { e ->
                    Timber.tag("MusicVideoDownloader").w(e, "Fallo descargando video")
                    State.Failed(
                        when (e) {
                            is StreamingOnlyException -> "Este video sólo está disponible en streaming"
                            is IOException -> "No se pudo descargar el video. Revisa tu conexión"
                            else -> "No se pudo guardar el video"
                        },
                    )
                },
            )
        }
    }

    private class StreamingOnlyException : Exception()

    private fun isMp4(url: String): Boolean =
        url.contains("mime=video%2Fmp4") || url.contains("mime=audio%2Fmp4") ||
            url.contains("mime=video/mp4") || url.contains("mime=audio/mp4")

    private fun downloadInternal(context: Context, video: MusicVideoResolver.ResolvedVideo, title: String) {
        val tmpDir = File(context.cacheDir, "video_dl").apply { mkdirs() }
        val outTmp = File(tmpDir, "out_${System.currentTimeMillis()}.mp4")
        val videoUrl = video.videoUrl
        val audioUrl = video.audioUrl
        try {
            when {
                videoUrl != null && audioUrl != null && isMp4(videoUrl) && isMp4(audioUrl) -> {
                    val vFile = File(tmpDir, "v.mp4")
                    val aFile = File(tmpDir, "a.m4a")
                    try {
                        // El video pesa mucho más: 90 % de la barra para él, 10 % para el audio.
                        fetch(videoUrl, vFile, title, 0f, 0.9f)
                        fetch(audioUrl, aFile, title, 0.9f, 0.1f)
                        mux(vFile, aFile, outTmp)
                    } finally {
                        vFile.delete()
                        aFile.delete()
                    }
                }
                video.muxedUrl != null -> fetch(video.muxedUrl, outTmp, title, 0f, 1f)
                else -> throw StreamingOnlyException()
            }
            publish(context, outTmp, title)
        } finally {
            outTmp.delete()
        }
    }

    /** Baja [url] a [dest] en trozos por rango, informando el progreso en [base]..[base]+[span]. */
    private fun fetch(url: String, dest: File, title: String, base: Float, span: Float) {
        val client = MusicVideoResolver.httpClient
        var total = -1L
        var written = 0L
        FileOutputStream(dest).use { out ->
            while (total < 0 || written < total) {
                val end = written + CHUNK_BYTES - 1
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", MusicVideoResolver.USER_AGENT)
                    .header("Range", "bytes=$written-$end")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Respuesta vacía")
                    if (total < 0) {
                        total = response.header("Content-Range")
                            ?.substringAfterLast('/')
                            ?.toLongOrNull()
                            ?: body.contentLength().takeIf { it > 0 && response.code == 200 }
                            ?: -1L
                    }
                    val before = written
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (total > 0) {
                                _state.value = State.Running(title, base + span * (written.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                    // Servidor que ignora Range (200 completo) o tamaño desconocido: ya está todo.
                    if (response.code == 200 || total < 0) {
                        total = written
                    }
                    if (written == before) throw IOException("Descarga interrumpida")
                }
            }
        }
    }

    /** Une la pista de video y la de audio en un MP4, sin recodificar. */
    private fun mux(videoFile: File, audioFile: File, out: File) {
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vEx = MediaExtractor()
        val aEx = MediaExtractor()
        var started = false
        try {
            vEx.setDataSource(videoFile.absolutePath)
            aEx.setDataSource(audioFile.absolutePath)
            val vTrack = findTrack(vEx, "video/") ?: throw IOException("Sin pista de video")
            val aTrack = findTrack(aEx, "audio/") ?: throw IOException("Sin pista de audio")
            vEx.selectTrack(vTrack)
            aEx.selectTrack(aTrack)
            val vFormat = vEx.getTrackFormat(vTrack)
            val aFormat = aEx.getTrackFormat(aTrack)
            val vOut = muxer.addTrack(vFormat)
            val aOut = muxer.addTrack(aFormat)
            muxer.start()
            started = true
            val bufSize = maxOf(
                vFormat.intOrNull(MediaFormat.KEY_MAX_INPUT_SIZE) ?: 0,
                aFormat.intOrNull(MediaFormat.KEY_MAX_INPUT_SIZE) ?: 0,
                2 * 1024 * 1024,
            )
            val buffer = ByteBuffer.allocate(bufSize)
            copySamples(vEx, muxer, vOut, buffer)
            copySamples(aEx, muxer, aOut, buffer)
        } finally {
            vEx.release()
            aEx.release()
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun findTrack(ex: MediaExtractor, prefix: String): Int? =
        (0 until ex.trackCount).firstOrNull { i ->
            ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
        }

    private fun copySamples(ex: MediaExtractor, muxer: MediaMuxer, track: Int, buffer: ByteBuffer) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = ex.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = ex.sampleTime
            info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(track, buffer, info)
            ex.advance()
        }
    }

    private fun safeName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120).ifBlank { "video" }

    /** Copia el MP4 terminado a Películas/Dezzy (MediaStore en Android 10+). */
    private fun publish(context: Context, file: File, title: String) {
        val name = "${safeName(title)}.mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Dezzy")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri: Uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("No se pudo crear el archivo")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("No se pudo escribir el archivo")
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            // Android 8–9: carpeta propia de la app (no requiere permiso de almacenamiento).
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Dezzy").apply { mkdirs() }
            file.copyTo(File(dir, name), overwrite = true)
        }
    }
}
