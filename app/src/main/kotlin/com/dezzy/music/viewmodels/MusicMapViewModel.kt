/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dezzy.music.innertube.YouTube
import com.dezzy.music.innertube.pages.ChartsPage
import com.dezzy.music.ui.screens.CountryMusicProfile
import com.dezzy.music.ui.screens.WorldMapData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MusicMapViewModel @Inject constructor() : ViewModel() {

    private val _selectedCountry = MutableStateFlow<CountryMusicProfile?>(null)
    val selectedCountry = _selectedCountry.asStateFlow()

    private val _countryCharts = MutableStateFlow<ChartsPage?>(null)
    val countryCharts = _countryCharts.asStateFlow()

    private val _isLoadingCharts = MutableStateFlow(false)
    val isLoadingCharts = _isLoadingCharts.asStateFlow()

    private val _chartsError = MutableStateFlow<String?>(null)
    val chartsError = _chartsError.asStateFlow()

    // Simple in-memory cache to avoid repeated network requests
    private val chartsCache = mutableMapOf<String, ChartsPage>()

    fun selectCountry(profile: CountryMusicProfile) {
        if (_selectedCountry.value?.countryCode == profile.countryCode) {
            // Deselect if tapping the same country
            _selectedCountry.value = null
            _countryCharts.value = null
            return
        }
        _selectedCountry.value = profile
        loadChartsForCountry(profile.countryCode)
    }

    fun deselectCountry() {
        _selectedCountry.value = null
        _countryCharts.value = null
        _chartsError.value = null
    }

    private fun loadChartsForCountry(countryCode: String) {
        // Return cached data immediately
        chartsCache[countryCode]?.let {
            _countryCharts.value = it
            return
        }

        viewModelScope.launch {
            _isLoadingCharts.value = true
            _chartsError.value = null
            _countryCharts.value = null

            // Charts are now region-specific: the country code is forwarded to
            // InnerTube as the gl (geolocation) override.
            YouTube.getChartsPage(countryCode = countryCode)
                .onSuccess { page ->
                    chartsCache[countryCode] = page
                    _countryCharts.value = page
                }
                .onFailure {
                    _chartsError.value = "No hay charts disponibles para esta región"
                }

            _isLoadingCharts.value = false
        }
    }

    fun getCountryAtMapPosition(x: Float, y: Float): CountryMusicProfile? {
        // Find the closest country dot to the tapped position
        // Uses a small hit radius threshold
        val hitRadius = 0.025f
        return WorldMapData.countries.minByOrNull { country ->
            val dx = country.mapX - x
            val dy = country.mapY - y
            dx * dx + dy * dy
        }?.takeIf { country ->
            val dx = country.mapX - x
            val dy = country.mapY - y
            (dx * dx + dy * dy) < (hitRadius * hitRadius)
        }
    }
}
