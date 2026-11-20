/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.lrclib.models

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class Track(
    val id: Int,
    val trackName: String,
    val artistName: String,
    // LRCLIB a veces devuelve "duration": null; sin el valor por defecto se cae toda la búsqueda.
    val duration: Double? = null,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

/** Diferencia en segundos con [target]; si LRCLIB no sabe la duración, nunca cuenta como coincidencia. */
internal fun Track.durationGap(target: Int): Int =
    duration?.let { abs(it.toInt() - target) } ?: Int.MAX_VALUE

internal fun List<Track>.bestMatchingFor(duration: Int): Track? {
    if (isEmpty()) return null

    if (duration == -1) {
        return firstOrNull { it.syncedLyrics != null } ?: firstOrNull()
    }

    return minByOrNull { it.durationGap(duration) }
        ?.takeIf { it.durationGap(duration) <= 2 }
}

internal fun List<Track>.bestMatchingFor(
    duration: Int,
    trackName: String? = null,
    artistName: String? = null,
): Track? {
    if (isEmpty()) return null

    if (duration == -1) {
        if (trackName != null && artistName != null) {
            return findBestMatch(trackName, artistName)
        }
        return firstOrNull { it.syncedLyrics != null } ?: firstOrNull()
    }

    return minByOrNull { it.durationGap(duration) }
        ?.takeIf { it.durationGap(duration) <= 2 }
}

private fun List<Track>.findBestMatch(
    trackName: String,
    artistName: String,
): Track? {
    val normalizedTrackName = trackName.trim().lowercase()
    val normalizedArtistName = artistName.trim().lowercase()

    return maxByOrNull { track ->
        var score = 0.0

        val trackNameSimilarity =
            calculateSimilarity(
                normalizedTrackName,
                track.trackName.trim().lowercase(),
            )

        val artistNameSimilarity =
            calculateSimilarity(
                normalizedArtistName,
                track.artistName.trim().lowercase(),
            )

        score = (trackNameSimilarity + artistNameSimilarity) / 2.0

        if (track.syncedLyrics != null) score += 0.1

        score
    }?.takeIf { track ->
        val trackNameSimilarity =
            calculateSimilarity(
                normalizedTrackName,
                track.trackName.trim().lowercase(),
            )
        val artistNameSimilarity =
            calculateSimilarity(
                normalizedArtistName,
                track.artistName.trim().lowercase(),
            )

        (trackNameSimilarity + artistNameSimilarity) / 2.0 > 0.6
    }
}

private fun calculateSimilarity(
    str1: String,
    str2: String,
): Double {
    if (str1 == str2) return 1.0
    if (str1.isEmpty() || str2.isEmpty()) return 0.0

    val containsScore =
        when {
            str1.contains(str2) || str2.contains(str1) -> 0.8
            else -> 0.0
        }

    val maxLength = maxOf(str1.length, str2.length)
    val distance = levenshteinDistance(str1, str2)
    val distanceScore = 1.0 - (distance.toDouble() / maxLength)

    return maxOf(containsScore, distanceScore)
}

private fun levenshteinDistance(
    str1: String,
    str2: String,
): Int {
    val len1 = str1.length
    val len2 = str2.length
    val matrix = Array(len1 + 1) { IntArray(len2 + 1) }

    for (i in 0..len1) matrix[i][0] = i
    for (j in 0..len2) matrix[0][j] = j

    for (i in 1..len1) {
        for (j in 1..len2) {
            val cost = if (str1[i - 1] == str2[j - 1]) 0 else 1
            matrix[i][j] =
                minOf(
                    matrix[i - 1][j] + 1, // deletion
                    matrix[i][j - 1] + 1, // insertion
                    matrix[i - 1][j - 1] + cost, // substitution
                )
        }
    }

    return matrix[len1][len2]
}
