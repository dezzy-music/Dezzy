/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.musicmap

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.dezzy.music.constants.SocialFeatures
import com.dezzy.music.extensions.toMediaItem
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.models.SongItem
import com.dezzy.music.innertube.models.WatchEndpoint
import com.dezzy.music.models.toMediaMetadata
import com.dezzy.music.musicmap.MapPresence
import com.dezzy.music.musicmap.MusicMapCharts
import com.dezzy.music.musicmap.MusicMapGenres
import com.dezzy.music.musicmap.MusicMapLocation
import com.dezzy.music.musicmap.MusicMapRepository
import com.dezzy.music.p2p.P2PSocial
import com.dezzy.music.playback.OmegaMathIntegration
import com.dezzy.music.playback.queues.ListQueue
import com.dezzy.music.playback.queues.YouTubeQueue
import com.dezzy.music.ui.component.CountryFlag
import com.dezzy.music.ui.component.LocalMenuState
import com.dezzy.music.ui.menu.YouTubeSongMenu
import com.dezzy.music.ui.utils.highRes
import com.dezzy.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Mapa musical — Fase 11, boceto A «El mapa manda, la hoja acompaña».
 * Mapa claro a pantalla completa (filtro nocturno sólo en tema oscuro), búsqueda
 * flotante con filtros Países / Personas / Cerca de mí, y una hoja inferior con el
 * top del país elegido. Tocar a una persona abre su tarjeta con lo que escucha.
 */

/**
 * A country whose real YouTube Music chart we plot on the map. [minZoom] hides the pills of
 * small or crowded countries until the user zooms in, so they don't pile up on each other.
 */
internal data class Country(
    val code: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val minZoom: Double = 0.0,
)

/** Real per-country chart result. */
internal data class CountryChart(
    val country: Country,
    val songs: List<SongItem>,
)

private enum class PointKind { SELF, COUNTRY, PERSON }

/** Qué capa manda en el mapa: los países (con todo) o sólo las personas. */
private enum class MapMode { COUNTRIES, PEOPLE }

/** A point rendered on the map: a country's #1 song, the user, or another listener. */
private data class MapPoint(
    val key: String,
    val kind: PointKind,
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val artworkUrl: String?,
    val videoId: String?,
    val countryCode: String? = null,
    val shortLabel: String = label,
    val userId: String? = null,
    val minZoom: Double = 0.0,
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
    // Más países con top propio en YouTube Music (VE y PR sólo devuelven el mundial).
    Country("PE", "Perú", -9.2, -75.0),
    Country("EC", "Ecuador", -1.8, -78.2, minZoom = 4.0),
    Country("BO", "Bolivia", -16.3, -63.6, minZoom = 4.0),
    Country("PY", "Paraguay", -23.4, -58.4, minZoom = 4.0),
    Country("UY", "Uruguay", -32.5, -55.8, minZoom = 4.0),
    Country("GT", "Guatemala", 15.8, -90.2, minZoom = 4.0),
    Country("SV", "El Salvador", 13.8, -88.9, minZoom = 5.0),
    Country("HN", "Honduras", 15.2, -86.2, minZoom = 5.0),
    Country("CR", "Costa Rica", 9.7, -83.8, minZoom = 4.5),
    Country("DO", "República Dominicana", 18.7, -70.2, minZoom = 4.0),
    Country("PT", "Portugal", 39.4, -8.2, minZoom = 4.0),
    Country("NL", "Países Bajos", 52.1, 5.3, minZoom = 4.0),
    Country("SE", "Suecia", 62.0, 15.0),
    Country("PL", "Polonia", 51.9, 19.1, minZoom = 4.0),
    Country("UA", "Ucrania", 48.4, 31.2, minZoom = 4.0),
    Country("RU", "Rusia", 61.5, 95.0),
    Country("TR", "Turquía", 39.0, 35.2),
    Country("EG", "Egipto", 26.8, 30.8),
    Country("SA", "Arabia Saudí", 23.9, 45.1, minZoom = 3.5),
    Country("NG", "Nigeria", 9.1, 8.7),
    Country("ZA", "Sudáfrica", -30.6, 22.9),
    Country("PH", "Filipinas", 12.9, 121.8),
    Country("ID", "Indonesia", -0.8, 113.9),
    Country("NZ", "Nueva Zelanda", -40.9, 174.9),
)

/** Fallback "worldwide" group used when per-country charts don't return anything. */
private val WORLD = Country("ZZ", "Mundial", 15.0, 0.0)

/**
 * Clustering grid size (in degrees) for a given zoom level. Bigger cells at low zoom → fewer, larger
 * groups; smaller cells as you zoom in → more individual people. 0 means no clustering.
 * Sólo se agrupan personas: los países siempre se ven como píldoras sueltas.
 */
private fun cellSizeForZoom(zoom: Double): Double = when {
    zoom < 3.5 -> 45.0
    zoom < 4.5 -> 25.0
    zoom < 6.0 -> 12.0
    zoom < 7.5 -> 5.0
    zoom < 9.0 -> 2.0
    else -> 0.0
}

/** Distancia aproximada en km entre dos puntos (haversine). */
private fun distanceKm(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val r = 6371.0
    val dLat = Math.toRadians(b.first - a.first)
    val dLng = Math.toRadians(b.second - a.second)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

private const val MAP_PREFS = "music_map_ui"
private const val KEY_SPOTIFY_PROMPT_SHOWN = "spotify_prompt_shown"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicMapScreen(navController: NavController, embedded: Boolean = false) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val menuState = LocalMenuState.current
    val localDensity = LocalDensity.current

    val metadataFlow = remember(playerConnection) {
        playerConnection?.mediaMetadata
            ?: kotlinx.coroutines.flow.MutableStateFlow<com.dezzy.music.models.MediaMetadata?>(null)
    }
    val mediaMetadata by metadataFlow.collectAsState()

    // La primera vez que se entra al mapa sin sesión de Spotify se sugiere iniciarla (rankings reales).
    var showSpotifyPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(MAP_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SPOTIFY_PROMPT_SHOWN, false)) return@LaunchedEffect
        val hasSession = withContext(Dispatchers.IO) { com.dezzy.music.spotify.SpotifyFeatures.hasSession(context) }
        if (hasSession) {
            prefs.edit().putBoolean(KEY_SPOTIFY_PROMPT_SHOWN, true).apply()
        } else {
            showSpotifyPrompt = true
        }
    }
    if (showSpotifyPrompt) {
        fun dismissPrompt() {
            showSpotifyPrompt = false
            context.getSharedPreferences(MAP_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SPOTIFY_PROMPT_SHOWN, true).apply()
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { dismissPrompt() },
            icon = { Icon(painterResource(R.drawable.stats), contentDescription = null) },
            title = { Text("Rankings reales con Spotify") },
            text = {
                Text(
                    "Busca cualquier canción y mira en qué países ha estado en el top, cuántos streams lleva y su mejor puesto. " +
                        "Para eso necesito que inicies sesión con Spotify una vez.",
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    dismissPrompt()
                    navController.navigate("settings/spotify/login")
                }) { Text("Conectar Spotify") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { dismissPrompt() }) { Text("Ahora no") }
            },
        )
    }

    val (shareLocationPref, setShareLocation) = rememberPreference(MusicMapShareLocationKey, false)
    // Compartir la ubicación es parte de «Personas»: sin red social no se comparte.
    val shareLocation = SocialFeatures.ENABLED && shareLocationPref

    var presences by remember { mutableStateOf<List<MapPresence>>(emptyList()) }
    var countryCharts by remember { mutableStateOf<List<CountryChart>>(emptyList()) }
    var chartsLoaded by remember { mutableStateOf(false) }
    var ownLatLng by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(MapMode.COUNTRIES) }
    // Al tocar la píldora de un país, la hoja se centra en su top.
    var focusCountry by remember { mutableStateOf<String?>(null) }
    // Al tocar a una persona, la hoja muestra su tarjeta.
    var selectedPersonId by remember { mutableStateOf<String?>(null) }
    // Boceto A: géneros del país abierto y buscador «dónde se escucha más».
    val genres = remember { mutableStateMapOf<String, String>() }
    var genresLoading by remember { mutableStateOf(false) }
    var genreFilter by remember { mutableStateOf<String?>(null) }
    var highlight by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searchTab by remember { mutableStateOf<SearchTab?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    val scheme = MaterialTheme.colorScheme
    val darkMap = scheme.surface.luminance() < 0.5f
    val markerColors = MarkerColors(
        pill = scheme.surfaceContainerLowest.toArgb(),
        onPill = scheme.onSurface.toArgb(),
        selected = scheme.primary.toArgb(),
        onSelected = scheme.onPrimary.toArgb(),
        placeholder = scheme.surfaceContainerHighest.toArgb(),
    )

    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // Sin botones +/- flotantes: aparecían al mover el mapa. Se hace zoom con dos dedos.
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setUseDataConnection(true)
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            minZoomLevel = 2.5
            controller.setZoom(3.0)
            controller.setCenter(GeoPoint(20.0, 0.0))
        }
    }

    // Cartografía clara en tema claro; el filtro nocturno queda sólo para el tema oscuro.
    LaunchedEffect(darkMap) {
        mapView.overlayManager.tilesOverlay.setColorFilter(
            if (darkMap) ColorMatrixColorFilter(NIGHT_MATRIX) else null
        )
        mapView.invalidate()
    }

    // DelayedMapListener fires its callback ~180 ms AFTER the event via an internal Handler
    // that removeMapListener does NOT cancel, so a scroll right before leaving this screen
    // used to run renderMarkers() against an already-detached MapView — Marker(mapView) then
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
            OmegaMathIntegration.shareLocation = true
            if (shareLocation) {
                // Ya estaba activado (permiso revocado y vuelto a dar): pide ubicación ahora.
                coroutineScope.launch { MusicMapLocation.refresh(context)?.let { ownLatLng = it } }
            } else {
                setShareLocation(true) // LaunchedEffect(shareLocation) pide la ubicación
            }
        } else {
            setShareLocation(false)
            OmegaMathIntegration.shareLocation = false
        }
    }

    fun toggleShareLocation() {
        if (shareLocation) {
            setShareLocation(false)
            OmegaMathIntegration.shareLocation = false
            ownLatLng = null
        } else if (MusicMapLocation.hasPermission(context)) {
            setShareLocation(true) // LaunchedEffect(shareLocation) pide la ubicación
            OmegaMathIntegration.shareLocation = true
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // Top real de cada país: caché local → descarga en el dispositivo (playlists de charts
    // por país), sin servidor propio. El mapa se pinta según llegan los datos.
    LaunchedEffect(Unit) {
        val codes = COUNTRIES.map { it.code } + MusicMapCharts.GLOBAL
        fun toCharts(byCode: Map<String, List<SongItem>>): List<CountryChart> {
            val perCountry = COUNTRIES.mapNotNull { country ->
                byCode[country.code]?.takeIf { it.isNotEmpty() }?.let { CountryChart(country, it.take(10)) }
            }
            if (perCountry.isNotEmpty()) return perCountry
            // Sin datos por país → el chart mundial, solo en la lista.
            return byCode[MusicMapCharts.GLOBAL]?.takeIf { it.isNotEmpty() }
                ?.let { listOf(CountryChart(WORLD, it.take(20))) }
                .orEmpty()
        }
        val loaded = runCatching {
            MusicMapCharts.load(context, codes) { partial ->
                val charts = toCharts(partial)
                if (charts.isNotEmpty()) withContext(Dispatchers.Main) { countryCharts = charts }
            }
        }.getOrNull().orEmpty()
        toCharts(loaded).takeIf { it.isNotEmpty() }?.let { countryCharts = it }
        chartsLoaded = true
    }

    LaunchedEffect(Unit) {
        if (!SocialFeatures.ENABLED) return@LaunchedEffect
        while (true) {
            presences = MusicMapRepository.fetchPresences()
            kotlinx.coroutines.delay(20_000L)
        }
    }

    LaunchedEffect(shareLocation) {
        OmegaMathIntegration.shareLocation = shareLocation
        if (shareLocation) {
            // Muestra al instante la última guardada y luego pide una nueva (aprox. ~1 km).
            MusicMapLocation.ensureRestored(context)
            ownLatLng = MusicMapLocation.stored(context)
            if (MusicMapLocation.hasPermission(context)) {
                MusicMapLocation.refresh(context)?.let { ownLatLng = it }
            }
        } else {
            ownLatLng = null
            MusicMapLocation.clear(context)
        }
    }

    val ownPoint = remember(mediaMetadata, ownLatLng) {
        val loc = ownLatLng ?: return@remember null
        val meta = mediaMetadata
        val label = when {
            meta == null -> "Tú"
            meta.artists.isEmpty() -> "Tú · ${meta.title}"
            else -> "Tú · ${meta.title} — ${meta.artists.joinToString { it.name }}"
        }
        MapPoint("self", PointKind.SELF, loc.first, loc.second, label, meta?.thumbnailUrl, null)
    }

    val countryPoints = remember(countryCharts) {
        countryCharts.mapNotNull { chart ->
            if (chart.country == WORLD) return@mapNotNull null // worldwide fallback is list-only
            val top = chart.songs.firstOrNull() ?: return@mapNotNull null
            MapPoint(
                key = "country_${chart.country.code}",
                kind = PointKind.COUNTRY,
                latitude = chart.country.latitude,
                longitude = chart.country.longitude,
                label = "${chart.country.name} · ${top.title}",
                artworkUrl = top.thumbnail,
                videoId = top.id,
                countryCode = chart.country.code,
                shortLabel = chart.country.name,
                minZoom = chart.country.minZoom,
            )
        }
    }

    val myKeys by P2PSocial.keys.collectAsState()
    val myUserId = remember(myKeys) { P2PSocial.me }

    val people = remember(presences, myUserId) {
        // Fuera mi propia presencia: ya salgo como `ownPoint` (los relés también
        // devuelven mi propio estado «escuchando»).
        presences.filter { it.userId != "self" && it.userId != myUserId }
    }
    val peoplePoints = remember(people) {
        people.map {
            // La etiqueta lleva la canción REAL de la persona, no solo su nombre.
            val song = it.title?.let { t -> if (it.artist.isNullOrBlank()) t else "$t — ${it.artist}" }
            MapPoint(
                key = "people_${it.userId}",
                kind = PointKind.PERSON,
                latitude = it.latitude,
                longitude = it.longitude,
                label = if (song != null) "${it.displayName} · $song" else it.displayName,
                artworkUrl = it.artworkUrl,
                videoId = it.videoId,
                userId = it.userId,
            )
        }
    }

    val visiblePoints = remember(ownPoint, countryPoints, peoplePoints, mode) {
        when (mode) {
            MapMode.COUNTRIES -> countryPoints + peoplePoints + listOfNotNull(ownPoint)
            MapMode.PEOPLE -> peoplePoints + listOfNotNull(ownPoint)
        }
    }

    // Portadas de las píldoras y las personas, cargadas una vez y reutilizadas.
    val covers = remember { mutableStateMapOf<String, Bitmap>() }
    LaunchedEffect(visiblePoints) {
        visiblePoints
            .filter { it.kind != PointKind.SELF }
            .mapNotNull { it.artworkUrl }
            .distinct()
            .filter { it !in covers }
            .forEach { url -> loadCoverBitmap(context, url)?.let { covers[url] = it } }
    }

    val songsById = remember(countryCharts) {
        countryCharts.flatMap { it.songs }.associateBy { it.id }
    }

    fun genreOf(song: SongItem): String? =
        song.artists.firstOrNull()?.name?.let { genres[MusicMapGenres.key(it)] }

    val focusedChart = remember(countryCharts, focusCountry) {
        countryCharts.firstOrNull { it.country.code == focusCountry }
    }

    LaunchedEffect(focusCountry) { genreFilter = null }

    // Clasifica por género a los artistas del país abierto (queda guardado en el teléfono).
    LaunchedEffect(focusedChart) {
        val chart = focusedChart ?: return@LaunchedEffect
        if (chart.country == WORLD) return@LaunchedEffect
        genresLoading = true
        try {
            MusicMapGenres.classify(context, chart.songs.mapNotNull { it.artists.firstOrNull()?.name }) { partial ->
                withContext(Dispatchers.Main) { genres.putAll(partial) }
            }
        } finally {
            genresLoading = false
        }
    }

    val shares by remember(focusedChart) {
        derivedStateOf { focusedChart?.let { chart -> genreShares(chart.songs) { genreOf(it) } }.orEmpty() }
    }

    // Top de hace un día o más, para las flechas de tendencia.
    val previousRanks = remember(countryCharts) {
        countryCharts.associate { it.country.code to MusicMapCharts.previousRanks(context, it.country.code) }
    }

    // Búsqueda en todo el catálogo (cualquier canción, aunque no esté en ningún top de hoy).
    var catalog by remember { mutableStateOf<Pair<String, List<SongItem>>?>(null) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            catalog = null
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(400)
        val items = YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).getOrNull()
            ?.items?.filterIsInstance<SongItem>().orEmpty().take(15)
        catalog = q to items
    }
    val catalogLoading = query.trim().length >= 2 && catalog?.first != query.trim()

    val search = remember(query, countryCharts, catalog) {
        if (query.isBlank()) {
            null
        } else {
            val inCharts = searchCharts(query, countryCharts)
            val extra = catalog?.takeIf { it.first == query.trim() }?.second.orEmpty()
            val known = inCharts.songs.mapTo(HashSet()) { it.song.id }
            inCharts.copy(catalog = extra.filter { it.id !in known })
        }
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

    // Reproduce la canción de una persona del mapa: su videoId no está en las listas
    // de charts, así que se resuelve en InnerTube (para precargar título/carátula)
    // y se pone a sonar. Si ya suena esa canción, alterna play/pausa.
    fun playVideoId(videoId: String) {
        if (videoId == mediaMetadata?.id) {
            playerConnection?.player?.togglePlayPause()
            return
        }
        songsById[videoId]?.let { playSong(it); return }
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

    val scaffoldState = rememberBottomSheetScaffoldState()

    // Al empezar a buscar se abre la hoja con los resultados; al borrar se quita el resaltado.
    LaunchedEffect(query.isBlank()) {
        if (query.isBlank()) {
            highlight = emptySet()
            searchTab = null
        } else {
            selectedPersonId = null
            runCatching { scaffoldState.bottomSheetState.expand() }
        }
    }

    fun centerOnMe(zoomTo: Double) {
        val loc = ownLatLng
        when {
            loc != null -> {
                mapView.controller.animateTo(GeoPoint(loc.first, loc.second))
                mapView.controller.setZoom(zoomTo)
            }
            !shareLocation -> toggleShareLocation()
            else -> coroutineScope.launch { MusicMapLocation.refresh(context)?.let { ownLatLng = it } }
        }
    }

    val density = context.resources.displayMetrics.density

    // Pinta países como píldoras (portada + nombre), personas como círculos con punto
    // «en vivo» (agrupadas según el zoom) y tu posición encima de todo.
    fun renderMarkers() {
        // Bail out if the map was detached (see mapAlive): late Handler callbacks from
        // DelayedMapListener must never touch a torn-down MapView.
        if (!mapAlive.get() || mapView.repository == null) return
        mapView.overlays.removeAll { it is Marker }
        val zoom = mapView.zoomLevelDouble
        val cell = cellSizeForZoom(zoom)

        data class Cluster(val lat: Double, val lng: Double, val points: List<MapPoint>)

        val personPoints = visiblePoints.filter { it.kind == PointKind.PERSON }
        val clusters: List<Cluster> = if (cell <= 0.0) {
            personPoints.map { Cluster(it.latitude, it.longitude, listOf(it)) }
        } else {
            val buckets = HashMap<Pair<Int, Int>, MutableList<MapPoint>>()
            personPoints.forEach { p ->
                val key = Math.floor(p.latitude / cell).toInt() to Math.floor(p.longitude / cell).toInt()
                buckets.getOrPut(key) { mutableListOf() }.add(p)
            }
            buckets.values.map { pts ->
                Cluster(pts.map { it.latitude }.average(), pts.map { it.longitude }.average(), pts)
            }
        }

        fun addMarker(lat: Double, lng: Double, bitmap: Bitmap, title: String, onClick: (Marker) -> Unit) {
            val marker = Marker(mapView).apply {
                position = GeoPoint(lat, lng)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = BitmapDrawable(context.resources, bitmap)
                this.title = title
            }
            marker.setOnMarkerClickListener { m, _ -> onClick(m); true }
            mapView.overlays.add(marker)
        }

        // Países
        visiblePoints.filter { it.kind == PointKind.COUNTRY }.forEach { p ->
            val highlighted = p.countryCode in highlight
            // Los países pequeños o muy juntos salen al acercar (salvo si los marca la búsqueda).
            if (!highlighted && p.countryCode != focusCountry && zoom < p.minZoom) return@forEach
            val selected = p.countryCode == focusCountry || highlighted
            addMarker(
                p.latitude, p.longitude,
                countryPillBitmap(p.shortLabel, p.artworkUrl?.let { covers[it] }, selected, density, markerColors),
                p.label,
            ) {
                // Alterna el foco: la hoja pasa a mostrar el top de ese país.
                focusCountry = if (focusCountry == p.countryCode) null else p.countryCode
                selectedPersonId = null
                coroutineScope.launch { runCatching { scaffoldState.bottomSheetState.partialExpand() } }
            }
        }

        // Personas (agrupadas)
        clusters.forEach { cluster ->
            val count = cluster.points.size
            if (count > 1) {
                addMarker(
                    cluster.lat, cluster.lng,
                    clusterBitmap(count, density, markerColors),
                    "$count personas aquí",
                ) {
                    // Acerca el zoom para separar el grupo.
                    mapView.controller.animateTo(GeoPoint(cluster.lat, cluster.lng))
                    mapView.controller.setZoom((zoom + 2.0).coerceAtMost(mapView.maxZoomLevel))
                }
            } else {
                val p = cluster.points[0]
                addMarker(
                    p.latitude, p.longitude,
                    personBitmap(p.artworkUrl?.let { covers[it] }, p.userId == selectedPersonId, density, markerColors),
                    p.label,
                ) {
                    selectedPersonId = p.userId
                    coroutineScope.launch { runCatching { scaffoldState.bottomSheetState.expand() } }
                }
            }
        }

        // Tu punto, siempre encima
        visiblePoints.firstOrNull { it.kind == PointKind.SELF }?.let { p ->
            addMarker(p.latitude, p.longitude, selfBitmap(density, markerColors), p.label) { it.showInfoWindow() }
        }
        mapView.invalidate()
    }

    val latestRender by rememberUpdatedState({ renderMarkers() })

    LaunchedEffect(visiblePoints, covers.size, focusCountry, selectedPersonId, markerColors, highlight) { renderMarkers() }

    // Re-agrupa al hacer zoom o desplazarse (con retardo) para que la densidad siga al zoom.
    DisposableEffect(mapView) {
        val listener = object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                latestRender(); return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                latestRender(); return false
            }
        }
        val delayed = org.osmdroid.events.DelayedMapListener(listener, 180)
        mapView.addMapListener(delayed)
        onDispose { mapView.removeMapListener(delayed) }
    }

    // Sin país elegido: el top 5 de cada país. Con país: su top entero, filtrado por el chip de género.
    val rows by remember(countryCharts, focusCountry, genreFilter) {
        derivedStateOf {
            val filter = genreFilter
            buildList {
                countryCharts.filter { focusCountry == null || it.country.code == focusCountry }.forEach { chart ->
                    val ranked = chart.songs.withIndex().toList()
                    val shown = when {
                        focusCountry == null -> ranked.take(5)
                        filter != null -> ranked.filter { (_, s) -> matchesGenre(genreOf(s), filter, shares) }
                        else -> ranked
                    }
                    if (shown.isNotEmpty()) {
                        add(ListRow.Header(chart.country))
                        shown.forEach { (index, song) -> add(ListRow.Song(chart.country, index + 1, song)) }
                    }
                }
            }
        }
    }

    val selectedPerson = remember(people, selectedPersonId) { people.firstOrNull { it.userId == selectedPersonId } }

    BackHandler(enabled = selectedPersonId != null || focusCountry != null || query.isNotEmpty()) {
        when {
            selectedPersonId != null -> selectedPersonId = null
            query.isNotEmpty() -> query = ""
            else -> focusCountry = null
        }
    }

    val bottomInset = with(localDensity) { LocalPlayerAwareWindowInsets.current.getBottom(this).toDp() }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 176.dp + bottomInset,
        sheetContainerColor = scheme.surface,
        sheetContent = {
            if (selectedPerson != null) {
                PersonSheet(
                    person = selectedPerson,
                    distanceKm = ownLatLng?.let { distanceKm(it, selectedPerson.latitude to selectedPerson.longitude) },
                    isPlaying = selectedPerson.videoId != null && selectedPerson.videoId == mediaMetadata?.id,
                    onClose = { selectedPersonId = null },
                    onListen = { selectedPerson.videoId?.let(::playVideoId) },
                    onProfile = { navController.navigate("social/profile/${selectedPerson.userId}") },
                    modifier = Modifier.padding(bottom = bottomInset),
                )
            } else if (search != null) {
                val tab = searchTab?.takeIf {
                    when (it) {
                        SearchTab.ARTIST -> search.artist != null
                        SearchTab.SONGS -> search.hasSongs
                        SearchTab.COUNTRIES -> search.countries.isNotEmpty()
                    }
                } ?: search.firstTab()
                MapSearchResults(
                    query = query,
                    search = search,
                    tab = tab,
                    countriesLoaded = countryCharts.count { it.country != WORLD },
                    currentSongId = mediaMetadata?.id,
                    onTab = { searchTab = it },
                    onRadio = { song ->
                        playerConnection?.playQueue(
                            YouTubeQueue(
                                endpoint = WatchEndpoint(videoId = song.id),
                                preloadItem = song.toMediaMetadata(),
                            )
                        )
                    },
                    onArtist = { id -> navController.navigate("artist/$id") },
                    onShowOnMap = { codes ->
                        keyboard?.hide()
                        highlight = codes
                        focusCountry = null
                        mode = MapMode.COUNTRIES
                        mapView.controller.setZoom(2.6)
                        mapView.controller.animateTo(GeoPoint(20.0, 0.0))
                        coroutineScope.launch { runCatching { scaffoldState.bottomSheetState.partialExpand() } }
                    },
                    onFocusCountry = { country ->
                        keyboard?.hide()
                        query = ""
                        mode = MapMode.COUNTRIES
                        focusCountry = country.code
                        if (country != WORLD) {
                            mapView.controller.animateTo(GeoPoint(country.latitude, country.longitude))
                            mapView.controller.setZoom(5.0)
                        }
                    },
                    onPlaySong = { playSong(it) },
                    onSearchEverywhere = {
                        navController.navigate("search/${java.net.URLEncoder.encode(query.trim(), "UTF-8")}")
                    },
                    onStats = { song ->
                        keyboard?.hide()
                        navController.navigate(
                            songStatsRoute(
                                title = song.title,
                                artist = song.artists.joinToString { it.name },
                                thumbnail = song.thumbnail,
                                videoId = song.id,
                                artistId = song.artists.firstOrNull()?.id,
                            )
                        )
                    },
                    catalogLoading = catalogLoading,
                    bottomPadding = bottomInset,
                )
            } else {
                val focus = countryCharts.firstOrNull { it.country.code == focusCountry }?.country
                val songsShown = rows.filterIsInstance<ListRow.Song>().map { it.song }
                TopSheetHeader(
                    flagCode = focus?.code,
                    title = when {
                        focus != null -> "Top en ${focus.name}"
                        countryCharts.singleOrNull()?.country == WORLD -> "Top mundial"
                        else -> "Top por país"
                    },
                    subtitle = when {
                        !chartsLoaded && countryCharts.isEmpty() -> stringResource(R.string.music_map_loading_songs)
                        focus != null && genreFilter != null -> "${cancionesLabel(songsShown.size)} de ${genreFilter}, " +
                            if (songsShown.size == 1) "toca para escucharla" else "toca una para escucharla"
                        focus != null -> "Top de hoy, ${cancionesLabel(songsShown.size)}"
                        else -> "Toca un país en el mapa para ver su top"
                    },
                    showClear = focus != null,
                    canPlay = songsShown.isNotEmpty(),
                    onClear = { focusCountry = null },
                    onPlayAll = {
                        playerConnection?.playQueue(
                            ListQueue(
                                title = focus?.let { f -> genreFilter?.let { "$it en ${f.name}" } ?: "Top en ${f.name}" } ?: "Top del mapa musical",
                                items = songsShown.map { it.toMediaItem() },
                            )
                        )
                    },
                )
                if (rows.isEmpty() && focus == null) {
                    Text(
                        text = stringResource(
                            if (chartsLoaded) R.string.music_map_no_songs
                            else R.string.music_map_loading_songs
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp + bottomInset),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 520.dp),
                        contentPadding = PaddingValues(bottom = 16.dp + bottomInset),
                    ) {
                        if (focus != null && focus != WORLD) {
                            item(key = "genres") {
                                GenreShareSection(
                                    shares = shares,
                                    loading = genresLoading,
                                    selected = genreFilter,
                                    onSelect = { genreFilter = it },
                                )
                            }
                        }
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
                                is ListRow.Header -> if (focusCountry == null) {
                                    CountryHeader(r.country.code, r.country.name) {
                                        focusCountry = r.country.code
                                        if (r.country != WORLD) {
                                            mapView.controller.animateTo(
                                                GeoPoint(r.country.latitude, r.country.longitude)
                                            )
                                            mapView.controller.setZoom(5.0)
                                        }
                                    }
                                }
                                is ListRow.Song -> TopSongRow(
                                    rank = r.rank,
                                    song = r.song,
                                    isCurrent = r.song.id == mediaMetadata?.id,
                                    previousRanks = previousRanks[r.country.code].orEmpty(),
                                    onClick = { playSong(r.song) },
                                    onMore = {
                                        menuState.show {
                                            YouTubeSongMenu(
                                                song = r.song,
                                                navController = navController,
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) {
        // clipToBounds: osmdroid pinta fuera de su caja al moverlo y asomaba detrás de las pestañas de Social.
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
            val containerHeightPx = constraints.maxHeight
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize().clipToBounds())

            // ── Búsqueda flotante, filtros y barra de ubicación ─────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (embedded) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars))
                    .padding(top = 8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = scheme.surfaceContainerHigh,
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (embedded) {
                            Icon(
                                painterResource(R.drawable.location_on),
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        } else {
                            IconButton(onClick = { navController.navigateUp() }) {
                                Icon(painterResource(R.drawable.arrow_back), contentDescription = "Atrás")
                            }
                        }
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Artista, canción o país", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = ComposeColor.Transparent,
                                unfocusedContainerColor = ComposeColor.Transparent,
                                focusedIndicatorColor = ComposeColor.Transparent,
                                unfocusedIndicatorColor = ComposeColor.Transparent,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(painterResource(R.drawable.close), contentDescription = "Borrar búsqueda")
                            }
                        } else {
                            Icon(
                                painterResource(R.drawable.search),
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 16.dp),
                            )
                        }
                    }
                }

                // Relleno de inicio y fin como Spacer dentro del scroll: así los chips empiezan
                // alineados con el buscador y no se ven cortados por la izquierda.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(8.dp))
                    MapModeChip("Países", R.drawable.location_on, mode == MapMode.COUNTRIES) {
                        mode = MapMode.COUNTRIES
                    }
                    if (SocialFeatures.ENABLED) MapModeChip("Personas", R.drawable.group, mode == MapMode.PEOPLE) {
                        mode = MapMode.PEOPLE
                        focusCountry = null
                    }
                    if (SocialFeatures.ENABLED) ElevatedAssistChip(
                        onClick = {
                            mode = MapMode.PEOPLE
                            focusCountry = null
                            centerOnMe(11.0)
                        },
                        label = { Text("Cerca de mí") },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.near_me), contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                }

                if (mode == MapMode.PEOPLE) {
                    ShareLocationBar(
                        sharing = shareLocation,
                        onToggle = { toggleShareLocation() },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            // ── «Mi ubicación»: sube y baja con la hoja ──────────────────────
            val fabSizePx = with(localDensity) { 56.dp.roundToPx() }
            val fabMarginPx = with(localDensity) { 16.dp.roundToPx() }
            val peekPx = with(localDensity) { (176.dp + bottomInset).roundToPx() }
            FloatingActionButton(
                onClick = { centerOnMe(10.0) },
                containerColor = scheme.surfaceContainerHigh,
                contentColor = if (ownLatLng != null) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        val sheetTop = runCatching { scaffoldState.bottomSheetState.requireOffset() }
                            .getOrNull()?.roundToInt() ?: (containerHeightPx - peekPx)
                        IntOffset(-fabMarginPx, (sheetTop - fabSizePx - fabMarginPx).coerceAtLeast(0))
                    },
            ) {
                Icon(
                    painterResource(R.drawable.my_location),
                    contentDescription = if (ownLatLng != null) "Centrar en mi ubicación" else stringResource(R.string.music_map_share_location),
                )
            }
        }
    }
}

private sealed interface ListRow {
    data class Header(val country: Country) : ListRow
    data class Song(val country: Country, val rank: Int, val song: SongItem) : ListRow
}

// ── Hoja: top del país ───────────────────────────────────────────────────────

@Composable
private fun TopSheetHeader(
    flagCode: String? = null,
    title: String,
    subtitle: String,
    showClear: Boolean,
    canPlay: Boolean,
    onClear: () -> Unit,
    onPlayAll: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, bottom = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (flagCode != null) {
                    CountryFlag(flagCode, height = 20.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showClear) {
            IconButton(onClick = onClear) {
                Icon(painterResource(R.drawable.close), contentDescription = "Ver todos los países")
            }
        }
        FilledTonalIconButton(onClick = onPlayAll, enabled = canPlay) {
            Icon(painterResource(R.drawable.play), contentDescription = "Reproducir el top")
        }
    }
}

@Composable
private fun CountryHeader(code: String, name: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "Ver el top de $name", onClick = onClick)
            .padding(horizontal = 24.dp),
    ) {
        CountryFlag(code, height = 16.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
    }
}

@Composable
private fun TopSongRow(
    rank: Int,
    song: SongItem,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    previousRanks: Map<String, Int> = emptyMap(),
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = if (rank <= 3 || isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(32.dp),
        )
        AsyncImage(
            model = song.thumbnail.highRes(),
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artists.joinToString { it.name },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TrendBadge(rank = rank, previousRanks = previousRanks, songId = song.id)
        IconButton(onClick = onMore) {
            Icon(painterResource(R.drawable.more_vert), contentDescription = "Más opciones de ${song.title}")
        }
    }
}

// ── Hoja: persona elegida ────────────────────────────────────────────────────

@Composable
private fun PersonSheet(
    person: MapPresence,
    distanceKm: Double?,
    isPlaying: Boolean,
    onClose: () -> Unit,
    onListen: () -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(scheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = person.displayName.trim().firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = person.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                val where = distanceKm?.let { "A unos ${it.roundToInt().coerceAtLeast(1)} km" } ?: "Cerca de ti"
                Text(
                    text = if (person.title != null) "$where, escuchando ahora" else where,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.close), contentDescription = "Cerrar")
            }
        }

        if (person.title != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = scheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = person.artworkUrl?.highRes(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(scheme.surfaceContainerHighest),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = person.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        person.artist?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (person.videoId != null) {
                        FilledIconButton(onClick = onListen) {
                            Icon(
                                painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                                contentDescription = if (isPlaying) "Pausar" else "Reproducir ${person.title}",
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onListen,
                enabled = person.videoId != null,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Icon(painterResource(R.drawable.headphones), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Escuchar")
            }
            FilledTonalButton(
                onClick = onProfile,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text("Ver perfil")
            }
        }
    }
}

// ── Controles flotantes ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapModeChip(label: String, icon: Int, selected: Boolean, onClick: () -> Unit) {
    ElevatedFilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                painterResource(if (selected) R.drawable.check else icon),
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        },
    )
}

@Composable
private fun ShareLocationBar(sharing: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerLowest,
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .toggleable(value = sharing, role = Role.Switch, onValueChange = { onToggle() })
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.location_on),
                contentDescription = null,
                tint = if (sharing) scheme.primary else scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (sharing) "Compartes tu zona" else "No compartes tu ubicación",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Zona aproximada, nunca tu punto exacto",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = sharing, onCheckedChange = null)
        }
    }
}

// --- helpers ----------------------------------------------------------------------------------

private val NIGHT_MATRIX = floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f,
)

/** Colores del esquema pasados a los bitmaps de los marcadores (osmdroid no entiende Compose). */
private data class MarkerColors(
    val pill: Int,
    val onPill: Int,
    val selected: Int,
    val onSelected: Int,
    val placeholder: Int,
)

private const val LIVE_GREEN = "#34C759"

private suspend fun loadCoverBitmap(context: Context, url: String): Bitmap? = try {
    val request = ImageRequest.Builder(context)
        .data(url.highRes())
        .size(96, 96)
        .allowHardware(false)
        .build()
    (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    null
}

/** Dibuja `source` recortado en un círculo de radio `radius` centrado en (cx, cy). */
private fun Canvas.drawCircularCover(source: Bitmap?, cx: Float, cy: Float, radius: Float, placeholder: Int) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    save()
    clipPath(Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) })
    if (source != null) {
        val side = (radius * 2f).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, side, side, true)
        drawBitmap(scaled, cx - radius, cy - radius, paint)
    } else {
        paint.color = placeholder
        drawCircle(cx, cy, radius, paint)
    }
    restore()
}

/** Píldora de país: portada redonda y nombre. */
private fun countryPillBitmap(
    name: String,
    cover: Bitmap?,
    selected: Boolean,
    density: Float,
    colors: MarkerColors,
): Bitmap {
    val shadow = 4f * density
    val h = 36f * density
    val pad = 4f * density
    val coverR = (h - 2 * pad) / 2f
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (selected) colors.onSelected else colors.onPill
        textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val w = pad + coverR * 2 + 8f * density + textPaint.measureText(name) + 14f * density
    val bmp = Bitmap.createBitmap((w + shadow * 2).toInt(), (h + shadow * 2).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (selected) colors.selected else colors.pill
        setShadowLayer(shadow * 0.8f, 0f, density, Color.parseColor("#40000000"))
    }
    canvas.drawRoundRect(RectF(shadow, shadow, shadow + w, shadow + h), h / 2, h / 2, bg)
    val cx = shadow + pad + coverR
    val cy = shadow + h / 2
    canvas.drawCircularCover(cover, cx, cy, coverR, colors.placeholder)
    val ty = cy - (textPaint.descent() + textPaint.ascent()) / 2
    canvas.drawText(name, cx + coverR + 8f * density, ty, textPaint)
    return bmp
}

/** Persona: círculo con su portada, aro blanco y punto verde «en vivo». */
private fun personBitmap(cover: Bitmap?, selected: Boolean, density: Float, colors: MarkerColors): Bitmap {
    val shadow = 4f * density
    val d = 40f * density
    val size = (d + shadow * 2 + 4f * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (selected) colors.selected else Color.WHITE
        setShadowLayer(shadow * 0.8f, 0f, density, Color.parseColor("#40000000"))
    }
    canvas.drawCircle(cx, cy, d / 2, ring)
    canvas.drawCircularCover(cover, cx, cy, d / 2 - 3f * density, colors.placeholder)
    // Punto «en vivo»
    val dotR = 6f * density
    val dx = cx + d / 2 * 0.72f
    val dy = cy + d / 2 * 0.72f
    canvas.drawCircle(dx, dy, dotR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    canvas.drawCircle(dx, dy, dotR - 2f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(LIVE_GREEN) })
    return bmp
}

/** Grupo de personas: círculo de color primario con el número. */
private fun clusterBitmap(count: Int, density: Float, colors: MarkerColors): Bitmap {
    val shadow = 4f * density
    val d = (36f + minOf(count - 1, 10) * 2f) * density
    val size = (d + shadow * 2).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val c = size / 2f
    canvas.drawCircle(c, c, d / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.selected
        setShadowLayer(shadow * 0.8f, 0f, density, Color.parseColor("#40000000"))
    })
    canvas.drawCircle(c, c, d / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    })
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.onSelected
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    canvas.drawText(if (count > 99) "99+" else count.toString(), c, c - (tp.descent() + tp.ascent()) / 2, tp)
    return bmp
}

/** Tu posición: punto de color primario con halo. */
private fun selfBitmap(density: Float, colors: MarkerColors): Bitmap {
    val size = (28f * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val c = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = colors.selected
    paint.alpha = 64
    canvas.drawCircle(c, c, c, paint)
    paint.color = Color.WHITE
    canvas.drawCircle(c, c, 9f * density, paint)
    paint.color = colors.selected
    canvas.drawCircle(c, c, 6.5f * density, paint)
    return bmp
}
