/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.p2p

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.dezzy.music.MainActivity
import com.dezzy.music.R
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Notificaciones de la red social P2P: mensajes cifrados nuevos y solicitudes de seguimiento.
 * Una notificación por conversación (estilo mensajería), que se borra al abrir el chat.
 */
object P2PNotifications {
    const val ACTION_OPEN_CHAT = "com.dezzy.music.action.OPEN_P2P_CHAT"
    const val ACTION_OPEN_SOCIAL = "com.dezzy.music.action.OPEN_P2P_SOCIAL"
    const val EXTRA_PEER = "p2p_peer"

    private const val CHANNEL_MESSAGES = "p2p_messages"
    private const val CHANNEL_SOCIAL = "p2p_social"
    private const val GROUP_MESSAGES = "com.dezzy.music.P2P_MESSAGES"
    private const val MAX_LINES = 6

    /** Conversación abierta ahora mismo: sus mensajes no avisan. */
    @Volatile
    var activeChatPeer: String? = null

    private data class Line(val text: String, val time: Long)

    /** Últimas líneas sin leer por conversación (sólo en memoria, para el estilo mensajería). */
    private val pending = ConcurrentHashMap<String, MutableList<Line>>()

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_MESSAGES) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MESSAGES, "Mensajes", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Mensajes cifrados de tus amigos"
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_SOCIAL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SOCIAL, "Amigos", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Solicitudes para seguirte"
                },
            )
        }
    }

    private fun notificationId(peer: String, kind: Int): Int = 0x50320000 xor (peer.hashCode() * 31 + kind)

    private fun openIntent(context: Context, action: String, peer: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            putExtra(EXTRA_PEER, peer)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            notificationId(peer, action.hashCode()),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Aviso de un mensaje nuevo de [peer]. */
    fun notifyMessage(context: Context, peer: String, text: String, songTitle: String?, createdAtSec: Long) {
        if (peer == activeChatPeer) return
        if (!canNotify(context)) return
        ensureChannels(context)
        val body = when {
            text.isNotBlank() && songTitle != null -> "$text · $songTitle"
            text.isNotBlank() -> text
            songTitle != null -> "Te compartió «$songTitle»"
            else -> "Mensaje nuevo"
        }
        val lines = pending.getOrPut(peer) { mutableListOf() }
        synchronized(lines) {
            lines += Line(body, createdAtSec * 1000L)
            lines.sortBy { it.time }
            while (lines.size > MAX_LINES) lines.removeAt(0)
        }
        val name = P2PSocial.user(peer).displayName
        val sender = Person.Builder().setName(name).setKey(peer).build()
        val me = Person.Builder().setName("Tú").build()
        val style = NotificationCompat.MessagingStyle(me)
        synchronized(lines) { lines.forEach { style.addMessage(it.text, it.time, sender) } }

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.nav_messages_filled)
            .setStyle(style)
            .setContentTitle(name)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_MESSAGES)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openIntent(context, ACTION_OPEN_CHAT, peer))
            .build()
        post(context, notificationId(peer, 1), notification)
    }

    /** Aviso de que [peer] quiere seguirte (cuenta privada). */
    fun notifyFollowRequest(context: Context, peer: String) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val name = P2PSocial.user(peer).displayName
        val notification = NotificationCompat.Builder(context, CHANNEL_SOCIAL)
            .setSmallIcon(R.drawable.person_add)
            .setContentTitle("$name quiere seguirte")
            .setContentText("Toca para aceptar o rechazar la solicitud.")
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, ACTION_OPEN_SOCIAL, peer))
            .build()
        post(context, notificationId(peer, 2), notification)
    }

    /** Al abrir la conversación: fuera el aviso y las líneas acumuladas. */
    fun clearChat(context: Context, peer: String) {
        pending.remove(peer)
        runCatching { NotificationManagerCompat.from(context).cancel(notificationId(peer, 1)) }
    }

    fun clearAll(context: Context) {
        pending.clear()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.activeNotifications
                .filter { it.notification.group == GROUP_MESSAGES || it.notification.channelId == CHANNEL_SOCIAL }
                .forEach { nm.cancel(it.id) }
        }
    }

    private fun post(context: Context, id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            Timber.w(e, "Sin permiso para notificar")
        }
    }
}
