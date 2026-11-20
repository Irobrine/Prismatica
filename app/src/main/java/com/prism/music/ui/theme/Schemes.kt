package com.prism.music.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.SchemeFamily
import com.prism.music.data.prefs.SchemeVariant

/**
 * Named color schemes (Settings → Theme & colors → Color scheme) as Prism wears them.
 *
 * A scheme is colors only. [current] says which palette the settings wear in a mode, [apply] turns it into the
 * Material scheme Prism draws with, and [light] is its split light (progress bar, play ring, the tab's fill).
 * Nothing else changes: shapes, type, glass and backgrounds stay as they are, the accent stays on the Accent row
 * (picking a scheme puts its signature color there, and it can be changed afterwards), and Prism's own colors are
 * [SchemeFamily.PRISM] — no palette at all, every setting working exactly as before.
 *
 * The palettes' published colors only cover their backgrounds, ink and accents ([SchemeVariant]); Material's other
 * roles (containers, dim/bright pages, inverse colors) are mixed from them here, the same way [withSurfaces] mixes
 * Prism's own. Every color is opaque: the schemes' translucent inks are already composited in the palettes.
 */
object Schemes {
    /** The scheme's palette for [dark], or null for Prism's own colors. */
    fun current(s: AppSettings, dark: Boolean): SchemeVariant? =
        if (s.shapes.scheme == SchemeFamily.PRISM) null else s.shapes.scheme.forMode(dark, s.shapes.schemeVariant)

    /** What the scheme row says: "Catppuccin · Mocha", or Prism's own name. */
    fun name(s: AppSettings, dark: Boolean): String {
        if (s.shapes.scheme == SchemeFamily.PRISM) return SchemeFamily.PRISM.label
        val v = current(s, dark) ?: return s.shapes.scheme.label
        return "${s.shapes.scheme.label} · ${v.name}"
    }

    /**
     * [base] (Prism's own scheme for these settings, already tinted and firmed) wearing [v]: its pages, ink,
     * borders and three accents, with Material's containers and inverse roles mixed from them. [accent] is the
     * accent in force ([seedFor]) — the palette's own unless the listener changed it on the Accent row.
     */
    fun apply(base: ColorScheme, v: SchemeVariant, accent: Color, minRatio: Float = 4.5f): ColorScheme {
        val bg = Color(v.background.toInt())
        val low = Color(v.surfaceLow.toInt())
        val high = Color(v.surfaceHigh.toInt())
        val fg = Color(v.foreground.toInt())
        val sub = Color(v.subtext.toInt())
        val line = Color(v.outline.toInt())
        val second = Color(v.accent2.toInt())
        val third = Color(v.accent3.toInt())
        val error = Color(v.error.toInt())
        fun mix(a: Color, b: Color, t: Float) = Color(ColorMath.mix(a.toArgb(), b.toArgb(), t))
        /** Ink that reads on [fill]: the page's own when it passes, white when it doesn't (whichever is better). */
        fun on(fill: Color): Color = listOf(bg, Color.White).maxBy { contrastRatio(it, fill) }
        fun container(fill: Color) = mix(bg, fill, if (v.dark) 0.24f else 0.16f)
        // Contrast above Standard firms the scheme's own ink and accents against its page, as firmed() does for
        // Prism's colors (the palettes' colors are published for their own backgrounds, which is usually enough).
        fun firm(color: Color, min: Float = minRatio) = if (minRatio > 4.5f) readableOn(color, bg, min) else color

        return base.copy(
            primary = firm(accent),
            onPrimary = on(accent),
            primaryContainer = container(accent),
            onPrimaryContainer = mix(fg, accent, 0.45f),
            inversePrimary = mix(accent, bg, 0.35f),
            secondary = firm(second),
            onSecondary = on(second),
            secondaryContainer = container(second),
            onSecondaryContainer = mix(fg, second, 0.45f),
            tertiary = firm(third),
            onTertiary = on(third),
            tertiaryContainer = container(third),
            onTertiaryContainer = mix(fg, third, 0.45f),
            error = error,
            onError = on(error),
            errorContainer = container(error),
            onErrorContainer = mix(fg, error, 0.45f),
            background = bg,
            onBackground = fg,
            surface = bg,
            onSurface = fg,
            // The palette's second and third steps are its panels and raised surfaces: Material's ladder runs from
            // the first of them (panels sunk under the page) to the other (cards, sheets, menus).
            surfaceVariant = mix(bg, high, 0.5f),
            onSurfaceVariant = firm(sub),
            surfaceTint = accent,
            surfaceDim = if (v.dark) bg else mix(bg, fg, 0.06f),
            surfaceBright = if (v.dark) mix(high, fg, 0.08f) else bg,
            surfaceContainerLowest = low,
            surfaceContainerLow = mix(low, bg, 0.5f),
            surfaceContainer = mix(bg, high, 0.35f),
            surfaceContainerHigh = mix(bg, high, 0.7f),
            surfaceContainerHighest = high,
            outline = firm(line, 3f),
            outlineVariant = mix(bg, line, 0.55f),
            inverseSurface = fg,
            inverseOnSurface = bg,
            scrim = Color.Black,
        )
    }

    /**
     * The scheme's split light: the palette's own accents in order, so the progress bar and play ring are the
     * scheme's colors too. [SpectrumPresets.Light]'s deep run is the same colors a shade deeper (they draw on light
     * pages), and the ring closes on the first.
     */
    fun light(v: SchemeVariant): SpectrumPresets.Light {
        val colors = v.light.map { Color(it.toInt()) }
        fun stops(list: List<Color>) = list.mapIndexed { i, c ->
            (if (list.size <= 1) 0f else i / (list.size - 1f)) to c
        }
        return SpectrumPresets.Light(
            colors = stops(colors),
            deep = stops(colors.map { mix(it, Color.Black, 0.14f) }),
            ring = colors + colors.take(1),
            hues = colors,
        )
    }

    private fun mix(a: Color, b: Color, t: Float): Color =
        Color(ColorMath.mix(a.toArgb(), b.toArgb(), t))
}
