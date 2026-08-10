/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.lyrics

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Traducción de letras 100% on-device usando ML Kit — sin IA, sin API key y
 * sin conexión una vez descargado el modelo. Cada modelo de idioma pesa ~30 MB
 * y se gestiona desde Ajustes → Traducción de Letras.
 *
 * ML Kit traduce pivotando por inglés: para "en → es" basta el modelo de
 * español. Para letras en otros idiomas de origen, ML Kit descarga ese modelo
 * adicional bajo demanda la primera vez.
 */
object OfflineTranslationService {

    private val modelManager get() = RemoteModelManager.getInstance()

    /** Convierte un código ("es", "en-US"…) al idioma ML Kit, o null si no está soportado. */
    fun toMlKitLanguage(code: String?): String? =
        code?.takeIf { it.isNotBlank() }
            ?.let { TranslateLanguage.fromLanguageTag(it.substringBefore('-')) }

    // NOTA: en algunos dispositivos (p. ej. POCO con GMS restringido) la
    // inicialización de ML Kit lanza NPE SÍNCRONO desde Google Play Services
    // (GoogleSignatureVerifier) — los addOnFailureListener no lo atrapan.
    // Por eso TODO acceso a ML Kit va envuelto en runCatching.

    suspend fun isModelDownloaded(languageCode: String): Boolean {
        val lang = toMlKitLanguage(languageCode) ?: return false
        return runCatching {
            val model = TranslateRemoteModel.Builder(lang).build()
            suspendCancellableCoroutine { cont ->
                modelManager.isModelDownloaded(model)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(false) }
            }
        }.getOrElse {
            Timber.w(it, "ML Kit no disponible en este dispositivo")
            false
        }
    }

    suspend fun downloadModel(languageCode: String): Result<Unit> {
        val lang = toMlKitLanguage(languageCode)
            ?: return Result.failure(Exception("Idioma no soportado offline: $languageCode"))
        return runCatching {
            val model = TranslateRemoteModel.Builder(lang).build()
            suspendCancellableCoroutine<Result<Unit>> { cont ->
                modelManager.download(model, DownloadConditions.Builder().build())
                    .addOnSuccessListener { cont.resume(Result.success(Unit)) }
                    .addOnFailureListener { cont.resume(Result.failure(it)) }
            }
        }.getOrElse {
            Result.failure(Exception("Traducción offline no disponible (Google Play Services): ${it.message}"))
        }
    }

    suspend fun deleteModel(languageCode: String): Result<Unit> {
        val lang = toMlKitLanguage(languageCode)
            ?: return Result.failure(Exception("Idioma no soportado offline: $languageCode"))
        return runCatching {
            val model = TranslateRemoteModel.Builder(lang).build()
            suspendCancellableCoroutine<Result<Unit>> { cont ->
                modelManager.deleteDownloadedModel(model)
                    .addOnSuccessListener { cont.resume(Result.success(Unit)) }
                    .addOnFailureListener { cont.resume(Result.failure(it)) }
            }
        }.getOrElse {
            Result.failure(Exception("Traducción offline no disponible (Google Play Services): ${it.message}"))
        }
    }

    /**
     * Traduce [lines] al idioma [targetLanguageCode] en el dispositivo,
     * preservando el mapeo 1:1 línea a línea. Detecta el idioma de origen con
     * ML Kit language-id; si no puede, asume inglés (el caso abrumadoramente
     * común en música).
     */
    suspend fun translateLines(
        lines: List<String>,
        targetLanguageCode: String,
    ): Result<List<String>> {
        if (lines.isEmpty()) return Result.success(emptyList())

        val target = toMlKitLanguage(targetLanguageCode)
            ?: return Result.failure(Exception("Idioma de destino no soportado offline: $targetLanguageCode"))

        val sample = lines.filter { it.isNotBlank() }.take(8).joinToString(" ")
        val detected = LanguageDetectionHelper.identifyLanguage(sample)
        val source = toMlKitLanguage(detected) ?: TranslateLanguage.ENGLISH

        if (source == target) {
            // La letra ya está en el idioma de destino: nada que traducir.
            return Result.success(lines)
        }

        val translator = runCatching {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build(),
            )
        }.getOrElse {
            return Result.failure(
                Exception("Traducción offline no disponible en este dispositivo (Google Play Services)"),
            )
        }
        return try {
            // Si falta el modelo del idioma de origen lo descarga bajo demanda;
            // el de destino ya debería estar descargado desde Ajustes.
            val ready = suspendCancellableCoroutine<Result<Unit>> { cont ->
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener { cont.resume(Result.success(Unit)) }
                    .addOnFailureListener { cont.resume(Result.failure(it)) }
            }
            ready.exceptionOrNull()?.let {
                return Result.failure(Exception("Modelo offline no disponible: ${it.message}"))
            }

            val out = ArrayList<String>(lines.size)
            for (line in lines) {
                if (line.isBlank()) {
                    out.add(line)
                    continue
                }
                val translated = suspendCancellableCoroutine<String?> { cont ->
                    translator.translate(line)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { e ->
                            Timber.e(e, "Offline translation failed for a line")
                            cont.resume(null)
                        }
                }
                out.add(translated ?: line)
            }
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { translator.close() }
        }
    }
}
