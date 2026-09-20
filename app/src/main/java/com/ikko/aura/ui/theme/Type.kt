package com.ikko.aura.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ikko.aura.R

/**
 * SF Pro Display, at the user's explicit request, to match BitChord — which bundles these same
 * five weights.
 *
 * Worth knowing if this app is ever distributed: Apple licenses SF Pro for designing and
 * developing interfaces *for Apple platforms*, so shipping it inside an Android app falls outside
 * that grant. It is here on the understanding that Aura is a personal, non-distributed build —
 * swap this family for an OFL face (Inter is the closest match) before any public release.
 */
val AuraFontFamily = FontFamily(
    Font(R.font.sf_pro_display_regular, FontWeight.Normal),
    Font(R.font.sf_pro_display_medium, FontWeight.Medium),
    Font(R.font.sf_pro_display_semibold, FontWeight.SemiBold),
    Font(R.font.sf_pro_display_bold, FontWeight.Bold),
    Font(R.font.sf_pro_display_heavy, FontWeight.Black),
)

val AuraTypography = Typography(
    displayLarge = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp),
    displayMedium = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineLarge = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 33.sp),
    headlineMedium = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 29.sp),
    titleLarge = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp),
    labelMedium = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp),
)
