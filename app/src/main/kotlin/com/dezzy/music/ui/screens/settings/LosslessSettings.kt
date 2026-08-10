/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.R
import com.dezzy.music.lossless.LosslessEnabledKey
import com.dezzy.music.lossless.LosslessExportToPublicKey
import com.dezzy.music.lossless.DefaultLosslessResolverUrl
import com.dezzy.music.lossless.LosslessResolverUrlKey
import com.dezzy.music.lossless.LosslessWifiOnlyKey
import com.dezzy.music.ui.component.EditTextPreference
import com.dezzy.music.ui.component.IconButton
import com.dezzy.music.ui.component.InfoLabel
import com.dezzy.music.ui.component.PreferenceEntry
import com.dezzy.music.ui.component.PreferenceGroupTitle
import com.dezzy.music.ui.component.SwitchPreference
import com.dezzy.music.ui.utils.backToMain
import com.dezzy.music.utils.rememberPreference
import com.dezzy.music.viewmodels.LosslessViewModel

/**
 * Ajustes del subsistema lossless (estilo SpotiFLAC). Configura el *resolver
 * propio* (la app no guarda credenciales de streaming ni usa el backend gated de
 * SpotiFLAC), y lista las extensiones del registry real.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LosslessSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: LosslessViewModel = hiltViewModel(),
) {
    val (enabled, onEnabledChange) = rememberPreference(LosslessEnabledKey, false)
    val (resolverUrl, onResolverUrlChange) =
        rememberPreference(LosslessResolverUrlKey, DefaultLosslessResolverUrl)
    val (wifiOnly, onWifiOnlyChange) = rememberPreference(LosslessWifiOnlyKey, false)
    val (exportToPublic, onExportToPublicChange) = rememberPreference(LosslessExportToPublicKey, false)

    val extensions by viewModel.extensions.collectAsState()
    val isLoadingExtensions by viewModel.isLoadingExtensions.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadExtensions() }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            )
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)),
        )

        PreferenceGroupTitle(title = "General")

        SwitchPreference(
            title = { Text("Habilitar descargas lossless") },
            description = "Buscar y descargar pistas en FLAC/Opus como archivos locales",
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )

        PreferenceEntry(
            title = { Text("Buscar y descargar") },
            description = "Abre el buscador de canciones",
            icon = { Icon(painterResource(R.drawable.search), null) },
            onClick = { navController.navigate("lossless") },
            isEnabled = enabled,
        )

        PreferenceEntry(
            title = { Text("Cola de descargas") },
            description = "Ver el progreso y limpiar terminadas",
            icon = { Icon(painterResource(R.drawable.downloading), null) },
            onClick = { navController.navigate("lossless/downloads") },
            isEnabled = enabled,
        )

        PreferenceGroupTitle(title = "Resolver")

        EditTextPreference(
            title = { Text("URL del resolver") },
            icon = { Icon(painterResource(R.drawable.link), null) },
            value = resolverUrl,
            onValueChange = onResolverUrlChange,
            isInputValid = { it.isBlank() || it.startsWith("http") },
        )

        SwitchPreference(
            title = { Text("Solo por Wi-Fi") },
            description = "No descargar con datos móviles",
            checked = wifiOnly,
            onCheckedChange = onWifiOnlyChange,
        )

        SwitchPreference(
            title = { Text("Copiar a Música/Dezzy") },
            description = "Guarda también una copia en la carpeta pública, visible en otras apps",
            checked = exportToPublic,
            onCheckedChange = onExportToPublicChange,
        )

        InfoLabel(
            text = "Dezzy no se conecta al backend de SpotiFLAC ni guarda cuentas de " +
                "streaming. Apunta esta URL a un resolver que tú controles; devolverá el " +
                "enlace directo del archivo lossless. La búsqueda funciona sin resolver.",
        )

        PreferenceGroupTitle(title = "Extensiones")

        if (isLoadingExtensions && extensions.isEmpty()) {
            PreferenceEntry(
                title = { Text("Cargando extensiones…") },
                description = null,
                icon = { Icon(painterResource(R.drawable.sync), null) },
            )
        }

        extensions.forEach { ext ->
            PreferenceEntry(
                title = { Text(ext.displayName) },
                description = ext.description,
                icon = { Icon(painterResource(R.drawable.download), null) },
                trailingContent = {
                    Text(
                        text = if (ext.provider != null) "Soportado" else ext.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }

    TopAppBar(
        title = { Text("Descarga lossless") },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
