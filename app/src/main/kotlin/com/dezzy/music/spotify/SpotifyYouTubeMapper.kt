/**
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.spotify

import android.content.Context
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.pages.SearchSummaryPage
import com.dezzy.music.db.MusicDatabase
import com.dezzy.music.extensions.toMediaItem
import com.dezzy.music.models.MediaMetadata
import com.dezzy.music.spotify.Spotify
import com.dezzy.music.spotify.SpotifyMapper
import com.dezzy.music.spotify.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import org.json.JSONObject
import org.json.JSONArray

object SpotifyMatchFileCache {
    private var cacheFile: File? = null
    private val matchesMap = mutableMapOf<String, CachedMatch>()

    data class CachedMatch(
        val spotifyId: String,
        val youtubeId: String,
        val title: String,
        val artist: String,
        val matchScore: Double,
        val cachedAt: Long,
        val isManualOverride: Boolean = false,
        val isrc: String? = null,
        val artistId: String? = null,
        val albumId: String? = null,
    )

    fun init(context: Context) {
        if (cacheFile != null) return
        cacheFile = File(context.filesDir, "spotify_matches.json")
        loadCache()
    }

    @Synchronized
    private fun loadCache() {
        val file = cacheFile ?: return
        if (!file.exists()) return
        try {
            val json = file.readText()
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val spotifyId = obj.getString("spotifyId")
                val youtubeId = obj.getString("youtubeId")
                val title = obj.optString("title", "")
                val artist = obj.optString("artist", "")
                val matchScore = obj.optDouble("matchScore", 1.0)
                val cachedAt = obj.optLong("cachedAt", System.currentTimeMillis())
                val isManualOverride = obj.optBoolean("isManualOverride", false)
                val isrc = obj.optString("isrc", "").takeIf { it.isNotBlank() }
                val artistId = obj.optString("artistId", "").takeIf { it.isNotBlank() }
                val albumId = obj.optString("albumId", "").takeIf { it.isNotBlank() }
                matchesMap[spotifyId] = CachedMatch(
                    spotifyId, youtubeId, title, artist, matchScore, cachedAt, isManualOverride,
                    isrc, artistId, albumId
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error loading Spotify matches cache")
        }
    }

    @Synchronized
    private fun saveCache() {
        val file = cacheFile ?: return
        try {
            val array = JSONArray()
            matchesMap.values.forEach { match ->
                val obj = JSONObject().apply {
                    put("spotifyId", match.spotifyId)
                    put("youtubeId", match.youtubeId)
                    put("title", match.title)
                    put("artist", match.artist)
                    put("matchScore", match.matchScore)
                    put("cachedAt", match.cachedAt)
                    put("isManualOverride", match.isManualOverride)
                    match.isrc?.let { put("isrc", it) }
                    match.artistId?.let { put("artistId", it) }
                    match.albumId?.let { put("albumId", it) }
                }
                array.put(obj)
            }
            file.writeText(array.toString(2))
        } catch (e: Exception) {
            Timber.e(e, "Error saving Spotify matches cache")
        }
    }

    @Synchronized
    fun getMatch(spotifyId: String): CachedMatch? {
        return matchesMap[spotifyId]
    }

    @Synchronized
    fun getMatchByYouTubeId(youtubeId: String): CachedMatch? {
        return matchesMap.values.firstOrNull { it.youtubeId == youtubeId }
    }

    @Synchronized
    fun putMatch(match: CachedMatch) {
        matchesMap[match.spotifyId] = match
        saveCache()
    }
}

/**
 * Handles the matching of Spotify tracks to YouTube Music equivalents.
 * Uses fuzzy matching on title, artist, and duration to find the best result.
 * Caches successful matches in a persistent JSON file and an in-memory LRU
 * cache to avoid repeated file I/O for recently resolved tracks.
 */
class SpotifyYouTubeMapper(
    private val database: MusicDatabase,
    context: Context,
) {
    init {
        SpotifyMatchFileCache.init(context)
    }

    /**
     * In-memory LRU cache of recently resolved Spotify→YouTube matches.
     * Avoids DB I/O for tracks resolved in the same session.
     */
    private val memoryCache = object : LinkedHashMap<String, CachedMatch>(
        MEM_CACHE_MAX_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedMatch>?): Boolean =
            size > MEM_CACHE_MAX_SIZE
    }

    private data class CachedMatch(
        val youtubeId: String,
        val title: String,
        val artist: String,
        val isManualOverride: Boolean = false,
    )

    /**
     * Maps a Spotify track to a YouTube MediaMetadata by searching YouTube Music.
     * Returns null if no suitable match is found.
     *
     * Resolution order: in-memory cache → persistent cache → YouTube search.
     */
    suspend fun mapToYouTube(track: SpotifyTrack): MediaMetadata? = withContext(Dispatchers.IO) {
        // 1. In-memory LRU cache (zero I/O)
        memoryCache[track.id]?.let { mem ->
            Timber.d("Spotify match memory hit: ${track.name} -> ${mem.youtubeId}")
            return@withContext buildMediaMetadata(mem.youtubeId, track, mem.title, mem.artist)
        }

        // 2. Persistent JSON cache
        val cached = SpotifyMatchFileCache.getMatch(track.id)
        if (cached != null) {
            Timber.d("Spotify match cache hit: ${track.name} -> ${cached.youtubeId} (manual=${cached.isManualOverride})")
            memoryCache[track.id] = CachedMatch(
                cached.youtubeId, cached.title, cached.artist, cached.isManualOverride,
            )
            return@withContext buildMediaMetadata(cached.youtubeId, track, cached.title, cached.artist)
        }

        // 3. YouTube search + fuzzy match
        val query = SpotifyMapper.buildSearchQuery(track)
        Timber.d("Searching YouTube for Spotify track: $query")

        val searchResult = YouTube.searchSummary(query).getOrNull() ?: return@withContext null
        val bestMatch = findBestMatch(track, searchResult)

        if (bestMatch != null) {
            SpotifyMatchFileCache.putMatch(
                SpotifyMatchFileCache.CachedMatch(
                    spotifyId = track.id,
                    youtubeId = bestMatch.id,
                    title = bestMatch.title,
                    artist = bestMatch.artists.firstOrNull()?.name ?: "",
                    matchScore = bestMatch.score,
                    cachedAt = System.currentTimeMillis(),
                    isrc = track.external_ids?.isrc,
                    artistId = bestMatch.artists.firstOrNull()?.id,
                    albumId = bestMatch.albumId,
                )
            )
            memoryCache[track.id] = CachedMatch(
                bestMatch.id, bestMatch.title, bestMatch.artistName,
            )
            Timber.d("Spotify match found: ${track.name} -> ${bestMatch.id} (score: ${bestMatch.score})")
            return@withContext buildMediaMetadata(
                youtubeId = bestMatch.id,
                spotifyTrack = track,
                ytTitle = bestMatch.title,
                ytArtist = bestMatch.artistName,
                ytThumbnailUrl = bestMatch.thumbnailUrl,
                ytArtists = bestMatch.artists,
                ytAlbumId = bestMatch.albumId,
                ytAlbumTitle = bestMatch.albumTitle,
            )
        }

        Timber.w("No YouTube match found for Spotify track: ${track.name} by ${track.artists.firstOrNull()?.name}")
        null
    }

    /**
     * Persists a user-chosen YouTube match for a Spotify track.
     * Manual overrides are never replaced by the automatic fuzzy matcher.
     */
    suspend fun overrideMatch(
        spotifyId: String,
        youtubeId: String,
        title: String,
        artist: String,
    ) = withContext(Dispatchers.IO) {
        SpotifyMatchFileCache.putMatch(
            SpotifyMatchFileCache.CachedMatch(
                spotifyId = spotifyId,
                youtubeId = youtubeId,
                title = title,
                artist = artist,
                matchScore = 1.0,
                cachedAt = System.currentTimeMillis(),
                isManualOverride = true,
            )
        )
        memoryCache[spotifyId] = CachedMatch(
            youtubeId, title, artist, isManualOverride = true,
        )
        Timber.d("Manual override saved: $spotifyId -> $youtubeId ($title by $artist)")
    }

    /**
     * Resolves a Spotify track to a MediaItem suitable for the player queue.
     */
    suspend fun resolveToMediaItem(track: SpotifyTrack): androidx.media3.common.MediaItem? {
        Timber.d("SpotifyMapper: resolving '${track.name}' by ${track.artists.firstOrNull()?.name}")
        val metadata = mapToYouTube(track)
        if (metadata == null) {
            Timber.w("SpotifyMapper: FAILED to resolve '${track.name}' - no YouTube match")
            return null
        }
        Timber.d("SpotifyMapper: resolved '${track.name}' -> YouTube ID: ${metadata.id}")
        return metadata.toMediaItem()
    }

    private fun findBestMatch(
        spotifyTrack: SpotifyTrack,
        searchResult: SearchSummaryPage,
    ): MatchCandidate? {
        val spotifyArtist = spotifyTrack.artists.firstOrNull()?.name ?: ""

        val precomputed = SpotifyMapper.precompute(
            title = spotifyTrack.name,
            artist = spotifyArtist,
            durationMs = spotifyTrack.durationMs,
        )

        val songs = searchResult.summaries
            .flatMap { it.items }
            .filterIsInstance<SongItem>()

        var bestCandidate: MatchCandidate? = null
        val earlyExitThreshold = SpotifyMapper.earlyExitThreshold()

        for (song in songs) {
            val score = SpotifyMapper.matchScorePrecomputed(
                precomputed = precomputed,
                candidateTitle = song.title,
                candidateArtist = song.artists.firstOrNull()?.name ?: "",
                candidateDurationSec = song.duration,
            )

            if (bestCandidate == null || score > bestCandidate.score) {
                bestCandidate = MatchCandidate(
                    id = song.id,
                    title = song.title,
                    artistName = song.artists.firstOrNull()?.name ?: "",
                    artists = song.artists.map { MediaMetadata.Artist(id = it.id, name = it.name) },
                    duration = song.duration ?: -1,
                    thumbnailUrl = song.thumbnail,
                    albumId = song.album?.id,
                    albumTitle = song.album?.name,
                    explicit = song.explicit,
                    score = score,
                )
                if (score >= earlyExitThreshold) break
            }
        }

        return bestCandidate?.takeIf { it.score >= MIN_MATCH_THRESHOLD }
    }

    private fun buildMediaMetadata(
        youtubeId: String,
        spotifyTrack: SpotifyTrack,
        ytTitle: String,
        ytArtist: String,
        ytThumbnailUrl: String? = null,
        ytArtists: List<MediaMetadata.Artist> = emptyList(),
        ytAlbumId: String? = null,
        ytAlbumTitle: String? = null,
    ): MediaMetadata {
        val thumbnail = SpotifyMapper.getTrackThumbnail(spotifyTrack)
            ?: ytThumbnailUrl
            ?: "https://i.ytimg.com/vi/$youtubeId/hqdefault.jpg"

        val artistsList = if (ytArtists.isNotEmpty()) {
            ytArtists
        } else if (ytArtist.isNotEmpty()) {
            listOf(MediaMetadata.Artist(id = null, name = ytArtist))
        } else {
            spotifyTrack.artists.map { MediaMetadata.Artist(id = null, name = it.name) }
        }

        val albumData = if (ytAlbumId != null && ytAlbumTitle != null) {
            MediaMetadata.Album(id = ytAlbumId, title = ytAlbumTitle)
        } else spotifyTrack.album?.let {
            MediaMetadata.Album(id = it.id, title = it.name)
        }

        return MediaMetadata(
            id = youtubeId,
            title = ytTitle.ifEmpty { spotifyTrack.name },
            artists = artistsList,
            duration = spotifyTrack.durationMs / 1000,
            thumbnailUrl = thumbnail,
            album = albumData,
            explicit = spotifyTrack.explicit,
        )
    }

    private data class MatchCandidate(
        val id: String,
        val title: String,
        val artistName: String,
        val artists: List<MediaMetadata.Artist>,
        val duration: Int,
        val thumbnailUrl: String?,
        val albumId: String?,
        val albumTitle: String?,
        val explicit: Boolean,
        val score: Double,
    )

    /**
     * Reverse lookup: given a YouTube track's metadata, finds the corresponding Spotify URI.
     */
    suspend fun resolveToSpotifyUri(
        youtubeId: String,
        title: String,
        artist: String,
        durationSec: Int = -1,
    ): String? = withContext(Dispatchers.IO) {
        SpotifyMatchFileCache.getMatchByYouTubeId(youtubeId)?.let { cached ->
            Timber.d("Reverse lookup cache hit: $youtubeId -> spotify:track:${cached.spotifyId}")
            return@withContext "spotify:track:${cached.spotifyId}"
        }

        val query = if (artist.isBlank()) title else "$artist $title"
        Timber.d("Reverse lookup: searching Spotify for '$query'")

        val results = Spotify.search(query, types = listOf("track"), limit = 5)
            .getOrNull()?.tracks?.items
        if (results.isNullOrEmpty()) {
            Timber.w("Reverse lookup: no Spotify results for '$query'")
            return@withContext null
        }

        val ytPrecomputed = SpotifyMapper.precompute(
            title = title,
            artist = artist,
            durationMs = if (durationSec > 0) durationSec * 1000 else 0,
        )

        var best: SpotifyTrack? = null
        var bestScore = 0.0
        for (candidate in results) {
            val score = SpotifyMapper.matchScorePrecomputed(
                precomputed = ytPrecomputed,
                candidateTitle = candidate.name,
                candidateArtist = candidate.artists.firstOrNull()?.name ?: "",
                candidateDurationSec = candidate.durationMs / 1000,
            )
            if (score > bestScore) {
                best = candidate
                bestScore = score
            }
        }

        if (best != null) {
            val score = bestScore
            if (score >= MIN_MATCH_THRESHOLD) {
                val uri = best.uri ?: "spotify:track:${best.id}"
                Timber.d("Reverse lookup found: $youtubeId -> $uri (score=$score)")
                SpotifyMatchFileCache.putMatch(
                    SpotifyMatchFileCache.CachedMatch(
                        spotifyId = best.id,
                        youtubeId = youtubeId,
                        title = title,
                        artist = artist,
                        matchScore = score,
                        cachedAt = System.currentTimeMillis(),
                    ),
                )
                return@withContext uri
            }
        }

        Timber.w("Reverse lookup: no match above threshold for '$query'")
        null
    }

    companion object {
        private const val MIN_MATCH_THRESHOLD = 0.35
        private const val MEM_CACHE_MAX_SIZE = 512
    }
}
