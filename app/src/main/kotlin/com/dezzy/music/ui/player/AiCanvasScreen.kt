/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 *
 * AI Canvas — a procedural "kinetic maximalist typography" visualizer.
 * It builds a TikTok/Instagram-edit style composition from the currently
 * playing song's synced lyrics: giant hero words stacked with smaller words
 * and app icons as visual punctuation, colored from the album artwork palette.
 *
 * v1 is fully procedural (no network / no generative model): a deterministic
 * seed derived from the song id makes each track's design stable and "designed"
 * rather than chaotic. The AI layer (word emphasis / mood) can be plugged in
 * later on top of this engine.
 */

package com.dezzy.music.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player.STATE_READY
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.dezzy.music.LocalPlayerConnection
import com.dezzy.music.R
import com.dezzy.music.constants.AiProviderKey
import com.dezzy.music.constants.CanvasAiEnhanceKey
import com.dezzy.music.constants.OpenRouterApiKey
import com.dezzy.music.constants.OpenRouterBaseUrlKey
import com.dezzy.music.constants.OpenRouterModelKey
import com.dezzy.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.dezzy.music.lyrics.AiCanvasAnalyzer
import com.dezzy.music.lyrics.CanvasAnalysis
import com.dezzy.music.lyrics.LyricsEntry
import com.dezzy.music.lyrics.LyricsUtils.findCurrentLineIndex
import com.dezzy.music.lyrics.LyricsUtils.isTtml
import com.dezzy.music.lyrics.LyricsUtils.parseLyrics
import com.dezzy.music.lyrics.LyricsUtils.parseTtml
import com.dezzy.music.models.MediaMetadata
import com.dezzy.music.ui.theme.PlayerColorExtractor
import com.dezzy.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.random.Random

// ---------------------------------------------------------------------------
// Procedural composition engine
// ---------------------------------------------------------------------------

/** Layout personalities. Chosen deterministically per song so each track feels distinct. */
private enum class CanvasTemplate(
    val heroRotation: Float,
    val jitter: Float,
    val bias: Alignment.Horizontal,
    val arrangement: Arrangement.Horizontal,
) {
    CENTER_STACK(0f, 3f, Alignment.CenterHorizontally, Arrangement.Center),
    LEFT_COLUMN(-2f, 5f, Alignment.Start, Arrangement.Start),
    DIAGONAL_SCATTER(-6f, 10f, Alignment.CenterHorizontally, Arrangement.Center),
}

private val CANVAS_TEMPLATES = CanvasTemplate.values()

/**
 * Song mood (from the AI analysis in phase 2) biases the energy of the composition:
 * how much words tilt, how many icons appear, and which layout template is favored.
 */
private enum class CanvasMood(
    val jitterScale: Float,
    val iconChance: Float,
    val templateBias: CanvasTemplate?,
) {
    HYPE(1.6f, 0.32f, CanvasTemplate.DIAGONAL_SCATTER),
    ENERGETIC(1.4f, 0.28f, CanvasTemplate.DIAGONAL_SCATTER),
    PLAYFUL(1.5f, 0.30f, null),
    ROMANTIC(0.5f, 0.12f, CanvasTemplate.CENTER_STACK),
    DREAMY(0.6f, 0.14f, CanvasTemplate.CENTER_STACK),
    MELANCHOLIC(0.5f, 0.10f, CanvasTemplate.LEFT_COLUMN),
    DARK(0.7f, 0.12f, CanvasTemplate.LEFT_COLUMN),
    NEUTRAL(1f, 0.20f, null);

    companion object {
        fun from(value: String?): CanvasMood = when (value?.lowercase()) {
            "hype" -> HYPE
            "energetic" -> ENERGETIC
            "playful" -> PLAYFUL
            "romantic" -> ROMANTIC
            "dreamy" -> DREAMY
            "melancholic" -> MELANCHOLIC
            "dark" -> DARK
            else -> NEUTRAL
        }
    }
}

/**
 * Icons used as "visual punctuation" between words — a bespoke, cohesive family
 * (see res/drawable/canvas_*.xml). One visual language: geometric, solid, same weight.
 */
private val CANVAS_ICON_POOL = intArrayOf(
    R.drawable.canvas_spark,
    R.drawable.canvas_asterisk,
    R.drawable.canvas_bars,
    R.drawable.canvas_bolt,
    R.drawable.canvas_star,
    R.drawable.canvas_note,
    R.drawable.canvas_heart,
)

/**
 * Decorative objects that bloom BEHIND the lyrics (flowers, roses, stars, frames,
 * figures, people…). Per-mood pools so the ambience matches the song's feel.
 */
private val DECOR_ROMANTIC = intArrayOf(
    R.drawable.canvas_rose, R.drawable.canvas_flower, R.drawable.canvas_petal,
    R.drawable.canvas_heart, R.drawable.canvas_butterfly,
)
private val DECOR_DREAMY = intArrayOf(
    R.drawable.canvas_flower, R.drawable.canvas_petal, R.drawable.canvas_butterfly,
    R.drawable.canvas_sparkle, R.drawable.canvas_star, R.drawable.canvas_leaf,
)
private val DECOR_HYPE = intArrayOf(
    R.drawable.canvas_star, R.drawable.canvas_sparkle, R.drawable.canvas_bolt,
    R.drawable.canvas_spark, R.drawable.canvas_person,
)
private val DECOR_DARK = intArrayOf(
    R.drawable.canvas_frame, R.drawable.canvas_person, R.drawable.canvas_star,
    R.drawable.canvas_leaf,
)
private val DECOR_NEUTRAL = intArrayOf(
    R.drawable.canvas_flower, R.drawable.canvas_star, R.drawable.canvas_sparkle,
    R.drawable.canvas_frame, R.drawable.canvas_petal, R.drawable.canvas_leaf,
    R.drawable.canvas_person,
)

private fun decorPoolFor(mood: CanvasMood): IntArray = when (mood) {
    CanvasMood.ROMANTIC -> DECOR_ROMANTIC
    CanvasMood.DREAMY -> DECOR_DREAMY
    CanvasMood.HYPE, CanvasMood.ENERGETIC, CanvasMood.PLAYFUL -> DECOR_HYPE
    CanvasMood.DARK, CanvasMood.MELANCHOLIC -> DECOR_DARK
    CanvasMood.NEUTRAL -> DECOR_NEUTRAL
}

/** One background object instance, positioned in normalized [0,1] stage space. */
private data class DecorItem(
    val res: Int,
    val xFraction: Float,
    val yFraction: Float,
    val sizeDp: Float,
    val rotation: Float,
    val baseAlpha: Float,
    val driftPhase: Float,
    val driftSpeed: Float,
    val spin: Float,
    val appearDelayMs: Int,
)

/**
 * Builds the set of background objects for a line. Deterministic per (seed,line) so
 * the ambience is stable, but re-seeded each line so objects change as the song moves.
 */
private fun buildDecor(
    lineIndex: Int,
    seed: Int,
    mood: CanvasMood,
): List<DecorItem> {
    val pool = decorPoolFor(mood)
    val rnd = Random(seed * 101L + lineIndex * 197L + 7L)
    val count = when (mood) {
        CanvasMood.HYPE, CanvasMood.ENERGETIC, CanvasMood.PLAYFUL -> 7
        CanvasMood.ROMANTIC, CanvasMood.DREAMY -> 6
        else -> 5
    }
    return List(count) {
        DecorItem(
            res = pool[rnd.nextInt(pool.size)],
            xFraction = rnd.nextFloat(),
            yFraction = rnd.nextFloat(),
            sizeDp = 40f + rnd.nextFloat() * 90f,
            rotation = rnd.nextFloat() * 360f,
            baseAlpha = 0.06f + rnd.nextFloat() * 0.12f,
            driftPhase = rnd.nextFloat() * TWO_PI_CANVAS,
            driftSpeed = 0.5f + rnd.nextFloat() * 1.2f,
            spin = (rnd.nextFloat() * 2f - 1f) * 18f,
            appearDelayMs = rnd.nextInt(600),
        )
    }
}

private const val TWO_PI_CANVAS = 6.2831855f

private val WORD_SPLIT = Regex("\\s+")
private val PUNCT_STRIP = Regex("[^\\p{L}\\p{N}']")

private val STOPWORDS = setOf(
    "the", "a", "an", "and", "or", "but", "of", "to", "in", "on", "at", "is", "it",
    "i", "you", "me", "my", "we", "he", "she", "they", "that", "this", "for", "with",
    "de", "la", "el", "y", "que", "en", "un", "una", "los", "las", "se", "tu", "mi",
    "no", "es", "lo", "por", "con", "su", "al", "del", "me", "te", "yo",
)

private sealed interface CanvasItem
private data class WordItem(
    val text: String,
    val sizeSp: Float,
    val weight: FontWeight,
    val rotation: Float,
    val isHero: Boolean,
    val isAccent: Boolean,
) : CanvasItem

private data class IconItem(val res: Int, val sizeDp: Float, val rotation: Float) : CanvasItem

private fun normalizeWord(word: String): String =
    word.replace(PUNCT_STRIP, "").lowercase()

/** Words that recur across the whole song get a scoring bonus (they're the "hook"). */
private fun buildRecurringWords(lines: List<LyricsEntry>): Map<String, Int> {
    val counts = HashMap<String, Int>()
    for (line in lines) {
        for (raw in line.text.split(WORD_SPLIT)) {
            val w = normalizeWord(raw)
            if (w.length >= 3 && w !in STOPWORDS) {
                counts[w] = (counts[w] ?: 0) + 1
            }
        }
    }
    return counts.filterValues { it >= 2 }
}

private fun scoreWord(word: String, recurring: Map<String, Int>): Int {
    val norm = normalizeWord(word)
    if (norm.isEmpty()) return -1
    var score = norm.length
    if (word.length > 1 && word == word.uppercase() && word.any { it.isLetter() }) score += 5
    if (word.endsWith("!") || word.endsWith("?")) score += 3
    if (norm in STOPWORDS) score -= 4
    score += (recurring[norm] ?: 0) * 2
    return score
}

private fun heroSizeFor(length: Int): Float = when {
    length <= 3 -> 108f
    length <= 5 -> 94f
    length <= 7 -> 80f
    length <= 10 -> 66f
    length <= 14 -> 52f
    else -> 44f
}

/**
 * Turns one lyric line into a maximalist composition of word/icon items.
 * Deterministic given (seed, lineIndex): the same line always renders identically.
 */
private fun buildLineComposition(
    line: String,
    lineIndex: Int,
    seed: Int,
    recurring: Map<String, Int>,
    template: CanvasTemplate,
    mood: CanvasMood = CanvasMood.NEUTRAL,
    forcedHero: String? = null,
): List<CanvasItem> {
    val words = line.split(WORD_SPLIT).filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()

    val rnd = Random(seed * 31L + lineIndex * 131L)

    // Prefer the AI-chosen hero word (phase 2); fall back to the local heuristic.
    var heroIndex = -1
    if (!forcedHero.isNullOrBlank()) {
        val target = normalizeWord(forcedHero)
        if (target.isNotEmpty()) {
            heroIndex = words.indexOfFirst { normalizeWord(it) == target }
        }
    }
    if (heroIndex < 0) {
        var bestScore = Int.MIN_VALUE
        words.forEachIndexed { i, w ->
            val s = scoreWord(w, recurring)
            if (s > bestScore) {
                bestScore = s
                heroIndex = i
            }
        }
    }

    val jitter = template.jitter * mood.jitterScale
    val items = ArrayList<CanvasItem>(words.size + 3)
    var iconsUsed = 0
    val maxIcons = if (words.size <= 3) 1 else 3

    words.forEachIndexed { i, word ->
        if (i == heroIndex) {
            val hero = word.trim().uppercase()
            items.add(
                WordItem(
                    text = hero,
                    sizeSp = heroSizeFor(hero.length),
                    weight = FontWeight.Black,
                    rotation = template.heroRotation,
                    isHero = true,
                    isAccent = false,
                )
            )
        } else {
            val roll = rnd.nextFloat()
            val accent = roll < 0.24f
            val size = if (accent) 38f + rnd.nextFloat() * 10f else 22f + rnd.nextFloat() * 12f
            items.add(
                WordItem(
                    text = word,
                    sizeSp = size,
                    weight = if (accent) FontWeight.ExtraBold else FontWeight.SemiBold,
                    rotation = (rnd.nextFloat() * 2f - 1f) * jitter,
                    isHero = false,
                    isAccent = accent,
                )
            )
        }

        // Occasionally drop an icon after a word.
        if (iconsUsed < maxIcons && rnd.nextFloat() < mood.iconChance) {
            items.add(
                IconItem(
                    res = CANVAS_ICON_POOL[rnd.nextInt(CANVAS_ICON_POOL.size)],
                    sizeDp = 26f + rnd.nextFloat() * 20f,
                    rotation = (rnd.nextFloat() * 2f - 1f) * (jitter + 4f),
                )
            )
            iconsUsed++
        }
    }
    return items
}

// ---------- Color helpers ----------

private fun Color.darken(factor: Float): Color =
    copy(red = red * factor, green = green * factor, blue = blue * factor)

/** Push a palette color to be bright & saturated enough to read as an accent on a dark bg. */
private fun Color.vivid(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.65f)
    hsv[2] = hsv[2].coerceIn(0.78f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun accentFrom(colors: List<Color>): Color {
    val best = colors.maxByOrNull {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(it.toArgb(), hsv)
        hsv[1] * hsv[2]
    }
    return best?.vivid() ?: Color(0xFFFF4D6D)
}

private fun backgroundColors(colors: List<Color>): List<Color> {
    val base = if (colors.isEmpty()) listOf(Color(0xFF141414), Color(0xFF1F1F1F)) else colors
    val dark = base.take(3).map { it.darken(0.30f) }
    return if (dark.size >= 2) dark else listOf(Color(0xFF141414), Color(0xFF202020))
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiCanvasScreen(
    mediaMetadata: MediaMetadata,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val player = playerConnection.player
    val context = LocalContext.current

    val playbackState by playerConnection.playbackState.collectAsState()
    val currentLyrics by playerConnection.currentLyrics.collectAsState(initial = null)

    val lyrics = remember(currentLyrics) { currentLyrics?.lyrics?.trim() }

    // Parse lyrics into timed lines (LRC / TTML / plain text).
    val lines = remember(lyrics, mediaMetadata.id, mediaMetadata.duration) {
        when {
            lyrics.isNullOrEmpty() || lyrics == LYRICS_NOT_FOUND -> emptyList()
            lyrics.startsWith("[") -> parseLyrics(lyrics)
            isTtml(lyrics) -> parseTtml(lyrics, mediaMetadata.duration)
            else -> lyrics.lines().mapIndexed { i, l -> LyricsEntry(i * 100L, l) }
        }
    }
    val isSynced = remember(lyrics) {
        !lyrics.isNullOrEmpty() && (lyrics.startsWith("[") || isTtml(lyrics))
    }

    // Deterministic design seed → same song always looks the same.
    val seed = remember(mediaMetadata.id) { mediaMetadata.id.hashCode() }
    val baseTemplate = remember(seed) { CANVAS_TEMPLATES[seed.mod(CANVAS_TEMPLATES.size)] }
    val recurring = remember(lines) { buildRecurringWords(lines) }

    // ----- Phase 2: AI enhancement (per-line emphasis + mood) -----
    val aiEnhance by rememberPreference(CanvasAiEnhanceKey, defaultValue = false)
    val aiProvider by rememberPreference(AiProviderKey, "OpenRouter")
    val openRouterApiKey by rememberPreference(OpenRouterApiKey, "")
    val openRouterBaseUrl by rememberPreference(
        OpenRouterBaseUrlKey, "https://openrouter.ai/api/v1/chat/completions"
    )
    val openRouterModel by rememberPreference(
        OpenRouterModelKey, "mistralai/mistral-small-3.1-24b-instruct:free"
    )

    var analysis by remember(mediaMetadata.id) { mutableStateOf<CanvasAnalysis?>(null) }
    var aiLoading by remember(mediaMetadata.id) { mutableStateOf(false) }

    LaunchedEffect(mediaMetadata.id, lines, aiEnhance, aiProvider, openRouterApiKey) {
        analysis = null
        val nonEmpty = lines.map { it.text }.filter { it.isNotBlank() }
        if (!aiEnhance || openRouterApiKey.isBlank() || aiProvider == "DeepL" || nonEmpty.isEmpty()) {
            return@LaunchedEffect
        }
        aiLoading = true
        val result = AiCanvasAnalyzer.analyze(
            lines = nonEmpty,
            provider = aiProvider,
            apiKey = openRouterApiKey,
            baseUrl = openRouterBaseUrl,
            model = openRouterModel,
            context = context,
        )
        aiLoading = false
        analysis = result.getOrNull()
    }

    val mood = remember(analysis) { CanvasMood.from(analysis?.mood) }
    val template = remember(baseTemplate, mood) { mood.templateBias ?: baseTemplate }

    // Map AI hero words back to their line index (AI received non-empty lines in order).
    val heroByLineIndex = remember(lines, analysis) {
        val map = HashMap<Int, String>()
        val heroes = analysis?.heroes
        if (heroes != null) {
            var h = 0
            lines.forEachIndexed { idx, entry ->
                if (entry.text.isNotBlank()) {
                    if (h < heroes.size) map[idx] = heroes[h]
                    h++
                }
            }
        }
        map
    }

    // Palette extraction from album artwork (same pipeline as the player background).
    var gradientColors by remember { mutableStateOf<List<Color>>(emptyList()) }
    val fallbackColor = MaterialTheme.colorScheme.surface.toArgb()
    LaunchedEffect(mediaMetadata.id) {
        val url = mediaMetadata.thumbnailUrl ?: return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(Size(PlayerColorExtractor.Config.IMAGE_SIZE, PlayerColorExtractor.Config.IMAGE_SIZE))
            .allowHardware(false)
            .memoryCacheKey("canvas_${mediaMetadata.id}")
            .build()
        val image = runCatching {
            withContext(Dispatchers.IO) { context.imageLoader.execute(request) }
        }.getOrNull()?.image
        if (image != null) {
            val bitmap = image.toBitmap()
            val palette = withContext(Dispatchers.Default) {
                Palette.from(bitmap)
                    .maximumColorCount(PlayerColorExtractor.Config.MAX_COLOR_COUNT)
                    .resizeBitmapArea(PlayerColorExtractor.Config.BITMAP_AREA)
                    .generate()
            }
            gradientColors = PlayerColorExtractor.extractGradientColors(palette, fallbackColor)
        }
    }

    val accent = remember(gradientColors) { accentFrom(gradientColors) }
    val bgColors = remember(gradientColors) { backgroundColors(gradientColors) }

    // Track playback position → current lyric line.
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playbackState) {
        if (playbackState == STATE_READY) {
            while (isActive) {
                position = player.currentPosition
                delay(120)
            }
        }
    }
    val currentLineIndex = remember(lines, position, isSynced) {
        if (!isSynced || lines.isEmpty()) -1
        else findCurrentLineIndex(lines, position, leadMs = 300L)
    }

    // Progress through the CURRENT line (0f..1f), used to reveal words one by one in
    // sync with the song. Falls back to a gentle time-based reveal for unsynced lyrics.
    val lineProgress = remember(currentLineIndex, position, lines, isSynced) {
        if (currentLineIndex < 0) {
            0f
        } else {
            val start = lines.getOrNull(currentLineIndex)?.time ?: 0L
            val end = lines.getOrNull(currentLineIndex + 1)?.time
                ?: (start + if (isSynced) 4000L else 2600L)
            val span = (end - start).coerceAtLeast(1L)
            ((position - start).toFloat() / span).coerceIn(0f, 1f)
        }
    }

    // Subtle living background — slow drifting gradient.
    val infinite = rememberInfiniteTransition(label = "canvasBg")
    val drift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift",
    )

    BackHandler(onBack = onBackClick)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = bgColors + bgColors.first(),
                    start = androidx.compose.ui.geometry.Offset(0f, 400f * drift),
                    end = androidx.compose.ui.geometry.Offset(1200f, 1600f - 400f * drift),
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.systemBars.asPaddingValues())
        ) {
            // Header — deliberately minimal chrome so the maximalist content leads.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                // Collapse affordance (left)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, radius = 20.dp),
                        ) { onBackClick() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.canvas_chevron_down),
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(24.dp),
                    )
                }

                // Wordmark (always centered, independent of the pill width)
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.canvas_spark),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "CANVAS",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 3.5.sp,
                        ),
                        color = Color.White.copy(alpha = 0.92f),
                    )
                }

                // Mood pill (right) — appears only when the AI layer is working/active.
                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    MoodPill(
                        loading = aiLoading,
                        mood = analysis?.mood,
                        accent = accent,
                        dotAlpha = drift,
                    )
                }
            }

            // Stage
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Animated background objects (behind the lyrics): flowers, roses,
                // stars, frames, figures, people… they bloom in and drift while the
                // line plays, then cross-fade with the next line's set.
                if (lines.isNotEmpty() && lyrics != LYRICS_NOT_FOUND) {
                    AnimatedContent(
                        targetState = currentLineIndex,
                        transitionSpec = {
                            fadeIn(tween(700)) togetherWith fadeOut(tween(500))
                        },
                        label = "canvasDecor",
                        modifier = Modifier.fillMaxSize(),
                    ) { idx ->
                        if (idx >= 0) {
                            val decor = remember(idx, seed, mood) { buildDecor(idx, seed, mood) }
                            CanvasDecorLayer(decor = decor, accent = accent, drift = drift)
                        } else {
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }

                when {
                    lines.isEmpty() || lyrics == LYRICS_NOT_FOUND -> {
                        CanvasFallback(mediaMetadata, accent, template)
                    }
                    else -> {
                        AnimatedContent(
                            targetState = currentLineIndex,
                            transitionSpec = {
                                (fadeIn(tween(420)) + scaleIn(
                                    initialScale = 0.82f,
                                    animationSpec = tween(520),
                                ) + slideInVertically(tween(520)) { it / 8 }) togetherWith
                                    (fadeOut(tween(240)) + scaleOut(
                                        targetScale = 1.08f,
                                        animationSpec = tween(300),
                                    ))
                            },
                            label = "canvasLine",
                        ) { idx ->
                            val text = lines.getOrNull(idx)?.text?.trim().orEmpty()
                            if (idx < 0 || text.isEmpty()) {
                                // Intro / instrumental gap.
                                CanvasFallback(mediaMetadata, accent, template)
                            } else {
                                val items = remember(idx, seed, template, mood, heroByLineIndex) {
                                    buildLineComposition(
                                        line = text,
                                        lineIndex = idx,
                                        seed = seed,
                                        recurring = recurring,
                                        template = template,
                                        mood = mood,
                                        forcedHero = heroByLineIndex[idx],
                                    )
                                }
                                // Only the current line reveals progressively; while a line is
                                // frozen (e.g. unsynced) everything is shown.
                                val progress = if (idx == currentLineIndex) lineProgress else 1f
                                CanvasComposition(items, accent, template, progress)
                            }
                        }
                    }
                }
            }

            // Context hint: next line, faded.
            val nextLine = lines.getOrNull(currentLineIndex + 1)?.text?.trim()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (!nextLine.isNullOrEmpty()) {
                    Text(
                        text = nextLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.35f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Minimal translucent pill showing AI analysis state (loading) or the detected mood. */
@Composable
private fun MoodPill(
    loading: Boolean,
    mood: String?,
    accent: Color,
    dotAlpha: Float,
) {
    if (!loading && mood == null) return
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .then(if (loading) Modifier.alpha(0.35f + 0.65f * dotAlpha) else Modifier)
                .clip(CircleShape)
                .background(accent),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = if (loading) "Analizando" else (mood ?: "").uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
            color = Color.White.copy(alpha = 0.8f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CanvasComposition(
    items: List<CanvasItem>,
    accent: Color,
    template: CanvasTemplate,
    progress: Float = 1f,
) {
    // Reveal items one at a time as the line plays. Each item gets an equal slice of
    // the line's duration; a small trailing window animates its fade/scale/slide in.
    val revealCount = items.size.coerceAtLeast(1)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = template.arrangement,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            // per-item reveal ratio 0f..1f (staggered across the line).
            val threshold = index.toFloat() / revealCount
            val window = (1f / revealCount).coerceAtLeast(0.12f)
            val reveal = ((progress - threshold) / window).coerceIn(0f, 1f)
            val eased = reveal * reveal * (3f - 2f * reveal) // smoothstep

            val animAlpha by animateFloatAsState(eased, tween(260), label = "wordAlpha")
            val animScale by animateFloatAsState(0.6f + 0.4f * eased, tween(320), label = "wordScale")

            val revealModifier = Modifier.graphicsLayer {
                alpha = animAlpha
                scaleX = animScale
                scaleY = animScale
                translationY = (1f - animAlpha) * 26f
            }

            when (item) {
                is WordItem -> {
                    val color = when {
                        item.isHero -> Color.White
                        item.isAccent -> accent
                        else -> Color.White.copy(alpha = 0.72f)
                    }
                    Text(
                        text = item.text,
                        style = TextStyle(
                            fontSize = item.sizeSp.sp,
                            fontWeight = item.weight,
                            color = color,
                            lineHeight = (item.sizeSp * 0.98f).sp,
                            shadow = if (item.isHero) {
                                Shadow(color = accent.copy(alpha = 0.55f), blurRadius = 32f)
                            } else null,
                        ),
                        modifier = revealModifier
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                            .rotate(item.rotation),
                    )
                }

                is IconItem -> {
                    Icon(
                        painter = painterResource(item.res),
                        contentDescription = null,
                        tint = accent,
                        modifier = revealModifier
                            .padding(horizontal = 6.dp)
                            .size(item.sizeDp.dp)
                            .rotate(item.rotation),
                    )
                }
            }
        }
    }
}

/**
 * Renders the animated decorative objects behind the lyrics. Each object blooms in
 * (scale + fade), then breathes and slowly spins while the line is on screen.
 */
@Composable
private fun CanvasDecorLayer(
    decor: List<DecorItem>,
    accent: Color,
    drift: Float,
) {
    // A shared "bloom" progress so all objects appear shortly after the line change.
    var bloom by remember(decor) { mutableStateOf(0f) }
    LaunchedEffect(decor) {
        bloom = 0f
        val steps = 30
        repeat(steps) {
            delay(24)
            bloom = (it + 1f) / steps
        }
        bloom = 1f
    }
    val bloomAnim by animateFloatAsState(bloom, tween(500), label = "decorBloom")

    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        decor.forEach { d ->
            val breathe = kotlin.math.sin(TWO_PI_CANVAS * d.driftSpeed * drift + d.driftPhase)
            val appear = (bloomAnim - d.appearDelayMs / 900f).coerceIn(0f, 1f)
            val eased = appear * appear * (3f - 2f * appear)
            Icon(
                painter = painterResource(d.res),
                contentDescription = null,
                tint = accent.copy(alpha = d.baseAlpha * eased),
                modifier = Modifier
                    .size(d.sizeDp.dp)
                    .align(Alignment.TopStart)
                    .padding(
                        start = (w - d.sizeDp.dp).coerceAtLeast(0.dp) * d.xFraction,
                        top = (h - d.sizeDp.dp).coerceAtLeast(0.dp) * d.yFraction,
                    )
                    .graphicsLayer {
                        val s = 0.7f + 0.3f * eased + 0.04f * breathe
                        scaleX = s
                        scaleY = s
                        rotationZ = d.rotation + d.spin * breathe
                        translationY = breathe * 10f
                    },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CanvasFallback(
    mediaMetadata: MediaMetadata,
    accent: Color,
    template: CanvasTemplate,
) {
    Column(horizontalAlignment = template.bias) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.canvas_bars),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(38.dp),
            )
            Spacer(Modifier.size(12.dp))
            Icon(
                painter = painterResource(R.drawable.canvas_note),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = mediaMetadata.title.uppercase(),
            style = TextStyle(
                fontSize = heroSizeFor(mediaMetadata.title.length).sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                lineHeight = heroSizeFor(mediaMetadata.title.length).sp,
                shadow = Shadow(color = accent.copy(alpha = 0.55f), blurRadius = 32f),
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = mediaMetadata.artists.joinToString(" · ") { it.name },
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}
