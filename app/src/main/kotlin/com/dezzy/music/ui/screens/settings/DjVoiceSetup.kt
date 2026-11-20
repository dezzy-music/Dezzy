/*
 * Dezzy Project Original (2026)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.R
import com.dezzy.music.ai.DjVoice
import com.dezzy.music.constants.DjLanguageKey
import com.dezzy.music.constants.DjNameKey
import com.dezzy.music.constants.DjVoiceKey
import androidx.compose.material3.IconButton
import com.dezzy.music.utils.dataStore
import com.dezzy.music.utils.getAsync
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Asistente del boceto B, 4 pasos: 1) cerebro (clave de Gemini, se puede saltar), 2) proveedor de
 * voz, 3) clave con guía, 4) voz y prueba. Con «Voz gratuita» se guarda en el paso 2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjVoiceSetup(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val player = rememberDjPreviewPlayer()

    var step by remember { mutableStateOf(1) }
    var storedProvider by remember { mutableStateOf(DjVoice.FREE) }
    var provider by remember { mutableStateOf(DjVoice.ELEVENLABS) }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<DjVoice.KeyCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    var voiceId by remember { mutableStateOf("") }
    var loadingPreview by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var djName by remember { mutableStateOf("Dezzy") }
    var language by remember { mutableStateOf("auto") }
    var geminiKey by remember { mutableStateOf("") }
    var showGemini by remember { mutableStateOf(false) }
    var geminiCheck by remember { mutableStateOf<DjVoice.KeyCheck?>(null) }
    var checkingGemini by remember { mutableStateOf(false) }

    // Al volver a abrirlo («Cambiar voz») parte de lo que ya había.
    LaunchedEffect(Unit) {
        storedProvider = DjVoice.provider(context)
        if (storedProvider != DjVoice.FREE) {
            provider = storedProvider
            key = DjVoice.apiKey(context)
        }
        voiceId = context.dataStore.getAsync(DjVoiceKey, "")
        djName = context.dataStore.getAsync(DjNameKey, "").ifBlank { "Dezzy" }
        language = context.dataStore.getAsync(DjLanguageKey, "auto")
        geminiKey = DjVoice.geminiKey(context)
    }

    LaunchedEffect(geminiKey) {
        geminiCheck = null
        if (geminiKey.trim().length < 20) return@LaunchedEffect
        delay(600)
        checkingGemini = true
        geminiCheck = DjVoice.checkGemini(geminiKey)
        checkingGemini = false
    }

    // Comprueba la clave sola en cuanto parece completa.
    LaunchedEffect(provider, key) {
        check = null
        if (provider == DjVoice.FREE || key.trim().length < 20) return@LaunchedEffect
        delay(600)
        checking = true
        check = DjVoice.check(provider, key)
        checking = false
    }

    val voices = when (provider) {
        DjVoice.OPENAI -> DjVoice.OPENAI_VOICES
        DjVoice.ELEVENLABS -> check?.voices.orEmpty()
        else -> emptyList()
    }
    val selectedVoice = voices.firstOrNull { it.id == voiceId }
        ?: voices.firstOrNull { it.id == DjVoice.defaultVoice(provider) }
        ?: voices.firstOrNull()

    fun back() {
        player.stop()
        if (step > 1) step-- else navigateUpSafe(navController)
    }

    fun save() {
        if (saving) return
        saving = true
        player.stop()
        scope.launch {
            if (provider == DjVoice.FREE) {
                DjVoice.save(context, DjVoice.FREE, "", "", "")
            } else {
                val v = selectedVoice ?: DjVoice.Voice(DjVoice.defaultVoice(provider), "", "")
                DjVoice.save(context, provider, key, v.id, v.name)
            }
            Toast.makeText(context, "Voz del DJ guardada", Toast.LENGTH_SHORT).show()
            navigateUpSafe(navController)
        }
    }

    BackHandler(enabled = step > 1) { back() }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            ),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))

        StepBar(step)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            when (step) {
                1 -> BrainStep(
                    key = geminiKey,
                    showKey = showGemini,
                    checking = checkingGemini,
                    check = geminiCheck,
                    onKeyChange = { geminiKey = it.trim() },
                    onToggleShow = { showGemini = !showGemini },
                    onPaste = { clipboard.getText()?.text?.trim()?.let { geminiKey = it } },
                    onOpenSite = { uriHandler.openUri(DjVoice.GEMINI_KEY_URL) },
                )

                2 -> ProviderStep(
                    selected = provider,
                    onSelect = {
                        if (it != provider) {
                            // La clave de un proveedor no vale para otro.
                            key = if (it == storedProvider && storedProvider != DjVoice.FREE) key else ""
                            voiceId = ""
                        }
                        provider = it
                    },
                )

                3 -> KeyStep(
                    provider = provider,
                    key = key,
                    showKey = showKey,
                    checking = checking,
                    check = check,
                    onKeyChange = { key = it.trim() },
                    onToggleShow = { showKey = !showKey },
                    onPaste = { clipboard.getText()?.text?.trim()?.let { key = it } },
                    onOpenSite = { uriHandler.openUri(DjVoice.keyUrl(provider)) },
                )

                else -> VoiceStep(
                    voices = voices,
                    selectedId = selectedVoice?.id,
                    playingId = player.playingId,
                    loadingId = loadingPreview,
                    onSelect = { voiceId = it.id },
                    onListen = { v ->
                        if (player.playingId == v.id) {
                            player.stop()
                            return@VoiceStep
                        }
                        val onError = {
                            Toast.makeText(context, "No se pudo reproducir la muestra", Toast.LENGTH_SHORT).show()
                        }
                        if (v.previewUrl != null) {
                            player.play(v.previewUrl, v.id, onError)
                        } else {
                            loadingPreview = v.id
                            scope.launch {
                                val out = File(context.cacheDir, "dj_voice_${v.id}.mp3")
                                DjVoice.synthesizeWith(provider, key, v.id, sampleLine(djName, language), out)
                                    .onSuccess { player.play(out.absolutePath, v.id, onError) }
                                    .onFailure { onError() }
                                loadingPreview = null
                            }
                        }
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // Pie: Atrás / Siguiente
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 1) {
                OutlinedButton(onClick = { back() }) { Text("Atrás") }
            } else {
                // El cerebro es opcional: sin él, el DJ usa frases fijas.
                OutlinedButton(onClick = { step = 2 }) { Text("Saltar") }
            }
            Spacer(Modifier.weight(1f))
            val last = step == 4 || (step == 2 && provider == DjVoice.FREE)
            val canGo = when (step) {
                1 -> geminiCheck?.ok == true
                2 -> true
                3 -> check?.ok == true
                else -> selectedVoice != null
            }
            Button(
                enabled = canGo && !saving,
                onClick = {
                    when {
                        last -> save()
                        step == 1 -> {
                            // Se guarda ya: aunque luego no termine lo de la voz, el cerebro queda puesto.
                            scope.launch { DjVoice.saveGeminiKey(context, geminiKey) }
                            step = 2
                        }
                        else -> step++
                    }
                },
            ) {
                Text(if (last) "Guardar" else "Siguiente")
            }
        }
    }

    TopAppBar(
        title = { Text("Configurar DJ") },
        navigationIcon = {
            IconButton(onClick = { back() }) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

private fun navigateUpSafe(navController: NavController) {
    if (navController.currentDestination?.route == "settings/dj/setup") navController.navigateUp()
}

@Composable
private fun StepBar(step: Int) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 1..4) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(
                            if (i <= step) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Paso $step de 4 · " + when (step) {
                1 -> "Cerebro"
                2 -> "Proveedor de voz"
                3 -> "Tu clave"
                else -> "Elige la voz"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
}

/* ── Paso 1: cerebro ── */

@Composable
private fun BrainStep(
    key: String,
    showKey: Boolean,
    checking: Boolean,
    check: DjVoice.KeyCheck?,
    onKeyChange: (String) -> Unit,
    onToggleShow: () -> Unit,
    onPaste: () -> Unit,
    onOpenSite: () -> Unit,
) {
    StepTitle(
        "Primero, el cerebro del DJ",
        "Gemini escribe lo que dice entre canciones. Es gratis con tu cuenta de Google. " +
            "Si lo saltas, el DJ usa frases fijas.",
    )
    KeyField(
        label = "Clave de Gemini",
        placeholder = "AIza…",
        key = key,
        showKey = showKey,
        checking = checking,
        check = check,
        onKeyChange = onKeyChange,
        onToggleShow = onToggleShow,
        onPaste = onPaste,
    )
    Spacer(Modifier.height(16.dp))
    KeyGuide(
        title = "Cómo conseguirla gratis (1 minuto)",
        steps = listOf(
            "Entra en Google AI Studio con tu cuenta de Google.",
            "Pulsa «Create API key».",
            "Cópiala y vuelve aquí: se pega sola con el botón.",
        ),
        button = "Abrir Google AI Studio",
        onOpenSite = onOpenSite,
    )
}

/* ── Paso 2: proveedor de voz ── */

@Composable
private fun ProviderStep(selected: String, onSelect: (String) -> Unit) {
    StepTitle("¿Qué voz quieres para tu DJ?", "Puedes cambiarla cuando quieras.")
    ProviderCard(
        selected = selected == DjVoice.ELEVENLABS,
        title = "ElevenLabs",
        badge = "RECOMENDADA",
        description = "Las voces más naturales. Tiene plan gratuito con unos 10.000 caracteres al mes.",
        onClick = { onSelect(DjVoice.ELEVENLABS) },
    )
    ProviderCard(
        selected = selected == DjVoice.OPENAI,
        title = "OpenAI",
        badge = "TU CLAVE",
        description = "Voces claras y rápidas. Necesita saldo en tu cuenta de OpenAI.",
        onClick = { onSelect(DjVoice.OPENAI) },
    )
    ProviderCard(
        selected = selected == DjVoice.FREE,
        title = "Voz gratuita",
        badge = "GRATIS",
        description = "Sin clave ni cuenta. Suena más básica.",
        onClick = { onSelect(DjVoice.FREE) },
    )
}

@Composable
private fun ProviderCard(
    selected: Boolean,
    title: String,
    badge: String,
    description: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .padding(bottom = 10.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.surfaceContainer,
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* ── Paso 3: clave de la voz ── */

@Composable
private fun KeyStep(
    provider: String,
    key: String,
    showKey: Boolean,
    checking: Boolean,
    check: DjVoice.KeyCheck?,
    onKeyChange: (String) -> Unit,
    onToggleShow: () -> Unit,
    onPaste: () -> Unit,
    onOpenSite: () -> Unit,
) {
    val name = DjVoice.providerName(provider)
    StepTitle("Pega tu clave de $name", "La comprobamos al momento. Se guarda cifrada sólo en este móvil.")

    KeyField(
        label = "Clave de API",
        placeholder = if (provider == DjVoice.OPENAI) "sk-…" else "sk_…",
        key = key,
        showKey = showKey,
        checking = checking,
        check = check,
        onKeyChange = onKeyChange,
        onToggleShow = onToggleShow,
        onPaste = onPaste,
    )
    Spacer(Modifier.height(16.dp))
    KeyGuide(
        title = "Cómo conseguirla (1 minuto)",
        steps = if (provider == DjVoice.OPENAI) {
            listOf(
                "Entra en platform.openai.com con tu cuenta.",
                "Ve a «API keys» y pulsa «Create new secret key».",
                "Cópiala y vuelve aquí: se pega sola con el botón.",
            )
        } else {
            listOf(
                "Crea una cuenta gratis en elevenlabs.io.",
                "Abre «API Keys» y pulsa «Create API Key».",
                "Cópiala y vuelve aquí: se pega sola con el botón.",
            )
        },
        button = "Abrir $name",
        onOpenSite = onOpenSite,
    )
}

@Composable
private fun KeyField(
    label: String,
    placeholder: String,
    key: String,
    showKey: Boolean,
    checking: Boolean,
    check: DjVoice.KeyCheck?,
    onKeyChange: (String) -> Unit,
    onToggleShow: () -> Unit,
    onPaste: () -> Unit,
) {
    OutlinedTextField(
        value = key,
        onValueChange = onKeyChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(painterResource(R.drawable.key), null) },
        trailingIcon = {
            IconButton(onClick = onPaste) {
                Icon(painterResource(R.drawable.content_paste), contentDescription = "Pegar")
            }
        },
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        isError = check?.ok == false,
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        when {
            checking -> {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Comprobando…", style = MaterialTheme.typography.bodySmall)
            }
            check != null -> {
                Icon(
                    painterResource(if (check.ok) R.drawable.check else R.drawable.error),
                    null,
                    tint = if (check.ok) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    check.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (check.ok) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.error,
                )
            }
            else -> Text(
                if (key.isBlank()) "Aún no has pegado ninguna clave" else "Sigue pegando la clave completa",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        if (key.isNotBlank()) {
            TextButton(onClick = onToggleShow) { Text(if (showKey) "Ocultar" else "Ver") }
        }
    }
}

@Composable
private fun KeyGuide(title: String, steps: List<String>, button: String, onOpenSite: () -> Unit) {
    // Guía corta: el usuario no tiene por qué saber qué es una «clave de API».
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.lightbulb), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        steps.forEachIndexed { i, text ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Text(
                    "${i + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(top = 2.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(onClick = onOpenSite, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.open_in_new), null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(button)
        }
    }
}

/* ── Paso 4: voz ── */

@Composable
private fun VoiceStep(
    voices: List<DjVoice.Voice>,
    selectedId: String?,
    playingId: String?,
    loadingId: String?,
    onSelect: (DjVoice.Voice) -> Unit,
    onListen: (DjVoice.Voice) -> Unit,
) {
    StepTitle("Elige la voz", "Toca «Escuchar» para oír cada una antes de guardar.")
    if (voices.isEmpty()) {
        Text(
            "Tu cuenta no tiene voces disponibles. Se usará la voz predeterminada.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    voices.forEach { v ->
        val selected = v.id == selectedId
        Row(
            Modifier
                .padding(bottom = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.surfaceContainer,
                )
                .clickable { onSelect(v) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = { onSelect(v) })
            Column(Modifier.weight(1f)) {
                Text(v.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (v.description.isNotBlank()) {
                    Text(v.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = { onListen(v) }, enabled = loadingId == null || loadingId == v.id) {
                if (loadingId == v.id) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        painterResource(if (playingId == v.id) R.drawable.pause else R.drawable.play),
                        null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(if (playingId == v.id) "Parar" else "Escuchar")
            }
        }
    }
}
