/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.p2p

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Revisa el buzón cifrado cada ~15 min aunque la app esté cerrada, para que los mensajes
 * nuevos lleguen como notificación. Con la app abierta el buzón ya va en vivo (relés) y
 * esto sólo confirma que no falte nada; los duplicados se descartan en la base de datos.
 */
class P2PInboxWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        P2PSocial.init(applicationContext)
        withTimeoutOrNull(10_000) { P2PIdentity.loaded.first { it } } ?: return Result.success()
        if (P2PSocial.keys.value == null) return Result.success()
        withTimeoutOrNull(45_000) { P2PMessaging.syncNow() }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "p2p_inbox_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<P2PInboxWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
