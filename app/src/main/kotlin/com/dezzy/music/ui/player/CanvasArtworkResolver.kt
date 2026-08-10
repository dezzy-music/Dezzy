/*
 Credits:
 * ArchiveTune (2026)
 * © Chartreux Westia — github.com/koiverse
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */





package com.dezzy.music.ui.player

import com.dezzy.music.artistvideo.ArtistVideoCanvasProvider
import com.dezzy.music.artistvideo.ArtistVideoResponse
import com.dezzy.music.canvas.models.CanvasArtwork
import com.dezzy.music.canvas.DezzyCanvas
import com.dezzy.music.canvas.AppleMusicCanvasProvider
import com.dezzy.music.canvas.SpotifyCanvasProvider
import com.dezzy.music.spotify.Spotify
import com.dezzy.music.spotify.SpotifyTokenManager
import com.dezzy.music.utils.YTPlayerUtils
import java.util.concurrent.ConcurrentHashMap

/** Session cache of "song|artist" → Spotify track id (or "" for a known miss). */
private val canvasTrackIdCache = ConcurrentHashMap<String, String>()


internal suspend fun fetchCanvasArtworkForPlayback(
    songTitleRaw: String,
    artistNameRaw: String,
    storefront: String,
    requireVertical: Boolean,
    albumTitle: String? = null,
    durationSeconds: Int? = null,
    spotifyCanvasEnabled: Boolean = false,
    spotifyTrackId: String? = null,
    logFilePath: String? = null,
    allowFallback: Boolean = true,
): CanvasArtwork? {
    val songTitle = normalizeCanvasSongTitle(songTitleRaw)
    val artistName = normalizeCanvasArtistName(artistNameRaw)
    val candidates =
        linkedSetOf(
            songTitle to artistName,
            songTitleRaw to artistName,
            songTitle to artistNameRaw,
            songTitleRaw to artistNameRaw,
        ).filter { (song, artist) ->
            song.isNotBlank() && artist.isNotBlank()
        }

    // ── Modo Spotify Canvas (exclusivo) ──────────────────────────────────────
    // Cuando el usuario elige Spotify Canvas, no se intenta ninguna otra fuente.
    if (spotifyCanvasEnabled) {
        val hasAuth = SpotifyTokenManager.ensureAuthenticated()
        if (hasAuth) {
            val token = Spotify.accessToken
            // Resolve the Spotify track id: prefer the one mapped during the library
            // preload (cached); otherwise resolve it on demand via pathfinder search
            // (searchSuggestions) — the public REST search is rate-limited (429). This
            // gives Canvas full coverage, not just preloaded songs. Resolved ids are
            // cached so the search runs at most once per track.
            val resolvedTrackId = spotifyTrackId?.takeIf { it.isNotBlank() }
                ?: run {
                    val q = "$songTitle|$artistName".lowercase().trim()
                    canvasTrackIdCache[q] ?: run {
                        val id = Spotify.searchTrackId("$songTitle $artistName".trim())
                        // Cache both hits and misses (empty string) so we search at most once.
                        canvasTrackIdCache[q] = id ?: ""
                        id
                    }?.takeIf { it.isNotBlank() }
                }
            if (!resolvedTrackId.isNullOrBlank()) {
                val direct = SpotifyCanvasProvider.getByTrackId(
                    trackId = resolvedTrackId,
                    accessToken = token,
                    logFilePath = logFilePath,
                )?.takeIf { artwork ->
                    if (requireVertical) !artwork.preferredVerticalAnimationUrl.isNullOrBlank()
                    else !artwork.preferredAnimationUrl.isNullOrBlank()
                }
                if (direct != null) return direct.copy(source = CanvasArtwork.SOURCE_SPOTIFY)
            }
            val spotifyCanvas = candidates.firstNotNullOfOrNull { (song, artist) ->
                SpotifyCanvasProvider.getBySongArtist(
                    song = song,
                    artist = artist,
                    album = albumTitle,
                    durationSeconds = durationSeconds,
                    accessToken = token,
                    logFilePath = logFilePath
                )?.takeIf { artwork ->
                    if (requireVertical) {
                        !artwork.preferredVerticalAnimationUrl.isNullOrBlank()
                    } else {
                        !artwork.preferredAnimationUrl.isNullOrBlank()
                    }
                }
            }
            // Return whatever Spotify gave us (or null) — do NOT fall through to Apple.
            if (spotifyCanvas != null) return spotifyCanvas.copy(source = CanvasArtwork.SOURCE_SPOTIFY)
        }
        if (!allowFallback) return null
    }

    // ── Modo Apple Music Canvas (fallback chain) ──────────────────────────────
    // ── Intento 1: CanvasArtwork vía DezzyCanvas (con soporte vertical) ──
    val primary = candidates.firstNotNullOfOrNull { (song, artist) ->
        DezzyCanvas
            .getBySongArtist(
                song = song,
                artist = artist,
                storefront = storefront,
            )?.takeIf { artwork ->
                if (requireVertical) {
                    !artwork.preferredVerticalAnimationUrl.isNullOrBlank()
                } else {
                    !artwork.preferredAnimationUrl.isNullOrBlank()
                }
            }
    }
    if (primary != null) return primary.copy(source = CanvasArtwork.SOURCE_APPLE)

    // ── Intento 2: Fallback con ArtistVideoCanvasProvider (album + duration = más preciso) ──
    val fallbackResponse: ArtistVideoResponse? = candidates.firstNotNullOfOrNull { (song, artist) ->
        ArtistVideoCanvasProvider.getBySongArtist(
            song = song,
            artist = artist,
            album = albumTitle,
            duration = durationSeconds,
        )?.takeIf { !it.preferredAnimationUrl.isNullOrBlank() }
    }

    if (fallbackResponse != null) {
        return CanvasArtwork(
            name = fallbackResponse.name,
            artist = fallbackResponse.artist,
            albumId = fallbackResponse.albumId,
            albumName = albumTitle,
            static = fallbackResponse.static,
            animated = fallbackResponse.animated,
            animatedVertical = null,
            videoUrl = fallbackResponse.videoUrl,
            videoUrlVertical = null,
            source = CanvasArtwork.SOURCE_APPLE,
        )
    }

    // ── Intento 3: Fallback con AppleMusicCanvasProvider (Apple Music catalog) ──
    val appleMusicCanvas = candidates.firstNotNullOfOrNull { (song, artist) ->
        AppleMusicCanvasProvider.getBySongArtist(
            song = song,
            artist = artist,
            album = albumTitle,
            storefront = storefront,
        )?.takeIf { !it.preferredAnimationUrl.isNullOrBlank() }
    }
    if (appleMusicCanvas != null) return appleMusicCanvas.copy(source = CanvasArtwork.SOURCE_APPLE)

    if (!allowFallback) return null

    // ── Intento 4: Fallback de Video Oficial de YouTube para canciones con carátula estática ──
    val ytVideoUrl = runCatching {
        val query = "$songTitleRaw $artistNameRaw".trim()
        val searchResult = com.dezzy.music.innertube.YouTube.search(
            query = query,
            filter = com.dezzy.music.innertube.YouTube.SearchFilter.FILTER_VIDEO
        ).getOrNull()
        val videoId = searchResult?.items?.firstOrNull()?.id
        if (!videoId.isNullOrBlank()) {
            YTPlayerUtils.videoPlaybackDataForPlayback(videoId).getOrNull()?.streamUrl
        } else null
    }.getOrNull()

    if (!ytVideoUrl.isNullOrBlank()) {
        val expectedSource = if (spotifyCanvasEnabled) CanvasArtwork.SOURCE_SPOTIFY else CanvasArtwork.SOURCE_APPLE
        return CanvasArtwork(
            name = songTitleRaw,
            artist = artistNameRaw,
            videoUrl = ytVideoUrl,
            videoUrlVertical = ytVideoUrl,
            animated = ytVideoUrl,
            animatedVertical = ytVideoUrl,
            source = expectedSource
        )
    }

    return null
}

private fun normalizeCanvasSongTitle(raw: String): String {
    val stripped =
        raw
            .replace(Regex("\\s*\\[[^]]*]"), "")
            .replace(
                Regex(
                    "\\s*\\((?:feat\\.?|ft\\.?|featuring|with)\\b[^)]*\\)",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(
                Regex(
                    "\\s*\\((?:official\\s*)?(?:music\\s*)?(?:video|mv|lyrics?|audio|visualizer|live|remaster(?:ed)?|version|edit|mix|remix)[^)]*\\)",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(
                Regex(
                    "\\s*-\\s*(?:official\\s*)?(?:music\\s*)?(?:video|mv|lyrics?|audio|visualizer|live|remaster(?:ed)?|version|edit|mix|remix)\\b.*$",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(Regex("\\s+"), " ")
            .trim()

    return stripped
        .trim('-')
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun normalizeCanvasArtistName(raw: String): String {
    val first =
        raw
            .split(
                Regex(
                    "(?:\\s*,\\s*|\\s*&\\s*|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)",
                    RegexOption.IGNORE_CASE,
                ),
                limit = 2,
            ).firstOrNull().orEmpty()

    return first.replace(Regex("\\s+"), " ").trim()
}