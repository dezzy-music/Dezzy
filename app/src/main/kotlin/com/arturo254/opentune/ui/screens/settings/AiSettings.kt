/**
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.arturo254.opentune.LocalPlayerAwareWindowInsets
import com.arturo254.opentune.R
import com.arturo254.opentune.constants.AiProviderKey
import com.arturo254.opentune.constants.AiSystemPromptKey
import com.arturo254.opentune.constants.AutoTranslateLyricsKey
import com.arturo254.opentune.constants.AutoTranslateLyricsMismatchKey
import com.arturo254.opentune.constants.DeeplApiKey
import com.arturo254.opentune.constants.DeeplFormalityKey
import com.arturo254.opentune.constants.LanguageCodeToName
import com.arturo254.opentune.constants.OpenRouterApiKey
import com.arturo254.opentune.constants.OpenRouterBaseUrlKey
import com.arturo254.opentune.constants.OpenRouterModelKey
import com.arturo254.opentune.constants.TranslateLanguageKey
import com.arturo254.opentune.constants.TranslateModeKey
import com.arturo254.opentune.ui.component.EnumDialog
import com.arturo254.opentune.ui.component.Material3SettingsGroup
import com.arturo254.opentune.ui.component.Material3SettingsItem
import com.arturo254.opentune.ui.component.TextFieldDialog
import com.arturo254.opentune.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    var aiProvider by rememberPreference(AiProviderKey, "OpenRouter")
    var openRouterApiKey by rememberPreference(OpenRouterApiKey, "")
    var openRouterBaseUrl by rememberPreference(OpenRouterBaseUrlKey, "https://openrouter.ai/api/v1/chat/completions")
    var openRouterModel by rememberPreference(OpenRouterModelKey, "mistralai/mistral-small-3.1-24b-instruct:free")
    var autoTranslateLyrics by rememberPreference(AutoTranslateLyricsKey, false)
    var autoTranslateLyricsMismatch by rememberPreference(AutoTranslateLyricsMismatchKey, false)
    var translateLanguage by rememberPreference(TranslateLanguageKey, "en")
    var translateMode by rememberPreference(TranslateModeKey, "Literal")
    
    var aiSystemPrompt by rememberPreference(AiSystemPromptKey, "")
    var deeplApiKey by rememberPreference(DeeplApiKey, "")
    var deeplFormality by rememberPreference(DeeplFormalityKey, "default")

    val aiProviders = mapOf(
        "OpenRouter" to "https://openrouter.ai/api/v1/chat/completions",
        "OpenAI" to "https://api.openai.com/v1/chat/completions",
        "Mistral" to "https://api.mistral.ai/v1/chat/completions",
        "DeepL" to "",
        "Gemini" to "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        "Perplexity" to "https://api.perplexity.ai/chat/completions",
        "Claude" to "https://api.anthropic.com/v1/messages",
        "XAi" to "https://api.x.ai/v1/chat/completions",
        "Groq" to "https://api.groq.com/openai/v1/chat/completions",
        "Custom" to ""
    )

    val modelsByProvider = mapOf(
        "OpenRouter" to listOf(
            "google/gemini-2.0-flash-exp:free",
            "mistralai/mistral-small-3.1-24b-instruct:free",
            "meta-llama/llama-3.3-70b-instruct:free",
            "google/gemini-flash-1.5-8b-exp",
            "openai/gpt-4o-mini"
        ),
        "OpenAI" to listOf(
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-3.5-turbo"
        ),
        "Mistral" to listOf(
            "mistral-small-latest",
            "mistral-medium-latest",
            "mistral-large-latest",
            "pixtral-large-latest"
        ),
        "Gemini" to listOf(
            "gemini-2.0-flash-exp",
            "gemini-1.5-pro",
            "gemini-1.5-flash",
            "gemini-1.5-flash-8b",
        ),
        "Perplexity" to listOf(
            "llama-3.1-sonar-small-128k-online",
            "llama-3.1-sonar-large-128k-online",
            "llama-3.1-sonar-huge-128k-online"
        ),
        "Claude" to listOf(
            "claude-3-5-sonnet-20241022",
            "claude-3-5-haiku-20241022",
            "claude-3-opus-20240229"
        ),
        "Groq" to listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-70b-versatile",
            "mixtral-8x7b-32768",
            "gemma2-9b-it"
        ),
        "XAi" to listOf(
            "grok-beta"
        ),
        "Custom" to listOf()
    )

    val commonModels = modelsByProvider[aiProvider] ?: listOf()

    var showProviderDialog by rememberSaveable { mutableStateOf(false) }
    var showTranslateModeDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showBaseUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showModelDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomModelInput by rememberSaveable { mutableStateOf(false) }
    var showSystemPromptDialog by rememberSaveable { mutableStateOf(false) }
    var showDeeplFormalityDialog by rememberSaveable { mutableStateOf(false) }

    if (showProviderDialog) {
        EnumDialog(
            onDismiss = { showProviderDialog = false },
            onSelect = {
                aiProvider = it
                if (it != "Custom" && it != "DeepL") {
                    openRouterBaseUrl = aiProviders[it] ?: ""
                }
                val modelsForProvider = modelsByProvider[it] ?: listOf()
                if (modelsForProvider.isNotEmpty() && (openRouterModel !in modelsForProvider)) {
                    openRouterModel = modelsForProvider[0]
                }
                showProviderDialog = false
            },
            title = "Proveedor de IA",
            current = aiProvider,
            values = aiProviders.keys.toList(),
            valueText = { it }
        )
    }

    if (showTranslateModeDialog) {
        EnumDialog(
            onDismiss = { showTranslateModeDialog = false },
            onSelect = {
                translateMode = it
                showTranslateModeDialog = false
            },
            title = "Modo de traducción",
            current = translateMode,
            values = listOf("Literal", "Transcribed", "Romanized"),
            valueText = {
                when (it) {
                    "Literal" -> "Literal"
                    "Transcribed" -> "Transcripción"
                    "Romanized" -> "Romanización (IA)"
                    else -> it
                }
            }
        )
    }

    if (showLanguageDialog) {
        EnumDialog(
            onDismiss = { showLanguageDialog = false },
            onSelect = {
                translateLanguage = it
                showLanguageDialog = false
            },
            title = "Idioma de destino",
            current = translateLanguage,
            values = LanguageCodeToName.keys.sortedBy { LanguageCodeToName[it] },
            valueText = { LanguageCodeToName[it] ?: it }
        )
    }

    if (showApiKeyDialog) {
        TextFieldDialog(
            title = { Text(if (aiProvider == "DeepL") "Clave API de DeepL" else "Clave API de IA") },
            icon = { Icon(painterResource(R.drawable.token), null) },
            initialTextFieldValue = TextFieldValue(text = if (aiProvider == "DeepL") deeplApiKey else openRouterApiKey),
            onDone = {
                if (aiProvider == "DeepL") deeplApiKey = it else openRouterApiKey = it
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false }
        )
    }

    if (showBaseUrlDialog) {
        TextFieldDialog(
            title = { Text("URL Base de IA") },
            icon = { Icon(painterResource(R.drawable.link), null) },
            initialTextFieldValue = TextFieldValue(text = openRouterBaseUrl),
            onDone = {
                openRouterBaseUrl = it
                showBaseUrlDialog = false
            },
            onDismiss = { showBaseUrlDialog = false }
        )
    }

    if (showModelDialog) {
        EnumDialog(
            onDismiss = { showModelDialog = false },
            onSelect = {
                if (it == "custom_input") {
                    showCustomModelInput = true
                    showModelDialog = false
                } else {
                    openRouterModel = it
                    showModelDialog = false
                }
            },
            title = "Modelo de IA",
            current = if (openRouterModel in commonModels) openRouterModel else "custom_input",
            values = commonModels + "custom_input",
            valueText = { 
                if (it == "custom_input") "Personalizado" else it
            }
        )
    }

    if (showCustomModelInput) {
        TextFieldDialog(
            title = { Text("Modelo de IA") },
            icon = { Icon(painterResource(R.drawable.discover_tune), null) },
            initialTextFieldValue = TextFieldValue(text = openRouterModel),
            onDone = {
                openRouterModel = it
                showCustomModelInput = false
            },
            onDismiss = { showCustomModelInput = false }
        )
    }
    
    if (showSystemPromptDialog) {
        TextFieldDialog(
            title = { Text("Indicación del sistema (System Prompt)") },
            placeholder = { Text("Instrucciones personalizadas para la traducción de la IA...") },
            initialTextFieldValue = TextFieldValue(text = aiSystemPrompt),
            singleLine = false,
            onDone = {
                aiSystemPrompt = it
                showSystemPromptDialog = false
            },
            onDismiss = { showSystemPromptDialog = false }
        )
    }

    if (showDeeplFormalityDialog) {
        EnumDialog(
            onDismiss = { showDeeplFormalityDialog = false },
            onSelect = {
                deeplFormality = it
                showDeeplFormalityDialog = false
            },
            title = "Formalidad de DeepL",
            current = deeplFormality,
            values = listOf("default", "less", "more"),
            valueText = {
                when (it) {
                    "default" -> "Por defecto"
                    "less" -> "Informal"
                    "more" -> "Formal"
                    else -> it
                }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Traducción de Letras con IA") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = { navController.navigateUp() }) {
                        androidx.compose.material3.Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = null
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { paddingValues ->
        Column(
            Modifier
                .padding(paddingValues)
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                    )
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Material3SettingsGroup(
                title = "Proveedor de IA",
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.explore_outlined),
                        title = { Text("Proveedor de IA") },
                        description = { Text(aiProvider) },
                        onClick = { showProviderDialog = true }
                    ),
                    if (aiProvider == "Custom" || (aiProvider != "DeepL" && aiProvider != "Mistral" && aiProvider != "OpenRouter")) {
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.link),
                            title = { Text("URL Base de IA") },
                            description = { Text(openRouterBaseUrl.ifBlank { "No establecido" }) },
                            onClick = { showBaseUrlDialog = true }
                        )
                    } else {
                        null
                    }
                ).filterNotNull()
            )

            Spacer(modifier = Modifier.height(27.dp))

            Material3SettingsGroup(
                title = "Configuración",
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.token),
                        title = { Text(if (aiProvider == "DeepL") "Clave API de DeepL" else "Clave API de IA") },
                        description = { 
                            val key = if (aiProvider == "DeepL") deeplApiKey else openRouterApiKey
                            Text(
                                if (key.isNotEmpty()) 
                                    "•".repeat(minOf(key.length, 8))
                                else 
                                    "No establecida"
                            )
                        },
                        onClick = { showApiKeyDialog = true }
                    ),
                    if (aiProvider != "DeepL") {
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.discover_tune),
                            title = { Text("Modelo de IA") },
                            description = { Text(openRouterModel.ifBlank { "No establecido" }) },
                            onClick = { showModelDialog = true }
                        )
                    } else {
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.discover_tune),
                            title = { Text("Formalidad de DeepL") },
                            description = { 
                                Text(
                                    when (deeplFormality) {
                                        "default" -> "Por defecto"
                                        "less" -> "Informal"
                                        "more" -> "Formal"
                                        else -> deeplFormality
                                    }
                                )
                            },
                            onClick = { showDeeplFormalityDialog = true }
                        )
                    },
                    if (aiProvider != "DeepL") {
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.info),
                            title = { Text("System Prompt") },
                            description = { Text(aiSystemPrompt.ifBlank { "Estilo predeterminado" }) },
                            onClick = { showSystemPromptDialog = true }
                        )
                    } else null
                ).filterNotNull()
            )

            Spacer(modifier = Modifier.height(27.dp))

            Material3SettingsGroup(
                title = "Traducción Automática",
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.translate),
                        title = { Text("Traducir automáticamente") },
                        description = { Text("Traducir letras en reproducción de forma automática") },
                        trailingContent = {
                            Switch(
                                checked = autoTranslateLyrics,
                                onCheckedChange = { autoTranslateLyrics = it },
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (autoTranslateLyrics) R.drawable.check else R.drawable.close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        },
                        onClick = { autoTranslateLyrics = !autoTranslateLyrics }
                    )
                ).let { items ->
                    if (autoTranslateLyrics) {
                        items + listOf(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.info),
                                title = { Text("Diferencia de idioma") },
                                description = { Text("Traducir solo si el idioma es diferente del de la interfaz") },
                                trailingContent = {
                                    Switch(
                                        checked = autoTranslateLyricsMismatch,
                                        onCheckedChange = { autoTranslateLyricsMismatch = it },
                                        thumbContent = {
                                            Icon(
                                                painter = painterResource(
                                                    id = if (autoTranslateLyricsMismatch) R.drawable.check else R.drawable.close
                                                ),
                                                contentDescription = null,
                                                modifier = Modifier.size(SwitchDefaults.IconSize)
                                            )
                                        }
                                    )
                                },
                                onClick = { autoTranslateLyricsMismatch = !autoTranslateLyricsMismatch }
                            )
                        )
                    } else {
                        items
                    }
                }
            )

            Spacer(modifier = Modifier.height(27.dp))

            Material3SettingsGroup(
                title = "Opciones de traducción",
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.translate),
                        title = { Text("Modo de traducción") },
                        description = {
                            Text(
                                when (translateMode) {
                                    "Literal" -> "Literal"
                                    "Transcribed" -> "Transcripción"
                                    "Romanized" -> "Romanización (IA)"
                                    else -> translateMode
                                }
                            )
                        },
                        onClick = { showTranslateModeDialog = true }
                    ),
                    if (!autoTranslateLyricsMismatch || !autoTranslateLyrics) {
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.language),
                            title = { Text("Idioma de destino") },
                            description = { Text(LanguageCodeToName[translateLanguage] ?: translateLanguage) },
                            onClick = { showLanguageDialog = true }
                        )
                    } else {
                        null
                    }
                ).filterNotNull()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
