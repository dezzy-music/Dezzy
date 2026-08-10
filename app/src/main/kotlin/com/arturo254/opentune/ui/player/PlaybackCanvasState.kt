/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.arturo254.opentune.canvas.models.CanvasArtwork
import com.arturo254.opentune.constants.CanvasMode
import com.arturo254.opentune.constants.CanvasModeKey
import com.arturo254.opentune.utils.rememberEnumPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Shared canvas lookup for player styles: resolves the animated canvas for the current
 * song (memory/disk cache → downloaded local file → network), honoring the user's
 * [CanvasModeKey] preference. Extracted from DezzyPlayer so Deezer/Spotify (and any
 * other standalone player) can show canvases without duplicating the pipeline.
 */
@Composable
fun rememberPlaybackCanvas(
    mediaId: String?,
    title: String?,
    artist: String?,
    albumTitle: String?,
    durationMs: Long,
): CanvasArtwork? {
    val context = LocalContext.current
    val canvasMode by rememberEnumPreference(CanvasModeKey, defaultValue = CanvasMode.APPLE_WITH_FALLBACK)
    val anyCanvasActive = canvasMode != CanvasMode.NONE
    val canvasActiveForSpotify = canvasMode == CanvasMode.SPOTIFY || canvasMode == CanvasMode.SPOTIFY_WITH_FALLBACK

    // Keyed by (mediaId, canvasMode): switching the canvas source must clear the shown
    // artwork and must NOT inherit a stale fetchInFlight=true from a cancelled fetch of
    // the other source (which silently blocked the new fetch forever).
    var canvasArtwork by remember(mediaId, canvasMode) { mutableStateOf<CanvasArtwork?>(null) }
    var fetchInFlight by remember(mediaId, canvasMode) { mutableStateOf(false) }
    val storefront = remember {
        val country = Locale.getDefault().country
        if (country.length == 2) country.lowercase(Locale.ROOT) else "us"
    }

    LaunchedEffect(mediaId, canvasMode) {
        val id = mediaId
        if (id.isNullOrBlank() || !anyCanvasActive) {
            canvasArtwork = null
            return@LaunchedEffect
        }
        val expectedSource =
            if (canvasActiveForSpotify) CanvasArtwork.SOURCE_SPOTIFY else CanvasArtwork.SOURCE_APPLE

        // 1. In-memory/disk cache (shared with DezzyPlayer and Thumbnail) — only if the
        //    cached entry matches the selected source (local files are source-agnostic).
        CanvasArtworkPlaybackCache.get(id)?.let { cached ->
            if (cached.isLocalFile || cached.source == expectedSource) {
                canvasArtwork = cached
                return@LaunchedEffect
            }
            // Cached canvas belongs to the other source — ignore it and refetch below.
        }

        // 2. Downloaded local canvas file
        val localCanvasFile = java.io.File(context.filesDir, "canvas/$id.mp4")
        if (localCanvasFile.exists() && localCanvasFile.length() > 0) {
            val localPath = "file://${localCanvasFile.absolutePath}"
            val localArtwork = CanvasArtwork(
                name = title,
                artist = artist,
                animated = localPath,
                animatedVertical = localPath,
                videoUrl = localPath,
                videoUrlVertical = localPath,
            )
            canvasArtwork = localArtwork
            CanvasArtworkPlaybackCache.put(id, localArtwork)
            return@LaunchedEffect
        }

        // 3. Remote fetch
        if (fetchInFlight) return@LaunchedEffect
        fetchInFlight = true
        val songTitleRaw = title?.takeIf { it.isNotBlank() } ?: run {
            fetchInFlight = false
            return@LaunchedEffect
        }
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                fetchCanvasArtworkForPlayback(
                    songTitleRaw = songTitleRaw,
                    artistNameRaw = artist ?: "",
                    storefront = storefront,
                    requireVertical = false,
                    albumTitle = albumTitle,
                    durationSeconds = durationMs.takeIf { it > 0 }?.let { (it / 1000).toInt() },
                    spotifyCanvasEnabled = canvasActiveForSpotify,
                    spotifyTrackId = com.arturo254.opentune.spotify.SpotifyMatchFileCache
                        .getMatchByYouTubeId(id)?.spotifyId,
                    allowFallback = canvasMode == CanvasMode.APPLE_WITH_FALLBACK || canvasMode == CanvasMode.SPOTIFY_WITH_FALLBACK,
                )
            }.getOrNull()
        }
        canvasArtwork = fetched
        if (fetched != null) CanvasArtworkPlaybackCache.put(id, fetched)
        fetchInFlight = false
    }

    return canvasArtwork
}

/** Best animated URL pair for [CanvasArtworkPlayer]: primary (HLS/animated) + mp4 fallback. */
fun CanvasArtwork.playbackUrls(): Pair<String?, String?> =
    (animatedVertical ?: animated) to (videoUrlVertical ?: videoUrl)
