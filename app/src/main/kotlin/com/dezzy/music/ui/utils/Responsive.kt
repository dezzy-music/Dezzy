/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Responsive helpers shared across screens.
 *
 * The app is phone-first; these utilities keep single-column content readable
 * on large screens (tablets, foldables, landscape) without restructuring each
 * screen into multi-pane layouts.
 */

/** Comfortable maximum reading width for single-column content. */
val DefaultMaxContentWidth: Dp = 640.dp

/**
 * Side padding that centers single-column content within [maxContentWidth] on
 * large screens. Returns 0.dp on phones (the screen is already narrower than the
 * cap), so it is a no-op there and only affects tablets / landscape / foldables.
 *
 * Apply as `Modifier.padding(horizontal = rememberAdaptiveSidePadding())` on the
 * root scroll container of a single-column screen.
 */
@Composable
fun rememberAdaptiveSidePadding(maxContentWidth: Dp = DefaultMaxContentWidth): Dp {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    return if (screenWidthDp > maxContentWidth) (screenWidthDp - maxContentWidth) / 2 else 0.dp
}
