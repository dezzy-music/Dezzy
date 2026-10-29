package com.dezzy.music.ui.screens.eq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun GraphicEqFader(
    label: String,
    gainDb: Float,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary
) {
    // Rango de -15dB a +15dB
    val maxDb = 15f
    val range = maxDb * 2f
    
    // Mapear la ganancia a un porcentaje (0.0 a 1.0), donde 0.5 es 0dB
    val percent = ((gainDb + maxDb) / range).coerceIn(0f, 1f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.width(60.dp).padding(vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Box(
            modifier = Modifier
                .weight(1f)
                .width(44.dp)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val height = size.height.toFloat()
                        // Invertimos la dirección (hacia arriba es positivo)
                        val deltaPercent = -dragAmount.y / height
                        val newPercent = (percent + deltaPercent).coerceIn(0f, 1f)
                        val newGain = (newPercent * range) - maxDb
                        onGainChange(newGain)
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                
                // Dibujar marcas (como en un fader profesional)
                val lines = 21
                for (i in 0 until lines) {
                    val y = (i.toFloat() / (lines - 1)) * height
                    val isCenter = i == 10
                    val lineColor = if (isCenter) activeColor.copy(alpha = 0.5f) else Color.DarkGray
                    val lineWidth = if (isCenter) width * 0.8f else width * 0.4f
                    val startX = (width - lineWidth) / 2f
                    drawLine(
                        color = lineColor,
                        start = Offset(startX, y),
                        end = Offset(startX + lineWidth, y),
                        strokeWidth = 2f
                    )
                }
                
                // Dibujar el 'fader' (la perilla)
                val faderY = height - (percent * height)
                
                // Fader cap
                drawLine(
                    color = activeColor,
                    start = Offset(6f, faderY),
                    end = Offset(width - 6f, faderY),
                    strokeWidth = 16f,
                    cap = StrokeCap.Round
                )
                
                // Indicador luminoso central en el fader
                drawLine(
                    color = Color.White,
                    start = Offset(width/2f - 4f, faderY),
                    end = Offset(width/2f + 4f, faderY),
                    strokeWidth = 4f,
                    cap = StrokeCap.Round
                )
            }
        }
        
        Text(
            text = if (gainDb > 0) "+${gainDb.roundToInt()}" else "${gainDb.roundToInt()}",
            color = if (gainDb == 0f) Color.Gray else activeColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
