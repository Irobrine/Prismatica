package com.prism.music.data.prefs

import com.prism.music.R
import com.prism.music.tr

/**
 * The named color schemes Prism can wear (Settings → Theme & colors → Color scheme), beside its own colors
 * ([PRISM], which is every setting on the page working exactly as before).
 *
 * A scheme is only colors: it replaces the page's surfaces, ink and the split light while it's on, and changes
 * nothing else (the accent stays yours to change on the Accent row, and the theme row still says how dark Prism is).
 * [SchemeVariant]s are the palettes themselves, taken from each project's own palette, not from a port:
 *
 * - Catppuccin: catppuccin/palette/palette.json (MIT)
 * - Tokyo Night: enkia/tokyo-night-vscode-theme for Night, Storm and Light; folke/tokyonight.nvim's generated
 *   exports for Moon and Day, which enkia's theme doesn't define (Apache-2.0)
 * - Rosé Pine: rose-pine/rose-pine-palette/palette.json (MIT)
 * - Everforest: sainnhe/everforest (MIT) — medium backgrounds
 * - Gruvbox: gruvbox-community/gruvbox (MIT) — medium backgrounds, the bright ramp on dark and faded on light
 * - Nord: nordtheme/nord (MIT). Nord has no official light theme (nord#46); [NORD_LIGHT] is the usual Snow Storm
 *   inversion, marked as ours rather than Nord's.
 * - Dracula: dracula/dracula-theme (MIT) — Dracula and its official light sibling, Alucard
 * - Kanagawa: rebelot/kanagawa.nvim (MIT) — Wave, Dragon and Lotus
 * - Solarized: altercation/solarized (MIT) — Dark and Light
 * - Ayu: ayu-theme/ayu-colors + ayu-theme/vscode-ayu (MIT) — Dark, Mirage and Light
 *
 * Every palette gives one background, two surface steps (its own ladder: mantle/current line/bg1…), text, a
 * quieter text, a border, three accents (the scheme's signature color first), an error red and the colors its
 * split light runs through. [com.prism.music.ui.theme.Schemes] turns one into a Material scheme; the here numbers
 * are the source palettes, untouched but for the rows noted as derived.
 */
enum class SchemeFamily(
    val label: String,
    val variants: List<SchemeVariant>,
    /** The variant a mode opens on when none of its own was picked ([variants]' id). */
    val lightDefault: String,
    val darkDefault: String,
) {
    /** Prism's own colors: every color setting on the page, exactly as it is without a scheme. */
    PRISM("Prism", emptyList(), "", ""),

    CATPPUCCIN(
        "Catppuccin",
        listOf(
            SchemeVariant(
                "latte", "Latte", dark = false,
                background = 0xFFEFF1F5, surfaceLow = 0xFFE6E9EF, surfaceHigh = 0xFFCCD0DA,
                foreground = 0xFF4C4F69, subtext = 0xFF5C5F77, outline = 0xFFACB0BE,
                accent = 0xFF8839EF, accent2 = 0xFF1E66F5, accent3 = 0xFF40A02B, error = 0xFFD20F39,
                light = listOf(0xFF1E66F5, 0xFF7287FD, 0xFF8839EF, 0xFFEA76CB, 0xFFFE640B),
            ),
            SchemeVariant(
                "frappe", "Frappé", dark = true,
                background = 0xFF303446, surfaceLow = 0xFF292C3C, surfaceHigh = 0xFF414559,
                foreground = 0xFFC6D0F5, subtext = 0xFFB5BFE2, outline = 0xFF626880,
                accent = 0xFFCA9EE6, accent2 = 0xFF8CAAEE, accent3 = 0xFFA6D189, error = 0xFFE78284,
                light = listOf(0xFF8CAAEE, 0xFFBABBF1, 0xFFCA9EE6, 0xFFF4B8E4, 0xFFEF9F76),
            ),
            SchemeVariant(
                "macchiato", "Macchiato", dark = true,
                background = 0xFF24273A, surfaceLow = 0xFF1E2030, surfaceHigh = 0xFF363A4F,
                foreground = 0xFFCAD3F5, subtext = 0xFFB8C0E0, outline = 0xFF5B6078,
                accent = 0xFFC6A0F6, accent2 = 0xFF8AADF4, accent3 = 0xFFA6DA95, error = 0xFFED8796,
                light = listOf(0xFF8AADF4, 0xFFB7BDF8, 0xFFC6A0F6, 0xFFF5BDE6, 0xFFF5A97F),
            ),
            SchemeVariant(
                "mocha", "Mocha", dark = true,
                background = 0xFF1E1E2E, surfaceLow = 0xFF181825, surfaceHigh = 0xFF313244,
                foreground = 0xFFCDD6F4, subtext = 0xFFBAC2DE, outline = 0xFF585B70,
                accent = 0xFFCBA6F7, accent2 = 0xFF89B4FA, accent3 = 0xFFA6E3A1, error = 0xFFF38BA8,
                light = listOf(0xFF89B4FA, 0xFFB4BEFE, 0xFFCBA6F7, 0xFFF5C2E7, 0xFFFAB387),
            ),
        ),
        lightDefault = "latte", darkDefault = "mocha",
    ),

    TOKYO_NIGHT(
        "Tokyo Night",
        listOf(
            SchemeVariant(
                "day", "Day", dark = false,
                background = 0xFFE1E2E7, surfaceLow = 0xFFD0D5E3, surfaceHigh = 0xFFC4C8DA,
                foreground = 0xFF3760BF, subtext = 0xFF6172B0, outline = 0xFFA8AECB,
                accent = 0xFF2E7DE9, accent2 = 0xFF9854F1, accent3 = 0xFF587539, error = 0xFFF52A65,
                light = listOf(0xFF2E7DE9, 0xFF007197, 0xFF9854F1, 0xFFB15C00),
            ),
            SchemeVariant(
                "night", "Night", dark = true,
                background = 0xFF1A1B26, surfaceLow = 0xFF16161E, surfaceHigh = 0xFF292E42,
                foreground = 0xFFC0CAF5, subtext = 0xFFA9B1D6, outline = 0xFF3B4261,
                accent = 0xFF7AA2F7, accent2 = 0xFFBB9AF7, accent3 = 0xFF9ECE6A, error = 0xFFF7768E,
                light = listOf(0xFF7DCFFF, 0xFF7AA2F7, 0xFFBB9AF7, 0xFFFF9E64),
            ),
            SchemeVariant(
                "storm", "Storm", dark = true,
                background = 0xFF24283B, surfaceLow = 0xFF1F2335, surfaceHigh = 0xFF292E42,
                foreground = 0xFFC0CAF5, subtext = 0xFFA9B1D6, outline = 0xFF3B4261,
                accent = 0xFF7AA2F7, accent2 = 0xFFBB9AF7, accent3 = 0xFF9ECE6A, error = 0xFFF7768E,
                light = listOf(0xFF7DCFFF, 0xFF7AA2F7, 0xFFBB9AF7, 0xFFFF9E64),
            ),
            SchemeVariant(
                "moon", "Moon", dark = true,
                background = 0xFF222436, surfaceLow = 0xFF1E2030, surfaceHigh = 0xFF2F334D,
                foreground = 0xFFC8D3F5, subtext = 0xFF828BB8, outline = 0xFF3B4261,
                accent = 0xFF82AAFF, accent2 = 0xFFC099FF, accent3 = 0xFFC3E88D, error = 0xFFFF757F,
                light = listOf(0xFF86E1FC, 0xFF82AAFF, 0xFFC099FF, 0xFFFF966C),
            ),
        ),
        lightDefault = "day", darkDefault = "night",
    ),

    ROSE_PINE(
        "Rosé Pine",
        listOf(
            SchemeVariant(
                "dawn", "Dawn", dark = false,
                background = 0xFFFAF4ED, surfaceLow = 0xFFFFFAF3, surfaceHigh = 0xFFF2E9E1,
                foreground = 0xFF464261, subtext = 0xFF797593, outline = 0xFF9893A5,
                accent = 0xFF907AA9, accent2 = 0xFFD7827E, accent3 = 0xFF56949F, error = 0xFFB4637A,
                light = listOf(0xFF56949F, 0xFF907AA9, 0xFFD7827E, 0xFFEA9D34),
            ),
            SchemeVariant(
                "main", "Main", dark = true,
                background = 0xFF191724, surfaceLow = 0xFF1F1D2E, surfaceHigh = 0xFF26233A,
                foreground = 0xFFE0DEF4, subtext = 0xFF908CAA, outline = 0xFF6E6A86,
                accent = 0xFFC4A7E7, accent2 = 0xFFEBBCBA, accent3 = 0xFF9CCFD8, error = 0xFFEB6F92,
                light = listOf(0xFF9CCFD8, 0xFFC4A7E7, 0xFFEBBCBA, 0xFFF6C177),
            ),
            SchemeVariant(
                "moon", "Moon", dark = true,
                background = 0xFF232136, surfaceLow = 0xFF2A273F, surfaceHigh = 0xFF393552,
                foreground = 0xFFE0DEF4, subtext = 0xFF908CAA, outline = 0xFF6E6A86,
                accent = 0xFFC4A7E7, accent2 = 0xFFEA9A97, accent3 = 0xFF9CCFD8, error = 0xFFEB6F92,
                light = listOf(0xFF9CCFD8, 0xFFC4A7E7, 0xFFEA9A97, 0xFFF6C177),
            ),
        ),
        lightDefault = "dawn", darkDefault = "main",
    ),

    EVERFOREST(
        "Everforest",
        listOf(
            SchemeVariant(
                "light", tr(R.string.light), dark = false,
                background = 0xFFFDF6E3, surfaceLow = 0xFFF4F0D9, surfaceHigh = 0xFFEFEBD4,
                foreground = 0xFF5C6A72, subtext = 0xFF829181, outline = 0xFFBDC3AF,
                accent = 0xFF8DA101, accent2 = 0xFF35A77C, accent3 = 0xFFDFA000, error = 0xFFF85552,
                light = listOf(0xFF35A77C, 0xFF8DA101, 0xFFDFA000, 0xFFF57D26),
            ),
            SchemeVariant(
                "dark", tr(R.string.dark), dark = true,
                background = 0xFF2D353B, surfaceLow = 0xFF343F44, surfaceHigh = 0xFF3D484D,
                foreground = 0xFFD3C6AA, subtext = 0xFF859289, outline = 0xFF475258,
                accent = 0xFFA7C080, accent2 = 0xFF83C092, accent3 = 0xFFDBBC7F, error = 0xFFE67E80,
                light = listOf(0xFF83C092, 0xFFA7C080, 0xFFDBBC7F, 0xFFE69875),
            ),
        ),
        lightDefault = "light", darkDefault = "dark",
    ),

    GRUVBOX(
        "Gruvbox",
        listOf(
            SchemeVariant(
                "light", tr(R.string.light), dark = false,
                background = 0xFFFBF1C7, surfaceLow = 0xFFEBDBB2, surfaceHigh = 0xFFD5C4A1,
                foreground = 0xFF3C3836, subtext = 0xFF7C6F64, outline = 0xFFBDAE93,
                accent = 0xFFAF3A03, accent2 = 0xFF427B58, accent3 = 0xFFB57614, error = 0xFF9D0006,
                light = listOf(0xFF076678, 0xFF427B58, 0xFFB57614, 0xFFAF3A03),
            ),
            SchemeVariant(
                "dark", tr(R.string.dark), dark = true,
                background = 0xFF282828, surfaceLow = 0xFF3C3836, surfaceHigh = 0xFF504945,
                foreground = 0xFFEBDBB2, subtext = 0xFFA89984, outline = 0xFF665C54,
                accent = 0xFFFE8019, accent2 = 0xFF8EC07C, accent3 = 0xFFFABD2F, error = 0xFFFB4934,
                light = listOf(0xFF83A598, 0xFF8EC07C, 0xFFFABD2F, 0xFFFE8019),
            ),
        ),
        lightDefault = "light", darkDefault = "dark",
    ),

    NORD(
        "Nord",
        listOf(
            // Nord has no light theme of its own (nordtheme/nord#46): this is the Snow Storm inversion (nord4–6 as
            // pages, Polar Night as ink, Frost deepened for links and buttons), so it's marked as ours, not Nord's.
            SchemeVariant(
                "light", "Nord Light", dark = false,
                background = 0xFFECEFF4, surfaceLow = 0xFFE5E9F0, surfaceHigh = 0xFFD8DEE9,
                foreground = 0xFF2E3440, subtext = 0xFF4C566A, outline = 0xFFB0B8C6,
                accent = 0xFF5E81AC, accent2 = 0xFF81A1C1, accent3 = 0xFF8FBCBB, error = 0xFFBF616A,
                light = listOf(0xFF5E81AC, 0xFF81A1C1, 0xFF8FBCBB, 0xFF88C0D0),
            ),
            SchemeVariant(
                "nord", "Nord", dark = true,
                background = 0xFF2E3440, surfaceLow = 0xFF3B4252, surfaceHigh = 0xFF434C5E,
                foreground = 0xFFD8DEE9, subtext = 0xFFA5ABB6, outline = 0xFF4C566A,
                accent = 0xFF88C0D0, accent2 = 0xFF81A1C1, accent3 = 0xFFA3BE8C, error = 0xFFBF616A,
                light = listOf(0xFF8FBCBB, 0xFF88C0D0, 0xFF81A1C1, 0xFF5E81AC),
            ),
        ),
        lightDefault = "light", darkDefault = "nord",
    ),

    DRACULA(
        "Dracula",
        listOf(
            // Alucard, Dracula's own light sibling.
            SchemeVariant(
                "alucard", "Alucard", dark = false,
                background = 0xFFFFFBEB, surfaceLow = 0xFFF5F1E3, surfaceHigh = 0xFFEAE6D8,
                foreground = 0xFF1F1F1F, subtext = 0xFF6C664B, outline = 0xFFCFCFDE,
                accent = 0xFF644AC9, accent2 = 0xFF036A96, accent3 = 0xFF14710A, error = 0xFFCB3A2A,
                light = listOf(0xFF036A96, 0xFF644AC9, 0xFFA3144D, 0xFFA34D14),
            ),
            SchemeVariant(
                "dracula", "Dracula", dark = true,
                background = 0xFF282A36, surfaceLow = 0xFF44475A, surfaceHigh = 0xFF4E5166,
                foreground = 0xFFF8F8F2, subtext = 0xFF6272A4, outline = 0xFF44475A,
                accent = 0xFFBD93F9, accent2 = 0xFF8BE9FD, accent3 = 0xFF50FA7B, error = 0xFFFF5555,
                light = listOf(0xFF8BE9FD, 0xFFBD93F9, 0xFFFF79C6, 0xFFFFB86C),
            ),
        ),
        lightDefault = "alucard", darkDefault = "dracula",
    ),

    KANAGAWA(
        "Kanagawa",
        listOf(
            SchemeVariant(
                "lotus", "Lotus", dark = false,
                background = 0xFFF2ECBC, surfaceLow = 0xFFE7DBA0, surfaceHigh = 0xFFE4D794,
                foreground = 0xFF545464, subtext = 0xFF716E61, outline = 0xFFA09CAC,
                accent = 0xFF4D699B, accent2 = 0xFFB35B79, accent3 = 0xFF6F894E, error = 0xFFE82424,
                light = listOf(0xFF4D699B, 0xFF624C83, 0xFFB35B79, 0xFFCC6D00),
            ),
            SchemeVariant(
                "wave", "Wave", dark = true,
                background = 0xFF1F1F28, surfaceLow = 0xFF2A2A37, surfaceHigh = 0xFF363646,
                foreground = 0xFFDCD7BA, subtext = 0xFFC8C093, outline = 0xFF54546D,
                accent = 0xFF7E9CD8, accent2 = 0xFFD27E99, accent3 = 0xFF98BB6C, error = 0xFFE82424,
                light = listOf(0xFF7E9CD8, 0xFF957FB8, 0xFFD27E99, 0xFFFFA066),
            ),
            SchemeVariant(
                "dragon", "Dragon", dark = true,
                background = 0xFF181616, surfaceLow = 0xFF282727, surfaceHigh = 0xFF393836,
                foreground = 0xFFC5C9C5, subtext = 0xFFA6A69C, outline = 0xFF625E5A,
                accent = 0xFF8BA4B0, accent2 = 0xFFA292A3, accent3 = 0xFF87A987, error = 0xFFE82424,
                light = listOf(0xFF8BA4B0, 0xFFA292A3, 0xFF87A987, 0xFFB98D7B),
            ),
        ),
        lightDefault = "lotus", darkDefault = "wave",
    ),

    SOLARIZED(
        "Solarized",
        listOf(
            SchemeVariant(
                "light", tr(R.string.light), dark = false,
                background = 0xFFFDF6E3, surfaceLow = 0xFFEEE8D5, surfaceHigh = 0xFFE4DCC3,
                foreground = 0xFF657B83, subtext = 0xFF586E75, outline = 0xFF93A1A1,
                accent = 0xFF268BD2, accent2 = 0xFF2AA198, accent3 = 0xFF859900, error = 0xFFDC322F,
                light = listOf(0xFF268BD2, 0xFF2AA198, 0xFF859900, 0xFFB58900),
            ),
            SchemeVariant(
                "dark", tr(R.string.dark), dark = true,
                background = 0xFF002B36, surfaceLow = 0xFF073642, surfaceHigh = 0xFF0B3B48,
                foreground = 0xFF839496, subtext = 0xFF586E75, outline = 0xFF586E75,
                accent = 0xFF268BD2, accent2 = 0xFF2AA198, accent3 = 0xFF859900, error = 0xFFDC322F,
                light = listOf(0xFF268BD2, 0xFF2AA198, 0xFF859900, 0xFFB58900),
            ),
        ),
        lightDefault = "light", darkDefault = "dark",
    ),

    AYU(
        "Ayu",
        listOf(
            SchemeVariant(
                "light", tr(R.string.light), dark = false,
                background = 0xFFFCFCFC, surfaceLow = 0xFFF8F9FA, surfaceHigh = 0xFFEBEEF0,
                foreground = 0xFF5C6166, subtext = 0xFF8A9199, outline = 0xFFDDE2E7,
                accent = 0xFFF29718, accent2 = 0xFF22A4E6, accent3 = 0xFF86B300, error = 0xFFE65050,
                light = listOf(0xFF22A4E6, 0xFFF29718, 0xFFFF7383, 0xFF86B300),
            ),
            SchemeVariant(
                "dark", tr(R.string.dark), dark = true,
                background = 0xFF0D1017, surfaceLow = 0xFF10141C, surfaceHigh = 0xFF141821,
                foreground = 0xFFB3B1AD, subtext = 0xFF5C6773, outline = 0xFF1B1F29,
                accent = 0xFFE6B450, accent2 = 0xFF59C2FF, accent3 = 0xFFAAD94C, error = 0xFFD95757,
                light = listOf(0xFF59C2FF, 0xFFE6B450, 0xFFF07178, 0xFFAAD94C),
            ),
            SchemeVariant(
                "mirage", "Mirage", dark = true,
                background = 0xFF1F2430, surfaceLow = 0xFF242936, surfaceHigh = 0xFF282E3B,
                foreground = 0xFFCCCAC2, subtext = 0xFF6E7C8F, outline = 0xFF171B24,
                accent = 0xFFFFCC66, accent2 = 0xFF73D0FF, accent3 = 0xFFD5FF80, error = 0xFFFF6666,
                light = listOf(0xFF73D0FF, 0xFFFFCC66, 0xFFDFBFFF, 0xFFD5FF80),
            ),
        ),
        lightDefault = "light", darkDefault = "dark",
    ),
    ;

    /** This family's variant by [id], or null (Prism's own colors have none). */
    fun variant(id: String?): SchemeVariant? = variants.firstOrNull { it.id == id }

    /**
     * The variant to wear in this mode: [stored] when it is one of this family's and is that dark (or light),
     * otherwise the family's default for the mode. A pick of Mocha stays Mocha through a switch to light and back
     * (the stored pick is kept), while light pages get a light palette in the meantime.
     */
    fun forMode(dark: Boolean, stored: String?): SchemeVariant? =
        variant(stored)?.takeIf { it.dark == dark } ?: variant(if (dark) darkDefault else lightDefault)
}

/**
 * One palette of a [SchemeFamily]: the colors that scheme's own project publishes, as ARGB.
 *
 * [surfaceLow] and [surfaceHigh] are the palette's own steps beside its background (Catppuccin's mantle and
 * surface0, Tokyo Night's bg_dark and bg_highlight, Gruvbox's bg1/bg2…), used for panels and the raised surfaces
 * Material draws cards on. [subtext] is its quieter ink (subtext1, subtle, grey, comment…), [outline] its border
 * or overlay; [accent] is the signature color, with [accent2]/[accent3] the two it pairs with (links, selections,
 * progress). [light] is the run of colors Prism's split light, progress bar and play ring draw, from that
 * palette's accents. [error] is its red.
 *
 * The roles Material also needs (containers, dim/bright surfaces, inverse colors) are worked out from these in
 * [com.prism.music.ui.theme.Schemes], not stored: the published palettes don't define them.
 */
class SchemeVariant(
    val id: String,
    val name: String,
    val dark: Boolean,
    val background: Long,
    val surfaceLow: Long,
    val surfaceHigh: Long,
    val foreground: Long,
    val subtext: Long,
    val outline: Long,
    val accent: Long,
    val accent2: Long,
    val accent3: Long,
    val error: Long,
    val light: List<Long>,
)
