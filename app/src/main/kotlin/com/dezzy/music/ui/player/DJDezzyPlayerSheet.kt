/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.player

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.constants.DjNameKey
import com.dezzy.music.ui.screens.DJDezzyViewModel
import com.dezzy.music.ui.screens.DJUiState
import com.dezzy.music.utils.rememberPreference

/** Peticiones rápidas fijas; se suman a las sugerencias que genera el DJ. */
private val DjQuickRequests = listOf(
    R.drawable.bolt to "Más energía",
    R.drawable.ic_mood_relax to "Algo tranquilo",
    R.drawable.casino to "Sorpréndeme",
    R.drawable.new_release to "Sólo canciones nuevas",
)

/**
 * DJ Dezzy como hoja del reproductor (fase 6 del rediseño, boceto C): estado del DJ,
 * lo que está haciendo, peticiones rápidas y un campo para escribir o dictar.
 * Reutiliza [DJDezzyViewModel]; la pantalla completa del DJ sigue en «dj_dezzy».
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DJDezzyPlayerSheet(
    onDismiss: () -> Unit,
    onOpenFullScreen: () -> Unit,
    viewModel: DJDezzyViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val colorScheme = MaterialTheme.colorScheme
    val uiState by viewModel.uiState.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val vibeCards by viewModel.dynamicVibeCards.collectAsState()
    val (customDjName) = rememberPreference(DjNameKey, "")
    val djName = if (customDjName.isNotBlank()) "DJ $customDjName" else "DJ Dezzy"
    var inputText by remember { mutableStateOf("") }

    fun submit(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotBlank() && playerConnection != null) {
            viewModel.handleUserRequest(trimmed, playerConnection)
            inputText = ""
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let(::submit)
        }
    }

    val stateLabel = when (uiState) {
        DJUiState.IDLE -> "En pausa"
        DJUiState.GENERATING -> "Preparando la mezcla"
        DJUiState.PLAYING -> "Mezclando para ti"
        DJUiState.SPEAKING -> "El DJ está hablando"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
        ) {
            // ── Encabezado: orbe, nombre y estado ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DjOrb(active = uiState == DJUiState.SPEAKING || uiState == DJUiState.GENERATING)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = djName,
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = 22.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colorScheme.surfaceContainerHigh,
                        contentColor = colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (uiState == DJUiState.IDLE) colorScheme.outline else colorScheme.primary),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(text = stateLabel, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }

            // ── Lo que dice / hace el DJ ──
            Surface(
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp),
                color = colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }

            if (uiState == DJUiState.IDLE) {
                Button(
                    onClick = { playerConnection?.let { viewModel.startDjSession(it) } },
                    enabled = playerConnection != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.graphic_eq),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Iniciar DJ con tu música")
                }
            }

            // ── Peticiones rápidas ──
            Text(
                text = "Pídele algo",
                style = MaterialTheme.typography.titleSmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)
                    .semantics { heading() },
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DjQuickRequests.forEach { (icon, label) ->
                    SuggestionChip(
                        onClick = { submit(label) },
                        label = { Text(label) },
                        icon = {
                            Icon(
                                painter = painterResource(icon),
                                contentDescription = null,
                                tint = colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        enabled = playerConnection != null,
                    )
                }
                vibeCards.take(4).forEach { card ->
                    SuggestionChip(
                        onClick = { submit(card) },
                        label = {
                            Text(
                                text = card,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        enabled = playerConnection != null,
                        modifier = Modifier.widthIn(max = 300.dp),
                    )
                }
            }

            // ── Campo para escribir o dictar ──
            OutlinedTextField(
                value = inputText,
                onValueChange = {
                    inputText = it
                    viewModel.setUserTyping(it.isNotEmpty())
                },
                placeholder = { Text("Escribe o dicta lo que quieres oír") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                leadingIcon = {
                    IconButton(onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Dile a $djName qué quieres escuchar")
                        }
                        try {
                            speechLauncher.launch(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "Reconocimiento de voz no disponible", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.mic),
                            contentDescription = "Dictar petición",
                            tint = colorScheme.primary,
                        )
                    }
                },
                trailingIcon = {
                    FilledIconButton(
                        onClick = { submit(inputText) },
                        enabled = inputText.isNotBlank() && playerConnection != null,
                        modifier = Modifier.padding(end = 4.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_upward),
                            contentDescription = "Enviar petición",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit(inputText) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )

            TextButton(
                onClick = onOpenFullScreen,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.fullscreen),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Abrir el DJ a pantalla completa")
            }
        }
    }
}

/** Orbe del DJ: degradado con los colores de Dezzy; late mientras el DJ habla o prepara la mezcla. */
@Composable
private fun DjOrb(active: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "djOrb")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "djOrbPulse",
    )
    Box(
        modifier = Modifier
            .size(64.dp)
            .scale(if (active) pulse else 1f)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(Color.White, colorScheme.primaryContainer, colorScheme.primary, colorScheme.onPrimaryContainer),
                ),
            ),
    )
}
