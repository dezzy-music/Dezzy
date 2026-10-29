/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.innertube

import com.dezzy.music.innertube.models.AccountInfo
import com.dezzy.music.innertube.models.AlbumItem
import com.dezzy.music.innertube.models.Artist
import com.dezzy.music.innertube.models.ArtistItem
import com.dezzy.music.innertube.models.BrowseEndpoint
import com.dezzy.music.innertube.models.GridRenderer
import com.dezzy.music.innertube.models.MediaInfo
import com.dezzy.music.innertube.models.MusicCarouselShelfRenderer
import com.dezzy.music.innertube.models.MusicPlaylistShelfRenderer
import com.dezzy.music.innertube.models.MusicResponsiveListItemRenderer
import com.dezzy.music.innertube.models.MusicShelfRenderer
import com.dezzy.music.innertube.models.MusicTwoRowItemRenderer
import com.dezzy.music.innertube.models.PlaylistItem
import com.dezzy.music.innertube.models.SearchSuggestions
import com.dezzy.music.innertube.models.SectionListRenderer
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.dezzy.music.innertube.models.YTItem
import com.dezzy.music.innertube.models.YouTubeClient
import com.dezzy.music.innertube.models.YouTubeClient.Companion.WEB
import com.dezzy.music.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.dezzy.music.innertube.models.YouTubeLocale
import com.dezzy.music.innertube.models.comment.CommentRenderer
import com.dezzy.music.innertube.models.comment.CommentResponse
import com.dezzy.music.innertube.models.comment.CommentsResult
import com.dezzy.music.innertube.models.comment.VideoCommentSummary
import com.dezzy.music.innertube.models.getContinuation
import com.dezzy.music.innertube.models.getItems
import com.dezzy.music.innertube.models.oddElements
import com.dezzy.music.innertube.models.response.AccountMenuResponse
import com.dezzy.music.innertube.models.response.AddItemYouTubePlaylistResponse
import com.dezzy.music.innertube.models.response.BrowseResponse
import com.dezzy.music.innertube.models.response.CreatePlaylistResponse
import com.dezzy.music.innertube.models.response.GetQueueResponse
import com.dezzy.music.innertube.models.response.GetSearchSuggestionsResponse
import com.dezzy.music.innertube.models.response.GetTranscriptResponse
import com.dezzy.music.innertube.models.response.NextResponse
import com.dezzy.music.innertube.models.response.PlayerResponse
import com.dezzy.music.innertube.models.response.SearchResponse
import com.dezzy.music.innertube.pages.AlbumPage
import com.dezzy.music.innertube.pages.ArtistItemsContinuationPage
import com.dezzy.music.innertube.pages.ArtistItemsPage
import com.dezzy.music.innertube.pages.ArtistPage
import com.dezzy.music.innertube.pages.BrowseResult
import com.dezzy.music.innertube.pages.ChartsPage
import com.dezzy.music.innertube.pages.ExplorePage
import com.dezzy.music.innertube.pages.HistoryPage
import com.dezzy.music.innertube.pages.HomePage
import com.dezzy.music.innertube.pages.LibraryContinuationPage
import com.dezzy.music.innertube.pages.LibraryPage
import com.dezzy.music.innertube.pages.MoodAndGenres
import com.dezzy.music.innertube.pages.NewReleaseAlbumPage
import com.dezzy.music.innertube.pages.NextPage
import com.dezzy.music.innertube.pages.NextResult
import com.dezzy.music.innertube.pages.PlaylistContinuationPage
import com.dezzy.music.innertube.pages.PlaylistPage
import com.dezzy.music.innertube.pages.RelatedPage
import com.dezzy.music.innertube.pages.SearchPage
import com.dezzy.music.innertube.pages.SearchResult
import com.dezzy.music.innertube.pages.SearchSuggestionPage
import com.dezzy.music.innertube.pages.SearchSummary
import com.dezzy.music.innertube.pages.SearchSummaryPage
import com.dezzy.music.innertube.proxy.RotatingProxyClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.Proxy
import kotlin.random.Random

/**
 * Parse useful data with [InnerTube] sending requests.
 * Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic)
 */
object YouTube {
    private const val BROWSE_ID_EXPLORE = "FEmusic_explore"
    private const val BROWSE_ID_NEW_RELEASE_ALBUMS = "FEmusic_new_releases_albums"
    private const val BROWSE_ID_MOODS_AND_GENRES = "FEmusic_moods_and_genres"

    private val innerTube = InnerTube()
    private val mutableAuthState = MutableStateFlow(PlaybackAuthState.EMPTY)

    val authStateFlow: StateFlow<PlaybackAuthState> = mutableAuthState.asStateFlow()

    var authState: PlaybackAuthState
        get() = mutableAuthState.value
        set(value) {
            val normalized = value.normalized()
            mutableAuthState.value = normalized
            innerTube.applyAuthState(normalized)
        }

    var locale: YouTubeLocale
        get() = innerTube.locale
        set(value) {
            innerTube.locale = value
        }
    var visitorData: String?
        get() = authState.visitorData
        set(value) {
            authState = authState.copy(visitorData = value)
        }
    var dataSyncId: String?
        get() = authState.dataSyncId
        set(value) {
            authState = authState.copy(dataSyncId = value)
        }
    var cookie: String?
        get() = authState.cookie
        set(value) {
            authState = authState.copy(cookie = value)
        }
    var poToken: String?
        get() = authState.poToken
        set(value) {
            authState = authState.copy(poToken = value)
        }
    var webClientPoTokenEnabled: Boolean
        get() = authState.webClientPoTokenEnabled
        set(value) {
            authState = authState.copy(webClientPoTokenEnabled = value)
        }
    var poTokenGvs: String?
        get() = authState.poTokenGvs
        set(value) {
            authState = authState.copy(poTokenGvs = value)
        }
    var poTokenPlayer: String?
        get() = authState.poTokenPlayer
        set(value) {
            authState = authState.copy(poTokenPlayer = value)
        }

    /**
     * Optional bridge into the app module, which owns the BotGuard WebView that can mint genuine
     * per-video player poTokens. Set at app startup. Given a videoId, returns a freshly minted
     * player poToken, or null if one could not be produced.
     */
    @Volatile
    var poTokenMinter: (suspend (videoId: String) -> String?)? = null

    var proxy: Proxy?
        get() = innerTube.proxy
        set(value) {
            innerTube.proxy = value
        }
    var proxyUsername: String?
        get() = innerTube.proxyUsername
        set(value) {
            innerTube.proxyUsername = value
        }
    var proxyPassword: String?
        get() = innerTube.proxyPassword
        set(value) {
            innerTube.proxyPassword = value
        }
    var dns: Dns
        get() = innerTube.dns
        set(value) {
            innerTube.dns = value
        }
    var streamBypassProxy: Boolean = false
    val streamProxy: Proxy?
        get() = if (streamBypassProxy) null else proxy
    var useLoginForBrowse: Boolean
        get() = innerTube.useLoginForBrowse
        set(value) {
            innerTube.useLoginForBrowse = value
        }

    val rotatingProxyClient = RotatingProxyClient()
    private val _ipRotationActiveCount = MutableStateFlow(0)
    val ipRotationActiveCount: StateFlow<Int> = _ipRotationActiveCount.asStateFlow()

    suspend fun enableIpRotation() {
        withContext(Dispatchers.IO) {
            rotatingProxyClient.fetchAndLoad()
            innerTube.proxySelector = rotatingProxyClient.selector()
            _ipRotationActiveCount.value = rotatingProxyClient.activeCount()
        }
    }

    suspend fun refreshIpRotation() {
        withContext(Dispatchers.IO) {
            if (rotatingProxyClient.activeCount() <= 1) {
                rotatingProxyClient.fetchAndLoad()
            } else {
                rotatingProxyClient.rotate()
            }
            innerTube.proxySelector = rotatingProxyClient.selector()
            _ipRotationActiveCount.value = rotatingProxyClient.activeCount()
        }
    }

    fun disableIpRotation() {
        innerTube.proxySelector = null
        _ipRotationActiveCount.value = 0
    }

    fun currentPlaybackAuthState(): PlaybackAuthState = authState

    fun createDnsOverHttps(url: String): Dns {
        val bootstrapClient = OkHttpClient.Builder().build()
        return DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(url.toHttpUrl())
            .build()
    }

    private fun resolvePlayerPoToken(
        client: YouTubeClient,
        explicitPoToken: String?,
        authState: PlaybackAuthState,
    ): String? {
        return authState.resolvePlayerPoToken(
            client = client,
            explicitPoToken = explicitPoToken,
        )
    }

    fun hasLoginCookie(): Boolean {
        return authState.hasLoginCookie
    }

    fun hasPlaybackLoginContext(): Boolean {
        return authState.hasPlaybackLoginContext
    }

    internal fun resolveGvsPoToken(authState: PlaybackAuthState = currentPlaybackAuthState()): String? {
        return authState.resolveGvsPoToken()
    }

    internal fun appendGvsPoToken(
        url: String,
        client: YouTubeClient? = null,
        authState: PlaybackAuthState = currentPlaybackAuthState(),
    ): String {
        val token = authState.resolveGvsPoToken(client) ?: return url
        if (url.contains("pot=")) return url

        val separator = if (url.contains("?")) "&" else "?"
        return "$url${separator}pot=$token"
    }

    suspend fun searchSuggestions(query: String): Result<SearchSuggestions> =
        runCatching {
            val response = innerTube.getSearchSuggestions(WEB_REMIX, query).body<GetSearchSuggestionsResponse>()
            SearchSuggestions(
                queries =
                    response.contents?.getOrNull(0)?.searchSuggestionsSectionRenderer?.contents?.mapNotNull { content ->
                        content.searchSuggestionRenderer?.suggestion?.runs?.joinToString(separator = "") { it.text }
                    }.orEmpty(),
                recommendedItems =
                    response.contents?.getOrNull(1)?.searchSuggestionsSectionRenderer?.contents?.mapNotNull {
                        it.musicResponsiveListItemRenderer?.let { renderer ->
                            SearchSuggestionPage.fromMusicResponsiveListItemRenderer(renderer)
                        }
                    }.orEmpty(),
            )
        }

    suspend fun searchSummary(query: String): Result<SearchSummaryPage> =
        runCatching {
            val response = innerTube.search(WEB_REMIX, query).body<SearchResponse>()
            val contents =
                response.contents
                    ?.tabbedSearchResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    .orEmpty()
            val topItems = mutableListOf<YTItem>()
            val summaries = mutableListOf<SearchSummary>()

            contents.forEach { content ->
                content.musicCardShelfRenderer?.let { renderer ->
                    topItems +=
                        listOfNotNull(SearchSummaryPage.fromMusicCardShelfRenderer(renderer))
                            .plus(
                                renderer.contents
                                    ?.mapNotNull { it.musicResponsiveListItemRenderer }
                                    ?.mapNotNull {
                                        SearchSummaryPage.fromMusicResponsiveListItemRenderer(
                                            it,
                                        )
                                    }
                                    .orEmpty(),
                            )
                    return@forEach
                }

                content.itemSectionRenderer?.contents?.let { sectionContents ->
                    topItems +=
                        sectionContents.mapNotNull {
                            it.musicResponsiveListItemRenderer?.let { renderer ->
                                SearchSummaryPage.fromMusicResponsiveListItemRenderer(renderer)
                            }
                        }
                    summaries +=
                        sectionContents.mapNotNull { it.musicShelfRenderer?.toSearchSummary() }
                    return@forEach
                }

                content.musicShelfRenderer?.toSearchSummary()?.let(summaries::add)
            }

            SearchSummaryPage(
                summaries =
                    buildList {
                        topItems
                            .distinctBy { it.id }
                            .takeIf { it.isNotEmpty() }
                            ?.let { add(SearchSummary(title = "Top results", items = it)) }
                        addAll(summaries)
                    },
            )
        }

    suspend fun search(
        query: String,
        filter: SearchFilter,
    ): Result<SearchResult> =
        runCatching {
            val response = innerTube.search(WEB_REMIX, query, filter.value).body<SearchResponse>()
            val contents =
                response.contents
                    ?.tabbedSearchResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    .orEmpty()
            val shelves =
                contents.flatMap { content ->
                    buildList {
                        content.musicShelfRenderer?.let { add(it) }
                        content.itemSectionRenderer?.contents
                            ?.mapNotNull { it.musicShelfRenderer }
                            ?.let { addAll(it) }
                    }
                }
            val inlineItems =
                contents.flatMap { content ->
                    content.itemSectionRenderer?.contents
                        ?.mapNotNull { it.musicResponsiveListItemRenderer }
                        .orEmpty()
                }
            SearchResult(
                items =
                    shelves
                        .flatMap { it.contents?.getItems().orEmpty() }
                        .plus(inlineItems)
                        .mapNotNull { SearchPage.toYTItem(it) }
                        .distinctBy { it.id },
                continuation =
                    shelves
                        .asSequence()
                        .mapNotNull {
                            it.continuations?.getContinuation() ?: it.contents?.getContinuation()
                        }
                        .firstOrNull(),
            )
        }

    suspend fun searchContinuation(continuation: String): Result<SearchResult> =
        runCatching {
            val response = innerTube.search(WEB_REMIX, continuation = continuation).body<SearchResponse>()
            val continuationPage = response.continuationContents?.musicShelfContinuation
            val items =
                continuationPage
                    ?.contents
                    ?.mapNotNull {
                        it.musicResponsiveListItemRenderer?.let { renderer ->
                            SearchPage.toYTItem(
                                renderer,
                            )
                        }
                    }
                    ?: emptyList()
            SearchResult(
                items = items,
                continuation =
                    if (items.isEmpty()) {
                        null
                    } else {
                        continuationPage?.continuations?.getContinuation()
                            ?: continuationPage?.contents
                                ?.firstOrNull { it.continuationItemRenderer != null }
                                ?.continuationItemRenderer
                                ?.continuationEndpoint
                                ?.continuationCommand
                                ?.token
                    },
            )
        }

    private fun MusicShelfRenderer.toSearchSummary(): SearchSummary? {
        val items =
            contents
                ?.getItems()
                ?.mapNotNull { SearchSummaryPage.fromMusicResponsiveListItemRenderer(it) }
                ?.distinctBy { it.id }
                .orEmpty()
        if (items.isEmpty()) return null

        val title =
            title
                ?.runs
                ?.joinToString(separator = "") { it.text }
                ?.takeIf { it.isNotBlank() }
                ?: "Other"

        return SearchSummary(title = title, items = items)
    }

    suspend fun album(
        browseId: String,
        withSongs: Boolean = true,
    ): Result<AlbumPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId).body<BrowseResponse>()
            val playlistId =
                AlbumPage.getPlaylistId(response)
                    ?: throw IllegalStateException("Missing album playlist id for $browseId")
            val albumTitle =
                AlbumPage.getTitle(response)
                    ?: throw IllegalStateException("Missing album title for $browseId")
            val albumArtists = AlbumPage.getArtists(response).takeIf { it.isNotEmpty() }
            val albumYear = AlbumPage.getYear(response)
            val albumThumbnail =
                AlbumPage.getThumbnail(response)
                    ?: throw IllegalStateException("Missing album thumbnail url for $browseId")
            val albumItem =
                AlbumItem(
                    browseId = browseId,
                    playlistId = playlistId,
                    title = albumTitle,
                    artists = albumArtists,
                    year = albumYear,
                    thumbnail = albumThumbnail,
                    explicit = false, // TODO: Extract explicit badge for albums from YouTube response
                )
            val inlineSongs = if (withSongs) AlbumPage.getSongs(response, albumItem) else emptyList()
            val songs =
                if (withSongs) {
                    val fetchedSongs =
                        runCatching {
                            albumSongs(playlistId, albumItem).getOrThrow()
                        }.getOrElse { error ->
                            if (inlineSongs.isNotEmpty()) {
                                inlineSongs
                            } else {
                                throw error
                            }
                        }

                    if (fetchedSongs.isEmpty() && inlineSongs.isNotEmpty()) {
                        inlineSongs
                    } else {
                        fetchedSongs
                    }
                } else {
                    emptyList()
                }

            AlbumPage(
                album = albumItem,
                songs = songs,
                otherVersions =
                    response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer?.contents
                        ?.mapNotNull { it.musicCarouselShelfRenderer }
                        ?.flatMap { it.contents }
                        ?.mapNotNull { it.musicTwoRowItemRenderer }
                        ?.mapNotNull(NewReleaseAlbumPage::fromMusicTwoRowItemRenderer)
                        ?.distinctBy { it.id }
                        .orEmpty(),
            )
        }

    suspend fun albumSongs(
        playlistId: String,
        album: AlbumItem? = null,
    ): Result<List<SongItem>> =
        runCatching {
            var response = innerTube.browse(WEB_REMIX, "VL$playlistId").body<BrowseResponse>()
            val songs = linkedMapOf<String, SongItem>()

            fun appendSongs(
                candidates: List<MusicResponsiveListItemRenderer>,
                parsedSongs: List<SongItem>,
                source: String,
            ): Boolean {
                if (candidates.isNotEmpty() && parsedSongs.isEmpty()) {
                    throw IllegalStateException("Unable to parse album songs from $source for playlist $playlistId")
                }

                val previousSize = songs.size
                parsedSongs.forEach { songs.putIfAbsent(it.id, it) }
                return songs.size > previousSize
            }

            appendSongs(
                candidates = AlbumPage.getSongRenderers(response),
                parsedSongs = AlbumPage.getSongs(response, album),
                source = "initial response",
            )

            var continuation = AlbumPage.getSongContinuation(response)
            val seenContinuations = mutableSetOf<String>()
            var requestCount = 0
            val maxRequests = 50 // Prevent excessive API calls

            var consecutiveEmptyResponses = 0
            while (continuation != null && requestCount < maxRequests) {
                if (continuation in seenContinuations) {
                    break
                }
                seenContinuations.add(continuation)
                requestCount++

                response =
                    innerTube.browse(
                        client = WEB_REMIX,
                        continuation = continuation,
                    ).body<BrowseResponse>()

                val newSongCandidates = AlbumPage.getContinuationSongRenderers(response)
                val newSongs = AlbumPage.getContinuationSongs(response, album)
                val hasNewSongs =
                    if (newSongCandidates.isNotEmpty() || newSongs.isNotEmpty()) {
                        appendSongs(
                            candidates = newSongCandidates,
                            parsedSongs = newSongs,
                            source = "continuation response",
                        )
                    } else {
                        false
                    }

                if (!hasNewSongs) {
                    consecutiveEmptyResponses++
                    if (consecutiveEmptyResponses >= 2) break
                } else {
                    consecutiveEmptyResponses = 0
                }

                continuation = AlbumPage.getNextSongContinuation(response)
            }
            songs.values.toList()
        }

    suspend fun artist(browseId: String): Result<ArtistPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId).body<BrowseResponse>()
            val immersiveHeader = response.header?.musicImmersiveHeaderRenderer
            val subscribeButtonRenderer = immersiveHeader?.subscriptionButton?.subscribeButtonRenderer

            ArtistPage(
                artist =
                    ArtistItem(
                        id = browseId,
                        title =
                            immersiveHeader?.title?.runs?.firstOrNull()?.text
                                ?: response.header?.musicVisualHeaderRenderer?.title?.runs?.firstOrNull()?.text
                                ?: response.header?.musicHeaderRenderer?.title?.runs?.firstOrNull()?.text!!,
                        thumbnail =
                            immersiveHeader?.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                                ?: response.header?.musicVisualHeaderRenderer?.foregroundThumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                                ?: response.header?.musicDetailHeaderRenderer?.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl(),
                        channelId = subscribeButtonRenderer?.channelId,
                        playEndpoint =
                            response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                                ?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()?.musicShelfRenderer
                                ?.contents?.firstOrNull()?.musicResponsiveListItemRenderer?.overlay?.musicItemThumbnailOverlayRenderer
                                ?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint,
                        shuffleEndpoint =
                            immersiveHeader?.playButton?.buttonRenderer?.navigationEndpoint?.watchEndpoint
                                ?: response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer
                                    ?.contents?.firstOrNull()?.musicShelfRenderer?.contents?.firstOrNull()?.musicResponsiveListItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                        radioEndpoint = immersiveHeader?.startRadioButton?.buttonRenderer?.navigationEndpoint?.watchEndpoint,
                        subscriberCountText =
                            subscribeButtonRenderer?.subscriberCountText?.runs?.firstOrNull()?.text
                                ?: subscribeButtonRenderer?.subscriberCountWithSubscribeText?.runs?.firstOrNull()?.text,
                        monthlyListenerCountText = immersiveHeader?.monthlyListenerCount?.runs?.firstOrNull()?.text,
                    ),
                sections =
                    response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                        ?.tabRenderer?.content?.sectionListRenderer?.contents
                        ?.mapNotNull(ArtistPage::fromSectionListRendererContent)!!,
                description = immersiveHeader?.description?.runs?.firstOrNull()?.text,
            )
        }

    suspend fun artistItems(endpoint: BrowseEndpoint): Result<ArtistItemsPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, endpoint.browseId, endpoint.params).body<BrowseResponse>()
            val sectionContents = response.artistItemsSectionContents()
            val gridRenderer = sectionContents.firstNotNullOfOrNull { it.findGridRenderer() }
            if (gridRenderer != null) {
                ArtistItemsPage(
                    title = gridRenderer.header?.gridHeaderRenderer?.title?.runs?.firstOrNull()?.text.orEmpty(),
                    items =
                        gridRenderer.items.mapNotNull {
                            it.musicTwoRowItemRenderer?.let { renderer ->
                                ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer)
                            }
                        },
                    continuation = gridRenderer.continuations?.getContinuation(),
                )
            } else {
                val musicPlaylistShelfRenderer =
                    sectionContents.firstNotNullOfOrNull { it.findMusicPlaylistShelfRenderer() }
                val musicShelfRenderer =
                    sectionContents.firstNotNullOfOrNull { it.findMusicShelfRenderer() }
                val shelfContents =
                    musicPlaylistShelfRenderer?.contents ?: musicShelfRenderer?.contents.orEmpty()
                ArtistItemsPage(
                    title =
                        response.header?.musicHeaderRenderer?.title?.runs?.firstOrNull()?.text
                            ?: musicShelfRenderer?.title?.runs?.firstOrNull()?.text
                            ?: "",
                    items =
                        shelfContents.getItems().mapNotNull {
                            ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                        },
                    continuation =
                        shelfContents.getContinuation()
                            ?: musicPlaylistShelfRenderer?.continuations?.getContinuation()
                            ?: musicShelfRenderer?.continuations?.getContinuation(),
                )
            }
        }

    private fun BrowseResponse.artistItemsSectionContents(): List<SectionListRenderer.Content> =
        contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.contents.orEmpty()

    private fun SectionListRenderer.Content.findGridRenderer(): GridRenderer? =
        gridRenderer ?: itemSectionRenderer?.contents?.firstNotNullOfOrNull { it.gridRenderer }

    private fun SectionListRenderer.Content.findMusicPlaylistShelfRenderer(): MusicPlaylistShelfRenderer? = musicPlaylistShelfRenderer

    private fun SectionListRenderer.Content.findMusicShelfRenderer(): MusicShelfRenderer? =
        musicShelfRenderer
            ?: itemSectionRenderer?.contents?.firstNotNullOfOrNull { it.musicShelfRenderer }

    suspend fun artistItemsContinuation(continuation: String): Result<ArtistItemsContinuationPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, continuation = continuation).body<BrowseResponse>()

            when {
                response.continuationContents?.gridContinuation != null -> {
                    val gridContinuation = response.continuationContents.gridContinuation
                    val items =
                        gridContinuation.items.mapNotNull {
                            it.musicTwoRowItemRenderer?.let { renderer ->
                                ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer)
                            }
                        }
                    ArtistItemsContinuationPage(
                        items = items,
                        continuation = if (items.isEmpty()) null else gridContinuation.continuations?.getContinuation(),
                    )
                }

                response.continuationContents?.musicPlaylistShelfContinuation != null -> {
                    val musicPlaylistShelfContinuation = response.continuationContents.musicPlaylistShelfContinuation
                    val items =
                        musicPlaylistShelfContinuation.contents.getItems().mapNotNull {
                            ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                        }
                    ArtistItemsContinuationPage(
                        items = items,
                        continuation = if (items.isEmpty()) null else musicPlaylistShelfContinuation.continuations?.getContinuation(),
                    )
                }

                else -> {
                    val continuationItems =
                        response.onResponseReceivedActions?.firstOrNull()
                            ?.appendContinuationItemsAction?.continuationItems
                    val items =
                        continuationItems?.getItems()?.mapNotNull {
                            ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                        } ?: emptyList()
                    ArtistItemsContinuationPage(
                        items = items,
                        continuation = if (items.isEmpty()) null else continuationItems?.getContinuation(),
                    )
                }
            }
        }

    suspend fun playlist(playlistId: String): Result<PlaylistPage> =
        runCatching {
            val response =
                innerTube.browse(
                    client = WEB_REMIX,
                    browseId = "VL$playlistId",
                    setLogin = true,
                ).body<BrowseResponse>()
            val base = response.contents?.twoColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()
            val header = base?.musicResponsiveHeaderRenderer ?: base?.musicEditablePlaylistDetailHeaderRenderer?.header?.musicResponsiveHeaderRenderer
            if (header == null) throw IllegalStateException("PLAYLIST_PRIVATE")

            val title = header.title.runs?.firstOrNull()?.text ?: throw IllegalStateException("PLAYLIST_PRIVATE")
            val thumbnail =
                header.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                    ?: throw IllegalStateException("PLAYLIST_PRIVATE")

            val editable = base?.musicEditablePlaylistDetailHeaderRenderer != null

            val headerMenuItems =
                header.buttons.firstOrNull { it.menuRenderer != null }?.menuRenderer?.items.orEmpty()

            val allFirstColumnContents =
                response.contents?.twoColumnBrowseResultsRenderer?.tabs
                    ?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents.orEmpty()
            val description =
                base?.musicEditablePlaylistDetailHeaderRenderer?.header
                    ?.musicDetailHeaderRenderer?.description?.runs?.joinToString("") { it.text }
                    ?: allFirstColumnContents.firstNotNullOfOrNull {
                        it.musicDescriptionShelfRenderer?.description?.runs?.joinToString("") { run -> run.text }
                    }

            PlaylistPage(
                playlist =
                    PlaylistItem(
                        id = playlistId,
                        title = title,
                        author =
                            header.straplineTextOne?.runs?.firstOrNull()?.let {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            },
                        songCountText = header.secondSubtitle?.runs?.firstOrNull()?.text,
                        thumbnail = thumbnail,
                        description = description,
                        playEndpoint = header.buttons.firstOrNull()?.musicPlayButtonRenderer?.playNavigationEndpoint?.anyWatchEndpoint,
                        shuffleEndpoint = headerMenuItems.firstOrNull()?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                        radioEndpoint =
                            headerMenuItems.find {
                                it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                            }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                        isEditable = editable,
                    ),
                songs =
                    response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer
                        ?.contents?.firstOrNull()?.musicPlaylistShelfRenderer?.contents?.getItems()?.mapNotNull {
                            PlaylistPage.fromMusicResponsiveListItemRenderer(it)
                        } ?: emptyList(),
                songsContinuation =
                    response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer
                        ?.contents?.firstOrNull()?.musicPlaylistShelfRenderer?.let { shelf ->
                            shelf.contents.getContinuation() ?: shelf.continuations?.getContinuation()
                        },
                continuation =
                    response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer
                        ?.continuations?.getContinuation(),
            )
        }

    /**
     * Canciones de la playlist de TENDENCIAS de YouTube Music (la del propio
     * país del usuario). Es la fuente de la tarjeta "pistas más comentadas":
     * vídeos musicales reales que sí tienen comentarios (a diferencia de las
     * "art tracks" auto-generadas de los charts, que suelen tenerlos apagados).
     *
     * Camino verificado: charts → primer card de playlist ("VL…") → esa playlist.
     */
    suspend fun trendingTracks(limit: Int = 20): Result<List<SongItem>> =
        runCatching {
            val response =
                innerTube.browse(
                    client = WEB_REMIX,
                    browseId = "FEmusic_charts",
                    params = "ggMGCgQIgAQ%3D",
                    setLogin = true,
                ).body<BrowseResponse>()

            val sections =
                response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                    ?.tabRenderer?.content?.sectionListRenderer?.contents.orEmpty()

            val playlistBrowseId =
                sections.asSequence()
                    .mapNotNull { it.musicCarouselShelfRenderer }
                    .flatMap { it.contents.asSequence() }
                    .mapNotNull { it.musicTwoRowItemRenderer?.navigationEndpoint?.browseEndpoint?.browseId }
                    .firstOrNull { it.startsWith("VL") }

            if (playlistBrowseId != null) {
                val list = playlist(playlistBrowseId.removePrefix("VL")).getOrNull()?.songs?.take(limit).orEmpty()
                if (list.isNotEmpty()) return@runCatching list
            }

            // Fallback: búsqueda de canciones populares si la playlist de gráficos falla o cambia de ID
            search(
                "hits music",
                filter = SearchFilter.FILTER_SONG,
            ).getOrNull()?.items?.filterIsInstance<com.dezzy.music.innertube.models.SongItem>()?.take(limit).orEmpty()
        }

    suspend fun playlistContinuation(continuation: String): Result<PlaylistContinuationPage> =
        runCatching {
            val response =
                innerTube.browse(
                    client = WEB_REMIX,
                    continuation = continuation,
                    browseId = "",
                    setLogin = true,
                ).body<BrowseResponse>()

            playlistContinuationPageFromResponse(response)
        }

    suspend fun home(
        continuation: String? = null,
        params: String? = null,
    ): Result<HomePage> =
        runCatching {
            if (continuation != null) {
                return@runCatching homeContinuation(continuation).getOrThrow()
            }

            val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_home", params = params, setLogin = true).body<BrowseResponse>()
            val continuation =
                response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                    ?.tabRenderer?.content?.sectionListRenderer?.continuations?.getContinuation()
            val sectionListRender =
                response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                    ?.tabRenderer?.content?.sectionListRenderer
            val sections =
                sectionListRender?.contents!!
                    .mapNotNull { it.musicCarouselShelfRenderer }
                    .mapNotNull {
                        HomePage.Section.fromMusicCarouselShelfRenderer(it)
                    }.toMutableList()
            val chips = sectionListRender.header?.chipCloudRenderer?.chips?.mapNotNull { HomePage.Chip.fromChipCloudChipRenderer(it) }
            HomePage(chips, sections, continuation)
        }

    private suspend fun homeContinuation(continuation: String): Result<HomePage> =
        runCatching {
            val response =
                innerTube.browse(WEB_REMIX, continuation = continuation).body<BrowseResponse>()
            val sections =
                response.continuationContents?.sectionListContinuation?.contents
                    ?.mapNotNull { it.musicCarouselShelfRenderer }
                    ?.mapNotNull {
                        HomePage.Section.fromMusicCarouselShelfRenderer(it)
                    }.orEmpty()
            val nextContinuation =
                if (sections.isEmpty()) {
                    null
                } else {
                    response.continuationContents?.sectionListContinuation?.continuations?.getContinuation()
                }
            HomePage(
                chips = null,
                sections = sections,
                continuation = nextContinuation,
            )
        }

    suspend fun explore(): Result<ExplorePage> =
        runCatching {
            val response =
                innerTube.browse(WEB_REMIX, browseId = BROWSE_ID_EXPLORE).body<BrowseResponse>()
            ExplorePage(
                newReleaseAlbums =
                    response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.find {
                        it.musicCarouselShelfRenderer?.header?.musicCarouselShelfBasicHeaderRenderer?.moreContentButton?.buttonRenderer?.navigationEndpoint?.browseEndpoint?.browseId == BROWSE_ID_NEW_RELEASE_ALBUMS
                    }?.musicCarouselShelfRenderer?.contents
                        ?.mapNotNull { it.musicTwoRowItemRenderer }
                        ?.mapNotNull(NewReleaseAlbumPage::fromMusicTwoRowItemRenderer).orEmpty(),
                moodAndGenres =
                    response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.find {
                        it.musicCarouselShelfRenderer?.header?.musicCarouselShelfBasicHeaderRenderer?.moreContentButton?.buttonRenderer?.navigationEndpoint?.browseEndpoint?.browseId == BROWSE_ID_MOODS_AND_GENRES
                    }?.musicCarouselShelfRenderer?.contents
                        ?.mapNotNull { it.musicNavigationButtonRenderer }
                        ?.mapNotNull(MoodAndGenres.Companion::fromMusicNavigationButtonRenderer)
                        .orEmpty(),
            )
        }

    suspend fun newReleaseAlbums(): Result<List<AlbumItem>> =
        runCatching {
            try {
                val directAlbums = newReleaseAlbumsFromBrowsePage()
                if (directAlbums.isNotEmpty()) return@runCatching directAlbums
            } catch (throwable: Throwable) {
                if (!throwable.isBrowsePageUnavailable()) throw throwable
            }
            explore().getOrThrow().newReleaseAlbums
        }

    private suspend fun newReleaseAlbumsFromBrowsePage(): List<AlbumItem> {
        val response =
            innerTube.browse(WEB_REMIX, browseId = BROWSE_ID_NEW_RELEASE_ALBUMS)
                .body<BrowseResponse>()
        return response.newReleaseAlbumItems()
    }

    private fun BrowseResponse.newReleaseAlbumItems(): List<AlbumItem> {
        val contents =
            this.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?: this.contents
                    ?.sectionListRenderer
                    ?.contents
                ?: continuationContents
                    ?.sectionListContinuation
                    ?.contents
                ?: emptyList()

        return contents
            .asSequence()
            .flatMap { content ->
                sequence {
                    content.gridRenderer
                        ?.items
                        ?.asSequence()
                        ?.mapNotNull { it.musicTwoRowItemRenderer }
                        ?.forEach { yield(it) }
                    content.musicCarouselShelfRenderer
                        ?.contents
                        ?.asSequence()
                        ?.mapNotNull { it.musicTwoRowItemRenderer }
                        ?.forEach { yield(it) }
                    content.itemSectionRenderer
                        ?.contents
                        ?.asSequence()
                        ?.mapNotNull { it.gridRenderer }
                        ?.flatMap { it.items.asSequence() }
                        ?.mapNotNull { it.musicTwoRowItemRenderer }
                        ?.forEach { yield(it) }
                }
            }
            .mapNotNull(NewReleaseAlbumPage::fromMusicTwoRowItemRenderer)
            .toList()
    }

    private fun Throwable.isBrowsePageUnavailable(): Boolean {
        val exception = this as? ClientRequestException ?: return false
        return exception.response.status == HttpStatusCode.NotFound ||
            exception.response.status == HttpStatusCode.BadRequest
    }

    suspend fun moodAndGenres(): Result<List<MoodAndGenres>> =
        runCatching {
            val response =
                innerTube.browse(WEB_REMIX, browseId = BROWSE_ID_MOODS_AND_GENRES)
                    .body<BrowseResponse>()
            response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents!!
                .mapNotNull(MoodAndGenres.Companion::fromSectionListRendererContent)
        }

    suspend fun browse(
        browseId: String,
        params: String?,
    ): Result<BrowseResult> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId = browseId, params = params).body<BrowseResponse>()
            val browseItems =
                response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.mapNotNull {
                        content ->
                    when {
                        content.gridRenderer != null -> {
                            BrowseResult.Item(
                                title = content.gridRenderer.header?.gridHeaderRenderer?.title?.runs?.firstOrNull()?.text,
                                items =
                                    content.gridRenderer.items
                                        .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                                        .mapNotNull(RelatedPage.Companion::fromMusicTwoRowItemRenderer),
                            )
                        }

                        content.musicCarouselShelfRenderer != null -> {
                            BrowseResult.Item(
                                title = content.musicCarouselShelfRenderer.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.firstOrNull()?.text,
                                items =
                                    content.musicCarouselShelfRenderer.contents
                                        .mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
                                        .mapNotNull(RelatedPage.Companion::fromMusicTwoRowItemRenderer),
                            )
                        }

                        else -> null
                    }
                }.orEmpty()
            BrowseResult(
                title = response.header?.musicHeaderRenderer?.title?.runs?.firstOrNull()?.text,
                thumbnail =
                    response.header?.musicImmersiveHeaderRenderer?.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                        ?: response.header?.musicVisualHeaderRenderer?.foregroundThumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                        ?: response.header?.musicDetailHeaderRenderer?.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                        ?: response.header?.musicEditablePlaylistDetailHeaderRenderer?.header?.musicDetailHeaderRenderer?.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                        ?: response.header?.musicEditablePlaylistDetailHeaderRenderer?.header?.musicResponsiveHeaderRenderer?.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                        ?: response.header?.musicHeaderRenderer?.thumbnail?.thumbnails?.lastOrNull()?.normalizedUrl
                        ?: response.header?.musicHeaderRenderer?.straplineThumbnail?.thumbnails?.lastOrNull()?.normalizedUrl
                        ?: browseItems.asSequence().flatMap { it.items.asSequence() }
                            .mapNotNull { it.thumbnail }.firstOrNull(),
                items = browseItems,
            )
        }

    suspend fun library(
        browseId: String,
        tabIndex: Int = 0,
    ) = runCatching {
        val response =
            innerTube.browse(
                client = WEB_REMIX,
                browseId = browseId,
                setLogin = true,
            ).body<BrowseResponse>()

        val tabs = response.contents?.singleColumnBrowseResultsRenderer?.tabs

        val contents =
            if (tabs != null && tabIndex >= 0 && tabIndex < tabs.size) {
                tabs[tabIndex].tabRenderer.content?.sectionListRenderer?.contents?.firstOrNull()
            } else {
                null
            }

        when {
            contents?.gridRenderer != null -> {
                LibraryPage(
                    items =
                        contents.gridRenderer.items.orEmpty()
                            .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                            .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) },
                    continuation = contents.gridRenderer.continuations?.getContinuation(),
                )
            }

            contents?.musicShelfRenderer?.contents != null -> {
                LibraryPage(
                    items =
                        contents.musicShelfRenderer.contents
                            .mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                            .mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) },
                    continuation = contents.musicShelfRenderer.continuations?.getContinuation(),
                )
            }

            else -> {
                LibraryPage(
                    items = emptyList(),
                    continuation = null,
                )
            }
        }
    }

    suspend fun libraryContinuation(continuation: String) =
        runCatching {
            val response =
                innerTube.browse(
                    client = WEB_REMIX,
                    continuation = continuation,
                    setLogin = true,
                ).body<BrowseResponse>()

            val contents = response.continuationContents

            when {
                contents?.gridContinuation != null -> {
                    val items =
                        contents.gridContinuation.items
                            .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                            .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) }
                    LibraryContinuationPage(
                        items = items,
                        continuation = if (items.isEmpty()) null else contents.gridContinuation.continuations?.getContinuation(),
                    )
                }

                contents?.musicShelfContinuation?.contents != null -> {
                    val items =
                        contents.musicShelfContinuation.contents
                            .mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                            .mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) }
                    LibraryContinuationPage(
                        items = items,
                        continuation = if (items.isEmpty()) null else contents.musicShelfContinuation.continuations?.getContinuation(),
                    )
                }

                else -> {
                    LibraryContinuationPage(
                        items = emptyList(),
                        continuation = null,
                    )
                }
            }
        }

    suspend fun libraryRecentActivity(): Result<LibraryPage> =
        runCatching {
            val continuation = LibraryFilter.FILTER_RECENT_ACTIVITY.value

            val response =
                innerTube.browse(
                    client = WEB_REMIX,
                    continuation = continuation,
                    setLogin = true,
                ).body<BrowseResponse>()

            val gridItems =
                response.continuationContents?.sectionListContinuation?.contents?.firstOrNull()
                    ?.gridRenderer?.items

            if (gridItems == null) {
                return@runCatching LibraryPage(
                    items = emptyList(),
                    continuation = null,
                )
            }

            val items =
                gridItems.mapNotNull {
                    it.musicTwoRowItemRenderer?.let { renderer ->
                        LibraryPage.fromMusicTwoRowItemRenderer(renderer)
                    }
                }.toMutableList()

        /*
         * We need to fetch the artist page when accessing the library because it allows to have
         * a proper playEndpoint, which is needed to correctly report the playing indicator in
         * the home page.
         *
         * Despite this, we need to use the old thumbnail because it's the proper format for a
         * square picture, which is what we need.
         */
            items.forEachIndexed { index, item ->
                if (item is ArtistItem) {
                    artist(item.id).getOrNull()?.artist?.let { fetchedArtist ->
                        items[index] = fetchedArtist.copy(thumbnail = item.thumbnail)
                    }
                }
            }

            LibraryPage(
                items = items,
                continuation = null,
            )
        }

    suspend fun getChartsPage(
        countryCode: String? = null,
        continuation: String? = null,
    ): Result<ChartsPage> =
        runCatching {
            // Override the geolocation (gl) so YouTube Music returns charts for the
            // requested country instead of the device's default region.
            val localeOverride =
                countryCode
                    ?.takeIf { it.isNotBlank() }
                    ?.let { com.dezzy.music.innertube.models.YouTubeLocale(gl = it, hl = locale.hl) }
            val response =
                innerTube.browse(
                    client = WEB_REMIX,
                    browseId = "FEmusic_charts",
                    params = "ggMGCgQIgAQ%3D",
                    continuation = continuation,
                    localeOverride = localeOverride,
                ).body<BrowseResponse>()

            val sections = mutableListOf<ChartsPage.ChartSection>()

            response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.contents?.forEach { content ->

                    content.musicCarouselShelfRenderer?.let { renderer ->
                        val title =
                            renderer.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.firstOrNull()?.text
                                ?: return@forEach

                        val items =
                            renderer.contents.mapNotNull { item ->
                                when {
                                    item.musicResponsiveListItemRenderer != null ->
                                        convertToChartItem(item.musicResponsiveListItemRenderer)
                                    item.musicTwoRowItemRenderer != null ->
                                        convertMusicTwoRowItem(item.musicTwoRowItemRenderer)
                                    else -> null
                                }
                            }.filterNotNull()

                        if (items.isNotEmpty()) {
                            sections.add(
                                ChartsPage.ChartSection(
                                    title = title,
                                    items = items,
                                    chartType = determineChartType(title),
                                ),
                            )
                        }
                    }

                    content.gridRenderer?.let { renderer ->
                        val title =
                            renderer.header?.gridHeaderRenderer?.title?.runs?.firstOrNull()?.text
                                ?: return@let

                        val items =
                            renderer.items.mapNotNull { item ->
                                item.musicTwoRowItemRenderer?.let { renderer ->
                                    convertMusicTwoRowItem(renderer)
                                }
                            }.filterNotNull()

                        if (items.isNotEmpty()) {
                            sections.add(
                                ChartsPage.ChartSection(
                                    title = title,
                                    items = items,
                                    chartType = ChartsPage.ChartType.NEW_RELEASES,
                                ),
                            )
                        }
                    }
                }

            ChartsPage(
                sections = sections,
                continuation = response.continuationContents?.sectionListContinuation?.continuations?.getContinuation(),
            )
        }

    private fun determineChartType(title: String): ChartsPage.ChartType {
        return when {
            title.contains("Trending", ignoreCase = true) -> ChartsPage.ChartType.TRENDING
            title.contains("Top", ignoreCase = true) -> ChartsPage.ChartType.TOP
            else -> ChartsPage.ChartType.GENRE
        }
    }

    private fun convertToChartItem(renderer: MusicResponsiveListItemRenderer): YTItem? {
        return try {
            when {
                renderer.flexColumns.size >= 3 && renderer.playlistItemData?.videoId != null -> {
                    val firstColumn =
                        renderer.flexColumns.getOrNull(0)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text ?: return null

                    val secondColumn =
                        renderer.flexColumns.getOrNull(1)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text ?: return null

                    val titleRun = firstColumn.runs?.firstOrNull() ?: return null
                    val title = titleRun.text.takeIf { it.isNotBlank() } ?: return null

                    val artists =
                        secondColumn.runs?.mapNotNull { run ->
                            run.text.takeIf { it.isNotBlank() }?.let { name ->
                                Artist(
                                    name = name,
                                    id = run.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            }
                        } ?: emptyList()

                    val thirdColumn =
                        renderer.flexColumns.getOrNull(2)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text

                    SongItem(
                        id = renderer.playlistItemData.videoId,
                        title = title,
                        artists = artists,
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.badges?.any {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } == true,
                        chartPosition = thirdColumn?.runs?.firstOrNull()?.text?.toIntOrNull(),
                        chartChange = thirdColumn?.runs?.getOrNull(1)?.text,
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            println("Error converting chart item: ${e.message}\n${Json.encodeToString(renderer)}")
            null
        }
    }

    private fun convertMusicTwoRowItem(renderer: MusicTwoRowItemRenderer): YTItem? {
        return try {
            when {
                renderer.isSong -> {
                    val subtitle = renderer.subtitle?.runs ?: return null
                    SongItem(
                        id = renderer.navigationEndpoint.watchEndpoint?.videoId ?: return null,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        artists =
                            subtitle.mapNotNull {
                                it.navigationEndpoint?.browseEndpoint?.browseId?.let { id ->
                                    Artist(name = it.text, id = id)
                                }
                            },
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.subtitleBadges?.any {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } == true,
                    )
                }
                renderer.isAlbum -> {
                    AlbumItem(
                        browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                        playlistId =
                            renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                                ?.musicPlayButtonRenderer?.playNavigationEndpoint
                                ?.watchPlaylistEndpoint?.playlistId ?: return null,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        artists =
                            renderer.subtitle?.runs?.oddElements()?.drop(1)?.mapNotNull {
                                it.navigationEndpoint?.browseEndpoint?.browseId?.let { id ->
                                    Artist(name = it.text, id = id)
                                }
                            },
                        year = renderer.subtitle?.runs?.lastOrNull()?.text?.toIntOrNull(),
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.subtitleBadges?.any {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } == true,
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            println("Error converting two row item: ${e.message}\n${Json.encodeToString(renderer)}")
            null
        }
    }

    suspend fun musicHistory() =
        runCatching {
            val response =
                innerTube.browse(
                    client = WEB_REMIX,
                    browseId = "FEmusic_history",
                    setLogin = true,
                ).body<BrowseResponse>()

            HistoryPage(
                sections =
                    response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                        ?.tabRenderer?.content?.sectionListRenderer?.contents
                        ?.mapNotNull {
                            it.musicShelfRenderer?.let { musicShelfRenderer ->
                                HistoryPage.fromMusicShelfRenderer(musicShelfRenderer)
                            }
                        },
            )
        }

    suspend fun likeVideo(
        videoId: String,
        like: Boolean,
    ) = runCatching {
        if (like) {
            innerTube.likeVideo(WEB_REMIX, videoId)
        } else {
            innerTube.unlikeVideo(WEB_REMIX, videoId)
        }
    }

    suspend fun likePlaylist(
        playlistId: String,
        like: Boolean,
    ) = runCatching {
        if (like) {
            innerTube.likePlaylist(WEB_REMIX, playlistId)
        } else {
            innerTube.unlikePlaylist(WEB_REMIX, playlistId)
        }
    }

    suspend fun subscribeChannel(
        channelId: String,
        subscribe: Boolean,
    ) = runCatching {
        if (subscribe) {
            innerTube.subscribeChannel(WEB_REMIX, channelId)
        } else {
            innerTube.unsubscribeChannel(WEB_REMIX, channelId)
        }
    }

    suspend fun getChannelId(browseId: String): String {
        artist(browseId).onSuccess {
            return it.artist.channelId ?: ""
        }
        return ""
    }

    suspend fun addToPlaylist(
        playlistId: String,
        videoId: String,
    ) = runCatching {
        innerTube
            .addToPlaylist(WEB_REMIX, playlistId, videoId)
            .body<AddItemYouTubePlaylistResponse>()
            .playlistEditResults
            .firstOrNull()
            ?.playlistEditVideoAddedResultData
            ?.setVideoId
    }

    suspend fun addPlaylistToPlaylist(
        playlistId: String,
        addPlaylistId: String,
    ) = runCatching {
        innerTube.addPlaylistToPlaylist(WEB_REMIX, playlistId, addPlaylistId)
    }

    suspend fun playlistEntrySetVideoIds(
        playlistId: String,
        videoId: String,
    ) = runCatching {
        val setVideoIds = mutableListOf<String>()

        fun collectSetVideoIds(songs: List<SongItem>) {
            setVideoIds +=
                songs
                    .asSequence()
                    .filter { song -> song.id == videoId }
                    .mapNotNull(SongItem::setVideoId)
                    .toList()
        }

        val playlistPage = playlist(playlistId).getOrThrow()
        collectSetVideoIds(playlistPage.songs)

        var continuation =
            playlistPage.songsContinuation?.takeUnless(String::isBlank)
                ?: playlistPage.continuation?.takeUnless(String::isBlank)
        while (continuation != null) {
            val continuationPage = playlistContinuation(continuation).getOrThrow()
            collectSetVideoIds(continuationPage.songs)
            continuation = continuationPage.continuation?.takeUnless(String::isBlank)
        }

        setVideoIds.distinct()
    }

    suspend fun removeFromPlaylist(
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ) = runCatching {
        innerTube.removeFromPlaylist(WEB_REMIX, playlistId, videoId, setVideoId)
    }

    suspend fun moveSongPlaylist(
        playlistId: String,
        setVideoId: String,
        successorSetVideoId: String?,
    ) = runCatching {
        innerTube.moveSongPlaylist(WEB_REMIX, playlistId, setVideoId, successorSetVideoId)
    }

    suspend fun createPlaylist(title: String) =
        runCatching {
            innerTube.createPlaylist(WEB_REMIX, title).body<CreatePlaylistResponse>().playlistId
        }

    suspend fun renamePlaylist(
        playlistId: String,
        name: String,
    ) = runCatching {
        innerTube.renamePlaylist(WEB_REMIX, playlistId, name)
    }

    suspend fun deletePlaylist(playlistId: String) =
        runCatching {
            innerTube.deletePlaylist(WEB_REMIX, playlistId)
        }

    suspend fun player(
        videoId: String,
        playlistId: String? = null,
        client: YouTubeClient,
        signatureTimestamp: Int? = null,
        poToken: String? = null,
        setLogin: Boolean = true,
        authState: PlaybackAuthState = currentPlaybackAuthState(),
    ): Result<PlayerResponse> =
        runCatching {
            // For web clients that require service integrity, mint a fresh per-video player poToken
            // through the app-module BotGuard bridge if no explicit token was supplied. Non-web
            // clients (ANDROID_VR, IOS, ...) do not use this token and skip the WebView round-trip.
            val explicitPlayerToken =
                poToken ?: run {
                    val minter = poTokenMinter
                    if (minter != null &&
                        authState.webClientPoTokenEnabled &&
                        PlaybackAuthState.needsServiceIntegrity(client)
                    ) {
                        runCatching { minter.invoke(videoId) }.getOrNull()
                    } else {
                        null
                    }
                }
            val resolvedPoToken = resolvePlayerPoToken(client, explicitPlayerToken, authState)
            innerTube.player(
                client = client,
                videoId = videoId,
                playlistId = playlistId,
                signatureTimestamp = signatureTimestamp,
                poToken = resolvedPoToken,
                setLogin = setLogin,
                authState = authState,
            ).body<PlayerResponse>()
        }

    suspend fun registerPlayback(
        playlistId: String? = null,
        playbackTracking: String,
        authState: PlaybackAuthState = currentPlaybackAuthState(),
    ) = runCatching {
        val cpn =
            (1..16).map {
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"[
                    Random.Default.nextInt(
                        0,
                        64,
                    ),
                ]
            }.joinToString("")

        val playbackUrl =
            playbackTracking.replace(
                "https://s.youtube.com",
                "https://music.youtube.com",
            )

        innerTube.registerPlayback(
            url = playbackUrl,
            playlistId = playlistId,
            cpn = cpn,
            poToken = resolveGvsPoToken(authState),
            authState = authState,
        )
    }

    suspend fun next(
        endpoint: WatchEndpoint,
        continuation: String? = null,
        followAutomixPreview: Boolean = true,
    ): Result<NextResult> =
        runCatching {
            val response =
                innerTube.next(
                    WEB_REMIX,
                    endpoint.videoId,
                    endpoint.playlistId,
                    endpoint.playlistSetVideoId,
                    endpoint.index,
                    endpoint.params,
                    continuation,
                ).body<NextResponse>()
            val playlistPanelRenderer =
                response.continuationContents?.playlistPanelContinuation
                    ?: response.contents.singleColumnMusicWatchNextResultsRenderer?.tabbedRenderer
                        ?.watchNextTabbedResultsRenderer?.tabs?.get(0)?.tabRenderer?.content?.musicQueueRenderer
                        ?.content?.playlistPanelRenderer!!
            val title =
                response.contents.singleColumnMusicWatchNextResultsRenderer?.tabbedRenderer
                    ?.watchNextTabbedResultsRenderer?.tabs?.get(0)?.tabRenderer?.content?.musicQueueRenderer
                    ?.header?.musicQueueHeaderRenderer?.subtitle?.runs?.firstOrNull()?.text
            val items =
                playlistPanelRenderer.contents.mapNotNull { content ->
                    content.playlistPanelVideoRenderer
                        ?.let(NextPage::fromPlaylistPanelVideoRenderer)
                        ?.let { it to content.playlistPanelVideoRenderer.selected }
                }
            val songs = items.map { it.first }
            val currentIndex = items.indexOfFirst { it.second }.takeIf { it != -1 }

            val commentsContinuationToken =
                response.engagementPanels
                    ?.firstOrNull { it.engagementPanelSectionListRenderer?.panelIdentifier == "engagement-panel-comments" }
                    ?.engagementPanelSectionListRenderer
                    ?.content
                    ?.yContinuationComponent
                    ?.nextContinuationData
                    ?.continuation

            if (followAutomixPreview) {
                // Keep automix opt-in so ordered playlist queues can page through their own continuation first.
                playlistPanelRenderer.contents.lastOrNull()?.automixPreviewVideoRenderer?.content?.automixPlaylistVideoRenderer?.navigationEndpoint?.watchPlaylistEndpoint?.let {
                        watchPlaylistEndpoint ->
                    return@runCatching next(watchPlaylistEndpoint).getOrThrow().let { result ->
                        result.copy(
                            title = title,
                            items = songs + result.items,
                            lyricsEndpoint =
                                response.contents.singleColumnMusicWatchNextResultsRenderer?.tabbedRenderer?.watchNextTabbedResultsRenderer?.tabs?.getOrNull(
                                    1,
                                )?.tabRenderer?.endpoint?.browseEndpoint,
                            relatedEndpoint =
                                response.contents.singleColumnMusicWatchNextResultsRenderer?.tabbedRenderer?.watchNextTabbedResultsRenderer?.tabs?.getOrNull(
                                    2,
                                )?.tabRenderer?.endpoint?.browseEndpoint,
                            currentIndex = currentIndex,
                            endpoint = watchPlaylistEndpoint,
                            commentsContinuation = commentsContinuationToken ?: result.commentsContinuation,
                        )
                    }
                }
            }
            NextResult(
                title = title,
                items = songs,
                currentIndex = currentIndex,
                lyricsEndpoint =
                    response.contents.singleColumnMusicWatchNextResultsRenderer?.tabbedRenderer?.watchNextTabbedResultsRenderer?.tabs?.getOrNull(
                        1,
                    )?.tabRenderer?.endpoint?.browseEndpoint,
                relatedEndpoint =
                    response.contents.singleColumnMusicWatchNextResultsRenderer?.tabbedRenderer?.watchNextTabbedResultsRenderer?.tabs?.getOrNull(
                        2,
                    )?.tabRenderer?.endpoint?.browseEndpoint,
                continuation = playlistPanelRenderer.continuations?.getContinuation(),
                endpoint = endpoint,
                commentsContinuation = commentsContinuationToken,
            )
        }

    suspend fun lyrics(endpoint: BrowseEndpoint): Result<String?> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, endpoint.browseId, endpoint.params).body<BrowseResponse>()
            response.contents?.sectionListRenderer?.contents?.firstOrNull()?.musicDescriptionShelfRenderer?.description?.runs?.firstOrNull()?.text
        }

    suspend fun related(endpoint: BrowseEndpoint): Result<RelatedPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, endpoint.browseId).body<BrowseResponse>()
            val songs = mutableListOf<SongItem>()
            val albums = mutableListOf<AlbumItem>()
            val artists = mutableListOf<ArtistItem>()
            val playlists = mutableListOf<PlaylistItem>()
            response.contents?.sectionListRenderer?.contents?.forEach { sectionContent ->
                sectionContent.musicCarouselShelfRenderer?.contents?.forEach { content ->
                    when (
                        val item =
                            content.musicResponsiveListItemRenderer?.let(RelatedPage.Companion::fromMusicResponsiveListItemRenderer)
                                ?: content.musicTwoRowItemRenderer?.let(RelatedPage.Companion::fromMusicTwoRowItemRenderer)
                    ) {
                        is SongItem ->
                            if (content.musicResponsiveListItemRenderer?.overlay
                                    ?.musicItemThumbnailOverlayRenderer?.content
                                    ?.musicPlayButtonRenderer?.playNavigationEndpoint
                                    ?.watchEndpoint?.watchEndpointMusicSupportedConfigs
                                    ?.watchEndpointMusicConfig?.musicVideoType == MUSIC_VIDEO_TYPE_ATV
                            ) {
                                songs.add(item)
                            }

                        is AlbumItem -> albums.add(item)
                        is ArtistItem -> artists.add(item)
                        is PlaylistItem -> playlists.add(item)
                        null -> {}
                    }
                }
            }
            RelatedPage(songs, albums, artists, playlists)
        }

    suspend fun queue(
        videoIds: List<String>? = null,
        playlistId: String? = null,
    ): Result<List<SongItem>> =
        runCatching {
            if (videoIds != null) {
                assert(videoIds.size <= MAX_GET_QUEUE_SIZE) // Max video limit
            }
            innerTube.getQueue(WEB_REMIX, videoIds, playlistId).body<GetQueueResponse>().queueDatas
                .mapNotNull {
                    it.content.playlistPanelVideoRenderer?.let { renderer ->
                        NextPage.fromPlaylistPanelVideoRenderer(renderer)
                    }
                }
        }

    suspend fun transcript(videoId: String): Result<String> =
        runCatching {
            val response = innerTube.getTranscript(WEB, videoId).body<GetTranscriptResponse>()
            response.actions?.firstOrNull()?.updateEngagementPanelAction?.content?.transcriptRenderer?.body?.transcriptBodyRenderer?.cueGroups?.joinToString(
                separator = "\n",
            ) { group ->
                val time = group.transcriptCueGroupRenderer.cues[0].transcriptCueRenderer.startOffsetMs
                val text =
                    group.transcriptCueGroupRenderer.cues[0].transcriptCueRenderer.cue.simpleText
                        .trim('♪')
                        .trim(' ')
                "[%02d:%02d.%03d]$text".format(time / 60000, (time / 1000) % 60, time % 1000)
            }!!
        }

    suspend fun visitorData(): Result<String> =
        runCatching {
            Json.parseToJsonElement(innerTube.getSwJsData().bodyAsText().substring(5))
                .jsonArray[0]
                .jsonArray[2]
                .jsonArray.first {
                    (it as? JsonPrimitive)?.contentOrNull?.let { candidate ->
                        VISITOR_DATA_REGEX.containsMatchIn(candidate)
                    } ?: false
                }
                .jsonPrimitive.content
        }

    suspend fun accountInfo(): Result<AccountInfo> =
        runCatching {
            val response = innerTube.accountMenu(WEB_REMIX).body<AccountMenuResponse>()
            val accountInfo =
                response.actions.firstOrNull()
                    ?.openPopupAction?.popup?.multiPageMenuRenderer
                    ?.header?.activeAccountHeaderRenderer
                    ?.toAccountInfo()
            accountInfo ?: throw IllegalStateException("Failed to get account info - user may not be logged in")
        }

    suspend fun getMediaInfo(videoId: String): Result<MediaInfo> =
        runCatching {
            return innerTube.getMediaInfo(videoId)
        }

    @JvmInline
    value class SearchFilter(val value: String) {
        companion object {
            val FILTER_SONG = SearchFilter("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
            val FILTER_VIDEO = SearchFilter("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ALBUM = SearchFilter("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ARTIST = SearchFilter("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_FEATURED_PLAYLIST = SearchFilter("EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D")
            val FILTER_COMMUNITY_PLAYLIST = SearchFilter("EgeKAQQoAEABagoQAxAEEAoQCRAF")
        }
    }

    @JvmInline
    value class LibraryFilter(val value: String) {
        companion object {
            val FILTER_RECENT_ACTIVITY = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpYnJhcnlfbGFuZGluZxoQZ2dNR0tnUUlCaEFCb0FZQg%3D%3D")
            val FILTER_RECENTLY_PLAYED = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpYnJhcnlfbGFuZGluZxoQZ2dNR0tnUUlCUkFCb0FZQg%3D%3D")
            val FILTER_PLAYLISTS_ALPHABETICAL = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBUkFBb0FZQg%3D%3D")
            val FILTER_PLAYLISTS_RECENTLY_SAVED = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBQkFCb0FZQg%3D%3D")
        }
    }

    suspend fun getVideoCommentsContinuation(videoId: String): Result<String?> =
        runCatching {
            println("DezzyDebug: getVideoCommentsContinuation starting for videoId=$videoId")
            // Use nextYouTube() so the request goes to www.youtube.com (not music.youtube.com)
            // which is the only host that returns engagement panels with comment continuation tokens.
            val response =
                innerTube.nextYouTube(
                    client = YouTubeClient.WEB,
                    videoId = videoId,
                    playlistId = null,
                    playlistSetVideoId = null,
                    index = null,
                    params = null,
                    continuation = null,
                    setLogin = true,
                ).body<NextResponse>()
            println("DezzyDebug: getVideoCommentsContinuation got response panels=${response.engagementPanels?.size}")

            val panels = response.engagementPanels

            val commentsPanel =
                panels?.firstOrNull {
                    it.engagementPanelSectionListRenderer?.panelIdentifier?.startsWith("engagement-panel-comments") == true
                }?.engagementPanelSectionListRenderer
            println("DezzyDebug: commentsPanel found=${commentsPanel != null}, identifier=${commentsPanel?.panelIdentifier}")

            // Path 1: continuationItemRenderer inside contents of itemSectionRenderer (WEB client standard path)
            val token1 =
                commentsPanel?.content?.sectionListRenderer?.contents?.firstOrNull()
                    ?.itemSectionRenderer?.contents?.firstOrNull()
                    ?.continuationItemRenderer?.continuationEndpoint?.continuationCommand?.token
            println("DezzyDebug: token1=$token1")

            // Path 2: continuations list under itemSectionRenderer (fallback path)
            val token2 =
                commentsPanel?.content?.sectionListRenderer?.contents?.firstOrNull()
                    ?.itemSectionRenderer?.continuations?.firstOrNull()
                    ?.let { c -> c.nextContinuationData?.continuation ?: c.reloadContinuationData?.continuation }
            println("DezzyDebug: token2=$token2")

            // Path 3: yContinuationComponent (YouTube Music legacy path)
            val token3 = commentsPanel?.content?.yContinuationComponent?.nextContinuationData?.continuation
            println("DezzyDebug: token3=$token3")

            val finalToken = token1 ?: token2 ?: token3
            println("DezzyDebug: finalToken=$finalToken")
            finalToken
        }.onFailure {
            println("DezzyDebug: getVideoCommentsContinuation failed with exception: ${it.message}")
            it.printStackTrace()
        }

    /**
     * Resumen de comentarios REAL de un video (datos de YouTube, no de Dezzy):
     * el conteo total tal como lo muestra YouTube Music ("254 K") y el comentario
     * destacado (autor + texto + avatar). Es lo que alimenta la tarjeta de
     * "pistas más comentadas".
     *
     * Devuelve null si el video no tiene comentarios o están desactivados.
     */
    suspend fun getVideoCommentSummary(videoId: String): Result<VideoCommentSummary?> =
        runCatching {
            val response =
                innerTube.nextYouTube(
                    client = YouTubeClient.WEB,
                    videoId = videoId,
                    playlistId = null,
                    playlistSetVideoId = null,
                    index = null,
                    params = null,
                    continuation = null,
                    setLogin = true,
                ).body<NextResponse>()

            val commentsPanel =
                response.engagementPanels?.firstOrNull {
                    it.engagementPanelSectionListRenderer?.panelIdentifier?.startsWith("engagement-panel-comments") == true
                }?.engagementPanelSectionListRenderer ?: return@runCatching null

            // Conteo total desde la cabecera del panel ("254 K", "1.2 M", "1,234").
            val countText =
                commentsPanel.header
                    ?.engagementPanelTitleHeaderRenderer
                    ?.contextualInfo
                    ?.runs
                    ?.firstOrNull()
                    ?.text
            val count = parseCompactCount(countText)

            // Token para traer la primera página y sacar el comentario destacado.
            val token =
                commentsPanel.content?.sectionListRenderer?.contents?.firstOrNull()
                    ?.itemSectionRenderer?.contents?.firstOrNull()
                    ?.continuationItemRenderer?.continuationEndpoint?.continuationCommand?.token
                    ?: commentsPanel.content?.sectionListRenderer?.contents?.firstOrNull()
                        ?.itemSectionRenderer?.continuations?.firstOrNull()
                        ?.let { it.nextContinuationData?.continuation ?: it.reloadContinuationData?.continuation }
                    ?: commentsPanel.content?.yContinuationComponent?.nextContinuationData?.continuation

            val top =
                token?.let { t ->
                    getComments(t).getOrNull()?.items?.firstOrNull()
                        ?.commentThreadRenderer?.comment?.commentRenderer
                }

            if (top == null && count == null) return@runCatching null

            VideoCommentSummary(
                videoId = videoId,
                commentCount = count,
                topCommentAuthor = top?.authorName(),
                topCommentText = top?.body(),
                topCommentAvatarUrl = top?.authorThumbnail?.bestUrl(),
            )
        }

    /**
     * Convierte "254 K" / "1.2 M" / "866" / "1,234" en número. YouTube separa el
     * número del sufijo con un espacio DURO (NBSP, "56 K"), que `\s` no
     * captura: por eso se quitan primero TODOS los espacios (normales, NBSP y
     * finos) antes de parsear, o "56 K" se leería como 56.
     */
    private fun parseCompactCount(raw: String?): Long? {
        val s = raw?.replace(Regex("[\\s\\u00A0\\u202F]"), "")?.takeIf { it.isNotBlank() } ?: return null
        val match = Regex("([0-9][0-9.,]*)([KMB])?", RegexOption.IGNORE_CASE).find(s) ?: return null
        val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val multiplier =
            when (match.groupValues[2].uppercase()) {
                "K" -> 1_000
                "M" -> 1_000_000
                "B" -> 1_000_000_000
                else -> 1
            }
        return (number * multiplier).toLong()
    }

    suspend fun getComments(continuation: String): Result<CommentsResult> =
        runCatching {
            println("DezzyDebug: getComments starting for token=$continuation")
            // Must use nextYouTube() so requests go to www.youtube.com/youtubei/v1/next
            // Comment continuation tokens are only valid on YouTube, not YouTube Music.
            val response =
                innerTube.nextYouTube(
                    client = YouTubeClient.WEB,
                    videoId = null,
                    playlistId = null,
                    playlistSetVideoId = null,
                    index = null,
                    params = null,
                    continuation = continuation,
                    setLogin = true,
                ).body<CommentResponse>()
            println(
                "DezzyDebug: getComments got response endpoints=${response.onResponseReceivedEndpoints?.size}, continuationContents=${response.continuationContents != null}",
            )

            val result = extractComments(response)
            println("DezzyDebug: getComments extracted items=${result.items.size}, nextContinuation=${result.continuation}")
            result
        }.onFailure {
            println("DezzyDebug: getComments failed with exception: ${it.message}")
            it.printStackTrace()
        }

    suspend fun getCommentReplies(continuation: String): Result<CommentsResult> =
        runCatching {
            val response =
                innerTube.nextYouTube(
                    client = WEB,
                    videoId = null,
                    playlistId = null,
                    playlistSetVideoId = null,
                    index = null,
                    params = null,
                    continuation = continuation,
                ).body<CommentResponse>()

            extractComments(response)
        }

    suspend fun postComment(
        commentText: String,
        createCommentParams: String? = null,
    ): Result<Boolean> =
        runCatching {
            val response =
                innerTube.createComment(
                    client = WEB,
                    commentText = commentText,
                    createCommentParams = createCommentParams,
                )
            response.status.isSuccess()
        }

    private fun extractComments(response: CommentResponse): CommentsResult {
        val endpoints = response.onResponseReceivedEndpoints
        val endpointItems =
            endpoints?.flatMap { endpoint ->
                val command = endpoint.reloadContinuationItemsCommand ?: endpoint.appendContinuationItemsAction
                command?.continuationItems ?: emptyList()
            } ?: emptyList()

        val comments =
            if (endpointItems.isNotEmpty()) {
                endpointItems
            } else {
                response.continuationContents?.itemSectionContinuation?.contents
                    ?: response.continuationContents?.commentRepliesContinuation?.contents
                    ?: emptyList()
            }

        val nextContinuation =
            if (endpointItems.isNotEmpty()) {
                endpointItems.firstOrNull { it.continuationItemRenderer != null }
                    ?.continuationItemRenderer?.continuationEndpoint?.continuationCommand?.token
            } else {
                val continuations =
                    response.continuationContents?.itemSectionContinuation?.continuations
                        ?: response.continuationContents?.commentRepliesContinuation?.continuations
                continuations?.firstOrNull { it.nextContinuationData != null || it.reloadContinuationData != null }
                    ?.let { it.nextContinuationData?.continuation ?: it.reloadContinuationData?.continuation }
            }

        // Modern WEB responses carry the actual comment data as commentEntityPayload mutations,
        // with each thread holding only a commentViewModel reference. Hydrate those references
        // into classic CommentRenderers so the UI keeps working with real-world data.
        val payloadById =
            response.frameworkUpdates?.entityBatchUpdate?.mutations
                ?.mapNotNull { it.payload?.commentEntityPayload }
                ?.mapNotNull { payload -> payload.properties?.commentId?.let { id -> id to payload } }
                ?.toMap()
                .orEmpty()

        fun hydrate(thread: CommentResponse.CommentThread): CommentResponse.CommentThread {
            val threadRenderer = thread.commentThreadRenderer ?: return thread
            if (threadRenderer.comment?.commentRenderer != null) return thread
            val id = threadRenderer.commentViewModel?.commentViewModel?.commentId ?: return thread
            val payload = payloadById[id] ?: return thread
            val renderer =
                CommentRenderer(
                    authorText = CommentRenderer.TextRuns(simpleText = payload.author?.displayName),
                    authorThumbnail =
                        CommentRenderer.AuthorThumbnail(
                            thumbnails =
                                payload.author?.avatarThumbnailUrl?.let { url ->
                                    listOf(CommentRenderer.AuthorThumbnail.Thumbnail(url = url, width = 88, height = 88))
                                },
                        ),
                    contentText = CommentRenderer.TextRuns(simpleText = payload.properties?.content?.content),
                    publishedTimeText = CommentRenderer.TextRuns(simpleText = payload.properties?.publishedTime),
                    voteCount =
                        CommentRenderer.TextRuns(
                            simpleText = payload.toolbar?.likeCountNotliked ?: payload.toolbar?.likeCountLiked,
                        ),
                    authorIsChannelOwner = payload.author?.isCreator ?: false,
                )
            return thread.copy(
                commentThreadRenderer =
                    threadRenderer.copy(
                        comment = CommentResponse.CommentThreadRenderer.Comment(commentRenderer = renderer),
                    ),
            )
        }

        val hydrated = comments.map(::hydrate)

        return CommentsResult(
            // Keep only threads the UI can actually render — referencia-only threads whose
            // payload was missing used to show up as blank rows.
            items =
                hydrated.filter {
                    it.commentThreadRenderer?.comment?.commentRenderer != null || it.commentRenderer != null
                },
            continuation = nextContinuation,
        )
    }

    const val MAX_GET_QUEUE_SIZE = 1000

    private val VISITOR_DATA_REGEX = Regex("^Cg[t|s]")
}

internal fun playlistContinuationPageFromResponse(response: BrowseResponse): PlaylistContinuationPage {
    val appendedContents =
        response.onResponseReceivedActions
            ?.firstOrNull()
            ?.appendContinuationItemsAction
            ?.continuationItems
            .orEmpty()

    val candidates =
        listOf(
            PlaylistContinuationCandidate(
                contents =
                    buildList {
                        response.continuationContents?.sectionListContinuation?.contents
                            .orEmpty()
                            .forEach { sectionContent ->
                                addAll(sectionContent.musicPlaylistShelfRenderer?.contents.orEmpty())
                            }
                        addAll(appendedContents)
                    },
                continuation =
                    response.continuationContents
                        ?.sectionListContinuation
                        ?.continuations
                        ?.getContinuation()
                        ?: appendedContents.getContinuation(),
            ),
            PlaylistContinuationCandidate(
                contents =
                    response.continuationContents
                        ?.musicPlaylistShelfContinuation
                        ?.contents
                        .orEmpty(),
                continuation =
                    response.continuationContents
                        ?.musicPlaylistShelfContinuation
                        ?.continuations
                        ?.getContinuation(),
            ),
            PlaylistContinuationCandidate(
                contents =
                    response.continuationContents
                        ?.musicShelfContinuation
                        ?.contents
                        .orEmpty(),
                continuation =
                    response.continuationContents
                        ?.musicShelfContinuation
                        ?.continuations
                        ?.getContinuation(),
            ),
            PlaylistContinuationCandidate(
                contents = appendedContents,
                continuation = appendedContents.getContinuation(),
            ),
        ).map { candidate ->
            candidate.copy(
                songs =
                    candidate.contents
                        .mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                        .mapNotNull(PlaylistPage::fromMusicResponsiveListItemRenderer),
            )
        }

    val selectedCandidate =
        candidates.firstOrNull { it.songs.isNotEmpty() }
            ?: candidates.firstOrNull { it.contents.isNotEmpty() }

    return PlaylistContinuationPage(
        songs = selectedCandidate?.songs.orEmpty(),
        continuation = selectedCandidate?.continuation?.takeUnless(String::isBlank),
    )
}

private data class PlaylistContinuationCandidate(
    val contents: List<MusicShelfRenderer.Content>,
    val continuation: String?,
    val songs: List<SongItem> = emptyList(),
)
