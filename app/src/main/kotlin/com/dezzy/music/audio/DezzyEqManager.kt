package com.dezzy.music.audio

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dezzy.music.audio.models.EqBand
import com.dezzy.music.audio.models.FilterType
import com.dezzy.music.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object DezzyEqManager {
    val bands = MutableStateFlow(createDefaultBands())
    val isEqEnabled = MutableStateFlow(true)
    
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val BANDS_KEY = stringPreferencesKey("parametric_eq_bands")
    private val ENABLED_KEY = booleanPreferencesKey("parametric_eq_enabled")

    fun initAndLoad(context: Context) {
        scope.launch {
            try {
                // Load enabled state
                val enabled = context.dataStore.data.map { it[ENABLED_KEY] ?: true }.first()
                isEqEnabled.value = enabled

                // Load bands state
                val bandsJson = context.dataStore.data.map { it[BANDS_KEY] }.first()
                if (!bandsJson.isNullOrEmpty()) {
                    try {
                        val loadedBands = Json.decodeFromString<List<EqBand>>(bandsJson)
                        if (loadedBands.isNotEmpty()) {
                            bands.value = loadedBands
                        }
                    } catch (_: Exception) { /* use defaults */ }
                }
            } catch (_: Exception) { /* use defaults on any DataStore failure */ }

            // Observe changes and save automatically (never let save errors crash)
            launch {
                bands.collectLatest { currentBands ->
                    try {
                        val json = Json.encodeToString(currentBands)
                        context.dataStore.edit { it[BANDS_KEY] = json }
                    } catch (_: Exception) {}
                }
            }
            launch {
                isEqEnabled.collectLatest { enabled ->
                    try {
                        context.dataStore.edit { it[ENABLED_KEY] = enabled }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun updateFromParametric(enabled: Boolean, newBands: List<EqBand>) {
        isEqEnabled.value = enabled
        if (newBands.isNotEmpty()) {
            bands.value = newBands
        }
    }

    fun updateFromSystemSettings(enabled: Boolean, levelsMb: List<Int>) {
        isEqEnabled.value = enabled
        
        // 10 bands: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k
        val defaultFrequencies = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        
        val newBands = levelsMb.mapIndexed { index, levelMb ->
            val freq = defaultFrequencies.getOrElse(index) { 1000f }
            val gainDb = (levelMb / 100f).coerceIn(-24f, 24f)
            val type = if (index == 0) FilterType.LOW_SHELF else if (index == levelsMb.lastIndex) FilterType.HIGH_SHELF else FilterType.PEAKING
            
            EqBand(
                id = index,
                type = type,
                frequencyHz = freq,
                gainDb = gainDb,
                qFactor = 0.707f
            )
        }
        
        if (newBands.isNotEmpty()) {
            bands.value = newBands
        }
    }

    private fun createDefaultBands(): List<EqBand> {
        val defaultFrequencies = listOf(
            20f, 31f, 50f, 80f, 125f, 200f, 315f, 500f, 800f, 1250f,
            2000f, 3150f, 5000f, 8000f, 10000f, 12500f, 14000f, 16000f, 18000f, 20000f
        )
        return defaultFrequencies.mapIndexed { index, freq ->
            EqBand(
                id = index,
                type = if (index == 0) FilterType.LOW_SHELF else if (index == 19) FilterType.HIGH_SHELF else FilterType.PEAKING,
                frequencyHz = freq,
                gainDb = 0f,
                qFactor = 0.707f
            )
        }
    }
}
