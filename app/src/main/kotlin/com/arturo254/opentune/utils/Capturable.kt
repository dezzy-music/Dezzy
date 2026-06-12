package com.arturo254.opentune.utils

import android.graphics.Bitmap
import android.graphics.Picture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

class CaptureController {
    var captureAction: (() -> Bitmap?)? = null

    fun capture(): Bitmap? {
        return captureAction?.invoke()
    }
}

@Composable
fun rememberCaptureController(): CaptureController {
    return remember { CaptureController() }
}

@Composable
fun Capturable(
    controller: CaptureController,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val picture = remember { Picture() }

    controller.captureAction = {
        if (picture.width > 0 && picture.height > 0) {
            val bitmap = Bitmap.createBitmap(
                picture.width,
                picture.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.drawPicture(picture)
            bitmap
        } else {
            null
        }
    }

    androidx.compose.foundation.layout.Box(
        modifier = modifier.drawWithCache {
            val width = this.size.width.toInt()
            val height = this.size.height.toInt()
            onDrawWithContent {
                val pictureCanvas = androidx.compose.ui.graphics.Canvas(
                    picture.beginRecording(width, height)
                )
                draw(this, this.layoutDirection, pictureCanvas, this.size) {
                    this@onDrawWithContent.drawContent()
                }
                picture.endRecording()

                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawPicture(picture)
                }
            }
        }
    ) {
        content()
    }
}
