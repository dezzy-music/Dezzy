/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.component

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.dezzy.music.ui.screens.Screens

/**
 * Barra de navegación principal (Boceto A, fase 1): `NavigationBar` de Material 3
 * acoplada al borde inferior, contenedor `surfaceContainer`, indicador en píldora
 * `secondaryContainer` e iconos Outlined (inactivo) / Filled (activo).
 *
 * Recibe la misma lógica de selección y clic que la barra flotante, así que el
 * comportamiento (volver arriba al re-seleccionar, abrir la búsqueda…) no cambia.
 *
 * @param bottomInset alto de la barra de gestos/botones del sistema; la barra se
 * dibuja por detrás (edge-to-edge) y deja ese espacio libre para sus elementos.
 */
@Composable
fun DezzyNavigationBar(
    items: List<Screens>,
    pureBlack: Boolean,
    bottomInset: Dp,
    isSelected: (Screens) -> Boolean,
    onItemClick: (Screens, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        windowInsets = WindowInsets(bottom = bottomInset),
    ) {
        items.forEach { screen ->
            val selected = isSelected(screen)
            NavigationBarItem(
                selected = selected,
                onClick = { onItemClick(screen, selected) },
                icon = {
                    Icon(
                        painter = painterResource(
                            if (selected) screen.iconIdActive else screen.iconIdInactive
                        ),
                        contentDescription = null,
                    )
                },
                label = {
                    Text(
                        text = stringResource(screen.titleId),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
