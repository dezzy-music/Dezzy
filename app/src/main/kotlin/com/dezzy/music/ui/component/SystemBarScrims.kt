/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.component

import android.app.Activity
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Opacidad del velo de marca bajo las barras del sistema (Android 10+). */
private const val ScrimAlpha = 0.92f

/**
 * Barras del sistema con el color distintivo de la app.
 *
 * - Android 10+ (edge-to-edge): la app dibuja de borde a borde y se pinta un
 *   velo (scrim) del color de marca justo bajo la barra de estado y la de
 *   navegación.
 * - Android 8–9: las barras se colorean directamente con el color de marca.
 *
 * Con [visible] en false (reproductor expandido) el velo se oculta y las barras
 * vuelven a ser transparentes. El color de los iconos del sistema se elige por
 * contraste con el color que queda detrás.
 */
@Composable
fun BoxScope.SystemBarScrims(
    color: Color,
    visible: Boolean,
    isDarkTheme: Boolean,
) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    val legacyBars = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    LaunchedEffect(window, color, visible, isDarkTheme) {
        if (window == null) return@LaunchedEffect
        val lightIcons = if (visible) color.luminance() > 0.5f else !isDarkTheme
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightIcons
            isAppearanceLightNavigationBars = lightIcons
        }
        if (legacyBars) {
            @Suppress("DEPRECATION")
            val barColor = (if (visible) color else Color.Transparent).toArgb()
            @Suppress("DEPRECATION")
            window.statusBarColor = barColor
            @Suppress("DEPRECATION")
            window.navigationBarColor = barColor
        }
    }

    if (legacyBars) return

    val alpha by animateFloatAsState(
        targetValue = if (visible) ScrimAlpha else 0f,
        label = "systemBarScrimAlpha",
    )
    val scrim = color
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .graphicsLayer { this.alpha = alpha }
            .background(scrim)
    )
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .windowInsetsBottomHeight(WindowInsets.navigationBars)
            .graphicsLayer { this.alpha = alpha }
            .background(scrim)
    )
}
