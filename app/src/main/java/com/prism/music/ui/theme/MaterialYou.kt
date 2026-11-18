package com.prism.music.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.core.graphics.ColorUtils
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.ColorSource
import com.prism.music.data.prefs.MaterialPalette
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Material You (Material 3 / AOSP), Prism's second design language (Appearance → Looks → Material You).
 *
 * Unlike Prism's split light, Material 3 builds every role from one color in tonal palettes: a hue and chroma kept
 * while the tone (perceptual lightness, CIELAB's L*) changes. A light page and a dark page are therefore two readings
 * of the same palette, not one turned inside out, and the surfaces (background, the five container steps, the outline)
 * are a coordinated neutral palette rather than the same grey at different opacities.
 */
object MaterialYou {
    /** The Material 3 baseline seed (the lavender every M3 template starts from). */
    const val BASELINE_SEED = 0xFF6750A4.toInt()

    /**
     * The seed Material 3 uses for [s]: the wallpaper palette when the accent follows it (and Android has one), the
     * cover's color when the accent comes from the artwork, otherwise the picked accent (light mode's own when set).
     */
    fun seed(s: AppSettings, dark: Boolean, artwork: Color?): Color = when (s.colorSource) {
        ColorSource.ARTWORK -> artwork ?: Color(s.accent)
        ColorSource.CUSTOM -> Color(if (!dark && s.accentLight != 0) s.accentLight else s.accent)
        ColorSource.DYNAMIC -> Color(s.accent)
    }

    /** Whether this device can give the wallpaper's Material You palette. */
    fun hasDynamic(): Boolean = android.os.Build.VERSION.SDK_INT >= 31

    /**
     * The scheme for [s] on a [dark] page, from [seed]. [dynamic] is the wallpaper's scheme when the accent follows it,
     * null otherwise. Material 3's contrast setting is folded into the tones; pure black is honoured on dark pages.
     */
    fun scheme(s: AppSettings, dark: Boolean, seed: Color, dynamic: ((Boolean) -> ColorScheme)?): ColorScheme {
        val contrast = s.contrast.materialContrast
        val base = dynamic?.invoke(dark) ?: tonalScheme(seed, dark, s.shapes.palette, contrast)
        val firmed = if (contrast > 0f && dynamic != null) firmDynamic(base, contrast) else base
        return if (dark && s.pureBlack) firmed.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = shaded(firmed.surfaceContainerLow, 0.18f),
            surfaceContainer = shaded(firmed.surfaceContainer, 0.10f),
            surfaceContainerHigh = shaded(firmed.surfaceContainerHigh, 0.06f),
            surfaceContainerHighest = shaded(firmed.surfaceContainerHighest, 0.03f),
        ) else firmed
    }

    /** A full Material 3 scheme from one seed color and a palette variant ([MaterialPalette]). */
    fun tonalScheme(seed: Color, dark: Boolean, palette: MaterialPalette, contrast: Float = 0f): ColorScheme {
        val lab = labOf(seed)
        val hue = Math.toDegrees(atan2(lab[2], lab[1])).toFloat()
        val chroma = hypot(lab[1].toFloat(), lab[2].toFloat())
        val p = PaletteTones.of(palette, hue, chroma)
        val primary = p.primary.tone(if (dark) 80f else 40f, contrast, dark)
        val onPrimary = p.primary.tone(if (dark) 20f else 100f, contrast, dark)
        val primaryContainer = p.primary.tone(if (dark) 30f else 90f, contrast, dark)
        val onPrimaryContainer = p.primary.tone(if (dark) 90f else 10f, contrast, dark)
        val secondary = p.secondary.tone(if (dark) 80f else 40f, contrast, dark)
        val onSecondary = p.secondary.tone(if (dark) 20f else 100f, contrast, dark)
        val secondaryContainer = p.secondary.tone(if (dark) 30f else 90f, contrast, dark)
        val onSecondaryContainer = p.secondary.tone(if (dark) 90f else 10f, contrast, dark)
        val tertiary = p.tertiary.tone(if (dark) 80f else 40f, contrast, dark)
        val onTertiary = p.tertiary.tone(if (dark) 20f else 100f, contrast, dark)
        val tertiaryContainer = p.tertiary.tone(if (dark) 30f else 90f, contrast, dark)
        val onTertiaryContainer = p.tertiary.tone(if (dark) 90f else 10f, contrast, dark)
        val bg = p.neutral.tone(if (dark) 6f else 98f, contrast, dark)
        val onBg = p.neutral.tone(if (dark) 90f else 10f, contrast, dark)
        val variant = p.neutralVariant.tone(if (dark) 30f else 90f, contrast, dark)
        val onVariant = p.neutralVariant.tone(if (dark) 80f else 30f, contrast, dark)
        return if (dark) {
            darkColorScheme(
                primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer, inversePrimary = p.primary.tone(40f, contrast, dark),
                secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = onTertiaryContainer,
                background = bg, onBackground = onBg, surface = bg, onSurface = onBg,
                surfaceVariant = variant, onSurfaceVariant = onVariant, surfaceTint = primary,
                inverseSurface = p.neutral.tone(90f, contrast, dark), inverseOnSurface = p.neutral.tone(20f, contrast, dark),
                outline = p.neutralVariant.tone(60f, contrast, dark), outlineVariant = p.neutralVariant.tone(30f, contrast, dark),
                surfaceBright = p.neutral.tone(24f, contrast, dark), surfaceDim = bg,
                surfaceContainerLowest = p.neutral.tone(4f, contrast, dark),
                surfaceContainerLow = p.neutral.tone(10f, contrast, dark),
                surfaceContainer = p.neutral.tone(12f, contrast, dark),
                surfaceContainerHigh = p.neutral.tone(17f, contrast, dark),
                surfaceContainerHighest = p.neutral.tone(22f, contrast, dark),
                error = p.error.tone(80f, contrast, dark), onError = p.error.tone(20f, contrast, dark),
                errorContainer = p.error.tone(30f, contrast, dark), onErrorContainer = p.error.tone(90f, contrast, dark),
            )
        } else {
            lightColorScheme(
                primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer, inversePrimary = p.primary.tone(80f, contrast, dark),
                secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = onTertiaryContainer,
                background = bg, onBackground = onBg, surface = bg, onSurface = onBg,
                surfaceVariant = variant, onSurfaceVariant = onVariant, surfaceTint = primary,
                inverseSurface = p.neutral.tone(20f, contrast, dark), inverseOnSurface = p.neutral.tone(95f, contrast, dark),
                outline = p.neutralVariant.tone(50f, contrast, dark), outlineVariant = p.neutralVariant.tone(80f, contrast, dark),
                surfaceBright = p.neutral.tone(98f, contrast, dark), surfaceDim = p.neutral.tone(87f, contrast, dark),
                surfaceContainerLowest = p.neutral.tone(100f, contrast, dark),
                surfaceContainerLow = p.neutral.tone(96f, contrast, dark),
                surfaceContainer = p.neutral.tone(94f, contrast, dark),
                surfaceContainerHigh = p.neutral.tone(92f, contrast, dark),
                surfaceContainerHighest = p.neutral.tone(90f, contrast, dark),
                error = p.error.tone(40f, contrast, dark), onError = Color.White,
                errorContainer = p.error.tone(90f, contrast, dark), onErrorContainer = p.error.tone(10f, contrast, dark),
            )
        }
    }

    /** The wallpaper's Material 3 palette, when the accent follows it and Android can give one. */
    fun dynamic(s: AppSettings, context: android.content.Context): ((Boolean) -> ColorScheme)? =
        if (s.colorSource == ColorSource.DYNAMIC && hasDynamic()) { d ->
            if (d) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else null

    /**
     * Material 3's type scale (Roboto, through the platform's sans), with Prism's own text-size, weight and spacing
     * choices still applied on top so the accessibility settings keep working.
     */
    fun typography(family: FontFamily, weightShift: Int = 0, tracking: Float = 0f): Typography {
        val base = Typography()
        fun TextStyle.m(): TextStyle {
            val weight = (fontWeight?.weight ?: 400) + weightShift
            return copy(
                fontFamily = family,
                fontWeight = FontWeight(weight.coerceIn(100, 900)),
                letterSpacing = (letterSpacing.takeIf { it.isEm }?.value ?: 0f).plus(tracking).em,
            )
        }
        return Typography(
            displayLarge = base.displayLarge.m(), displayMedium = base.displayMedium.m(), displaySmall = base.displaySmall.m(),
            headlineLarge = base.headlineLarge.m(), headlineMedium = base.headlineMedium.m(), headlineSmall = base.headlineSmall.m(),
            titleLarge = base.titleLarge.m(), titleMedium = base.titleMedium.m(), titleSmall = base.titleSmall.m(),
            bodyLarge = base.bodyLarge.m(), bodyMedium = base.bodyMedium.m(), bodySmall = base.bodySmall.m(),
            labelLarge = base.labelLarge.m(), labelMedium = base.labelMedium.m(), labelSmall = base.labelSmall.m(),
        )
    }

    /** A color's CIELAB: L* (tone) first, then a and b. */
    private fun labOf(c: Color): DoubleArray {
        val out = DoubleArray(3)
        ColorUtils.colorToLAB(c.toArgb(), out)
        return out
    }

    /** A dark scheme's containers lifted a little when OLED black puts the page at tone 0. */
    private fun shaded(c: Color, lift: Float): Color {
        val lab = labOf(c)
        return Color(ColorUtils.LABToColor(max(lab[0], 100.0 * lift), lab[1], lab[2]))
    }

    /** Dynamic (wallpaper) schemes take no contrast level here: make the ink and accent firmer instead. */
    private fun firmDynamic(s: ColorScheme, contrast: Float): ColorScheme {
        fun firm(c: Color, on: Color): Color {
            val d = labOf(c)
            val o = labOf(on)
            return if (abs(d[0] - o[0]) < 55.0 * (1.0 + contrast)) Color(
                ColorUtils.LABToColor(if (o[0] > 50.0) min(100.0, d[0] + 8.0 * contrast) else max(0.0, d[0] - 8.0 * contrast), d[1], d[2]),
            ) else c
        }
        return s.copy(
            primary = firm(s.primary, s.onSurface), secondary = firm(s.secondary, s.onSurface), tertiary = firm(s.tertiary, s.onSurface),
            onSurfaceVariant = firm(s.onSurfaceVariant, s.onSurface),
            outline = firm(s.outline, s.onSurface),
        )
    }

    /**
     * The tones of one palette: a hue and chroma, turned into colors by [tone]. Material's own stops (40/80 primary,
     * 90/30 containers, 98/6 surfaces) are used by [tonalScheme].
     */
    private class Tones(val hue: Float, val chroma: Float) {
        fun tone(t: Float, contrast: Float, dark: Boolean): Color {
            // Higher contrast: text and accents move away from their surface, so primary gets firmer and containers
            // spread out. The movement is small enough to stay in the palette's character.
            val tone = when {
                contrast <= 0f -> t
                dark -> when (t) {
                    80f -> t + 8f * contrast
                    20f -> t - 6f * contrast
                    30f -> t + 4f * contrast
                    90f -> t + 6f * contrast
                    else -> t
                }
                else -> when (t) {
                    40f -> t - 8f * contrast
                    90f -> t - 6f * contrast
                    10f -> t - 6f * contrast
                    else -> t
                }
            }.coerceIn(0f, 100f)
            return lab(tone, hue, chroma)
        }
    }

    /** A palette variant's hues and chromas, from the seed's own. */
    private object PaletteTones {
        fun of(palette: MaterialPalette, hue: Float, seedChroma: Float): Palettes {
            val c = seedChroma.coerceIn(8f, 96f)
            return when (palette) {
                MaterialPalette.TONAL_SPOT -> Palettes(
                    Tones(hue, c.coerceIn(20f, 64f)), Tones(hue, 16f), Tones(hue + 60f, 24f),
                    Tones(hue, 4f), Tones(hue, 8f), Tones(25f, 84f),
                )
                MaterialPalette.VIBRANT -> Palettes(
                    Tones(hue, max(c, 72f)), Tones(hue, 32f), Tones(hue + 30f, 40f),
                    Tones(hue, 6f), Tones(hue, 12f), Tones(25f, 84f),
                )
                MaterialPalette.EXPRESSIVE -> Palettes(
                    Tones(hue, c.coerceIn(16f, 72f)), Tones(hue + 15f, 24f), Tones(hue + 105f, 44f),
                    Tones(hue, 6f), Tones(hue, 10f), Tones(25f, 84f),
                )
                MaterialPalette.NEUTRAL -> Palettes(
                    Tones(hue, 8f), Tones(hue, 6f), Tones(hue + 60f, 12f),
                    Tones(hue, 3f), Tones(hue, 6f), Tones(25f, 70f),
                )
                MaterialPalette.MONOCHROME -> Palettes(
                    Tones(hue, 0f), Tones(hue, 0f), Tones(hue, 0f),
                    Tones(hue, 0f), Tones(hue, 0f), Tones(25f, 0f),
                )
            }
        }
    }

    private class Palettes(
        val primary: Tones, val secondary: Tones, val tertiary: Tones,
        val neutral: Tones, val neutralVariant: Tones, val error: Tones,
    )

    /**
     * The color at CIELAB tone [tone] for [hue] and [chroma]. Out-of-gamut requests are toned down in chroma until the
     * color keeps the requested tone (Material's own gamut mapping keeps the tone readable, not the exact chroma).
     */
    private fun lab(tone: Float, hue: Float, chroma: Float): Color {
        if (chroma < 0.5f || tone <= 0.5f || tone >= 99.5f) {
            return Color(ColorUtils.LABToColor(tone.toDouble(), 0.0, 0.0))
        }
        val rad = Math.toRadians(hue.toDouble())
        var c = chroma
        repeat(24) {
            val value = ColorUtils.LABToColor(tone.toDouble(), c * cos(rad), c * sin(rad))
            val got = labOf(Color(value))
            if (abs(got[0] - tone) <= 1.25) return Color(value)
            c *= 0.82f
            if (c < 0.5f) return Color(ColorUtils.LABToColor(tone.toDouble(), 0.0, 0.0))
        }
        return Color(ColorUtils.LABToColor(tone.toDouble(), c * cos(rad), c * sin(rad)))
    }
}

/** Material 3's contrast (Standard, Medium, High) as the 0 … 1 the tonal palettes take. */
internal val com.prism.music.data.prefs.ContrastLevel.materialContrast: Float
    get() = when (this) {
        com.prism.music.data.prefs.ContrastLevel.STANDARD -> 0f
        com.prism.music.data.prefs.ContrastLevel.MEDIUM -> 0.5f
        com.prism.music.data.prefs.ContrastLevel.HIGH -> 1f
    }

/** Material 3's shape scale, in dp: components take extraSmall … extraLarge (4, 8, 12, 16, 28). */
object MaterialShapeScale {
    const val EXTRA_SMALL = 4f
    const val SMALL = 8f
    const val MEDIUM = 12f
    const val LARGE = 16f
    const val EXTRA_LARGE = 28f
}
