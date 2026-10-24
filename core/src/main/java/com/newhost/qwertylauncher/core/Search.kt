package com.newhost.qwertylauncher.core

/** What the user typed at the prompt, pre-split for command-style parsing. */
class Query(val raw: String) {
    val text: String = raw.trim()

    /** First word, lowercased: the command name if this is a command. */
    val head: String = text.substringBefore(' ').lowercase()

    /** Everything after the first word. */
    val tail: String = text.substringAfter(' ', "").trim()
}

interface SearchProvider {
    val id: String

    /**
     * Returns results for [query]. Called off the main thread for every
     * keystroke and cancelled as soon as the query changes, so keep it cheap
     * and cooperative (switch to [kotlinx.coroutines.Dispatchers.IO] for I/O).
     */
    suspend fun search(query: Query): List<SearchResult>
}

/**
 * A command typed as `name args`, such as `call mom` or `alarm 7:30`. The shell
 * routes the query here once the first word matches [name] or an alias, and
 * offers `name` as a completion while the user is still typing it.
 */
interface Command {
    val name: String
    val aliases: List<String> get() = emptyList()

    /** One-line synopsis for `help`, e.g. `alarm <hh:mm> [label]`. */
    val usage: String
    val summary: String

    suspend fun suggest(args: String): List<SearchResult>
}

data class SearchResult(
    /** Stable identity across searches; also used to drop duplicates. */
    val key: String,
    /** Short type label shown in the left column: `app`, `cmd`, `ctc`, ... */
    val tag: String,
    val title: CharSequence,
    val detail: CharSequence? = null,
    val score: Int = Score.DEFAULT,
    /** Text the prompt becomes when the user presses Tab on this result. */
    val completion: String? = null,
    val action: ResultAction,
    /** More actions, shown as a menu when the user holds enter on (or long-presses) the result. */
    val menu: List<MenuItem> = emptyList(),
)

fun interface ResultAction {
    /** Runs on the main thread when the user picks the result. */
    fun run(host: LauncherHost)
}

/** Ranking bands; providers add or subtract within a band. Higher sorts first. */
object Score {
    const val COMMAND = 2000
    const val EXACT = 1000
    const val PREFIX = 800
    const val WORD = 600
    const val FUZZY = 300
    const val DEFAULT = 100
    const val FALLBACK = 10
}
