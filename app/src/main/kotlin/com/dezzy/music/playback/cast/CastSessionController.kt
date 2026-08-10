/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.playback.cast

import android.content.Context
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.framework.CastContext
import com.dezzy.music.models.MediaMetadata
import java.util.concurrent.ConcurrentHashMap
import com.dezzy.music.ui.utils.highRes
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Owns the Cast (Chromecast) side of playback: session lifecycle, converting the currently
 * playing song into a Cast-compatible [MediaItem] (a real HTTP(S) stream URL + remote artwork,
 * since the receiver runs on the TV/speaker and cannot reach this app's local cache), and
 * mirroring transport commands in both directions with the local ExoPlayer in [MusicService].
 *
 * Deliberately NOT a swap-in replacement for the local player: MusicService's ExoPlayer keeps
 * driving every existing feature (crossfade, equalizer, offload, together sessions...) exactly
 * as before. While connected, the local player is muted (see MusicService's volume pipeline) and
 * this controller becomes the thing users actually hear.
 */
class CastSessionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val resolveStream: suspend (mediaId: String) -> ResolvedStream?,
    private val resolveLyrics: suspend (mediaId: String) -> String? = { null },
    private val resolveUiPrefs: suspend () -> String? = { null },
    /**
     * Two dominant artwork colors as a JSON array (`[[r,g,b],[r,g,b]]`) the receiver uses to
     * paint its palette gradient. Computed on the phone because the web receiver frequently
     * can't read the artwork's pixels itself (cross-origin canvas taint), which otherwise
     * leaves those tracks on the generic fallback gradient.
     */
    private val resolvePalette: suspend (thumbnailUrl: String?) -> String? = { null },
) {
    data class ResolvedStream(
        val url: String,
        val mimeType: String?,
    )

    companion object {
        /** customData key the web receiver reads to show lyrics without hitting LRCLIB. */
        private const val CUSTOM_DATA_LYRICS = "dezzyLyrics"

        /** customData key with the phone's appearance prefs (lyrics align/style, bg, slider). */
        private const val CUSTOM_DATA_PREFS = "dezzyPrefs"

        /** customData key with the phone-extracted artwork palette (`[[r,g,b],[r,g,b]]`). */
        private const val CUSTOM_DATA_PALETTE = "dezzyPalette"

        /** customData key with the song's canvas video URL (https): the receiver swaps the
         *  static artwork for this looping video, fullscreen. */
        private const val CUSTOM_DATA_CANVAS = "dezzyCanvas"
    }

    /** Canvas video URL staged per mediaId, same lifecycle as [stagedLyrics]. */
    private val stagedCanvas = ConcurrentHashMap<String, String>()

    /**
     * Lyrics staged per mediaId right before load; the converter runs synchronously inside
     * CastPlayer so the suspend DB lookup has to happen ahead of time in [loadCurrent].
     */
    private val stagedLyrics = ConcurrentHashMap<String, String>()

    /** Artwork palette JSON staged per mediaId, same lifecycle as [stagedLyrics]. */
    private val stagedPalette = ConcurrentHashMap<String, String>()

    /** Latest appearance-prefs JSON, staged the same way as [stagedLyrics]. */
    @Volatile
    private var stagedUiPrefsJson: String? = null

    private inner class LyricsInjectingConverter : MediaItemConverter {
        private val delegate = DefaultMediaItemConverter()

        override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
            val queueItem = delegate.toMediaQueueItem(mediaItem)
            val lyrics = stagedLyrics[mediaItem.mediaId]
            // DefaultMediaItemConverter always populates customData (it round-trips the
            // MediaItem through it), so we can just append our keys to the same object.
            val data = queueItem.media?.customData
            if (data != null) {
                if (!lyrics.isNullOrBlank()) {
                    runCatching { data.put(CUSTOM_DATA_LYRICS, lyrics) }
                }
                stagedPalette[mediaItem.mediaId]?.let { paletteJson ->
                    runCatching { data.put(CUSTOM_DATA_PALETTE, org.json.JSONArray(paletteJson)) }
                }
                stagedUiPrefsJson?.let { prefsJson ->
                    runCatching { data.put(CUSTOM_DATA_PREFS, org.json.JSONObject(prefsJson)) }
                }
                stagedCanvas[mediaItem.mediaId]?.let { canvasUrl ->
                    runCatching { data.put(CUSTOM_DATA_CANVAS, canvasUrl) }
                }
            }
            return queueItem
        }

        override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem =
            delegate.toMediaItem(mediaQueueItem)
    }

    private var castContext: CastContext? = null
    private var castPlayer: CastPlayer? = null
    private var initialized = false

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    /** Set while we are applying a remote (Cast-originated) change, to avoid echoing it back. */
    @Volatile
    private var applyingRemoteChange = false

    /** The mediaId currently loaded on the receiver, so we only reload on an actual song change. */
    @Volatile
    private var loadedMediaId: String? = null

    var onRemotePlayWhenReadyChanged: ((Boolean) -> Unit)? = null

    private val sessionAvailabilityListener =
        object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() {
                loadedMediaId = null
                _isConnected.value = true
                _deviceName.value = runCatching {
                    castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName
                }.getOrNull()
            }

            override fun onCastSessionUnavailable() {
                _isConnected.value = false
                _deviceName.value = null
                loadedMediaId = null
            }
        }

    private val remoteStateListener =
        object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (applyingRemoteChange) return
                onRemotePlayWhenReadyChanged?.invoke(playWhenReady)
            }
        }

    /** Cheap and safe to call repeatedly; only does real work the first time. */
    fun initialize() {
        if (initialized) return
        initialized = true
        runCatching {
            CastContext.getSharedInstance(context, MoreExecutors.directExecutor())
                .addOnSuccessListener { ctx ->
                    castContext = ctx
                    castPlayer =
                        CastPlayer(ctx, LyricsInjectingConverter()).apply {
                            setSessionAvailabilityListener(sessionAvailabilityListener)
                            addListener(remoteStateListener)
                        }
                    // A session may already be active (e.g. reconnected) by the time we attach.
                    if (castPlayer?.isCastSessionAvailable == true) {
                        sessionAvailabilityListener.onCastSessionAvailable()
                    }
                }
                .addOnFailureListener { e ->
                    Timber.tag("Cast").w(e, "Google Cast unavailable on this device")
                }
        }.onFailure { e -> Timber.tag("Cast").w(e, "Cast init failed") }
    }

    /** Loads (or reloads, on a genuine song change) the current track onto the receiver. */
    fun loadCurrent(
        mediaMetadata: MediaMetadata,
        positionMs: Long,
        playWhenReady: Boolean,
    ) {
        val player = castPlayer ?: return
        if (!_isConnected.value) return
        scope.launch(Dispatchers.IO) {
            val resolved = runCatching { resolveStream(mediaMetadata.id) }.getOrNull() ?: return@launch
            // Stage cached lyrics so the converter can attach them to the load request;
            // instant on the TV for anything already in the local Room DB.
            val lyrics = runCatching { resolveLyrics(mediaMetadata.id) }.getOrNull()
            if (lyrics.isNullOrBlank()) {
                stagedLyrics.remove(mediaMetadata.id)
            } else {
                stagedLyrics[mediaMetadata.id] = lyrics
            }
            // Extract the artwork palette here (on the phone, no CORS limits) and stage it
            // so the converter can hand it to the receiver alongside lyrics/prefs.
            val paletteJson = runCatching { resolvePalette(mediaMetadata.thumbnailUrl) }.getOrNull()
            if (paletteJson.isNullOrBlank()) {
                stagedPalette.remove(mediaMetadata.id)
            } else {
                stagedPalette[mediaMetadata.id] = paletteJson
            }
            // Canvas de la canción (si está en caché y es una URL https accesible desde el
            // TV): el receiver lo muestra a pantalla completa en lugar de la carátula.
            val canvasUrl = runCatching {
                com.dezzy.music.ui.player.CanvasArtworkPlaybackCache.get(mediaMetadata.id)
                    ?.let { it.videoUrlVertical ?: it.videoUrl }
                    ?.takeIf { it.startsWith("http", ignoreCase = true) }
            }.getOrNull()
            if (canvasUrl.isNullOrBlank()) {
                stagedCanvas.remove(mediaMetadata.id)
            } else {
                stagedCanvas[mediaMetadata.id] = canvasUrl
            }
            // Re-read appearance prefs on every load so a settings change on the phone is
            // reflected on the TV from the next track onward.
            stagedUiPrefsJson = runCatching { resolveUiPrefs() }.getOrNull()
            withContext(Dispatchers.Main) {
                if (!_isConnected.value) return@withContext
                val item =
                    MediaItem.Builder()
                        .setMediaId(mediaMetadata.id)
                        .setUri(resolved.url)
                        // DefaultMediaItemConverter rejects items without a mimeType
                        .setMimeType(resolved.mimeType ?: "audio/mp4")
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(mediaMetadata.title)
                                .setArtist(mediaMetadata.artists.joinToString(", ") { it.name })
                                .apply {
                                    mediaMetadata.thumbnailUrl?.let { setArtworkUri(it.highRes().let(android.net.Uri::parse)) }
                                }
                                .build(),
                        ).build()
                loadedMediaId = mediaMetadata.id
                player.setMediaItem(item, positionMs)
                player.prepare()
                player.playWhenReady = playWhenReady
            }
        }
    }

    /** Call when the local song changes while casting — reloads only if it's actually a new id. */
    fun onLocalMediaItemChanged(mediaMetadata: MediaMetadata?, positionMs: Long, playWhenReady: Boolean) {
        if (!_isConnected.value || mediaMetadata == null) return
        if (mediaMetadata.id == loadedMediaId) return
        loadCurrent(mediaMetadata, positionMs, playWhenReady)
    }

    fun setPlayWhenReady(playWhenReady: Boolean) {
        val player = castPlayer ?: return
        if (!_isConnected.value) return
        applyingRemoteChange = true
        player.playWhenReady = playWhenReady
        applyingRemoteChange = false
    }

    fun seekTo(positionMs: Long) {
        val player = castPlayer ?: return
        if (!_isConnected.value) return
        applyingRemoteChange = true
        player.seekTo(positionMs)
        applyingRemoteChange = false
    }

    /**
     * The local ExoPlayer owns the queue (shuffle order, repeat-all advancement) and pushes
     * each new track to the receiver, so the receiver itself only ever needs to loop its
     * single loaded item (REPEAT_ONE) or not loop at all.
     */
    fun setRepeatMode(repeatMode: Int) {
        val player = castPlayer ?: return
        if (!_isConnected.value) return
        applyingRemoteChange = true
        runCatching {
            player.repeatMode =
                if (repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
        applyingRemoteChange = false
    }

    val currentPositionMs: Long
        get() = castPlayer?.takeIf { _isConnected.value }?.currentPosition ?: C.TIME_UNSET

    fun setVolume(volume: Float) {
        val player = castPlayer ?: return
        if (!_isConnected.value) return
        runCatching {
            player.volume = volume.coerceIn(0f, 1f)
        }
    }

    fun stopCasting() {
        runCatching { castContext?.sessionManager?.endCurrentSession(true) }
    }

    fun release() {
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.removeListener(remoteStateListener)
        castPlayer?.release()
        castPlayer = null
    }
}
