package com.prism.music.analysis

import ai.onnxruntime.OrtEnvironment
import com.prism.music.playback.KeyDetector
import com.prism.music.playback.LoudnessMeter
import com.prism.music.playback.Tempo
import com.prism.music.playback.VideoSync
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The whole analysis on real recordings, as the song scan runs it: mono at 44.1 kHz (`ffmpeg -ac 1 -ar 44100 -f f32le`,
 * files named `<label>_<name>.f32` in the folder ANALYSIS_DUMPS names) decimated to ~11 kHz as Downmix does, its
 * loudness, descriptors, the tagger's windows heard by YAMNet (the ONNX file YAMNET_ONNX names, on ONNX Runtime's
 * desktop build), the sleep score and tags, and each song's nearest neighbour. Prints a report; the label checks are
 * loose (a genre word among the tags), as the folder's songs are whatever was at hand.
 */
class RealSongsAnalysisTest {
    @Test fun realSongs() {
        val dir = System.getenv("ANALYSIS_DUMPS")?.let(::File)
        val model = System.getenv("YAMNET_ONNX")?.let(::File)
        assumeTrue("ANALYSIS_DUMPS not set", dir != null && dir.isDirectory)
        val env = OrtEnvironment.getEnvironment()
        val tagger = model?.takeIf { it.isFile }?.let { YamnetTagger(env, env.createSession(it.path, YamnetTagger.options())) }
        val entries = ArrayList<Pair<String, SoundEntry>>()
        var hits = 0; var labelled = 0
        for (file in dir!!.listFiles { f -> f.name.endsWith(".f32") }!!.sortedBy { it.name }) {
            val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val raw = FloatArray(bytes.remaining()).also { bytes.get(it) }
            val seconds = raw.size / 44_100.0
            if (seconds < 25) continue
            // The scan's path: decimate by 4 (a box average, as Downmix), at most 15 minutes.
            val t0 = System.nanoTime()
            val factor = 4
            val count = minOf(raw.size / factor, 15 * 60 * 11_025)
            val pcm = FloatArray(count) { i -> var s = 0f; for (k in 0 until factor) s += raw[i * factor + k]; s / factor }
            val rate = 44_100 / factor
            val meter = LoudnessMeter(rate, 1)
            for (v in pcm) meter.sample(v)
            val tap = TaggerTap(44_100, (seconds * 1000).toLong())
            for (v in raw) tap.sample(v)
            val t1 = System.nanoTime()
            val bands = VideoSync.bandFeatures(pcm, rate, count)
            val frameMs = (rate / 100) * 1000.0 / rate
            val tempo = Tempo.analyze(bands, frameMs, VideoSync.WINDOW / 2 * 1000.0 / rate)
            val chroma = KeyDetector.chroma(pcm, rate, count)
            val t2 = System.nanoTime()
            val f = Descriptors.extract(
                Descriptors.Input(pcm, count, rate, bands, frameMs, tempo, chroma?.let(KeyDetector::best), chroma, meter.stats(), null, null),
            )
            val t3 = System.nanoTime()
            val out = tagger?.run(tap.windows())
            val t4 = System.nanoTime()
            // Equalized (Normalize on, Prism's default) against the recording played at its own level.
            val sleep = SleepScore.of(f, out?.scores, SleepWeights(normalized = true))
            val rawLevel = SleepScore.of(f, out?.scores, SleepWeights(normalized = false))
            val tags = out?.let { TagVocabulary.tags(it.scores) }.orEmpty().sortedByDescending { it.confidence }
            val name = file.name.removeSuffix(".f32")
            println("=== $name  (%.0f s)".format(seconds))
            println("  decode-side %d ms · scan %d ms · descriptors %d ms · tagger %d ms (%d frames)".format(
                (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, (t4 - t3) / 1_000_000, out?.frames ?: 0))
            println("  %s BPM (pulse %.2f, steady %s) · %.1f LUFS · LRA %s · onsets %.1f/s · perc %.2f · centroid %.0f Hz · bright %.3f · dance %.2f · sudden %.1f/min (+%.0f LU)".format(
                f.bpm?.let { "%.0f".format(it) } ?: "-", f.pulseClarity, f.tempoStability?.let { "%.2f".format(it) } ?: "-", f.lufs ?: Float.NaN,
                f.lra?.let { "%.1f".format(it) } ?: "-", f.onsetRate, f.percussive, f.centroidHz, f.brightShare, f.danceability, f.suddenPerMinute, f.worstRise))
            println("  SLEEP ${sleep.score} normalized / ${rawLevel.score} raw${if (sleep.capped) " (capped)" else ""}  for: ${sleep.forIt().joinToString { it.factor.name }}  against: ${sleep.against().joinToString { it.factor.name + " " + it.detail }}")
            println("  TAGS " + tags.take(12).joinToString { "${it.key}=${"%.2f".format(it.confidence)}${if (it.status == TagStatus.REVIEW) "?" else ""}" })
            val label = name.substringBefore('_')
            val words = mapOf(
                "rock" to listOf("genre:Rock", "genre:Metal", "genre:Indie", "instrument:electric-guitar"),
                "metal" to listOf("genre:Metal", "genre:Rock", "style:heavy-metal", "style:punk"),
                "techno" to listOf("genre:Dance & electronic", "style:techno", "style:house", "style:electronica", "style:edm", "character:electronic"),
                "hiphop" to listOf("genre:Hip-hop", "vocals:rap"),
                "ambient" to listOf("style:ambient", "style:new-age", "genre:Dance & electronic", "character:electronic", "style-kind:meditative"),
                "piano" to listOf("instrument:piano", "instrument:electric-piano"),
                "jazz" to listOf("genre:Jazz", "instrument:saxophone", "instrument:piano", "style:swing"),
                "lullaby" to listOf("style-kind:lullaby", "genre:Folk & acoustic", "mood:tender", "vocals:singing", "genre:Family"),
            )[label]
            if (words != null && tags.isNotEmpty()) {
                labelled++
                if (tags.any { it.key in words && it.status != TagStatus.REJECTED }) hits++ else println("  (no ${label} word among its tags)")
            }
            entries += name to SoundEntry(name, f, out?.embedding?.let { AnalysisStore.unpackEmbedding(AnalysisStore.packEmbedding(it)) }, out?.scores?.let(TagVocabulary::profile))
        }
        tagger?.close()
        assumeTrue("no songs", entries.isNotEmpty())
        val sim = Similarity(DescriptorStats.of(entries.map { it.second.features }), AudioTaste.mean(entries.mapNotNull { it.second.embedding }))
        println("=== embedding similarity (centred) rows")
        for ((name, e) in entries) println("  %-20s ".format(name) + entries.joinToString(" ") { "%.2f".format(sim.sound(e.embedding ?: return@joinToString "-", it.second.embedding ?: return@joinToString "-")) })
        println("=== nearest neighbours")
        for ((name, e) in entries) {
            val near = entries.filter { it.first != name }.maxByOrNull { sim.between(e, it.second) }
            println("  $name -> ${near?.first} (%.2f)".format(near?.let { sim.between(e, it.second) } ?: 0.0))
        }
        println("=== sleep order (Normalize on): " + entries.sortedByDescending { SleepScore.of(it.second.features).score }.joinToString { it.first })
        if (labelled > 0) println("=== a label word among the tags: $hits of $labelled")
    }
}
