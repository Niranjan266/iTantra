package com.itantra.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's colour and type.
 *
 * ## Why this is not the dynamic Material You palette
 *
 * Android 12+ can derive a scheme from the user's wallpaper, and for most apps that is the
 * right default. Not here. The colours in this app **carry meaning** — one band means
 * ready, one button means emergency — and a palette derived from a photograph of someone's
 * cat will happily render the emergency control in mint green. Meaningful colour cannot be
 * delegated to a wallpaper.
 *
 * ## The urgency colours, and why they are not only colours
 *
 * Routine, warning and emergency are the one place in this app where colour does real
 * work, so they are chosen to survive the common forms of colour blindness — the blue and
 * amber stay distinguishable under deuteranopia and protanopia, where a red/green pair
 * collapses. Even so, **colour is never the only signal**: each level also has its own
 * silhouette ([ItantraIcons]) and its own size on screen. Someone who sees no colour at all
 * can still tell them apart.
 *
 * Contrast is held at 4.5:1 or better against its background for text, and the status band
 * pairs white on deliberately dark fills rather than the lighter tints Material would pick.
 */

// --- brand ---------------------------------------------------------------------------

/** Deep indigo. Calm, and not a colour any status uses, so it never competes. */
private val Indigo = Color(0xFF3730A3)
private val IndigoLight = Color(0xFF6366F1)
private val IndigoDark = Color(0xFF1E1B4B)

// --- status, all meaning-bearing ------------------------------------------------------

/** Ready. Dark enough for white text at 4.5:1. */
val StatusReady = Color(0xFF15803D)
val StatusReadyDark = Color(0xFF14532D)

/** Working. Grey, so "not yet" never looks like "wrong". */
val StatusBusy = Color(0xFF475569)
val StatusBusyDark = Color(0xFF334155)

/** Not usable. */
val StatusError = Color(0xFFB91C1C)
val StatusErrorDark = Color(0xFF7F1D1D)

/** Speaking — a received message is playing. Distinct from all three above. */
val StatusSpeaking = Color(0xFF0E7490)

// --- urgency --------------------------------------------------------------------------

/** Routine. Blue: unmistakably "not an alarm". */
val UrgencyRoutine = Color(0xFF1D4ED8)

/** Warning. Amber rather than yellow, which cannot hold contrast against white. */
val UrgencyWarning = Color(0xFFB45309)

/** Emergency. */
val UrgencyEmergency = Color(0xFFB91C1C)

private val LightScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = IndigoDark,
    secondary = Color(0xFF475569),
    onSecondary = Color.White,
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEEF2F7),
    onSurfaceVariant = Color(0xFF44506A),
    outline = Color(0xFF94A3B8),
    error = StatusError,
    onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF94A3B8),
    onSecondary = Color(0xFF0F172A),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFE8EDF7),
    surface = Color(0xFF151B2E),
    onSurface = Color(0xFFE8EDF7),
    surfaceVariant = Color(0xFF1E2842),
    onSurfaceVariant = Color(0xFFB6C2D9),
    outline = Color(0xFF475569),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
)

/**
 * Type scale.
 *
 * Larger than Material's defaults throughout, and heavier. The intended reader may be
 * holding the phone at arm's length in bad light, in the rain, in a hurry — and may read
 * slowly or not at all, which makes every word on screen expensive. Nothing is below 14 sp.
 */
private val ItantraTypography = Typography(
    displayLarge = TextStyle(fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.Black),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ItantraTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = ItantraTypography,
        content = content,
    )
}
