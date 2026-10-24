package com.newhost.qwertylauncher.core

import kotlinx.coroutines.flow.Flow

/**
 * A user-tunable value. Modules declare their settings with
 * [ModuleScope.declareSettings]; the shell stores them and lists them in the
 * launcher settings screen under the module's title.
 *
 * [key] only needs to be unique within its module: the shell namespaces it.
 */
sealed class Setting<T : Any>(
    val key: String,
    val title: String,
    val default: T,
    val summary: String?,
)

class ToggleSetting(
    key: String,
    title: String,
    default: Boolean,
    summary: String? = null,
) : Setting<Boolean>(key, title, default, summary)

class ChoiceSetting(
    key: String,
    title: String,
    val options: List<Choice>,
    default: String,
    summary: String? = null,
) : Setting<String>(key, title, default, summary) {
    data class Choice(val value: String, val label: String = value)
}

/**
 * Any number of [options] at once, kept in their order: edited in the
 * settings as a list to tick. [limit], when it gives a number, is the most
 * that can be ticked (it may change, e.g. with the room on screen).
 */
class MultiChoiceSetting(
    key: String,
    title: String,
    val options: List<ChoiceSetting.Choice>,
    default: List<String>,
    summary: String? = null,
    val limit: () -> Int? = { null },
) : Setting<List<String>>(key, title, default, summary)

class NumberSetting(
    key: String,
    title: String,
    default: Int,
    val min: Int,
    val max: Int,
    val step: Int = 1,
    val unit: String = "",
    summary: String? = null,
) : Setting<Int>(key, title, default, summary)

/** Where setting values live. Offered to modules as [ModuleScope.settings]. */
interface SettingsStore {
    /** The current value, or the default if none is stored. Cheap: reads an in-memory snapshot. */
    operator fun <T : Any> get(setting: Setting<T>): T

    /** Emits the current value, then every change. */
    fun <T : Any> observe(setting: Setting<T>): Flow<T>

    /** Stores [value] asynchronously. */
    fun <T : Any> set(setting: Setting<T>, value: T)
}
