package com.dezzy.music.audio

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor

/**
 * Cadena de procesado del "Motor de audio Hi-Res" (Ajustes > Reproductor).
 *
 * Con la salida float activada, ExoPlayer entrega el audio a los procesadores
 * en PCM float de 32 bits. La cadena por defecto de media3 incluye
 * SilenceSkippingAudioProcessor, que solo acepta PCM de 16 bits y haría fallar
 * la reproducción; esta cadena solo usa procesadores que aceptan float:
 * el ecualizador paramétrico de Dezzy y Sonic (velocidad / tono).
 *
 * "Omitir silencio" no está disponible en este modo.
 */
class HiResAudioProcessorChain : AudioProcessorChain {

    private val sonic = SonicAudioProcessor()
    private val processors = arrayOf<AudioProcessor>(
        DezzyBiquadAudioProcessor(),
        sonic,
    )

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration

    override fun getSkippedOutputFrameCount(): Long = 0L
}
