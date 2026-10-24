package com.newhost.qwertylauncher.settings

import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.MultiChoiceSetting
import com.newhost.qwertylauncher.core.PaneRow
import com.newhost.qwertylauncher.core.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * A [MultiChoiceSetting] as a list to tick, like the AI's model list:
 * `[x] bluetooth`, enter ticks or unticks, kept straight away. No more than
 * its limit can be ticked; trying says so. Esc goes [back] to config.
 */
internal class MultiChoicePane(
    private val setting: MultiChoiceSetting,
    private val store: SettingsStore,
    private val back: () -> Unit,
) : ListPane {
    override val title = "config: ${setting.title}"
    override val keysHelp = "⏎ tick  esc back"

    /** Why the last tick didn't take, until the next one does. */
    private val note = MutableStateFlow<String?>(null)

    override fun rows(): Flow<List<PaneRow>> = combine(store.observe(setting), note) { chosen, note ->
        val limit = setting.limit()
        buildList {
            add(PaneRow.Header(if (limit != null) "${chosen.size} of $limit ticked" else "${chosen.size} ticked"))
            if (note != null) add(PaneRow.Item(NOTE, note, status = note))
            setting.options.forEach { option ->
                val ticked = option.value in chosen
                add(
                    PaneRow.Item(
                        option.value,
                        "${if (ticked) "[x]" else "[ ]"} ${option.label}",
                        status = (if (ticked) "Enter unticks ${option.label}." else "Enter ticks ${option.label}.") +
                            (setting.summary?.let { "\n$it" } ?: ""),
                    ),
                )
            }
        }
    }

    override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
        if (item.id == NOTE) return false
        val chosen = store[setting]
        val limit = setting.limit()
        val next = when {
            item.id in chosen -> chosen - item.id
            limit != null && chosen.size >= limit -> {
                note.value = "No room for more: $limit fit. Untick one first."
                return false
            }
            else -> chosen + item.id
        }
        note.value = null
        // Kept in the options' order.
        store.set(setting, setting.options.map { it.value }.filter { it in next })
        return false
    }

    override fun onBack(host: LauncherHost): Boolean {
        back()
        return true
    }

    private companion object {
        const val NOTE = "multi:note"
    }
}
