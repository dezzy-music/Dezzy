/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.dezzy.music.playback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EqProfile(
    val id: String,
    val name: String,
    val bandCenterFreqHz: List<Int> = emptyList(),
    val bandLevelsMb: List<Int> = emptyList(),
    val outputGainMb: Int = 0,
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
)

@Serializable
data class EqProfilesPayload(
    @SerialName("profiles")
    val profiles: List<EqProfile> = emptyList(),
)

data class EqCapabilities(
    val bandCount: Int,
    val minBandLevelMb: Int,
    val maxBandLevelMb: Int,
    val centerFreqHz: List<Int>,
    val systemPresets: List<String>,
)

data class EqSettings(
    val enabled: Boolean,
    val bandLevelsMb: List<Int>,
    val outputGainEnabled: Boolean,
    val outputGainMb: Int,
    val bassBoostEnabled: Boolean,
    val bassBoostStrength: Int,
    val virtualizerEnabled: Boolean,
    val virtualizerStrength: Int,
)

/**
 * Curvas de ecualización por género.
 *
 * Se definen como puntos (frecuencia Hz → ganancia dB) en vez de por índice de banda,
 * porque el número de bandas y sus frecuencias centrales varían según el dispositivo
 * (5 bandas en la mayoría, 10 en algunos). [bandLevelsForCurve] interpola la curva a
 * las bandas reales del ecualizador del equipo.
 *
 * Las claves coinciden con las de `GenreClassifier` para poder enlazar directamente.
 */
object GenreEqPresets {

    /** Puntos de la curva: frecuencia en Hz → ganancia en dB. */
    private val CURVES: Map<String, List<Pair<Int, Float>>> = mapOf(
        // Graves potentes y agudos presentes para el "brillo" del urbano
        "regueton" to listOf(60 to 5.5f, 230 to 2f, 910 to -0.5f, 3600 to 1.5f, 14000 to 3f),
        "dembow" to listOf(60 to 6f, 230 to 2.5f, 910 to -1f, 3600 to 1.5f, 14000 to 2.5f),
        "trap" to listOf(60 to 6f, 230 to 1.5f, 910 to -1.5f, 3600 to 1f, 14000 to 3f),
        "hiphop" to listOf(60 to 5f, 230 to 2f, 910 to -1f, 3600 to 1.5f, 14000 to 2f),
        // Percusión acústica y voces al frente
        "bachata" to listOf(60 to 2f, 230 to 1f, 910 to 1.5f, 3600 to 2.5f, 14000 to 2f),
        "salsa" to listOf(60 to 2.5f, 230 to 1f, 910 to 1.5f, 3600 to 2.5f, 14000 to 2.5f),
        "merengue" to listOf(60 to 2.5f, 230 to 1f, 910 to 1.5f, 3600 to 2.5f, 14000 to 2.5f),
        "cumbia" to listOf(60 to 3f, 230 to 1.5f, 910 to 1f, 3600 to 2f, 14000 to 1.5f),
        "vallenato" to listOf(60 to 2f, 230 to 1f, 910 to 2f, 3600 to 2.5f, 14000 to 2f),
        "regional" to listOf(60 to 2.5f, 230 to 1.5f, 910 to 2f, 3600 to 2f, 14000 to 1.5f),
        // Guitarras: medios marcados, sin exceso de sub-graves
        "rock" to listOf(60 to 3f, 230 to 0f, 910 to 1.5f, 3600 to 2.5f, 14000 to 2f),
        "metal" to listOf(60 to 3.5f, 230 to -1f, 910 to 2f, 3600 to 3f, 14000 to 2.5f),
        "indie" to listOf(60 to 1.5f, 230 to 0f, 910 to 1f, 3600 to 2f, 14000 to 2.5f),
        // Electrónica: curva en V clásica
        "electronica" to listOf(60 to 5.5f, 230 to 1f, 910 to -2f, 3600 to 2f, 14000 to 4f),
        // Voces protagonistas
        "balada" to listOf(60 to 1f, 230 to 0.5f, 910 to 2f, 3600 to 2.5f, 14000 to 1.5f),
        "rnb" to listOf(60 to 4f, 230 to 1.5f, 910 to 1f, 3600 to 2f, 14000 to 2f),
        "pop" to listOf(60 to 3f, 230 to 1f, 910 to 0.5f, 3600 to 2f, 14000 to 2.5f),
        "kpop" to listOf(60 to 3.5f, 230 to 1f, 910 to 0f, 3600 to 2.5f, 14000 to 3f),
        // Naturales / acústicos: casi planos, con aire arriba
        "jazz" to listOf(60 to 2f, 230 to 0.5f, 910 to 1f, 3600 to 1.5f, 14000 to 2f),
        "clasica" to listOf(60 to 1.5f, 230 to 0f, 910 to 0.5f, 3600 to 1f, 14000 to 2f),
        "country" to listOf(60 to 1.5f, 230 to 0.5f, 910 to 1.5f, 3600 to 2f, 14000 to 1.5f),
        "reggae" to listOf(60 to 4.5f, 230 to 1.5f, 910 to -0.5f, 3600 to 1.5f, 14000 to 1.5f),
    )

    fun hasCurve(genreKey: String?): Boolean = genreKey != null && CURVES.containsKey(genreKey)

    /**
     * Convierte la curva del género a niveles por banda (en milibelios), interpolando
     * en escala logarítmica de frecuencia y recortando al rango que soporta el equipo.
     */
    fun bandLevelsForCurve(
        genreKey: String,
        centerFreqHz: List<Int>,
        minMb: Int,
        maxMb: Int,
    ): List<Int>? {
        val curve = CURVES[genreKey] ?: return null
        if (centerFreqHz.isEmpty()) return null
        return centerFreqHz.map { freq ->
            val gainDb = interpolateDb(curve, freq.coerceAtLeast(1))
            (gainDb * 100f).toInt().coerceIn(minMb, maxMb)
        }
    }

    /** Interpolación lineal sobre log10(frecuencia); fuera de rango se extiende el extremo. */
    private fun interpolateDb(curve: List<Pair<Int, Float>>, freqHz: Int): Float {
        val x = kotlin.math.log10(freqHz.toDouble())
        val first = curve.first()
        val last = curve.last()
        if (freqHz <= first.first) return first.second
        if (freqHz >= last.first) return last.second
        for (i in 0 until curve.size - 1) {
            val (f0, g0) = curve[i]
            val (f1, g1) = curve[i + 1]
            if (freqHz in f0..f1) {
                val x0 = kotlin.math.log10(f0.toDouble())
                val x1 = kotlin.math.log10(f1.toDouble())
                if (x1 == x0) return g1
                val t = ((x - x0) / (x1 - x0)).toFloat()
                return g0 + (g1 - g0) * t
            }
        }
        return last.second
    }
}

internal object EqualizerJson {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
}

