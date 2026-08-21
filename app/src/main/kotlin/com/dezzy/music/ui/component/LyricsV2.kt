/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music) & WTTexe!
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.component

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.constants.LyricsClickKey
import com.dezzy.music.constants.LyricsLineSpacingKey
import com.dezzy.music.constants.LyricsRomanizeJapaneseKey
import com.dezzy.music.constants.LyricsRomanizeKoreanKey
import com.dezzy.music.constants.LyricsScrollKey
import com.dezzy.music.constants.LyricsTextSizeKey
import com.dezzy.music.constants.PlayerBackgroundStyle
import com.dezzy.music.constants.PlayerBackgroundStyleKey
import com.dezzy.music.constants.LyricsTextPositionKey
import com.dezzy.music.ui.screens.settings.LyricsPosition
import com.dezzy.music.constants.OpenRouterApiKey
import com.dezzy.music.constants.DeeplApiKey
import com.dezzy.music.constants.DeeplFormalityKey
import com.dezzy.music.constants.OpenRouterBaseUrlKey
import com.dezzy.music.constants.OpenRouterModelKey
import com.dezzy.music.constants.AutoTranslateLyricsKey
import com.dezzy.music.constants.AutoTranslateLyricsMismatchKey
import com.dezzy.music.constants.TranslateLanguageKey
import com.dezzy.music.constants.TranslateModeKey
import com.dezzy.music.LocalDatabase
import com.dezzy.music.lyrics.LyricsTranslationHelper
import com.dezzy.music.lyrics.LanguageDetectionHelper
import com.dezzy.music.constants.UseSystemFontKey
import com.dezzy.music.constants.LyricsV2Style
import com.dezzy.music.constants.LyricsV2StyleKey
import com.dezzy.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.dezzy.music.lyrics.LyricsEntry
import com.dezzy.music.lyrics.LyricsUtils.findCurrentLineIndex
import com.dezzy.music.lyrics.LyricsUtils.isChinese
import com.dezzy.music.lyrics.LyricsUtils.isJapanese
import com.dezzy.music.lyrics.LyricsUtils.isKorean
import com.dezzy.music.lyrics.LyricsUtils.isTtml
import com.dezzy.music.lyrics.LyricsUtils.parseLyrics
import com.dezzy.music.lyrics.LyricsUtils.parseTtml
import com.dezzy.music.lyrics.LyricsUtils.romanizeJapanese
import com.dezzy.music.lyrics.LyricsUtils.romanizeKorean
import com.dezzy.music.lyrics.WordTimestamp
import com.dezzy.music.ui.component.shimmer.ShimmerHost
import com.dezzy.music.ui.component.shimmer.TextPlaceholder
import com.dezzy.music.ui.utils.smoothFadingEdge
import com.dezzy.music.utils.ComposeToImage
import com.dezzy.music.utils.rememberEnumPreference
import com.dezzy.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs


// ──────────────────────────────────────────────────────────────────────
// Constants
// ──────────────────────────────────────────────────────────────────────

/** Lead time offset for LRC-style line-synced lyrics (ms). */
private const val LRC_LEAD_MS = 300L

/** Lead time offset for TTML word-synced lyrics (ms). */
private const val TTML_LEAD_MS = 0L

/** Seconds to wait before auto-scroll resumes after manual scroll. */
private const val MANUAL_SCROLL_TIMEOUT_MS = 3000L

/** Apple-Music-style easing for smooth deceleration. */
private val V2Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f)

/** Liquid fill easing: fast attack, very smooth deceleration (Apple Music-like). */
private val LiquidFillEasing = CubicBezierEasing(0.0f, 0.0f, 0.15f, 1.0f)

/** Sentinel entry prepended so auto-scroll has headroom above the first line. */
private val HEAD_LYRICS_ENTRY = LyricsEntry(time = 0L, text = "")


// ──────────────────────────────────────────────────────────────────────
// Main Composable
// ──────────────────────────────────────────────────────────────────────


@SuppressLint("UnusedBoxWithConstraintsScope", "LocalContextGetResourceValueCall",
    "StringFormatInvalid"
)
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LyricsV2(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
    gradientColors: List<Color> = emptyList(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val player = playerConnection.player
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    // ── Preferences ──
    val (lyricsClick) = rememberPreference(LyricsClickKey, defaultValue = true)
    val (lyricsScroll) = rememberPreference(LyricsScrollKey, defaultValue = true)
    val (lyricsTextSize) = rememberPreference(LyricsTextSizeKey, defaultValue = 26f)
    val (lyricsLineSpacing) = rememberPreference(LyricsLineSpacingKey, defaultValue = 1.3f)
    val (romanizeJapanese) = rememberPreference(LyricsRomanizeJapaneseKey, defaultValue = true)
    val (romanizeKorean) = rememberPreference(LyricsRomanizeKoreanKey, defaultValue = true)
    val (useSystemFont) = rememberPreference(UseSystemFontKey, defaultValue = false)
    val (appFontName) = rememberPreference(com.dezzy.music.constants.FontFamilyKey, defaultValue = "LORA")
    val shareFontName = if (useSystemFont) "SYSTEM" else appFontName
    val lyricsFontFamily = androidx.compose.material3.MaterialTheme.typography.headlineMedium.fontFamily
    val playerBackground by rememberEnumPreference(PlayerBackgroundStyleKey, PlayerBackgroundStyle.DEFAULT)
    val lyricsV2Style by rememberEnumPreference(LyricsV2StyleKey, LyricsV2Style.LIQUID_FLOW)
    val (lyricsTextPositionStr) = rememberPreference(LyricsTextPositionKey, defaultValue = LyricsPosition.CENTER.name)
    val lyricsTextPosition = LyricsPosition.values().firstOrNull { it.name == lyricsTextPositionStr } ?: LyricsPosition.CENTER

    val database = LocalDatabase.current
    val openRouterApiKey by rememberPreference(OpenRouterApiKey, "")
    val openRouterBaseUrl by rememberPreference(OpenRouterBaseUrlKey, "https://openrouter.ai/api/v1/chat/completions")
    val openRouterModel by rememberPreference(OpenRouterModelKey, "mistralai/mistral-small-3.1-24b-instruct:free")
    val deeplApiKey by rememberPreference(DeeplApiKey, "")
    val deeplFormality by rememberPreference(DeeplFormalityKey, "default")
    val autoTranslateLyrics by rememberPreference(AutoTranslateLyricsKey, true)
    val autoTranslateLyricsMismatch by rememberPreference(AutoTranslateLyricsMismatchKey, false)
    val translateLanguage by rememberPreference(TranslateLanguageKey, "es")
    val translateMode by rememberPreference(TranslateModeKey, "Literal")
    val aiProvider by rememberPreference(com.dezzy.music.constants.AiProviderKey, "FreeOnline")
    val effectiveApiKey = remember(aiProvider, openRouterApiKey, deeplApiKey) {
        when (aiProvider) {
            "DeepL" -> deeplApiKey
            // Proveedores sin clave: traductor gratuito online y modo offline (ML Kit)
            "FreeOnline", "Offline" -> "free"
            else -> openRouterApiKey
        }
    }

    // ── Text colour derived from background style ──
    val textColor = if (playerBackground == PlayerBackgroundStyle.DEFAULT)
        MaterialTheme.colorScheme.onBackground
    else
        Color.White

    val inactiveAlpha = 0.35f

    // ── Selection mode state ──
    var isSelectionModeActive by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(com.dezzy.music.lyrics.LyricsSelectionState.isSelectingForShare) {
        if (com.dezzy.music.lyrics.LyricsSelectionState.isSelectingForShare) {
            isSelectionModeActive = true
        }
    }
    val selectedIndices = remember { mutableStateListOf<Int>() }
    var showMaxSelectionToast by remember { mutableStateOf(false) }
    val maxSelectionLimit = 5
    var showProgressDialog by remember { mutableStateOf(false) }
    var showShareCarouselSheet by remember { mutableStateOf(false) }
    var shareDialogData by remember {
        mutableStateOf<Triple<String, String, String>?>(null)
    }
    // Traducción de los MISMOS versos seleccionados, si la canción está traducida.
    // Habilita el selector Original / Traducción / Ambas del sheet de compartir.
    var shareTranslatedText by remember { mutableStateOf<String?>(null) }
    var showColorPickerDialog by remember { mutableStateOf(false) }
    var selectedGlassStyle by remember { mutableStateOf(LyricsGlassStyle.FrostedDark) }
    var paletteGlassStyle by remember { mutableStateOf<LyricsGlassStyle?>(null) }


    var showShareDialog by remember { mutableStateOf(false) }
    // ── Lyrics data ──
    val currentLyrics by playerConnection.currentLyrics.collectAsState(initial = null)
    val lyrics = currentLyrics?.lyrics

    // ── Parse lyrics into entries ──
    val isSynced = remember(lyrics) { lyrics != null && (lyrics!!.startsWith("[") || isTtml(lyrics!!)) }
    val isTtmlFormat = remember(lyrics) { lyrics != null && isTtml(lyrics!!) }

    val lyricsEntries: List<LyricsEntry> = remember(lyrics) {
        if (lyrics == null || lyrics == LYRICS_NOT_FOUND) return@remember emptyList()
        val parsed = when {
            isTtml(lyrics!!) -> parseTtml(lyrics!!)
            lyrics!!.startsWith("[") -> parseLyrics(lyrics!!)
            else -> lyrics!!.lines()
                .filter { it.isNotBlank() }
                .mapIndexed { index, line ->
                    LyricsEntry(time = -1L, text = line.trim())
                }
        }
        if (parsed.isNotEmpty() && parsed.first().time >= 0) {
            listOf(HEAD_LYRICS_ENTRY) + parsed
        } else {
            parsed
        }
    }

    // ── Synthesize word timings for LRC entries that lack them ──
    val entriesWithWords: List<LyricsEntry> = remember(lyricsEntries) {
        if (lyricsEntries.isEmpty()) return@remember emptyList()
        lyricsEntries.mapIndexed { index, entry ->
            if (entry.words != null || entry.time < 0 || entry.text.isBlank()) {
                entry // Already has word timings (TTML) or is non-synced
            } else {
                // Synthesize word-level timings for this LRC line
                val nextEntryTime = if (index < lyricsEntries.lastIndex) {
                    lyricsEntries[index + 1].time
                } else {
                    entry.time + 5000L // 5s fallback for last line
                }
                val lineDurationMs = (nextEntryTime - entry.time).coerceAtLeast(500L)
                val lineStartSec = entry.time / 1000.0

                val isCjkText = isJapanese(entry.text) || isChinese(entry.text) || isKorean(entry.text)
                val tokens = if (isCjkText) {
                    val chars = mutableListOf<String>()
                    var currentWord = StringBuilder()
                    entry.text.forEach { char ->
                        if (char.isWhitespace()) {
                            if (currentWord.isNotEmpty()) {
                                chars.add(currentWord.toString())
                                currentWord.clear()
                            }
                            chars.add(char.toString())
                        } else if (isJapanese(char.toString()) || isChinese(char.toString()) || isKorean(char.toString())) {
                            if (currentWord.isNotEmpty()) {
                                chars.add(currentWord.toString())
                                currentWord.clear()
                            }
                            chars.add(char.toString())
                        } else {
                            currentWord.append(char)
                        }
                    }
                    if (currentWord.isNotEmpty()) {
                        chars.add(currentWord.toString())
                    }

                    // Group spaces onto the preceding word
                    val groupedTokens = mutableListOf<String>()
                    var tempStr = StringBuilder()
                    chars.forEachIndexed { i, c ->
                        if (c.isBlank()) {
                            if (groupedTokens.isNotEmpty()) {
                                groupedTokens[groupedTokens.lastIndex] = groupedTokens.last() + c
                            }
                        } else {
                            groupedTokens.add(c)
                        }
                    }
                    groupedTokens
                } else {
                    entry.text.split(Regex("\\s+"))
                }
                if (tokens.isEmpty()) return@mapIndexed entry

                // Weight each token by character count for proportional distribution
                val totalChars = tokens.sumOf { it.length }.coerceAtLeast(1)
                val words = mutableListOf<WordTimestamp>()
                var currentOffsetMs = 0.0

                tokens.forEachIndexed { wordIdx, token ->
                    val weight = token.length.toDouble() / totalChars
                    val wordDurMs = lineDurationMs * weight
                    val wordStartSec = lineStartSec + (currentOffsetMs / 1000.0)
                    val wordEndSec = wordStartSec + (wordDurMs / 1000.0)

                    val wordText = if (wordIdx < tokens.lastIndex && !isCjkText) "$token " else token
                    words.add(
                        WordTimestamp(
                            text = wordText,
                            startTime = wordStartSec,
                            endTime = wordEndSec,
                        )
                    )
                    currentOffsetMs += wordDurMs
                }
                entry.copy(words = words)
            }
        }
    }

    // ── Romanization ──
    LaunchedEffect(entriesWithWords, romanizeJapanese, romanizeKorean) {
        if (!romanizeJapanese && !romanizeKorean) return@LaunchedEffect
        entriesWithWords.forEach { entry ->
            if (entry.text.isBlank() || entry.romanizedTextFlow.value != null) return@forEach
            scope.launch(Dispatchers.Default) {
                val romanized = when {
                    romanizeJapanese && isJapanese(entry.text) -> romanizeJapanese(entry.text)
                    romanizeKorean && isKorean(entry.text) -> romanizeKorean(entry.text)
                    else -> null
                }
                if (romanized != null) entry.romanizedTextFlow.value = romanized
            }
        }
    }

    // ── Playback position tracking ──
    val leadMs = if (isTtmlFormat) TTML_LEAD_MS else LRC_LEAD_MS
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var currentLineIndex by remember { mutableIntStateOf(0) }

    // Frame-accurate position loop
    LaunchedEffect(entriesWithWords, isSynced) {
        if (!isSynced || entriesWithWords.isEmpty()) return@LaunchedEffect
        while (isActive) {
            val sliderPos = sliderPositionProvider()
            val pos = sliderPos ?: player.currentPosition

            // Add a visual tuning offset so animations feel instantly responsive and perfectly land on beat
            val visualTuningOffsetMs = 150L
            currentPositionMs = pos + leadMs + visualTuningOffsetMs

            currentLineIndex = findCurrentLineIndex(entriesWithWords, currentPositionMs, 0L)
            delay(16L) // ~60fps polling
        }
    }

    // ── Scroll State ──
    val listState = rememberLazyListState()
    var isManualScrolling by remember { mutableStateOf(false) }
    var lastManualScrollTime by remember { mutableLongStateOf(0L) }

    // Detect manual scrolling
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!isSelectionModeActive && source == NestedScrollSource.UserInput) {
                    isManualScrolling = true
                    lastManualScrollTime = System.currentTimeMillis()
                }
                return Offset.Zero
            }
        }
    }

    // Resume auto-scroll after timeout
    LaunchedEffect(isManualScrolling, lastManualScrollTime) {
        if (isManualScrolling) {
            delay(MANUAL_SCROLL_TIMEOUT_MS)
            isManualScrolling = false
        }
    }

    // Auto-scroll to active line
    LaunchedEffect(currentLineIndex, isManualScrolling, lyricsScroll) {
        if (!lyricsScroll || isManualScrolling || !isSynced) return@LaunchedEffect
        if (currentLineIndex < 0 || currentLineIndex >= entriesWithWords.size) return@LaunchedEffect

        val visibleInfo = listState.layoutInfo
        val viewportHeight = visibleInfo.viewportSize.height
        val targetOffset = (viewportHeight * 0.35f).toInt() // Center bias at 35% from top

        val distance = abs(currentLineIndex - (listState.firstVisibleItemIndex))
        if (distance > 15) {
            // Far jump — snap first, then settle
            listState.scrollToItem(
                (currentLineIndex - 2).coerceAtLeast(0),
                0
            )
        }
        listState.animateScrollToItem(
            index = currentLineIndex,
            scrollOffset = -targetOffset
        )
    }

    androidx.compose.runtime.LaunchedEffect(isSelectionModeActive) { if (!isSelectionModeActive) { com.dezzy.music.lyrics.LyricsSelectionState.isSelectingForShare = false } }
    BackHandler(enabled = isSelectionModeActive) {
        isSelectionModeActive = false
        selectedIndices.clear()
    }

    LaunchedEffect(showMaxSelectionToast) {
        if (showMaxSelectionToast) {
            Toast.makeText(
                context,
                context.getString(R.string.max_selection_limit, maxSelectionLimit),
                Toast.LENGTH_SHORT
            ).show()
            showMaxSelectionToast = false
        }
    }

    // ── Keep screen alive ──
    val activity = context as? android.app.Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val translationStatus by LyricsTranslationHelper.status.collectAsState()
    
    DisposableEffect(Unit) {
        LyricsTranslationHelper.setCompositionActive(true)
        onDispose {
            LyricsTranslationHelper.setCompositionActive(false)
            LyricsTranslationHelper.cancelTranslation()
        }
    }

    LaunchedEffect(entriesWithWords.size, effectiveApiKey, aiProvider) {
        LyricsTranslationHelper.manualTrigger.collect {
             if (entriesWithWords.isNotEmpty() && effectiveApiKey.isNotBlank()) {
                     LyricsTranslationHelper.translateLyrics(
                         lyrics = entriesWithWords,
                         targetLanguage = translateLanguage,
                         apiKey = openRouterApiKey,
                         baseUrl = openRouterBaseUrl,
                         model = openRouterModel,
                         mode = translateMode,
                         scope = scope,
                         context = context,
                         database = database,
                         provider = aiProvider,
                         deeplApiKey = deeplApiKey,
                         deeplFormality = deeplFormality
                     )
             } else if (effectiveApiKey.isBlank()) {
                  Toast.makeText(context, context.getString(R.string.ai_api_key_required), Toast.LENGTH_SHORT).show()
             }
        }
    }

    LaunchedEffect(entriesWithWords, autoTranslateLyrics, autoTranslateLyricsMismatch, effectiveApiKey, aiProvider, translateMode, translateLanguage) {
        if (entriesWithWords.isNotEmpty()) {
            val targetLang = if (autoTranslateLyricsMismatch) java.util.Locale.getDefault().language else translateLanguage
            val hasCached = LyricsTranslationHelper.applyCachedTranslations(entriesWithWords, translateMode, targetLang, database)

            if (!autoTranslateLyrics) {
                if (!hasCached) {
                    LyricsTranslationHelper.resetStatus()
                }
                return@LaunchedEffect
            }
            
            if (!hasCached && effectiveApiKey.isNotBlank()) {
                val needsTranslation = entriesWithWords.any { it.translatedTextFlow.value == null && it.text.isNotBlank() }
                if (needsTranslation) {
                    var shouldTranslate = true
                    if (autoTranslateLyricsMismatch) {
                        try {
                            val combinedText = entriesWithWords.take(5).joinToString(" ") { it.text }
                            val detectedLang = LanguageDetectionHelper.identifyLanguage(combinedText)
                            val systemLang = java.util.Locale.getDefault().language
                            
                            if (detectedLang != null && detectedLang == systemLang) {
                                shouldTranslate = false
                            }
                        } catch (e: Exception) {
                            timber.log.Timber.e(e, "Language detection failed, proceeding with translation")
                        }
                    }

                    if (shouldTranslate) {
                        LyricsTranslationHelper.translateLyrics(
                            lyrics = entriesWithWords,
                            targetLanguage = targetLang,
                            apiKey = openRouterApiKey,
                            baseUrl = openRouterBaseUrl,
                            model = openRouterModel,
                            mode = translateMode,
                            scope = scope,
                            context = context,
                            database = database,
                            provider = aiProvider,
                            deeplApiKey = deeplApiKey,
                            deeplFormality = deeplFormality
                        )
                    }
                }
            }
        }
    }

    // ── Render ──
    BoxWithConstraints(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = 12.dp)
    ) {
        // Status UI for translation
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(1f)
                .padding(top = 56.dp),
            contentAlignment = Alignment.Center
        ) {
            when (val status = translationStatus) {
                is LyricsTranslationHelper.TranslationStatus.Translating -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = stringResource(R.string.ai_translating_lyrics),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                is LyricsTranslationHelper.TranslationStatus.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.error),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = status.message,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                is LyricsTranslationHelper.TranslationStatus.Success -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.check),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.ai_lyrics_translated),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
                is LyricsTranslationHelper.TranslationStatus.Idle -> {
                    // No status display
                }
            }
        }
        if (lyrics == LYRICS_NOT_FOUND) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.lyrics_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
            return@BoxWithConstraints
        }

        if (lyrics == null) {
            ShimmerHost {
                repeat(6) {
                    TextPlaceholder()
                }
            }
            return@BoxWithConstraints
        }

        if (entriesWithWords.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.lyrics_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
            return@BoxWithConstraints
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
                .smoothFadingEdge(vertical = 80.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(
                items = entriesWithWords,
                key = { index, entry -> "${index}_${entry.time}" }
            ) { index, item ->
                if (item == HEAD_LYRICS_ENTRY) {
                    Spacer(modifier = Modifier.height(120.dp))
                    return@itemsIndexed
                }

                // ── Preference-based positioning ──
                val textAlign = when (lyricsTextPosition) {
                    LyricsPosition.LEFT -> TextAlign.Start
                    LyricsPosition.CENTER -> TextAlign.Center
                    LyricsPosition.RIGHT -> TextAlign.End
                }
                val horizontalAlignment = when (lyricsTextPosition) {
                    LyricsPosition.LEFT -> Alignment.Start
                    LyricsPosition.CENTER -> Alignment.CenterHorizontally
                    LyricsPosition.RIGHT -> Alignment.End
                }

                val isActive = isSynced && index == currentLineIndex
                val isPast = isSynced && index < currentLineIndex
                val isFuture = isSynced && index > currentLineIndex

                // Distance-based alpha for non-active lines
                val distanceFromActive = if (isSynced) abs(index - currentLineIndex) else 0
                // For word-synced lines, each word handles its own alpha independently
                // so we use 1f for active lines to not double-dim
                val lineAlpha = when {
                    !isSynced -> 0.9f
                    isActive -> 1f
                    else -> (inactiveAlpha - (distanceFromActive - 1) * 0.03f)
                        .coerceIn(0.15f, inactiveAlpha)
                }
                // Word-synced lines: pass 1f alpha so individual words control their own
                val wordLineAlpha = if (item.words != null && isSynced) 1f else lineAlpha



                // Background vocal detection
                val hasBackgroundWords = item.words?.any { it.isBackground } == true
                val isAllBackground = item.words?.all { it.isBackground || it.text.isBlank() } == true

                val isSelected = selectedIndices.contains(index)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (isSelected && isSelectionModeActive)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            else
                                Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(
                            start = if (isAllBackground) 24.dp else 12.dp,
                            end = 12.dp,
                            top = if (index == 0 || (index == 1 && entriesWithWords[0] == HEAD_LYRICS_ENTRY)) 0.dp else (lyricsLineSpacing * 8).dp,
                            bottom = (lyricsLineSpacing * 8).dp,
                        )
                        .alpha(wordLineAlpha)
                        .combinedClickable(
                            enabled = true,
                            onClick = {
                                if (isSelectionModeActive) {
                                    if (isSelected) {
                                        selectedIndices.remove(index)
                                        if (selectedIndices.isEmpty()) {
                                            isSelectionModeActive = false
                                        }
                                    } else {
                                        if (selectedIndices.size < maxSelectionLimit) {
                                            selectedIndices.add(index)
                                        } else {
                                            showMaxSelectionToast = true
                                        }
                                    }
                                } else if (lyricsClick && isSynced && item.time > 0) {
                                    player.seekTo(item.time)
                                }
                            },
                            onLongClick = {
                                if (!isSelectionModeActive) {
                                    isSelectionModeActive = true
                                    selectedIndices.add(index)
                                } else if (!isSelected && selectedIndices.size < maxSelectionLimit) {
                                    selectedIndices.add(index)
                                } else if (!isSelected) {
                                    showMaxSelectionToast = true
                                }
                            }
                        ),
                    horizontalAlignment = horizontalAlignment,
                ) {
                    if (item.words != null && isSynced) {
                        // ── Word-synced rendering ──
                        LyricsLineV2(
                            words = item.words!!,
                            isActive = isActive,
                            isPast = isPast,
                            currentPositionMs = currentPositionMs,
                            textColor = textColor,
                            inactiveAlpha = inactiveAlpha,
                            baseFontSize = lyricsTextSize,
                            isLineAllBackground = isAllBackground,
                            textAlign = textAlign,
                            lyricsFontFamily = lyricsFontFamily,
                            lyricsV2Style = lyricsV2Style,
                        )
                    } else {
                        // ── Plain text rendering ──
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = if (isAllBackground) (lyricsTextSize * 0.82f).sp else lyricsTextSize.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                fontStyle = if (isAllBackground) FontStyle.Italic else FontStyle.Normal,
                                lineHeight = (lyricsTextSize * lyricsLineSpacing).sp,
                                fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily,
                            ),
                            color = textColor.copy(alpha = if (isActive) 1f else inactiveAlpha),
                            textAlign = textAlign,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // ── Romanization ──
                    val romanizedText by item.romanizedTextFlow.collectAsState()
                    if (romanizedText != null) {
                        Text(
                            text = romanizedText!!,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = (lyricsTextSize * 0.55f).sp,
                                lineHeight = (lyricsTextSize * 0.75f).sp,
                                fontWeight = FontWeight.Normal,
                                fontStyle = if (isAllBackground) FontStyle.Italic else FontStyle.Normal,
                                fontFamily = lyricsFontFamily ?: MaterialTheme.typography.bodyMedium.fontFamily,
                            ),
                            color = textColor.copy(alpha = if (isActive) 0.75f else inactiveAlpha * 0.7f),
                            textAlign = textAlign,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = (lyricsTextSize * 0.3f).dp),
                        )
                    }
                    if (autoTranslateLyrics || openRouterApiKey.isNotBlank()) {
                        val translatedText by item.translatedTextFlow.collectAsState(initial = null)
                        translatedText?.let { translated ->
                            Text(
                                text = translated,
                                fontSize = (lyricsTextSize * 0.7f).sp,
                                color = textColor.copy(alpha = if (isActive) 0.7f else 0.4f),
                                style = MaterialTheme.typography.bodyMedium.copy(shadow = null),
                                textAlign = textAlign,
                                fontWeight = FontWeight.Normal,
                                modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Bottom spacer for overscroll
            item {
                Spacer(modifier = Modifier.height(300.dp))
            }
        }

        // ── Resume auto-scroll button ──
        if (isManualScrolling && isSynced) {
            androidx.compose.material3.FilledTonalButton(
                onClick = {
                    isManualScrolling = false
                    scope.launch {
                        val viewportHeight = listState.layoutInfo.viewportSize.height
                        listState.animateScrollToItem(
                            index = currentLineIndex,
                            scrollOffset = -(viewportHeight * 0.35f).toInt()
                        )
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(24.dp),
            ) {
                Text(
                    text = "Resume",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (isSelectionModeActive) {
            mediaMetadata?.let { metadata ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    color = Color.Black.copy(alpha = 0.3f),
                                    shape = CircleShape
                                )
                                .clickable {
                                    isSelectionModeActive = false
                                    selectedIndices.clear()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.close),
                                contentDescription = stringResource(R.string.cancel),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .background(
                                    color = if (selectedIndices.isNotEmpty())
                                        Color.White.copy(alpha = 0.9f)
                                    else
                                        Color.White.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(24.dp)
                                )
                                .clickable(enabled = selectedIndices.isNotEmpty()) {
                                    if (selectedIndices.isNotEmpty()) {
                                        val sortedIndices = selectedIndices.sorted()
                                        val selectedEntries = sortedIndices
                                            .mapNotNull { entriesWithWords.getOrNull(it) }
                                        val selectedLyricsText = selectedEntries
                                            .joinToString("\n") { it.text }
                                        // Una línea por verso seleccionado (vacía si ese verso
                                        // no tiene traducción) para poder intercalarlas.
                                        val selectedTranslation = selectedEntries
                                            .map { it.translatedTextFlow.value.orEmpty() }
                                        shareTranslatedText = selectedTranslation
                                            .takeIf { list -> list.any { it.isNotBlank() } }
                                            ?.joinToString("\n")

                                        if (selectedLyricsText.isNotBlank()) {
                                            shareDialogData = Triple(
                                                selectedLyricsText,
                                                metadata.title ?: "",
                                                metadata.artists.joinToString { it.name }
                                            )
                                            showShareCarouselSheet = true
                                        }
                                        isSelectionModeActive = false
                                        selectedIndices.clear()
                                    }
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.share),
                                contentDescription = stringResource(R.string.share_selected),
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.share),
                                color = Color.Black,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }


        // ── Carrusel de estilos para compartir letras ──────────────────────────────
        if (showShareCarouselSheet) {
            shareDialogData?.let { (lyricText, title, artist) ->

                mediaMetadata?.let { metadata ->

                    LyricsShareCarouselSheet(
                        lyricText = lyricText,
                        mediaMetadata = metadata,
                        initialConfig = LyricsCardConfig(fontName = shareFontName),
                        translatedText = shareTranslatedText,
                        onDismiss = {
                            showShareCarouselSheet = false
                            shareDialogData = null
                        },
                        onShare = { config, shareText ->

                            showShareCarouselSheet = false
                            shareDialogData = null

                            scope.launch {
                                runCatching {

                                    val bitmap =
                                        ComposeToImage.createLyricsImageWithConfig(
                                            context = context,
                                            coverArtUrl = metadata.thumbnailUrl,
                                            songTitle = title,
                                            artistName = artist,
                                            lyrics = shareText,
                                            config = config,
                                            outputSize = 1080,
                                        )

                                    val uri = ComposeToImage.saveBitmapAsFile(
                                        context = context,
                                        bitmap = bitmap,
                                        fileName = "lyrics_${System.currentTimeMillis()}",
                                    )

                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }

                                    context.startActivity(
                                        Intent.createChooser(shareIntent, null)
                                    )
                                }
                            }
                        },
                        onSave = { config, shareText ->

                            scope.launch {
                                runCatching {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        val bitmap =
                                            ComposeToImage.createLyricsImageWithConfig(
                                                context = context,
                                                coverArtUrl = metadata.thumbnailUrl,
                                                songTitle = title,
                                                artistName = artist,
                                                lyrics = shareText,
                                                config = config,
                                                outputSize = 1080,
                                            )

                                        ComposeToImage.saveBitmapAsFile(
                                            context = context,
                                            bitmap = bitmap,
                                            fileName = "lyrics_saved_${System.currentTimeMillis()}",
                                        )
                                    }
                                }
                            }

                            showShareCarouselSheet = false
                            shareDialogData = null
                        },
                    )
                }
            }
        }
    }

    if (showProgressDialog) {
        BasicAlertDialog(onDismissRequest = {}) {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(modifier = Modifier.padding(32.dp)) {
                    Text(
                        text = stringResource(R.string.generating_image) + "\n" + stringResource(R.string.please_wait),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    if (showShareDialog && shareDialogData != null) {
        val (lyricsText, songTitle, artists) = shareDialogData!!
        BasicAlertDialog(onDismissRequest = { showShareDialog = false }) {
            Card(
                shape = RoundedCornerShape(28.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(0.85f)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.share_lyrics),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val songLink = "https://music.youtube.com/watch?v=${mediaMetadata?.id}"
                                val shareIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "\"$lyricsText\"\n\n$songTitle - $artists\n$songLink")
                                }
                                context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_lyrics)))
                                showShareDialog = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.share),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.share_as_text),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                shareDialogData = Triple(lyricsText, songTitle, artists)
                                showColorPickerDialog = true
                                showShareDialog = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.share),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.share_as_image),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable { showShareDialog = false }
                                .padding(vertical = 8.dp, horizontal = 12.dp)
                        )
                    }
                }
            }
        }
    }

    if (showColorPickerDialog && shareDialogData != null) {
        val (lyricsText, songTitle, artists) = shareDialogData!!
        val coverUrl = mediaMetadata?.thumbnailUrl

        LaunchedEffect(coverUrl) {
            if (coverUrl != null) {
                withContext(Dispatchers.IO) {
                    try {
                        val req = ImageRequest.Builder(context).data(coverUrl).size(200, 200).allowHardware(false).build()
                        val result = context.imageLoader.execute(req)
                        val bmp = result.image?.toBitmap()
                        if (bmp != null) {
                            val palette = Palette.from(bmp).generate()
                            paletteGlassStyle = LyricsGlassStyle.fromPalette(palette)
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        val availableStyles = remember(paletteGlassStyle) {
            val base = LyricsGlassStyle.allPresets.toMutableList()
            paletteGlassStyle?.let { base.add(0, it) }
            base
        }

        BasicAlertDialog(onDismissRequest = { showColorPickerDialog = false }) {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 24.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.customize_colors),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.02).em
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .clip(RoundedCornerShape(20.dp))
                    ) {
                        LyricsImageCard(
                            lyricText = lyricsText,
                            mediaMetadata = mediaMetadata ?: return@Box,
                            glassStyle = selectedGlassStyle,
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = stringResource(id = R.string.customize_colors),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        availableStyles.forEach { style ->
                            val isSelected = selectedGlassStyle == style
                            Box(
                                modifier = Modifier
                                    .size(width = 72.dp, height = 72.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .then(
                                        if (isSelected) {
                                            Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                                        } else {
                                            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                        }
                                    )
                                    .clickable { selectedGlassStyle = style },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    style.surfaceTint.copy(alpha = 0.6f),
                                                    style.overlayColor.copy(alpha = 0.4f),
                                                )
                                            ),
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                )
                                Box(
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .fillMaxSize()
                                        .background(style.surfaceTint.copy(alpha = style.surfaceAlpha), RoundedCornerShape(10.dp))
                                        .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = "Aa", color = style.textColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = {
                            showColorPickerDialog = false
                            showProgressDialog = true
                            scope.launch {
                                try {
                                    val exportSize = 1080
                                    val image = ComposeToImage.createLyricsImage(
                                        context = context,
                                        coverArtUrl = coverUrl,
                                        songTitle = songTitle,
                                        artistName = artists,
                                        lyrics = lyricsText,
                                        width = exportSize,
                                        height = exportSize,
                                        glassStyle = selectedGlassStyle,
                                        fontName = shareFontName,
                                    )
                                    val timestamp = System.currentTimeMillis()
                                    val filename = "lyrics_$timestamp"
                                    val uri = ComposeToImage.saveBitmapAsFile(context, image, filename)
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share Lyrics"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to create image: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    showProgressDialog = false
                                }
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text(text = stringResource(id = R.string.share), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}


// ──────────────────────────────────────────────────────────────────────
// Line-level composable: renders words with fluid fill animation
// ──────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricsLineV2(
    words: List<WordTimestamp>,
    isActive: Boolean,
    isPast: Boolean,
    currentPositionMs: Long,
    textColor: Color,
    inactiveAlpha: Float,
    baseFontSize: Float,
    isLineAllBackground: Boolean,
    textAlign: TextAlign,
    lyricsFontFamily: FontFamily?,
    lyricsV2Style: LyricsV2Style,
) {
    val arrangement = when (textAlign) {
        TextAlign.Center -> Arrangement.Center
        TextAlign.End -> Arrangement.End
        else -> Arrangement.Start
    }

    // Split words into main and background
    val mainWords = words.filter { !it.isBackground }
    val bgWords = words.filter { it.isBackground }

    // 1. Render main words First (if any)
    if (mainWords.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = arrangement,
        ) {
            mainWords.forEachIndexed { wordIndex, word ->
                if (word.text == " ") {
                    Text(
                        text = " ",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = if (isLineAllBackground) (baseFontSize * 0.82f).sp else baseFontSize.sp,
                            fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily,
                        ),
                        color = Color.Transparent,
                    )
                    return@forEachIndexed
                }
                if (word.text == "\n") {
                    Spacer(modifier = Modifier.fillMaxWidth())
                    return@forEachIndexed
                }

                AnimatedWordV2(
                    word = word,
                    wordIndex = wordIndex,
                    isLineActive = isActive,
                    isLinePast = isPast,
                    currentPositionMs = currentPositionMs,
                    textColor = textColor,
                    inactiveAlpha = inactiveAlpha,
                    fontSize = if (isLineAllBackground) baseFontSize * 0.82f else baseFontSize,
                    isBackground = isLineAllBackground,
                    lyricsFontFamily = lyricsFontFamily,
                    v2Style = lyricsV2Style,
                )
            }
        }
    }

    // 2. Render background words explicitly on a NEW line, noticeably smaller
    if (bgWords.isNotEmpty()) {
        val spacerHeight = if (mainWords.isNotEmpty()) 4.dp else 0.dp
        if (mainWords.isNotEmpty()) Spacer(modifier = Modifier.height(spacerHeight))

        FlowRow(
            modifier = Modifier.fillMaxWidth().alpha(0.85f), // Slightly dimmer overall
            horizontalArrangement = arrangement,
        ) {
            bgWords.forEachIndexed { wordIndex, word ->
                if (word.text == " ") {
                    Text(
                        text = " ",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = (baseFontSize * 0.65f).sp,
                            fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily,
                        ),
                        color = Color.Transparent,
                    )
                    return@forEachIndexed
                }

                AnimatedWordV2(
                    word = word,
                    wordIndex = wordIndex + mainWords.size,
                    isLineActive = isActive,
                    isLinePast = isPast,
                    currentPositionMs = currentPositionMs,
                    textColor = textColor,
                    inactiveAlpha = inactiveAlpha,
                    fontSize = baseFontSize * 0.65f, // ~65% size of main text
                    isBackground = true, // Force dimmer styling inside AnimatedWordV2
                    lyricsFontFamily = lyricsFontFamily,
                    v2Style = lyricsV2Style,
                )
            }
        }
    }
}


// ──────────────────────────────────────────────────────────────────────
// Word-level composable: liquid fill sweep + glow + bounce
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun AnimatedWordV2(
    word: WordTimestamp,
    wordIndex: Int,
    isLineActive: Boolean,
    isLinePast: Boolean,
    currentPositionMs: Long,
    textColor: Color,
    inactiveAlpha: Float,
    fontSize: Float,
    isBackground: Boolean,
    lyricsFontFamily: FontFamily?,
    v2Style: LyricsV2Style,
) {
    val wordStartMs = (word.startTime * 1000).toLong()
    val wordEndMs = (word.endTime * 1000).toLong()
    val wordDuration = (wordEndMs - wordStartMs).coerceAtLeast(1L)

    val isWordComplete = currentPositionMs >= wordEndMs
    val isWordActive = currentPositionMs in wordStartMs until wordEndMs

    // Perfect linear progress [0..1] that matches individual word timings
    val progress = when {
        isWordComplete -> 1f
        currentPositionMs <= wordStartMs -> 0f
        else -> ((currentPositionMs - wordStartMs).toFloat() / wordDuration).coerceIn(0f, 1f)
    }

    // ── Bounce and Float animation ──
    val sinProgress = kotlin.math.sin(progress * kotlin.math.PI).toFloat()

    val wordScale = when (v2Style) {
        LyricsV2Style.DEPTH_3D -> {
            val targetScale = if (isWordActive) 1.12f else if (isLineActive) 1.0f else 0.88f
            val animScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = targetScale,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                ),
                label = "wordScaleDepth3d"
            )
            animScale
        }
        LyricsV2Style.BOUNCE -> {
            val targetScale = if (isWordActive) 1.15f else 1.0f
            val animScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = targetScale,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioHighBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                ),
                label = "wordScaleBounce"
            )
            animScale
        }
        LyricsV2Style.MINIMAL_FADE -> 1f
        else -> 1f + (0.015f * sinProgress)
    }

    // Float offset
    val targetFloat = if (isWordActive &&
        v2Style != LyricsV2Style.MINIMAL_FADE &&
        v2Style != LyricsV2Style.SPOTLIGHT &&
        v2Style != LyricsV2Style.DEPTH_3D &&
        v2Style != LyricsV2Style.TYPEWRITER &&
        v2Style != LyricsV2Style.HEAT_MAP
    ) -4f else 0f

    val floatOffset by androidx.compose.animation.core.animateFloatAsState(
        targetValue = targetFloat,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
        ),
        label = "wordFloat"
    )

    // ── Glow intensity ──
    val glowProgress = (progress * 2f).coerceAtMost(1f)
    val glowAlpha = if (isWordActive) {
        if (v2Style == LyricsV2Style.SPOTLIGHT) glowProgress * 0.98f else glowProgress * 0.95f
    } else 0f
    val glowRadius = if (isWordActive) {
        if (v2Style == LyricsV2Style.SPOTLIGHT) glowProgress * 50f else glowProgress * 35f
    } else 0f

    val hasGlow = v2Style == LyricsV2Style.LIQUID_FLOW || v2Style == LyricsV2Style.SPOTLIGHT || v2Style == LyricsV2Style.GLASS_CARDS
    val finalGlowAlpha = if (hasGlow) glowAlpha else 0f
    val finalGlowRadius = if (hasGlow) glowRadius else 0f

    val actualFontSize = if (isBackground) fontSize * 0.85f else fontSize
    val fontWeight = FontWeight.SemiBold

    val customInactiveAlpha = if (v2Style == LyricsV2Style.SPOTLIGHT) inactiveAlpha * 0.3f else inactiveAlpha

    // Glass card background alpha
    val glassCardAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isWordActive && v2Style == LyricsV2Style.GLASS_CARDS) 0.15f else 0f,
        label = "glassCardAlpha"
    )

    // ── Two-layer rendering: dim base + custom overlay ──
    Box(
        modifier = Modifier
            .graphicsLayer {
                translationY = floatOffset * density
                scaleX = wordScale
                scaleY = wordScale
            }
            .drawBehind {
                if (glassCardAlpha > 0f) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = glassCardAlpha),
                        cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                    )
                }
            }
    ) {
        // Layer 1: Base text (always dimmed)
        Text(
            text = word.text,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = actualFontSize.sp,
                fontWeight = fontWeight,
                fontStyle = FontStyle.Normal,
                lineHeight = (actualFontSize * 1.35f).sp,
                fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily,
            ),
            color = textColor.copy(alpha = if (isBackground) customInactiveAlpha * 0.7f else customInactiveAlpha),
        )

        // Layer 2: Filled overlay
        if (isWordComplete || isWordActive || isLinePast) {
            val activeColor = when (v2Style) {
                LyricsV2Style.HEAT_MAP -> {
                    when {
                        progress < 0.5f -> androidx.compose.ui.graphics.lerp(Color(0xFF00E5FF), Color(0xFFFF9100), progress * 2f)
                        else -> androidx.compose.ui.graphics.lerp(Color(0xFFFF9100), Color.White, (progress - 0.5f) * 2f)
                    }
                }
                LyricsV2Style.CHROMATIC -> {
                    getRainbowColor(((wordIndex * 0.15f) + progress) % 1f)
                }
                else -> textColor
            }

            val overlayModifier = when {
                isWordActive && !isWordComplete -> {
                    when (v2Style) {
                        LyricsV2Style.LIQUID_FLOW -> {
                            Modifier
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                .drawWithContent {
                                    drawContent()
                                    val edgeWidth = 8.dp.toPx()
                                    val center = (size.width + edgeWidth * 2) * progress - edgeWidth
                                    drawRect(
                                        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                            colors = listOf(Color.Black, Color.Transparent),
                                            startX = center - edgeWidth,
                                            endX = center + edgeWidth,
                                        ),
                                        blendMode = BlendMode.DstIn,
                                    )
                                }
                        }
                        LyricsV2Style.GEOMETRIC -> {
                            Modifier
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                .drawWithContent {
                                    drawContent()
                                    val edgeWidth = 1.dp.toPx()
                                    val center = (size.width + edgeWidth * 2) * progress - edgeWidth
                                    drawRect(
                                        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                            colors = listOf(Color.Black, Color.Transparent),
                                            startX = center - edgeWidth,
                                            endX = center + edgeWidth,
                                        ),
                                        blendMode = BlendMode.DstIn,
                                    )
                                }
                        }
                        LyricsV2Style.TYPEWRITER -> {
                            Modifier.drawWithContent {
                                clipRect(right = size.width * progress) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                        }
                        else -> Modifier
                    }
                }
                else -> Modifier
            }

            Text(
                text = word.text,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = actualFontSize.sp,
                    fontWeight = fontWeight,
                    fontStyle = FontStyle.Normal,
                    lineHeight = (actualFontSize * 1.35f).sp,
                    fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily,
                    shadow = if (finalGlowAlpha > 0f) {
                        Shadow(
                            color = activeColor.copy(alpha = finalGlowAlpha),
                            offset = Offset.Zero,
                            blurRadius = finalGlowRadius.coerceAtLeast(1f),
                        )
                    } else null,
                ),
                color = activeColor.copy(
                    alpha = if (isBackground) 0.75f else 1f
                ),
                modifier = overlayModifier
            )
        }
    }
}

private fun getRainbowColor(progress: Float): Color {
    val colors = listOf(
        Color(0xFFFF0000), // Red
        Color(0xFFFF7F00), // Orange
        Color(0xFFFFFF00), // Yellow
        Color(0xFF00FF00), // Green
        Color(0xFF0000FF), // Blue
        Color(0xFF4B0082), // Indigo
        Color(0xFF9400D3)  // Violet
    )
    val size = colors.size
    val scaledProgress = progress * (size - 1)
    val index = scaledProgress.toInt().coerceIn(0, size - 2)
    val fraction = scaledProgress - index
    return androidx.compose.ui.graphics.lerp(colors[index], colors[index + 1], fraction)
}