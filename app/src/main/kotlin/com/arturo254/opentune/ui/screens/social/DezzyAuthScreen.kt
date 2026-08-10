/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.arturo254.opentune.ui.screens.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arturo254.opentune.R
import com.arturo254.opentune.social.SupabaseAuth
import kotlinx.coroutines.launch

/**
 * Acceso a la red social con cuenta PROPIA de Dezzy (correo + contraseña).
 *
 * Antes el único camino era Google, lo que ataba la identidad a un proveedor
 * externo y dejaba fuera a quien no quisiera usarlo. Aquí la cuenta se crea y se
 * usa dentro de la app.
 *
 * La contraseña nunca se guarda: se manda a Supabase y lo que se persiste es el
 * par de tokens de sesión.
 */
@Composable
fun DezzyAuthScreen(onAuthenticated: () -> Unit) {
    val scope = rememberCoroutineScope()

    var isRegister by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val emailOk = email.contains('@') && email.contains('.') && email.length >= 5
    val passwordOk = password.length >= 6
    val canSubmit = emailOk && passwordOk && !loading &&
        (!isRegister || displayName.isNotBlank())

    fun submit() {
        if (!canSubmit) return
        loading = true
        message = null
        scope.launch {
            val result = if (isRegister) {
                SupabaseAuth.signUpWithEmail(email, password, displayName)
            } else {
                SupabaseAuth.signInWithEmail(email, password)
            }
            loading = false
            when (result) {
                is SupabaseAuth.AuthOutcome.Success -> {
                    // La contraseña se descarta en cuanto hay sesión.
                    password = ""
                    onAuthenticated()
                }
                is SupabaseAuth.AuthOutcome.ConfirmEmail -> {
                    isError = false
                    message = "Cuenta creada. Revisa tu correo para confirmarla y luego inicia sesión."
                    isRegister = false
                }
                is SupabaseAuth.AuthOutcome.Error -> {
                    isError = true
                    message = result.message
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.star),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(34.dp),
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = if (isRegister) "Crea tu cuenta de Dezzy" else "Entra a Dezzy",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (isRegister) {
                "Con tu cuenta puedes seguir a otros, compartir música y ver qué escuchan tus amigos."
            } else {
                "Usa tu cuenta de Dezzy para entrar a la comunidad."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(26.dp))

        if (isRegister) {
            DezzyTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = "Nombre",
                placeholder = "Cómo quieres que te llamen",
                maxLength = 40,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(14.dp))
        }

        DezzyTextField(
            value = email,
            onValueChange = { email = it.trim() },
            label = "Correo",
            placeholder = "tucorreo@ejemplo.com",
            isError = email.isNotEmpty() && !emailOk,
            errorText = "Ese correo no parece válido",
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
                autoCorrectEnabled = false,
            ),
        )

        Spacer(Modifier.height(14.dp))

        DezzyTextField(
            value = password,
            onValueChange = { password = it },
            label = "Contraseña",
            placeholder = "••••••",
            isError = password.isNotEmpty() && !passwordOk,
            errorText = "Mínimo 6 caracteres",
            supportingText = if (isRegister) "Mínimo 6 caracteres" else null,
            visualTransformation = if (showPassword) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailing = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    // Un solo icono: atenuado cuando la contraseña está oculta.
                    Icon(
                        painter = painterResource(R.drawable.visibility),
                        contentDescription = if (showPassword) "Ocultar contraseña" else "Mostrar contraseña",
                        tint = if (showPassword) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { submit() },
            ),
        )

        if (message != null) {
            Spacer(Modifier.height(14.dp))
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isError) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = message.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        Button(
            onClick = ::submit,
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    text = if (isRegister) "Crear cuenta" else "Entrar",
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        TextButton(onClick = { isRegister = !isRegister; message = null }) {
            Text(
                text = if (isRegister) "Ya tengo cuenta, entrar"
                else "No tengo cuenta, crear una",
            )
        }

        if (!isRegister) {
            TextButton(
                enabled = emailOk && !loading,
                onClick = {
                    loading = true
                    scope.launch {
                        val r = SupabaseAuth.sendPasswordReset(email)
                        loading = false
                        isError = r !is SupabaseAuth.AuthOutcome.Success
                        message = if (isError) {
                            (r as? SupabaseAuth.AuthOutcome.Error)?.message ?: "No se pudo enviar"
                        } else {
                            "Te enviamos un correo para restablecer la contraseña."
                        }
                    }
                },
            ) {
                Text("Olvidé mi contraseña")
            }
        }
    }
}
