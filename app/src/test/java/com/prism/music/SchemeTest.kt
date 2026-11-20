package com.prism.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.SchemeFamily
import com.prism.music.ui.theme.Schemes
import com.prism.music.ui.theme.contrastRatio
import com.prism.music.ui.theme.themeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The named color schemes (Settings → Theme & colors): every palette is whole and readable, each family opens on the
 * right variant for the mode, Prism's own colors stay untouched, and the palette replaces the pages and the light
 * while leaving the accent yours.
 */
class SchemeTest {
    private fun rgb(v: Long) = Color(v.toInt())

    @Test fun everyFamilyHasBothModesAndUniqueIds() {
        for (f in SchemeFamily.entries) {
            if (f == SchemeFamily.PRISM) {
                assertTrue("Prism wears no palette", f.variants.isEmpty())
                continue
            }
            assertTrue("${f.label} has a light variant", f.variants.any { !it.dark })
            assertTrue("${f.label} has a dark variant", f.variants.any { it.dark })
            val ids = f.variants.map { it.id }
            assertEquals("${f.label}'s ids are unique", ids.size, ids.distinct().size)
            assertTrue("${f.label}'s names are filled in", f.variants.all { it.name.isNotBlank() })
        }
    }

    @Test fun everyPaletteIsReadable() {
        for (f in SchemeFamily.entries) for (v in f.variants) {
            val where = "${f.label} ${v.name}"
            assertTrue("$where: ${v.light.size} light colors", v.light.size in 2..7)
            assertTrue("$where: the page is not its ink", v.background != v.foreground)
            val body = contrastRatio(rgb(v.foreground), rgb(v.background))
            // Solarized's own body ink on base3 is 4.13:1 (the palette is famously soft); every other scheme is at
            // least 4.5, and Prism's Medium and High contrast firm any of them against the page.
            assertTrue("$where: text on the page is ${"%.2f".format(body)}:1", body >= 4f && (body >= 4.5f || f == SchemeFamily.SOLARIZED))
            val quiet = contrastRatio(rgb(v.subtext), rgb(v.background))
            assertTrue("$where: quieter text is ${"%.2f".format(quiet)}:1", quiet >= 2.5f)
            for ((name, c) in listOf("accent" to v.accent, "accent2" to v.accent2, "accent3" to v.accent3, "error" to v.error)) {
                val ratio = contrastRatio(rgb(c), rgb(v.background))
                assertTrue("$where: its $name is ${"%.2f".format(ratio)}:1", ratio >= 1.8f)
            }
            // A page is its palette's own: the ladder around it is not all one color.
            assertTrue("$where: its surfaces differ", v.surfaceLow != v.surfaceHigh)
        }
    }

    @Test fun everyFamilyOpensOnItsDefaults() {
        for (f in SchemeFamily.entries) {
            if (f == SchemeFamily.PRISM) continue
            val light = f.variant(f.lightDefault)
            val dark = f.variant(f.darkDefault)
            assertNotNull("${f.label}'s light default exists", light)
            assertNotNull("${f.label}'s dark default exists", dark)
            assertEquals("${f.label}'s light default is light", false, light!!.dark)
            assertEquals("${f.label}'s dark default is dark", true, dark!!.dark)
            // With nothing picked, each mode opens on its default.
            assertEquals(light, f.forMode(dark = false, stored = null))
            assertEquals(dark, f.forMode(dark = true, stored = null))
        }
    }

    @Test fun aFlavorStaysPickedAcrossTheOtherMode() {
        val cat = SchemeFamily.CATPPUCCIN
        val mocha = cat.variant("mocha")!!
        assertEquals(mocha, cat.forMode(dark = true, stored = "mocha"))
        // Light pages get the light palette meanwhile; back in dark, the flavor picked is still the one worn.
        assertEquals(cat.variant("latte"), cat.forMode(dark = false, stored = "mocha"))
        assertEquals(mocha, cat.forMode(dark = true, stored = "mocha"))
        // A variant of another family (or a stale id) falls back to the mode's default.
        assertEquals(cat.variant("latte"), cat.forMode(dark = false, stored = "storm"))
    }

    @Test fun prismWearsNoPaletteAndChangesNothing() {
        val s = AppSettings()
        assertNull(Schemes.current(s, dark = true))
        assertNull(Schemes.current(s, dark = false))
        assertEquals(SchemeFamily.PRISM.label, Schemes.name(s, dark = true))
        val base = themeColors(s, dark = true, seed = Color(0xFF3A86FF.toInt()), dynamic = null)
        assertTrue("Prism's own colors have their split light", !base.white)
    }

    @Test fun theSchemeReplacesPagesAndLightButKeepsTheAccentYours() {
        val s = AppSettings()
        val red = Color(0xFFE53935.toInt())
        val base = themeColors(s, dark = false, seed = red, dynamic = null)
        val latte = SchemeFamily.CATPPUCCIN.variant("latte")!!
        val applied = Schemes.apply(base.scheme, latte, red, base.settings!!.contrast.minRatio)
        assertEquals("the page is the palette's", rgb(latte.background), applied.background)
        assertEquals("the ink is the palette's", rgb(latte.foreground), applied.onBackground)
        assertEquals("the accent is still yours", red, applied.primary)
        assertNotEquals("its light is the palette's", base.light.ring, Schemes.light(latte).ring)
        assertEquals("its light runs through the palette's accents", latte.light.size + 1, Schemes.light(latte).ring.size)
        val stops = Schemes.light(latte).colors
        assertEquals(0f, stops.first().first, 0f)
        assertEquals(1f, stops.last().first, 0f)
    }

    @Test fun aDarkSchemeGivesDarkPages() {
        for (f in SchemeFamily.entries) {
            if (f == SchemeFamily.PRISM) continue
            for (v in f.variants) {
                val page = rgb(v.background).luminance()
                if (v.dark) assertTrue("${f.label} ${v.name} is dark", page < 0.35f)
                else assertTrue("${f.label} ${v.name} is light", page > 0.55f)
            }
        }
    }
}
