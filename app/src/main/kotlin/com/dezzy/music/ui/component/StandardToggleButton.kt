/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.dp

/** Colores de [StandardToggleButton]; los no indicados usan los de Material 3. */
data class ToggleColors(
    val containerColor: Color = Color.Unspecified,
    val contentColor: Color = Color.Unspecified,
    val checkedContainerColor: Color = Color.Unspecified,
    val checkedContentColor: Color = Color.Unspecified,
)

/**
 * Botón conmutable con componentes estándar de Material 3 (Surface), en lugar
 * del ToggleButton de Material 3 Expressive.
 */
@Composable
fun StandardToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(percent = 50),
    colors: ToggleColors = ToggleColors(),
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (checked) colors.checkedContainerColor.takeOrElse { scheme.primary }
    else colors.containerColor.takeOrElse { scheme.surfaceContainer }
    val contentColor = if (checked) colors.checkedContentColor.takeOrElse { scheme.onPrimary }
    else colors.contentColor.takeOrElse { scheme.onSurfaceVariant }

    Surface(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        shape = shape,
        color = container,
        contentColor = contentColor,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            content = content,
        )
    }
}

/** Formas para grupos de botones unidos (izquierda / centro / derecha). */
object ConnectedButtonShapes {
    val SpaceBetween = 2.dp
    val Leading: Shape = RoundedCornerShape(
        topStartPercent = 50, topEndPercent = 15, bottomEndPercent = 15, bottomStartPercent = 50,
    )
    val Middle: Shape = RoundedCornerShape(percent = 15)
    val Trailing: Shape = RoundedCornerShape(
        topStartPercent = 15, topEndPercent = 50, bottomEndPercent = 50, bottomStartPercent = 15,
    )
}
