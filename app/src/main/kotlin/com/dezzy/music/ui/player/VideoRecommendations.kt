/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.player

import android.view.LayoutInflater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dezzy.music.R
import com.dezzy.music.constants.VideoResizeMode
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.dezzy.music.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_UGC
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Filtros de los videos recomendados (chips de "Solo videos"). */
enum class VideoRecoFilter(val label: String) {
    FOR_SONG("Para esta canción"),
    OFFICIAL("Oficiales"),
    ARTIST("Del artista"),
}

internal fun SongItem.musicVideoType(): String? =
    endpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType

/** Video de verdad (oficial o subido), no un Art Track de audio con portada fija. */
internal fun SongItem.isVideo(): Boolean = musicVideoType().let { it == MUSIC_VIDEO_TYPE_OMV || it == MUSIC_VIDEO_TYPE_UGC }

internal fun SongItem.isOfficialVideo(): Boolean = musicVideoType() == MUSIC_VIDEO_TYPE_OMV

/** Miniatura 16:9 del video (hqdefault trae bandas negras que el recorte 16:9 quita). */
internal fun videoThumb(id: String): String = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

internal fun VideoResizeMode.toPlayerViewMode(): Int = when (this) {
    VideoResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoResizeMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    VideoResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}

/**
 * Carga los videos recomendados para la canción actual y los mantiene en estado de Compose.
 * Lo comparten el carrusel del modo video y la pantalla "Solo videos", así que cambiar de
 * una a otra no vuelve a pedir nada a la red. Guarda una caché corta por (video, filtro).
 */
@Stable
class VideoRecommendations(private val scope: CoroutineScope) {
    val items = mutableStateListOf<SongItem>()
    var loading by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    private var key: String? = null
    private var filter: VideoRecoFilter = VideoRecoFilter.FOR_SONG
    private var currentId: String? = null
    private var continuation: String? = null
    private var radioEndpoint: WatchEndpoint? = null
    private var job: Job? = null

    private class Snapshot(
        val items: List<SongItem>,
        val continuation: String?,
        val radioEndpoint: WatchEndpoint?,
    )

    private val cache = object : LinkedHashMap<String, Snapshot>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>?) = size > 8
    }

    fun load(videoId: String, title: String, artist: String, filter: VideoRecoFilter) {
        val newKey = "$videoId|${filter.name}"
        if (newKey == key && (items.isNotEmpty() || loading)) return
        saveSnapshot()
        job?.cancel()
        key = newKey
        this.filter = filter
        currentId = videoId
        failed = false
        loadingMore = false

        cache[newKey]?.let { snap ->
            items.clear()
            items.addAll(snap.items)
            continuation = snap.continuation
            radioEndpoint = snap.radioEndpoint
            loading = false
            return
        }

        items.clear()
        continuation = null
        radioEndpoint = null
        loading = true
        job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { fetchFirstPage(videoId, title, artist, filter) }.getOrNull()
            }
            if (key != newKey) return@launch
            loading = false
            if (result == null) {
                failed = true
                return@launch
            }
            items.addAll(result.items)
            continuation = result.continuation
            radioEndpoint = result.radioEndpoint
            saveSnapshot()
        }
    }

    /** Siguiente página (radio o búsqueda). Prueba hasta 3 páginas para traer algo nuevo. */
    fun loadMore() {
        if (loading || loadingMore || continuation == null) return
        val requestKey = key ?: return
        loadingMore = true
        job = scope.launch {
            var added = 0
            var attempts = 0
            while (added == 0 && continuation != null && attempts < 3 && key == requestKey) {
                attempts++
                val token = continuation ?: break
                val page = withContext(Dispatchers.IO) {
                    runCatching { fetchNextPage(token) }.getOrNull()
                }
                if (key != requestKey) return@launch
                if (page == null) {
                    continuation = null
                    break
                }
                val known = items.mapTo(HashSet()) { it.id }
                val fresh = page.items.filter { it.id != currentId && known.add(it.id) }
                items.addAll(fresh)
                added += fresh.size
                continuation = page.continuation.takeIf { it != token }
                page.radioEndpoint?.let { radioEndpoint = it }
            }
            if (key == requestKey) {
                loadingMore = false
                saveSnapshot()
            }
        }
    }

    fun retry(videoId: String, title: String, artist: String, filter: VideoRecoFilter) {
        key?.let { cache.remove(it) }
        key = null
        load(videoId, title, artist, filter)
    }

    private fun saveSnapshot() {
        val k = key ?: return
        if (loading || items.isEmpty()) return
        cache[k] = Snapshot(items.toList(), continuation, radioEndpoint)
    }

    private class Page(
        val items: List<SongItem>,
        val continuation: String?,
        val radioEndpoint: WatchEndpoint? = null,
    )

    private suspend fun fetchFirstPage(
        videoId: String,
        title: String,
        artist: String,
        filter: VideoRecoFilter,
    ): Page = coroutineScope {
        when (filter) {
            VideoRecoFilter.ARTIST -> {
                val result = YouTube.search(artist.ifBlank { title }, YouTube.SearchFilter.FILTER_VIDEO).getOrThrow()
                Page(
                    items = result.items.filterIsInstance<SongItem>().filter { it.id != videoId }.distinctBy { it.id },
                    continuation = result.continuation,
                )
            }

            VideoRecoFilter.FOR_SONG, VideoRecoFilter.OFFICIAL -> {
                val radioDeferred = async { runCatching { YouTube.next(WatchEndpoint(videoId = videoId)).getOrThrow() }.getOrNull() }
                val searchDeferred = async {
                    runCatching {
                        YouTube.search("$title $artist".trim(), YouTube.SearchFilter.FILTER_VIDEO).getOrThrow()
                    }.getOrNull()
                }
                val radio = radioDeferred.await()
                val search = searchDeferred.await()
                if (radio == null && search == null) error("Sin recomendaciones")

                val keep = videoFilter(filter)
                val currentTitle = normalizeTitle(title)
                val radioVideos = radio?.items.orEmpty().filter { it.id != videoId && keep(it) }
                val searchVideos = search?.items.orEmpty()
                    .filterIsInstance<SongItem>()
                    .filter { it.id != videoId && (filter != VideoRecoFilter.OFFICIAL || it.isOfficialVideo()) }
                    // El videoclip oficial de la propia canción ya es lo que se está viendo.
                    .filterNot { currentTitle.isNotEmpty() && normalizeTitle(it.title) == currentTitle && it.isOfficialVideo() }

                val merged = (radioVideos.take(2) + searchVideos.take(4) + radioVideos.drop(2))
                    .distinctBy { it.id }
                Page(
                    items = merged,
                    continuation = radio?.continuation,
                    radioEndpoint = radio?.endpoint,
                )
            }
        }
    }

    private suspend fun fetchNextPage(token: String): Page = when (filter) {
        VideoRecoFilter.ARTIST -> {
            val result = YouTube.searchContinuation(token).getOrThrow()
            Page(result.items.filterIsInstance<SongItem>(), result.continuation)
        }

        VideoRecoFilter.FOR_SONG, VideoRecoFilter.OFFICIAL -> {
            val endpoint = radioEndpoint ?: WatchEndpoint(videoId = currentId)
            val result = YouTube.next(endpoint, token).getOrThrow()
            val keep = videoFilter(filter)
            Page(result.items.filter(keep), result.continuation, result.endpoint)
        }
    }

    private companion object {
        private val OfficialTag = Regex(
            """[(\[][^)\]]*(official|oficial|video|vídeo|audio|visualizer|\bmv\b|\bhd\b|4k)[^)\]]*[)\]]""",
            RegexOption.IGNORE_CASE,
        )

        fun videoFilter(filter: VideoRecoFilter): (SongItem) -> Boolean {
            val officialOnly = filter == VideoRecoFilter.OFFICIAL
            return { item -> if (officialOnly) item.isOfficialVideo() else item.isVideo() }
        }

        fun normalizeTitle(title: String): String =
            title.replace(OfficialTag, "")
                .lowercase()
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
    }
}

/**
 * Superficie de video (PlayerView con TextureView, sin controlador). Solo se engancha al
 * reproductor mientras [active]: así dos superficies (la cabecera de "Solo videos" y su
 * ventanita flotante, o el video y la pantalla completa) se pasan el video sin pelearse.
 */
@Composable
internal fun VideoSurface(
    player: ExoPlayer?,
    active: Boolean,
    modifier: Modifier = Modifier,
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
) {
    AndroidView(
        factory = { ctx ->
            LayoutInflater.from(ctx).inflate(R.layout.video_player_view, null, false) as PlayerView
        },
        update = { view ->
            val target = if (active) player else null
            if (view.player !== target) view.player = target
            if (view.resizeMode != resizeMode) view.resizeMode = resizeMode
        },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

/**
 * Gira el contenido 90° y le intercambia ancho y alto: la pantalla completa en horizontal sin
 * rotar la Activity (rotarla la recrearía y los skins Spotify/Deezer fuerzan el vertical).
 */
internal fun Modifier.rotateQuarterTurn(enabled: Boolean): Modifier =
    if (!enabled) {
        this
    } else {
        this
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth,
                    ),
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(
                        x = (placeable.height - placeable.width) / 2,
                        y = (placeable.width - placeable.height) / 2,
                    )
                }
            }
            .graphicsLayer { rotationZ = 90f }
    }
