package fool.akza.foto.data

import kotlin.math.abs

/** How well one query token matched one indexed word, best first. */
enum class TokenMatch { EXACT, PREFIX, FUZZY }

/** Everything that isn't a letter or digit separates tokens. */
private val SearchSeparators = Regex("[^\\p{L}\\p{N}]+")

/**
 * Splits text into the tokens search compares. Lowercase, punctuation dropped:
 * `37° 46' 29.6" N` → `37`, `46`, `29`, `6`, `n`; `f/1.8` → `f`, `1`, `8`.
 */
internal fun tokenizeForSearch(text: String): List<String> =
    text.lowercase().split(SearchSeparators).filter { it.isNotEmpty() }

/**
 * Edit budget for a query token: short tokens must be exact or a prefix, medium
 * ones forgive one typo, long ones two. Without a budget every three-letter
 * query would match half the library.
 */
internal fun maxEditsFor(token: String): Int = when {
    token.length <= 3 -> 0
    token.length <= 6 -> 1
    else -> 2
}

/**
 * One query token against one indexed word.
 *
 * - exact, or the word starts with the token (`lighth` → `lighthouse`)
 * - otherwise a bounded Damerau-Levenshtein distance, so `canonn` → `canon` and
 *   `lighthosue` → `lighthouse` (a transposition costs one edit) still match.
 *
 * Deliberately no phonetic folding or embeddings: the goal is forgiving
 * matching over metadata a person can see, not guessing at synonyms.
 */
internal fun matchToken(query: String, word: String): TokenMatch? {
    if (query.isEmpty() || word.isEmpty()) return null
    if (word == query) return TokenMatch.EXACT
    if (word.startsWith(query)) return TokenMatch.PREFIX
    val budget = maxEditsFor(query)
    if (budget == 0) return null
    if (abs(word.length - query.length) > budget) return null
    return if (withinEdits(query, word, budget)) TokenMatch.FUZZY else null
}

/** True when every query token matches at least one word. */
internal fun matchesAllTokens(queryTokens: List<String>, words: Collection<String>): Boolean =
    queryTokens.isNotEmpty() && queryTokens.all { token -> words.any { matchToken(token, it) != null } }

/**
 * Restricted Damerau-Levenshtein distance ≤ [maxEdits]. Returns as soon as the
 * current row proves the budget is blown, so a wrong word costs a row or two.
 */
internal fun withinEdits(a: String, b: String, maxEdits: Int): Boolean {
    if (abs(a.length - b.length) > maxEdits) return false
    var previousPrevious = IntArray(b.length + 1)
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        var rowMin = current[0]
        for (j in 1..b.length) {
            val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            val deletion = previous[j] + 1
            val insertion = current[j - 1] + 1
            var best = minOf(substitution, deletion, insertion)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                best = minOf(best, previousPrevious[j - 2] + 1)
            }
            current[j] = best
            if (best < rowMin) rowMin = best
        }
        if (rowMin > maxEdits) return false
        val recycled = previousPrevious
        previousPrevious = previous
        previous = current
        current = recycled
    }
    return previous[b.length] <= maxEdits
}
