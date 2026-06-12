/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.ui.component

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@Composable
fun VideoWallpaperPlayer(
    videoUri: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    exoPlayer?.pause()
                }
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> {
                    exoPlayer?.play()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView(
        factory = { ctx ->
            val player = ExoPlayer.Builder(ctx).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                playWhenReady = true
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                volume = 0f // Keep it silent for wallpaper
                // Explicitly mute audio to avoid focus issues
                val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                setAudioAttributes(audioAttributes, false)
            }
            exoPlayer = player

            PlayerView(ctx).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                // Ensure transparency to avoid black flashes or backgrounds
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { playerView ->
            val player = playerView.player as? ExoPlayer ?: return@AndroidView
            
            val uri = try {
                if (videoUri.startsWith("content://") || videoUri.startsWith("file://") || videoUri.startsWith("http")) {
                    Uri.parse(videoUri)
                } else if (videoUri.startsWith("android.resource://") || videoUri.startsWith("rawresource://")) {
                    // Normalize resource URI
                    val resName = videoUri.substringAfterLast("/")
                    val resId = context.resources.getIdentifier(resName, "raw", context.packageName)
                    if (resId != 0) {
                        Uri.parse("rawresource:///$resId")
                    } else {
                        Uri.parse(videoUri)
                    }
                } else {
                    // Assume it's a raw resource name if no scheme
                    val resId = context.resources.getIdentifier(videoUri, "raw", context.packageName)
                    if (resId != 0) {
                        Uri.parse("rawresource:///$resId")
                    } else {
                        Uri.parse(videoUri)
                    }
                }
            } catch (e: Exception) {
                Uri.parse(videoUri)
            }
            
            val currentUri = player.currentMediaItem?.localConfiguration?.uri
            if (currentUri != uri) {
                val mediaItem = MediaItem.fromUri(uri)
                player.setMediaItem(mediaItem)
                player.prepare()
            }
        },
        onRelease = { playerView ->
            playerView.player?.release()
            playerView.player = null
            exoPlayer = null
        },
        modifier = modifier.fillMaxSize()
    )
}
