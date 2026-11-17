package com.prism.music.ui.screens

/** From how many entries a Library tab gets its search field (#357): a short list is read at a glance. */
internal const val LIBRARY_SEARCH_FROM = 8

/**
 * Whether what's typed is in these names, for filtering a Library tab as you type (#357): every word, anywhere, in any
 * order, without minding capitals or accents ("beyonce" finds "Beyoncé", "ac dc" finds "AC/DC"). A search of only
 * punctuation ("!!!" is a band) goes by the characters themselves.
 */
internal fun libraryMatches(query: String, vararg names: String): Boolean =
    libraryMatches(query, emptyMap(), names)

/**
 * [libraryMatches], with the names' Latin spellings (#363) read too: "tori no uta" (or "torinouta") finds "鳥の歌" once
 * [spellings] has it. Passed by the Library tabs, whose titles are spelled in the background as a search wants them.
 */
internal fun libraryMatches(query: String, spellings: Map<String, String>, names: Array<out String>): Boolean {
    val words = libraryWords(query)
    if (words.isEmpty()) {
        val plain = query.trim()
        return names.any { it.contains(plain, ignoreCase = true) || spellings[it]?.contains(plain, ignoreCase = true) == true }
    }
    // The names as written first: a Latin search of Latin names costs nothing extra (the Roman spellings are only read
    // when they're needed, which is where the work is).
    if (words.all { it in names.joinToString(" ") { name -> phoneNorm(name) } }) return true
    return words.all { it in libraryHaystack(names.asIterable(), spellings) }
}

/** What's typed, as the words [libraryMatches] looks for ("Beyonce", "ac dc"). Empty for punctuation only. */
internal fun libraryWords(query: String): List<String> = phoneNorm(query).split(' ').filter { it.isNotBlank() }

/**
 * The names as [libraryMatches] reads them, made once per entry where a list is filtered as the listener types:
 * normalizing every name of every song on each keystroke was the slow part of a Library search. A name in another
 * script also gets the phone's Latin tags (the same [com.prism.music.data.local.SearchTags] that Search → On this
 * phone and Local files read, so it works at once) and any [spellings] the background pass has worked out; both with
 * their spaces and without ("tori no uta", "torinouta").
 */
internal fun libraryHaystack(names: Iterable<String>, spellings: Map<String, String> = emptyMap()): String =
    names.joinToString(" ") { name ->
        buildList {
            add(phoneNorm(name))
            spellings[name]?.let { spelled ->
                add(phoneNorm(spelled))
                add(phoneNorm(spelled).replace(" ", ""))
            }
            com.prism.music.data.local.SearchTags.forText(name).forEach { add(phoneNorm(it)) }
        }.filter { it.isNotBlank() }.distinct().joinToString(" ")
    }

/** The names of an album's subtitle, without the kind it starts with ("Album • An artist • 2024": searching "album" shouldn't find them all). */
internal fun albumNames(subtitle: String, single: String): List<String> =
    subtitle.split(" • ").filter { it != "Album" && it != "Single" && it != "EP" && it != single }

/** What the slider's bubble says for a name: its first letter (a whole character, whatever the script). */
internal fun scrollLetter(name: String): String? {
    val t = name.trim()
    if (t.isEmpty()) return null
    return String(Character.toChars(t.codePointAt(0))).uppercase()
}

/**
 * What the slider's bubble says for each row, by the row's key: the first letter of the [name] the list goes by, or how far
 * down the list it is when it has none (not going by a name, or that row has no album, say).
 */
internal fun <T> scrollMarks(list: List<T>, key: (T) -> String, name: (T) -> String?): Map<String, String> =
    list.withIndex().associate { (i, t) -> key(t) to (name(t)?.let(::scrollLetter) ?: "${i + 1}") }
