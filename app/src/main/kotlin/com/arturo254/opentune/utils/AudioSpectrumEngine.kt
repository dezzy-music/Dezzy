/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.utils

import android.media.audiofx.Visualizer
import timber.log.Timber
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Motor de espectro en vivo para el visualizador NCS: captura la FFT del
 * audioSessionId de ExoPlayer con [Visualizer] y la reduce a [BANDS] bandas
 * log-espaciadas, con ataque rápido y decaimiento suave (el "rebote" NCS).
 *
 * Requiere el permiso RECORD_AUDIO concedido ANTES de llamar a [start] —
 * así lo exige Android para leer la salida de audio (igual que Poweramp).
 * El listener de captura corre en un hilo binder: [bands] y [loudness] se
 * leen desde el frame loop de Compose sin lock (el tearing visual de un
 * float a 60fps es imperceptible y evita bloquear el hilo de audio).
 */
class AudioSpectrumEngine {

    companion object {
        const val BANDS = 64
    }

    /** Bandas suavizadas 0..1, de graves (0) a agudos (63). */
    val bands = FloatArray(BANDS)

    /** Sonoridad global suavizada 0..1 (mueve el glow y las partículas). */
    @Volatile
    var loudness = 0f
        private set

    private var visualizer: Visualizer? = null

    /** true si la captura arrancó. false = sin permiso, sesión inválida o equipo sin soporte. */
    fun start(audioSessionId: Int): Boolean = runCatching {
        release()
        visualizer = Visualizer(audioSessionId).apply {
            captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int,
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int,
                    ) {
                        if (fft != null) processFft(fft)
                    }
                },
                Visualizer.getMaxCaptureRate(),
                false,
                true,
            )
            enabled = true
        }
        true
    }.getOrElse {
        Timber.w(it, "No se pudo iniciar el Visualizer (¿permiso RECORD_AUDIO / sesión inválida?)")
        release()
        false
    }

    private fun processFft(fft: ByteArray) {
        // Formato Android: fft[0]=Re(0), fft[1]=Re(N/2), luego pares (Re, Im).
        val bins = fft.size / 2
        if (bins < 2) return

        var total = 0f
        for (b in 0 until BANDS) {
            // Agrupación logarítmica: los graves ocupan pocos bins, los agudos muchos.
            val startBin = (bins - 1.0).pow(b / BANDS.toDouble()).toInt().coerceAtLeast(1)
            val endBin = (bins - 1.0).pow((b + 1) / BANDS.toDouble()).toInt()
                .coerceAtLeast(startBin + 1)
                .coerceAtMost(bins)

            var sum = 0f
            var count = 0
            for (i in startBin until endBin) {
                val re = fft[2 * i].toFloat()
                val im = fft[2 * i + 1].toFloat()
                sum += hypot(re, im)
                count++
            }
            val raw = if (count > 0) (sum / count) / 128f else 0f
            // Compensación de la caída natural de energía en los agudos
            val boosted = (raw * (1f + b * 0.025f)).coerceIn(0f, 1f)

            // Ataque rápido / decaimiento suave
            bands[b] = if (boosted > bands[b]) {
                bands[b] + (boosted - bands[b]) * 0.55f
            } else {
                bands[b] * 0.86f
            }
            total += bands[b]
        }
        loudness = (loudness * 0.82f + (total / BANDS * 3f).coerceIn(0f, 1f) * 0.18f)
    }

    fun release() {
        runCatching {
            visualizer?.enabled = false
            visualizer?.release()
        }
        visualizer = null
        bands.fill(0f)
        loudness = 0f
    }
}
