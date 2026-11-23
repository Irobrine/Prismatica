package com.prism.music.data.dedup

/** The five places a duplicate can be hidden, each deduplicated independently of the others. */
enum class DedupScope(val setting: String) {
    SONGS("songs"),
    ALBUMS("albums"),
    PLAYLISTS("playlists"),
    LOCAL_FILES("local_files"),
    DOWNLOADS("downloads"),
}

/**
 * Resolution of the global default and a page's override. An absent override means "Inherit global setting"; changing
 * the global default leaves every stored override untouched.
 */
object DedupSettingsLogic {
    fun enabled(scope: DedupScope, global: Boolean, overrides: Map<String, Boolean>): Boolean =
        overrides[scope.setting] ?: global

    fun override(scope: DedupScope, overrides: Map<String, Boolean>): Boolean? = overrides[scope.setting]

    fun withOverride(scope: DedupScope, overrides: Map<String, Boolean>, value: Boolean?): Map<String, Boolean> =
        if (value == null) overrides - scope.setting else overrides + (scope.setting to value)

    /** The stored "scope:true,scope2:false" string, as a map. */
    fun parse(raw: String): Map<String, Boolean> = raw.split(',').mapNotNull { part ->
        val bits = part.split(':', limit = 2)
        if (bits.size == 2) bits[0] to (bits[1] == "true") else null
    }.toMap()
}

/** One row of a deduplicated list. A [Group] is expandable; its alternatives are kept, never deleted. */
sealed interface DisplayRow {
    data class Single(val id: String) : DisplayRow
    data class Group(val id: String, val representative: String, val alternatives: List<String>) : DisplayRow {
        val count: Int get() = alternatives.size + 1
    }
}

/**
 * Turns a plain list of ids into what a collection shows and what its play queue carries.
 *
 * When disabled it is the identity: existing lists, queues and behavior are unchanged. When enabled each duplicate
 * group is one row; only its representative goes into a collection queue, so a play of the list never plays the same
 * recording twice. The underlying library and playlist contents are never touched — this only filters a view of them.
 */
class DedupViews(
    private val groups: List<Grouping.Group>,
    private val candidates: List<Candidate>,
    private val enabled: Boolean,
) {
    /**
     * Which group each version belongs to, and each group's candidates, worked out once from [groups] and [candidates].
     *
     * [rows], [hidden] and [queue] used to search the whole group list for every song and the whole song list for every
     * group. A library with thousands of groups (2,938 here against 889 songs) spent 150-300 ms of the main thread on
     * every Library entry that way; both are now lookups.
     */
    private val groupOfId: Map<String, Grouping.Group> by lazy {
        val map = HashMap<String, Grouping.Group>(groups.size * 2)
        groups.forEach { g -> g.members.forEach { id -> map.putIfAbsent(id, g) } }
        map
    }

    /** Every group's candidates, with an empty list for a group none of them is in (so a group is always known here). */
    private val candidatesOfGroup: Map<String, List<Candidate>> by lazy {
        val map = HashMap<String, MutableList<Candidate>>(groups.size * 2)
        groups.forEach { map[it.id] = ArrayList(2) }
        candidates.forEach { c -> groupOfId[c.id]?.let { g -> map[g.id]?.add(c) } }
        map
    }

    /** Representative of [group] among the versions actually in this collection ([present]) and reachable ([available]). */
    fun representative(group: Grouping.Group, present: Set<String>, available: Set<String>? = null): String? {
        // A group this view was not built from (a caller's own) still reads the whole candidate list, as it always did.
        val from = candidatesOfGroup[group.id] ?: candidates.filter { it.id in group.members }
        val pool = from.filter { it.id in present }
        if (pool.isEmpty()) return null
        val reachable = available?.let { it intersect present } ?: present
        return Representative.select(pool, reachable)
    }

    private fun groupOf(id: String): Grouping.Group? = groupOfId[id]

    /** Rows in the original list order; each group appears once, at its first member. */
    fun rows(ids: List<String>, available: Set<String>? = null): List<DisplayRow> {
        if (!enabled) return ids.map(DisplayRow::Single)
        val present = ids.toHashSet()
        val seen = HashSet<String>()
        return ids.mapNotNull { id ->
            val group = groupOf(id)
            if (group == null) DisplayRow.Single(id)
            else if (!seen.add(group.id)) null
            else {
                val rep = representative(group, present, available) ?: id
                val alternatives = group.members.filter { it != rep && it in present }
                DisplayRow.Group(group.id, rep, alternatives)
            }
        }
    }

    /**
     * Versions that a collapsed group keeps in the list but out of the play queue. Only groups with a member in [ids]
     * can hide anything, so only those are looked at (it used to run [representative] for every group in the library).
     */
    fun hidden(ids: List<String>, available: Set<String>? = null): Set<String> {
        if (!enabled) return emptySet()
        val present = ids.toHashSet()
        val hidden = HashSet<String>()
        val done = HashSet<String>()
        for (id in ids) {
            val group = groupOf(id) ?: continue
            if (!done.add(group.id)) continue
            val rep = representative(group, present, available) ?: continue
            group.members.forEach { if (it != rep && it in present) hidden += it }
        }
        return hidden
    }

    /** The collection/playlist queue: one representative per group, every ungrouped song as it was. */
    fun queue(ids: List<String>, available: Set<String>? = null): List<String> {
        if (!enabled) return ids
        val hidden = hidden(ids, available)
        return ids.filterNot { it in hidden }
    }
}
