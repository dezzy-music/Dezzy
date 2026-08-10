@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)
/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.dezzy.music.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import androidx.media3.common.Player
import androidx.media3.common.Player.STATE_ENDED
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import me.saket.squiggles.SquigglySlider
import com.dezzy.music.R
import com.dezzy.music.constants.PlayerBackgroundStyle
import com.dezzy.music.constants.PlayerDesignStyle
import com.dezzy.music.constants.PlayerHorizontalPadding
import com.dezzy.music.constants.SliderStyle
import com.dezzy.music.db.entities.FormatEntity
import com.dezzy.music.extensions.togglePlayPause
import com.dezzy.music.extensions.toggleRepeatMode
import com.dezzy.music.models.MediaMetadata
import com.dezzy.music.playback.PlayerConnection
import com.dezzy.music.ui.component.BottomSheetPageState
import com.dezzy.music.ui.component.BottomSheetState
import com.dezzy.music.ui.component.MenuState
import com.dezzy.music.ui.component.PlayerSliderTrack
import com.dezzy.music.ui.component.ResizableIconButton
import com.dezzy.music.ui.menu.PlayerMenu
import com.dezzy.music.ui.theme.PlayerBackgroundColorUtils
import com.dezzy.music.ui.component.PlayerSliderColors
import com.dezzy.music.ui.utils.ShowMediaInfo
import com.dezzy.music.ui.utils.highRes
import com.dezzy.music.utils.makeTimeString

@Composable
fun PlayerTitleSection(
    mediaMetadata: MediaMetadata,
    textBackgroundColor: Color,
    navController: NavController,
    state: BottomSheetState,
    clipboardManager: ClipboardManager,
    context: Context
) {
    AnimatedContent(
        targetState = mediaMetadata.title,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "",
    ) { title ->
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = textBackgroundColor,
            modifier =
                Modifier
                    .basicMarquee()
                    .combinedClickable(
                        enabled = true,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {
                            if (mediaMetadata.album != null) {
                                state.snapTo(state.collapsedBound)
                                navController.navigate("album/${mediaMetadata.album.id}")
                            }
                        },
                        onLongClick = {
                            val clip = ClipData.newPlainText("Copied Title", title)
                            clipboardManager.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied Title", Toast.LENGTH_SHORT).show()
                        }
                    ),
        )
    }

    Spacer(Modifier.height(6.dp))

    val annotatedString = buildAnnotatedString {
        mediaMetadata.artists.forEachIndexed { index, artist ->
            val tag = "artist_${artist.id.orEmpty()}"
            pushStringAnnotation(tag = tag, annotation = artist.id.orEmpty())
            withStyle(SpanStyle(color = textBackgroundColor, fontSize = 16.sp)) {
                append(artist.name)
            }
            pop()
            if (index != mediaMetadata.artists.lastIndex) append(", ")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .basicMarquee()
            .padding(end = 12.dp)
    ) {
        var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
        var clickOffset by remember { mutableStateOf<Offset?>(null) }
        Text(
            text = annotatedString,
            style = MaterialTheme.typography.titleMedium.copy(color = textBackgroundColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layoutResult = it },
            modifier = Modifier
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val tapPosition = event.changes.firstOrNull()?.position
                            if (tapPosition != null) {
                                clickOffset = tapPosition
                            }
                        }
                    }
                }
                .combinedClickable(
                    enabled = true,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {
                        val tapPosition = clickOffset
                        val layout = layoutResult
                        if (tapPosition != null && layout != null) {
                            val offset = layout.getOffsetForPosition(tapPosition)
                            annotatedString.getStringAnnotations(offset, offset)
                                .firstOrNull()
                                ?.let { ann ->
                                    val artistId = ann.item
                                    if (artistId.isNotBlank()) {
                                        navController.navigate("artist/$artistId")
                                        state.collapseSoft()
                                    }
                                }
                        }
                    },
                    onLongClick = {
                        val clip = ClipData.newPlainText("Copied Artist", annotatedString)
                        clipboardManager.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied Artist", Toast.LENGTH_SHORT).show()
                    }
                )
        )
    }
}

@Composable
fun PlayerTopActions(
    mediaMetadata: MediaMetadata,
    playerDesignStyle: PlayerDesignStyle,
    textButtonColor: Color,
    iconButtonColor: Color,
    textBackgroundColor: Color,
    playerConnection: PlayerConnection,
    navController: NavController,
    menuState: MenuState,
    state: BottomSheetState,
    bottomSheetPageState: BottomSheetPageState,
    context: Context,
    currentSongLiked: Boolean,
    onVisualizerClick: (() -> Unit)? = null
) {
    var showShareDialog by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    if (showShareDialog) {
        com.dezzy.music.ui.component.ShareBottomSheet(
            title = mediaMetadata.title,
            subtitle = mediaMetadata.artists.joinToString { it.name },
            thumbnailUrl = mediaMetadata.thumbnailUrl,
            shareLink = "https://music.youtube.com/watch?v=${mediaMetadata.id}",
            hasVideo = false,
            mediaId = mediaMetadata.id,
            onDismiss = { showShareDialog = false }
        )
    }

    when (playerDesignStyle) {
        PlayerDesignStyle.V2 -> {
            val shareShape = RoundedCornerShape(
                topStart = 50.dp, bottomStart = 50.dp,
                topEnd = 10.dp, bottomEnd = 10.dp
            )

            val favShape = RoundedCornerShape(
                topStart = 10.dp, bottomStart = 10.dp,
                topEnd = 50.dp, bottomEnd = 50.dp
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onVisualizerClick != null) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(textButtonColor)
                            .clickable { onVisualizerClick() }
                    ) {
                        Image(
                            painter = painterResource(R.drawable.auto_awesome),
                            contentDescription = "Visualizador",
                            colorFilter = ColorFilter.tint(iconButtonColor),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(22.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(shareShape)
                        .background(textButtonColor)
                        .clickable { showShareDialog = true }
                ) {
                    Image(
                        painter = painterResource(R.drawable.share),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(iconButtonColor),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(24.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(favShape)
                        .background(textButtonColor)
                        .clickable {
                            playerConnection.toggleLike()
                        }
                ) {
                    Image(
                        painter = painterResource(
                            if (currentSongLiked)
                                R.drawable.favorite
                            else R.drawable.favorite_border
                        ),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(iconButtonColor),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(24.dp)
                    )
                }
            }
        }

        PlayerDesignStyle.V3, PlayerDesignStyle.V5 -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onVisualizerClick != null) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onVisualizerClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.auto_awesome),
                            contentDescription = "Visualizador",
                            tint = textBackgroundColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showShareDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.share),
                        contentDescription = null,
                        tint = textBackgroundColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { playerConnection.toggleLike() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(
                            if (currentSongLiked) R.drawable.favorite
                            else R.drawable.favorite_border
                        ),
                        contentDescription = null,
                        tint = if (currentSongLiked)
                            MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                        else textBackgroundColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        PlayerDesignStyle.V4 -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onVisualizerClick != null) {
                    Surface(
                        onClick = { onVisualizerClick() },
                        shape = RoundedCornerShape(14.dp),
                        color = textBackgroundColor.copy(alpha = 0.12f),
                        modifier = Modifier
                            .height(44.dp)
                            .width(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                painter = painterResource(R.drawable.auto_awesome),
                                contentDescription = "Visualizador",
                                tint = textBackgroundColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Surface(
                    onClick = { showShareDialog = true },
                    shape = RoundedCornerShape(14.dp),
                    color = textBackgroundColor.copy(alpha = 0.12f),
                    modifier = Modifier
                        .height(44.dp)
                        .width(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter = painterResource(R.drawable.share),
                            contentDescription = null,
                            tint = textBackgroundColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Surface(
                    onClick = { playerConnection.toggleLike() },
                    shape = RoundedCornerShape(14.dp),
                    color = if (currentSongLiked)
                        MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
                    else textBackgroundColor.copy(alpha = 0.12f),
                    modifier = Modifier
                        .height(44.dp)
                        .width(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter = painterResource(
                                if (currentSongLiked) R.drawable.favorite
                                else R.drawable.favorite_border
                            ),
                            contentDescription = null,
                            tint = if (currentSongLiked)
                                MaterialTheme.colorScheme.error
                            else textBackgroundColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // More menu button - cinematic glass card
                Surface(
                    onClick = {
                        menuState.show {
                            PlayerMenu(
                                mediaMetadata = mediaMetadata,
                                navController = navController,
                                playerBottomSheetState = state,
                                onShowDetailsDialog = {
                                    mediaMetadata.id.let {
                                        bottomSheetPageState.show {
                                            ShowMediaInfo(it)
                                        }
                                    }
                                },
                                onDismiss = menuState::dismiss,
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = textBackgroundColor.copy(alpha = 0.12f),
                    modifier = Modifier
                        .height(44.dp)
                        .width(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter = painterResource(R.drawable.more_horiz),
                            contentDescription = null,
                            tint = textBackgroundColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        PlayerDesignStyle.V1 -> {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(textButtonColor)
                        .clickable { showShareDialog = true },
            ) {
                Image(
                    painter = painterResource(R.drawable.share),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(iconButtonColor),
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .size(24.dp),
                )
            }

            Spacer(modifier = Modifier.size(12.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(textButtonColor)
                        .clickable {
                            menuState.show {
                                PlayerMenu(
                                    mediaMetadata = mediaMetadata,
                                    navController = navController,
                                    playerBottomSheetState = state,
                                    onShowDetailsDialog = {
                                        mediaMetadata.id.let {
                                            bottomSheetPageState.show {
                                                ShowMediaInfo(it)
                                            }
                                        }
                                    },
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        },
            ) {
                Image(
                    painter = painterResource(R.drawable.more_horiz),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(iconButtonColor),
                )
            }
        }

        PlayerDesignStyle.V6 -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = { showShareDialog = true },
                    shape = RoundedCornerShape(
                        topStart = 50.dp, bottomStart = 50.dp,
                        topEnd = 6.dp, bottomEnd = 6.dp
                    ),
                    color = textBackgroundColor.copy(alpha = 0.12f),
                    modifier = Modifier
                        .height(42.dp)
                        .width(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter = painterResource(R.drawable.share),
                            contentDescription = null,
                            tint = textBackgroundColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Surface(
                    onClick = { playerConnection.toggleLike() },
                    shape = RoundedCornerShape(50),
                    color = if (currentSongLiked)
                        MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
                    else textBackgroundColor.copy(alpha = 0.12f),
                    modifier = Modifier
                        .height(42.dp)
                        .width(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter = painterResource(
                                if (currentSongLiked) R.drawable.favorite
                                else R.drawable.favorite_border
                            ),
                            contentDescription = null,
                            tint = if (currentSongLiked)
                                MaterialTheme.colorScheme.error
                            else textBackgroundColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Surface(
                    onClick = {
                        menuState.show {
                            PlayerMenu(
                                mediaMetadata = mediaMetadata,
                                navController = navController,
                                playerBottomSheetState = state,
                                onShowDetailsDialog = {
                                    mediaMetadata.id.let {
                                        bottomSheetPageState.show {
                                            ShowMediaInfo(it)
                                        }
                                    }
                                },
                                onDismiss = menuState::dismiss,
                            )
                        }
                    },
                    shape = RoundedCornerShape(
                        topStart = 6.dp, bottomStart = 6.dp,
                        topEnd = 50.dp, bottomEnd = 50.dp
                    ),
                    color = textBackgroundColor.copy(alpha = 0.12f),
                    modifier = Modifier
                        .height(42.dp)
                        .width(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter = painterResource(R.drawable.more_horiz),
                            contentDescription = null,
                            tint = textBackgroundColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        PlayerDesignStyle.V7 -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (currentSongLiked) textBackgroundColor.copy(alpha = 0.2f)
                            else Color.Transparent
                        )
                        .clickable { playerConnection.toggleLike() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(
                            if (currentSongLiked) R.drawable.favorite
                            else R.drawable.favorite_border
                        ),
                        contentDescription = null,
                        tint = textBackgroundColor.copy(alpha = if (currentSongLiked) 1f else 0.7f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .clickable {
                            menuState.show {
                                PlayerMenu(
                                    mediaMetadata = mediaMetadata,
                                    navController = navController,
                                    playerBottomSheetState = state,
                                    onShowDetailsDialog = {
                                        bottomSheetPageState.show {
                                            ShowMediaInfo(mediaMetadata.id)
                                        }
                                    },
                                    onDismiss = menuState::dismiss
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.more_horiz),
                        contentDescription = null,
                        tint = textBackgroundColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        else -> {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSlider(
    sliderStyle: SliderStyle,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    textButtonColor: Color,
    onValueChange: (Long) -> Unit,
    onValueChangeFinished: () -> Unit,
    songKey: String = "",
) {
    val safeDuration = if (duration <= 0L) 0f else duration.toFloat()
    val safeValue = (sliderPosition ?: position).toFloat().coerceIn(0f, maxOf(0f, safeDuration))

    StyledPlaybackSlider(
        sliderStyle = sliderStyle,
        value = safeValue,
        valueRange = 0f..maxOf(1f, safeDuration),
        onValueChange = { onValueChange(it.toLong()) },
        onValueChangeFinished = onValueChangeFinished,
        activeColor = textButtonColor,
        isPlaying = isPlaying,
        songKey = songKey,
        modifier = Modifier.padding(horizontal = PlayerHorizontalPadding)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyledPlaybackSlider(
    sliderStyle: SliderStyle,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    activeColor: Color,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    songKey: String = "",
) {
    when (sliderStyle) {
        SliderStyle.Standard -> {
            Slider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = PlayerSliderColors.standardSliderColors(activeColor),
                modifier = modifier
            )
        }

        SliderStyle.Wavy -> {
            SquigglySlider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = PlayerSliderColors.wavySliderColors(activeColor),
                modifier = modifier,
                squigglesSpec = SquigglySlider.SquigglesSpec(
                    amplitude = if (isPlaying) 2.dp else 0.dp,
                    strokeWidth = 6.dp
                )
            )
        }

        SliderStyle.Thick -> {
            Slider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = PlayerSliderColors.thickSliderColors(activeColor),
                thumb = { Spacer(modifier = Modifier.size(0.dp)) },
                track = { sliderState ->
                    PlayerSliderTrack(
                        sliderState = sliderState,
                        colors = PlayerSliderColors.thickSliderColors(activeColor),
                        trackHeight = 12.dp
                    )
                },
                modifier = modifier
            )
        }

        SliderStyle.Circular -> {
            SquigglySlider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = PlayerSliderColors.circularSliderColors(activeColor),
                modifier = modifier,
                squigglesSpec = SquigglySlider.SquigglesSpec(
                    amplitude = if (isPlaying) 2.dp else 0.dp,
                    strokeWidth = 6.dp
                )
            )
        }

        SliderStyle.Simple -> {
            Slider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = PlayerSliderColors.simpleSliderColors(activeColor),
                thumb = { Spacer(modifier = Modifier.size(0.dp)) },
                track = { sliderState ->
                    PlayerSliderTrack(
                        sliderState = sliderState,
                        colors = PlayerSliderColors.simpleSliderColors(activeColor),
                        trackHeight = 3.dp
                    )
                },
                modifier = modifier
            )
        }

        SliderStyle.NeonGlow -> {
            // Dynamic per song: pulse cadence is seeded from the song id, and the tube color
            // is the album-art accent (activeColor) that already changes with every track.
            val seed = remember(songKey) { songKey.hashCode() }
            val pulseDurationMs = remember(seed) { 850 + abs(seed % 900) }
            val neonTransition = rememberInfiniteTransition(label = "neonGlow")
            val pulseRaw by neonTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(pulseDurationMs, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "neonPulse",
            )
            val shimmerRaw by neonTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2400, easing = LinearEasing),
                ),
                label = "neonShimmer",
            )
            val pulse = if (isPlaying) 0.55f + 0.45f * pulseRaw else 0.55f
            val shimmer = if (isPlaying) shimmerRaw else -1f

            Slider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    thumbColor = Color.Transparent
                ),
                thumb = { Spacer(modifier = Modifier.size(0.dp)) },
                track = { sliderState ->
                    val valRange = sliderState.valueRange
                    val fraction = ((sliderState.value - valRange.start) / (valRange.endInclusive - valRange.start)).coerceIn(0f, 1f)
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                    ) {
                        val isRtl = layoutDirection == LayoutDirection.Rtl
                        val sliderLeft = Offset(0f, center.y)
                        val sliderRight = Offset(size.width, center.y)
                        val sliderStart = if (isRtl) sliderRight else sliderLeft
                        val sliderEnd = if (isRtl) sliderLeft else sliderRight
                        val sliderValueEnd = Offset(
                            x = sliderStart.x + (sliderEnd.x - sliderStart.x) * fraction,
                            y = center.y
                        )

                        // Unlit tube: thin base line + the faintest hint of the neon color,
                        // like a switched-off neon sign.
                        drawLine(
                            color = Color.White.copy(alpha = 0.10f),
                            start = sliderStart,
                            end = sliderEnd,
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawGlowLine(
                            color = activeColor.copy(alpha = 0.07f),
                            start = sliderStart,
                            end = sliderEnd,
                            strokeWidthPx = 4.dp.toPx(),
                            blurRadiusPx = 6.dp.toPx(),
                        )

                        if (fraction > 0f) {
                            // Lit tube: wide soft halo -> tighter halo -> saturated core ->
                            // white-hot filament. The halos breathe with the pulse.
                            drawGlowLine(
                                color = activeColor.copy(alpha = 0.22f * pulse),
                                start = sliderStart,
                                end = sliderValueEnd,
                                strokeWidthPx = 10.dp.toPx(),
                                blurRadiusPx = 16.dp.toPx() * pulse,
                            )
                            drawGlowLine(
                                color = activeColor.copy(alpha = 0.50f * pulse),
                                start = sliderStart,
                                end = sliderValueEnd,
                                strokeWidthPx = 5.dp.toPx(),
                                blurRadiusPx = 7.dp.toPx() * pulse,
                            )
                            drawLine(
                                color = activeColor,
                                start = sliderStart,
                                end = sliderValueEnd,
                                strokeWidth = 3.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = lerp(activeColor, Color.White, 0.72f),
                                start = sliderStart,
                                end = sliderValueEnd,
                                strokeWidth = 1.2.dp.toPx(),
                                cap = StrokeCap.Round
                            )

                            // Energy spark travelling down the lit tube while music plays.
                            if (shimmer >= 0f) {
                                val sparkX = sliderStart.x + (sliderValueEnd.x - sliderStart.x) * shimmer
                                drawGlowCircle(
                                    color = lerp(activeColor, Color.White, 0.8f).copy(alpha = 0.65f),
                                    radiusPx = 2.5.dp.toPx(),
                                    center = Offset(sparkX, center.y),
                                    blurRadiusPx = 6.dp.toPx(),
                                )
                            }
                        }

                        // Glowing playhead orb (replaces the thumb).
                        drawGlowCircle(
                            color = activeColor.copy(alpha = 0.55f * pulse + 0.2f),
                            radiusPx = (5.dp.toPx() + 2.dp.toPx() * pulse),
                            center = sliderValueEnd,
                            blurRadiusPx = 10.dp.toPx(),
                        )
                        drawCircle(
                            color = lerp(activeColor, Color.White, 0.85f),
                            radius = 3.dp.toPx(),
                            center = sliderValueEnd,
                        )
                    }
                },
                modifier = modifier
            )
        }

        SliderStyle.ShootingStar -> {
            // Per-song sky: star positions, twinkle phases and comet cadence are all seeded
            // from the song id, so every track gets its own constellation.
            val seed = remember(songKey) { songKey.hashCode() }
            val stars = remember(seed) {
                val rnd = java.util.Random(seed.toLong())
                List(24) {
                    StarSpec(
                        x = rnd.nextFloat(),
                        yOffset = (rnd.nextFloat() - 0.5f) * 2f,
                        phase = rnd.nextFloat() * TWO_PI,
                        twinkleSpeed = 0.6f + rnd.nextFloat() * 1.6f,
                        size = 0.5f + rnd.nextFloat(),
                    )
                }
            }
            val tailSparks = remember(seed) {
                val rnd = java.util.Random(seed.toLong() * 31 + 7)
                List(12) {
                    StarSpec(
                        x = rnd.nextFloat().pow(1.4f),          // clustered near the head
                        yOffset = (rnd.nextFloat() - 0.5f) * 2f,
                        phase = rnd.nextFloat() * TWO_PI,
                        twinkleSpeed = 1f + rnd.nextFloat() * 2f,
                        size = 0.4f + rnd.nextFloat() * 0.8f,
                    )
                }
            }
            val starTransition = rememberInfiniteTransition(label = "shootingStar")
            val timeRaw by starTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(6000, easing = LinearEasing),
                ),
                label = "starTime",
            )
            val time = if (isPlaying) timeRaw else 0.31f

            Slider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    thumbColor = Color.Transparent
                ),
                thumb = { Spacer(modifier = Modifier.size(0.dp)) },
                track = { sliderState ->
                    val valRange = sliderState.valueRange
                    val fraction = ((sliderState.value - valRange.start) / (valRange.endInclusive - valRange.start)).coerceIn(0f, 1f)
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                    ) {
                        drawShootingStarTrack(
                            fraction = fraction,
                            activeColor = activeColor,
                            time = time,
                            isPlaying = isPlaying,
                            stars = stars,
                            tailSparks = tailSparks,
                            isRtl = layoutDirection == LayoutDirection.Rtl,
                        )
                    }
                },
                modifier = modifier
            )
        }

        SliderStyle.Aurora -> {
            // Aurora borealis: three translucent ribbons whose hues orbit the album accent
            // color; waveforms and drift speeds are seeded per song.
            val seed = remember(songKey) { songKey.hashCode() }
            val ribbons = remember(seed, activeColor) {
                val rnd = java.util.Random(seed.toLong())
                listOf(-38f, 0f, 34f).map { hueShift ->
                    RibbonSpec(
                        color = shiftHue(activeColor, hueShift),
                        amplitude = 0.55f + rnd.nextFloat() * 0.45f,
                        wavelength = 0.55f + rnd.nextFloat() * 0.75f,
                        speed = if (rnd.nextBoolean()) 1f + rnd.nextFloat() else -(1f + rnd.nextFloat()),
                        phase = rnd.nextFloat() * TWO_PI,
                    )
                }
            }
            val auroraTransition = rememberInfiniteTransition(label = "aurora")
            val timeRaw by auroraTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(7000, easing = LinearEasing),
                ),
                label = "auroraTime",
            )
            val time = if (isPlaying) timeRaw else 0.18f
            val auroraPath = remember { androidx.compose.ui.graphics.Path() }

            Slider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    thumbColor = Color.Transparent
                ),
                thumb = { Spacer(modifier = Modifier.size(0.dp)) },
                track = { sliderState ->
                    val valRange = sliderState.valueRange
                    val fraction = ((sliderState.value - valRange.start) / (valRange.endInclusive - valRange.start)).coerceIn(0f, 1f)
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                    ) {
                        drawAuroraTrack(
                            fraction = fraction,
                            activeColor = activeColor,
                            time = time,
                            ribbons = ribbons,
                            path = auroraPath,
                            isRtl = layoutDirection == LayoutDirection.Rtl,
                        )
                    }
                },
                modifier = modifier
            )
        }

        SliderStyle.Capsule -> {
            Slider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = SliderDefaults.colors(
                    activeTrackColor = activeColor,
                    inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                    thumbColor = activeColor
                ),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(width = 8.dp, height = 18.dp)
                            .background(activeColor, RoundedCornerShape(4.dp))
                    )
                },
                track = { sliderState ->
                    PlayerSliderTrack(
                        sliderState = sliderState,
                        colors = PlayerSliderColors.thickSliderColors(activeColor),
                        trackHeight = 12.dp
                    )
                },
                modifier = modifier
            )
        }

        SliderStyle.DoubleLine -> {
            Slider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = SliderDefaults.colors(
                    activeTrackColor = activeColor,
                    inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                    thumbColor = activeColor
                ),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(width = 4.dp, height = 16.dp)
                            .background(activeColor, RoundedCornerShape(2.dp))
                    )
                },
                track = { sliderState ->
                    val valRange = sliderState.valueRange
                    val fraction = ((sliderState.value - valRange.start) / (valRange.endInclusive - valRange.start)).coerceIn(0f, 1f)
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                    ) {
                        val isRtl = layoutDirection == LayoutDirection.Rtl
                        val sliderLeft = Offset(0f, center.y)
                        val sliderRight = Offset(size.width, center.y)
                        val sliderStart = if (isRtl) sliderRight else sliderLeft
                        val sliderEnd = if (isRtl) sliderLeft else sliderRight
                        
                        val sliderValueEnd = Offset(
                            x = sliderStart.x + (sliderEnd.x - sliderStart.x) * fraction,
                            y = center.y
                        )

                        val offset1 = 3.dp.toPx()

                        drawLine(
                            color = Color.White.copy(alpha = 0.15f),
                            start = Offset(sliderStart.x, center.y - offset1),
                            end = Offset(sliderEnd.x, center.y - offset1),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = Color.White.copy(alpha = 0.15f),
                            start = Offset(sliderStart.x, center.y + offset1),
                            end = Offset(sliderEnd.x, center.y + offset1),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )

                        drawLine(
                            color = activeColor,
                            start = Offset(sliderStart.x, center.y - offset1),
                            end = Offset(sliderValueEnd.x, center.y - offset1),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = activeColor,
                            start = Offset(sliderStart.x, center.y + offset1),
                            end = Offset(sliderValueEnd.x, center.y + offset1),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                },
                modifier = modifier
            )
        }

        SliderStyle.Dotted -> {
            Slider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = SliderDefaults.colors(
                    activeTrackColor = activeColor,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                    thumbColor = activeColor
                ),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(activeColor, CircleShape)
                    )
                },
                track = { sliderState ->
                    val valRange = sliderState.valueRange
                    val fraction = ((sliderState.value - valRange.start) / (valRange.endInclusive - valRange.start)).coerceIn(0f, 1f)
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                    ) {
                        val dotsCount = 20
                        val stepX = size.width / (dotsCount - 1)
                        for (i in 0 until dotsCount) {
                            val dotFraction = i.toFloat() / (dotsCount - 1)
                            val dotX = i * stepX
                            val isFilled = dotFraction <= fraction
                            drawCircle(
                                color = if (isFilled) activeColor else Color.White.copy(alpha = 0.25f),
                                radius = (if (isFilled) 4.dp else 3.dp).toPx(),
                                center = Offset(dotX, center.y)
                            )
                        }
                    }
                },
                modifier = modifier
            )
        }

        SliderStyle.Waveform -> {
            Slider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = SliderDefaults.colors(
                    activeTrackColor = activeColor,
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                    thumbColor = Color.Transparent
                ),
                thumb = { Spacer(modifier = Modifier.size(0.dp)) },
                track = { sliderState ->
                    val valRange = sliderState.valueRange
                    val fraction = ((sliderState.value - valRange.start) / (valRange.endInclusive - valRange.start)).coerceIn(0f, 1f)
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                    ) {
                        val barCount = 35
                        val barWidth = 3.dp.toPx()
                        val gap = 2.dp.toPx()
                        val totalBarWidth = barWidth + gap
                        val startX = (size.width - (barCount * totalBarWidth - gap)) / 2
                        
                        for (i in 0 until barCount) {
                            val waveFactor = kotlin.math.sin(i.toFloat() * 0.3f) * 0.4f + 0.6f
                            val barHeight = (size.height * waveFactor * 0.8f).coerceAtLeast(4.dp.toPx())
                            val x = startX + i * totalBarWidth
                            val barFraction = i.toFloat() / (barCount - 1)
                            val isFilled = barFraction <= fraction
                            
                            drawLine(
                                color = if (isFilled) activeColor else Color.White.copy(alpha = 0.2f),
                                start = Offset(x, center.y - barHeight / 2),
                                end = Offset(x, center.y + barHeight / 2),
                                strokeWidth = barWidth,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                },
                modifier = modifier
            )
        }
    }
}

private const val TWO_PI = (Math.PI * 2).toFloat()

/** Static per-particle parameters, pre-seeded per song so the sky is unique per track. */
private data class StarSpec(
    val x: Float,            // 0..1 along the track
    val yOffset: Float,      // -1..1 vertical scatter
    val phase: Float,        // twinkle phase offset
    val twinkleSpeed: Float, // twinkle cycles per animation loop
    val size: Float,         // relative size multiplier
)

/** Static per-ribbon parameters for the Aurora slider. */
private data class RibbonSpec(
    val color: Color,
    val amplitude: Float,  // relative (multiplied by dp base)
    val wavelength: Float, // relative to track width
    val speed: Float,      // drift cycles per loop (sign = direction)
    val phase: Float,
)

/** Rotates the hue of [color] by [degrees], keeping saturation/value. */
private fun shiftHue(color: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * True blurred glow line — Compose has no shadow for strokes, so this drops to the
 * framework canvas and uses a [android.graphics.BlurMaskFilter].
 */
private fun DrawScope.drawGlowLine(
    color: Color,
    start: Offset,
    end: Offset,
    strokeWidthPx: Float,
    blurRadiusPx: Float,
) {
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeWidth = strokeWidthPx
            this.color = color.toArgb()
            if (blurRadiusPx > 0.5f) {
                maskFilter = android.graphics.BlurMaskFilter(
                    blurRadiusPx,
                    android.graphics.BlurMaskFilter.Blur.NORMAL,
                )
            }
        }
        canvas.nativeCanvas.drawLine(start.x, start.y, end.x, end.y, paint)
    }
}

/** True blurred glow dot, same technique as [drawGlowLine]. */
private fun DrawScope.drawGlowCircle(
    color: Color,
    radiusPx: Float,
    center: Offset,
    blurRadiusPx: Float,
) {
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
            this.color = color.toArgb()
            if (blurRadiusPx > 0.5f) {
                maskFilter = android.graphics.BlurMaskFilter(
                    blurRadiusPx,
                    android.graphics.BlurMaskFilter.Blur.NORMAL,
                )
            }
        }
        canvas.nativeCanvas.drawCircle(center.x, center.y, radiusPx, paint)
    }
}

/** Comet + starfield track for [SliderStyle.ShootingStar]. */
private fun DrawScope.drawShootingStarTrack(
    fraction: Float,
    activeColor: Color,
    time: Float,
    isPlaying: Boolean,
    stars: List<StarSpec>,
    tailSparks: List<StarSpec>,
    isRtl: Boolean,
) {
    val sliderLeft = Offset(0f, center.y)
    val sliderRight = Offset(size.width, center.y)
    val sliderStart = if (isRtl) sliderRight else sliderLeft
    val sliderEnd = if (isRtl) sliderLeft else sliderRight
    val head = Offset(
        x = sliderStart.x + (sliderEnd.x - sliderStart.x) * fraction,
        y = center.y,
    )
    val dir = if (isRtl) -1f else 1f
    val starColor = lerp(activeColor, Color.White, 0.75f)

    // Faint horizon line across the whole track.
    drawLine(
        color = Color.White.copy(alpha = 0.10f),
        start = sliderStart,
        end = sliderEnd,
        strokeWidth = 1.5.dp.toPx(),
        cap = StrokeCap.Round,
    )

    // Twinkling constellation over the part of the sky not yet travelled.
    for (star in stars) {
        if (star.x <= fraction) continue
        val twinkle = 0.5f + 0.5f * sin(TWO_PI * star.twinkleSpeed * time + star.phase)
        val alpha = 0.10f + 0.45f * twinkle
        drawCircle(
            color = starColor.copy(alpha = alpha),
            radius = (0.8f + star.size) * 1.dp.toPx() * (0.75f + 0.25f * twinkle),
            center = Offset(
                x = sliderStart.x + (sliderEnd.x - sliderStart.x) * star.x,
                y = center.y + star.yOffset * 8.dp.toPx(),
            ),
        )
    }

    if (fraction > 0.001f) {
        // Comet tail: gradient trail that brightens toward the head, with a soft glow.
        val tailBrush = Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to activeColor.copy(alpha = 0f),
                0.55f to activeColor.copy(alpha = 0.35f),
                1f to activeColor.copy(alpha = 0.95f),
            ),
            startX = sliderStart.x,
            endX = head.x,
        )
        drawGlowLine(
            color = activeColor.copy(alpha = 0.30f),
            start = sliderStart,
            end = head,
            strokeWidthPx = 6.dp.toPx(),
            blurRadiusPx = 9.dp.toPx(),
        )
        drawLine(
            brush = tailBrush,
            start = sliderStart,
            end = head,
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // Ember sparks tumbling in the comet's wake.
        val tailLen = (head.x - sliderStart.x) * dir
        val maxSparkSpan = minOf(tailLen, size.width * 0.35f)
        if (maxSparkSpan > 4.dp.toPx()) {
            for (spark in tailSparks) {
                val dist = spark.x * maxSparkSpan
                val fade = 1f - dist / maxSparkSpan
                val wobble = sin(TWO_PI * spark.twinkleSpeed * time + spark.phase)
                drawCircle(
                    color = lerp(activeColor, Color.White, 0.5f + 0.3f * wobble)
                        .copy(alpha = (0.15f + 0.55f * fade) * (0.6f + 0.4f * wobble)),
                    radius = spark.size * 1.6.dp.toPx() * (0.5f + 0.5f * fade),
                    center = Offset(
                        x = head.x - dir * dist,
                        y = center.y + spark.yOffset * 6.dp.toPx() * (1f - fade * 0.4f) * (0.7f + 0.3f * wobble),
                    ),
                )
            }
        }

        // A recurring meteor racing along the tail toward the head while playing.
        if (isPlaying && tailLen > 12.dp.toPx()) {
            val meteorFraction = (time * 2.1f) % 1f
            val meteorX = head.x - dir * tailLen * (1f - meteorFraction)
            drawGlowCircle(
                color = Color.White.copy(alpha = 0.55f * meteorFraction),
                radiusPx = 2.dp.toPx(),
                center = Offset(meteorX, center.y),
                blurRadiusPx = 5.dp.toPx(),
            )
        }
    }

    // The star itself: blurred halo + white-hot core + rotating 4-point flare.
    val pulse = if (isPlaying) 0.8f + 0.2f * sin(TWO_PI * 2.3f * time) else 0.85f
    drawGlowCircle(
        color = activeColor.copy(alpha = 0.65f * pulse),
        radiusPx = 6.dp.toPx() * pulse,
        center = head,
        blurRadiusPx = 12.dp.toPx(),
    )
    val flareAngle = if (isPlaying) sin(TWO_PI * time) * 0.35f else 0f
    val flareLen = 8.dp.toPx() * pulse
    val flareShort = flareLen * 0.55f
    for (i in 0 until 4) {
        val angle = flareAngle + i * (TWO_PI / 4f)
        val len = if (i % 2 == 0) flareLen else flareShort
        drawLine(
            color = Color.White.copy(alpha = 0.85f * pulse),
            start = head,
            end = Offset(head.x + cos(angle) * len, head.y + sin(angle) * len),
            strokeWidth = 1.4.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
    drawCircle(
        color = Color.White,
        radius = 2.8.dp.toPx(),
        center = head,
    )
}

/** Flowing northern-lights track for [SliderStyle.Aurora]. */
private fun DrawScope.drawAuroraTrack(
    fraction: Float,
    activeColor: Color,
    time: Float,
    ribbons: List<RibbonSpec>,
    path: androidx.compose.ui.graphics.Path,
    isRtl: Boolean,
) {
    val sliderLeft = Offset(0f, center.y)
    val sliderRight = Offset(size.width, center.y)
    val sliderStart = if (isRtl) sliderRight else sliderLeft
    val sliderEnd = if (isRtl) sliderLeft else sliderRight
    val head = Offset(
        x = sliderStart.x + (sliderEnd.x - sliderStart.x) * fraction,
        y = center.y,
    )

    // Night-sky base line.
    drawLine(
        color = Color.White.copy(alpha = 0.12f),
        start = sliderStart,
        end = sliderEnd,
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round,
    )

    if (fraction > 0.001f) {
        val ampBase = 5.dp.toPx()
        val strokePx = 2.6.dp.toPx()
        val sampleStep = 6.dp.toPx()
        val travelled = abs(head.x - sliderStart.x)
        val samples = (travelled / sampleStep).toInt().coerceIn(2, 96)

        for (ribbon in ribbons) {
            path.reset()
            for (s in 0..samples) {
                val f = s.toFloat() / samples
                val x = sliderStart.x + (head.x - sliderStart.x) * f
                // Waves taper at both ends so the ribbon melts into start and playhead.
                val taper = (sin(f * Math.PI).toFloat()).pow(0.7f)
                val y = center.y + taper * ampBase * ribbon.amplitude *
                    sin(TWO_PI * (f * size.width / (size.width * ribbon.wavelength)) +
                        ribbon.phase + TWO_PI * ribbon.speed * time)
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            // Soft glow pass under the crisp ribbon.
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    style = android.graphics.Paint.Style.STROKE
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeJoin = android.graphics.Paint.Join.ROUND
                    strokeWidth = strokePx * 2.4f
                    this.color = ribbon.color.copy(alpha = 0.20f).toArgb()
                    maskFilter = android.graphics.BlurMaskFilter(
                        6.dp.toPx(),
                        android.graphics.BlurMaskFilter.Blur.NORMAL,
                    )
                }
                canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
            }
            drawPath(
                path = path,
                color = ribbon.color.copy(alpha = 0.55f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = strokePx,
                    cap = StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
        }
    }

    // Playhead: cool moonlit orb with halo.
    drawGlowCircle(
        color = lerp(activeColor, Color.White, 0.4f).copy(alpha = 0.6f),
        radiusPx = 5.dp.toPx(),
        center = head,
        blurRadiusPx = 9.dp.toPx(),
    )
    drawCircle(
        color = lerp(activeColor, Color.White, 0.9f),
        radius = 2.6.dp.toPx(),
        center = head,
    )
}

@Composable
fun PlayerTimeLabel(
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    textBackgroundColor: Color,
    showRemainingTime: Boolean = false,
    centerContent: @Composable (() -> Unit)? = null,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PlayerHorizontalPadding + 4.dp),
    ) {
        Text(
            text = makeTimeString(sliderPosition ?: position),
            style = MaterialTheme.typography.labelMedium,
            color = textBackgroundColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart),
        )

        if (centerContent != null) {
            Box(
                modifier = Modifier.align(Alignment.Center),
                contentAlignment = Alignment.Center,
            ) {
                centerContent()
            }
        }

        Text(
            text = if (duration != C.TIME_UNSET) {
                if (showRemainingTime) {
                    val remaining = duration - (sliderPosition ?: position)
                    "-${makeTimeString(remaining.coerceAtLeast(0))}"
                } else {
                    makeTimeString(duration)
                }
            } else "",
            style = MaterialTheme.typography.labelMedium,
            color = textBackgroundColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
fun PlayerPlaybackControls(
    playerDesignStyle: PlayerDesignStyle,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    repeatMode: Int,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    textButtonColor: Color,
    iconButtonColor: Color,
    textBackgroundColor: Color,
    icBackgroundColor: Color,
    playPauseRoundness: androidx.compose.ui.unit.Dp,
    playerConnection: PlayerConnection,
    currentSongLiked: Boolean
) {
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()

    when (playerDesignStyle) {
        PlayerDesignStyle.V2 -> {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth()
            ) {
                val maxW = maxWidth
                val playButtonHeight = maxW / 6f
                val playButtonWidth = playButtonHeight * 1.6f
                val sideButtonHeight = playButtonHeight * 0.8f
                val sideButtonWidth = sideButtonHeight * 1.3f

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {

                    FilledTonalIconButton(
                        onClick = playerConnection::seekToPrevious,
                        enabled = canSkipPrevious,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = textButtonColor,
                            contentColor = iconButtonColor
                        ),
                        modifier = Modifier
                            .size(width = sideButtonWidth, height = sideButtonHeight)
                            .clip(RoundedCornerShape(32.dp))
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.skip_previous),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    FilledIconButton(
                        onClick = {
                            if (playbackState == STATE_ENDED) {
                                playerConnection.player.seekTo(0, 0)
                                playerConnection.player.playWhenReady = true
                            } else {
                                playerConnection.player.togglePlayPause()
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = textButtonColor,
                            contentColor = iconButtonColor
                        ),
                        modifier = Modifier
                            .size(width = playButtonWidth, height = playButtonHeight)
                            .clip(RoundedCornerShape(32.dp))
                    ) {
                        if (isLoading) {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(42.dp),
                                color = iconButtonColor,
                            )
                        } else {
                            Icon(
                                painter = painterResource(
                                    when {
                                        playbackState == STATE_ENDED -> R.drawable.replay
                                        isPlaying -> R.drawable.pause
                                        else -> R.drawable.play
                                    }
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(42.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    FilledTonalIconButton(
                        onClick = playerConnection::seekToNext,
                        enabled = canSkipNext,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = textButtonColor,
                            contentColor = iconButtonColor
                        ),
                        modifier = Modifier
                            .size(width = sideButtonWidth, height = sideButtonHeight)
                            .clip(RoundedCornerShape(32.dp))
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.skip_next),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }

        PlayerDesignStyle.V3 -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PlayerHorizontalPadding)
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.shuffle),
                            contentDescription = null,
                            tint = textBackgroundColor.copy(
                                alpha = if (shuffleModeEnabled) 1f else 0.4f
                            ),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(textBackgroundColor.copy(alpha = 0.08f))
                            .clickable(enabled = canSkipPrevious) {
                                playerConnection.seekToPrevious()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.skip_previous),
                            contentDescription = null,
                            tint = textBackgroundColor.copy(alpha = if (canSkipPrevious) 0.9f else 0.4f),
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(70.dp)
                            .clip(RoundedCornerShape(50))
                            .background(textBackgroundColor)
                            .clickable {
                                if (playbackState == STATE_ENDED) {
                                    playerConnection.player.seekTo(0, 0)
                                    playerConnection.player.playWhenReady = true
                                } else {
                                    playerConnection.player.togglePlayPause()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading) {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = icBackgroundColor,
                            )
                        } else {
                            Icon(
                                painter = painterResource(
                                    when {
                                        playbackState == STATE_ENDED -> R.drawable.replay
                                        isPlaying -> R.drawable.pause
                                        else -> R.drawable.play
                                    }
                                ),
                                contentDescription = null,
                                tint = icBackgroundColor,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(textBackgroundColor.copy(alpha = 0.08f))
                            .clickable(enabled = canSkipNext) {
                                playerConnection.seekToNext()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.skip_next),
                            contentDescription = null,
                            tint = textBackgroundColor.copy(alpha = if (canSkipNext) 0.9f else 0.4f),
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { playerConnection.player.toggleRepeatMode() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(
                                when (repeatMode) {
                                    Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL -> R.drawable.repeat
                                    Player.REPEAT_MODE_ONE -> R.drawable.repeat_one
                                    else -> R.drawable.repeat
                                }
                            ),
                            contentDescription = null,
                            tint = textBackgroundColor.copy(
                                alpha = if (repeatMode == Player.REPEAT_MODE_OFF) 0.4f else 1f
                            ),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        PlayerDesignStyle.V4 -> {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PlayerHorizontalPadding)
            ) {
                val baseLarge = 56.dp
                val baseSmall = 46.dp
                val baseGap = 12.dp
                val baseLargeIcon = 28.dp
                val baseSmallIcon = 22.dp
                val baseLargeRadius = 18.dp
                val baseSmallRadius = 16.dp
                val centerSize = 88.dp
                val centerPadding = 40.dp
                val sideTotal = (maxWidth - centerSize - centerPadding) / 2f
                val scale =
                    ((sideTotal - baseGap) / (baseLarge + baseSmall)).coerceAtMost(1f).coerceAtLeast(0.6f)
                val large = baseLarge * scale
                val small = baseSmall * scale
                val gap = baseGap * scale
                val largeIcon = baseLargeIcon * scale
                val smallIcon = baseSmallIcon * scale
                val largeRadius = baseLargeRadius * scale
                val smallRadius = baseSmallRadius * scale

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = {
                                playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled
                            },
                            shape = RoundedCornerShape(smallRadius),
                            color = textBackgroundColor.copy(
                                alpha = if (shuffleModeEnabled) 0.2f else 0.08f
                            ),
                            modifier = Modifier.size(small)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.shuffle),
                                    contentDescription = null,
                                    tint = textBackgroundColor.copy(
                                        alpha = if (shuffleModeEnabled) 1f else 0.6f
                                    ),
                                    modifier = Modifier.size(smallIcon)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(gap))

                        Surface(
                            onClick = { playerConnection.seekToPrevious() },
                            enabled = canSkipPrevious,
                            shape = RoundedCornerShape(largeRadius),
                            color = textBackgroundColor.copy(alpha = 0.15f),
                            modifier = Modifier.size(large)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_previous),
                                    contentDescription = null,
                                    tint = textBackgroundColor.copy(
                                        alpha = if (canSkipPrevious) 1f else 0.4f
                                    ),
                                    modifier = Modifier.size(largeIcon)
                                )
                            }
                        }
                    }

                    Surface(
                        onClick = {
                            if (playbackState == STATE_ENDED) {
                                playerConnection.player.seekTo(0, 0)
                                playerConnection.player.playWhenReady = true
                            } else {
                                playerConnection.player.togglePlayPause()
                            }
                        },
                        shape = RoundedCornerShape(28.dp),
                        color = textButtonColor,
                        modifier = Modifier
                            .padding(horizontal = 20.dp)
                            .size(88.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isLoading) {
                                CircularWavyProgressIndicator(
                                    modifier = Modifier.size(40.dp),
                                    color = icBackgroundColor,
                                )
                            } else {
                                Icon(
                                    painter = painterResource(
                                        when {
                                            playbackState == STATE_ENDED -> R.drawable.replay
                                            isPlaying -> R.drawable.pause
                                            else -> R.drawable.play
                                        }
                                    ),
                                    contentDescription = null,
                                    tint = icBackgroundColor,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = { playerConnection.seekToNext() },
                            enabled = canSkipNext,
                            shape = RoundedCornerShape(largeRadius),
                            color = textBackgroundColor.copy(alpha = 0.15f),
                            modifier = Modifier.size(large)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_next),
                                    contentDescription = null,
                                    tint = textBackgroundColor.copy(
                                        alpha = if (canSkipNext) 1f else 0.4f
                                    ),
                                    modifier = Modifier.size(largeIcon)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(gap))

                        Surface(
                            onClick = { playerConnection.player.toggleRepeatMode() },
                            shape = RoundedCornerShape(smallRadius),
                            color = textBackgroundColor.copy(
                                alpha = if (repeatMode != Player.REPEAT_MODE_OFF) 0.2f else 0.08f
                            ),
                            modifier = Modifier.size(small)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(
                                        when (repeatMode) {
                                            Player.REPEAT_MODE_ONE -> R.drawable.repeat_one
                                            else -> R.drawable.repeat
                                        }
                                    ),
                                    contentDescription = null,
                                    tint = textBackgroundColor.copy(
                                        alpha = if (repeatMode == Player.REPEAT_MODE_OFF) 0.6f else 1f
                                    ),
                                    modifier = Modifier.size(smallIcon)
                                )
                            }
                        }
                    }
                }
            }
        }

        PlayerDesignStyle.V1, PlayerDesignStyle.V5 -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PlayerHorizontalPadding),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    ResizableIconButton(
                        icon = when (repeatMode) {
                            Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL -> R.drawable.repeat
                            Player.REPEAT_MODE_ONE -> R.drawable.repeat_one
                            else -> throw IllegalStateException()
                        },
                        color = textBackgroundColor,
                        modifier = Modifier
                            .size(32.dp)
                            .padding(4.dp)
                            .align(Alignment.Center)
                            .alpha(if (repeatMode == Player.REPEAT_MODE_OFF) 0.5f else 1f),
                        onClick = {
                            playerConnection.player.toggleRepeatMode()
                        },
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    ResizableIconButton(
                        icon = R.drawable.skip_previous,
                        enabled = canSkipPrevious,
                        color = textBackgroundColor,
                        modifier =
                            Modifier
                                .size(32.dp)
                                .align(Alignment.Center),
                        onClick = playerConnection::seekToPrevious,
                    )
                }

                Spacer(Modifier.width(8.dp))

                Box(
                    modifier =
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(playPauseRoundness))
                            .background(textButtonColor)
                            .clickable {
                                if (playbackState == STATE_ENDED) {
                                    playerConnection.player.seekTo(0, 0)
                                    playerConnection.player.playWhenReady = true
                                } else {
                                    playerConnection.player.togglePlayPause()
                                }
                            },
                ) {
                    if (isLoading) {
                        CircularWavyProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(36.dp),
                            color = iconButtonColor,
                        )
                    } else {
                        Image(
                            painter =
                                painterResource(
                                    if (playbackState ==
                                        STATE_ENDED
                                    ) {
                                        R.drawable.replay
                                    } else if (isPlaying) {
                                        R.drawable.pause
                                    } else {
                                        R.drawable.play
                                    },
                                ),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(iconButtonColor),
                            modifier =
                                Modifier
                                    .align(Alignment.Center)
                                    .size(36.dp),
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                Box(modifier = Modifier.weight(1f)) {
                    ResizableIconButton(
                        icon = R.drawable.skip_next,
                        enabled = canSkipNext,
                        color = textBackgroundColor,
                        modifier =
                            Modifier
                                .size(32.dp)
                                .align(Alignment.Center),
                        onClick = playerConnection::seekToNext,
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    ResizableIconButton(
                        icon = if (currentSongLiked) R.drawable.favorite else R.drawable.favorite_border,
                        color = if (currentSongLiked) MaterialTheme.colorScheme.error else textBackgroundColor,
                        modifier =
                            Modifier
                                .size(32.dp)
                                .padding(4.dp)
                                .align(Alignment.Center),
                        onClick = playerConnection::toggleLike,
                    )
                }
            }
        }

        PlayerDesignStyle.V6 -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PlayerHorizontalPadding)
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = textBackgroundColor.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = { playerConnection.seekToPrevious() },
                            enabled = canSkipPrevious,
                            shape = RoundedCornerShape(
                                topStart = 22.dp, bottomStart = 22.dp,
                                topEnd = 8.dp, bottomEnd = 8.dp
                            ),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_previous),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(
                                        alpha = if (canSkipPrevious) 1f else 0.4f
                                    ),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Surface(
                            onClick = {
                                if (playbackState == STATE_ENDED) {
                                    playerConnection.player.seekTo(0, 0)
                                    playerConnection.player.playWhenReady = true
                                } else {
                                    playerConnection.player.togglePlayPause()
                                }
                            },
                            shape = RoundedCornerShape(28.dp),
                            color = textButtonColor,
                            modifier = Modifier
                                .size(width = 88.dp, height = 80.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoading) {
                                    CircularWavyProgressIndicator(
                                        modifier = Modifier.size(40.dp),
                                        color = iconButtonColor,
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(
                                            when {
                                                playbackState == STATE_ENDED -> R.drawable.replay
                                                isPlaying -> R.drawable.pause
                                                else -> R.drawable.play
                                            }
                                        ),
                                        contentDescription = null,
                                        tint = iconButtonColor,
                                        modifier = Modifier.size(44.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Surface(
                            onClick = { playerConnection.seekToNext() },
                            enabled = canSkipNext,
                            shape = RoundedCornerShape(
                                topStart = 8.dp, bottomStart = 8.dp,
                                topEnd = 22.dp, bottomEnd = 22.dp
                            ),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_next),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(
                                        alpha = if (canSkipNext) 1f else 0.4f
                                    ),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        onClick = {
                            playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled
                        },
                        shape = RoundedCornerShape(50),
                        color = if (shuffleModeEnabled)
                            MaterialTheme.colorScheme.tertiaryContainer
                        else textBackgroundColor.copy(alpha = 0.08f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.shuffle),
                                contentDescription = null,
                                tint = if (shuffleModeEnabled)
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                else textBackgroundColor.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Surface(
                        onClick = { playerConnection.player.toggleRepeatMode() },
                        shape = RoundedCornerShape(50),
                        color = if (repeatMode != Player.REPEAT_MODE_OFF)
                            MaterialTheme.colorScheme.tertiaryContainer
                        else textBackgroundColor.copy(alpha = 0.08f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(
                                    when (repeatMode) {
                                        Player.REPEAT_MODE_ONE -> R.drawable.repeat_one
                                        else -> R.drawable.repeat
                                    }
                                ),
                                contentDescription = null,
                                tint = if (repeatMode != Player.REPEAT_MODE_OFF)
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                else textBackgroundColor.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        PlayerDesignStyle.V7 -> {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PlayerHorizontalPadding)
            ) {
                Surface(
                    onClick = playerConnection::seekToPrevious,
                    enabled = canSkipPrevious,
                    shape = CircleShape,
                    color = Color.Transparent,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.skip_previous),
                            contentDescription = null,
                            tint = textBackgroundColor.copy(
                                alpha = if (canSkipPrevious) 1f else 0.4f
                            ),
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }

                Surface(
                    onClick = {
                        if (playbackState == STATE_ENDED) {
                            playerConnection.player.seekTo(0, 0)
                            playerConnection.player.playWhenReady = true
                        } else {
                            playerConnection.player.togglePlayPause()
                        }
                    },
                    shape = CircleShape,
                    color = Color.Transparent,
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading) {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(44.dp),
                                color = textBackgroundColor,
                            )
                        } else {
                            Icon(
                                painter = painterResource(
                                    when {
                                        playbackState == STATE_ENDED -> R.drawable.replay
                                        isPlaying -> R.drawable.pause
                                        else -> R.drawable.play
                                    }
                                ),
                                contentDescription = null,
                                tint = textBackgroundColor,
                                modifier = Modifier.size(52.dp)
                            )
                        }
                    }
                }

                Surface(
                    onClick = { playerConnection.seekToNext() },
                    enabled = canSkipNext,
                    shape = CircleShape,
                    color = Color.Transparent,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.skip_next),
                            contentDescription = null,
                            tint = textBackgroundColor.copy(
                                alpha = if (canSkipNext) 1f else 0.4f
                            ),
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
            }
        }
        else -> {}
    }
}

/**
 * Wrapper composable that combines all player control components.
 * This replaces the large inline controlsContent lambda in BottomSheetPlayer
 * to reduce JIT compilation overhead.
 */
@Composable
fun PlayerControlsContent(
    mediaMetadata: MediaMetadata,
    playerDesignStyle: PlayerDesignStyle,
    sliderStyle: SliderStyle,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    repeatMode: Int,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    textButtonColor: Color,
    iconButtonColor: Color,
    textBackgroundColor: Color,
    icBackgroundColor: Color,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
    clipboardManager: ClipboardManager,
    context: Context,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    currentFormat: FormatEntity? = null,
    onResetTimer: () -> Unit = {},
    controlsFaded: Boolean = false,
    onVisualizerClick: (() -> Unit)? = null,
) {
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)
    val currentSongLiked = currentSong?.song?.liked == true

    val playPauseRoundness by animateDpAsState(
        targetValue = if (isPlaying) 24.dp else 36.dp,
        animationSpec = tween(durationMillis = 90, easing = LinearEasing),
        label = "playPauseRoundness",
    )

    val fadeAlpha by animateFloatAsState(
        targetValue = if (controlsFaded) 0f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "controlsFadeAlpha"
    )

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PlayerHorizontalPadding),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            PlayerTitleSection(
                mediaMetadata = mediaMetadata,
                textBackgroundColor = textBackgroundColor,
                navController = navController,
                state = state,
                clipboardManager = clipboardManager,
                context = context
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .graphicsLayer { alpha = fadeAlpha }
                .pointerInput(controlsFaded) {
                    if (controlsFaded) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                            }
                        }
                    }
                }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.dezzy.music.ui.component.CastButton(tint = textBackgroundColor)
                Spacer(modifier = Modifier.width(4.dp))
                PlayerTopActions(
                    mediaMetadata = mediaMetadata,
                    playerDesignStyle = playerDesignStyle,
                    textButtonColor = textButtonColor,
                    iconButtonColor = iconButtonColor,
                    textBackgroundColor = textBackgroundColor,
                    playerConnection = playerConnection,
                    navController = navController,
                    menuState = menuState,
                    state = state,
                    bottomSheetPageState = bottomSheetPageState,
                    context = context,
                    currentSongLiked = currentSongLiked,
                    onVisualizerClick = onVisualizerClick
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = fadeAlpha }
            .pointerInput(controlsFaded) {
                if (controlsFaded) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                        }
                    }
                }
            }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))

            PlayerSlider(
                sliderStyle = sliderStyle,
                sliderPosition = sliderPosition,
                position = position,
                duration = duration,
                isPlaying = isPlaying,
                textButtonColor = textButtonColor,
                onValueChange = onSliderValueChange,
                onValueChangeFinished = onSliderValueChangeFinished,
                songKey = mediaMetadata.id
            )

            Spacer(Modifier.height(4.dp))

            PlayerTimeLabel(
                sliderPosition = sliderPosition,
                position = position,
                duration = duration,
                textBackgroundColor = textBackgroundColor,
                showRemainingTime = playerDesignStyle == PlayerDesignStyle.V7,
                centerContent = if (playerDesignStyle == PlayerDesignStyle.V7 && currentFormat != null) {
                    {
                        val codec = currentFormat.mimeType.substringAfter("/").uppercase()
                        val label = when {
                            codec.contains("FLAC") || codec.contains("ALAC") -> "Lossless"
                            codec.contains("OPUS") -> codec
                            codec.contains("AAC") -> codec
                            codec.contains("MP4A") -> "AAC"
                            codec.contains("VORBIS") -> "Vorbis"
                            else -> codec
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = textBackgroundColor.copy(alpha = 0.12f),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.graphic_eq),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = textBackgroundColor.copy(alpha = 0.8f),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = textBackgroundColor.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                } else null,
            )

            Spacer(Modifier.height(12.dp))

            PlayerPlaybackControls(
                playerDesignStyle = playerDesignStyle,
                playbackState = playbackState,
                isPlaying = isPlaying,
                isLoading = isLoading,
                repeatMode = repeatMode,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                textButtonColor = textButtonColor,
                iconButtonColor = iconButtonColor,
                textBackgroundColor = textBackgroundColor,
                icBackgroundColor = icBackgroundColor,
                playPauseRoundness = playPauseRoundness,
                playerConnection = playerConnection,
                currentSongLiked = currentSongLiked
            )
        }
    }
}
@Composable
fun PlayerBackground(
    playerBackground: PlayerBackgroundStyle,
    mediaMetadata: MediaMetadata?,
    gradientColors: List<Color>,
    disableBlur: Boolean,
    blurRadius: Float,
    playerCustomImageUri: String,
    playerCustomBlur: Float,
    playerCustomContrast: Float,
    playerCustomBrightness: Float
) {
    val effectiveBlurRadius = blurRadius.coerceIn(0f, 48f)
    val shouldApplyBlur = !disableBlur && effectiveBlurRadius > 0f
    Box(modifier = Modifier.fillMaxSize()) {
        when (playerBackground) {
            PlayerBackgroundStyle.BLUR -> {
                AnimatedContent(
                    targetState = mediaMetadata?.thumbnailUrl,
                    transitionSpec = {
                        fadeIn(tween(1000)) togetherWith fadeOut(tween(1000))
                    },
                    label = ""
                ) { thumbnailUrl ->
                    if (thumbnailUrl != null) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = thumbnailUrl.highRes(),
                                contentDescription = "Blurred background",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().let {
                                    if (shouldApplyBlur) it.blur(radius = effectiveBlurRadius.dp) else it
                                }
                            )
                            val overlayStops = PlayerBackgroundColorUtils.buildBlurOverlayStops(gradientColors)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Brush.verticalGradient(colorStops = overlayStops))
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.08f))
                            )
                        }
                    }
                }
            }

            PlayerBackgroundStyle.GRADIENT -> {
                AnimatedContent(
                    targetState = gradientColors,
                    transitionSpec = {
                        fadeIn(tween(1000)) togetherWith fadeOut(tween(1000))
                    },
                    label = ""
                ) { colors ->
                    if (colors.isNotEmpty()) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            val gradientColorStops = if (colors.size >= 3) {
                                arrayOf(
                                    0.0f to colors[0].copy(alpha = 0.92f), // Top: primary vibrant color
                                    0.5f to colors[1].copy(alpha = 0.75f), // Middle: darker variant
                                    1.0f to colors[2].copy(alpha = 0.65f)  // Bottom: black-ish
                                )
                            } else {
                                arrayOf(
                                    0.0f to colors[0].copy(alpha = 0.9f), // Top: primary color
                                    0.6f to colors[0].copy(alpha = 0.55f), // Middle: faded variant
                                    1.0f to Color.Black.copy(alpha = 0.7f) // Bottom: black
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Brush.verticalGradient(colorStops = gradientColorStops))
                            )
                            // Keep a gentle dark overlay to ensure text contrast on bright artwork
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.18f))
                            )
                        }
                    }
                }
            }

            PlayerBackgroundStyle.COLORING -> {
                AnimatedContent(
                    targetState = gradientColors,
                    transitionSpec = {
                        fadeIn(tween(1000)) togetherWith fadeOut(tween(1000))
                    },
                    label = ""
                ) { colors ->
                    if (colors.isNotEmpty()) {
                        val baseColor = PlayerBackgroundColorUtils.ensureComfortableColor(colors.first())
                        val gradientStops = PlayerBackgroundColorUtils.buildColoringStops(baseColor)
                        Box(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.fillMaxSize().background(baseColor))
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Brush.verticalGradient(colorStops = gradientStops))
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.25f))
                            )
                        }
                    }
                }
            }

            PlayerBackgroundStyle.BLUR_GRADIENT -> {
                AnimatedContent(
                    targetState = mediaMetadata?.thumbnailUrl,
                    transitionSpec = {
                        fadeIn(tween(1000)) togetherWith fadeOut(tween(1000))
                    },
                    label = ""
                ) { thumbnailUrl ->
                    if (thumbnailUrl != null) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = thumbnailUrl.highRes(),
                                contentDescription = "Blurred background",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().let {
                                    if (shouldApplyBlur) it.blur(radius = effectiveBlurRadius.dp) else it
                                }
                            )
                            val gradientColorStops =
                                PlayerBackgroundColorUtils.buildBlurGradientStops(gradientColors)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Brush.verticalGradient(colorStops = gradientColorStops))
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.05f))
                            )
                        }
                    }
                }
            }

            PlayerBackgroundStyle.CUSTOM -> {
                AnimatedContent(
                    targetState = playerCustomImageUri,
                    transitionSpec = {
                        fadeIn(tween(1000)) togetherWith fadeOut(tween(1000))
                    },
                    label = ""
                ) { uri ->
                    if (uri.isNotBlank()) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            val blurPx = playerCustomBlur
                            val contrastVal = playerCustomContrast
                            val brightnessVal = playerCustomBrightness

                            val t = (1f - contrastVal) * 128f + (brightnessVal - 1f) * 255f
                            val matrix = floatArrayOf(
                                contrastVal, 0f, 0f, 0f, t,
                                0f, contrastVal, 0f, 0f, t,
                                0f, 0f, contrastVal, 0f, t,
                                0f, 0f, 0f, 1f, 0f,
                            )

                            val cm = ColorMatrix(matrix)

                            AsyncImage(
                                model = Uri.parse(uri),
                                contentDescription = "Custom background",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().let {
                                    if (disableBlur) it else it.blur(radius = blurPx.dp)
                                },
                                colorFilter = ColorFilter.colorMatrix(cm)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.4f))
                            )
                        }
                    }
                }
            }

            PlayerBackgroundStyle.GLOW -> {
                AnimatedContent(
                    targetState = gradientColors,
                    transitionSpec = {
                        fadeIn(tween(1200)) togetherWith fadeOut(tween(1200))
                    },
                    label = ""
                ) { colors ->
                    if (colors.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .drawWithCache {
                                    val width = size.width
                                    val height = size.height

                                    // Use a dark base, but the gradients will cover most of it
                                    val baseColor = Color(0xFF050505)

                                    // Extract up to 6 colors
                                    val color1 = colors.getOrElse(0) { Color.DarkGray }
                                    val color2 = colors.getOrElse(1) { color1 }
                                    val color3 = colors.getOrElse(2) { color2 }
                                    val color4 = colors.getOrElse(3) { color1 }
                                    val color5 = colors.getOrElse(4) { color2 }
                                    val color6 = colors.getOrElse(5) { color3 }

                                    // Top-Left Large Glow (Primary)
                                    val brush1 = Brush.radialGradient(
                                        colors = listOf(
                                            color1.copy(alpha = 0.8f),
                                            color1.copy(alpha = 0.5f),
                                            Color.Transparent
                                        ),
                                        center = Offset(width * 0.2f, height * 0.25f),
                                        radius = width * 1.2f
                                    )

                                    // Bottom-Right Large Glow (Secondary)
                                    val brush2 = Brush.radialGradient(
                                        colors = listOf(
                                            color2.copy(alpha = 0.75f),
                                            color2.copy(alpha = 0.45f),
                                            Color.Transparent
                                        ),
                                        center = Offset(width * 0.85f, height * 0.8f),
                                        radius = width * 1.1f
                                    )

                                    // Top-Right Glow (Tertiary)
                                    val brush3 = Brush.radialGradient(
                                        colors = listOf(
                                            color3.copy(alpha = 0.7f),
                                            color3.copy(alpha = 0.4f),
                                            Color.Transparent
                                        ),
                                        center = Offset(width * 0.9f, height * 0.15f),
                                        radius = width * 1.0f
                                    )

                                    // Bottom-Left (Quaternary)
                                    val brush4 = Brush.radialGradient(
                                        colors = listOf(
                                            color4.copy(alpha = 0.65f),
                                            color4.copy(alpha = 0.35f),
                                            Color.Transparent
                                        ),
                                        center = Offset(width * 0.1f, height * 0.9f),
                                        radius = width * 1.0f
                                    )

                                    // Top-Center (Quinary)
                                    val brush5 = Brush.radialGradient(
                                        colors = listOf(
                                            color5.copy(alpha = 0.6f),
                                            color5.copy(alpha = 0.3f),
                                            Color.Transparent
                                        ),
                                        center = Offset(width * 0.5f, height * 0.1f),
                                        radius = width * 0.9f
                                    )

                                    // Bottom-Center (Senary)
                                    val brush6 = Brush.radialGradient(
                                        colors = listOf(
                                            color6.copy(alpha = 0.6f),
                                            color6.copy(alpha = 0.3f),
                                            Color.Transparent
                                        ),
                                        center = Offset(width * 0.5f, height * 0.95f),
                                        radius = width * 0.9f
                                    )

                                    onDrawBehind {
                                        drawRect(color = baseColor)
                                        drawRect(brush = brush1)
                                        drawRect(brush = brush2)
                                        drawRect(brush = brush3)
                                        drawRect(brush = brush4)
                                        drawRect(brush = brush5)
                                        drawRect(brush = brush6)
                                    }
                                }
                        )
                    }
                }
            }

            PlayerBackgroundStyle.GLOW_ANIMATED -> {
                AnimatedContent(
                    targetState = gradientColors,
                    transitionSpec = {
                        fadeIn(tween(1200)) togetherWith fadeOut(tween(1200))
                    },
                    label = "GlowAnimatedContent"
                ) { colors ->
                    if (colors.isNotEmpty()) {
                        val infiniteTransition = rememberInfiniteTransition(label = "GlowAnimation")

                        val progress by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(20000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "glowProgress"
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .drawWithCache {
                                    val width = size.width
                                    val height = size.height
                                    val baseColor = Color(0xFF050505)

                                    onDrawBehind {
                                        val p = progress

                                        fun rotatedColorAt(index: Int): Color {
                                            val size = colors.size
                                            val idx = index.toFloat() + p * size
                                            val a = kotlin.math.floor(idx).toInt() % size
                                            val b = (a + 1) % size
                                            val frac = idx - kotlin.math.floor(idx)
                                            return androidx.compose.ui.graphics.lerp(colors.getOrElse(a) { Color.DarkGray }, colors.getOrElse(b) { Color.DarkGray }, frac)
                                        }

                                        fun oscillate(min: Float, max: Float, phase: Float, speed: Float = 1f): Float {
                                            val v = kotlin.math.sin(2f * kotlin.math.PI.toFloat() * (p * speed + phase)).toFloat()
                                            return min + (max - min) * ((v + 1f) * 0.5f)
                                        }

                                        val color1 = rotatedColorAt(0)
                                        val color2 = rotatedColorAt(1)
                                        val color3 = rotatedColorAt(2)
                                        val color4 = rotatedColorAt(3)
                                        val color5 = rotatedColorAt(4)
                                        val color6 = rotatedColorAt(5)

                                        val o1x = oscillate(0.0f, 1.0f, 0.00f, 1.0f)
                                        val o1y = oscillate(0.0f, 0.5f, 0.07f, 1.0f)
                                        val r1 = oscillate(0.8f, 1.6f, 0.12f, 1.0f)

                                        val o2x = oscillate(1.0f, 0.0f, 0.2f, 1.0f)
                                        val o2y = oscillate(0.5f, 1.0f, 0.25f, 1.0f)
                                        val r2 = oscillate(0.7f, 1.5f, 0.18f, 1.0f)

                                        val o3x = oscillate(0.2f, 0.8f, 0.33f, 1.0f)
                                        val o3y = oscillate(0.8f, 0.2f, 0.36f, 1.0f)
                                        val r3 = oscillate(0.6f, 1.4f, 0.29f, 1.0f)

                                        val o4x = oscillate(0.3f, 0.7f, 0.44f, 1.0f)
                                        val o4y = oscillate(0.2f, 0.8f, 0.41f, 1.0f)
                                        val r4 = oscillate(0.9f, 1.7f, 0.47f, 1.0f)

                                        val o5x = oscillate(0.4f, 0.6f, 0.55f, 1.0f)
                                        val o5y = oscillate(0.0f, 1.0f, 0.51f, 1.0f)
                                        val r5 = oscillate(0.7f, 1.5f, 0.58f, 1.0f)

                                        val o6x = oscillate(0.0f, 1.0f, 0.66f, 1.0f)
                                        val o6y = oscillate(0.5f, 0.7f, 0.62f, 1.0f)
                                        val r6 = oscillate(0.8f, 1.8f, 0.69f, 1.0f)

                                        val brush1 = Brush.radialGradient(
                                            colors = listOf(color1.copy(alpha = 0.85f), color1.copy(alpha = 0.5f), Color.Transparent),
                                            center = Offset(width * o1x, height * o1y),
                                            radius = width * r1
                                        )
                                        val brush2 = Brush.radialGradient(
                                            colors = listOf(color2.copy(alpha = 0.8f), color2.copy(alpha = 0.45f), Color.Transparent),
                                            center = Offset(width * o2x, height * o2y),
                                            radius = width * r2
                                        )
                                        val brush3 = Brush.radialGradient(
                                            colors = listOf(color3.copy(alpha = 0.75f), color3.copy(alpha = 0.4f), Color.Transparent),
                                            center = Offset(width * o3x, height * o3y),
                                            radius = width * r3
                                        )
                                        val brush4 = Brush.radialGradient(
                                            colors = listOf(color4.copy(alpha = 0.7f), color4.copy(alpha = 0.35f), Color.Transparent),
                                            center = Offset(width * o4x, height * o4y),
                                            radius = width * r4
                                        )
                                        val brush5 = Brush.radialGradient(
                                            colors = listOf(color5.copy(alpha = 0.65f), color5.copy(alpha = 0.3f), Color.Transparent),
                                            center = Offset(width * o5x, height * o5y),
                                            radius = width * r5
                                        )
                                        val brush6 = Brush.radialGradient(
                                            colors = listOf(color6.copy(alpha = 0.6f), color6.copy(alpha = 0.25f), Color.Transparent),
                                            center = Offset(width * o6x, height * o6y),
                                            radius = width * r6
                                        )

                                        drawRect(color = baseColor)
                                        drawRect(brush = brush1)
                                        drawRect(brush = brush2)
                                        drawRect(brush = brush3)
                                        drawRect(brush = brush4)
                                        drawRect(brush = brush5)
                                        drawRect(brush = brush6)
                                    }
                                }
                        )
                    }
                }
            }

            else -> {
                // DEFAULT or other modes - no background
            }
        }
    }
}