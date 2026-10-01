package com.ikko.aura.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ikko.aura.R

/**
 * Plus Jakarta Sans, under the SIL Open Font License (see assets/licenses), as one variable file with the
 * weights the app uses cut from it. It replaces SF Pro, whose licence does not allow it to be shipped
 * inside an Android app.
 *
 * The file goes up to 800: [FontWeight.Black] is asked for in places and gets 800, the heaviest there is.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val AuraFontFamily = FontFamily(
    listOf(
        FontWeight.Normal to 400,
        FontWeight.Medium to 500,
        FontWeight.SemiBold to 600,
        FontWeight.Bold to 700,
        FontWeight.ExtraBold to 800,
        FontWeight.Black to 800,
    ).map { (weight, value) ->
        Font(
            R.font.plus_jakarta_sans,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(value)),
        )
    },
)

/** Every digit the same width, so a duration or a count does not shimmer as it changes. */
const val TABULAR_NUMBERS = "tnum"

val AuraTypography = Typography(
    displayLarge = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp),
    displayMedium = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineLarge = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 33.sp),
    headlineMedium = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 29.sp),
    titleLarge = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp),
    titleSmall = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp),
    titleMedium = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp),
    labelMedium = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp),
)

/**
 * The library's own type, one definition each, in Plus Jakarta Sans. Sizes are set so the capitals stand
 * as tall as the previous face's did, which is why they are not the old sizes.
 */
object LibraryTypography {
    val PageTitle = TextStyle(
        fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    )
    val PageCount = TextStyle(
        fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp,
        fontFeatureSettings = TABULAR_NUMBERS,
    )
    val AlbumTitle = TextStyle(
        fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = 15.5.sp, lineHeight = 19.sp,
    )
    val AlbumArtist = TextStyle(
        fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 17.sp,
    )
    val CapsuleLabel = TextStyle(
        fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, lineHeight = 16.sp,
    )
    val CapsuleLabelActive = CapsuleLabel.copy(fontWeight = FontWeight.Bold)
    val PlayerTitle = TextStyle(
        fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, lineHeight = 17.sp,
    )
    val PlayerArtist = TextStyle(
        fontFamily = AuraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 14.5.sp,
    )
    val IndexLetter = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.Bold, fontSize = 9.sp)
    val IndexLetterCurrent = TextStyle(fontFamily = AuraFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 10.5.sp)
}
