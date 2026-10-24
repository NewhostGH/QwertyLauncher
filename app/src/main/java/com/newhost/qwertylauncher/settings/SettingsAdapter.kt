package com.newhost.qwertylauncher.settings

import android.annotation.SuppressLint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.newhost.qwertylauncher.R
import com.newhost.qwertylauncher.core.ChoiceSetting
import com.newhost.qwertylauncher.core.MultiChoiceSetting
import com.newhost.qwertylauncher.core.NumberSetting
import com.newhost.qwertylauncher.core.Setting
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.ToggleSetting
import com.newhost.qwertylauncher.shell.SettingsSection
import com.newhost.qwertylauncher.ui.TtyFont
import kotlin.math.roundToInt
import com.newhost.qwertylauncher.core.R as CoreR

internal sealed interface SettingsRow {
    class Header(val title: String) : SettingsRow

    /** [isSwitch] marks a module's on/off switch. */
    class Item(val setting: Setting<*>, val section: String, val isSwitch: Boolean) : SettingsRow

    /** A section on config's first page: enter opens it; a module's shows whether it's on. */
    class Section(val section: SettingsSection) : SettingsRow

    companion object {
        /** Config's first page: a line per section, under its heading (launcher, home tiles...). */
        fun index(sections: List<SettingsSection>): List<SettingsRow> = buildList {
            var group: String? = null
            for (section in sections) {
                if (section.group != group) {
                    group = section.group
                    add(Header(section.group))
                }
                add(Section(section))
            }
        }

        /**
         * One section: its switch, and (when it's on) its settings, then its
         * tiles' sizes under their own header. A module that's off shows only its switch.
         */
        fun section(section: SettingsSection, store: SettingsStore): List<SettingsRow> = buildList {
            val switch = section.enabled
            val on = switch == null || store[switch]
            add(Header(section.title))
            if (switch != null) add(Item(switch, section.title, isSwitch = true))
            if (!on) return@buildList
            section.settings.forEach { add(Item(it, section.title, isSwitch = false)) }
            if (section.tiles.isNotEmpty()) {
                add(Header("${section.title}: tile"))
                section.tiles.forEach { add(Item(it, section.title, isSwitch = false)) }
            }
        }

        /** Every setting whose name (or section's) has [query] in it, from sections that are on, by section. */
        fun search(sections: List<SettingsSection>, store: SettingsStore, query: String): List<SettingsRow> = buildList {
            val q = query.trim().lowercase()
            add(Header("settings with \"$query\""))
            for (section in sections) {
                val switch = section.enabled
                val on = switch == null || store[switch]
                val all = listOfNotNull(switch) + if (on) section.settings + section.tiles else emptyList()
                val found = all.filter { q in it.title.lowercase() || q in section.title.lowercase() }
                if (found.isEmpty()) continue
                add(Header(section.title))
                found.forEach { add(Item(it, section.title, isSwitch = it === switch)) }
            }
        }
    }
}

/**
 * A number or choice open for editing in config: [draft] is the value shown
 * (an Int or a choice's value), stored only when the edit is kept. [typed] is
 * what's been typed of a number, shown as typed until it's kept.
 */
internal class SettingEdit(val setting: Setting<*>, var draft: Any, var typed: String = "")

/** Reads and steps setting values; shared by keys and taps. */
internal object SettingValues {
    fun label(store: SettingsStore, setting: Setting<*>): String = when (setting) {
        is ToggleSetting -> if (store[setting]) "on" else "off"
        is ChoiceSetting -> store[setting].let { value -> setting.options.firstOrNull { it.value == value }?.label ?: value }
        is NumberSetting -> store[setting].toString() + if (setting.unit.isEmpty()) "" else " ${setting.unit}"
        is MultiChoiceSetting -> store[setting].let { chosen ->
            if (chosen.isEmpty()) "none" else setting.options.filter { it.value in chosen }.joinToString("  ") { it.label }
        }
    }

    /** Toggles, or steps a choice/number by [direction], wrapping at the ends. */
    fun change(store: SettingsStore, setting: Setting<*>, direction: Int) {
        when (setting) {
            is ToggleSetting -> store.set(setting, !store[setting])
            is ChoiceSetting -> {
                val options = setting.options
                val current = options.indexOfFirst { it.value == store[setting] }.coerceAtLeast(0)
                store.set(setting, options[(current + direction).mod(options.size)].value)
            }
            // Ticked in its editor, one by one.
            is MultiChoiceSetting -> Unit
            is NumberSetting -> {
                val next = store[setting] + direction * setting.step
                store.set(
                    setting,
                    when {
                        next > setting.max -> setting.min
                        next < setting.min -> setting.max
                        else -> next
                    },
                )
            }
        }
    }

    /** A sentence for the status bar: the value, and what it can be. */
    fun describe(store: SettingsStore, setting: Setting<*>, isSwitch: Boolean): String {
        val value = label(store, setting)
        val options = when (setting) {
            is NumberSetting -> "Choose from ${setting.min} to ${setting.max}${if (setting.unit.isEmpty()) "" else " " + setting.unit}."
            is ChoiceSetting -> "Options are ${setting.options.joinToString(", ") { it.label }}."
            is ToggleSetting -> ""
            is MultiChoiceSetting -> "Enter opens the list to tick${setting.limit()?.let { ", up to $it" } ?: ""}."
        }
        val what = when {
            setting.summary != null -> setting.summary
            isSwitch -> "When off, this module and all its options are hidden."
            else -> ""
        }
        return "${setting.title.replaceFirstChar { it.uppercase() }} is $value. $options\n$what".trim()
    }
}

/**
 * One terminal line per row: `[x] toggle`, `setting        value`. The cursor
 * row is shown in reverse video; views never take focus, so the host's key
 * handling owns navigation.
 */
internal class SettingsAdapter(
    private val store: SettingsStore,
    private val onTap: (position: Int) -> Unit,
) : RecyclerView.Adapter<SettingsAdapter.Holder>() {

    var rows: List<SettingsRow> = emptyList()
        private set

    /** The setting being edited, drawn with its slider; the host rebinds its row. */
    var edit: SettingEdit? = null

    var cursor = -1
        set(value) {
            val old = field
            field = value
            if (old in rows.indices) notifyItemChanged(old, REBIND)
            if (value in rows.indices) notifyItemChanged(value, REBIND)
        }

    @SuppressLint("NotifyDataSetChanged") // rows come and go with module switches; the list is short
    fun submit(newRows: List<SettingsRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_setting, parent, false) as TextView
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position], position == cursor)

    inner class Holder(private val view: TextView) : RecyclerView.ViewHolder(view) {
        private val fg = view.context.getColor(CoreR.color.tty_fg)
        private val bg = view.context.getColor(CoreR.color.tty_bg)
        private val dim = view.context.getColor(CoreR.color.tty_dim)
        private val accent = view.context.getColor(CoreR.color.tty_accent)
        private val cyan = view.context.getColor(CoreR.color.tty_cyan)

        init {
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && rows[position] !is SettingsRow.Header) onTap(position)
            }
        }

        fun bind(row: SettingsRow, atCursor: Boolean) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, TtyFont.sizeSp)
            val editing = (row as? SettingsRow.Item)?.let { item -> edit?.takeIf { it.setting === item.setting } }
            // The row being edited has a second line: its slider or its options.
            view.maxLines = if (editing != null) 2 else 1
            // Reverse video for the cursor line, like a terminal menu.
            view.setBackgroundColor(if (atCursor) fg else bg)
            view.setTextColor(if (atCursor) bg else fg)
            view.text = when (row) {
                is SettingsRow.Header -> SpannableStringBuilder().apply {
                    append("── ${row.title} ".padEnd(HEADER_WIDTH, '─'), ForegroundColorSpan(accent), SPAN)
                }
                is SettingsRow.Item -> if (editing != null) editor(row, editing) else item(row, atCursor)
                is SettingsRow.Section -> section(row, atCursor)
            }
        }

        /**
         * The row being edited, always under the cursor:
         * ```
         *     font size            < 14 sp >
         *     9 ━━━━━━━━●────────────── 20
         * ```
         */
        private fun editor(row: SettingsRow.Item, edit: SettingEdit) = SpannableStringBuilder().apply {
            append("    ").append(row.setting.title.padEnd(TITLE_WIDTH)).append(' ')
            when (val setting = row.setting) {
                is NumberSetting -> {
                    val unit = if (setting.unit.isEmpty()) "" else " ${setting.unit}"
                    // While typing, the digits so far and a cursor; else the value between arrows.
                    append(if (edit.typed.isNotEmpty()) "${edit.typed}_$unit" else "< ${edit.draft}$unit >")
                    append("\n    ").append(setting.min.toString()).append(' ')
                    val value = (edit.draft as Int).coerceIn(setting.min, setting.max)
                    val span = (setting.max - setting.min).coerceAtLeast(1)
                    append(slider((value - setting.min).toFloat() / span))
                    append(' ').append(setting.max.toString())
                }
                is ChoiceSetting -> {
                    val current = setting.options.firstOrNull { it.value == edit.draft }
                    append("< ${current?.label ?: edit.draft} >")
                    append("\n    ")
                    val labels = setting.options.map { if (it.value == edit.draft) "[${it.label}]" else it.label }
                    append(windowed(labels, setting.options.indexOfFirst { it.value == edit.draft }.coerceAtLeast(0), columns() - INDENT))
                }
                is ToggleSetting -> append(SettingValues.label(store, setting))
                is MultiChoiceSetting -> append(SettingValues.label(store, setting))
            }
        }

        /** The row's width in characters, the screen's before it's laid out. */
        private fun columns(): Int {
            val width = view.width.takeIf { it > 0 } ?: view.resources.displayMetrics.widthPixels
            return ((width - view.paddingLeft - view.paddingRight) / TtyFont.cellWidth(view.resources)).toInt()
        }

        /**
         * The options on one line. When they don't all fit, a window of them
         * centered on the [focus] one (the chosen, or the cursor's), with `<` or
         * `>` where there are more beyond the edge:
         * ```
         * < mono  [fira code]  hack  iosevka >
         * ```
         */
        private fun windowed(labels: List<String>, focus: Int, columns: Int): String {
            val all = labels.joinToString(GAP)
            if (all.length <= columns) return all
            var from = focus
            var to = focus
            fun width(a: Int, b: Int): Int {
                val marks = (if (a > 0) MORE_LEFT.length else 0) + (if (b < labels.lastIndex) MORE_RIGHT.length else 0)
                return labels.subList(a, b + 1).sumOf { it.length } + GAP.length * (b - a) + marks
            }
            // Grow a side at a time, alternating, so the chosen one stays in the middle; one side when the other can't.
            var preferLeft = true
            while (true) {
                val canLeft = from > 0 && width(from - 1, to) <= columns
                val canRight = to < labels.lastIndex && width(from, to + 1) <= columns
                when {
                    canLeft && (preferLeft || !canRight) -> from--
                    canRight -> to++
                    else -> break
                }
                preferLeft = !preferLeft
            }
            return (if (from > 0) MORE_LEFT else "") + labels.subList(from, to + 1).joinToString(GAP) + (if (to < labels.lastIndex) MORE_RIGHT else "")
        }

        /** `━━━━━●──────`: filled up to the knob, empty after it. */
        private fun slider(fraction: Float): String {
            val knob = (fraction.coerceIn(0f, 1f) * (SLIDER_WIDTH - 1)).roundToInt()
            return "━".repeat(knob) + "●" + "─".repeat(SLIDER_WIDTH - 1 - knob)
        }

        /**
         * A section on the first page: on or off (a module), its name, how
         * many settings, and a few of their values to see at a glance.
         * ```
         * [x] weather            3  °C, km/h  30 min  on  >
         * ```
         */
        private fun section(row: SettingsRow.Section, atCursor: Boolean) = SpannableStringBuilder().apply {
            val section = row.section
            val switch = section.enabled
            val on = switch == null || store[switch]
            append(
                when {
                    switch == null -> "    "
                    on -> "[x] "
                    else -> "[ ] "
                },
            )
            append(section.title.padEnd(TITLE_WIDTH), if (on) ForegroundColorSpan(if (atCursor) bg else fg) else ForegroundColorSpan(if (atCursor) bg else dim), SPAN)
            append(' ')
            val count = section.settings.size + section.tiles.size
            val glance = if (on) section.settings.take(GLANCE).joinToString("  ") { SettingValues.label(store, it) } else "off"
            append("$count".padStart(COUNT_WIDTH), ForegroundColorSpan(if (atCursor) bg else dim), SPAN)
            append("  ")
            append(glance, ForegroundColorSpan(if (atCursor) bg else cyan), SPAN)
        }

        private fun item(row: SettingsRow.Item, atCursor: Boolean) = SpannableStringBuilder().apply {
            val setting = row.setting
            if (setting is ToggleSetting) {
                append(if (store[setting]) "[x] " else "[ ] ").append(setting.title)
            } else {
                append("    ").append(setting.title.padEnd(TITLE_WIDTH)).append(' ')
                append(SettingValues.label(store, setting), ForegroundColorSpan(if (atCursor) bg else cyan), SPAN)
            }
            if (row.isSwitch) append("  (module)", ForegroundColorSpan(if (atCursor) bg else dim), SPAN)
        }
    }

    private companion object {
        const val HEADER_WIDTH = 40
        /** Settings shown at a glance on the first page, and the width of their count. */
        const val GLANCE = 3
        const val COUNT_WIDTH = 2
        const val TITLE_WIDTH = 20
        const val SLIDER_WIDTH = 24
        const val INDENT = 4
        const val GAP = "  "
        const val MORE_LEFT = "< "
        const val MORE_RIGHT = " >"
        const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        val REBIND = Any()
    }
}
