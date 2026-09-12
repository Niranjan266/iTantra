package com.itantra.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The app's icons, as real vectors.
 *
 * ## Why not emoji
 *
 * The first version of this screen used emoji — 🎤 for the talk button, ⚠ and 🆘 for
 * urgency. They were quick, and wrong for this product:
 *
 *  - **They are bitmap font glyphs, not vectors.** Blown up to a 280 dp button they are
 *    visibly soft, and the talk button is the largest thing on the screen.
 *  - **They are drawn by whoever made the phone.** Samsung, Motorola and Google ship
 *    different artwork for the same code point, so the one symbol a non-reading user is
 *    meant to recognise looks different on the phone next to theirs. For an interface
 *    whose whole premise is "a symbol you recognise without reading", that is a defect.
 *  - **Colour is not controllable.** Emoji carry their own colours, so they cannot be
 *    tinted to meet a contrast ratio or inverted for dark mode.
 *
 * These are defined once, scale to any size without softening, render identically on every
 * handset, and take the colour they are given.
 *
 * ## Why SVG path strings rather than the builder DSL
 *
 * [PathParser] is part of Compose, so a path can be written in the same notation icons are
 * designed and exported in. Transcribing that into `moveTo`/`curveTo` calls by hand is
 * long and, more to the point, silently wrong when a coordinate is mistyped — a mistake
 * that shows up as a subtly deformed shape rather than as an error.
 *
 * Every icon is drawn in a 24×24 box, the standard grid, so they share an optical weight.
 */
object ItantraIcons {

    /**
     * Speech, for a routine message. A rounded bubble with three dots.
     *
     * The dots matter: an empty bubble reads as "no message" to some people, and this is
     * the control that means "ordinary talking".
     */
    val Chat: ImageVector = icon(
        "Chat",
        "M20 2H4a2 2 0 0 0-2 2v11a2 2 0 0 0 2 2h3v4l4.5-4H20a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2z" +
            "M7.5 11.5a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z" +
            "M12 11.5a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z" +
            "M16.5 11.5a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z"
    )

    /**
     * A warning. Triangle with an exclamation.
     *
     * A triangle, not a coloured circle: the silhouette alone has to carry the meaning for
     * the roughly one man in twelve who cannot separate red from green.
     */
    val Warning: ImageVector = icon(
        "Warning",
        "M12 2.5 22.5 21H1.5L12 2.5z" +
            "M11 9h2v6h-2V9z" +
            "M11 16.5h2V19h-2v-2.5z"
    )

    /**
     * Emergency. An octagon with a cross inside.
     *
     * The octagon is the one shape in this set that means "stop everything", borrowed from
     * road signage where it is already learned. The cross reads as help, not as a letter,
     * so it does not depend on knowing the Latin alphabet or what "SOS" spells.
     */
    val Emergency: ImageVector = icon(
        "Emergency",
        "M7.6 1.5h8.8L22.5 7.6v8.8L16.4 22.5H7.6L1.5 16.4V7.6L7.6 1.5z" +
            "M10.75 6h2.5v4.75H18v2.5h-4.75V18h-2.5v-4.75H6v-2.5h4.75V6z"
    )

    /** A microphone. Capsule, cradle and stand — the outline people already know. */
    val Microphone: ImageVector = icon(
        "Microphone",
        "M12 15.5a3.5 3.5 0 0 0 3.5-3.5V5a3.5 3.5 0 0 0-7 0v7a3.5 3.5 0 0 0 3.5 3.5z" +
            "M18.5 12a6.5 6.5 0 0 1-5.5 6.42V21.5h-2v-3.08A6.5 6.5 0 0 1 5.5 12h2a4.5 4.5 0 0 0 9 0h2z"
    )

    /** Play, for hearing a message again. A plain triangle in a circle. */
    val Replay: ImageVector = icon(
        "Replay",
        "M12 1.5A10.5 10.5 0 1 0 22.5 12 10.5 10.5 0 0 0 12 1.5z" +
            "M9.75 7.25 16.5 12l-6.75 4.75V7.25z"
    )

    /** Ready. A tick. */
    val Check: ImageVector = icon(
        "Check",
        "M9.2 18.6 3 12.4l2.1-2.1 4.1 4.1L18.9 5l2.1 2.1-11.8 11.5z"
    )

    /** Not ready. A cross — a different silhouette from the tick, not just a different colour. */
    val Close: ImageVector = icon(
        "Close",
        "M19.5 6.2 17.8 4.5 12 10.3 6.2 4.5 4.5 6.2 10.3 12l-5.8 5.8 1.7 1.7 5.8-5.8 5.8 5.8 1.7-1.7-5.8-5.8 5.8-5.8z"
    )

    /** Working. An hourglass, for the seconds while the speech models load. */
    val Loading: ImageVector = icon(
        "Loading",
        "M6 2h12v2h-1v3.5L13.5 11l3.5 3.5V18h1v2H6v-2h1v-3.5L10.5 11 7 7.5V4H6V2z" +
            "M9 4v2.9l3 3 3-3V4H9z"
    )

    /** Settings, for the way through to the technical view. */
    val Settings: ImageVector = icon(
        "Settings",
        "M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7z" +
            "M19.4 13c.04-.33.06-.66.06-1s-.02-.67-.06-1l2.1-1.6a.5.5 0 0 0 .12-.65l-2-3.46a.5.5 0 0 0-.6-.22l-2.5 1a7.3 7.3 0 0 0-1.73-1l-.38-2.65A.5.5 0 0 0 13.93 2h-4a.5.5 0 0 0-.49.42L9.06 5.07a7.3 7.3 0 0 0-1.73 1l-2.5-1a.5.5 0 0 0-.6.22l-2 3.46a.5.5 0 0 0 .12.65L4.45 11c-.04.33-.06.66-.06 1s.02.67.06 1l-2.1 1.6a.5.5 0 0 0-.12.65l2 3.46a.5.5 0 0 0 .6.22l2.5-1a7.3 7.3 0 0 0 1.73 1l.38 2.65a.5.5 0 0 0 .49.42h4a.5.5 0 0 0 .49-.42l.38-2.65a7.3 7.3 0 0 0 1.73-1l2.5 1a.5.5 0 0 0 .6-.22l2-3.46a.5.5 0 0 0-.12-.65L19.4 13z"
    )

    /**
     * Broadcast, for the mesh link. Concentric arcs leaving a dot.
     *
     * Deliberately not a Wi-Fi glyph: this is not the internet, and a Wi-Fi symbol is the
     * one thing most likely to make someone assume it needs a network.
     */
    val Broadcast: ImageVector = icon(
        "Broadcast",
        "M12 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4z" +
            "M7.8 7.8 6.4 6.4a7.9 7.9 0 0 0 0 11.2l1.4-1.4a5.9 5.9 0 0 1 0-8.4z" +
            "M17.6 6.4l-1.4 1.4a5.9 5.9 0 0 1 0 8.4l1.4 1.4a7.9 7.9 0 0 0 0-11.2z" +
            "M4.9 3.5 3.5 2.1a13.9 13.9 0 0 0 0 19.8l1.4-1.4a11.9 11.9 0 0 1 0-17z" +
            "M20.5 2.1l-1.4 1.4a11.9 11.9 0 0 1 0 17l1.4 1.4a13.9 13.9 0 0 0 0-19.8z"
    )

    /** A speaker, shown while a message is being read out. */
    val Speaker: ImageVector = icon(
        "Speaker",
        "M3 9.5h3.5L12 4v16L6.5 14.5H3v-5z" +
            "M15.5 8.2a5.2 5.2 0 0 1 0 7.6l1.5 1.5a7.3 7.3 0 0 0 0-10.6l-1.5 1.5z" +
            "M18.4 4.4 17 5.8a8.8 8.8 0 0 1 0 12.4l1.4 1.4a10.8 10.8 0 0 0 0-15.2z"
    )

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Black here is a placeholder only. Every use site passes a tint, which is the
            // point of drawing these rather than using emoji.
            addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                fill = SolidColor(Color.Black),
                // Even-odd, so an inner subpath CUTS A HOLE instead of filling.
                //
                // With the default non-zero rule, whether an inner shape becomes a hole
                // depends on the direction it happens to be wound, which is invisible in
                // the path string. The first build of these icons shipped a warning
                // triangle with no exclamation mark and an emergency octagon with no
                // cross — both solid blobs, both meaningless, and both looking deliberate.
                pathFillType = PathFillType.EvenOdd,
            )
        }.build()
}
