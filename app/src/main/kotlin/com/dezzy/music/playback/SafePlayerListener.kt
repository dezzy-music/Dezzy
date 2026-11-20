package com.dezzy.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import timber.log.Timber

/**
 * Envuelve un oyente propio del reproductor para que sus excepciones nunca salgan.
 *
 * ExoPlayer avisa a sus oyentes uno tras otro, y una excepción en uno corta la ronda: los
 * que vienen después no se enteran del cambio. El oyente de la MediaSession (el que
 * alimenta la notificación, la pantalla de bloqueo y el Bluetooth) se registra DESPUÉS
 * que el del servicio, así que cualquier fallo en `onMediaItemTransition` u `onEvents`
 * dejaba la notificación congelada en la canción anterior (p. ej. «cargando») aunque ya
 * sonara otra.
 */
class SafePlayerListener(private val delegate: Player.Listener) : Player.Listener {
    private inline fun guard(callback: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Timber.tag("SafePlayerListener").e(e, "$callback falló en ${delegate.javaClass.simpleName}")
        }
    }

    override fun onEvents(player: Player, events: Player.Events) =
        guard("onEvents") { delegate.onEvents(player, events) }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) =
        guard("onTimelineChanged") { delegate.onTimelineChanged(timeline, reason) }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
        guard("onMediaItemTransition") { delegate.onMediaItemTransition(mediaItem, reason) }

    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) =
        guard("onMediaMetadataChanged") { delegate.onMediaMetadataChanged(mediaMetadata) }

    override fun onPlaybackStateChanged(playbackState: Int) =
        guard("onPlaybackStateChanged") { delegate.onPlaybackStateChanged(playbackState) }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
        guard("onPlayWhenReadyChanged") { delegate.onPlayWhenReadyChanged(playWhenReady, reason) }

    override fun onIsPlayingChanged(isPlaying: Boolean) =
        guard("onIsPlayingChanged") { delegate.onIsPlayingChanged(isPlaying) }

    override fun onIsLoadingChanged(isLoading: Boolean) =
        guard("onIsLoadingChanged") { delegate.onIsLoadingChanged(isLoading) }

    override fun onRepeatModeChanged(repeatMode: Int) =
        guard("onRepeatModeChanged") { delegate.onRepeatModeChanged(repeatMode) }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) =
        guard("onShuffleModeEnabledChanged") { delegate.onShuffleModeEnabledChanged(shuffleModeEnabled) }

    override fun onPlayerError(error: PlaybackException) =
        guard("onPlayerError") { delegate.onPlayerError(error) }

    override fun onPlayerErrorChanged(error: PlaybackException?) =
        guard("onPlayerErrorChanged") { delegate.onPlayerErrorChanged(error) }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) = guard("onPositionDiscontinuity") { delegate.onPositionDiscontinuity(oldPosition, newPosition, reason) }
}
