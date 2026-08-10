/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.ui.screens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class CountryMusicProfile(
    val countryCode: String,
    val countryName: String,
    val emoji: String,
    val dominantGenres: List<String>,
    val accentColor: Color,
    val artistHighlight: String? = null,
    // Normalized map coordinates (0f..1f) for dot placement on the world map
    val mapX: Float,
    val mapY: Float,
)

object WorldMapData {

    val countries: List<CountryMusicProfile> = listOf(
        CountryMusicProfile("US", "United States", "🇺🇸", listOf("Pop", "Hip-Hop", "R&B", "Country"),
            Color(0xFF4A90E2), "Taylor Swift", 0.155f, 0.33f),
        CountryMusicProfile("MX", "México", "🇲🇽", listOf("Regional Mexicano", "Pop Latino", "Banda"),
            Color(0xFF27AE60), "Bad Bunny", 0.19f, 0.43f),
        CountryMusicProfile("BR", "Brasil", "🇧🇷", listOf("Funk Carioca", "Sertanejo", "MPB", "Pagode"),
            Color(0xFFF39C12), "Anitta", 0.30f, 0.60f),
        CountryMusicProfile("GB", "United Kingdom", "🇬🇧", listOf("Pop", "Grime", "Indie Rock"),
            Color(0xFF8E44AD), "Adele", 0.435f, 0.22f),
        CountryMusicProfile("DE", "Germany", "🇩🇪", listOf("Electronic", "Pop", "Schlager"),
            Color(0xFF2C3E50), "Rammstein", 0.48f, 0.24f),
        CountryMusicProfile("FR", "France", "🇫🇷", listOf("French Pop", "Electronic", "Hip-Hop"),
            Color(0xFF2980B9), "David Guetta", 0.455f, 0.26f),
        CountryMusicProfile("ES", "España", "🇪🇸", listOf("Flamenco", "Reggaeton", "Pop Latino"),
            Color(0xFFE74C3C), "Rosalía", 0.43f, 0.29f),
        CountryMusicProfile("IT", "Italia", "🇮🇹", listOf("Pop Italiano", "Opera", "Electronic"),
            Color(0xFF27AE60), "Måneskin", 0.475f, 0.28f),
        CountryMusicProfile("JP", "Japan", "🇯🇵", listOf("J-Pop", "Anime OST", "City Pop"),
            Color(0xFFE74C3C), "YOASOBI", 0.815f, 0.33f),
        CountryMusicProfile("KR", "South Korea", "🇰🇷", listOf("K-Pop", "K-Hip-Hop", "Ballad"),
            Color(0xFF3498DB), "BTS", 0.795f, 0.35f),
        CountryMusicProfile("IN", "India", "🇮🇳", listOf("Bollywood", "Punjabi Pop", "Classical"),
            Color(0xFFFF6B35), "Arijit Singh", 0.66f, 0.38f),
        CountryMusicProfile("AU", "Australia", "🇦🇺", listOf("Pop", "Rock", "Electronic"),
            Color(0xFFFFBE0B), "Sia", 0.84f, 0.68f),
        CountryMusicProfile("CA", "Canada", "🇨🇦", listOf("Pop", "Hip-Hop", "Indie"),
            Color(0xFFE74C3C), "Drake", 0.175f, 0.25f),
        CountryMusicProfile("AR", "Argentina", "🇦🇷", listOf("Tango", "Rock Nacional", "Cumbia"),
            Color(0xFF74B9FF), "Bizarrap", 0.265f, 0.74f),
        CountryMusicProfile("CO", "Colombia", "🇨🇴", listOf("Vallenato", "Salsa", "Reggaeton"),
            Color(0xFFFDCB6E), "J Balvin", 0.245f, 0.53f),
        CountryMusicProfile("CL", "Chile", "🇨🇱", listOf("Pop", "Rock", "Cumbia"),
            Color(0xFFD63031), "Mon Laferte", 0.245f, 0.70f),
        CountryMusicProfile("SE", "Sweden", "🇸🇪", listOf("Pop", "Electronic", "Metal"),
            Color(0xFF0984E3), "ABBA", 0.48f, 0.18f),
        CountryMusicProfile("NG", "Nigeria", "🇳🇬", listOf("Afrobeats", "Afropop", "Highlife"),
            Color(0xFF00B894), "Burna Boy", 0.47f, 0.48f),
        CountryMusicProfile("ZA", "South Africa", "🇿🇦", listOf("Amapiano", "Kwaito", "Afropop"),
            Color(0xFFFDCB6E), "Black Coffee", 0.525f, 0.63f),
        CountryMusicProfile("TR", "Turkey", "🇹🇷", listOf("Turkish Pop", "Arabesk", "Electronic"),
            Color(0xFFE17055), "Tarkan", 0.555f, 0.30f),
        CountryMusicProfile("PL", "Poland", "🇵🇱", listOf("Pop", "Hip-Hop", "Electronic"),
            Color(0xFFFF7675), "Kayah", 0.51f, 0.23f),
        CountryMusicProfile("PT", "Portugal", "🇵🇹", listOf("Fado", "Pop", "Electronic"),
            Color(0xFF6C5CE7), "Salvador Sobral", 0.415f, 0.29f),
        CountryMusicProfile("ID", "Indonesia", "🇮🇩", listOf("Dangdut", "Pop", "K-Pop influenced"),
            Color(0xFFE17055), "Agnez Mo", 0.775f, 0.52f),
        CountryMusicProfile("PH", "Philippines", "🇵🇭", listOf("OPM", "Pop", "BPop"),
            Color(0xFF0984E3), "Ben&Ben", 0.805f, 0.44f),
        CountryMusicProfile("TH", "Thailand", "🇹🇭", listOf("T-Pop", "Luk Thung", "Electronic"),
            Color(0xFF6C5CE7), "Billkin", 0.752f, 0.42f),
        CountryMusicProfile("RU", "Russia", "🇷🇺", listOf("Pop", "Electronic", "Rock"),
            Color(0xFF74B9FF), "Monetochka", 0.59f, 0.20f),
        CountryMusicProfile("NL", "Netherlands", "🇳🇱", listOf("Electronic", "Pop", "Drum and Bass"),
            Color(0xFFFF7675), "Tiësto", 0.46f, 0.22f),
        CountryMusicProfile("SA", "Saudi Arabia", "🇸🇦", listOf("Khaliji", "Arabic Pop", "Maqam"),
            Color(0xFF00B894), "Mohammed Abdo", 0.59f, 0.37f),
        CountryMusicProfile("EG", "Egypt", "🇪🇬", listOf("Shaabi", "Arabic Pop", "Classical Arabic"),
            Color(0xFFFDCB6E), "Amr Diab", 0.535f, 0.36f),
        CountryMusicProfile("GH", "Ghana", "🇬🇭", listOf("Highlife", "Hiplife", "Afrobeats"),
            Color(0xFF55EFC4), "Sarkodie", 0.455f, 0.48f),
        CountryMusicProfile("VE", "Venezuela", "🇻🇪", listOf("Salsa", "Reggaeton", "Vallenato"),
            Color(0xFF74B9FF), "Nacho", 0.255f, 0.50f),
        CountryMusicProfile("PE", "Perú", "🇵🇪", listOf("Cumbia", "Chicha", "Salsa"),
            Color(0xFFFF7675), "Gian Marco", 0.24f, 0.60f),
        CountryMusicProfile("CN", "China", "🇨🇳", listOf("C-Pop", "Mandopop", "Traditional"),
            Color(0xFFE17055), "Jay Chou", 0.765f, 0.32f),
        CountryMusicProfile("MY", "Malaysia", "🇲🇾", listOf("Malay Pop", "R&B", "Electronic"),
            Color(0xFF0984E3), "Yuna", 0.762f, 0.47f),
        CountryMusicProfile("VN", "Vietnam", "🇻🇳", listOf("V-Pop", "Bolero", "EDM"),
            Color(0xFFE17055), "Sơn Tùng M-TP", 0.758f, 0.44f),
    )

    val countryByCode: Map<String, CountryMusicProfile> =
        countries.associateBy { it.countryCode }

    // YouTube Music browse IDs for country-specific charts
    // Format: countryCode -> browseId suffix used by YouTube Charts
    val countryChartIds: Map<String, String> = mapOf(
        "US" to "us",
        "MX" to "mx",
        "BR" to "br",
        "GB" to "gb",
        "DE" to "de",
        "FR" to "fr",
        "ES" to "es",
        "IT" to "it",
        "JP" to "jp",
        "KR" to "kr",
        "IN" to "in",
        "AU" to "au",
        "CA" to "ca",
        "AR" to "ar",
        "CO" to "co",
        "CL" to "cl",
        "SE" to "se",
        "NG" to "ng",
        "ZA" to "za",
        "TR" to "tr",
        "PL" to "pl",
        "PT" to "pt",
        "ID" to "id",
        "PH" to "ph",
        "TH" to "th",
        "RU" to "ru",
        "NL" to "nl",
        "SA" to "sa",
        "EG" to "eg",
        "GH" to "gh",
        "VE" to "ve",
        "PE" to "pe",
        "CN" to "cn",
        "MY" to "my",
        "VN" to "vn",
    )
}
