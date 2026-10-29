package com.dezzy.music.listentogether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class ListenTogetherActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra(ListenTogetherClient.EXTRA_NOTIFICATION_ID, 0)

        // Cancel the notification immediately so the user sees visual feedback
        NotificationManagerCompat.from(context).cancel(notifId)

        val client = ListenTogetherClient.getInstance()
        if (client == null) {
            Timber.tag("ListenTogether").w("ActionReceiver: client instance is null (app may have been killed)")
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(
                    context,
                    "Session expired. Please reopen the app and reconnect.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        when (intent.action) {
            ListenTogetherClient.ACTION_APPROVE_JOIN -> {
                val userId = intent.getStringExtra(ListenTogetherClient.EXTRA_USER_ID)
                if (userId == null) {
                    Timber.tag("ListenTogether").e("ActionReceiver: APPROVE_JOIN missing user_id extra")
                    return
                }
                Timber.tag("ListenTogether").i("ActionReceiver: Approving join for user $userId")
                CoroutineScope(Dispatchers.IO).launch {
                    client.approveJoin(userId)
                }
            }
            ListenTogetherClient.ACTION_REJECT_JOIN -> {
                val userId = intent.getStringExtra(ListenTogetherClient.EXTRA_USER_ID)
                if (userId == null) {
                    Timber.tag("ListenTogether").e("ActionReceiver: REJECT_JOIN missing user_id extra")
                    return
                }
                Timber.tag("ListenTogether").i("ActionReceiver: Rejecting join for user $userId")
                CoroutineScope(Dispatchers.IO).launch {
                    client.rejectJoin(userId, null)
                }
            }
            ListenTogetherClient.ACTION_APPROVE_SUGGESTION -> {
                val suggestionId = intent.getStringExtra(ListenTogetherClient.EXTRA_SUGGESTION_ID)
                if (suggestionId == null) {
                    Timber.tag("ListenTogether").e("ActionReceiver: APPROVE_SUGGESTION missing suggestion_id extra")
                    return
                }
                Timber.tag("ListenTogether").i("ActionReceiver: Approving suggestion $suggestionId")
                CoroutineScope(Dispatchers.IO).launch {
                    client.approveSuggestion(suggestionId)
                }
            }
            ListenTogetherClient.ACTION_REJECT_SUGGESTION -> {
                val suggestionId = intent.getStringExtra(ListenTogetherClient.EXTRA_SUGGESTION_ID)
                if (suggestionId == null) {
                    Timber.tag("ListenTogether").e("ActionReceiver: REJECT_SUGGESTION missing suggestion_id extra")
                    return
                }
                Timber.tag("ListenTogether").i("ActionReceiver: Rejecting suggestion $suggestionId")
                CoroutineScope(Dispatchers.IO).launch {
                    client.rejectSuggestion(suggestionId, null)
                }
            }
            else -> {
                Timber.tag("ListenTogether").w("ActionReceiver: Unknown action ${intent.action}")
            }
        }
    }
}
