/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.arturo254.opentune.R
import com.arturo254.opentune.constants.CastBetaNoticeShownKey
import com.arturo254.opentune.playback.cast.CastOptionsProvider
import com.arturo254.opentune.utils.rememberPreference
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.common.util.concurrent.MoreExecutors

/**
 * Pure-Compose Cast button + device picker.
 *
 * Deliberately NOT androidx.mediarouter.app.MediaRouteButton: that widget requires an
 * AppCompat theme to resolve its colors (this app ships a platform Material theme, which
 * crashed with "background can not be translucent") and its picker dialog additionally
 * requires the host to be a FragmentActivity (MainActivity is a plain ComponentActivity).
 * Selecting a route whose category matches our receiver App ID makes the Cast framework
 * start the CastSession on its own, so none of that machinery is actually needed.
 */
@Composable
fun CastButton(
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color? = null,
    /** Optional custom icons; defaults to the cast glyphs. Lets callers (e.g. a "devices"
     *  button) reuse the same device picker under a different icon. */
    icon: Int? = null,
    iconConnected: Int? = null,
) {
    val context = LocalContext.current
    val mediaRouter = remember { MediaRouter.getInstance(context.applicationContext) }
    val selector = remember {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastOptionsProvider.APP_ID))
            .build()
    }

    var routes by remember { mutableStateOf(listOf<MediaRouter.RouteInfo>()) }
    var selectedRoute by remember { mutableStateOf<MediaRouter.RouteInfo?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    // First-use guidance: Chromecast is a beta feature — say so once before the picker opens.
    val (castBetaNoticeShown, setCastBetaNoticeShown) = rememberPreference(CastBetaNoticeShownKey, false)
    var showBetaNotice by remember { mutableStateOf(false) }

    fun refresh() {
        routes = mediaRouter.routes.filter { !it.isDefault && it.matchesSelector(selector) }
        selectedRoute = mediaRouter.selectedRoute
            .takeIf { !it.isDefault && it.matchesSelector(selector) }
    }

    DisposableEffect(Unit) {
        // Warm up the Cast framework so selecting a route auto-starts the session.
        runCatching {
            CastContext.getSharedInstance(context.applicationContext, MoreExecutors.directExecutor())
        }
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
        }
        mediaRouter.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        refresh()
        onDispose { mediaRouter.removeCallback(callback) }
    }

    // Active scan only while the picker is open — it's expensive on battery/network.
    if (showPicker) {
        DisposableEffect(Unit) {
            val activeScanCallback = object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
                override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            }
            mediaRouter.addCallback(
                selector,
                activeScanCallback,
                MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN,
            )
            refresh()
            onDispose { mediaRouter.removeCallback(activeScanCallback) }
        }
    }

    val connected = selectedRoute != null

    // Always visible: freshly powered-on devices often only show up once the picker's
    // active scan runs, so hiding the button until passive discovery finds something
    // made it look like Cast was broken.
    IconButton(
        onClick = {
            if (!castBetaNoticeShown) showBetaNotice = true else showPicker = true
        },
        modifier = modifier
    ) {
        Icon(
            painter = painterResource(
                if (connected) (iconConnected ?: R.drawable.cast_connected)
                else (icon ?: R.drawable.cast)
            ),
            contentDescription = "Cast",
            tint = if (connected) MaterialTheme.colorScheme.primary else (tint ?: LocalContentColor.current),
        )
    }

    if (showBetaNotice) {
        AlertDialog(
            onDismissRequest = { showBetaNotice = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.cast),
                    contentDescription = null,
                )
            },
            title = { Text("Chromecast (Beta)") },
            text = {
                Text(
                    "La transmisión por Chromecast es una función en beta.\n\n" +
                        "• Reproduce en tu TV con letras sincronizadas y fondo dinámico.\n" +
                        "• Controla la reproducción y el volumen desde el teléfono.\n" +
                        "• Si algo no se ve bien, detén la transmisión y vuelve a conectar.\n\n" +
                        "Seguimos puliendo esta experiencia; ¡gracias por probarla!"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        setCastBetaNoticeShown(true)
                        showBetaNotice = false
                        showPicker = true
                    },
                ) {
                    Text("Entendido")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBetaNotice = false }) {
                    Text("Ahora no")
                }
            },
        )
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.cast),
                    contentDescription = null,
                )
            },
            title = { Text(if (connected) "Transmitiendo a ${selectedRoute?.name}" else "Transmitir a") },
            text = {
                Column {
                    if (routes.isEmpty()) {
                        Text("Buscando dispositivos…")
                    }
                    routes.forEach { route ->
                        val isThisSelected = route.id == selectedRoute?.id
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!isThisSelected) route.select()
                                    showPicker = false
                                }
                                .padding(vertical = 12.dp),
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (isThisSelected) R.drawable.cast_connected else R.drawable.cast,
                                ),
                                contentDescription = null,
                                tint = if (isThisSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                },
                                modifier = Modifier.size(22.dp),
                            )
                            Column(modifier = Modifier.padding(start = 14.dp)) {
                                Text(
                                    text = route.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isThisSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        LocalContentColor.current
                                    },
                                )
                                route.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (connected) {
                    TextButton(
                        onClick = {
                            mediaRouter.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
                            showPicker = false
                        },
                    ) {
                        Text("Dejar de transmitir")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("Cancelar")
                }
            },
        )
    }
}
