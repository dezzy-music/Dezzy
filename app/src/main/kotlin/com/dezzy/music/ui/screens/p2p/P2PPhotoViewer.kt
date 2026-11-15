/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.p2p

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.dezzy.music.R
import kotlin.math.abs

/**
 * Visor de fotos a pantalla completa, al estilo Instagram: fondo negro, pellizcar o doble toque
 * para ampliar, deslizar a los lados entre fotos y hacia abajo para cerrar. Un toque oculta o
 * muestra los controles. Atrás también cierra.
 */
@Composable
fun P2PPhotoViewer(
    photos: List<String>,
    onDismiss: () -> Unit,
    initialPage: Int = 0,
    title: String? = null,
    caption: String? = null,
) {
    if (photos.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, photos.lastIndex)) { photos.size }
        var chrome by remember { mutableStateOf(true) }
        var zoomed by remember { mutableStateOf(false) }
        var dismissDrag by remember { mutableFloatStateOf(0f) }
        val fade = (1f - abs(dismissDrag) / 900f).coerceIn(0.3f, 1f)

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = fade)),
        ) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = !zoomed,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                ZoomablePhoto(
                    model = p2pImageModel(photos[page]),
                    description = if (photos.size > 1) "Foto ${page + 1} de ${photos.size}" else "Foto",
                    active = page == pager.currentPage,
                    onZoomChange = { if (page == pager.currentPage) zoomed = it },
                    onDrag = { dismissDrag = it },
                    onDismiss = onDismiss,
                    onTap = { chrome = !chrome },
                )
            }

            // Los controles van sobre un velo oscuro: blanco sobre negro, siempre legible.
            AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(painterResource(R.drawable.close), contentDescription = "Cerrar foto", tint = Color.White)
                    }
                    Text(
                        title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (photos.size > 1) {
                        Text(
                            "${pager.currentPage + 1}/${photos.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                }
            }
            if (!caption.isNullOrBlank()) {
                AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                            .navigationBarsPadding()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            caption,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomablePhoto(
    model: Any?,
    description: String,
    active: Boolean,
    onZoomChange: (Boolean) -> Unit,
    onDrag: (Float) -> Unit,
    onDismiss: () -> Unit,
    onTap: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dragY by remember { mutableFloatStateOf(0f) }

    // Al pasar a otra foto, ésta vuelve a su tamaño.
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            offset = Offset.Zero
            dragY = 0f
        }
    }

    fun setScale(value: Float) {
        scale = value.coerceIn(1f, 5f)
        if (scale == 1f) offset = Offset.Zero
        onZoomChange(scale > 1f)
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { setScale(if (scale > 1f) 1f else 2.5f) },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dismissing = false
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val fingers = event.changes.count { it.pressed }
                        when {
                            fingers > 1 || scale > 1f -> {
                                // Ampliada o con dos dedos: pellizcar y mover la foto.
                                if (zoom != 1f) setScale(scale * zoom)
                                if (scale > 1f) {
                                    val maxX = size.width * (scale - 1f) / 2f
                                    val maxY = size.height * (scale - 1f) / 2f
                                    offset = Offset(
                                        (offset.x + pan.x * scale).coerceIn(-maxX, maxX),
                                        (offset.y + pan.y * scale).coerceIn(-maxY, maxY),
                                    )
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }

                            dismissing || (abs(pan.y) > abs(pan.x) * 1.5f && abs(pan.y) > 2f) -> {
                                // Sin ampliar, arrastre vertical: cerrar al soltar si se pasa del umbral.
                                dismissing = true
                                dragY += pan.y
                                onDrag(dragY)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (dismissing) {
                        if (abs(dragY) > size.height * 0.18f) {
                            onDismiss()
                        } else {
                            dragY = 0f
                            onDrag(0f)
                        }
                    }
                }
            }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(if (scale > 1f) "Ver a tamaño normal" else "Ampliar") {
                        setScale(if (scale > 1f) 1f else 2.5f)
                        true
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = model,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y + dragY
                },
        )
    }
}
