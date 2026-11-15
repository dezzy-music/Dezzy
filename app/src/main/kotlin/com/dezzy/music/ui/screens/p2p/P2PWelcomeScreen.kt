/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.p2p

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import com.dezzy.music.R
import com.dezzy.music.p2p.P2PIdentity
import com.dezzy.music.p2p.P2PSocial
import kotlinx.coroutines.launch

private enum class WelcomeStep { INTRO, WORDS, RESTORE }

/**
 * A1 · «Tu cuenta vive en tu teléfono»: crea las claves en el dispositivo, enseña las 12
 * palabras de respaldo y permite recuperar una cuenta existente.
 */
@Composable
fun P2PWelcomeScreen(onDone: () -> Unit, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(WelcomeStep.INTRO) }
    var name by rememberSaveable { mutableStateOf("") }
    var words by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    when (step) {
        WelcomeStep.INTRO -> WelcomeIntro(
            name = name,
            onNameChange = { name = it.take(40) },
            busy = busy,
            onBack = onBack,
            onCreate = {
                busy = true
                scope.launch {
                    words = P2PIdentity.create(context)
                    P2PSocial.onSignedIn()
                    P2PSocial.scope.launch {
                        P2PSocial.updateMyProfile(name.ifBlank { "Dezzy" }, "", null, isPrivate = false)
                    }
                    busy = false
                    step = WelcomeStep.WORDS
                }
            },
            onRestore = { step = WelcomeStep.RESTORE },
        )

        WelcomeStep.WORDS -> P2PBackupWordsContent(
            words = words,
            onConfirm = {
                P2PIdentity.markBackedUp(context)
                onDone()
            },
        )

        WelcomeStep.RESTORE -> RestoreContent(
            busy = busy,
            onBack = { step = WelcomeStep.INTRO },
            onRestore = { input, onError ->
                busy = true
                scope.launch {
                    val ok = P2PIdentity.restore(context, input)
                    busy = false
                    if (ok) {
                        P2PSocial.onSignedIn()
                        onDone()
                    } else {
                        onError()
                    }
                }
            },
        )
    }
}

@Composable
private fun WelcomeIntro(
    name: String,
    onNameChange: (String) -> Unit,
    busy: Boolean,
    onBack: (() -> Unit)?,
    onCreate: () -> Unit,
    onRestore: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.surface)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = "Volver")
            }
        } else {
            Spacer(Modifier.height(24.dp))
        }
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(72.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.token), null, Modifier.size(36.dp), tint = scheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Tu cuenta vive en tu teléfono",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Sin correo ni contraseña. Nadie, ni Dezzy, puede leer tus mensajes.",
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainerLow) {
            Column(Modifier.padding(vertical = 8.dp)) {
                FeatureRow(R.drawable.lock, "Cifrado de extremo a extremo", "Los chats se cifran aquí antes de salir.")
                FeatureRow(R.drawable.wifi, "Sin servidores de Dezzy", "Viaja por relés públicos gratuitos que no pueden leerlo.")
                FeatureRow(R.drawable.security, "12 palabras de respaldo", "Te las mostramos al terminar. Guárdalas: son tu llave.")
            }
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Tu nombre") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            supportingText = { Text("Así te verán tus amigos. Puedes cambiarlo luego.") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onCreate,
            enabled = !busy && name.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = scheme.onPrimary)
            } else {
                Text("Crear mi identidad")
            }
        }
        TextButton(
            onClick = onRestore,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Text("Ya tengo 12 palabras")
        }
    }
}

@Composable
private fun FeatureRow(icon: Int, title: String, body: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Impide capturas de pantalla mientras se ven las 12 palabras. */
@Composable
fun SecureWindow() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** Las 12 palabras de respaldo, con confirmación explícita. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun P2PBackupWordsContent(words: List<String>, onConfirm: () -> Unit, confirmLabel: String = "Ya las guardé") {
    SecureWindow()
    val scheme = MaterialTheme.colorScheme
    var checked by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.surface)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Tus 12 palabras",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Escríbelas en papel, en este orden. Con ellas recuperas tu cuenta, tus amigos y tus chats en otro teléfono. Quien las tenga puede hacerse pasar por ti: no las compartas.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3,
            modifier = Modifier.fillMaxWidth(),
        ) {
            words.forEachIndexed { index, word ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = scheme.surfaceContainerHigh,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 96.dp),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.primary,
                            modifier = Modifier.widthIn(min = 20.dp),
                        )
                        Text(word, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { checked = it })
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.size(8.dp))
            Text(
                "Las guardé en un lugar seguro. Sé que sin ellas no se puede recuperar la cuenta.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            onClick = onConfirm,
            enabled = checked,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) {
            Text(confirmLabel)
        }
    }
}

@Composable
private fun RestoreContent(busy: Boolean, onBack: () -> Unit, onRestore: (String, onError: () -> Unit) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var input by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.surface)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) {
            Icon(painterResource(R.drawable.arrow_back), contentDescription = "Volver")
        }
        Text(
            "Recuperar mi cuenta",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Escribe tus 12 palabras separadas por espacios (o tu clave nsec). Tus amigos, publicaciones y chats vuelven solos desde los relés.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                error = false
            },
            label = { Text("12 palabras o nsec…") },
            isError = error,
            supportingText = {
                Text(
                    if (error) "No coinciden. Revisa el orden y la ortografía." else "Nunca te pediremos estas palabras por mensaje.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            minLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onRestore(input) { error = true } },
            enabled = !busy && input.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = scheme.onPrimary) else Text("Recuperar")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Crear una cuenta nueva") }
        Text(
            "Las palabras se comprueban en tu teléfono; no se envían a ningún sitio.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
