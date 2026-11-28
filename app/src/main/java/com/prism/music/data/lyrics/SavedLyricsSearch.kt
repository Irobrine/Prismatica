package com.prism.music.data.lyrics

import com.prism.music.data.LibrarySearch
import com.prism.music.data.db.LyricsRow
import com.prism.music.data.db.LyricsSearchDao
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Lyrics mode: the songs whose lyrics saved on this phone have what was typed, offline. Searched as the library is
 * ([LibrarySearch.norm]: no capitals, accents or punctuation), each word anywhere, and in Latin letters for lyrics in
 * other scripts: "saranghae" finds 사랑해, "kimi no na wa" finds 君の名は once the Japanese dictionary is here.
 *
 * The saved lyrics are read once, a page at a time, and kept only as their plain lines (a word-timed file is many
 * times its words: holding the files themselves ran a big library out of memory); after that only what was saved since
 * is read. One text per song, the latest saved. Latin spellings are worked out in the background the first time lyrics
 * mode wants them and kept in the `romanized` table, so each song's lyrics are spelled once; [version] moves as they
 * arrive, so a search can look again.
 */
class SavedLyricsSearch(
    private val scope: CoroutineScope,
    /** Saved rows after a rowid, at most so many, by rowid. */
    private val page: suspend (after: Long, limit: Int) -> List<LyricsRow>,
    private val lines: (String) -> List<String>,
    /** The spelling engines' key: a new dictionary spells the lyrics again. */
    private val readingKey: () -> String = { "" },
    private val stored: suspend (String) -> String? = { null },
    private val store: suspend (String, String) -> Unit = { _, _ -> },
    /** A song's lines in Latin letters, null for a line that needs none or can't be spelled here. */
    private val spell: (suspend (songId: String, lines: List<String>) -> List<String?>)? = null,
) {
    constructor(scope: CoroutineScope, dao: LyricsSearchDao, romanized: RomanizedDao, lines: (String) -> List<String>, romanizer: Romanizer) : this(
        scope, dao::savedAfter, lines, { romanizer.readingKey },
        { k -> romanized.get(k)?.content }, { k, v -> romanized.put(RomanizedEntity(k, v, System.currentTimeMillis())) },
        romanizer::spellLyricsForSearch,
    )

    /** What lyrics mode found in one song: the line to show under it, and how well ([rank] 0 is best). */
    data class Match(val songId: String, val line: String, val rank: Int)

    /**
     * One song's saved lyrics, read for searching: its lines as written, normalized, and normalized without spaces,
     * each one string with a line a row ('\n' between them), so a song is three strings, not three lists of lines.
     */
    class Doc(val songId: String, val source: String, lines: List<String>) {
        internal val text: String = lines.joinToString("\n") { it.replace('\n', ' ').trim() }
        internal val norm: String = lines.joinToString("\n") { LibrarySearch.norm(it) }
        internal val joined: String = norm.replace(" ", "")
        internal val foreign: Boolean = Script.foreignIn(text).isNotEmpty()
        internal val lineCount: Int = lines.size
        /** The lines in Latin letters (normalized, a line a row), once spelled: null until then, or when none needs it. */
        @Volatile internal var roman: String? = null
        @Volatile internal var romanJoined: String? = null

        internal fun lines(): List<String> = text.split('\n')

        internal fun spelled(spellings: List<String?>) {
            if (spellings.size != lineCount || spellings.all { it == null }) return
            val r = spellings.joinToString("\n") { s -> s?.let { romanKey(LibrarySearch.norm(it)) }.orEmpty() }
            romanJoined = r.replace(" ", "")
            roman = r
        }
    }

    private val _version = MutableStateFlow(0)
    /** Moves when more lyrics have been spelled in Latin letters: a search made before may find more now. */
    val version: StateFlow<Int> = _version

    private val lock = Mutex()
    /** By song: the latest lyrics saved for it. */
    private val docs = LinkedHashMap<String, Doc>()
    @Volatile private var snapshot: List<Doc> = emptyList()
    /** The last row read, and when it was saved (the newest row, replaced, keeps its rowid: it's read again). */
    private var lastRow = 0L
    private var lastRowAt = -1L
    private val queue = ConcurrentLinkedQueue<Doc>()
    private val asked: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var job: Job? = null

    /** Reads what was saved since the last time (everything, the first time): then searching starts at once. */
    fun warm() {
        scope.launch(Dispatchers.IO) { runCatching { load() } }
    }

    private suspend fun load(): List<Doc> = withContext(Dispatchers.IO) {
        lock.withLock {
            val started = System.nanoTime()
            var read = 0
            var changed = false
            // From the last row itself: replaced, the newest row keeps its rowid.
            var after = (lastRow - 1).coerceAtLeast(0)
            while (true) {
                val rows = runCatching { page(after, PAGE) }.getOrDefault(emptyList())
                if (rows.isEmpty()) break
                read += rows.size
                for (r in rows) {
                    if (r.row == lastRow && r.fetchedAt == lastRowAt) continue
                    changed = true
                    if (r.content.isEmpty()) {
                        // A miss saved over a source's lyrics takes them away.
                        if (docs[r.songId]?.source == r.source) docs.remove(r.songId)
                    } else {
                        val text = runCatching { lines(r.content) }.getOrNull()?.filter { it.isNotBlank() }
                        if (!text.isNullOrEmpty()) { docs.remove(r.songId); docs[r.songId] = Doc(r.songId, r.source, text) }
                    }
                }
                val last = rows.last()
                if (last.row >= lastRow) { lastRow = last.row; lastRowAt = last.fetchedAt }
                after = last.row
                if (rows.size < PAGE) break
            }
            if (changed || snapshot.size != docs.size) snapshot = docs.values.toList()
            if (read > 1) runCatching {
                android.util.Log.i("PrismLyricsSearch", "read $read saved lyrics rows, ${docs.size} songs, in ${(System.nanoTime() - started) / 1_000_000} ms")
            }
            snapshot
        }
    }

    /**
     * Songs whose saved lyrics have [query] (among [among] when given), best first: the words together in a line, then
     * all of them in a line (or the next), then all of them somewhere in the song. Each song once.
     */
    suspend fun find(query: String, among: Collection<String>? = null, limit: Int = 300): List<Match> {
        val q = Query.of(query) ?: return emptyList()
        if (among != null && among.isEmpty()) return emptyList()
        val all = load()
        return withContext(Dispatchers.Default) {
            val ids = among?.let { it as? Set<String> ?: it.toHashSet() }
            val pool = if (ids == null) all else all.filter { it.songId in ids }
            spellInBackground(pool)
            matchAll(pool, q, limit)
        }
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
                val lines = d.lines()
                val saved = runCatching { stored(k) }.getOrNull()
                val spellings = if (saved != null) saved.split('\n').map { it.ifEmpty { null } } else {
                    (runCatching { engine(d.songId, lines) }.getOrNull() ?: List(lines.size) { null }).also { s ->
                        // Kept only when every line could be spelled: kanji without the dictionary (or before the player
                        // has shown the song romanized) are tried again another time.
                        val whole = lines.indices.all { i -> Script.foreignIn(lines[i]).isEmpty() || s.getOrNull(i)?.let { Script.foreignIn(it).isEmpty() } == true }
                        if (whole) runCatching { store(k, s.joinToString("\n") { it.orEmpty().replace('\n', ' ') }) }
                    }
                }
                d.spelled(spellings)
                if (d.roman != null) found = true
                if (++n % BATCH == 0) {
                    if (found) { _version.value++; found = false }
                    delay(PAUSE_MS)
                }
            }
            if (found) _version.value++
        }.also { j -> j.invokeOnCompletion { cause -> if (cause == null && queue.isNotEmpty()) spellInBackground(emptyList()) } }
    }

    internal class Query(val text: String, val words: List<String>, val joined: String) {
        /** The same, as [romanKey] reads Latin spellings: compared with the lyrics' spellings only. */
        val romanText = romanKey(text)
        val romanWords = words.map(::romanKey)
        val romanJoined = romanText.replace(" ", "")

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
        const val VERSION = 3

        private val ROMAN_RULES = listOf(
            // Hepburn and the other ways of writing the same sounds: "shi"/"si", "tsu"/"tu", "ja"/"zya"…
            "shi" to "si", "chi" to "ti", "tsu" to "tu", "fu" to "hu", "ji" to "zi",
            "sh" to "sy", "ch" to "ty", "j" to "zy",
            // The particles as written (は, を, へ) and as said ("wa", "o", "e").
            "ha" to "wa", "wo" to "o", "he" to "e",
            "mb" to "nb", "mp" to "np",
            // Long vowels however they're written: "toukyou", "tookyoo", "tōkyō" (its macrons are gone already), "tokyo".
            "ou" to "o", "oo" to "o", "uu" to "u", "aa" to "a", "ii" to "i", "ee" to "e", "ei" to "e",
        )

        /**
         * A Latin spelling as one key for the ways it can be typed ("toukyou", "tōkyō" and "tokyo"; "shi" and "si"; は as
         * "ha" or "wa"), for what's typed and the lyrics' spellings alike. Only the spellings are compared this way, so
         * a word as written still has to be typed as written.
         */
        fun romanKey(s: String): String {
            var r = s
            for ((from, to) in ROMAN_RULES) if (from in r) r = r.replace(from, to)
            // A vowel lengthened twice ("ooo") or made long by a rule above ("ouu").
            for ((from, to) in ROMAN_RULES.takeLast(7)) if (from in r) r = r.replace(from, to)
            return r
        }
        private const val PAGE = 100
        private const val BATCH = 20
        private const val PAUSE_MS = 30L

        private fun spellKey(reading: String, d: Doc) = "lyricsearch:$VERSION:$reading:${d.songId}:${d.text.length}:${d.text.hashCode()}"

        /** [find]'s matching, for the tests: [docs] searched for [query]. */
        fun matchAll(docs: List<Doc>, query: String, limit: Int = 300): List<Match> = Query.of(query)?.let { matchAll(docs, it, limit) }.orEmpty()

        private fun matchAll(docs: List<Doc>, q: Query, limit: Int): List<Match> {
            val found = ArrayList<Match>()
            for (d in docs) match(d, q)?.let(found::add)
            return found.sortedBy { it.rank }.take(limit)
        }

        /** The line [at] (an offset in a text whose lines are rows) is in. */
        private fun lineAt(s: String, at: Int): Int {
            var n = 0
            for (i in 0 until at) if (s[i] == '\n') n++
            return n
        }

        internal fun match(d: Doc, q: Query): Match? {
            val roman = d.roman
            val romanJoined = d.romanJoined
            // The words together, as typed: in a line as written, or in its Latin spelling. Searched in the whole text at
            // once (the typed words have no line break in them, so a find is always within one line).
            fun phrase(s: String?, part: String): Int = if (s == null) -1 else s.indexOf(part).let { if (it < 0) -1 else lineAt(s, it) }
            val together = phrase(d.norm, q.text).takeIf { it >= 0 }
                ?: phrase(d.joined, q.joined).takeIf { it >= 0 }
                ?: phrase(roman, q.romanText).takeIf { it >= 0 }
                ?: phrase(romanJoined, q.romanJoined).takeIf { it >= 0 }
            if (together != null) return Match(d.songId, d.lines().getOrElse(together) { "" }, 0)
            if (q.words.size < 2) return null
            // A word is in a stretch as written, or (by its key) in its Latin spelling.
            fun has(k: Int, written: String, spelled: String?) = q.words[k] in written || (spelled != null && q.romanWords[k] in spelled)
            // Every word somewhere in the song, or it isn't one: most songs stop here, before their lines are split.
            if (!q.words.indices.all { has(it, d.norm, roman) }) return null
            val norm = d.norm.split('\n')
            val rom = roman?.split('\n')
            val lines = d.lines()
            fun spelled(i: Int) = rom?.getOrElse(i) { "" }
            fun allIn(written: String, spelled: String?) = q.words.indices.all { has(it, written, spelled) }
            // Every word in one line, or running on into the next.
            for (i in norm.indices) {
                if (allIn(norm[i], spelled(i))) return Match(d.songId, lines.getOrElse(i) { "" }, 1)
                if (i + 1 < norm.size) {
                    val two = norm[i] + " " + norm[i + 1]
                    val twoSpelled = rom?.let { spelled(i) + " " + spelled(i + 1) }
                    if (allIn(two, twoSpelled)) return Match(d.songId, "${lines.getOrElse(i) { "" }} / ${lines.getOrElse(i + 1) { "" }}", 1)
                }
            }
            // Spread over the song: the line with the most of them shows.
            val i = norm.indices.maxByOrNull { i -> q.words.indices.count { has(it, norm[i], spelled(i)) } } ?: return null
            return Match(d.songId, lines.getOrElse(i) { "" }, 2)
        }
    }
}
