/*
Credits:
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/koiverse
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.arturo254.opentune.canvas.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CanvasArtwork(
    val name: String? = null,
    val artist: String? = null,
    @SerialName("albumId")
    val albumId: String? = null,
    val albumName: String? = null,
    val static: String? = null,
    val animated: String? = null,
    val animatedVertical: String? = null,
    val videoUrl: String? = null,
    val videoUrlVertical: String? = null,
    // Which provider produced this canvas ("spotify" / "apple"); null for local files and
    // legacy cache entries. Consumers use it to invalidate the cache when the user switches
    // the canvas source — without it, a cached Spotify canvas kept showing in Apple mode.
    val source: String? = null,
) {
    val preferredAnimationUrl: String?
        get() = animated ?: videoUrl

    val preferredVerticalAnimationUrl: String?
        get() = animatedVertical ?: videoUrlVertical

    /** True when this entry is a downloaded local file, valid for any canvas source. */
    val isLocalFile: Boolean
        get() = videoUrl?.startsWith("file://") == true

    companion object {
        const val SOURCE_SPOTIFY = "spotify"
        const val SOURCE_APPLE = "apple"
    }
}
