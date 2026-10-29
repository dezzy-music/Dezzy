package com.dezzy.music.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import com.dezzy.music.audio.models.EqBand
import com.dezzy.music.audio.models.FilterType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class DezzyBiquadAudioProcessor : AudioProcessor {

    private var inputAudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat = AudioFormat.NOT_SET
    private var isActive = false
    private var buffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private var bands: List<EqBand> = emptyList()
    private var filters = emptyArray<BiquadFilterState>()

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    init {
        scope.launch {
            DezzyEqManager.bands.collectLatest { newBands ->
                bands = newBands
                recalculateFilters()
            }
        }
        scope.launch {
            DezzyEqManager.isEqEnabled.collectLatest { enabled ->
                isActive = enabled
            }
        }
    }

    private class BiquadFilterState {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0
        var a1 = 0.0; var a2 = 0.0
        var x1L = 0.0; var x2L = 0.0
        var y1L = 0.0; var y2L = 0.0
        var x1R = 0.0; var x2R = 0.0
        var y1R = 0.0; var y2R = 0.0
    }

    private fun recalculateFilters() {
        val newFilters = Array(bands.size) { BiquadFilterState() }
        val sampleRate = if (inputAudioFormat.sampleRate != AudioFormat.NOT_SET.sampleRate) {
            inputAudioFormat.sampleRate.toDouble()
        } else {
            44100.0
        }

        for (i in bands.indices) {
            val band = bands[i]
            val filter = newFilters[i]

            val w0 = 2.0 * Math.PI * band.frequencyHz / sampleRate
            val alpha = sin(w0) / (2.0 * band.qFactor)
            val a = 10.0.pow(band.gainDb / 40.0)

            val b0: Double; val b1: Double; val b2: Double
            val a0: Double; val a1: Double; val a2: Double

            when (band.type) {
                FilterType.PEAKING -> {
                    b0 = 1.0 + alpha * a
                    b1 = -2.0 * cos(w0)
                    b2 = 1.0 - alpha * a
                    a0 = 1.0 + alpha / a
                    a1 = -2.0 * cos(w0)
                    a2 = 1.0 - alpha / a
                }
                FilterType.LOW_SHELF -> {
                    b0 = a * ((a + 1.0) - (a - 1.0) * cos(w0) + 2.0 * sqrt(a) * alpha)
                    b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cos(w0))
                    b2 = a * ((a + 1.0) - (a - 1.0) * cos(w0) - 2.0 * sqrt(a) * alpha)
                    a0 = (a + 1.0) + (a - 1.0) * cos(w0) + 2.0 * sqrt(a) * alpha
                    a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cos(w0))
                    a2 = (a + 1.0) + (a - 1.0) * cos(w0) - 2.0 * sqrt(a) * alpha
                }
                FilterType.HIGH_SHELF -> {
                    b0 = a * ((a + 1.0) + (a - 1.0) * cos(w0) + 2.0 * sqrt(a) * alpha)
                    b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cos(w0))
                    b2 = a * ((a + 1.0) + (a - 1.0) * cos(w0) - 2.0 * sqrt(a) * alpha)
                    a0 = (a + 1.0) - (a - 1.0) * cos(w0) + 2.0 * sqrt(a) * alpha
                    a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cos(w0))
                    a2 = (a + 1.0) - (a - 1.0) * cos(w0) - 2.0 * sqrt(a) * alpha
                }
                else -> { b0 = 1.0; a0 = 1.0; b1=0.0; b2=0.0; a1=0.0; a2=0.0 }
            }

            filter.b0 = b0 / a0
            filter.b1 = b1 / a0
            filter.b2 = b2 / a0
            filter.a1 = a1 / a0
            filter.a2 = a2 / a0

            // Keep previous state to avoid pops
            if (i < filters.size) {
                filter.x1L = filters[i].x1L; filter.x2L = filters[i].x2L
                filter.y1L = filters[i].y1L; filter.y2L = filters[i].y2L
                filter.x1R = filters[i].x1R; filter.x2R = filters[i].x2R
                filter.y1R = filters[i].y1R; filter.y2R = filters[i].y2R
            }
        }
        filters = newFilters
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_FLOAT &&
            inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat
        recalculateFilters()
        return outputAudioFormat
    }

    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) {
            return
        }

        if (buffer.capacity() < remaining) {
            buffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }

        if (filters.isEmpty() || !isActive) {
            // Bypass mode
            buffer.put(inputBuffer)
            buffer.flip()
            outputBuffer = buffer
            return
        }

        val channels = inputAudioFormat.channelCount
        val currentFilters = filters 

        if (inputAudioFormat.encoding == androidx.media3.common.C.ENCODING_PCM_FLOAT) {
            val floats = remaining / 4
            val inFloatBuffer = inputBuffer.asFloatBuffer()
            val outFloatBuffer = buffer.asFloatBuffer()

            for (i in 0 until floats step channels) {
                var sampleL = inFloatBuffer.get(i).toDouble()
                var sampleR = if (channels > 1) inFloatBuffer.get(i + 1).toDouble() else sampleL

                for (f in currentFilters) {
                    val outL = f.b0 * sampleL + f.b1 * f.x1L + f.b2 * f.x2L - f.a1 * f.y1L - f.a2 * f.y2L
                    f.x2L = f.x1L; f.x1L = sampleL
                    f.y2L = f.y1L; f.y1L = outL
                    sampleL = outL

                    if (channels > 1) {
                        val outR = f.b0 * sampleR + f.b1 * f.x1R + f.b2 * f.x2R - f.a1 * f.y1R - f.a2 * f.y2R
                        f.x2R = f.x1R; f.x1R = sampleR
                        f.y2R = f.y1R; f.y1R = outR
                        sampleR = outR
                    }
                }

                outFloatBuffer.put(i, sampleL.toFloat())
                if (channels > 1) {
                    outFloatBuffer.put(i + 1, sampleR.toFloat())
                }
            }
        } else {
            // 16-bit PCM Fallback
            val shorts = remaining / 2
            val inShortBuffer = inputBuffer.asShortBuffer()
            val outShortBuffer = buffer.asShortBuffer()

            for (i in 0 until shorts step channels) {
                var sampleL = inShortBuffer.get(i).toDouble() / 32768.0
                var sampleR = if (channels > 1) inShortBuffer.get(i + 1).toDouble() / 32768.0 else sampleL

                for (f in currentFilters) {
                    val outL = f.b0 * sampleL + f.b1 * f.x1L + f.b2 * f.x2L - f.a1 * f.y1L - f.a2 * f.y2L
                    f.x2L = f.x1L; f.x1L = sampleL
                    f.y2L = f.y1L; f.y1L = outL
                    sampleL = outL

                    if (channels > 1) {
                        val outR = f.b0 * sampleR + f.b1 * f.x1R + f.b2 * f.x2R - f.a1 * f.y1R - f.a2 * f.y2R
                        f.x2R = f.x1R; f.x1R = sampleR
                        f.y2R = f.y1R; f.y1R = outR
                        sampleR = outR
                    }
                }

                val outShortL = (sampleL * 32768.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                outShortBuffer.put(i, outShortL)
                if (channels > 1) {
                    val outShortR = (sampleR * 32768.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    outShortBuffer.put(i + 1, outShortR)
                }
            }
        }

        inputBuffer.position(inputBuffer.position() + remaining)
        buffer.limit(remaining)
        buffer.position(0)
        outputBuffer = buffer
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return output
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        for (f in filters) {
            f.x1L = 0.0; f.x2L = 0.0; f.y1L = 0.0; f.y2L = 0.0
            f.x1R = 0.0; f.x2R = 0.0; f.y1R = 0.0; f.y2R = 0.0
        }
    }

    override fun reset() {
        flush()
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
    }
}
