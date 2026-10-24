package com.newhost.qwertylauncher.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * News that modules share for others to react to, without knowing each
 * other: the weather module says what the sky is doing, the timer command
 * when a countdown ends, games that a best score was beaten. A character
 * (Sir Alex) can then shelter from the rain or cheer. Offered by the shell,
 * always there. Kinds are dotted names, owned by the module that posts
 * them; see [Happening] for the ones in use.
 */
interface Happenings {
    /** The latest of each kind, by [Happening.kind]: what's so now. */
    val latest: StateFlow<Map<String, Happening>>

    /** Every one as it's posted: what just happened. */
    val stream: SharedFlow<Happening>

    fun post(kind: String, value: String = "")
}

/**
 * Something that happened, or is so: a [kind], a [value] (text, or a
 * number as text) and when it was posted. In use:
 *
 * - `weather.sky`: clear, cloudy, fog, rain, snow or storm; `weather.celsius`: the temperature now
 * - `timer.ends`: when the countdown just started ends, in epoch milliseconds
 * - `games.played`: the game's id, when one starts; `games.best`: the game's id, when its best was beaten
 */
data class Happening(val kind: String, val value: String, val at: Long)

/** [kind]'s latest value, if there's been one. */
fun Happenings.now(kind: String): String? = latest.value[kind]?.value
