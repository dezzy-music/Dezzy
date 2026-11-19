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

    /** Idioma de origen ML Kit a partir de lo detectado; las variantes romanizadas ("ja-Latn") no sirven. */
    private fun toMlKitSource(code: String?): String? =
        code?.takeUnless { it.contains("-Latn", ignoreCase = true) }?.let(::toMlKitLanguage)

    /**
     * Traduce [lines] al idioma [targetLanguageCode] en el dispositivo,
     * preservando el mapeo 1:1 línea a línea.
     *
     * Las letras mezclan idiomas a menudo (intro en inglés, estribillo en
     * español…): cada línea larga se traduce desde su propio idioma y el resto
     * desde el idioma dominante de toda la letra. Si nada cambia se devuelve un
     * error, para que no se guarde como "traducción" la letra original.
     */
    suspend fun translateLines(
        lines: List<String>,
        targetLanguageCode: String,
    ): Result<List<String>> {
        if (lines.isEmpty()) return Result.success(emptyList())

        val target = toMlKitLanguage(targetLanguageCode)
            ?: return Result.failure(Exception("Idioma de destino no soportado offline: $targetLanguageCode"))

        val textLines = lines.filter { it.isNotBlank() }
        val dominant = toMlKitSource(
            LanguageDetectionHelper.identifyLanguage(textLines.take(40).joinToString("\n")),
        ) ?: TranslateLanguage.ENGLISH

        val sources = lines.map { line ->
            when {
                line.isBlank() -> null
                line.trim().length >= PER_LINE_MIN_CHARS ->
                    toMlKitSource(LanguageDetectionHelper.identifyLanguage(line)) ?: dominant
                else -> dominant
            }
        }
        if (sources.all { it == null || it == target }) {
            return Result.failure(Exception("La letra ya está en ese idioma"))
        }

        val translators = HashMap<String, com.google.mlkit.nl.translate.Translator?>()
        var lastError: String? = null

        /** Traductor listo (con su modelo) para [source], o null si no se puede usar. */
        suspend fun translatorFor(source: String): com.google.mlkit.nl.translate.Translator? {
            if (translators.containsKey(source)) return translators[source]
            val translator = runCatching {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(target)
                        .build(),
                )
            }.getOrElse {
                lastError = "Traducción offline no disponible en este dispositivo (Google Play Services)"
                null
            }
            val ready = translator != null && runCatching {
                // Descarga bajo demanda el modelo que falte (origen o destino) si hay red.
                suspendCancellableCoroutine<Boolean> { cont ->
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                        .addOnSuccessListener { cont.resume(true) }
                        .addOnFailureListener { e ->
                            Timber.w(e, "Modelo ML Kit no disponible: $source -> $target")
                            cont.resume(false)
                        }
                }
            }.getOrDefault(false)
            if (translator != null && !ready) {
                runCatching { translator.close() }
                lastError = "Falta el modelo de ${java.util.Locale.forLanguageTag(source).displayLanguage} " +
                    "para traducir sin conexión. Conéctate una vez o descárgalo en Ajustes → Traducción de letras."
            }
            return (if (ready) translator else null).also { translators[source] = it }
        }

        return try {
            var changed = 0
            val out = ArrayList<String>(lines.size)
            for ((i, line) in lines.withIndex()) {
                val source = sources[i]
                if (source == null || source == target) {
                    out.add(line)
                    continue
                }
                // Si no hay modelo para el idioma de la línea, se prueba con el dominante.
                val translator = translatorFor(source)
                    ?: dominant.takeIf { it != source && it != target }?.let { translatorFor(it) }
                if (translator == null) {
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
                if (!translated.isNullOrBlank() && translated.trim() != line.trim()) changed++
                out.add(translated?.takeIf { it.isNotBlank() } ?: line)
            }
            if (changed == 0) {
                Result.failure(Exception(lastError ?: "No se pudo traducir la letra sin conexión"))
            } else {
                Result.success(out)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        } finally {
            translators.values.forEach { t -> runCatching { t?.close() } }
        }
    }

    private const val PER_LINE_MIN_CHARS = 24
}
