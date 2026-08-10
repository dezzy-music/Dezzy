/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.playback.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/**
 * Registered via the `com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME`
 * meta-data in AndroidManifest.xml. The App ID below is DezzyMusic's own Cast Developer
 * Console entry (App ID 2412673B, Custom Receiver at dezzymusic-cast-receiver.vercel.app) —
 * not a placeholder or the shared Default Media Receiver.
 */
class CastOptionsProvider : OptionsProvider {

    companion object {
        const val APP_ID = "2412673B"
    }

    override fun getCastOptions(context: Context): CastOptions {
        // No Cast-SDK notification/media-session: the local MusicService keeps driving its
        // media3 notification (tap-to-open, prev/next, play/pause all work and mirror to the
        // receiver). The SDK's own notification only offered toggle/stop and hijacked taps.
        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(null)
            .setMediaSessionEnabled(false)
            .build()

        return CastOptions.Builder()
            .setReceiverApplicationId(APP_ID)
            .setCastMediaOptions(mediaOptions)
            .setResumeSavedSession(true)
            .setEnableReconnectionService(true)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
