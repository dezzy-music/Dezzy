/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class Thumbnails(
    val thumbnails: List<Thumbnail>,
) {
    fun getHighestResUrl(): String? {
        return thumbnails
            .filter { it.width != null && it.height != null }
            .maxByOrNull { it.width!! * it.height!! }
            ?.url
            ?: thumbnails.lastOrNull()?.url
    }
}

@Serializable
data class Thumbnail(
    val url: String,
    val width: Int?,
    val height: Int?,
) {
    val normalizedUrl: String get() = if (url.startsWith("//")) "https:$url" else url
}
