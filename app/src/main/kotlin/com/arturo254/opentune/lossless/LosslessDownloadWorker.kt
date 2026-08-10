/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.lossless

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Ejecuta una descarga lossless en segundo plano, sobreviviendo a que la app se
 * cierre y con reintentos gestionados por WorkManager. No usa @HiltWorker (el
 * proyecto no tiene HiltWorkerFactory); obtiene el [LosslessDownloadManager] vía
 * un Hilt EntryPoint, igual de válido y sin tocar la clase Application.
 *
 * El progreso se refleja en el StateFlow del manager (singleton compartido en el
 * mismo proceso), así que la UI lo ve sin usar setProgress.
 */
class LosslessDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val provider = LosslessProvider.fromId(inputData.getString(KEY_PROVIDER))
            ?: return Result.failure()
        val trackId = inputData.getString(KEY_TRACK_ID) ?: return Result.failure()
        val quality = provider.qualities.firstOrNull { it.id == inputData.getString(KEY_QUALITY) }
            ?: provider.qualities.first()

        val track = LosslessTrack(
            providerTrackId = trackId,
            provider = provider,
            title = inputData.getString(KEY_TITLE).orEmpty(),
            artistNames = inputData.getStringArray(KEY_ARTISTS)?.toList().orEmpty(),
            albumName = inputData.getString(KEY_ALBUM),
            durationSec = inputData.getInt(KEY_DURATION, -1),
            isrc = inputData.getString(KEY_ISRC),
            thumbnailUrl = inputData.getString(KEY_THUMB),
            explicit = inputData.getBoolean(KEY_EXPLICIT, false),
        )

        val manager = EntryPointAccessors
            .fromApplication(applicationContext, LosslessEntryPoint::class.java)
            .losslessDownloadManager()

        val result = manager.download(track, quality)
        return when {
            result.isSuccess -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.failure()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface LosslessEntryPoint {
        fun losslessDownloadManager(): LosslessDownloadManager
    }

    companion object {
        const val WORK_TAG = "lossless_download"
        private const val MAX_ATTEMPTS = 3

        private const val KEY_PROVIDER = "provider"
        private const val KEY_TRACK_ID = "trackId"
        private const val KEY_QUALITY = "quality"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTISTS = "artists"
        private const val KEY_ALBUM = "album"
        private const val KEY_DURATION = "duration"
        private const val KEY_ISRC = "isrc"
        private const val KEY_THUMB = "thumb"
        private const val KEY_EXPLICIT = "explicit"

        /** Nombre único del trabajo, para no duplicar la descarga de una misma pista. */
        fun uniqueName(track: LosslessTrack): String =
            "$WORK_TAG:${track.provider.id}:${track.providerTrackId}"

        fun dataFor(track: LosslessTrack, quality: LosslessQuality): Data =
            Data.Builder()
                .putString(KEY_PROVIDER, track.provider.id)
                .putString(KEY_TRACK_ID, track.providerTrackId)
                .putString(KEY_QUALITY, quality.id)
                .putString(KEY_TITLE, track.title)
                .putStringArray(KEY_ARTISTS, track.artistNames.toTypedArray())
                .putString(KEY_ALBUM, track.albumName)
                .putInt(KEY_DURATION, track.durationSec)
                .putString(KEY_ISRC, track.isrc)
                .putString(KEY_THUMB, track.thumbnailUrl)
                .putBoolean(KEY_EXPLICIT, track.explicit)
                .build()
    }
}
