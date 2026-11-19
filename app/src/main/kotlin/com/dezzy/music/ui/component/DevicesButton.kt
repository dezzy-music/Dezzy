/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.dezzy.music.R
import com.dezzy.music.playback.cast.CastOptionsProvider
import com.google.android.gms.cast.CastMediaControlIntent

/**
 * Botón único de dispositivos de los reproductores: abre «Conectar a un dispositivo»,
 * donde están tus otros Dezzy, los Chromecast, Bluetooth y Escuchar juntos.
 * Sustituye al botón suelto de Cast. Mientras se transmite a un Chromecast el icono
 * cambia a «cast_connected» y se pinta con [activeTint].
 */
@Composable
fun DevicesButton(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    activeTint: Color = tint,
    iconSize: Dp = 24.dp,
    onOpenListenTogether: (() -> Unit)? = null,
    /** Icono en reposo (sin emitir); el estilo Mínimo pasa su versión de trazo fino. */
    idleIcon: Int = R.drawable.devices,
) {
    val casting = rememberIsCasting()
    var showSheet by remember { mutableStateOf(false) }

    IconButton(onClick = { showSheet = true }, modifier = modifier) {
        Icon(
            painter = painterResource(if (casting) R.drawable.cast_connected else idleIcon),
            contentDescription = "Conectar a un dispositivo",
            tint = if (casting) activeTint else tint,
            modifier = Modifier.size(iconSize),
        )
    }

    if (showSheet) {
        ConnectDeviceBottomSheet(
            onDismissRequest = { showSheet = false },
            onOpenListenTogether = onOpenListenTogether?.let { open ->
                {
                    showSheet = false
                    open()
                }
            },
        )
    }
}

/** true mientras hay una ruta de Google Cast seleccionada. */
@Composable
fun rememberIsCasting(): Boolean = rememberCastingRouteName() != null

/** Nombre del Chromecast al que se está transmitiendo, o null (sin búsqueda activa). */
@Composable
fun rememberCastingRouteName(): String? {
    val context = LocalContext.current
    val mediaRouter = remember { MediaRouter.getInstance(context.applicationContext) }
    val selector = remember {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastOptionsProvider.APP_ID))
            .build()
    }
    var name by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        fun refresh() {
            val route = mediaRouter.selectedRoute
            name = route.takeIf { !it.isDefault && it.matchesSelector(selector) }?.name
        }
        val callback = object : MediaRouter.Callback() {
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        }
        mediaRouter.addCallback(selector, callback)
        refresh()
        onDispose { mediaRouter.removeCallback(callback) }
    }
    return name
}
