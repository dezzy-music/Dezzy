/*
 * Dezzy Project Original (2026)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.settings

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.R
import com.dezzy.music.ai.DjVoice
import com.dezzy.music.constants.DjGeminiKeyKey
import com.dezzy.music.constants.DjLanguageKey
import com.dezzy.music.constants.DjNameKey
import com.dezzy.music.constants.DjStyleKey
import com.dezzy.music.constants.DjTalkKey
import com.dezzy.music.constants.DjTtsApiKeyKey
import com.dezzy.music.constants.DjTtsProviderKey
import com.dezzy.music.constants.DjVoiceNameKey
import com.dezzy.music.ui.component.EditTextPreference
import com.dezzy.music.ui.component.IconButton
import com.dezzy.music.ui.component.InfoLabel
import com.dezzy.music.ui.component.ListPreference
import com.dezzy.music.ui.component.PreferenceEntry
import com.dezzy.music.ui.component.PreferenceGroupTitle
import com.dezzy.music.ui.utils.backToMain
import com.dezzy.music.utils.rememberPreference
import kotlinx.coroutines.launch
import java.io.File

/**
 * Ajustes › DJ (boceto B): una lista corta. Lo difícil —clave de Gemini, proveedor de voz, su
 * clave y la voz— vive en el asistente de 4 pasos [DjVoiceSetup] (ruta `settings/dj/setup`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val (provider, _) = rememberPreference(DjTtsProviderKey, DjVoice.FREE)
    val (encKey, _) = rememberPreference(DjTtsApiKeyKey, "")
    val (voiceName, _) = rememberPreference(DjVoiceNameKey, "")
    val (encGemini, _) = rememberPreference(DjGeminiKeyKey, "")
    val (djName, onDjNameChange) = rememberPreference(DjNameKey, "")
    val (language, onLanguageChange) = rememberPreference(DjLanguageKey, "auto")
    val (style, onStyleChange) = rememberPreference(DjStyleKey, "radio")
    val (talk, onTalkChange) = rememberPreference(DjTalkKey, "blocks")

    val hasPaidVoice = provider != DjVoice.FREE && encKey.isNotBlank()
    val hasBrain = encGemini.isNotBlank()
    val player = rememberDjPreviewPlayer()
    var testing by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var confirmForgetBrain by remember { mutableStateOf(false) }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))

        if (!hasPaidVoice || !hasBrain) {
            SetupBanner(
                brainMissing = !hasBrain,
                voiceMissing = !hasPaidVoice,
                onClick = { navController.navigate("settings/dj/setup") },
            )
        }

        PreferenceGroupTitle(title = "Tu DJ")

        PreferenceEntry(
            title = { Text("Cerebro") },
            description = if (hasBrain) "Gemini · clave guardada" else "Sin clave: el DJ usa frases fijas",
            icon = { Icon(painterResource(R.drawable.auto_awesome), null) },
            trailingContent = {
                if (hasBrain) {
                    Icon(painterResource(R.drawable.check), null, tint = MaterialTheme.colorScheme.primary)
                } else {
                    Text("Conectar", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            },
            onClick = { navController.navigate("settings/dj/setup") },
        )

        PreferenceEntry(
            title = { Text("Voz") },
            description = if (hasPaidVoice) {
                listOf(DjVoice.providerName(provider), voiceName).filter { it.isNotBlank() }.joinToString(" · ")
            } else {
                "Voz gratuita · Básica, sin clave"
            },
            icon = { Icon(painterResource(R.drawable.mic), null) },
            trailingContent = {
                Text(
                    if (hasPaidVoice) "Cambiar" else "Configurar",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            onClick = { navController.navigate("settings/dj/setup") },
        )

        if (hasPaidVoice) {
            PreferenceEntry(
                title = { Text("Clave de ${DjVoice.providerName(provider)}") },
                description = "Guardada y cifrada en este móvil",
                icon = { Icon(painterResource(R.drawable.key), null) },
                trailingContent = {
                    Icon(
                        painterResource(R.drawable.check),
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        }

        PreferenceEntry(
            title = { Text("Probar voz") },
            description = if (hasPaidVoice) "Escucha cómo suena tu DJ" else "Configura una voz para probarla",
            icon = {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(painterResource(R.drawable.play), null)
                }
            },
            isEnabled = hasPaidVoice && !testing,
            onClick = {
                testing = true
                scope.launch {
                    val out = File(context.cacheDir, "dj_test_voice.mp3")
                    val name = djName.ifBlank { "Dezzy" }
                    DjVoice.synthesize(context, sampleLine(name, language), out)
                        .onSuccess { player.play(out.absolutePath) }
                        .onFailure { Toast.makeText(context, "No se pudo probar: ${it.message}", Toast.LENGTH_SHORT).show() }
                    testing = false
                }
            },
        )

        if (hasPaidVoice) {
            PreferenceEntry(
                title = { Text("Borrar clave de voz", color = MaterialTheme.colorScheme.error) },
                description = "El DJ vuelve a la voz gratuita",
                icon = { Icon(painterResource(R.drawable.delete), null, tint = MaterialTheme.colorScheme.error) },
                onClick = { confirmForget = true },
            )
        }

        if (hasBrain) {
            PreferenceEntry(
                title = { Text("Borrar clave de Gemini", color = MaterialTheme.colorScheme.error) },
                description = "El DJ vuelve a las frases fijas",
                icon = { Icon(painterResource(R.drawable.delete), null, tint = MaterialTheme.colorScheme.error) },
                onClick = { confirmForgetBrain = true },
            )
        }

        PreferenceGroupTitle(title = "Personalidad")

        EditTextPreference(
            title = { Text("Nombre del DJ") },
            icon = { Icon(painterResource(R.drawable.person), null) },
            value = djName,
            onValueChange = { onDjNameChange(it.trim().take(24)) },
            isInputValid = { true },
        )

        ListPreference(
            title = { Text("Idioma") },
            icon = { Icon(painterResource(R.drawable.translate), null) },
            selectedValue = language,
            values = listOf("auto", "es", "en"),
            valueText = {
                when (it) {
                    "es" -> "Español"
                    "en" -> "Inglés"
                    else -> "Automático (el de la canción)"
                }
            },
            onValueSelected = onLanguageChange,
        )

        ListPreference(
            title = { Text("Estilo") },
            icon = { Icon(painterResource(R.drawable.mood), null) },
            selectedValue = style,
            values = listOf("radio", "party", "chill"),
            valueText = {
                when (it) {
                    "party" -> "Fiesta · mucha energía"
                    "chill" -> "Tranquilo · cálido y pausado"
                    else -> "Radio · cercano y con ritmo"
                }
            },
            onValueSelected = onStyleChange,
        )

        ListPreference(
            title = { Text("Cada cuánto habla") },
            icon = { Icon(painterResource(R.drawable.timer), null) },
            selectedValue = talk,
            values = listOf("start", "blocks", "often"),
            valueText = {
                when (it) {
                    "start" -> "Sólo al empezar"
                    "often" -> "A menudo · cada 2 canciones"
                    else -> "Al cambiar de bloque"
                }
            },
            onValueSelected = onTalkChange,
        )

        Spacer(Modifier.height(8.dp))
        InfoLabel(
            text = "Tus claves se guardan cifradas en este móvil y sólo se envían a Google y al proveedor de voz que elijas. " +
                "Dezzy no las ve ni las guarda en ningún servidor.",
        )
        Spacer(Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text("DJ Dezzy") },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
        scrollBehavior = scrollBehavior,
    )

    if (confirmForgetBrain) {
        AlertDialog(
            onDismissRequest = { confirmForgetBrain = false },
            icon = { Icon(painterResource(R.drawable.key), null) },
            title = { Text("¿Borrar la clave de Gemini?") },
            text = { Text("Se borra de este móvil y el DJ vuelve a sus frases fijas.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForgetBrain = false
                    scope.launch { DjVoice.saveGeminiKey(context, "") }
                }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmForgetBrain = false }) { Text("Cancelar") } },
        )
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            icon = { Icon(painterResource(R.drawable.key), null) },
            title = { Text("¿Borrar la clave?") },
            text = { Text("Se borra de este móvil y el DJ vuelve a la voz gratuita. Podrás ponerla otra vez cuando quieras.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    scope.launch { DjVoice.forget(context) }
                }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SetupBanner(brainMissing: Boolean, voiceMissing: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.headphones),
                null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(8.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        brainMissing && voiceMissing -> "Completa tu DJ"
                        brainMissing -> "Dale un cerebro a tu DJ"
                        else -> "Dale una voz de verdad a tu DJ"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    when {
                        brainMissing && voiceMissing -> "Gemini para lo que dice y ElevenLabs u OpenAI para la voz. Tarda un par de minutos."
                        brainMissing -> "Con Gemini (gratis) cuenta curiosidades de cada canción."
                        else -> "Usa tu clave de ElevenLabs u OpenAI. Tarda un minuto."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = onClick) { Text("Configurar") }
        }
    }
}

/** Frase corta para «Probar» y «Escuchar», en el idioma del DJ. */
internal fun sampleLine(djName: String, language: String): String =
    if (language == "en") {
        "Hey! I'm DJ $djName. Turn it up, because this next one is a gem."
    } else {
        "¡Hola! Soy DJ $djName. Sube el volumen, que lo que viene ahora es una joya."
    }

/** Un MediaPlayer para las muestras de voz; se libera al salir de la pantalla. */
internal class DjPreviewPlayer(private val context: Context) {
    private var mp: MediaPlayer? = null
    var playingId by mutableStateOf<String?>(null)
        private set

    /** `source`: ruta local o URL (las muestras de ElevenLabs). `id` marca qué fila suena. */
    fun play(source: String, id: String = source, onError: () -> Unit = {}) {
        stop()
        playingId = id
        val p = MediaPlayer()
        mp = p
        p.apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setOnPreparedListener { it.start() }
            setOnCompletionListener { stop() }
            setOnErrorListener { _, _, _ ->
                stop()
                onError()
                true
            }
            runCatching {
                setDataSource(source)
                prepareAsync()
            }.onFailure {
                stop()
                onError()
            }
        }
    }

    fun stop() {
        mp?.let { runCatching { it.stop() }; it.release() }
        mp = null
        playingId = null
    }
}

@Composable
internal fun rememberDjPreviewPlayer(): DjPreviewPlayer {
    val context = LocalContext.current
    val player = remember { DjPreviewPlayer(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    return player
}
