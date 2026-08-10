/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.ui.screens.social

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.arturo254.opentune.R
import com.arturo254.opentune.constants.SocialMyPhoneKey
import com.arturo254.opentune.social.ContactsRepository
import com.arturo254.opentune.social.MyProfile
import com.arturo254.opentune.social.SocialRepository
import com.arturo254.opentune.social.SocialResult
import com.arturo254.opentune.utils.dataStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var profile by remember { mutableStateOf<MyProfile?>(null) }
    var localPhone by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditField?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    suspend fun reload() {
        profile = SocialRepository.getMyProfile()
        localPhone = context.dataStore.data.first()[SocialMyPhoneKey].orEmpty()
    }
    LaunchedEffect(Unit) { reload() }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) scope.launch {
            uploading = true
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bmp = BitmapFactory.decodeStream(input)
                    val side = minOf(bmp.width, bmp.height).coerceAtLeast(1)
                    val square = android.graphics.Bitmap.createBitmap(
                        bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side
                    )
                    val scaled = android.graphics.Bitmap.createScaledBitmap(square, 512, 512, true)
                    java.io.ByteArrayOutputStream().apply {
                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, this)
                    }.toByteArray()
                }
            }.getOrNull()
            if (bytes != null) SocialRepository.uploadAvatar(bytes)
            reload()
            uploading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Perfil") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .size(150.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val avatar = profile?.avatarUrl
                if (avatar != null) {
                    AsyncImage(
                        model = avatar,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(150.dp)
                            .clip(CircleShape),
                    )
                } else {
                    Box(
                        Modifier
                            .size(150.dp)
                            .clip(CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.social_profile),
                            contentDescription = null,
                            tint = Color(0xFFCFD8DC),
                            modifier = Modifier.size(90.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Text(if (uploading) "Subiendo…" else "Editar", color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(16.dp))

            ProfileRow(
                icon = R.drawable.social_profile,
                label = "Nombre",
                value = profile?.name?.ifBlank { null },
                placeholder = "Agrega tu nombre",
            ) { editing = EditField.Name(profile?.name.orEmpty()) }

            ProfileRow(
                icon = R.drawable.alternate_email,
                label = "Usuario",
                value = profile?.username?.let { "@$it" },
                placeholder = "Elige tu @usuario",
            ) { editing = EditField.Username(profile?.username.orEmpty()) }

            ProfileRow(
                icon = R.drawable.info,
                label = "Info.",
                value = profile?.bio?.ifBlank { null },
                placeholder = "Completar sección Info.",
            ) { editing = EditField.Bio(profile?.bio.orEmpty()) }

            ProfileRow(
                icon = R.drawable.location_on,
                label = "Teléfono",
                value = localPhone.ifBlank { null },
                placeholder = "Agrega tu teléfono",
            ) { editing = EditField.Phone(localPhone) }

            ProfileRow(
                icon = R.drawable.social_invite,
                label = "Enlaces",
                value = profile?.links?.ifBlank { null },
                placeholder = "Agrega tus redes",
            ) { editing = EditField.Links(profile?.links.orEmpty()) }

            Spacer(Modifier.height(32.dp))
        }
    }

    editing?.let { field ->
        EditDialog(
            field = field,
            onDismiss = { editing = null },
            onSave = { newValue ->
                scope.launch {
                    // El resultado importa: antes se ignoraba y un fallo del
                    // servidor cerraba el diálogo como si se hubiera guardado.
                    val result = when (field) {
                        is EditField.Name -> SocialRepository.updateProfile(name = newValue)
                        is EditField.Bio -> SocialRepository.updateProfile(bio = newValue)
                        is EditField.Links -> SocialRepository.updateProfile(links = newValue)
                        is EditField.Username -> SocialRepository.setUsername(newValue.removePrefix("@"))
                        is EditField.Phone -> {
                            context.dataStore.edit { it[SocialMyPhoneKey] = newValue }
                            ContactsRepository.normalizePhone(newValue)?.let {
                                SocialRepository.setPhoneHash(ContactsRepository.hashPhone(it))
                            } ?: SocialResult.Success
                        }
                    }
                    reload()
                    editing = null
                    if (result is SocialResult.Error) {
                        snackbarHostState.showSnackbar("No se pudo guardar: ${result.message}")
                    }
                }
            },
        )
    }
}

private sealed interface EditField {
    val title: String
    val initial: String
    /** Pista bajo el campo: qué se espera escribir ahí. */
    val hint: String? get() = null
    /** Tope de caracteres; también alimenta el contador de los campos largos. */
    val maxLength: Int get() = 80
    data class Name(override val initial: String) : EditField {
        override val title = "Nombre"
        override val hint = "Así te verán los demás en Dezzy"
    }
    data class Username(override val initial: String) : EditField {
        override val title = "Usuario (@)"
        override val hint = "Solo letras, números y guion bajo"
        override val maxLength = 30
    }
    data class Bio(override val initial: String) : EditField {
        override val title = "Info."
        override val hint = "Cuenta qué escuchas"
        override val maxLength = 160
    }
    data class Phone(override val initial: String) : EditField {
        override val title = "Teléfono"
        override val hint = "Solo sirve para que te encuentren tus contactos"
    }
    data class Links(override val initial: String) : EditField { override val title = "Enlaces" }
}

@Composable
private fun EditDialog(field: EditField, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(field.initial) }
    val isBio = field is EditField.Bio
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(field.title) },
        text = {
            DezzyTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = field.title,
                supportingText = field.hint,
                singleLine = !isBio,
                minLines = if (isBio) 3 else 1,
                maxLength = field.maxLength,
                showCounter = isBio,
                keyboardOptions = when (field) {
                    is EditField.Phone -> KeyboardOptions(keyboardType = KeyboardType.Phone)
                    is EditField.Username -> KeyboardOptions(autoCorrectEnabled = false)
                    else -> KeyboardOptions.Default
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            // Sin cambios no hay nada que guardar: antes se podía pulsar "Guardar"
            // y disparar una escritura idéntica contra Supabase.
            TextButton(
                enabled = text.trim() != field.initial.trim(),
                onClick = { onSave(text.trim()) },
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun ProfileRow(
    icon: Int,
    label: String,
    value: String?,
    placeholder: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickableRow(onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = value ?: placeholder,
                fontSize = 14.sp,
                color = if (value != null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
