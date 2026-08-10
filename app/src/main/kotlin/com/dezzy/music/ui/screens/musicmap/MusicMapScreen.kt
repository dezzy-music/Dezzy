/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.musicmap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.constants.MusicMapShareLocationKey
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.social.SupabaseAuth
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.innertube.pages.ChartsPage
import com.dezzy.music.models.toMediaMetadata
import com.dezzy.music.musicmap.MapPresence
import com.dezzy.music.musicmap.MusicMapRepository
import com.dezzy.music.playback.OmegaMathIntegration
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.ui.utils.highRes
import com.dezzy.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** A country whose real YouTube Music chart we plot on the map. */
private data class Country(
    val code: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

/** Real per-country chart result. */
private data class CountryChart(
    val country: Country,
    val songs: List<SongItem>,
)

/** A point rendered on the map: a country's #1 song, the user, or another listener. */
private data class MapPoint(
    val key: String,
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val artworkUrl: String?,
    val isSelf: Boolean,
    val videoId: String?,
    val countryCode: String? = null,
)

private val COUNTRIES = listOf(
    Country("US", "Estados Unidos", 39.8, -98.6),
    Country("MX", "México", 23.6, -102.5),
    Country("ES", "España", 40.4, -3.7),
    Country("GB", "Reino Unido", 54.0, -2.0),
    Country("BR", "Brasil", -14.2, -51.9),
    Country("AR", "Argentina", -38.4, -63.6),
    Country("CO", "Colombia", 4.6, -74.1),
    Country("CL", "Chile", -35.7, -71.5),
    Country("FR", "Francia", 46.6, 2.2),
    Country("DE", "Alemania", 51.2, 10.4),
    Country("IT", "Italia", 41.9, 12.6),
    Country("JP", "Japón", 36.2, 138.3),
    Country("KR", "Corea del Sur", 35.9, 127.8),
    Country("IN", "India", 20.6, 78.9),
    Country("CA", "Canadá", 56.1, -106.3),
    Country("AU", "Australia", -25.3, 133.8),
)

/** Fallback "worldwide" group used when per-country charts don't return anything. */
private val WORLD = Country("ZZ", "Mundial", 15.0, 0.0)

private fun ChartsPage.topSongs(): List<SongItem> =
    sections
        .filter { it.chartType == ChartsPage.ChartType.TOP || it.chartType == ChartsPage.ChartType.TRENDING }
        .flatMap { it.items }
        .filterIsInstance<SongItem>()
        .ifEmpty { sections.flatMap { it.items }.filterIsInstance<SongItem>() }
        .distinctBy { it.id }

/**
 * Clustering grid size (in degrees) for a given zoom level. Bigger cells at low zoom → fewer, larger
 * stars; smaller cells as you zoom in → more, smaller stars. 0 means no clustering (every point is a
 * star). This is what makes "more stars appear the more you zoom in".
 */
private fun cellSizeForZoom(zoom: Double): Double = when {
    zoom < 3.5 -> 45.0
    zoom < 4.5 -> 25.0
    zoom < 6.0 -> 12.0
    zoom < 7.5 -> 5.0
    zoom < 9.0 -> 2.0
    else -> 0.0
}

/** Draws a mini 5-point star marker, optionally with a count badge for clustered locations. */
private fun starMarkerBitmap(sizePx: Int, count: Int, isSelf: Boolean): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = sizePx / 2f
    val cy = sizePx / 2f
    val outer = sizePx * 0.44f
    val inner = outer * 0.42f
    val path = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outer else inner
        val angle = -Math.PI / 2 + i * Math.PI / 5
        val x = cx + (r * Math.cos(angle)).toFloat()
        val y = cy + (r * Math.sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isSelf) Color.parseColor("#4CD964") else Color.parseColor("#FFD54A")
        style = Paint.Style.FILL
        setShadowLayer(sizePx * 0.10f, 0f, sizePx * 0.02f, Color.parseColor("#99000000"))
    }
    canvas.drawPath(path, fill)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.045f
        strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(path, stroke)
    if (count > 1) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1C1C1E")
            textSize = sizePx * 0.30f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val ty = cy - (tp.descent() + tp.ascent()) / 2
        canvas.drawText(if (count > 99) "99+" else count.toString(), cx, ty, tp)
    }
    return bmp
}

@Composable
fun MusicMapScreen(navController: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current

    val metadataFlow = remember(playerConnection) {
        playerConnection?.mediaMetadata
            ?: kotlinx.coroutines.flow.MutableStateFlow<com.dezzy.music.models.MediaMetadata?>(null)
    }
    val mediaMetadata by metadataFlow.collectAsState()

    val (shareLocation, setShareLocation) = rememberPreference(MusicMapShareLocationKey, false)

    var presences by remember { mutableStateOf<List<MapPresence>>(emptyList()) }
    var countryCharts by remember { mutableStateOf<List<CountryChart>>(emptyList()) }
    var chartsLoaded by remember { mutableStateOf(false) }
    var ownLatLng by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var query by remember { mutableStateOf("") }
    var topExpanded by remember { mutableStateOf(true) }
    // When a country star is tapped, the bottom list focuses on that country's top songs.
    var focusCountry by remember { mutableStateOf<String?>(null) }

    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            minZoomLevel = 2.5
            controller.setZoom(3.0)
            controller.setCenter(GeoPoint(20.0, 0.0))
            overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(NIGHT_MATRIX))
        }
    }

    // DelayedMapListener fires its callback ~180 ms AFTER the event via an internal Handler
    // that removeMapListener does NOT cancel, so a scroll right before leaving this screen
    // used to run renderStars() against an already-detached MapView — Marker(mapView) then
    // NPE'd on the torn-down repository and crashed the app. Track liveness and bail out.
    val mapAlive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapAlive.set(false)
            mapView.onDetach()
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ownLatLng = lastKnownLocation(context)
            setShareLocation(true)
            OmegaMathIntegration.shareLocation = true
            ownLatLng?.let {
                OmegaMathIntegration.latitude = it.first
                OmegaMathIntegration.longitude = it.second
            }
        } else {
            setShareLocation(false)
            OmegaMathIntegration.shareLocation = false
        }
    }

    // Load each country's REAL chart, incrementally and with limited concurrency so YouTube doesn't
    // throttle 16 simultaneous requests. Falls back to the global chart if per-country yields nothing.
    LaunchedEffect(Unit) {
        val acc = ArrayList<CountryChart>()
        val gate = Semaphore(4)
        coroutineScope {
            COUNTRIES.forEach { country ->
                launch(Dispatchers.IO) {
                    gate.withPermit {
                        val songs = runCatching {
                            YouTube.getChartsPage(countryCode = country.code).getOrNull()?.topSongs()
                        }.getOrNull()?.take(5).orEmpty()
                        if (songs.isNotEmpty()) {
                            val snapshot: List<CountryChart>
                            synchronized(acc) {
                                acc.add(CountryChart(country, songs))
                                snapshot = acc.sortedBy { COUNTRIES.indexOf(it.country) }.toList()
                            }
                            withContext(Dispatchers.Main) { countryCharts = snapshot }
                        }
                    }
                }
            }
        }
        // Fallback: nothing per-country (e.g. region blocked) → show the global chart so the list works.
        if (acc.isEmpty()) {
            val global = runCatching {
                YouTube.getChartsPage().getOrNull()?.topSongs()
            }.getOrNull()?.take(20).orEmpty()
            if (global.isNotEmpty()) {
                withContext(Dispatchers.Main) { countryCharts = listOf(CountryChart(WORLD, global)) }
            }
        }
        chartsLoaded = true
    }

    LaunchedEffect(Unit) {
        while (true) {
            presences = MusicMapRepository.fetchPresences()
            kotlinx.coroutines.delay(20_000L)
        }
    }

    LaunchedEffect(shareLocation) {
        OmegaMathIntegration.shareLocation = shareLocation
        if (shareLocation && hasLocationPermission(context)) {
            ownLatLng = lastKnownLocation(context)
            ownLatLng?.let {
                OmegaMathIntegration.latitude = it.first
                OmegaMathIntegration.longitude = it.second
            }
        } else if (!shareLocation) {
            ownLatLng = null
        }
    }

    val ownPoint = remember(mediaMetadata, ownLatLng) {
        val loc = ownLatLng ?: return@remember null
        val meta = mediaMetadata ?: return@remember null
        val artistText = meta.artists.joinToString { it.name }
        val label = if (artistText.isBlank()) "♪ ${meta.title}" else "♪ ${meta.title} — $artistText"
        MapPoint("self", loc.first, loc.second, label, meta.thumbnailUrl, true, null)
    }

    val countryPoints = remember(countryCharts) {
        countryCharts.mapNotNull { chart ->
            if (chart.country == WORLD) return@mapNotNull null // worldwide fallback is list-only
            val top = chart.songs.firstOrNull() ?: return@mapNotNull null
            MapPoint(
                key = "country_${chart.country.code}",
                latitude = chart.country.latitude,
                longitude = chart.country.longitude,
                label = "${chart.country.name} · ${top.title}",
                artworkUrl = top.thumbnail,
                isSelf = false,
                videoId = top.id,
                countryCode = chart.country.code,
            )
        }
    }

    val myUserId by SupabaseAuth.currentUserId.collectAsState()

    val peoplePoints = remember(presences, myUserId) {
        presences
            // Fuera mi propia presencia: ya salgo como `ownPoint`, y el servidor
            // ahora me devuelve (soy "amigo" de mí mismo en _can_see_activity).
            .filter { it.userId != "self" && it.userId != myUserId }
            .map {
                // La etiqueta lleva la canción REAL del amigo, no solo su nombre.
                val song = it.title?.let { t ->
                    if (it.artist.isNullOrBlank()) "♪ $t" else "♪ $t — ${it.artist}"
                }
                val label = if (song != null) "${it.displayName} · $song" else it.displayName
                MapPoint(
                    key = "people_${it.userId}",
                    latitude = it.latitude,
                    longitude = it.longitude,
                    label = label,
                    artworkUrl = it.artworkUrl,
                    isSelf = false,
                    videoId = it.videoId,
                )
            }
    }

    val allPoints = remember(ownPoint, countryPoints, peoplePoints) {
        listOfNotNull(ownPoint) + countryPoints + peoplePoints
    }

    val songsById = remember(countryCharts) {
        countryCharts.flatMap { it.songs }.associateBy { it.id }
    }

    fun playSong(song: SongItem) {
        if (song.id == mediaMetadata?.id) {
            playerConnection?.player?.togglePlayPause()
        } else {
            playerConnection?.playQueue(
                YouTubeQueue(
                    endpoint = WatchEndpoint(videoId = song.id),
                    preloadItem = song.toMediaMetadata(),
                )
            )
        }
    }

    // Reproduce la canción de un amigo del mapa: su videoId no está en las listas
    // de charts, así que se resuelve en InnerTube (para precargar título/carátula)
    // y se pone a sonar. Si ya suena esa canción, alterna play/pausa.
    fun playVideoId(videoId: String) {
        if (videoId == mediaMetadata?.id) {
            playerConnection?.player?.togglePlayPause()
            return
        }
        val connection = playerConnection ?: return
        coroutineScope.launch {
            val song = runCatching {
                YouTube.queue(listOf(videoId)).getOrNull()?.firstOrNull()
            }.getOrNull()
            connection.playQueue(
                YouTubeQueue(
                    endpoint = WatchEndpoint(videoId = videoId),
                    preloadItem = song?.toMediaMetadata(),
                )
            )
        }
    }

    val density = context.resources.displayMetrics.density

    // Renders every location as a mini star, grouping nearby ones into a single larger star (with a
    // count) depending on the current zoom. Zooming in shrinks the grid → more individual stars.
    fun renderStars() {
        // Bail out if the map was detached (see mapAlive): late Handler callbacks from
        // DelayedMapListener must never touch a torn-down MapView.
        if (!mapAlive.get() || mapView.repository == null) return
        mapView.overlays.removeAll { it is Marker }
        val zoom = mapView.zoomLevelDouble
        val cell = cellSizeForZoom(zoom)

        data class Cluster(val lat: Double, val lng: Double, val points: List<MapPoint>)

        val clusters: List<Cluster> = if (cell <= 0.0) {
            allPoints.map { Cluster(it.latitude, it.longitude, listOf(it)) }
        } else {
            val buckets = HashMap<Pair<Int, Int>, MutableList<MapPoint>>()
            allPoints.forEach { p ->
                val key = Math.floor(p.latitude / cell).toInt() to Math.floor(p.longitude / cell).toInt()
                buckets.getOrPut(key) { mutableListOf() }.add(p)
            }
            buckets.values.map { pts ->
                Cluster(pts.map { it.latitude }.average(), pts.map { it.longitude }.average(), pts)
            }
        }

        clusters.forEach { cluster ->
            val count = cluster.points.size
            val isSelf = cluster.points.any { it.isSelf }
            val sizeDp = 24 + minOf(count - 1, 10) * 3
            val sizePx = (sizeDp * density).toInt().coerceAtLeast(1)
            val marker = Marker(mapView).apply {
                position = GeoPoint(cluster.lat, cluster.lng)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = BitmapDrawable(context.resources, starMarkerBitmap(sizePx, count, isSelf))
                title = if (count == 1) cluster.points[0].label else "$count ubicaciones aquí"
            }
            marker.setOnMarkerClickListener { m, _ ->
                if (count > 1) {
                    // Zoom into the cluster so it splits into more stars.
                    mapView.controller.animateTo(GeoPoint(cluster.lat, cluster.lng))
                    mapView.controller.setZoom((zoom + 2.0).coerceAtMost(mapView.maxZoomLevel))
                } else {
                    val p = cluster.points[0]
                    when {
                        p.countryCode != null -> {
                            // Toggle focus: show this country's top songs in the bottom list.
                            focusCountry = if (focusCountry == p.countryCode) null else p.countryCode
                            topExpanded = true
                        }
                        // Charts traen el SongItem completo; las estrellas de
                        // gente solo el videoId, que se resuelve al vuelo.
                        p.videoId != null ->
                            songsById[p.videoId]?.let { playSong(it) } ?: playVideoId(p.videoId)
                        else -> m.showInfoWindow()
                    }
                }
                true
            }
            mapView.overlays.add(marker)
        }
        mapView.invalidate()
    }

    LaunchedEffect(allPoints) { renderStars() }

    // Re-cluster whenever the user zooms or pans (debounced) so star density tracks the zoom.
    // Keyed on allPoints so the listener always renders the current set of stars.
    DisposableEffect(mapView, allPoints) {
        val listener = object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                renderStars(); return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                renderStars(); return false
            }
        }
        val delayed = org.osmdroid.events.DelayedMapListener(listener, 180)
        mapView.addMapListener(delayed)
        onDispose { mapView.removeMapListener(delayed) }
    }

    val rows = remember(countryCharts, query, focusCountry) {
        buildList {
            countryCharts.filter { focusCountry == null || it.country.code == focusCountry }.forEach { chart ->
                val matching = if (query.isBlank()) chart.songs else chart.songs.filter { s ->
                    s.title.contains(query, true) ||
                        s.artists.joinToString { it.name }.contains(query, true) ||
                        chart.country.name.contains(query, true)
                }
                if (matching.isNotEmpty()) {
                    add(ListRow.Header(chart.country))
                    matching.forEachIndexed { index, song ->
                        add(ListRow.Song(chart.country, index + 1, song))
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Top bar: back + status pill (left, flexible) and the share-location button (right),
        // laid out in one full-width Row so they can never overlap.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = ComposeColor(0xCC1C1C1E),
                contentColor = ComposeColor.White,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .height(48.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickableNoRipple { navController.navigateUp() }
                            .padding(11.dp),
                    )
                    Icon(
                        painter = painterResource(R.drawable.location_on),
                        contentDescription = null,
                        tint = if (shareLocation) ComposeColor(0xFF4CD964) else ComposeColor(0xFFFF3B30),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            if (shareLocation) R.string.music_map_location_on
                            else R.string.music_map_location_off
                        ),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = ComposeColor(0xCC1C1C1E),
                contentColor = ComposeColor.White,
                modifier = Modifier
                    .size(48.dp)
                    .clickableNoRipple {
                        if (shareLocation) {
                            setShareLocation(false)
                            OmegaMathIntegration.shareLocation = false
                            ownLatLng = null
                        } else if (hasLocationPermission(context)) {
                            setShareLocation(true)
                            OmegaMathIntegration.shareLocation = true
                            ownLatLng = lastKnownLocation(context)?.also {
                                OmegaMathIntegration.latitude = it.first
                                OmegaMathIntegration.longitude = it.second
                            }
                        } else {
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.location_on),
                        contentDescription = stringResource(R.string.music_map_share_location),
                        tint = if (shareLocation) ComposeColor(0xFF4CD964) else ComposeColor.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = ComposeColor(0xF21C1C1E),
            contentColor = ComposeColor.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current),
            ) {
                Surface(
                    color = ComposeColor(0x55FFFFFF),
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(40.dp)
                        .height(4.dp),
                ) {}
                Spacer(Modifier.height(12.dp))

                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.music_map_search_songs)) },
                    leadingIcon = { Icon(painterResource(R.drawable.search), contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = ComposeColor(0x22FFFFFF),
                        unfocusedContainerColor = ComposeColor(0x22FFFFFF),
                        focusedIndicatorColor = ComposeColor.Transparent,
                        unfocusedIndicatorColor = ComposeColor.Transparent,
                        focusedTextColor = ComposeColor.White,
                        unfocusedTextColor = ComposeColor.White,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableNoRipple { topExpanded = !topExpanded }
                        .padding(vertical = 4.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.trending_up),
                        contentDescription = null,
                        tint = ComposeColor(0xFF7C4DFF),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.music_map_top_songs),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        painter = painterResource(
                            if (topExpanded) R.drawable.expand_more else R.drawable.expand_less
                        ),
                        contentDescription = null,
                    )
                }

                AnimatedVisibility(visible = topExpanded) {
                    if (rows.isEmpty()) {
                        Text(
                            text = stringResource(
                                if (chartsLoaded) R.string.music_map_no_songs
                                else R.string.music_map_loading_songs
                            ),
                            color = ComposeColor(0xAAFFFFFF),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            contentPadding = PaddingValues(vertical = 6.dp),
                        ) {
                            items(
                                count = rows.size,
                                key = { i ->
                                    when (val r = rows[i]) {
                                        is ListRow.Header -> "h_${r.country.code}"
                                        is ListRow.Song -> "s_${r.country.code}_${r.song.id}"
                                    }
                                },
                            ) { i ->
                                when (val r = rows[i]) {
                                    is ListRow.Header -> CountryHeader(r.country.name) {
                                        mapView.controller.animateTo(
                                            GeoPoint(r.country.latitude, r.country.longitude)
                                        )
                                        mapView.controller.setZoom(5.0)
                                    }
                                    is ListRow.Song -> TopSongRow(r.rank, r.song) { playSong(r.song) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface ListRow {
    data class Header(val country: Country) : ListRow
    data class Song(val country: Country, val rank: Int, val song: SongItem) : ListRow
}

@Composable
private fun CountryHeader(name: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoRipple(onClick)
            .padding(top = 10.dp, bottom = 4.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.location_on),
            contentDescription = null,
            tint = ComposeColor(0xFF7C4DFF),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = name, color = ComposeColor.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TopSongRow(rank: Int, song: SongItem, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickableNoRipple(onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = rank.toString(),
            color = if (rank <= 3) ComposeColor(0xFF7C4DFF) else ComposeColor(0xAAFFFFFF),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            modifier = Modifier.width(26.dp),
        )
        AsyncImage(
            model = song.thumbnail.highRes(),
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = ComposeColor.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = song.artists.joinToString { it.name },
                color = ComposeColor(0xAAFFFFFF),
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        Icon(
            painter = painterResource(R.drawable.play),
            contentDescription = null,
            tint = ComposeColor.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

// --- helpers ----------------------------------------------------------------------------------

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}

private val NIGHT_MATRIX = floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f,
)

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private fun lastKnownLocation(context: Context): Pair<Double, Double>? {
    if (!hasLocationPermission(context)) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )
    for (provider in providers) {
        try {
            if (lm.isProviderEnabled(provider)) {
                lm.getLastKnownLocation(provider)?.let { return it.latitude to it.longitude }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }
    return null
}

private suspend fun loadCoverBitmap(context: Context, url: String): Bitmap? = try {
    val request = ImageRequest.Builder(context)
        .data(url.highRes())
        .size(96, 96)
        .allowHardware(false)
        .build()
    (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
} catch (_: Exception) {
    null
}

private fun circularMarkerBitmap(source: Bitmap?, isSelf: Boolean): Bitmap {
    val sizePx = 96
    val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    val ring = if (isSelf) Color.parseColor("#4CD964") else Color.parseColor("#7C4DFF")
    paint.color = ring
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

    val inset = sizePx * 0.09f
    val innerRadius = sizePx / 2f - inset
    val path = Path().apply { addCircle(sizePx / 2f, sizePx / 2f, innerRadius, Path.Direction.CW) }
    canvas.save()
    canvas.clipPath(path)
    if (source != null) {
        val side = (innerRadius * 2f).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, side, side, true)
        canvas.drawBitmap(scaled, inset, inset, paint)
    } else {
        paint.color = Color.parseColor("#2C2C2E")
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, innerRadius, paint)
    }
    canvas.restore()
    return output
}
