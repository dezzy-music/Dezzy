/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.appicon

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

private fun DrawScope.drawIcon(style: AppIconStyle, background: Boolean, corner: Float, mono: Color?) {
    val s = size.minDimension
    drawIntoCanvas { c ->
        val nc = c.nativeCanvas
        nc.save()
        nc.translate((size.width - s) / 2f, (size.height - s) / 2f)
        AppIcon.draw(nc, s, style, background, corner, mono?.toArgb())
        nc.restore()
    }
}

/** El icono completo de Dezzy (fondo + figura) con los colores elegidos en Ajustes. */
@Composable
fun DezzyAppIcon(
    modifier: Modifier = Modifier,
    style: AppIconStyle = rememberAppIconStyle(),
    corner: Float = 0.22f,
) {
    Canvas(modifier) { drawIcon(style, true, corner, null) }
}

/**
 * Sólo la marca (sin fondo) con la forma elegida. Con [tint] se pinta de un color, como un
 * icono normal; sin él, usa los colores de figura y acento del usuario.
 */
@Composable
fun DezzyLogoMark(
    modifier: Modifier = Modifier,
    tint: Color? = null,
    style: AppIconStyle = rememberAppIconStyle(),
) {
    Canvas(modifier) { drawIcon(style, false, 0f, tint) }
}
