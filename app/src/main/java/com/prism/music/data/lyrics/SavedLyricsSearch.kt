package com.prism.music.data.lyrics

import com.prism.music.data.LibrarySearch
import com.prism.music.data.db.LyricsSearchDao
import com.prism.music.data.db.LyricsText
import com.prism.music.data.db.RomanizedDao
import com.prism.music.data.db.RomanizedEntity
import com.prism.music.data.lyrics.roman.Script
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Lyrics mode: the songs whose lyrics saved on this phone have what was typed, offline and at once. Searched as the
 * library is ([LibrarySearch.norm]: no capitals, accents or punctuation), each word anywhere, and in Latin letters for
 * lyrics in other scripts: "saranghae" finds 사랑해, "kimi no na wa" finds 君の名は once the Japanese dictionary is
 * here. Those spellings are worked out in the background the first time lyrics mode wants them, and kept in the
 * `romanized` table so each song's lyrics are spelled once; [version] moves as they arrive so a search can look again.
 */
class SavedLyricsSearch(
    private val scope: CoroutineScope,
    private val rows: suspend () -> List<LyricsText>,
    private val stamp: suspend () -> String,
    private val lines: (String) -> List<String>,
    /** The spelling engines' key: a new dictionary spells the lyrics again. */
    private val readingKey: () -> String = { "" },
    private val stored: suspend (String) -> String? = { null },
    private val store: suspend (String, String) -> Unit = { _, _ -> },
    /** A song's lines in Latin letters, null for a line that needs none or can't be spelled here. */
    private val spell: (suspend (List<String>) -> List<String?>)? = null,
) {
    constructor(scope: CoroutineScope, dao: LyricsSearchDao, romanized: RomanizedDao, lines: (String) -> List<String>, romanizer: Romanizer) : this(
        scope, dao::allSaved, dao::savedStamp, lines, { romanizer.readingKey },
        { k -> romanized.get(k)?.content }, { k, v -> romanized.put(RomanizedEntity(k, v, System.currentTimeMillis())) },
        romanizer::spellLyricsForSearch,
    )

    /** What lyrics mode found in one song: the line to show under it, and how well ([rank] 0 is best). */
    data class Match(val songId: String, val line: String, val rank: Int)

    /** One saved lyrics text, read for searching. */
    class Doc(val songId: String, val content: String, val lines: List<String>) {
        internal val norm: Array<String> = Array(lines.size) { LibrarySearch.norm(lines[it]) }
        internal val joined: Array<String> = Array(lines.size) { norm[it].replace(" ", "") }
        internal val foreign: Boolean = lines.any { Script.foreignIn(it).isNotEmpty() }
        /** The lines in Latin letters (normalized), once spelled: null until then, or for lyrics that need none. */
        @Volatile internal var roman: Array<String>? = null
        @Volatile internal var romanJoined: Array<String>? = null
        internal val whole: String get() = norm.joinToString(" ") + (roman?.joinToString(" ", prefix = " ") ?: "")

        internal fun spelled(spellings: List<String?>) {
            if (spellings.size != lines.size) return
            val r = Array(lines.size) { spellings[it]?.let(LibrarySearch::norm).orEmpty() }
            romanJoined = Array(r.size) { r[it].replace(" ", "") }
            roman = r
        }
    }

    private val _version = MutableStateFlow(0)
    /** Moves when more lyrics have been spelled in Latin letters: a search made before may find more now. */
    val version: StateFlow<Int> = _version

    private val lock = Mutex()
    @Volatile private var docs: List<Doc> = emptyList()
    @Volatile private var lastStamp: String? = null
    private val queue = ConcurrentLinkedQueue<Doc>()
    private val asked = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    @Volatile private var job: Job? = null

    /** The saved lyrics, read again only when the table changed; texts already read are kept as they were. */
    private suspend fun load(): List<Doc> = lock.withLock {
        val now = runCatching { stamp() }.getOrNull()
        if (now != null && now == lastStamp) return@withLock docs
        val known = docs.associateBy { key(it.songId, it.content) }
        val fresh = runCatching { rows() }.getOrDefault(emptyList()).mapNotNull { r ->
            known[key(r.songId, r.content)] ?: runCatching { lines(r.content) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { Doc(r.songId, r.content, it) }
        }
        docs = fresh
        lastStamp = now
        fresh
    }

    /**
     * Songs whose saved lyrics have [query] (among [among] when given), best first: the words together in a line, then
     * all of them in a line (or the next), then all of them somewhere in the song. Each song once.
     */
    suspend fun find(query: String, among: Collection<String>? = null, limit: Int = 300): List<Match> = withContext(Dispatchers.Default) {
        val q = Query.of(query) ?: return@withContext emptyList()
        if (among != null && among.isEmpty()) return@withContext emptyList()
        val all = load()
        val ids = among?.let { it as? Set<String> ?: it.toHashSet() }
        val pool = if (ids == null) all else all.filter { it.songId in ids }
        spellInBackground(pool)
        matchAll(pool, q, limit)
    }

    private fun spellInBackground(pool: List<Doc>) {
        val engine = spell ?: return
        val reading = readingKey()
        pool.forEach { d -> if (d.foreign && d.roman == null && asked.add(spellKey(reading, d))) queue += d }
        if (queue.isEmpty() || job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            var n = 0
            var found = false
            while (true) {
                val d = queue.poll() ?: break
                val k = spellKey(readingKey(), d)
                val saved = runCatching { stored(k) }.getOrNull()
                val spellings = if (saved != null) saved.split('\n').map { it.ifEmpty { null } } else {
                    (runCatching { engine(d.lines) }.getOrNull() ?: List(d.lines.size) { null }).also { s ->
                        runCatching { store(k, s.joinToString("\n") { it.orEmpty().replace('\n', ' ') }) }
                    }
                }
                d.spelled(spellings)
                if (spellings.any { it != null }) found = true
                if (++n % BATCH == 0) {
                    if (found) { _version.value++; found = false }
                    delay(PAUSE_MS)
                }
            }
            if (found) _version.value++
        }.also { j -> j.invokeOnCompletion { cause -> if (cause == null && queue.isNotEmpty()) spellInBackground(emptyList()) } }
    }

    internal class Query(val text: String, val words: List<String>, val joined: String) {
        companion object {
            fun of(query: String): Query? {
                val q = LibrarySearch.norm(query)
                val words = q.split(' ').filter { it.isNotBlank() }
                val joined = q.replace(" ", "")
                // One letter would be in nearly every song.
                if (joined.codePointCount(0, joined.length) < 2) return null
                return Query(q, words, joined)
            }
        }
    }

    companion object {
        const val VERSION = 1
        private const val BATCH = 20
        private const val PAUSE_MS = 30L

        private fun key(songId: String, content: String) = "$songId\u0000${content.length}:${content.hashCode()}"
        private fun spellKey(reading: String, d: Doc) = "lyricsearch:$VERSION:$reading:${d.songId}:${d.content.length}:${d.content.hashCode()}"

        /** [find]'s matching, for the tests: [docs] searched for [query]. */
        fun matchAll(docs: List<Doc>, query: String, limit: Int = 300): List<Match> = Query.of(query)?.let { matchAll(docs, it, limit) }.orEmpty()

        private fun matchAll(docs: List<Doc>, q: Query, limit: Int): List<Match> {
            val best = LinkedHashMap<String, Match>()
            for (d in docs) {
                val m = match(d, q) ?: continue
                val had = best[d.songId]
                if (had == null || m.rank < had.rank) best[d.songId] = m
            }
            return best.values.sortedBy { it.rank }.take(limit)
        }

        internal fun match(d: Doc, q: Query): Match? {
            val roman = d.roman
            val romanJoined = d.romanJoined
            fun line(i: Int) = d.lines[i].trim()
            // The words together, as typed: in the line as written, or in its Latin spelling.
            for (i in d.lines.indices) {
                if (q.text in d.norm[i] || q.joined in d.joined[i] ||
                    (roman != null && (q.text in roman[i] || q.joined in romanJoined!![i]))
                ) return Match(d.songId, line(i), 0)
            }
            if (q.words.size > 1) {
                // Every word in one line, or running on into the next.
                fun hay(i: Int) = if (roman == null) d.norm[i] else "${d.norm[i]} ${roman[i]}"
                for (i in d.lines.indices) {
                    val one = hay(i)
                    if (q.words.all { it in one }) return Match(d.songId, line(i), 1)
                    if (i + 1 < d.lines.size) {
                        val two = one + " " + hay(i + 1)
                        if (q.words.all { it in two }) return Match(d.songId, "${line(i)} / ${line(i + 1)}", 1)
                    }
                }
                // Every word somewhere in the song: the line with the most of them shows.
                val whole = d.whole
                if (q.words.all { it in whole }) {
                    val i = d.lines.indices.maxByOrNull { i -> hay(i).let { h -> q.words.count { it in h } } } ?: return null
                    return Match(d.songId, line(i), 2)
                }
            }
            return null
        }
    }
}
