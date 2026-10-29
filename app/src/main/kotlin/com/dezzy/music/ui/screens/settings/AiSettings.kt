/**
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens.settings

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
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.R
import com.dezzy.music.constants.AiProviderKey

import com.dezzy.music.constants.AiSystemPromptKey
import com.dezzy.music.constants.CanvasAiEnhanceKey
import com.dezzy.music.constants.AutoTranslateLyricsKey
import com.dezzy.music.constants.AutoTranslateLyricsMismatchKey
import com.dezzy.music.constants.DeeplApiKey
import com.dezzy.music.constants.DeeplFormalityKey
import com.dezzy.music.constants.LanguageCodeToName
import com.dezzy.music.constants.OpenRouterApiKey
import com.dezzy.music.constants.OpenRouterBaseUrlKey
import com.dezzy.music.constants.OpenRouterModelKey
import com.dezzy.music.constants.TranslateLanguageKey
import com.dezzy.music.constants.TranslateModeKey
import com.dezzy.music.lyrics.OfflineTranslationService
import com.dezzy.music.ui.component.EnumDialog
import com.dezzy.music.ui.component.Material3SettingsGroup
import com.dezzy.music.ui.component.Material3SettingsItem
import com.dezzy.music.ui.component.TextFieldDialog
import com.dezzy.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    var aiProvider by rememberPreference(AiProviderKey, "FreeOnline")
    
    var openRouterApiKey by rememberPreference(OpenRouterApiKey, "")
    var openRouterBaseUrl by rememberPreference(OpenRouterBaseUrlKey, "https://openrouter.ai/api/v1/chat/completions")
    var openRouterModel by rememberPreference(OpenRouterModelKey, "mistralai/mistral-small-3.1-24b-instruct:free")
    var autoTranslateLyrics by rememberPreference(AutoTranslateLyricsKey, true)
    var autoTranslateLyricsMismatch by rememberPreference(AutoTranslateLyricsMismatchKey, false)
    var translateLanguage by rememberPreference(TranslateLanguageKey, "es")
    var translateMode by rememberPreference(TranslateModeKey, stringResource(R.string.mode_literal))
    
    var aiSystemPrompt by rememberPreference(AiSystemPromptKey, "")
    var djVoice by rememberPreference(com.dezzy.music.constants.DjVoiceKey, "es-MX-JorgeNeural")
    var showDjVoiceDialog by remember { mutableStateOf(false) }
    var canvasAiEnhance by rememberPreference(CanvasAiEnhanceKey, false)
    var deeplApiKey by rememberPreference(DeeplApiKey, "")
    var deeplFormality by rememberPreference(DeeplFormalityKey, "default")

    // Proveedores gratuitos (sin API key) primero; luego los de IA.
    val aiProviders = mapOf(
        "FreeOnline" to "",
        "Offline" to "",
        "OpenRouter" to "https://openrouter.ai/api/v1/chat/completions",
        "OpenAI" to "https://api.openai.com/v1/chat/completions",
        "Mistral" to "https://api.mistral.ai/v1/chat/completions",
        "DeepL" to "",
        "Gemini" to "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        "Perplexity" to "https://api.perplexity.ai/chat/completions",
        "Claude" to "https://api.anthropic.com/v1/messages",
        "XAi" to "https://api.x.ai/v1/chat/completions",
        "Groq" to "https://api.groq.com/openai/v1/chat/completions",
        "Nvidia" to "https://integrate.api.nvidia.com/v1/chat/completions",
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
        "Nvidia" to listOf(
            "meta/llama-3.1-70b-instruct",
            "meta/llama-3.1-8b-instruct",
            "meta/llama-3.1-405b-instruct",
            "meta/llama3-70b-instruct"
        ),
        "Custom" to listOf()
    )

    val commonModels = modelsByProvider[aiProvider] ?: listOf()

    // Proveedores que no requieren clave ni modelo de IA
    val isFreeProvider = aiProvider == "FreeOnline" || aiProvider == "Offline"
    val providerDisplayName: @Composable (String) -> String = {
        when (it) {
            "FreeOnline" -> stringResource(R.string.free_translator)
            "Offline" -> stringResource(R.string.offline_translator)
            else -> it
        }
    }

    // ── Estado del modelo offline (ML Kit) ──
    val context = LocalContext.current
    val settingsScope = rememberCoroutineScope()
    // Idioma del modelo offline: el idioma de destino si ML Kit lo soporta, o español.
    val offlineLang = remember(translateLanguage) {
        if (OfflineTranslationService.toMlKitLanguage(translateLanguage) != null) translateLanguage else "es"
    }
    var offlineModelDownloaded by remember { mutableStateOf<Boolean?>(null) }
    var offlineModelDownloading by remember { mutableStateOf(false) }
    LaunchedEffect(aiProvider, offlineLang) {
        if (aiProvider == "Offline") {
            offlineModelDownloaded = OfflineTranslationService.isModelDownloaded(offlineLang)
        }
    }

    var showProviderDialog by rememberSaveable { mutableStateOf(false) }
    var showTranslateModeDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showBaseUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showModelDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomModelInput by rememberSaveable { mutableStateOf(false) }
    var showSystemPromptDialog by rememberSaveable { mutableStateOf(false) }
    var showDeeplFormalityDialog by rememberSaveable { mutableStateOf(false) }

        if (showDjVoiceDialog) {
                EnumDialog(
            title = stringResource(R.string.dj_voice),
            onDismiss = { showDjVoiceDialog = false },
            onSelect = {
                djVoice = it
                showDjVoiceDialog = false
            },
            current = djVoice,
            values = listOf("es-MX-JorgeNeural", "es-MX-DaliaNeural"),
            valueText = { if (it == "es-MX-DaliaNeural") stringResource(R.string.dalia_female) else stringResource(R.string.jorge_male) }
        )
    }

if (showProviderDialog) {
        EnumDialog(
            onDismiss = { showProviderDialog = false },
            onSelect = {
                aiProvider = it
                if (it != "Custom" && it != "DeepL" && it != "FreeOnline" && it != "Offline") {
                    openRouterBaseUrl = aiProviders[it] ?: ""
                }
                val modelsForProvider = modelsByProvider[it] ?: listOf()
                if (modelsForProvider.isNotEmpty() && (openRouterModel !in modelsForProvider)) {
                    openRouterModel = modelsForProvider[0]
                }
                // El modo offline solo soporta idiomas de ML Kit: si el destino
                // actual no está soportado, pasamos a español por defecto.
                if (it == "Offline" && OfflineTranslationService.toMlKitLanguage(translateLanguage) == null) {
                    translateLanguage = "es"
                }
                showProviderDialog = false
            },
            title = stringResource(R.string.ai_translation_provider),
            current = aiProvider,
            values = aiProviders.keys.toList(),
            valueText = { providerDisplayName(it) }
        )
    }

    if (showTranslateModeDialog) {
        EnumDialog(
            onDismiss = { showTranslateModeDialog = false },
            onSelect = {
                translateMode = it
                showTranslateModeDialog = false
            },
            title = stringResource(R.string.translation_mode),
            current = translateMode,
            values = listOf(stringResource(R.string.mode_literal), "Emotional", "Transcribed", "Romanized"),
            valueText = {
                when (it) {
                    stringResource(R.string.mode_literal) -> stringResource(R.string.mode_literal)
                    "Emotional" -> stringResource(R.string.mode_emotional)
                    "Transcribed" -> stringResource(R.string.mode_transcription)
                    "Romanized" -> stringResource(R.string.mode_romanization)
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
            title = stringResource(R.string.target_language_settings),
            current = translateLanguage,
            values = LanguageCodeToName.keys.sortedBy { LanguageCodeToName[it] },
            valueText = { LanguageCodeToName[it] ?: it }
        )
    }

    if (showApiKeyDialog) {
        TextFieldDialog(
            title = { Text(if (aiProvider == "DeepL") stringResource(R.string.deepl_api_key) else stringResource(R.string.ai_api_key)) },
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
            title = { Text(stringResource(R.string.ai_base_url)) },
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
            title = stringResource(R.string.ai_model),
            current = if (openRouterModel in commonModels) openRouterModel else "custom_input",
            values = commonModels + "custom_input",
            valueText = { 
                if (it == "custom_input") stringResource(R.string.custom_model_input) else it
            }
        )
    }

    if (showCustomModelInput) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.ai_model)) },
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
            title = { Text(stringResource(R.string.system_prompt)) },
            placeholder = { Text(stringResource(R.string.system_prompt_placeholder)) },
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
            title = stringResource(R.string.deepl_formality),
            current = deeplFormality,
            values = listOf("default", "less", "more"),
            valueText = {
                when (it) {
                    "default" -> stringResource(R.string.formality_default)
                    "less" -> stringResource(R.string.formality_informal)
                    "more" -> stringResource(R.string.formality_formal)
                    else -> it
                }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lyrics_translation_title)) },
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
                title = stringResource(R.string.ai_translation_provider),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.explore_outlined),
                        title = { Text(stringResource(R.string.ai_provider)) },
                        description = { Text(providerDisplayName(aiProvider)) },
                        onClick = { showProviderDialog = true }
                    ),
                    if (!isFreeProvider && (aiProvider == "Custom" || (aiProvider != "DeepL" && aiProvider != "Mistral" && aiProvider != "OpenRouter" && aiProvider != "Nvidia"))) {
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.link),
                            title = { Text(stringResource(R.string.ai_base_url)) },
                            description = { Text(openRouterBaseUrl.ifBlank { stringResource(R.string.not_set) }) },
                            onClick = { showBaseUrlDialog = true }
                        )
                    } else {
                        null
                    }
                ).filterNotNull()
            )

            Spacer(modifier = Modifier.height(27.dp))

            // ── Traductor gratuito: no requiere configuración ──
            if (aiProvider == "FreeOnline") {
                Material3SettingsGroup(
                    title = stringResource(R.string.ai_configuration),
                    items = listOf(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.info),
                            title = { Text(stringResource(R.string.no_config_needed)) },
                            description = { Text(stringResource(R.string.free_translator_desc)) },
                            onClick = {}
                        )
                    )
                )
                Spacer(modifier = Modifier.height(27.dp))
            }

            // ── Modo offline: gestión del modelo de idioma on-device ──
            if (aiProvider == "Offline") {
                val langName = LanguageCodeToName[offlineLang] ?: offlineLang
                Material3SettingsGroup(
                    title = stringResource(R.string.offline_model),
                    items = listOf(
                        Material3SettingsItem(
                            icon = painterResource(
                                when {
                                    offlineModelDownloading -> R.drawable.downloading
                                    offlineModelDownloaded == true -> R.drawable.offline
                                    else -> R.drawable.download
                                }
                            ),
                            title = { Text(stringResource(R.string.model_of_lang_mb, langName)) },
                            description = {
                                Text(
                                    when {
                                        offlineModelDownloading -> stringResource(R.string.downloading_model)
                                        offlineModelDownloaded == true -> stringResource(R.string.downloaded_translate_offline)
                                        offlineModelDownloaded == false -> stringResource(R.string.tap_to_download_offline)
                                        else -> stringResource(R.string.checking_status)
                                    }
                                )
                            },
                            onClick = {
                                if (offlineModelDownloading || offlineModelDownloaded != false) return@Material3SettingsItem
                                offlineModelDownloading = true
                                settingsScope.launch {
                                    val result = OfflineTranslationService.downloadModel(offlineLang)
                                    offlineModelDownloading = false
                                    offlineModelDownloaded = result.isSuccess
                                    Toast.makeText(
                                        context,
                                        if (result.isSuccess) {
                                            "Modelo de $langName descargado ✓"
                                        } else {
                                            // En equipos con Google Play Services restringido
                                            // (p. ej. algunos POCO/Xiaomi) ML Kit no puede bajar
                                            // el modelo: ofrecer la alternativa que sí funciona.
                                            "No se pudo descargar el modelo (Google Play Services lo está bloqueando). " +
                                                "Usa el proveedor \"Traductor gratuito\" online mientras tanto."
                                        },
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        ),
                        if (offlineModelDownloaded == true) {
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.delete),
                                title = { Text(stringResource(R.string.delete_downloaded_model)) },
                                description = { Text(stringResource(R.string.delete_downloaded_model_desc)) },
                                onClick = {
                                    settingsScope.launch {
                                        val result = OfflineTranslationService.deleteModel(offlineLang)
                                        if (result.isSuccess) {
                                            offlineModelDownloaded = false
                                            Toast.makeText(context, "Modelo eliminado", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        } else null,
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.info),
                            title = { Text(stringResource(R.string.how_it_works)) },
                            description = { Text(stringResource(R.string.how_it_works_offline_desc)) },
                            onClick = {}
                        )
                    ).filterNotNull()
                )
                Spacer(modifier = Modifier.height(27.dp))
            }

            if (!isFreeProvider) {
            Material3SettingsGroup(
                title = stringResource(R.string.ai_configuration),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.token),
                        title = { Text(if (aiProvider == "DeepL") stringResource(R.string.deepl_api_key) else stringResource(R.string.ai_api_key)) },
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
                            title = { Text(stringResource(R.string.ai_model)) },
                            description = { Text(openRouterModel.ifBlank { stringResource(R.string.not_set) }) },
                            onClick = { showModelDialog = true }
                        )
                    } else {
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.discover_tune),
                            title = { Text(stringResource(R.string.deepl_formality)) },
                            description = { 
                                Text(
                                    when (deeplFormality) {
                                        "default" -> stringResource(R.string.formality_default)
                                        "less" -> stringResource(R.string.formality_informal)
                                        "more" -> stringResource(R.string.formality_formal)
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
                            title = { Text(stringResource(R.string.system_prompt)) },
                            description = { Text(aiSystemPrompt.ifBlank { stringResource(R.string.default_style) }) },
                            onClick = { showSystemPromptDialog = true }
                        )
                    } else null
                ).filterNotNull()
            )

            Spacer(modifier = Modifier.height(27.dp))
            }

            Material3SettingsGroup(
                title = stringResource(R.string.auto_translation),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.translate),
                        title = { Text(stringResource(R.string.translate_automatically)) },
                        description = { Text(stringResource(R.string.translate_automatically_desc)) },
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
                                title = { Text(stringResource(R.string.language_difference)) },
                                description = { Text(stringResource(R.string.language_difference_desc)) },
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
                title = stringResource(R.string.translation_options),
                items = listOf(
                    // Los proveedores gratuitos siempre traducen en modo literal.
                    if (!isFreeProvider) {
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.translate),
                            title = { Text(stringResource(R.string.translation_mode)) },
                            description = {
                                Text(
                                    when (translateMode) {
                                        stringResource(R.string.mode_literal) -> stringResource(R.string.mode_literal)
                                        "Transcribed" -> stringResource(R.string.mode_transcription)
                                        "Romanized" -> stringResource(R.string.mode_romanization)
                                        else -> translateMode
                                    }
                                )
                            },
                            onClick = { showTranslateModeDialog = true }
                        )
                    } else null,
                    if (!autoTranslateLyricsMismatch || !autoTranslateLyrics) {
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.language),
                            title = { Text(stringResource(R.string.target_language_settings)) },
                            description = { Text(LanguageCodeToName[translateLanguage] ?: translateLanguage) },
                            onClick = { showLanguageDialog = true }
                        )
                    } else {
                        null
                    }
                ).filterNotNull()
            )

            Spacer(modifier = Modifier.height(27.dp))

            Material3SettingsGroup(
                title = stringResource(R.string.ai_canvas),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.auto_awesome),
                        title = { Text(stringResource(R.string.smart_emphasis)) },
                        description = { Text(stringResource(R.string.smart_emphasis_desc)) },
                        trailingContent = {
                            Switch(
                                checked = canvasAiEnhance,
                                onCheckedChange = { canvasAiEnhance = it },
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (canvasAiEnhance) R.drawable.check else R.drawable.close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        },
                        onClick = { canvasAiEnhance = !canvasAiEnhance }
                    )
                )
            )

            
            Material3SettingsGroup(
                title = "DJ Dezzy",
                items = listOf(
                    
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
