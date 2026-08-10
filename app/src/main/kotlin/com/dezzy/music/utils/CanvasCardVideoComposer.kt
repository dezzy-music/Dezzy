/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import com.dezzy.music.ui.theme.getTypeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

enum class CanvasExportMode {
    FULL_BRANDING,
    PURE_CANVAS,
}

/**
 * Genera un VIDEO del Canvas en dos modos:
 * 1. FULL_BRANDING: Tarjeta 3:4 con diseño, fondo, título, artista y logo ★ Dezzy.
 * 2. PURE_CANVAS: Video 9:16 (1080x1920) pantalla completa sin cuadro, sin textos ni marcas.
 *
 * Transcodifica el audio a AAC (audio/mp4a-latm) para garantizar compatibilidad total
 * con WhatsApp, Instagram Stories, TikTok y reproductores nativos.
 */
object CanvasCardVideoComposer {

    private const val WIDTH_BRANDING = 1080
    private const val HEIGHT_BRANDING = 1440
    private const val WIDTH_PURE = 1080
    private const val HEIGHT_PURE = 1920

    private const val MAX_DURATION_MS = 8_000L
    private const val BIT_RATE = 14_000_000
    private const val FALLBACK_FPS = 15
    private const val FRAME_BATCH = 6

    suspend fun compose(
        context: Context,
        canvasFile: File,
        title: String,
        artist: String,
        bgColor: Int,
        bgGradient: Boolean,
        textColor: Int,
        secondaryColor: Int,
        fontName: String = "SYSTEM",
        output: File,
        targetDurationMs: Long = 30_000L,
        audioFile: File? = null,
        audioStartMs: Long = 0L,
        mode: CanvasExportMode = CanvasExportMode.FULL_BRANDING,
    ): Result<File> = withContext(Dispatchers.Default) {
        runCatching {
            // Fast path para PURE_CANVAS: copia de paquetes sin re-codificación de video (sub-segundo)
            if (mode == CanvasExportMode.PURE_CANVAS) {
                if (fastComposePureCanvas(context, canvasFile, output, targetDurationMs, audioFile, audioStartMs)) {
                    return@runCatching output
                }
            }

            val retriever = MediaMetadataRetriever()
            var encoder: MediaCodec? = null
            var muxer: MediaMuxer? = null
            var audioExtractor: MediaExtractor? = null
            var tempAacFile: File? = null

            try {
                retriever.setDataSource(canvasFile.absolutePath)
                val fullDurationMs = (retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                )?.toLongOrNull() ?: MAX_DURATION_MS).coerceAtLeast(1L)
                val durationMs = targetDurationMs.coerceIn(1_000L, 120_000L)

                val width = if (mode == CanvasExportMode.PURE_CANVAS) WIDTH_PURE else WIDTH_BRANDING
                val height = if (mode == CanvasExportMode.PURE_CANVAS) HEIGHT_PURE else HEIGHT_BRANDING

                // ── Preparar pista de audio transcodificada a AAC para compatibilidad total ──
                var audioFormat: MediaFormat? = null
                var audioTrackIdxInFile = -1
                if (audioFile != null && audioFile.exists()) {
                    val tempFile = File(context.cacheDir, "temp_transcoded_audio_${System.currentTimeMillis()}.mp4")
                    if (transcodeAudioToAac(audioFile, audioStartMs, durationMs, tempFile)) {
                        tempAacFile = tempFile
                        val ext = MediaExtractor()
                        ext.setDataSource(tempFile.absolutePath)
                        for (i in 0 until ext.trackCount) {
                            val fmt = ext.getTrackFormat(i)
                            val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                            if (mime.startsWith("audio/")) {
                                audioTrackIdxInFile = i
                                audioFormat = fmt
                                audioExtractor = ext
                                break
                            }
                        }
                    }
                }

                // ── Decodificación de frames nativos (API 28+) ──
                val srcFrameCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT,
                    )?.toIntOrNull() ?: 0
                } else 0
                val srcFps = if (srcFrameCount > 0) srcFrameCount * 1000f / fullDurationMs else 0f
                val canSequential = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    srcFps >= 8f && srcFps <= 61f

                var frameStep = 1
                var outFps = if (canSequential) srcFps else FALLBACK_FPS.toFloat()
                while (outFps > 40f) {
                    frameStep *= 2
                    outFps /= 2f
                }
                var frameDurationUs = (1_000_000.0 / outFps).toLong()

                // ── Layout según el modo ──
                val overlayBitmap: Bitmap?
                val videoRect: RectF
                val clipPath: Path?

                if (mode == CanvasExportMode.PURE_CANVAS) {
                    overlayBitmap = null
                    videoRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
                    clipPath = null
                } else {
                    val (bmp, rect) = cardOverlay(context, title, artist, bgColor, bgGradient, textColor, secondaryColor, fontName)
                    overlayBitmap = bmp
                    videoRect = rect
                    clipPath = Path().apply {
                        addRoundRect(videoRect, width * 0.04f, width * 0.04f, Path.Direction.CW)
                    }
                }

                // ── MediaCodec Video Encoder (H.264 / AVC) ──
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_FRAME_RATE, outFps.roundToInt().coerceAtLeast(1))
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // I-frame cada 1s para seeking en WhatsApp/Instagram
                }
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
                val inputSurface = encoder.createInputSurface()
                encoder.start()

                if (output.exists()) output.delete()
                muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var videoTrackIndex = -1
                var audioTrackIndex = -1
                var muxerStarted = false
                var encodedFrames = 0L
                val bufferInfo = MediaCodec.BufferInfo()

                fun drainEncoder(endOfStream: Boolean) {
                    val enc = encoder ?: return
                    if (endOfStream) enc.signalEndOfInputStream()
                    while (true) {
                        val status = enc.dequeueOutputBuffer(bufferInfo, 10_000)
                        when {
                            status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                if (!endOfStream) return
                            }
                            status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                videoTrackIndex = muxer!!.addTrack(enc.outputFormat)
                                if (audioFormat != null) {
                                    audioTrackIndex = muxer!!.addTrack(audioFormat!!)
                                }
                                muxer!!.start()
                                muxerStarted = true
                            }
                            status >= 0 -> {
                                val data = enc.getOutputBuffer(status)
                                if (data != null && bufferInfo.size > 0 && muxerStarted &&
                                    (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                                ) {
                                    bufferInfo.presentationTimeUs = encodedFrames * frameDurationUs
                                    encodedFrames++
                                    data.position(bufferInfo.offset)
                                    data.limit(bufferInfo.offset + bufferInfo.size)
                                    muxer!!.writeSampleData(videoTrackIndex, data, bufferInfo)
                                }
                                enc.releaseOutputBuffer(status, false)
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                            }
                        }
                    }
                }

                val framePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                var sentFrames = 0L

                fun encodeFrame(frame: Bitmap?) {
                    sentFrames++
                    val canvas = inputSurface.lockHardwareCanvas()
                    try {
                        if (overlayBitmap != null) {
                            canvas.drawBitmap(overlayBitmap, 0f, 0f, null)
                        } else {
                            canvas.drawColor(android.graphics.Color.BLACK)
                        }
                        if (frame != null) {
                            val drawBlock = {
                                val scale = maxOf(
                                    videoRect.width() / frame.width,
                                    videoRect.height() / frame.height,
                                )
                                val dw = frame.width * scale
                                val dh = frame.height * scale
                                val left = videoRect.left + (videoRect.width() - dw) / 2f
                                val top = videoRect.top + (videoRect.height() - dh) / 2f
                                canvas.drawBitmap(frame, null, RectF(left, top, left + dw, top + dh), framePaint)
                            }
                            if (clipPath != null) {
                                canvas.withClip(clipPath) { drawBlock() }
                            } else {
                                drawBlock()
                            }
                        }
                    } finally {
                        inputSurface.unlockCanvasAndPost(canvas)
                    }
                    drainEncoder(endOfStream = false)
                }

                // ── Renderizado y Bucle de Frames ──
                val totalOutFrames = (durationMs * outFps / 1000f).toInt().coerceAtLeast(1)

                if (canSequential && srcFrameCount > 0) {
                    runCatching {
                        val cachedFrames = mutableListOf<Bitmap>()
                        var index = 0
                        while (index < srcFrameCount) {
                            val batch = minOf(FRAME_BATCH, srcFrameCount - index)
                            val frames = retriever.getFramesAtIndex(index, batch)
                            if (frames.isEmpty()) break
                            cachedFrames.addAll(frames)
                            index += frames.size
                        }

                        if (cachedFrames.isNotEmpty()) {
                            for (i in 0 until totalOutFrames) {
                                val frameIndex = (i * frameStep) % cachedFrames.size
                                encodeFrame(cachedFrames[frameIndex])
                            }
                            cachedFrames.forEach { runCatching { it.recycle() } }
                        }
                    }.onFailure {
                        Timber.w(it, "Sequential canvas decode failed after $sentFrames frames")
                    }
                }

                if (sentFrames == 0L) {
                    frameDurationUs = 1_000_000L / FALLBACK_FPS
                    val totalOutFramesFallback = (durationMs * FALLBACK_FPS / 1000L).toInt().coerceAtLeast(FALLBACK_FPS)
                    // Pre-decodificar un número pequeño de frames clave (máximo 40) para ciclar en memoria instantáneamente
                    val sampleCount = minOf(40, (fullDurationMs / 100L).toInt().coerceAtLeast(10))
                    val sampleStepUs = (fullDurationMs * 1000L) / sampleCount
                    val cachedFrames = mutableListOf<Bitmap>()
                    for (s in 0 until sampleCount) {
                        val tUs = s * sampleStepUs
                        val f = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (f != null) cachedFrames.add(f)
                    }

                    if (cachedFrames.isNotEmpty()) {
                        for (i in 0 until totalOutFramesFallback) {
                            val frameIndex = (i % cachedFrames.size)
                            encodeFrame(cachedFrames[frameIndex])
                        }
                        cachedFrames.forEach { runCatching { it.recycle() } }
                    }
                }
                drainEncoder(endOfStream = true)

                // ── Muxing de la pista de Audio AAC transcodificada ──
                if (audioTrackIndex >= 0 && audioExtractor != null && audioTrackIdxInFile >= 0) {
                    runCatching {
                        val ext = audioExtractor!!
                        ext.selectTrack(audioTrackIdxInFile)
                        val maxBufSize = audioFormat?.run {
                            if (containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1024 * 1024
                        } ?: (1024 * 1024)

                        val audioBuffer = ByteBuffer.allocateDirect(maxBufSize)
                        val audioBufferInfo = MediaCodec.BufferInfo()

                        while (true) {
                            audioBuffer.clear()
                            val sampleSize = ext.readSampleData(audioBuffer, 0)
                            if (sampleSize < 0) break

                            audioBufferInfo.offset = 0
                            audioBufferInfo.size = sampleSize
                            audioBufferInfo.presentationTimeUs = ext.sampleTime
                            audioBufferInfo.flags = ext.sampleFlags

                            muxer!!.writeSampleData(audioTrackIndex, audioBuffer, audioBufferInfo)
                            ext.advance()
                        }
                    }.onFailure {
                        Timber.w(it, "Audio AAC muxing failed")
                    }
                }

                output
            } finally {
                runCatching { retriever.release() }
                runCatching { audioExtractor?.release() }
                runCatching { encoder?.stop() }
                runCatching { encoder?.release() }
                runCatching { muxer?.stop() }
                runCatching { muxer?.release() }
                tempAacFile?.delete()
            }
        }.onFailure { Timber.e(it, "Canvas video composition failed") }
    }

    /**
     * Transcodifica el segmento de audio indicado a AAC estándar (audio/mp4a-latm)
     * en un contenedor MP4 temporal, para garantizar compatibilidad total.
     */
    private fun transcodeAudioToAac(
        audioFile: File,
        startMs: Long,
        durationMs: Long,
        outputAacFile: File
    ): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(audioFile.absolutePath)
            var trackIdx = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIdx = i
                    inputFormat = fmt
                    break
                }
            }
            if (trackIdx < 0 || inputFormat == null) return false
            extractor.selectTrack(trackIdx)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            val startUs = startMs * 1000L
            val endUs = startUs + (durationMs * 1000L)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            // Si ya es AAC, extraer directamente los paquetes
            if (mime.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)) {
                if (outputAacFile.exists()) outputAacFile.delete()
                muxer = MediaMuxer(outputAacFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val aacTrackIdx = muxer.addTrack(inputFormat)
                muxer.start()

                val buffer = ByteBuffer.allocateDirect(1024 * 1024)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    val sampleTimeUs = extractor.sampleTime
                    if (sampleTimeUs >= endUs) break

                    info.offset = 0
                    info.size = sampleSize
                    info.presentationTimeUs = (sampleTimeUs - startUs).coerceAtLeast(0L)
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(aacTrackIdx, buffer, info)
                    extractor.advance()
                }
                muxer.stop()
                muxer.release()
                return outputAacFile.exists() && outputAacFile.length() > 0
            }

            // Decodificar a PCM y codificar a AAC LC 128kbps
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val outputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            if (outputAacFile.exists()) outputAacFile.delete()
            muxer = MediaMuxer(outputAacFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrackIdx = -1
            var muxerStarted = false

            val decBufferInfo = MediaCodec.BufferInfo()
            val encBufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawEncEOS = false

            while (!sawEncEOS) {
                if (!sawInputEOS) {
                    val inputBufIdx = decoder.dequeueInputBuffer(5000)
                    if (inputBufIdx >= 0) {
                        val inputBuf = decoder.getInputBuffer(inputBufIdx)!!
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        val sampleTime = extractor.sampleTime
                        if (sampleSize < 0 || sampleTime >= endUs) {
                            decoder.queueInputBuffer(inputBufIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            decoder.queueInputBuffer(inputBufIdx, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                var decStatus = decoder.dequeueOutputBuffer(decBufferInfo, 5000)
                while (decStatus >= 0) {
                    val pcmBuf = decoder.getOutputBuffer(decStatus)
                    val isEOS = (decBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (pcmBuf != null && decBufferInfo.size > 0) {
                        val encInputIdx = encoder.dequeueInputBuffer(5000)
                        if (encInputIdx >= 0) {
                            val encInputBuf = encoder.getInputBuffer(encInputIdx)!!
                            encInputBuf.clear()
                            encInputBuf.put(pcmBuf)
                            val presentationUs = (decBufferInfo.presentationTimeUs - startUs).coerceAtLeast(0L)
                            encoder.queueInputBuffer(
                                encInputIdx, 0, decBufferInfo.size, presentationUs,
                                if (isEOS) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            )
                        }
                    } else if (isEOS) {
                        val encInputIdx = encoder.dequeueInputBuffer(5000)
                        if (encInputIdx >= 0) {
                            encoder.queueInputBuffer(encInputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    }
                    decoder.releaseOutputBuffer(decStatus, false)
                    decStatus = decoder.dequeueOutputBuffer(decBufferInfo, 0)
                }

                var encStatus = encoder.dequeueOutputBuffer(encBufferInfo, 5000)
                while (encStatus >= 0) {
                    val encBuf = encoder.getOutputBuffer(encStatus)
                    if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encBufferInfo.size = 0
                    }
                    if (encBufferInfo.size > 0 && muxerStarted) {
                        muxer.writeSampleData(muxerTrackIdx, encBuf!!, encBufferInfo)
                    }
                    if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawEncEOS = true
                    }
                    encoder.releaseOutputBuffer(encStatus, false)
                    encStatus = encoder.dequeueOutputBuffer(encBufferInfo, 0)
                }
                if (encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxerTrackIdx = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
            }

            return outputAacFile.exists() && outputAacFile.length() > 0
        } catch (e: Exception) {
            Timber.w(e, "AAC audio transcoding failed")
            return false
        } finally {
            runCatching { extractor?.release() }
            runCatching { decoder?.stop(); decoder?.release() }
            runCatching { encoder?.stop(); encoder?.release() }
            runCatching { if (muxer != null) { muxer.stop(); muxer.release() } }
        }
    }

    private fun cardOverlay(
        context: Context,
        title: String,
        artist: String,
        bgColor: Int,
        bgGradient: Boolean,
        textColor: Int,
        secondaryColor: Int,
        fontName: String,
    ): Pair<Bitmap, RectF> {
        val w = WIDTH_BRANDING.toFloat()
        val h = HEIGHT_BRANDING.toFloat()
        val bmp = createBitmap(WIDTH_BRANDING, HEIGHT_BRANDING)
        val canvas = Canvas(bmp)
        val padding = w * 0.07f

        val baseTypeface = getTypeface(context, fontName)
        val boldTypeface = Typeface.create(baseTypeface, Typeface.BOLD)

        canvas.drawRect(RectF(0f, 0f, w, h), Paint().apply {
            isAntiAlias = true
            if (bgGradient) {
                shader = LinearGradient(0f, 0f, 0f, h, bgColor, android.graphics.Color.BLACK, Shader.TileMode.CLAMP)
            } else {
                color = bgColor
            }
        })

        val contentWidth = w - padding * 2

        val titlePaint = TextPaint().apply {
            color = textColor; textSize = w * 0.055f
            typeface = boldTypeface; isAntiAlias = true
        }
        val artistPaint = TextPaint().apply {
            color = secondaryColor; textSize = w * 0.042f
            typeface = baseTypeface; isAntiAlias = true
        }
        val brandPaint = TextPaint().apply {
            color = textColor; textSize = w * 0.040f
            typeface = boldTypeface; isAntiAlias = true
        }

        fun singleLine(text: String, paint: TextPaint) =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth.toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(1)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .setEllipsizedWidth(contentWidth.toInt())
                .build()

        val titleLayout = singleLine(title, titlePaint)
        val artistLayout = singleLine(artist, artistPaint)

        val gapTitleArtist = w * 0.020f
        val gapArtistBrand = w * 0.045f
        val brand = "★ Dezzy"
        val brandWidth = brandPaint.measureText(brand)

        val brandBaseline = h - padding - brandPaint.descent()
        val brandTop = brandBaseline + brandPaint.ascent()
        val artistTop = brandTop - gapArtistBrand - artistLayout.height
        val titleTop = artistTop - gapTitleArtist - titleLayout.height

        val videoBottom = titleTop - w * 0.05f
        val videoRect = RectF(padding, padding, padding + contentWidth, videoBottom)

        canvas.withTranslation(padding, titleTop) { titleLayout.draw(this) }
        canvas.withTranslation(padding, artistTop) { artistLayout.draw(this) }
        canvas.drawText(brand, (w - brandWidth) / 2f, brandBaseline, brandPaint)

        return bmp to videoRect
    }

    private fun fastComposePureCanvas(
        context: Context,
        canvasFile: File,
        output: File,
        targetDurationMs: Long,
        audioFile: File?,
        audioStartMs: Long
    ): Boolean {
        var tempAacFile: File? = null
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(canvasFile.absolutePath)
            var videoTrackInSrc = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrackInSrc = i
                    videoFormat = fmt
                    break
                }
            }
            if (videoTrackInSrc < 0 || videoFormat == null) return false
            videoExtractor.selectTrack(videoTrackInSrc)

            if (output.exists()) output.delete()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoTrackInMuxer = muxer.addTrack(videoFormat)

            var audioTrackInMuxer = -1
            if (audioFile != null && audioFile.exists()) {
                val tempFile = File(context.cacheDir, "temp_pure_audio_${System.currentTimeMillis()}.mp4")
                if (transcodeAudioToAac(audioFile, audioStartMs, targetDurationMs, tempFile)) {
                    tempAacFile = tempFile
                    audioExtractor = MediaExtractor()
                    audioExtractor.setDataSource(tempFile.absolutePath)
                    for (i in 0 until audioExtractor.trackCount) {
                        val fmt = audioExtractor.getTrackFormat(i)
                        val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                        if (mime.startsWith("audio/")) {
                            audioTrackInMuxer = muxer.addTrack(fmt)
                            audioExtractor.selectTrack(i)
                            break
                        }
                    }
                }
            }

            muxer.start()

            // ── Bucle ultrarrápido de paquetes de video directo ──
            val durationUs = targetDurationMs * 1000L
            val maxBufSize = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else 2 * 1024 * 1024
            val videoBuf = ByteBuffer.allocateDirect(maxBufSize)
            val info = MediaCodec.BufferInfo()

            var currentOffsetUs = 0L
            var lastSampleTimeUs = 0L

            while (currentOffsetUs < durationUs) {
                videoBuf.clear()
                val sampleSize = videoExtractor.readSampleData(videoBuf, 0)
                if (sampleSize < 0) {
                    videoExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    currentOffsetUs += (lastSampleTimeUs + 33_000L)
                    continue
                }
                val sampleTime = videoExtractor.sampleTime
                lastSampleTimeUs = sampleTime
                val pts = currentOffsetUs + sampleTime
                if (pts >= durationUs) break

                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = pts
                info.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(videoTrackInMuxer, videoBuf, info)
                videoExtractor.advance()
            }

            // ── Escribir muestras de audio AAC ──
            if (audioTrackInMuxer >= 0 && audioExtractor != null) {
                val audioBuf = ByteBuffer.allocateDirect(1024 * 1024)
                val aInfo = MediaCodec.BufferInfo()
                while (true) {
                    audioBuf.clear()
                    val sSize = audioExtractor.readSampleData(audioBuf, 0)
                    if (sSize < 0) break
                    aInfo.offset = 0
                    aInfo.size = sSize
                    aInfo.presentationTimeUs = audioExtractor.sampleTime
                    aInfo.flags = audioExtractor.sampleFlags
                    muxer.writeSampleData(audioTrackInMuxer, audioBuf, aInfo)
                    audioExtractor.advance()
                }
            }

            muxer.stop()
            muxer.release()
            muxer = null

            return output.exists() && output.length() > 0
        } catch (e: Exception) {
            Timber.w(e, "Fast pure canvas composition failed")
            return false
        } finally {
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
            runCatching { muxer?.release() }
            tempAacFile?.delete()
        }
    }
}
