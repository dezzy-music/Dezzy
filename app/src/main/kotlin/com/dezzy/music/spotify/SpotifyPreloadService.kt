/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Foreground service that keeps the process alive while [SpotifyPreloadManager]
 * runs a preload, showing a progress notification with a cancel action. It does
 * no work itself — it just observes the manager's state and stops on terminal states.
 */

package com.dezzy.music.spotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dezzy.music.R
import com.dezzy.music.viewmodels.SpotifyPreloadState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SpotifyPreloadService : Service() {

    @Inject
    lateinit var preloadManager: SpotifyPreloadManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must enter the foreground promptly after startForegroundService(), including
        // for the cancel action (which also arrives here as a start command).
        startForeground(NOTIFICATION_ID, buildNotification(preloadManager.state.value))

        if (intent?.action == ACTION_CANCEL) {
            preloadManager.cancel()
            // The state observer below handles teardown via the Cancelled terminal state.
        }

        if (!observing) {
            observing = true
            scope.launch {
                // StateFlow conflates by design; the delay below caps notification updates
                // at ~1/s no matter how fast Converting ticks — notification spam throttles
                // on some OEMs.
                preloadManager.state.collect { state ->
                    when (state) {
                        is SpotifyPreloadState.Completed,
                        is SpotifyPreloadState.Cancelled,
                        is SpotifyPreloadState.Error,
                        is SpotifyPreloadState.Idle,
                        is SpotifyPreloadState.SelectingPlaylists,
                        -> {
                            postTerminalNotification(state)
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                            return@collect
                        }
                        else -> {
                            notificationManager().notify(NOTIFICATION_ID, buildNotification(state))
                            delay(1000)
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(state: SpotifyPreloadState): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, SpotifyPreloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.downloading)
            .setContentTitle("Precarga de Spotify")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(R.drawable.close, "Cancelar", cancelIntent)

        when (state) {
            is SpotifyPreloadState.Converting -> {
                builder.setContentText("${state.current} / ${state.total} canciones")
                builder.setProgress(state.total, state.current, false)
            }
            is SpotifyPreloadState.Counting -> {
                builder.setContentText("${state.total} canciones encontradas, preparando…")
                builder.setProgress(0, 0, true)
            }
            else -> {
                builder.setContentText("Buscando tus playlists y canciones…")
                builder.setProgress(0, 0, true)
            }
        }
        return builder.build()
    }

    /** One-shot, dismissible summary when the job ends in the background. */
    private fun postTerminalNotification(state: SpotifyPreloadState) {
        val text = when (state) {
            is SpotifyPreloadState.Completed -> "Precarga completada"
            is SpotifyPreloadState.Cancelled -> "Precarga cancelada"
            is SpotifyPreloadState.Error -> "Error en la precarga: ${state.message.take(120)}"
            else -> return // Idle / SelectingPlaylists: nothing worth notifying
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (state is SpotifyPreloadState.Error) R.drawable.error else R.drawable.check)
            .setContentTitle("Precarga de Spotify")
            .setContentText(text)
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        notificationManager().notify(TERMINAL_NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Precarga de Spotify",
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "spotify_preload"
        const val NOTIFICATION_ID = 4210
        const val TERMINAL_NOTIFICATION_ID = 4211
        const val ACTION_CANCEL = "com.dezzy.music.spotify.PRELOAD_CANCEL"
    }
}
