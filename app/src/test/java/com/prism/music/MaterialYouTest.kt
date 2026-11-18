package com.prism.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prism.music.data.looks.BuiltInLooks
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.ContrastLevel
import com.prism.music.data.prefs.DesignLanguage
import com.prism.music.data.prefs.MaterialPalette
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.ui.theme.MaterialYou
import com.prism.music.ui.theme.PrismUi
import com.prism.music.ui.theme.appTypography
import com.prism.music.ui.theme.contrastRatio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs

/**
 * Material You (1.9): Material 3's tonal palettes (a coordinated light and dark reading of one seed, with readable
 * role colors), its type and shape scales, the palette variants, and the Look that carries them.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class MaterialYouTest {
    private val seeds = listOf(0xFF6750A4, 0xFF3A86FF, 0xFFFF6B35, 0xFF2EC27E, 0xFF7C5CFF).map { Color(it.toInt()) }

    @Test fun everyRoleReadsOnWhatItSitsOn() {
        for (seed in seeds) for (dark in listOf(false, true)) for (palette in MaterialPalette.entries) {
            val s = MaterialYou.tonalScheme(seed, dark, palette)
            val where = "${seed.toArgb().toUInt().toString(16)} $dark $palette"
            fun atLeast(a: Color, b: Color, ratio: Float) =
                assertTrue("$where: ${contrastRatio(a, b)} between $a and $b", contrastRatio(a, b) >= ratio)
            atLeast(s.onBackground, s.background, 7f)
            atLeast(s.onSurface, s.surface, 7f)
            atLeast(s.onSurfaceVariant, s.surface, 4.5f)
            atLeast(s.onPrimary, s.primary, 4.5f)
            atLeast(s.onSecondary, s.secondary, 4.5f)
            atLeast(s.onTertiary, s.tertiary, 4.5f)
            atLeast(s.onPrimaryContainer, s.primaryContainer, 4.5f)
            atLeast(s.onSecondaryContainer, s.secondaryContainer, 4.5f)
            atLeast(s.onTertiaryContainer, s.tertiaryContainer, 4.5f)
            atLeast(s.outline, s.surface, 2.5f)
        }
    }

    @Test fun lightAndDarkAreTheSamePaletteAtTwoTones() {
        val seed = Color(0xFF6750A4)
        val light = MaterialYou.tonalScheme(seed, dark = false, MaterialPalette.TONAL_SPOT)
        val dark = MaterialYou.tonalScheme(seed, dark = true, MaterialPalette.TONAL_SPOT)
        // A light page is near white, a dark one near black; neither is the other turned inside out.
        assertTrue(light.background.luminance() > 0.85f)
        assertTrue(dark.background.luminance() < 0.04f)
        assertNotEquals(light.primary, dark.primary)
        // The primary keeps the seed's hue family (violet): more red and blue than green in both readings.
        for (c in listOf(light.primary, dark.primary)) {
            assertTrue("$c keeps the violet hue", c.red > c.green && c.blue > c.green)
        }
        // Surfaces step by tone: light gets quieter down the containers, dark gets brighter.
        val lightSteps = listOf(light.surfaceContainerLowest, light.surfaceContainerLow, light.surfaceContainer, light.surfaceContainerHigh, light.surfaceContainerHighest)
        val darkSteps = listOf(dark.surfaceContainerLowest, dark.surfaceContainerLow, dark.surfaceContainer, dark.surfaceContainerHigh, dark.surfaceContainerHighest)
        lightSteps.zipWithNext().forEach { (a, b) -> assertTrue("$a should be brighter than $b", a.luminance() > b.luminance()) }
        darkSteps.zipWithNext().forEach { (a, b) -> assertTrue("$a should be darker than $b", a.luminance() < b.luminance()) }
    }

    @Test fun thePaletteVariantsCarryMoreOrLessColor() {
        val seed = Color(0xFF6750A4)
        fun chroma(c: Color): Float {
            val lab = DoubleArray(3)
            androidx.core.graphics.ColorUtils.colorToLAB(c.toArgb(), lab)
            return kotlin.math.hypot(lab[1].toFloat(), lab[2].toFloat())
        }
        val tonal = MaterialYou.tonalScheme(seed, false, MaterialPalette.TONAL_SPOT)
        val vibrant = MaterialYou.tonalScheme(seed, false, MaterialPalette.VIBRANT)
        val neutral = MaterialYou.tonalScheme(seed, false, MaterialPalette.NEUTRAL)
        val mono = MaterialYou.tonalScheme(seed, false, MaterialPalette.MONOCHROME)
        assertTrue("vibrant is more colorful", chroma(vibrant.primary) >= chroma(tonal.primary))
        assertTrue("neutral is quieter", chroma(neutral.primary) < chroma(tonal.primary))
        assertTrue("monochrome has no hue", chroma(mono.primary) < 2f && chroma(mono.tertiary) < 2f)
        // Expressive moves the tertiary further round than tonal spot does.
        val expressive = MaterialYou.tonalScheme(seed, false, MaterialPalette.EXPRESSIVE)
        assertNotEquals(expressive.tertiary, tonal.tertiary)
    }

    @Test fun contrastFirmsUpTheRoles() {
        fun ratio(level: ContrastLevel): Float {
            val s = MaterialYou.tonalScheme(Color(0xFF6750A4), dark = false, MaterialPalette.TONAL_SPOT, level.ordinal / 2f)
            return contrastRatio(s.primary, s.surface)
        }
        assertTrue("medium is firmer than standard", ratio(ContrastLevel.MEDIUM) > ratio(ContrastLevel.STANDARD))
        assertTrue("high is firmer than medium", ratio(ContrastLevel.HIGH) > ratio(ContrastLevel.MEDIUM))
    }

    @Test fun contrastAlsoFirmsAWallpaperPalette() {
        val seed = Color(0xFF6750A4)
        val baseLight = MaterialYou.tonalScheme(seed, false, MaterialPalette.TONAL_SPOT)
        val baseDark = MaterialYou.tonalScheme(seed, true, MaterialPalette.TONAL_SPOT)
        fun firmed(dark: Boolean, level: ContrastLevel) = MaterialYou.scheme(
            AppSettings(contrast = level, shapes = AppSettings().shapes.copy(design = DesignLanguage.MATERIAL_YOU)),
            dark, seed,
        ) { if (dark) baseDark else baseLight }
        for (dark in listOf(false, true)) {
            val base = if (dark) baseDark else baseLight
            val high = firmed(dark, ContrastLevel.HIGH)
            assertTrue("$dark: primary reads better", contrastRatio(high.primary, high.surface) >= contrastRatio(base.primary, base.surface))
            assertTrue("$dark: secondary text reads better", contrastRatio(high.onSurfaceVariant, high.surface) >= contrastRatio(base.onSurfaceVariant, base.surface))
            // The wallpaper's hue is kept: the firmed colors aren't turned into greys.
            assertTrue("$dark: primary stays colored", high.primary != high.surface)
        }
    }

    @Test fun theTypeAndShapeScalesAreMaterials() {
        val s = AppSettings(shapes = AppSettings().shapes.copy(design = DesignLanguage.MATERIAL_YOU))
        val t = appTypography(s)
        assertEquals(57f, t.displayLarge.fontSize.value)
        assertEquals(32f, t.headlineLarge.fontSize.value)
        assertEquals(22f, t.titleLarge.fontSize.value)
        assertEquals(16f, t.bodyLarge.fontSize.value)
        assertEquals(11f, t.labelSmall.fontSize.value)
        // Prism's own scale is not Material's (its page title is 36 at ExtraLight).
        val prism = appTypography(AppSettings())
        assertEquals(36f, prism.headlineLarge.fontSize.value)
        assertEquals(FontWeight.ExtraLight, prism.headlineLarge.fontWeight)
        // Material's corners: 4, 8, 12, 16, 28; Prism's own are 8 … 28.
        val ui = PrismUi.from(s)
        assertTrue(ui.material)
        assertEquals(androidx.compose.foundation.shape.CornerSize(4.dp), ui.materialShapes.extraSmall.topStart)
        assertEquals(androidx.compose.foundation.shape.CornerSize(28.dp), ui.materialShapes.extraLarge.topStart)
    }

    @Test fun theLookCarriesMaterialYou() {
        val look = BuiltInLooks.of("material-you")
        assertNotNull("Material You is one of Prism's Looks", look)
        assertEquals("s:MATERIAL_YOU", look!!.values["design_language"])
        assertEquals("s:DYNAMIC", look.values["color_source"])
        assertEquals("s:THEME", look.values["player_background"])
        assertTrue("a Look's values apply", look.values.size > 15)
        // And the app reads them as the design and palette.
        val repo = SettingsRepository(RuntimeEnvironment.getApplication(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val preview = repo.previewLook(look.values)
        assertEquals(DesignLanguage.MATERIAL_YOU, preview.shapes.design)
        assertEquals(MaterialPalette.TONAL_SPOT, preview.shapes.palette)
        // A palette chosen in the Colors page goes through a Look too.
        val p = repo.previewLook(mapOf("design_language" to "s:MATERIAL_YOU", "material_palette" to "s:VIBRANT"))
        assertEquals(MaterialPalette.VIBRANT, p.shapes.palette)
    }

    @Test fun applyingTheDesignWritesItAndComesBack() = runBlocking {
        val repo = SettingsRepository(RuntimeEnvironment.getApplication(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val values = mapOf("design_language" to "s:MATERIAL_YOU", "material_palette" to "s:EXPRESSIVE")
        repo.applyLook(values)
        val back = withTimeout(5_000) { repo.lookValues.first { it["design_language"] == "s:MATERIAL_YOU" } }
        assertEquals("s:EXPRESSIVE", back["material_palette"])
        assertTrue(withTimeout(5_000) { repo.flow.first { it.shapes.design == DesignLanguage.MATERIAL_YOU }.shapes.design == DesignLanguage.MATERIAL_YOU })
        // The settings store is one for the whole test run: leave it as Prism comes for the tests after this one.
        repo.applyLook(emptyMap())
        withTimeout(5_000) { repo.lookValues.first { it.isEmpty() } }
        Unit
    }

    /** Material's own baseline lavender sits in the palette's violet family (its hue is not turned by the generator). */
    @Test fun theSeedKeepsItsHue() {
        val seed = Color(0xFF6750A4)
        val seedLab = DoubleArray(3).also { androidx.core.graphics.ColorUtils.colorToLAB(seed.toArgb(), it) }
        val hue = Math.toDegrees(kotlin.math.atan2(seedLab[2], seedLab[1]))
        val out = DoubleArray(3).also { androidx.core.graphics.ColorUtils.colorToLAB(MaterialYou.tonalScheme(seed, false, MaterialPalette.TONAL_SPOT).primary.toArgb(), it) }
        val outHue = Math.toDegrees(kotlin.math.atan2(out[2], out[1]))
        assertTrue("seed $hue°, primary $outHue°", abs(hue - outHue) < 25.0)
    }
}
