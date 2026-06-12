/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.arturo254.opentune.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.STATE_BUFFERING
import coil3.compose.AsyncImage
import com.arturo254.opentune.LocalPlayerConnection
import com.arturo254.opentune.R
import com.arturo254.opentune.constants.MiniPlayerHeight
import com.arturo254.opentune.constants.SwipeSensitivityKey
import com.arturo254.opentune.constants.ThumbnailCornerRadius
import com.arturo254.opentune.constants.UseNewMiniPlayerDesignKey
import com.arturo254.opentune.constants.MiniPlayerStyle
import com.arturo254.opentune.constants.MiniPlayerStyleKey
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import com.arturo254.opentune.constants.CropThumbnailToSquareKey
import com.arturo254.opentune.extensions.togglePlayPause
import com.arturo254.opentune.models.MediaMetadata
import com.arturo254.opentune.utils.rememberPreference
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import androidx.compose.ui.platform.LocalContext
import com.arturo254.opentune.constants.DynamicMiniPlayerColorKey
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.absoluteValue
import kotlin.math.roundToInt


@Composable
fun MiniPlayer(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
) {
    NewMiniPlayer(
        position = position,
        duration = duration,
        modifier = modifier,
        pureBlack = pureBlack
    )
}

@Composable
private fun NewMiniPlayer(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val layoutDirection = LocalLayoutDirection.current
    val coroutineScope = rememberCoroutineScope()
    val swipeSensitivity by rememberPreference(SwipeSensitivityKey, 0.73f)
    val swipeThumbnail by rememberPreference(com.arturo254.opentune.constants.SwipeThumbnailKey, true)

    val (savedStyle, setStyle) = rememberPreference(MiniPlayerStyleKey, defaultValue = MiniPlayerStyle.PILL.name)
    val miniPlayerStyle = remember(savedStyle) {
        try { MiniPlayerStyle.valueOf(savedStyle) } catch (e: Exception) { MiniPlayerStyle.PILL }
    }
    
    val (dynamicColorEnabled) = rememberPreference(DynamicMiniPlayerColorKey, defaultValue = false)
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    var paletteColor by remember { mutableStateOf<Color?>(null) }
    val context = LocalContext.current
    
    LaunchedEffect(mediaMetadata?.thumbnailUrl, dynamicColorEnabled) {
        if (!dynamicColorEnabled) {
            paletteColor = null
            return@LaunchedEffect
        }
        val url = mediaMetadata?.thumbnailUrl
        if (url != null) {
            withContext(Dispatchers.IO) {
                try {
                    val req = ImageRequest.Builder(context).data(url).allowHardware(false).build()
                    val loader = ImageLoader(context)
                    val res = loader.execute(req)
                    val bmp = res.image?.toBitmap()
                    if (bmp != null) {
                        val p = Palette.from(bmp).generate()
                        paletteColor = p.dominantSwatch?.rgb?.let { Color(it) } 
                            ?: p.mutedSwatch?.rgb?.let { Color(it) }
                    }
                } catch (_: Exception) {}
            }
        } else {
            paletteColor = null
        }
    }

    SwipeableMiniPlayerBox(
        modifier = modifier,
        swipeSensitivity = swipeSensitivity,
        swipeThumbnail = swipeThumbnail,
        playerConnection = playerConnection,
        layoutDirection = layoutDirection,
        coroutineScope = coroutineScope,
        pureBlack = pureBlack,
        useLegacyBackground = false
    ) { offsetX ->
        val shape = when (miniPlayerStyle) {
            MiniPlayerStyle.PILL -> RoundedCornerShape(32.dp)
            MiniPlayerStyle.CARD -> RoundedCornerShape(16.dp)
            MiniPlayerStyle.GLASS -> RoundedCornerShape(24.dp)
            MiniPlayerStyle.COMPACT -> RoundedCornerShape(8.dp)
        }
        val baseBackgroundColor = when (miniPlayerStyle) {
            MiniPlayerStyle.PILL -> MaterialTheme.colorScheme.surfaceContainer
            MiniPlayerStyle.CARD -> MaterialTheme.colorScheme.surfaceContainerHigh
            MiniPlayerStyle.GLASS -> MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
            MiniPlayerStyle.COMPACT -> Color.Transparent
        }
        val backgroundColor = if (dynamicColorEnabled && paletteColor != null) {
            if (miniPlayerStyle == MiniPlayerStyle.GLASS) paletteColor!!.copy(alpha = 0.4f)
            else if (miniPlayerStyle == MiniPlayerStyle.COMPACT) Color.Transparent
            else paletteColor!!
        } else {
            baseBackgroundColor
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .then(if (miniPlayerStyle == MiniPlayerStyle.CARD) Modifier.shadow(8.dp, shape) else Modifier)
                .clip(shape)
                .background(backgroundColor)
                .then(if (miniPlayerStyle == MiniPlayerStyle.GLASS) Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=0.2f), shape) else Modifier)
        ) {
            NewMiniPlayerContent(
                pureBlack = pureBlack,
                position = position,
                duration = duration,
                playerConnection = playerConnection,
                miniPlayerStyle = miniPlayerStyle,
                onStyleChange = { setStyle(it.name) }
            )
        }
    }
}