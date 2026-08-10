/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.ui.screens

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
