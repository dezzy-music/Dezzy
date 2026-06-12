/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.arturo254.opentune.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.arturo254.opentune.R

// Define M3 Typography based on Material Design guidelines
// https://m3.material.io/styles/typography/type-scale-tokens
private fun buildTypography(fontFamily: FontFamily) =
    Typography(
        displayLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 57.sp,
            lineHeight = 64.sp,
            letterSpacing = (-0.25).sp
        ),
        displayMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 45.sp,
            lineHeight = 52.sp,
            letterSpacing = 0.sp
        ),
        displaySmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 36.sp,
            lineHeight = 44.sp,
            letterSpacing = 0.sp
        ),
        headlineLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            letterSpacing = 0.sp
        ),
        headlineMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 28.sp,
            lineHeight = 36.sp,
            letterSpacing = 0.sp
        ),
        headlineSmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            letterSpacing = 0.sp
        ),
        titleLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp
        ),
        titleMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.15.sp
        ),
        titleSmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp
        ),
        bodyLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.5.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.25.sp
        ),
        bodySmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.4.sp
        ),
        labelLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp
        ),
        labelMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp
        ),
        labelSmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp
        )
    )

// All fonts are bundled locally as TTF files in res/font/
// This guarantees they work offline, instantly, without Google Play Services.

private val AppFontFamily = FontFamily(Font(R.font.poppins))
val AppTypography = buildTypography(AppFontFamily)
val SystemTypography = buildTypography(FontFamily.Default)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun createVariableFontFamily(resId: Int): FontFamily {
    return try {
        FontFamily(
            Font(resId, weight = FontWeight.Normal, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(400))),
            Font(resId, weight = FontWeight.Medium, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(500))),
            Font(resId, weight = FontWeight.SemiBold, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(600))),
            Font(resId, weight = FontWeight.Bold, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(700)))
        )
    } catch (e: Exception) {
        FontFamily.Default
    }
}

fun getTypography(fontName: String): Typography {
    val fontFamily = when (fontName.uppercase()) {
        "POPPINS"          -> FontFamily(Font(R.font.poppins))
        "SF_PRO"           -> FontFamily(Font(R.font.sfprodisplaybold))
        "SERIF"            -> FontFamily.Serif
        "MONOSPACE"        -> FontFamily.Monospace
        "SYSTEM"           -> FontFamily.Default
        // Bundled Google Fonts (OFL license) — work offline, no GMS dependency
        "INTER"            -> createVariableFontFamily(R.font.inter)
        "ROBOTO"           -> createVariableFontFamily(R.font.roboto)
        "MONTSERRAT"       -> createVariableFontFamily(R.font.montserrat)
        "LORA"             -> createVariableFontFamily(R.font.lora)
        "PLAYFAIR_DISPLAY" -> createVariableFontFamily(R.font.playfair_display)
        "NUNITO"           -> createVariableFontFamily(R.font.nunito)
        "RUBIK"            -> createVariableFontFamily(R.font.rubik)
        "KANIT"            -> createVariableFontFamily(R.font.kanit)
        "COMFORTAA"        -> createVariableFontFamily(R.font.comfortaa)
        "PACIFICO"         -> createVariableFontFamily(R.font.pacifico)
        "LOBSTER"          -> createVariableFontFamily(R.font.lobster)
        "DANCING_SCRIPT"   -> createVariableFontFamily(R.font.dancing_script)
        "CAVEAT"           -> createVariableFontFamily(R.font.caveat)
        "GREAT_VIBES"      -> createVariableFontFamily(R.font.great_vibes)
        "CINZEL"           -> createVariableFontFamily(R.font.cinzel)
        "OUTFIT"           -> createVariableFontFamily(R.font.outfit)
        "SYNE"             -> createVariableFontFamily(R.font.syne)
        "SPACE_GROTESK"    -> createVariableFontFamily(R.font.space_grotesk)
        "MERRIWEATHER"     -> createVariableFontFamily(R.font.merriweather)
        "OSWALD"           -> createVariableFontFamily(R.font.oswald)
        else               -> FontFamily.Default
    }
    return buildTypography(fontFamily)
}
fun getTypeface(context: Context, fontName: String): Typeface {
    val resId = when (fontName.uppercase()) {
        "POPPINS"          -> R.font.poppins
        "SF_PRO"           -> R.font.sfprodisplaybold
        "INTER"            -> R.font.inter
        "ROBOTO"           -> R.font.roboto
        "MONTSERRAT"       -> R.font.montserrat
        "LORA"             -> R.font.lora
        "PLAYFAIR_DISPLAY" -> R.font.playfair_display
        "NUNITO"           -> R.font.nunito
        "RUBIK"            -> R.font.rubik
        "KANIT"            -> R.font.kanit
        "COMFORTAA"        -> R.font.comfortaa
        "PACIFICO"         -> R.font.pacifico
        "LOBSTER"          -> R.font.lobster
        "DANCING_SCRIPT"   -> R.font.dancing_script
        "CAVEAT"           -> R.font.caveat
        "GREAT_VIBES"      -> R.font.great_vibes
        "CINZEL"           -> R.font.cinzel
        "OUTFIT"           -> R.font.outfit
        "SYNE"             -> R.font.syne
        "SPACE_GROTESK"    -> R.font.space_grotesk
        "MERRIWEATHER"     -> R.font.merriweather
        "OSWALD"           -> R.font.oswald
        else               -> null
    }
    
    if (resId != null) {
        try {
            return ResourcesCompat.getFont(context, resId) ?: Typeface.DEFAULT
        } catch (e: Exception) {
            return Typeface.DEFAULT
        }
    }
    
    return when (fontName.uppercase()) {
        "SERIF"     -> Typeface.SERIF
        "MONOSPACE" -> Typeface.MONOSPACE
        else        -> Typeface.DEFAULT
    }
}
