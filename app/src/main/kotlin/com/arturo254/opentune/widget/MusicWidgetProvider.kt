/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.arturo254.opentune.MainActivity
import com.arturo254.opentune.R

/**
 * Widget de pantalla de inicio con la canción en curso y los controles básicos.
 *
 * Usa RemoteViews clásico en vez de Glance para no añadir dependencias: el contenido
 * es estático (carátula + dos textos + tres botones) y no justifica el peso extra.
 *
 * El servicio empuja el estado con [update]; los botones mandan un broadcast que
 * `MusicService` ya escucha (el mismo canal que usa la burbuja flotante).
 */
class MusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Al añadir el widget aún no hay estado del servicio: se pinta el placeholder
        // y el siguiente evento de reproducción lo completa.
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context, null, null, false, null))
        }
    }

    companion object {
        const val ACTION_COMMAND = "com.arturo254.opentune.widget.COMMAND"
        const val EXTRA_COMMAND = "command"

        /** Acción con la que el botón de micro abre la app directo al reconocimiento. */
        const val ACTION_RECOGNIZE = "com.arturo254.opentune.widget.RECOGNIZE"

        /** Redibuja todos los widgets con el estado actual de reproducción. */
        fun update(
            context: Context,
            title: String?,
            artist: String?,
            isPlaying: Boolean,
            artwork: Bitmap?,
        ) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, MusicWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val views = buildViews(context, title, artist, isPlaying, artwork)
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        private fun buildViews(
            context: Context,
            title: String?,
            artist: String?,
            isPlaying: Boolean,
            artwork: Bitmap?,
        ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_music).apply {
            setTextViewText(
                R.id.widget_title,
                title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name),
            )
            setTextViewText(R.id.widget_artist, artist.orEmpty())
            setImageViewResource(
                R.id.widget_play_pause,
                if (isPlaying) R.drawable.pause else R.drawable.play,
            )
            if (artwork != null) {
                setImageViewBitmap(R.id.widget_artwork, artwork)
            } else {
                setImageViewResource(R.id.widget_artwork, R.drawable.music_note)
            }

            // Tocar la tarjeta abre la app
            setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            // El micro abre la app directo al reconocimiento y lo arranca solo.
            setOnClickPendingIntent(
                R.id.widget_recognize,
                PendingIntent.getActivity(
                    context,
                    4,
                    Intent(context, MainActivity::class.java).apply {
                        action = ACTION_RECOGNIZE
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            setOnClickPendingIntent(R.id.widget_previous, commandIntent(context, "PREVIOUS", 1))
            setOnClickPendingIntent(R.id.widget_play_pause, commandIntent(context, "PLAY_PAUSE", 2))
            setOnClickPendingIntent(R.id.widget_next, commandIntent(context, "NEXT", 3))
        }

        private fun commandIntent(context: Context, command: String, requestCode: Int): PendingIntent {
            val intent = Intent(ACTION_COMMAND).apply {
                putExtra(EXTRA_COMMAND, command)
                setPackage(context.packageName)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
