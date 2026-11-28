package com.prism.music.analysis

import android.util.Log
import com.prism.music.data.ModelStore
import com.prism.music.data.db.AnalysisDao
import com.prism.music.data.db.SleepVoteEntity
import com.prism.music.data.db.SongAnalysisEntity
import com.prism.music.data.db.SongAutoTagEntity
import com.prism.music.data.prefs.AnalysisPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The sound analysis' store and the song scan's [SongAnalyzer]: what each song's analysis says (the `song_analysis`
 * table and an in-memory index of it), its machine tags, the listener's sleep votes and listening signals, and the
 * sleep score worked out from them with the listener's weights and feedback.
 *
 * Fast paths ([needs], [sleepScore], [entry]) read memory only; the index loads from the database once, in the
 * background, at the first ask. The tagger's network is opened at the first song that needs it and closed again after
 * [IDLE_CLOSE_S] seconds without one (~25 MB).
 */
class AnalysisStore(
    private val dao: AnalysisDao,
    private val models: ModelStore,
    private val prefs: () -> AnalysisPrefs,
    private val scope: CoroutineScope,
    /** The tagger, made when it's first needed (null: none can run here). Tests hand in their own. */
    private val makeTagger: () -> AudioTagger? = { null },
) : SongAnalyzer {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Song id -> (descriptors' version, tagger version or 0). Loaded with the index. */
    private val versions = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val entries = ConcurrentHashMap<String, SoundEntry>()
    /** Song id -> its tagger's pooled class probabilities (for the sleep score's vocals, instruments and mood). */
    private val scores = ConcurrentHashMap<String, FloatArray>()
    private val votes = ConcurrentHashMap<String, Int>()
    @Volatile private var loaded = false
    private val loadLock = Mutex()

    @Volatile private var personal: SleepPersonal? = null
    @Volatile private var weights = SleepWeights()
    private val scoreCache = ConcurrentHashMap<String, Int>()

    @Volatile private var stats: DescriptorStats = DescriptorStats.DEFAULT
    @Volatile var similarity: Similarity = Similarity(stats)
        private set

    /** Bumped whenever an analysis, a vote or a tag's status changes: screens showing them read again. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    /** How many songs are analysed (shown in Settings). */
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    // ------------------------------------------------------------------ loading

    /** Reads the whole index once (at the first ask; a few hundred ms for 10,000 songs, off the main thread). */
    suspend fun load() = loadLock.withLock {
        if (loaded) return@withLock
        val rows = runCatching { dao.all() }.getOrDefault(emptyList())
        for (r in rows) remember(r)
        runCatching { dao.votes() }.getOrDefault(emptyList()).forEach { votes[it.songId] = it.vote }
        loaded = true
        refreshStats()
        refitPersonal()
        _count.value = entries.size
        bump()
    }

    private fun ensureLoading() {
        if (!loaded) scope.launch(Dispatchers.IO) { load() }
    }

    private fun remember(r: SongAnalysisEntity) {
        versions[r.songId] = r.algorithmVersion to (r.taggerVersion ?: 0)
        val features = runCatching { json.decodeFromString(SongFeatures.serializer(), r.featuresJson) }.getOrNull() ?: return
        val s = r.scores?.let(::unpackScores)
        if (s != null) scores[r.songId] = s else scores.remove(r.songId)
        entries[r.songId] = SoundEntry(r.songId, features, r.embedding?.let(::unpackEmbedding), s?.let(TagVocabulary::profile), r.sleepScore)
        scoreCache.remove(r.songId)
    }

    private fun refreshStats() {
        stats = DescriptorStats.of(entries.values.map { it.features })
        // The average to read embeddings against, once there are enough songs for it to be the library's own.
        val embeddings = entries.values.mapNotNull { it.embedding }
        similarity = Similarity(stats, if (embeddings.size >= 20) AudioTaste.mean(embeddings) else null)
    }

    private fun bump() { _changes.value = _changes.value + 1 }

    // ------------------------------------------------------------------ SongAnalyzer

    override fun needs(id: String): Boolean {
        if (!loaded) { ensureLoading(); return false }
        val v = versions[id] ?: return true
        if (v.first < Descriptors.VERSION) return true
        return taggerReady() && v.second != YamnetTagger.VERSION
    }

    override fun taggerReady(): Boolean {
        if (!prefs().tagger || !models.ready(ModelStore.Model.YAMNET)) return false
        val runtime = ModelStore.Model.singRuntime() ?: return false
        return models.ready(runtime)
    }

    override fun record(id: String, songId: String?, features: SongFeatures, windows: List<FloatArray>) {
        val out = if (windows.isNotEmpty() && taggerReady()) runTagger(windows) else null
        val baseScore = SleepScore.of(features, out?.scores).base
        val now = System.currentTimeMillis()
        val row = SongAnalysisEntity(
            songId = id,
            algorithmVersion = Descriptors.VERSION,
            taggerId = out?.let { YamnetTagger.ID },
            taggerVersion = out?.let { YamnetTagger.VERSION },
            analyzedAt = now,
            featuresJson = json.encodeToString(SongFeatures.serializer(), features),
            scores = out?.scores?.let(::packScores),
            embedding = out?.embedding?.let(::packEmbedding),
            sleepScore = baseScore,
        )
        val tags = out?.let { TagVocabulary.tags(it.scores) }.orEmpty().map {
            SongAutoTagEntity(id, it.key, it.category.name, it.confidence, "${YamnetTagger.ID}:${YamnetTagger.VERSION}", it.status.ordinal, now)
        }
        // The queue's song gets the same analysis when it played as another form (a picked version, its video) and has
        // none of its own: what's heard is what's described.
        val alias = songId?.takeIf { it != id && (versions[it]?.first ?: 0) < Descriptors.VERSION }
        runBlocking(Dispatchers.IO) {
            dao.put(row)
            if (out != null) dao.replaceMachineTags(id, tags)
            if (alias != null) {
                dao.put(row.copy(songId = alias))
                if (out != null) dao.replaceMachineTags(alias, tags.map { it.copy(songId = alias) })
            }
        }
        remember(row)
        alias?.let { remember(row.copy(songId = it)) }
        _count.value = entries.size
        // The library's spread moves slowly: worked out again every 50 songs.
        if (entries.size < 50 || entries.size % 50 == 0) refreshStats()
        bump()
    }

    // ------------------------------------------------------------------ tagger

    private val taggerLock = Any()
    private var tagger: AudioTagger? = null
    private var closeLater: ScheduledFuture<*>? = null
    private val closer by lazy { Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "prism-tagger-close").apply { isDaemon = true } } }

    private fun runTagger(windows: List<FloatArray>): TaggerOutput? = synchronized(taggerLock) {
        closeLater?.cancel(false)
        val t = tagger ?: runCatching { makeTagger() }.onFailure { Log.w(TAG, "the tagger didn't start: $it") }.getOrNull()?.also { tagger = it }
        val started = System.nanoTime()
        val out = t?.let { runCatching { it.run(windows) { com.prism.music.playback.MixTempo.ease() } }.onFailure { e ->
            if (e is com.prism.music.playback.ScansAbandoned) throw e
            Log.w(TAG, "the tagger failed: $e")
        }.getOrNull() }
        if (out != null) Log.i(TAG, "tagged ${out.frames} frames in ${(System.nanoTime() - started) / 1_000_000} ms")
        closeLater = closer.schedule({ closeTagger() }, IDLE_CLOSE_S, TimeUnit.SECONDS)
        out
    }

    /** Lets go of the tagger's network (its model switched off, deleted, or idle). */
    fun closeTagger() = synchronized(taggerLock) {
        runCatching { tagger?.close() }
        tagger = null
    }

    // ------------------------------------------------------------------ reading

    fun entry(id: String): SoundEntry? {
        if (!loaded) ensureLoading()
        return entries[id]
    }

    /** Every analysed song (the index), for similarity across the library. */
    fun all(): Collection<SoundEntry> {
        if (!loaded) ensureLoading()
        return entries.values
    }

    fun classScores(id: String): FloatArray? = scores[id]

    /** Whether [id] has an analysis at all (any version). */
    fun has(id: String): Boolean = entries.containsKey(id)

    /** When [id] was analysed and with what (for the explanation's last line). */
    suspend fun provenance(id: String): SongAnalysisEntity? = dao.get(id)

    /**
     * [id]'s sleep score with the listener's weights and feedback (null: not analysed). From memory: the sleep timer's
     * gate asks this for every song it looks at.
     */
    fun sleepScore(id: String): Int? {
        scoreCache[id]?.let { return it }
        val r = sleepResult(id) ?: return null
        scoreCache[id] = r.score
        return r.score
    }

    fun sleepResult(id: String): SleepResult? {
        val e = entry(id) ?: return null
        val w = SleepWeights(vocalsCount = prefs().vocalsCount)
        if (w != weights) { weights = w; scoreCache.clear() }
        return SleepScore.of(e.features, scores[id], w, personal, votes[id] ?: 0)
    }

    // ------------------------------------------------------------------ the listener's say

    fun vote(id: String): Int = votes[id] ?: 0

    /** "Good for sleep" (+1), "Not for sleep" (-1), or no vote (0). */
    suspend fun setVote(id: String, vote: Int) {
        if (vote == 0) { votes.remove(id); dao.clearVote(id) } else { votes[id] = vote.coerceIn(-1, 1); dao.putVote(SleepVoteEntity(id, vote.coerceIn(-1, 1), System.currentTimeMillis())) }
        refitPersonal()
        scoreCache.clear()
        bump()
    }

    suspend fun setTagStatus(id: String, tag: String, status: TagStatus) {
        dao.setStatus(id, tag, status.ordinal, System.currentTimeMillis())
        bump()
    }

    /** Songs the listener queued themselves (Play next, Add to queue): a sign of liking autoplay reads. */
    fun noteQueued(ids: Collection<String>) {
        if (ids.isEmpty()) return
        scope.launch(Dispatchers.IO) { runCatching { ids.distinct().forEach { dao.bump(it, queued = 1) } } }
    }

    /**
     * How a song fared while the sleep timer ran: [skipped] (the listener moved on) says it isn't for sleep, played
     * through says a little that it is. Both teach [SleepPersonal].
     */
    fun noteSleepListen(id: String, skipped: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (skipped) dao.bump(id, sleepSkips = 1) else dao.bump(id, sleepFinishes = 1)
                refitPersonal()
                scoreCache.clear()
                bump()
            }
        }
    }

    /** Fits [SleepPersonal] to every vote and sleep-timer listen of an analysed song. */
    private suspend fun refitPersonal() {
        val w = SleepWeights(vocalsCount = prefs().vocalsCount)
        val examples = ArrayList<SleepPersonal.Example>()
        fun partsOf(id: String) = entries[id]?.let { SleepScore.of(it.features, scores[id], w).parts }
        for ((id, v) in votes) partsOf(id)?.let { examples += SleepPersonal.Example(it, v > 0, 1f) }
        for (s in runCatching { dao.signals() }.getOrDefault(emptyList())) {
            if (votes.containsKey(s.songId)) continue
            val parts = partsOf(s.songId) ?: continue
            if (s.sleepSkips > 0) examples += SleepPersonal.Example(parts, false, 0.5f * minOf(3, s.sleepSkips))
            if (s.sleepFinishes > 0) examples += SleepPersonal.Example(parts, true, 0.3f * minOf(3, s.sleepFinishes))
        }
        personal = SleepPersonal.fit(examples)
    }

    /** Queue additions per song (autoplay's taste reads them). */
    suspend fun queuedCounts(): Map<String, Int> = runCatching { dao.signals() }.getOrDefault(emptyList()).associate { it.songId to it.queued }

    companion object {
        private const val TAG = "PrismAnalysis"
        const val IDLE_CLOSE_S = 90L

        /** Probabilities as one byte each (0 … 255). */
        fun packScores(s: FloatArray) = ByteArray(s.size) { (s[it].coerceIn(0f, 1f) * 255).roundToInt().toByte() }
        fun unpackScores(b: ByteArray) = FloatArray(b.size) { (b[it].toInt() and 0xff) / 255f }

        /**
         * An embedding, one signed byte each: scaled by its largest value (a 1024-value vector's L2-normalized values are
         * too small for a byte's 127 steps), and L2-normalized again when read, so every song weighs the same.
         */
        fun packEmbedding(e: FloatArray): ByteArray {
            val top = e.maxOfOrNull { kotlin.math.abs(it) }?.takeIf { it > 0f } ?: 1f
            return ByteArray(e.size) { (e[it] / top * 127).roundToInt().coerceIn(-127, 127).toByte() }
        }
        fun unpackEmbedding(b: ByteArray): FloatArray {
            var n = 0.0
            for (v in b) n += v * v
            val norm = sqrt(n).takeIf { it > 0 }?.toFloat() ?: 1f
            return FloatArray(b.size) { b[it] / norm }
        }

        /** The YAMNet tagger on the phone's ONNX Runtime (the engine Sing downloads). */
        fun yamnet(models: ModelStore): AudioTagger? {
            val runtime = ModelStore.Model.singRuntime() ?: return null
            if (!models.ready(runtime) || !models.ready(ModelStore.Model.YAMNET)) return null
            val env = com.prism.music.playback.sing.OrtLoader.environment(models.dir(runtime))
            val session = env.createSession(File(models.dir(ModelStore.Model.YAMNET), ModelStore.Model.YAMNET.file).path, YamnetTagger.options())
            return YamnetTagger(env, session)
        }
    }
}
