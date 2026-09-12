package com.itantra.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The Stitch design system, transcribed exactly.
 *
 * Every colour below is copied from the project's own token map
 * (`iTantra UI/UX Design System`, 47 tokens) rather than eyeballed from a screenshot, so
 * the app and the design cannot drift apart by a shade.
 *
 * ## Light only, deliberately
 *
 * The design specifies a single light scheme and no dark counterpart. Rather than invent
 * one — a derived dark palette would be a guess, and the urgency colours below carry
 * meaning that a guess could quietly break — the app pins the light scheme regardless of
 * the system setting. It also suits the use: a bright screen is easier to read outdoors,
 * which is where this gets used.
 *
 * ## Where the design's own palette is not enough
 *
 * Stitch gives one `error` red. This app needs **three** distinguishable urgency levels,
 * and the design's screens show them as blue / amber / red. So [UrgencyWarning] is added
 * here, chosen to stay separable from the other two under the common forms of colour
 * blindness — and, as elsewhere, colour is never the only signal: each level also has its
 * own Material Symbol and its own size on screen.
 */

// --- Stitch tokens, verbatim -----------------------------------------------------------

private val Primary = Color(0xFF0051B6)
private val OnPrimary = Color(0xFFFFFFFF)
private val PrimaryContainer = Color(0xFF1769E0)
private val OnPrimaryContainer = Color(0xFFF0F2FF)
private val PrimaryFixed = Color(0xFFD9E2FF)
private val PrimaryFixedDim = Color(0xFFAFC6FF)
private val OnPrimaryFixed = Color(0xFF001A43)
private val OnPrimaryFixedVariant = Color(0xFF004398)

private val Secondary = Color(0xFF005BBF)
private val OnSecondary = Color(0xFFFFFFFF)
private val SecondaryContainer = Color(0xFF5694FE)
private val OnSecondaryContainer = Color(0xFF002D64)
private val SecondaryFixed = Color(0xFFD7E2FF)

private val Tertiary = Color(0xFF00633C)
private val OnTertiary = Color(0xFFFFFFFF)
private val TertiaryContainer = Color(0xFF007F4E)
private val OnTertiaryContainer = Color(0xFFCAFFDB)
private val TertiaryFixed = Color(0xFF81FAB6)

private val ErrorRed = Color(0xFFBA1A1A)
private val OnError = Color(0xFFFFFFFF)
private val ErrorContainer = Color(0xFFFFDAD6)
private val OnErrorContainer = Color(0xFF93000A)

private val Background = Color(0xFFF9F9FF)
private val OnBackground = Color(0xFF121B2E)
private val SurfaceVariant = Color(0xFFD9E2FC)
private val OnSurfaceVariant = Color(0xFF424754)
private val Outline = Color(0xFF727785)
private val OutlineVariant = Color(0xFFC2C6D6)
private val InverseSurface = Color(0xFF273044)
private val InverseOnSurface = Color(0xFFEDF0FF)
private val InversePrimary = Color(0xFFAFC6FF)

/** The five surface-container steps. Depth in this design comes from these, not shadows. */
val SurfaceLowest = Color(0xFFFFFFFF)
val SurfaceLow = Color(0xFFF1F3FF)
val SurfaceContainer = Color(0xFFE9EDFF)
val SurfaceHigh = Color(0xFFE1E8FF)
val SurfaceHighest = Color(0xFFD9E2FC)
val SurfaceDim = Color(0xFFD1DAF4)

// --- exported meaning-bearing colours --------------------------------------------------

/** Ready / connected / "offline ready". The design's green. */
val StatusReady = TertiaryContainer
val StatusReadyContainer = OnTertiaryContainer
val OnStatusReady = Color(0xFF002111)

/** Routine urgency. */
val UrgencyRoutine = Primary

/**
 * Warning urgency — **not** from the Stitch token map, which has no amber.
 *
 * Added because three urgency levels need three separable colours and the design's screens
 * show an amber middle step. Chosen to hold 4.5:1 on white and to stay distinct from both
 * the blue and the red under deuteranopia and protanopia.
 */
val UrgencyWarning = Color(0xFFB45309)

/** Emergency urgency. The design's error red. */
val UrgencyEmergency = ErrorRed

val StitchScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    inversePrimary = InversePrimary,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    background = Background,
    onBackground = OnBackground,
    surface = Background,
    onSurface = OnBackground,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceContainerLowest = SurfaceLowest,
    surfaceContainerLow = SurfaceLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = SurfaceHighest,
    surfaceDim = SurfaceDim,
    surfaceBright = Background,
    outline = Outline,
    outlineVariant = OutlineVariant,
    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,
    error = ErrorRed,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
)

/**
 * Inter, bundled rather than fetched.
 *
 * The design specifies Inter from Google Fonts. This app declares **no INTERNET
 * permission**, so a web font is not merely slow here — it is impossible. The variable
 * font ships in assets (856 KB) and every weight comes from the one file.
 */
/**
 * Inter, from a bundled variable font.
 *
 * The design specifies Inter from Google Fonts. This app declares **no INTERNET
 * permission**, so a web font is not merely slow here — it is impossible. One 856 KB
 * variable file in assets supplies every weight.
 *
 * Composable-scoped because reading an asset needs a Context, and [remember] so the
 * typeface is parsed once rather than on every recomposition.
 */
@Composable
private fun rememberInter(): FontFamily {
    val assets = LocalContext.current.assets
    return remember {
        fun w(weight: Int) = Font(
            path = "fonts/inter_variable.ttf",
            assetManager = assets,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
        FontFamily(w(400), w(500), w(600), w(700))
    }
}

private fun interTypography(family: FontFamily) = Typography(
    displayLarge = TextStyle(fontFamily = family, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontFamily = family, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = family, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontFamily = family, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = family, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = family, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = family, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ItantraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StitchScheme,
        typography = interTypography(rememberInter()),
        content = content,
    )
}
