/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.video

import kotlinx.coroutines.flow.MutableStateFlow

/** Si la Activity está en imagen en imagen (lo actualiza MainActivity). */
object VideoPipState {
    val inPip = MutableStateFlow(false)
}
