package com.newhost.qwertylauncher.settings

import android.view.KeyEvent
import com.newhost.qwertylauncher.R
import com.newhost.qwertylauncher.core.ChoiceSetting
import com.newhost.qwertylauncher.core.MultiChoiceSetting
import com.newhost.qwertylauncher.core.NumberSetting
import com.newhost.qwertylauncher.core.Setting
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.ToggleSetting
import com.newhost.qwertylauncher.databinding.ViewPaneBinding
import com.newhost.qwertylauncher.shell.SettingsSection
import com.newhost.qwertylauncher.ui.PaneCursor
import com.newhost.qwertylauncher.ui.PaneState
import com.newhost.qwertylauncher.ui.VimKeys
import com.newhost.qwertylauncher.ui.applyTtyFont

/**
 * The launcher settings as a full-screen pane inside home, in two levels,
 * so there's never a long list to wade through: first a line per section
 * (general, then each module: on or off, how many settings, a glance at
 * them); enter opens one, space switches a module on or off. In a section,
 * its settings, then its tiles' sizes. `/` searches every setting by name.
 * Esc goes back a level (from search, a section), then leaves.
 *
 * The host feeds it input and calls [refresh] when stored values change;
 * [onClose] asks the host to leave config.
 *
 * Enter on a number or a choice opens it for editing in place, with a slider:
 * type a number, or slide with the arrows, scrolling or a sideways swipe.
 * Enter keeps the new value, esc puts the old one back.
 */
internal class ConfigScreen(
    private val binding: ViewPaneBinding,
    private val store: SettingsStore,
    private val sections: () -> List<SettingsSection>,
    state: PaneState,
    /** Opens a list to tick, for a multiple choice. */
    private val onPick: (MultiChoiceSetting) -> Unit,
    private val onClose: () -> Unit,
) {
    private var rows: List<SettingsRow> = emptyList()
    private val adapter: SettingsAdapter = SettingsAdapter(store) { position -> tap(position) }
    private val cursor: PaneCursor = PaneCursor(binding.list, state, { rows.size }, { rows[it] is SettingsRow.Header }) { position ->
        adapter.cursor = position
        updateStatus()
    }

    init {
        binding.title.setText(R.string.settings_title)
        binding.keys.setText(R.string.settings_help)
        binding.applyTtyFont()
        binding.list.adapter = adapter
    }

    /** The number or choice being edited, or null. */
    private var edit: SettingEdit? = null

    /** The section open (its module's id), or null on the first page. */
    private var open: String? = null

    /** Where the cursor was on the first page, to go back to. */
    private var indexCursor = -1

    /** What's being searched for, or null; [typing] while it's still being typed. */
    private var query: String? = null
    private var typing = false

    /** Where the cursor was before searching. */
    private var beforeSearch = -1

    val isEditing: Boolean get() = edit != null

    /** Opens config where it was (a section opened, from its list to tick, comes back to that section). */
    fun show() {
        // A value left half-edited when config closed is dropped.
        setEdit(null)
        rebuild()
        cursor.show()
    }

    /**
     * Esc or back: out of search, out of a section to the first page (on
     * that section), or, from the first page, false: leave config.
     */
    fun back(): Boolean {
        when {
            edit != null -> setEdit(null)
            query != null -> {
                query = null
                typing = false
                rebuild()
                cursor.moveTo(beforeSearch)
            }
            open != null -> {
                open = null
                rebuild()
                cursor.moveTo(indexCursor)
            }
            else -> return false
        }
        return true
    }

    /**
     * Stored values changed: rebuild the rows (switching a module shows or hides
     * its options), keeping the cursor on the same setting.
     */
    fun refresh() {
        val current = currentSetting()
        val section = (rows.getOrNull(cursor.position) as? SettingsRow.Section)?.section?.moduleId
        rebuild()
        val index = when {
            current != null -> rows.indexOfFirst { it is SettingsRow.Item && it.setting === current }
            section != null -> rows.indexOfFirst { it is SettingsRow.Section && it.section.moduleId == section }
            else -> -1
        }
        if (index >= 0) cursor.moveTo(index) else cursor.show()
    }

    fun rowHeight() = cursor.rowHeight()

    fun move(delta: Int) = cursor.move(delta)

    /**
     * Scrolling moves the cursor; while editing, it slides the value instead:
     * swiping up or right for more, down or left for less.
     */
    fun scroll(down: Int, right: Int) {
        val current = edit
        if (current == null) cursor.move(down) else slide(current, down + right)
    }

    /** Handles a key press; returns false for keys config doesn't use. */
    fun onKey(event: KeyEvent): Boolean {
        edit?.let { return onEditKey(it, event) }
        if (typing) return onSearchKey(event)
        if (event.unicodeChar.toChar() == '/') {
            startSearch()
            return true
        }
        // Typing a number on a number row starts editing it with that digit. Checked first:
        // keyboards without a number row type digits with alt on letters that are also shortcuts.
        val setting = currentSetting()
        val digit = digitOf(event)
        if (digit != null && setting is NumberSetting) {
            val started = SettingEdit(setting, store[setting])
            type(started, digit)
            setEdit(started)
            return true
        }
        if (cursor.onKey(event)) return true
        val onSection = rows.getOrNull(cursor.position) as? SettingsRow.Section
        when {
            // On the first page: right or enter opens a section, space switches a module.
            onSection != null && event.keyCode == KeyEvent.KEYCODE_SPACE -> onSection.section.enabled?.let { store.set(it, !store[it]) } ?: openSection(onSection)
            onSection != null && (VimKeys.isRight(event) || isEnter(event)) -> openSection(onSection)
            onSection != null && VimKeys.isLeft(event) -> Unit
            VimKeys.isLeft(event) -> change(-1)
            VimKeys.isRight(event) -> change(+1)
            isEnter(event) || event.keyCode == KeyEvent.KEYCODE_SPACE -> activate()
            event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_Q -> if (!back()) onClose()
            else -> return false
        }
        return true
    }

    private fun openSection(row: SettingsRow.Section) {
        indexCursor = cursor.position
        open = row.section.moduleId
        rebuild()
        cursor.first()
    }

    // --- Searching: `/`, then type ---

    private fun startSearch() {
        beforeSearch = cursor.position
        query = ""
        typing = true
        rebuild()
        cursor.first()
        updateStatus()
    }

    /** While typing a search: letters go into it, up and down still move, enter goes through the results, esc stops. */
    private fun onSearchKey(event: KeyEvent): Boolean {
        val text = query ?: return false
        when {
            event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_BACK -> back()
            isEnter(event) -> {
                typing = false
                if (text.isEmpty()) back() else updateStatus()
            }
            event.keyCode == KeyEvent.KEYCODE_DEL -> setQuery(text.dropLast(1))
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> cursor.move(-1)
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> cursor.move(+1)
            else -> {
                val char = event.unicodeChar.takeIf { it != 0 }?.toChar() ?: return true
                if (!char.isISOControl()) setQuery(text + char)
            }
        }
        return true
    }

    private fun setQuery(text: String) {
        query = text
        rebuild()
        cursor.first()
        updateStatus()
    }

    /** Enter or a tap on a row: opens a section; toggles a switch, opens a number or choice for editing. */
    private fun activate() {
        (rows.getOrNull(cursor.position) as? SettingsRow.Section)?.let {
            openSection(it)
            return
        }
        val setting = currentSetting() ?: return
        when (setting) {
            is ToggleSetting -> change(+1)
            is NumberSetting -> setEdit(SettingEdit(setting, store[setting]))
            is ChoiceSetting -> setEdit(SettingEdit(setting, store[setting]))
            is MultiChoiceSetting -> onPick(setting)
        }
    }

    /** A tap: on the row being edited it keeps the value; elsewhere it moves there and activates. */
    private fun tap(position: Int) {
        val editing = edit
        if (editing != null) {
            if (position == cursor.position) {
                commit(editing)
                return
            }
            setEdit(null)
        }
        cursor.moveTo(position)
        activate()
    }

    private fun onEditKey(edit: SettingEdit, event: KeyEvent): Boolean {
        val big = if (event.isShiftPressed) BIG_STEP else 1
        val digit = digitOf(event)
        when {
            // Digits first: they may come from alt on letter keys that are also shortcuts.
            digit != null && edit.setting is NumberSetting -> {
                type(edit, digit)
                showEdit()
            }
            // Not a held enter's repeats: the press that opened the editor may still be down.
            isEnter(event) && event.repeatCount > 0 -> Unit
            isEnter(event) || event.keyCode == KeyEvent.KEYCODE_SPACE -> commit(edit)
            event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_Q ||
                event.keyCode == KeyEvent.KEYCODE_BACK -> setEdit(null)
            VimKeys.isRight(event) || VimKeys.isUp(event) -> slide(edit, big)
            VimKeys.isLeft(event) || VimKeys.isDown(event) -> slide(edit, -big)
            event.keyCode == KeyEvent.KEYCODE_MOVE_HOME -> slide(edit, Int.MIN_VALUE / 2)
            event.keyCode == KeyEvent.KEYCODE_MOVE_END -> slide(edit, Int.MAX_VALUE / 2)
            event.keyCode == KeyEvent.KEYCODE_DEL -> {
                edit.typed = edit.typed.dropLast(1)
                edit.typed.toIntOrNull()?.let { edit.draft = it }
                showEdit()
            }
        }
        // Everything else is swallowed: the editor has the keyboard until enter or esc.
        return true
    }

    private fun type(edit: SettingEdit, digit: Char) {
        val setting = edit.setting as NumberSetting
        // No more digits than the largest value has.
        if (edit.typed.length >= setting.max.toString().length) return
        edit.typed += digit
        edit.draft = edit.typed.toInt()
    }

    /** Moves the value [steps] along, stopping at the ends. */
    private fun slide(edit: SettingEdit, steps: Int) {
        if (steps == 0) return
        edit.typed = ""
        when (val setting = edit.setting) {
            is NumberSetting -> {
                val from = (edit.draft as Int).coerceIn(setting.min, setting.max)
                val next = from.toLong() + steps.toLong() * setting.step
                edit.draft = next.coerceIn(setting.min.toLong(), setting.max.toLong()).toInt()
            }
            is ChoiceSetting -> {
                val options = setting.options
                val from = options.indexOfFirst { it.value == edit.draft }.coerceAtLeast(0)
                val next = (from.toLong() + steps).coerceIn(0L, options.lastIndex.toLong()).toInt()
                edit.draft = options[next].value
            }
            is ToggleSetting, is MultiChoiceSetting -> Unit
        }
        showEdit()
    }

    /** Stores the edited value (a typed number is kept within its range) and closes the editor. */
    private fun commit(edit: SettingEdit) {
        when (val setting = edit.setting) {
            is NumberSetting -> store.set(setting, (edit.draft as Int).coerceIn(setting.min, setting.max))
            is ChoiceSetting -> store.set(setting, edit.draft as String)
            is ToggleSetting, is MultiChoiceSetting -> Unit
        }
        setEdit(null)
    }

    private fun setEdit(value: SettingEdit?) {
        edit = value
        showEdit()
    }

    private fun showEdit() {
        adapter.edit = edit
        if (cursor.position >= 0) adapter.notifyItemChanged(cursor.position)
        updateStatus()
    }

    private fun isEnter(event: KeyEvent) =
        event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    /** The digit a key types, with alt or sym held too, as on keyboards without a number row. */
    private fun digitOf(event: KeyEvent): Char? =
        event.unicodeChar.takeIf { it != 0 }?.toChar()?.takeIf { it in '0'..'9' }

    /** The rows for where config is: search results, a section, or the first page; and the title to match. */
    private fun rebuild() {
        val all = sections()
        val section = open?.let { id -> all.firstOrNull { it.moduleId == id } }
        if (open != null && section == null) open = null
        val search = query
        rows = when {
            search != null && search.isNotBlank() -> SettingsRow.search(all, store, search)
            search != null -> SettingsRow.index(all)
            section != null -> SettingsRow.section(section, store)
            else -> SettingsRow.index(all)
        }
        adapter.submit(rows)
        binding.title.text = when {
            search != null -> "~$ config /$search" + if (typing) "_" else ""
            section != null -> "~$ config/${section.title}"
            else -> binding.root.context.getString(R.string.settings_title)
        }
    }

    private fun currentSetting(): Setting<*>? = (rows.getOrNull(cursor.position) as? SettingsRow.Item)?.setting

    private fun change(direction: Int) {
        val row = rows.getOrNull(cursor.position) as? SettingsRow.Item ?: return
        SettingValues.change(store, row.setting, direction)
    }

    private fun updateStatus() {
        if (typing) {
            val found = rows.count { it is SettingsRow.Item }
            binding.status.text = "/${query}_  ${if (query.isNullOrBlank()) "type a name" else "$found found"}\n↑↓ pick  ⏎ done typing  esc stop"
            return
        }
        (rows.getOrNull(cursor.position) as? SettingsRow.Section)?.let { row ->
            val section = row.section
            val count = section.settings.size + section.tiles.size
            val switch = section.enabled
            binding.status.text = "${section.title}: $count settings" + when {
                switch == null -> ""
                store[switch] -> ", on"
                else -> ", off"
            } + "\n⏎ open" + (if (switch != null) "  space on/off" else "") + "  / search"
            return
        }
        val row = rows.getOrNull(cursor.position) as? SettingsRow.Item ?: return
        val editing = edit
        binding.status.text = when {
            editing == null -> "${row.section}: " + SettingValues.describe(store, row.setting, row.isSwitch)
            editing.setting is NumberSetting -> "${row.setting.title}\ntype a number  ←→ slide  ⏎ keep  esc undo"
            else -> "${row.setting.title}\n←→ choose  ⏎ keep  esc undo"
        }
    }

    private companion object {
        /** Steps per press with shift held. */
        const val BIG_STEP = 10
    }
}
