/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.dezzy.music.MainActivity
import com.dezzy.music.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import timber.log.Timber

@AndroidEntryPoint
class ExoDownloadService : DownloadService(
    NOTIFICATION_ID,
    1000L,
    CHANNEL_ID,
    R.string.downloading,
    0
) {
    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Volatile private var isMassCancelling = false
    private val notificationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val debouncedNotificationRunnable = Runnable { updateNotification() }

    private fun debouncedUpdateNotification() {
        notificationHandler.removeCallbacks(debouncedNotificationRunnable)
        notificationHandler.postDelayed(debouncedNotificationRunnable, 500L)
    }

    private val notificationUpdateListener = object : DownloadManager.Listener {
        override fun onDownloadsPausedChanged(downloadManager: DownloadManager, downloadsPaused: Boolean) {
            if (!isMassCancelling) debouncedUpdateNotification()
        }

        override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
            if (!isMassCancelling) debouncedUpdateNotification()
        }

        override fun onIdle(downloadManager: DownloadManager) {
            isMassCancelling = false
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        downloadManager.addListener(notificationUpdateListener)
    }

    override fun onDestroy() {
        downloadManager.removeListener(notificationUpdateListener)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            REMOVE_ALL_PENDING_DOWNLOADS -> {
                isMassCancelling = true
                downloadManager.removeAllDownloads()
            }
            REMOVE_DOWNLOAD -> {
                intent.getStringExtra(EXTRA_DOWNLOAD_ID)?.let { id ->
                    downloadManager.removeDownload(id)
                }
            }
            PAUSE_DOWNLOADS -> {
                downloadManager.pauseDownloads()
                updateNotification()
            }
            RESUME_DOWNLOADS -> {
                downloadManager.resumeDownloads()
                updateNotification()
            }
        }
        return try {
            super.onStartCommand(intent, flags, startId)
        } catch (e: IllegalStateException) {
            // Android 12+ puede negar el primer plano (p. ej. ForegroundServiceStartNotAllowedException
            // cuando el sistema reinicia el servicio en segundo plano, o porque en Android 15 ya se
            // gastaron las 6 h diarias de «dataSync»). Las descargas siguen en cola en el
            // DownloadManager y se reanudan la próxima vez que se abra la app; no hay que tumbarla.
            Timber.tag(TAG).w(e, "No se pudo pasar a primer plano; se detiene el servicio")
            stopSelf()
            START_NOT_STICKY
        }
    }

    /**
     * Android 15: el sistema avisa aquí cuando se agota el tiempo de «dataSync». Hay que salir
     * del primer plano en unos segundos o el sistema mata la app.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Timber.tag(TAG).w("Tiempo de primer plano agotado (tipo %d); se detiene el servicio", fgsType)
        runCatching { super.onTimeout(startId, fgsType) }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun getDownloadManager() = downloadUtil.downloadManager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        val notificationHelper = downloadUtil.downloadNotificationHelper

        val activeDownload = downloads.find { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_RESTARTING }
            ?: downloads.firstOrNull()

        val title = activeDownload?.let {
            val songName = Util.fromUtf8Bytes(it.request.data)
            if (downloads.size > 1) {
                "$songName (+${downloads.size - 1})"
            } else {
                songName
            }
        } ?: resources.getString(R.string.downloading)

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_DOWNLOAD_QUEUE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder.recoverBuilder(
            this, notificationHelper.buildProgressNotification(
                this,
                R.drawable.downloading,
                contentIntent,
                title,
                downloads,
                notMetRequirements
            )
        )

        // 1. Pause/Resume Button
        val isPaused = downloadManager.downloadsPaused || downloads.all { it.state == Download.STATE_QUEUED || it.state == Download.STATE_RESTARTING }
        val pauseResumeAction = if (isPaused) {
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.play),
                getString(R.string.exo_download_resume),
                PendingIntent.getService(
                    this, 1,
                    Intent(this, ExoDownloadService::class.java).setAction(RESUME_DOWNLOADS),
                    PendingIntent.FLAG_IMMUTABLE
                )
            ).build()
        } else {
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.pause),
                getString(R.string.exo_download_pause),
                PendingIntent.getService(
                    this, 2,
                    Intent(this, ExoDownloadService::class.java).setAction(PAUSE_DOWNLOADS),
                    PendingIntent.FLAG_IMMUTABLE
                )
            ).build()
        }

        // 2. Cancel Button (Current/First)
        val cancelAction = activeDownload?.let { download ->
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.close),
                getString(R.string.action_cancel),
                PendingIntent.getService(
                    this, 3,
                    Intent(this, ExoDownloadService::class.java)
                        .setAction(REMOVE_DOWNLOAD)
                        .putExtra(EXTRA_DOWNLOAD_ID, download.request.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            ).build()
        }

        // 3. Cancel All Button
        val cancelAllAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.close),
            getString(R.string.action_cancel_all),
            PendingIntent.getService(
                this, 4,
                Intent(this, ExoDownloadService::class.java).setAction(REMOVE_ALL_PENDING_DOWNLOADS),
                PendingIntent.FLAG_IMMUTABLE
            )
        ).build()

        builder.setActions(pauseResumeAction)
        cancelAction?.let { builder.addAction(it) }
        builder.addAction(cancelAllAction)

        return builder.build()
    }

    private fun updateNotification() {
        val downloads = downloadManager.currentDownloads
        if (downloads.isNotEmpty()) {
            val notification = getForegroundNotification(downloads.toMutableList(), 0)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e: IllegalStateException) {
                // Sin permiso para primer plano (límite de Android 15 o app en segundo plano):
                // se actualiza sólo la notificación en vez de romper la app.
                Timber.tag(TAG).w(e, "startForeground denegado; sólo se actualiza la notificación")
                runCatching { NotificationUtil.setNotification(this, NOTIFICATION_ID, notification) }
            }
        }
    }

    /**
     * This helper will outlive the lifespan of a single instance of [ExoDownloadService]
     */
    class TerminalStateNotificationHelper(
        private val context: Context,
        private val notificationHelper: DownloadNotificationHelper,
        private var nextNotificationId: Int,
    ) : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            if (download.state == Download.STATE_FAILED) {
                val notification = notificationHelper.buildDownloadFailedNotification(
                    context,
                    R.drawable.error,
                    null,
                    Util.fromUtf8Bytes(download.request.data)
                )
                NotificationUtil.setNotification(context, nextNotificationId++, notification)
            }
        }
    }

    companion object {
        private const val TAG = "ExoDownloadService"
        const val CHANNEL_ID = "download"
        const val NOTIFICATION_ID = 1
        const val JOB_ID = 1
        const val REMOVE_ALL_PENDING_DOWNLOADS = "REMOVE_ALL_PENDING_DOWNLOADS"
        const val REMOVE_DOWNLOAD = "REMOVE_DOWNLOAD"
        const val PAUSE_DOWNLOADS = "PAUSE_DOWNLOADS"
        const val RESUME_DOWNLOADS = "RESUME_DOWNLOADS"
        const val EXTRA_DOWNLOAD_ID = "EXTRA_DOWNLOAD_ID"
        const val ACTION_DOWNLOAD_QUEUE = "com.dezzy.music.action.DOWNLOAD_QUEUE"
    }
}
