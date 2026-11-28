package com.prism.music.analysis

import com.prism.music.R

/** What kind of thing a machine tag says. Order is the order a song's tags are shown in. */
enum class TagCategory(val label: Int) {
    GENRE(R.string.analysis_cat_genre),
    STYLE(R.string.analysis_cat_style),
    MOOD(R.string.analysis_cat_mood),
    INSTRUMENT(R.string.analysis_cat_instruments),
    VOCALS(R.string.analysis_cat_vocals),
    CHARACTER(R.string.analysis_cat_character),
    SOUNDSCAPE(R.string.analysis_cat_sounds),
}

/** How sure a machine tag is, and what the listener made of it. Stored as its ordinal: never reorder. */
enum class TagStatus {
    /** Above its category's sure line: shown as the song's. */
    AUTO,
    /** Between the maybe and sure lines: shown as a suggestion to accept or reject. */
    REVIEW,
    /** The listener said yes. Kept through re-analysis. */
    ACCEPTED,
    /** The listener said no. Kept through re-analysis, never shown again for this song. */
    REJECTED,
    ;

    val listenerDecided: Boolean get() = this == ACCEPTED || this == REJECTED
}

/** One tag the tagger found: [key] is stable ("instrument:piano"), [confidence] the pooled model probability. */
data class PredictedTag(val key: String, val category: TagCategory, val confidence: Float, val status: TagStatus)

/**
 * Prism's tag vocabulary, read from YAMNet's AudioSet classes (indices into its 521 outputs, from yamnet_class_map.csv,
 * CC BY-SA 4.0). A tag's score is the highest of its classes'; a main genre's also takes its subgenres' (heavy metal
 * says metal). Each category has a *sure* and a *maybe* line, set from YAMNet's scores on real music (its genre and
 * mood outputs are low and spread; instruments and singing are confident): see docs/audio-analysis.md.
 *
 * Main genres are Prism's own categories ([com.prism.music.data.meta.Genres], shown translated); styles (subgenres)
 * are genre names used as they are around the world (Techno, Bossa…) and shown as such.
 */
object TagVocabulary {
    class Def(
        val key: String,
        val category: TagCategory,
        val classes: IntArray,
        /** The word shown; null for a genre (its [com.prism.music.data.meta.Genres] name) or a style ([english]). */
        val label: Int?,
        val english: String,
        /** A style's main genre (a Prism category name). */
        val parent: String? = null,
    )

    private fun d(key: String, cat: TagCategory, label: Int?, english: String, vararg classes: Int, parent: String? = null) =
        Def(key, cat, classes, label, english, parent)

    private val G = TagCategory.GENRE
    private val S = TagCategory.STYLE
    private val M = TagCategory.MOOD
    private val I = TagCategory.INSTRUMENT
    private val V = TagCategory.VOCALS
    private val C = TagCategory.CHARACTER
    private val N = TagCategory.SOUNDSCAPE

    /** Styles: AudioSet's genre classes, each under the Prism genre it belongs to. */
    val styles: List<Def> = listOf(
        d("style:punk", S, null, "Punk", 216, parent = "Rock"),
        d("style:grunge", S, null, "Grunge", 217, parent = "Indie & alternative"),
        d("style:progressive-rock", S, null, "Progressive rock", 218, parent = "Rock"),
        d("style:rock-and-roll", S, null, "Rock and roll", 219, parent = "Rock"),
        d("style:psychedelic-rock", S, null, "Psychedelic rock", 220, parent = "Rock"),
        d("style:heavy-metal", S, null, "Heavy metal", 215, parent = "Metal"),
        d("style:funk", S, null, "Funk", 227, parent = "R&B & soul"),
        d("style:soul", S, null, "Soul", 222, parent = "R&B & soul"),
        d("style:ska", S, null, "Ska", 258, parent = "Reggae & caribbean"),
        d("style:bluegrass", S, null, "Bluegrass", 226, parent = "Country & Americana"),
        d("style:swing", S, null, "Swing", 225, parent = "Jazz"),
        d("style:flamenco", S, null, "Flamenco", 245, parent = "Folk & acoustic"),
        d("style:traditional", S, null, "Traditional", 259, parent = "Folk & acoustic"),
        d("style:disco", S, null, "Disco", 231, parent = "Dance & electronic"),
        d("style:house", S, null, "House", 235, parent = "Dance & electronic"),
        d("style:techno", S, null, "Techno", 236, parent = "Dance & electronic"),
        d("style:dubstep", S, null, "Dubstep", 237, parent = "Dance & electronic"),
        d("style:drum-and-bass", S, null, "Drum and bass", 238, parent = "Dance & electronic"),
        d("style:electronica", S, null, "Electronica", 239, parent = "Dance & electronic"),
        d("style:edm", S, null, "EDM", 240, parent = "Dance & electronic"),
        d("style:ambient", S, null, "Ambient", 241, parent = "Dance & electronic"),
        d("style:trance", S, null, "Trance", 242, parent = "Dance & electronic"),
        d("style:opera", S, null, "Opera", 233, parent = "Classical"),
        d("style:new-age", S, null, "New age", 248, parent = "Classical"),
        d("style:latin", S, null, "Latin", 243, parent = "Pop"),
        d("style:salsa", S, null, "Salsa", 244, parent = "Pop"),
        d("style:afrobeat", S, null, "Afrobeat", 252, parent = "Pop"),
        d("style:african", S, null, "African", 251, parent = "Pop"),
        d("style:bollywood", S, null, "Bollywood", 257, parent = "Pop"),
        d("style:carnatic", S, null, "Carnatic", 256, parent = "Pop"),
        d("style:middle-eastern", S, null, "Middle Eastern", 229, parent = "Pop"),
        d("style:gospel", S, null, "Gospel", 254, parent = "Christian & gospel"),
    )

    /** Main genres (Prism's categories) with the classes that name them directly. */
    val genres: List<Def> = listOf(
        d("genre:Pop", G, null, "Pop", 211),
        d("genre:Hip-hop", G, null, "Hip-hop", 212),
        d("genre:Rock", G, null, "Rock", 214),
        d("genre:Metal", G, null, "Metal", 215),
        d("genre:Indie & alternative", G, null, "Indie & alternative", 260),
        d("genre:R&B & soul", G, null, "R&B & soul", 221),
        d("genre:Reggae & caribbean", G, null, "Reggae & caribbean", 223),
        d("genre:Country & Americana", G, null, "Country & Americana", 224),
        d("genre:Jazz", G, null, "Jazz", 230),
        d("genre:Folk & acoustic", G, null, "Folk & acoustic", 228),
        d("genre:Dance & electronic", G, null, "Dance & electronic", 234),
        d("genre:Classical", G, null, "Classical", 232),
        d("genre:Blues", G, null, "Blues", 246),
        d("genre:Christian & gospel", G, null, "Christian & gospel", 253),
        d("genre:Family", G, null, "Family", 247),
        d("genre:Soundtracks & musicals", G, null, "Soundtracks & musicals", 265, 263),
    )

    val others: List<Def> = listOf(
        d("mood:happy", M, R.string.tag_happy, "Happy", 271),
        d("mood:sad", M, R.string.tag_sad, "Sad", 272),
        d("mood:tender", M, R.string.tag_tender, "Tender", 273),
        d("mood:exciting", M, R.string.tag_exciting, "Exciting", 274),
        d("mood:angry", M, R.string.tag_angry, "Angry", 275),
        d("mood:scary", M, R.string.tag_scary, "Scary", 276),

        d("instrument:guitar", I, R.string.tag_guitar, "Guitar", 135, 134),
        d("instrument:electric-guitar", I, R.string.tag_electric_guitar, "Electric guitar", 136),
        d("instrument:acoustic-guitar", I, R.string.tag_acoustic_guitar, "Acoustic guitar", 138, 141),
        d("instrument:bass", I, R.string.tag_bass, "Bass", 137, 189),
        d("instrument:piano", I, R.string.tag_piano, "Piano", 148),
        d("instrument:electric-piano", I, R.string.tag_electric_piano, "Electric piano", 149),
        d("instrument:organ", I, R.string.tag_organ, "Organ", 150, 151, 152),
        d("instrument:synthesizer", I, R.string.tag_synthesizer, "Synthesizer", 153, 154),
        d("instrument:drums", I, R.string.tag_drums, "Drums", 157, 159, 160, 163, 166, 167),
        d("instrument:drum-machine", I, R.string.tag_drum_machine, "Drum machine", 158),
        d("instrument:percussion", I, R.string.tag_percussion, "Percussion", 156, 165, 169, 171),
        d("instrument:strings", I, R.string.tag_strings, "Strings", 184, 185, 186, 188),
        d("instrument:orchestra", I, R.string.tag_orchestra, "Orchestra", 179),
        d("instrument:brass", I, R.string.tag_brass, "Brass", 180, 181, 182, 183),
        d("instrument:saxophone", I, R.string.tag_saxophone, "Saxophone", 192),
        d("instrument:woodwind", I, R.string.tag_woodwind, "Flute and woodwind", 190, 191, 193),
        d("instrument:harp", I, R.string.tag_harp, "Harp", 194),
        d("instrument:bells", I, R.string.tag_bells, "Bells and chimes", 195, 200, 173, 176, 201),
        d("instrument:mallets", I, R.string.tag_mallets, "Marimba and vibraphone", 174, 175, 177, 178),
        d("instrument:harmonica", I, R.string.tag_harmonica, "Harmonica", 203),
        d("instrument:accordion", I, R.string.tag_accordion, "Accordion", 204),
        d("instrument:folk-strings", I, R.string.tag_folk_strings, "Banjo, mandolin and ukulele", 142, 144, 146, 145),
        d("instrument:sitar", I, R.string.tag_sitar, "Sitar", 143),

        d("vocals:singing", V, R.string.tag_singing, "Singing", 24, 261),
        d("vocals:choir", V, R.string.tag_choir, "Choir", 25, 250),
        d("vocals:rap", V, R.string.tag_rap, "Rap", 31),
        d("vocals:speech", V, R.string.tag_speech, "Speech", 0, 3),
        d("vocals:humming", V, R.string.tag_humming, "Humming", 32),
        d("vocals:chant", V, R.string.tag_chant, "Chant", 27, 28),

        d("style-kind:lullaby", S, R.string.tag_lullaby, "Lullaby", 266),
        d("style-kind:video-game", S, R.string.tag_video_game, "Video game music", 267),
        d("style-kind:christmas", S, R.string.tag_christmas, "Christmas", 268),
        d("style-kind:background", S, R.string.tag_background, "Background music", 262),
        d("style-kind:dance", S, R.string.tag_dance, "Dance", 269),
        d("style-kind:meditative", S, R.string.tag_meditative, "Meditative", 209, 28),

        d("character:electronic", C, R.string.tag_electronic, "Electronic", 234, 153, 158, 239, 240, 235, 236),

        d("sounds:rain", N, R.string.tag_rain, "Rain", 283, 284, 285, 280),
        d("sounds:water", N, R.string.tag_water, "Water and waves", 282, 286, 287, 288, 289),
        d("sounds:wind", N, R.string.tag_wind, "Wind", 277, 278),
        d("sounds:birds", N, R.string.tag_birds, "Birdsong", 106, 107, 108),
        d("sounds:fire", N, R.string.tag_fire, "Fire", 292, 293),
        d("sounds:noise", N, R.string.tag_noise, "White noise", 514, 515),
    )

    /**
     * Derived from other scores rather than one class: acoustic is a class score's, Instrumental was only ever *inferred*
     * (no voice class), which made it a meaningless 99 % "suggestion" on nearly every instrumental track and drowned the
     * tags the model actually heard. It's gone as a tag; [voice] still feeds the sleep score's vocals factor and the
     * Sound profile's figures. Kept for the label of rows written before it went (removed as they load).
     */
    const val INSTRUMENTAL = "vocals:instrumental"
    const val ACOUSTIC = "character:acoustic"

    val all: List<Def> = genres + styles + others
    private val byKey = all.associateBy { it.key }
    fun def(key: String): Def? = byKey[key]

    /** Above [sure] a tag is the song's ([TagStatus.AUTO]); from [maybe] up it's offered for review. */
    class Lines(val sure: Float, val maybe: Float)

    fun lines(c: TagCategory): Lines = when (c) {
        TagCategory.GENRE -> Lines(0.25f, 0.10f)
        TagCategory.STYLE -> Lines(0.20f, 0.08f)
        TagCategory.MOOD -> Lines(0.15f, 0.06f)
        TagCategory.INSTRUMENT -> Lines(0.30f, 0.12f)
        TagCategory.VOCALS -> Lines(0.30f, 0.12f)
        TagCategory.CHARACTER -> Lines(0.35f, 0.15f)
        TagCategory.SOUNDSCAPE -> Lines(0.25f, 0.10f)
    }

    /** Classes that say there's a voice in the song (for [INSTRUMENTAL]). */
    private val VOICE = intArrayOf(0, 3, 24, 25, 27, 28, 29, 30, 31, 32, 250)
    /** Classes of acoustic instruments and genres played on them (for [ACOUSTIC]). */
    private val ACOUSTIC_CLASSES = intArrayOf(138, 148, 184, 185, 186, 188, 179, 194, 191, 193, 232, 228, 226)
    private val ELECTRONIC_CLASSES = intArrayOf(234, 153, 158, 239, 240, 235, 236, 237, 238, 242)

    private fun max(scores: FloatArray, classes: IntArray): Float {
        var m = 0f
        for (c in classes) if (c in scores.indices && scores[c] > m) m = scores[c]
        return m
    }

    /** How much there's a voice in the song (0 … 1), from the tagger's [scores]. */
    fun voice(scores: FloatArray): Float = max(scores, VOICE)

    /**
     * The tags [scores] (a tagger's pooled class probabilities) support, each with its status by its category's lines;
     * anything under the maybe line is left out.
     */
    fun tags(scores: FloatArray): List<PredictedTag> {
        val out = ArrayList<PredictedTag>()
        fun add(key: String, cat: TagCategory, score: Float) {
            val l = lines(cat)
            if (score >= l.maybe) out += PredictedTag(key, cat, score, if (score >= l.sure) TagStatus.AUTO else TagStatus.REVIEW)
        }
        val styleScores = styles.associate { it.key to max(scores, it.classes) }
        for (g in genres) {
            val own = max(scores, g.classes)
            val fromStyles = styles.filter { it.parent == g.english }.maxOfOrNull { styleScores.getValue(it.key) } ?: 0f
            add(g.key, G, maxOf(own, fromStyles))
        }
        for (s in styles) add(s.key, S, styleScores.getValue(s.key))
        for (o in others) add(o.key, o.category, max(scores, o.classes))
        // Acoustic: acoustic instruments clearly ahead of electronic ones (a class score, unlike the old Instrumental).
        val acoustic = max(scores, ACOUSTIC_CLASSES)
        val electronic = max(scores, ELECTRONIC_CLASSES)
        if (acoustic > 0.15f && electronic < acoustic / 3) add(ACOUSTIC, C, acoustic)
        return out
    }

    /** The probabilities [Similarity] compares songs by: genres, styles, moods, instruments, vocals and character. */
    fun profile(scores: FloatArray): FloatArray {
        val defs = all
        return FloatArray(defs.size) { max(scores, defs[it].classes) }
    }
}
