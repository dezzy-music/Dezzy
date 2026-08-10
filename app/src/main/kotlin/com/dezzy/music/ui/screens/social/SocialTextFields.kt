/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.ui.screens.social

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dezzy.music.R

/**
 * Campos de texto compartidos por TODA la red social.
 *
 * Antes cada pantalla montaba su propio `TextField`/`OutlinedTextField` con
 * colores y formas distintas: el buscador de Amigos no se parecía al de
 * Mensajes, el compositor del feed no se parecía al del chat, y ninguno
 * gestionaba foco, límite de caracteres ni acción del teclado. Aquí vive un
 * único juego de campos para que todo se vea y se comporte igual:
 *
 *  - [DezzyTextField]    formulario (nombre, correo, contraseña, bio…)
 *  - [DezzySearchField]  buscador tipo píldora, con borrar y acción "buscar"
 *  - [DezzyComposerField] escribir + enviar (chat, comentarios, publicaciones)
 *
 * Todos comparten: contenedor relleno sin línea inferior, esquinas redondeadas,
 * anillo de foco animado en color primario, y estado de error explícito.
 */

private val FieldShape = RoundedCornerShape(18.dp)
private val PillShape = RoundedCornerShape(28.dp)

@Composable
private fun fieldContainerColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

/**
 * Caja base: contenedor relleno + anillo de foco animado. La comparten los tres
 * campos para que el foco se sienta idéntico en toda la red social.
 */
@Composable
private fun FieldSurface(
    focused: Boolean,
    isError: Boolean,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val borderColor by animateColorAsState(
        targetValue = when {
            isError -> MaterialTheme.colorScheme.error
            focused -> MaterialTheme.colorScheme.primary
            else -> Color.Transparent
        },
        animationSpec = tween(160),
        label = "field_border",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused || isError) 1.5.dp else 0.dp,
        animationSpec = tween(160),
        label = "field_border_width",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(fieldContainerColor())
            .border(borderWidth, borderColor, shape),
    ) { content() }
}

/**
 * Campo de formulario. `label` se dibuja ENCIMA del campo (no flotante): con
 * etiqueta flotante el texto saltaba al escribir y en pantallas con varios
 * campos seguidos quedaba desalineado.
 */
@Composable
fun DezzyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    errorText: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    maxLength: Int? = null,
    showCounter: Boolean = maxLength != null && !singleLine,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
        }
        FieldSurface(
            focused = focused,
            isError = isError,
            shape = FieldShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(
                        painter = painterResource(leadingIcon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 0.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Box(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = value,
                        onValueChange = { new ->
                            onValueChange(if (maxLength != null) new.take(maxLength) else new)
                        },
                        enabled = enabled,
                        singleLine = singleLine,
                        minLines = minLines,
                        maxLines = maxLines,
                        textStyle = MaterialTheme.typography.bodyLarge.merge(
                            TextStyle(color = MaterialTheme.colorScheme.onSurface)
                        ),
                        visualTransformation = visualTransformation,
                        keyboardOptions = keyboardOptions,
                        keyboardActions = keyboardActions,
                        interactionSource = interactionSource,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (trailing != null) {
                    Spacer(Modifier.width(8.dp))
                    trailing()
                }
            }
        }

        val helper = if (isError) errorText ?: supportingText else supportingText
        if (helper != null || (showCounter && maxLength != null)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = helper.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showCounter && maxLength != null) {
                    Text(
                        text = "${value.length}/$maxLength",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Buscador tipo píldora. Trae la lupa, el botón de borrar (aparece solo con
 * texto) y la acción "buscar" del teclado, que antes no tenía ninguno de los
 * buscadores de la red social.
 */
@Composable
fun DezzySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Buscar",
    onSearch: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    FieldSurface(
        focused = focused,
        isError = false,
        shape = PillShape,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.social_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.merge(
                        TextStyle(color = MaterialTheme.colorScheme.onSurface)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        onSearch?.invoke()
                        keyboard?.hide()
                        focusManager.clearFocus()
                    }),
                    interactionSource = interactionSource,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.social_close),
                        contentDescription = "Borrar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * Campo de "escribir y enviar" (chat, comentarios, publicaciones).
 *
 * Crece con el texto hasta [maxLines] y luego hace scroll interno, en vez de
 * empujar la pantalla. El botón de enviar solo se habilita con texto real, y
 * limpia el campo desde aquí para que ninguna pantalla se olvide de hacerlo.
 */
@Composable
fun DezzyComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Escribe algo…",
    enabled: Boolean = true,
    sending: Boolean = false,
    maxLines: Int = 5,
    maxLength: Int = 500,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val canSend = enabled && !sending && value.isNotBlank()
    val sendTint by animateColorAsState(
        targetValue = if (canSend) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(160),
        label = "composer_send_tint",
    )
    val sendBg by animateColorAsState(
        targetValue = if (canSend) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(160),
        label = "composer_send_bg",
    )

    Row(
        modifier = modifier.padding(contentPadding),
        verticalAlignment = Alignment.Bottom,
    ) {
        FieldSurface(
            focused = focused,
            isError = false,
            shape = PillShape,
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 18.dp, vertical = 13.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { onValueChange(it.take(maxLength)) },
                    enabled = enabled,
                    maxLines = maxLines,
                    textStyle = MaterialTheme.typography.bodyLarge.merge(
                        TextStyle(color = MaterialTheme.colorScheme.onSurface)
                    ),
                    interactionSource = interactionSource,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(sendBg),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                enabled = canSend,
                onClick = {
                    val text = value.trim()
                    if (text.isNotEmpty()) {
                        onValueChange("")
                        onSend(text)
                    }
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.social_send),
                    contentDescription = "Enviar",
                    tint = sendTint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
