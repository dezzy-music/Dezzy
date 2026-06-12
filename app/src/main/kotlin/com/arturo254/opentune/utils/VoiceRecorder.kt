package com.arturo254.opentune.utils

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import timber.log.Timber
import java.io.File

/**
 * Utility class to record audio for AI processing.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(): File? {
        try {
            outputFile = File(context.cacheDir, "ai_voice_input.m4a")
            if (outputFile?.exists() == true) {
                outputFile?.delete()
            }

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(outputFile?.absolutePath)
                prepare()
                start()
            }
            Timber.d("Voice recording started")
            return outputFile
        } catch (e: Exception) {
            Timber.e(e, "Failed to start voice recording")
            return null
        }
    }

    fun stopRecording() {
        try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            Timber.d("Voice recording stopped")
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop voice recording")
        }
    }

    fun release() {
        try {
            recorder?.release()
            recorder = null
        } catch (e: Exception) {
            Timber.e(e, "Failed to release voice recorder")
        }
    }
}
