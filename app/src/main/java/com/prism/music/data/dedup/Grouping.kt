package com.prism.music.data.dedup

/**
 * Where a "these two are the same recording" statement came from. Provenance is kept per pair, not per group, so a
 * rescan can replace only the automatic statements while a confirmation, a manual addition or a separation stays.
 */
enum class RelationSource {
    /** Recomputed from fingerprints by a scan. */
    AUTO,
    /** A borderline automatic match a person accepted. */
    CONFIRMED,
    /** A pair a person added by hand, which overrides every threshold. */
    MANUAL,
    /** A pair a person explicitly said is NOT the same recording, which overrides everything. */
    SEPARATED,
}

/**
 * One pairwise statement. The pair is stored in [a]/[b] order as given, but [pair] normalizes it so (a,b) and (b,a)
 * are the same relationship. [changedA]/[changedB] survive a file change and make the UI warn that a confirmed or
 * manual relationship should be re-listened to.
 */
data class Relation(
    val a: String,
    val b: String,
    val source: RelationSource,
    /** Vote count for AUTO (see [Matcher]); 0 for a manual statement. */
    val score: Double = 0.0,
    val changedA: Boolean = false,
    val changedB: Boolean = false,
    val at: Long = 0L,
) {
    val pair: Pair<String, String> get() = if (a <= b) a to b else b to a

    fun involves(id: String): Boolean = a == id || b == id

    fun other(id: String): String = if (a == id) b else a
}

/**
 * Builds the groups from the current pairwise statements.
 *
 * Automatic statements are deliberately conservative: two components are joined by an automatic edge only when every
 * cross pair also has a directly high-confidence (or manual) statement. That stops a chain of weak matches A~B, B~C
 * from swallowing an unrelated C (or A) — the transitive failure this feature must avoid. A person can always add or
 * merge by hand ([RelationSource.MANUAL]), which overrides the thresholds; an explicit [RelationSource.SEPARATED]
 * overrides even that.
 */
object Grouping {
    data class Group(val id: String, val members: Set<String>)

    data class Result(
        val groups: List<Group>,
        /** Borderline automatic pairs, offered for confirmation rather than grouped. */
        val suggestions: List<Relation>,
    )

    fun build(ids: Collection<String>, relations: List<Relation>): Result {
        val members = ids.toSet()
        val separated = relations.filter { it.source == RelationSource.SEPARATED }.map { it.pair }.toSet()
        val mergeable = HashSet<Pair<String, String>>()
        val edges = ArrayList<Pair<Relation, Int>>() // relation, priority (higher first)
        for (r in relations) {
            if (r.a == r.b || r.a !in members || r.b !in members) continue
            val p = r.pair
            when (r.source) {
                RelationSource.SEPARATED -> Unit
                RelationSource.MANUAL, RelationSource.CONFIRMED -> {
                    mergeable += p
                    edges += r to 1_000_000
                }
                RelationSource.AUTO -> when {
                    r.score >= Matcher.HIGH_MIN_VOTES -> { mergeable += p; edges += r to r.score.toInt() }
                    r.score >= Matcher.BORDERLINE_MIN_VOTES -> Unit // a suggestion, handled below
                    else -> Unit
                }
            }
        }
        val parent = HashMap<String, String>().apply { members.forEach { this[it] = it } }
        fun find(x: String): String {
            var r = x
            while (parent.getValue(r) != r) r = parent.getValue(r)
            var y = x
            while (parent.getValue(y) != y) { val next = parent.getValue(y); parent[y] = r; y = next }
            return r
        }
        fun membersOf(root: String): Set<String> = members.filterTo(HashSet()) { find(it) == root }

        for ((r, _) in edges.sortedByDescending { it.second }) {
            val ra = find(r.a); val rb = find(r.b)
            if (ra == rb) continue
            val ca = membersOf(ra); val cb = membersOf(rb)
            // An explicit separation wins over a manual merge and over an automatic one.
            if (ca.any { x -> cb.any { y -> (x to y).normalPair() in separated } }) continue
            // An automatic edge may only join two components that are already complete pairwise; a manual statement
            // does not need that (a person saw the recordings and decided they belong together).
            if (r.source == RelationSource.AUTO && ca.any { x -> cb.any { y -> (x to y).normalPair() !in mergeable } }) continue
            parent[rb] = ra
        }

        val resultGroups = members.groupBy(::find).values.map { list ->
            val set = list.toSet()
            Group(set.minOrNull()!!, set)
        }.sortedBy { it.id }
        val groupedIds = resultGroups.flatMap { it.members }.toSet()
        val suggestions = relations.filter {
            it.source == RelationSource.AUTO &&
                it.score >= Matcher.BORDERLINE_MIN_VOTES && it.score < Matcher.HIGH_MIN_VOTES &&
                it.pair.first in groupedIds && it.pair.second in groupedIds &&
                find(it.a) != find(it.b) &&
                it.pair !in separated
        }
        return Result(resultGroups, suggestions)
    }

    private fun Pair<String, String>.normalPair(): Pair<String, String> = if (first <= second) this else second to first
}

/**
 * The persistent relationship statements and the edits a person makes to them. Only the AUTO rows are replaced by a
 * scan; every other kind survives it. This is why manual edits do not have to be re-applied after a rescan.
 */
class RelationSet(private val rows: MutableSet<Relation> = LinkedHashSet()) {
    fun all(): List<Relation> = rows.toList()

    fun auto(): List<Relation> = rows.filter { it.source == RelationSource.AUTO }

    /** Replaces only what the fingerprints say, leaving confirmations, manual additions and separations alone. */
    fun replaceAuto(fresh: List<Relation>) {
        rows.removeAll { it.source == RelationSource.AUTO }
        rows.addAll(fresh)
    }

    fun hasPair(a: String, b: String, source: RelationSource? = null): Boolean =
        rows.any { it.pair == (a to b).let { p -> if (p.first <= p.second) p else p.second to p.first } && (source == null || it.source == source) }

    /** Marks a borderline match as confirmed, so it survives later scans as a merge-worthy relationship. */
    fun confirm(a: String, b: String, score: Double, now: Long): Boolean {
        if (a == b) return false
        val p = normalized(a, b)
        rows.removeAll { it.pair == p }
        return rows.add(Relation(p.first, p.second, RelationSource.CONFIRMED, score, at = now))
    }

    fun addManual(a: String, b: String, now: Long): Boolean {
        if (a == b) return false
        val p = normalized(a, b)
        rows.removeAll { it.pair == p && it.source != RelationSource.SEPARATED }
        return rows.add(Relation(p.first, p.second, RelationSource.MANUAL, at = now))
    }

    /**
     * Permanently separates two recordings: no future scan may group them, and a manual merge is not allowed either.
     */
    fun separate(a: String, b: String, now: Long): Boolean {
        if (a == b) return false
        val p = normalized(a, b)
        rows.removeAll { it.pair == p }
        return rows.add(Relation(p.first, p.second, RelationSource.SEPARATED, at = now))
    }

    fun undoSeparation(a: String, b: String) {
        val p = normalized(a, b)
        rows.removeAll { it.pair == p && it.source == RelationSource.SEPARATED }
    }

    /** Drops the manual statement only: an automatic relationship may then put the pair back on the next scan. */
    fun removeManualMembership(a: String, b: String) {
        val p = normalized(a, b)
        rows.removeAll { it.pair == p && (it.source == RelationSource.MANUAL || it.source == RelationSource.CONFIRMED) }
    }

    /** Merges two groups by stating that their representatives are manually the same recording. */
    fun mergeGroups(first: Collection<String>, second: Collection<String>, now: Long): Boolean {
        val a = first.minOrNull() ?: return false
        val b = second.minOrNull() ?: return false
        return addManual(a, b, now)
    }

    /**
     * Moves [id] from one group to another: its manual ties to the old group are dropped and ties to the new one added.
     */
    fun move(id: String, from: Collection<String>, to: Collection<String>, now: Long) {
        for (other in from) {
            val p = normalized(id, other)
            rows.removeAll { it.pair == p && (it.source == RelationSource.MANUAL || it.source == RelationSource.CONFIRMED) }
        }
        for (other in to) addManual(id, other, now)
    }

    /** Notes that one side of a persisted relationship changed on disk (the UI shows a warning). */
    fun markChanged(id: String, now: Long) {
        val changed = rows.filter { it.involves(id) }.map { r ->
            if (r.a == id) r.copy(changedA = true) else r.copy(changedB = true)
        }
        rows.removeAll { it.involves(id) }
        rows.addAll(changed)
    }

    private fun normalized(a: String, b: String): Pair<String, String> = if (a <= b) a to b else b to a
}
