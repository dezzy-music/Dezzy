/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.R
import com.dezzy.music.p2p.DesktopLink
import com.dezzy.music.ui.utils.backToMain
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch

private sealed interface LinkUi {
    data object Idle : LinkUi
    data class Working(val step: DesktopLink.Step) : LinkUi
    data object Done : LinkUi
    data class Failed(val message: String) : LinkUi
}

/**
 * «Vincular escritorio»: pasa las sesiones de este móvil a Dezzy en el ordenador escaneando su QR
 * o escribiendo su código. El ordenador nunca inicia sesión por su cuenta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopLinkScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var sessions by remember { mutableStateOf<DesktopLink.Sessions?>(null) }
    var input by remember { mutableStateOf("") }
    var ui by remember { mutableStateOf<LinkUi>(LinkUi.Idle) }
    var inputError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { sessions = DesktopLink.currentSessions(context) }

    val busy = ui is LinkUi.Working
    val canSend = sessions?.isEmpty == false && !busy

    fun send(raw: String) {
        val current = sessions ?: return
        val code = DesktopLink.parseCode(raw)
        if (code == null) {
            inputError = "El código tiene 8 letras y números, como en el ordenador."
            return
        }
        inputError = null
        keyboard?.hide()
        ui = LinkUi.Working(DesktopLink.Step.SEARCHING)
        scope.launch {
            val outcome = DesktopLink.send(code, current) { step -> ui = LinkUi.Working(step) }
            ui = when (outcome) {
                DesktopLink.Outcome.Linked -> LinkUi.Done
                is DesktopLink.Outcome.Failed -> LinkUi.Failed(outcome.message)
            }
        }
    }

    fun scan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        // Si el escáner de Google Play no se puede crear, avisar en vez de cerrar la app.
        val scanner = runCatching { GmsBarcodeScanning.getClient(context, options) }.getOrElse {
            inputError = "No se pudo abrir el escáner. Escribe el código a mano."
            return
        }
        runCatching { scanner.startScan() }.getOrElse {
            inputError = "No se pudo abrir el escáner. Escribe el código a mano."
            return
        }
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue.orEmpty()
                val code = DesktopLink.parseCode(raw)
                if (code == null) {
                    inputError = "Ese QR no es de Dezzy para ordenador."
                } else {
                    input = code.chunked(4).joinToString("-")
                    send(code)
                }
            }
            .addOnFailureListener {
                inputError = "No se pudo abrir el escáner. Escribe el código a mano."
            }
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))

        Text(
            text = "Usa tus cuentas en Dezzy para ordenador sin iniciar sesión allí. Abre Dezzy en el ordenador y " +
                "escanea el QR que aparece, o escribe su código.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )

        // Qué se va a pasar
        DezzySectionLabel("Se pasará al ordenador")
        val current = sessions
        val shared = buildList {
            if (current?.youtubeCookie != null) {
                add(Triple(R.drawable.person, "YouTube Music", current.youtubeName ?: "Tu cuenta"))
            }
            if (current?.spotifySpDc != null) add(Triple(R.drawable.link, "Spotify", "Tu biblioteca"))
            if (current?.socialKey != null && com.dezzy.music.constants.SocialFeatures.ENABLED) add(Triple(R.drawable.group, "Red social", "Tu perfil, publicaciones y chats"))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when {
                current == null -> InfoRow(R.drawable.smartphone, "Comprobando tus cuentas…", null, dezzyItemShape(0, 1))
                shared.isEmpty() -> InfoRow(
                    icon = R.drawable.error,
                    title = "No hay ninguna cuenta iniciada",
                    subtitle = "Inicia sesión en Cuentas y servicios y vuelve aquí.",
                    shape = dezzyItemShape(0, 1),
                    tone = DezzyIconTone.ERROR,
                )
                else -> shared.forEachIndexed { i, (icon, title, subtitle) ->
                    InfoRow(icon, title, subtitle, dezzyItemShape(i, shared.size))
                }
            }
        }

        DezzySectionLabel("Vincular")
        when (val state = ui) {
            LinkUi.Done -> DoneCard(onAnother = {
                ui = LinkUi.Idle
                input = ""
            })
            else -> {
                Button(
                    onClick = ::scan,
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Icon(painterResource(R.drawable.desktop_windows), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Escanear QR")
                }

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        input = value.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(9)
                        inputError = null
                    },
                    label = { Text("O escribe el código") },
                    placeholder = { Text("XXXX-XXXX") },
                    singleLine = true,
                    enabled = canSend,
                    isError = inputError != null,
                    supportingText = inputError?.let { { Text(it, fontSize = 13.sp) } },
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { send(input) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { send(input) },
                    enabled = canSend && input.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("Vincular con el código")
                }

                when (state) {
                    is LinkUi.Working -> StatusLine(
                        progress = true,
                        text = when (state.step) {
                            DesktopLink.Step.SEARCHING -> "Buscando el ordenador…"
                            DesktopLink.Step.SENDING -> "Pasando tus cuentas…"
                        },
                    )
                    is LinkUi.Failed -> StatusLine(progress = false, text = state.message, error = true)
                    else -> Unit
                }
            }
        }

        Text(
            text = "Viaja cifrado de extremo a extremo y sin servidores de Dezzy. El código sólo sirve unos minutos " +
                "y el ordenador guarda las sesiones cifradas. Puedes desvincularlo desde sus ajustes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp),
        )

        Spacer(Modifier.height(32.dp))
    }

    TopAppBar(
        title = { Text("Vincular escritorio") },
        navigationIcon = {
            com.dezzy.music.ui.component.IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = "Atrás")
            }
        },
    )
}

@Composable
private fun InfoRow(
    icon: Int,
    title: String,
    subtitle: String?,
    shape: androidx.compose.ui.graphics.Shape,
    tone: DezzyIconTone = DezzyIconTone.PRIMARY,
) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) {},
        ) {
            DezzySettingsIcon(icon, tone)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StatusLine(progress: Boolean, text: String, error: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        if (progress) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                painterResource(R.drawable.error),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DoneCard(onAnother: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Ordenador vinculado",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                "Tu biblioteca ya está en Dezzy para ordenador.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = onAnother, modifier = Modifier.padding(top = 4.dp)) {
                Text("Vincular otro ordenador")
            }
        }
    }
}
