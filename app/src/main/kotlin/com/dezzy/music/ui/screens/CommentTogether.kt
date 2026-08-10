/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun CommentTogether(
    videoId: String,
    modifier: Modifier = Modifier
) {
    // A simple container wrapper for dual pane display if needed
    Box(modifier = modifier) {
        CommentSheet(
            videoId = videoId,
            onDismiss = {}
        )
    }
}
