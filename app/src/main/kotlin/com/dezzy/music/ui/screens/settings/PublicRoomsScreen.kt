/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.settings
import com.dezzy.music.together.membershipIn
import com.dezzy.music.together.currentTogetherRoom
import com.dezzy.music.together.TogetherRoomMembership
import com.dezzy.music.together.TogetherSessionState
import com.dezzy.music.together.TogetherLink
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import com.dezzy.music.LocalPlayerConnection

import android.os.Build
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.dezzy.music.constants.ListenTogetherServerUrlKey
import com.dezzy.music.constants.TogetherDisplayNameKey
import com.dezzy.music.listentogether.ListenTogetherClient
import com.dezzy.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject


import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.R
import com.dezzy.music.together.TogetherPublicRoom
import com.dezzy.music.together.TogetherRoomTheme

data class ThemeFilter(
    val theme: TogetherRoomTheme?,
    val label: String,
    val iconRes: Int,
)

val themeFilters = listOf(
    ThemeFilter(null, "Todos", R.drawable.resource_public),
    ThemeFilter(TogetherRoomTheme.LOFI, "Lofi", R.drawable.ic_coffee),
    ThemeFilter(TogetherRoomTheme.POP, "Pop", R.drawable.star),
    ThemeFilter(TogetherRoomTheme.HIP_HOP, "Hip-Hop", R.drawable.mic),
    ThemeFilter(TogetherRoomTheme.ELECTRONIC, "Electronic", R.drawable.ic_radio_waves),
    ThemeFilter(TogetherRoomTheme.REGGAETON, "Reggaeton", R.drawable.fire),
    ThemeFilter(TogetherRoomTheme.LATIN, "Latin", R.drawable.music_note),
    ThemeFilter(TogetherRoomTheme.ROCK, "Rock", R.drawable.ic_guitar),
    ThemeFilter(TogetherRoomTheme.KPOP, "K-Pop", R.drawable.star),
    ThemeFilter(TogetherRoomTheme.RNB, "R&B", R.drawable.music_note),
    ThemeFilter(TogetherRoomTheme.JAZZ, "Jazz", R.drawable.queue_music),
)

fun themeColor(theme: TogetherRoomTheme): Color = when (theme) {
    TogetherRoomTheme.LOFI -> Color(0xFF6C8EBF)
    TogetherRoomTheme.REGGAETON -> Color(0xFFFF6B35)
    TogetherRoomTheme.POP -> Color(0xFFFF69B4)
    TogetherRoomTheme.ELECTRONIC -> Color(0xFF00D4FF)
    TogetherRoomTheme.HIP_HOP -> Color(0xFFFFBE0B)
    TogetherRoomTheme.RNB -> Color(0xFFB388FF)
    TogetherRoomTheme.LATIN -> Color(0xFFFF5252)
    TogetherRoomTheme.KPOP -> Color(0xFFE040FB)
    TogetherRoomTheme.ROCK -> Color(0xFF78909C)
    TogetherRoomTheme.JAZZ -> Color(0xFFFFCC02)
    TogetherRoomTheme.CLASSICAL -> Color(0xFFD4AC0D)
    TogetherRoomTheme.MIXED -> Color(0xFF81C784)
}

fun themeIcon(theme: TogetherRoomTheme): Int = when (theme) {
    TogetherRoomTheme.LOFI -> R.drawable.ic_coffee
    TogetherRoomTheme.REGGAETON -> R.drawable.fire
    TogetherRoomTheme.POP -> R.drawable.star
    TogetherRoomTheme.ELECTRONIC -> R.drawable.ic_radio_waves
    TogetherRoomTheme.HIP_HOP -> R.drawable.mic
    TogetherRoomTheme.RNB -> R.drawable.music_note
    TogetherRoomTheme.LATIN -> R.drawable.music_note
    TogetherRoomTheme.KPOP -> R.drawable.star
    TogetherRoomTheme.ROCK -> R.drawable.ic_guitar
    TogetherRoomTheme.JAZZ -> R.drawable.queue_music
    TogetherRoomTheme.CLASSICAL -> R.drawable.library_music
    TogetherRoomTheme.MIXED -> R.drawable.resource_public
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicRoomsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val clipboard = LocalClipboardManager.current

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var privateRoomCode by rememberSaveable { mutableStateOf("") }
    var selectedTheme by remember { mutableStateOf<TogetherRoomTheme?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val (serverUrl, _) = rememberPreference(
        ListenTogetherServerUrlKey,
        defaultValue = "https://huggingface.co/spaces/Dezzyyyy/listen-together"
    )
    val (displayName, _) = rememberPreference(
        TogetherDisplayNameKey,
        defaultValue = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Oyente",
    )

    var publicRooms by remember { mutableStateOf<List<TogetherPublicRoom>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // Sala en la que ya está este dispositivo (anfitrión o invitado). Se recalcula cuando cambia
    // la sesión y en cada refresco de la lista (cada 5 s).
    val idleTogether = remember { MutableStateFlow<TogetherSessionState>(TogetherSessionState.Idle) }
    val togetherState by (playerConnection?.service?.togetherSessionState ?: idleTogether).collectAsState()
    val myRoom = remember(togetherState, publicRooms) { currentTogetherRoom(togetherState) }

    // Abre la sala en la que ya estás o se une a una nueva. Unirse a la propia sala dejaba al
    // anfitrión fuera y la sala sin host.
    fun openOrJoin(code: String) {
        when (myRoom.membershipIn(code)) {
            TogetherRoomMembership.HOST -> navController.navigate("settings/music_together")
            TogetherRoomMembership.GUEST -> navController.navigate("listen_together_chat")
            TogetherRoomMembership.NONE -> {
                val service = playerConnection?.service
                if (service != null) {
                    service.joinTogether(code, displayName)
                } else {
                    val client = ListenTogetherClient.getInstance()
                        ?: ListenTogetherClient(context).also { ListenTogetherClient.setInstance(it) }
                    client.joinRoom(code, displayName)
                }
                navController.navigate("listen_together_chat")
            }
        }
    }

    // Escanear el QR de una sala (móvil o PC) con el escáner de Google Play: no pide
    // permiso de cámara. Un QR válido rellena el código y entra directamente.
    var scanError by remember { mutableStateOf<String?>(null) }
    fun scanRoomQr() {
        scanError = null
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = runCatching { GmsBarcodeScanning.getClient(context, options) }.getOrElse {
            scanError = "No se pudo abrir el escáner. Escribe el código a mano."
            return
        }
        runCatching { scanner.startScan() }.getOrElse {
            scanError = "No se pudo abrir el escáner. Escribe el código a mano."
            return
        }
            .addOnSuccessListener { barcode ->
                val target = TogetherLink.parseScannedQr(barcode.rawValue.orEmpty())
                if (target == null) {
                    scanError = "Este QR no es de una sala de Dezzy"
                } else {
                    // Sala en línea: el código; sala LAN: el enlace completo.
                    privateRoomCode = if (target.length <= 8) target else ""
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    openOrJoin(target)
                }
            }
            .addOnFailureListener {
                scanError = "No se pudo abrir el escáner. Escribe el código a mano."
            }
    }

    fun fetchRooms() {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            try {
                val baseUrl = when {
                    serverUrl.contains("dezzyyyy-listen-together") || serverUrl.contains("huggingface.co/spaces/Dezzyyyy/listen-together") -> {
                        "https://dezzyyyy-listen-together.hf.space"
                    }
                    serverUrl.startsWith("wss://") -> "https://" + serverUrl.removePrefix("wss://").removeSuffix("/ws").trimEnd('/')
                    serverUrl.startsWith("ws://") -> "http://" + serverUrl.removePrefix("ws://").removeSuffix("/ws").trimEnd('/')
                    else -> serverUrl.trimEnd('/')
                }
                val request = Request.Builder()
                    .url("$baseUrl/api/v1/public-rooms")
                    .header("Accept", "application/json")
                    .build()
                val client = OkHttpClient.Builder()
                    .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string().orEmpty()
                    val json = JSONObject(bodyStr)
                    val roomsArray = json.optJSONArray("rooms") ?: JSONArray()
                    val list = mutableListOf<TogetherPublicRoom>()
                    for (i in 0 until roomsArray.length()) {
                        val r = roomsArray.getJSONObject(i)
                        val hostName = r.optString("hostName", "Anfitrión")
                        val code = r.optString("code")
                        val sessionId = r.optString("sessionId", code)
                        // Ocultar salas de test
                        if (hostName.contains("Test", ignoreCase = true) || hostName.equals("TestDezzy", ignoreCase = true) || code.equals("S6F4D5", ignoreCase = true) || sessionId.equals("S6F4D5", ignoreCase = true)) {
                            continue
                        }
                        val themeStr = r.optString("theme", "MIXED")
                        val themeEnum = runCatching { TogetherRoomTheme.valueOf(themeStr) }.getOrDefault(TogetherRoomTheme.MIXED)
                        list.add(
                            TogetherPublicRoom(
                                sessionId = sessionId,
                                code = code,
                                hostName = hostName,
                                theme = themeEnum,
                                participantCount = r.optInt("participantCount", 0),
                                currentTrackTitle = r.optString("currentTrackTitle").takeIf { it.isNotBlank() },
                                currentTrackArtist = r.optString("currentTrackArtist").takeIf { it.isNotBlank() },
                                currentTrackThumbnail = r.optString("currentTrackThumbnail").takeIf { it.isNotBlank() },
                                isPublic = r.optBoolean("isPublic", true),
                                themeName = themeStr
                            )
                        )
                    }
                    withContext(Dispatchers.Main) {
                        publicRooms = list
                    }
                }
            } catch (_: Exception) {
            } finally {
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        fetchRooms()
        while (isActive) {
            delay(5000)
            fetchRooms()
        }
    }

    val filteredRooms = publicRooms.filter { room ->
        val matchesTheme = selectedTheme == null || room.theme == selectedTheme || room.themeName?.equals(selectedTheme?.name, ignoreCase = true) == true
        val matchesQuery = searchQuery.isBlank() ||
            room.hostName.contains(searchQuery, ignoreCase = true) ||
            room.code.contains(searchQuery, ignoreCase = true) ||
            (room.currentTrackTitle?.contains(searchQuery, ignoreCase = true) == true) ||
            (room.currentTrackArtist?.contains(searchQuery, ignoreCase = true) == true) ||
            room.theme.name.contains(searchQuery, ignoreCase = true) ||
            (room.themeName?.contains(searchQuery, ignoreCase = true) == true)
        matchesTheme && matchesQuery
    }.sortedByDescending { myRoom.membershipIn(it.code) != TogetherRoomMembership.NONE }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    )
                )
            )
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = if (selectedTab == 0) "Juntos" else "Unirse con código",
                        fontWeight = FontWeight.Bold,
                    )
                    if (selectedTab == 0) {
                        Text(
                            text = "Escucha en sincronía con otras personas",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = {
                androidx.compose.material3.IconButton(
                    onClick = { if (selectedTab == 1) selectedTab = 0 else navController.navigateUp() }
                ) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = "Volver")
                }
            },
            actions = {
                if (selectedTab == 0) {
                    androidx.compose.material3.IconButton(
                        onClick = {
                            showSearch = !showSearch
                            if (!showSearch) searchQuery = ""
                        }
                    ) {
                        Icon(
                            painter = painterResource(if (showSearch) R.drawable.close else R.drawable.search),
                            contentDescription = if (showSearch) "Cerrar búsqueda" else "Buscar salas",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    androidx.compose.material3.IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            fetchRooms()
                        }
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.sync),
                                contentDescription = "Actualizar",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            },
            scrollBehavior = scrollBehavior,
            modifier = Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            ),
        )

        BackHandler(enabled = selectedTab == 1) { selectedTab = 0 }

        if (selectedTab == 0) {
            // Toda la pestaña es una sola lista con scroll (buscador, filtros y
            // salas o estado vacío). Antes el estado vacío era un Box fijo: con
            // el mini reproductor visible le quedaba poco alto y el botón
            // "Crear sala pública" se aplastaba.
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    // El mini reproductor cubre la parte inferior: este padding
                    // deja el último elemento siempre por encima de él.
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                    ),
            ) {
                item(key = "actions") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TogetherActionCard(
                            iconRes = R.drawable.add_circle,
                            title = "Crear sala",
                            subtitle = "Tú eliges la música",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            onClick = { navController.navigate("settings/music_together") },
                            modifier = Modifier.weight(1f),
                        )
                        TogetherActionCard(
                            iconRes = R.drawable.lock,
                            title = "Unirse con código",
                            subtitle = "Código de 6 caracteres o QR",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedTab = 1
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (showSearch) {
                    item(key = "search") {
                        androidx.compose.material3.OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Buscar salas por anfitrión, código o tema...", style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = {
                                Icon(painter = painterResource(R.drawable.search), contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    androidx.compose.material3.IconButton(onClick = { searchQuery = "" }) {
                                        Icon(painterResource(R.drawable.close), contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            )
                        )
                    }
                }

                item(key = "rooms_header") {
                    TogetherSectionHeader(
                        title = "Salas públicas",
                        trailing = publicRooms.size.takeIf { it > 0 }?.let { n ->
                            if (n == 1) "1 activa" else "$n activas"
                        },
                        live = true,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                // Theme filter chips
                item(key = "filters") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        themeFilters.forEach { filter ->
                            FilterChip(
                                selected = selectedTheme == filter.theme,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedTheme = if (selectedTheme == filter.theme) null else filter.theme
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(filter.iconRes),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (selectedTheme == filter.theme) {
                                            filter.theme?.let { themeColor(it) } ?: MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                },
                                label = { Text(filter.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = filter.theme?.let { themeColor(it) }?.copy(alpha = 0.2f)
                                        ?: MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = filter.theme?.let { themeColor(it) }
                                        ?: MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                }

                if (filteredRooms.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp, vertical = 40.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.resource_public),
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "No hay salas públicas activas",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = "¡Sé el primero! Crea una sala pública y comparte tu música con el mundo.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { navController.navigate("settings/music_together") },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.add),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Crear sala pública")
                            }
                        }
                    }
                } else {
                    items(filteredRooms, key = { it.sessionId }) { room ->
                        TogetherPublicRoomRow(
                            room = room,
                            membership = myRoom.membershipIn(room.code),
                            onJoin = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                openOrJoin(room.code)
                            },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        } else {
            // Private code join tab
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    // El mini reproductor cubre la parte inferior: sin este
                    // padding el botón "Conectarse a la sesión" y la tarjeta
                    // de anfitrión quedaban debajo de él y no se podían pulsar.
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)
                    )
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(76.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.lock),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                Text(
                    text = "Unirse a Sala Privada",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = "Escribe el código o escanea el QR del móvil o del PC.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(Modifier.height(8.dp))

                androidx.compose.material3.OutlinedTextField(
                    value = privateRoomCode,
                    onValueChange = {
                        privateRoomCode = it.uppercase().take(8)
                        scanError = null
                    },
                    placeholder = {
                        Text(
                            "CÓDIGO (ej. AB12CD)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        letterSpacing = 4.sp
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    trailingIcon = {
                        if (privateRoomCode.isNotEmpty()) {
                            androidx.compose.material3.IconButton(onClick = { privateRoomCode = "" }) {
                                Icon(painterResource(R.drawable.close), null, modifier = Modifier.size(18.dp))
                            }
                        } else Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.IconButton(
                                onClick = {
                                    val text = clipboard.getText()?.text?.trim().orEmpty()
                                    if (text.isNotBlank()) {
                                        privateRoomCode = text.uppercase().take(8)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.link),
                                    contentDescription = "Pegar",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            androidx.compose.material3.FilledTonalButton(
                                onClick = { scanRoomQr() },
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .height(40.dp),
                            ) {
                                Icon(painterResource(R.drawable.qr_code_scanner), null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Escanear", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    },
                    supportingText = scanError?.let { msg ->
                        { Text(msg, color = MaterialTheme.colorScheme.error) }
                    },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                )

                Spacer(Modifier.height(4.dp))

                Button(
                    onClick = {
                        val trimmed = privateRoomCode.trim().uppercase()
                        if (trimmed.isNotBlank()) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            openOrJoin(trimmed)
                        }
                    },
                    enabled = privateRoomCode.trim().length >= 4,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .height(52.dp),
                ) {
                    Icon(painterResource(R.drawable.login), null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Conectarse a la sesión", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                Spacer(Modifier.height(16.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.add),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "¿Quieres ser el anfitrión?",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Crea tu propia sala y comparte tu música.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        TextButton(
                            onClick = { navController.navigate("settings/music_together") }
                        ) {
                            Text("Crear", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PublicRoomCard(
    room: TogetherPublicRoom,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = themeColor(room.theme)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(color.copy(alpha = 0.12f), Color.Transparent)
                    )
                )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Theme badge (Clean minimalist vector icon)
                    Surface(
                        shape = CircleShape,
                        color = color.copy(alpha = 0.15f),
                        modifier = Modifier.scale(pulse),
                    ) {
                        Box(
                            modifier = Modifier.size(44.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(themeIcon(room.theme)),
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        val displayTheme = room.themeName?.takeIf { it.isNotBlank() } ?: room.theme.name
                        Text(
                            text = displayTheme.lowercase().replaceFirstChar { it.uppercase() } + " Room",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Host: ${room.hostName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    // Listener count
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.group),
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${room.participantCount}",
                            style = MaterialTheme.typography.labelLarge,
                            color = color,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // Current track
                room.currentTrackTitle?.let { title ->
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(color.copy(alpha = 0.08f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.graphic_eq),
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            room.currentTrackArtist?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onJoin,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = color,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Unirse a la sala", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
