package com.newhost.qwertylauncher.help

import android.annotation.SuppressLint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.newhost.qwertylauncher.R
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.databinding.ViewPaneBinding
import com.newhost.qwertylauncher.ui.PaneCursor
import com.newhost.qwertylauncher.ui.PaneState
import com.newhost.qwertylauncher.ui.Speech
import com.newhost.qwertylauncher.ui.TtyFont
import com.newhost.qwertylauncher.ui.applyTtyFont
import com.newhost.qwertylauncher.ui.VimKeys
import com.newhost.qwertylauncher.core.R as CoreR

internal sealed interface HelpRow {
    class Header(val title: String) : HelpRow
    class CommandRow(val command: Command) : HelpRow
    class KeyRow(val keys: String, val does: String) : HelpRow
}

/**
 * `help` as a full-screen pane inside home: every command of the enabled
 * modules, then the keys of each screen. Enter on a command closes help and
 * types the command at the prompt ([onPick]).
 */
internal class HelpScreen(
    private val binding: ViewPaneBinding,
    private val commands: () -> List<Command>,
    state: PaneState,
    private val onPick: (Command) -> Unit,
    private val onClose: () -> Unit,
) {
    private var rows: List<HelpRow> = emptyList()
    private val adapter: HelpAdapter = HelpAdapter { position ->
        cursor.moveTo(position)
        pick()
    }
    private val cursor: PaneCursor = PaneCursor(binding.list, state, { rows.size }, { rows[it] is HelpRow.Header }) { position ->
        adapter.cursor = position
        updateStatus()
    }

    init {
        binding.title.setText(R.string.help_title)
        binding.keys.setText(R.string.help_keys)
        binding.applyTtyFont()
        binding.list.adapter = adapter
    }

    /** Rebuilds the list (modules may have been switched on or off) and shows the cursor. */
    fun show() {
        rows = buildRows()
        adapter.submit(rows)
        cursor.show()
    }

    fun rowHeight() = cursor.rowHeight()

    fun move(delta: Int) = cursor.move(delta)

    fun onKey(event: KeyEvent): Boolean {
        if (cursor.onKey(event)) return true
        when {
            VimKeys.isRight(event) ||
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_SPACE -> pick()
            event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_Q -> onClose()
            else -> return false
        }
        return true
    }

    private fun pick() {
        val row = rows.getOrNull(cursor.position) as? HelpRow.CommandRow ?: return
        onPick(row.command)
    }

    private fun updateStatus() {
        binding.status.text = when (val row = rows.getOrNull(cursor.position)) {
            is HelpRow.CommandRow -> {
                val command = row.command
                val aliases = if (command.aliases.isEmpty()) "" else "  (also: ${command.aliases.joinToString(", ")})"
                "${command.usage}$aliases\n${command.summary}  (⏎ type it)"
            }
            is HelpRow.KeyRow -> "${row.keys}\n${row.does}"
            else -> ""
        }
    }

    private fun buildRows(): List<HelpRow> = buildList {
        add(HelpRow.Header("commands"))
        commands().sortedBy { it.name }.forEach { add(HelpRow.CommandRow(it)) }
        for ((section, keys) in KEYS) {
            add(HelpRow.Header(section))
            keys.forEach { (key, does) -> add(HelpRow.KeyRow(key, does)) }
        }
    }

    private companion object {
        val KEYS = listOf(
            "home" to listOf(
                "any key" to "opens the prompt and starts a search",
                "scroll, ←↑↓→" to "move through the tiles, their controls (like music's) and favourites; a slide on the keyboard does the same",
                "finger" to "drags the screen itself up and down; swipes a grid of apps to its next page",
                "⏎ space" to "open what is selected",
                "hold ⏎" to "on a tile: move, resize or hide it (hiding turns its module off); on the question mark or wrench: move help and config along the top, as one",
                "hold ⏎, menu" to "on a favourite: its options",
                "question mark" to "opens this help",
                "wrench" to "opens config",
                "toggles" to "top left: enter switches one; hold enter for its settings, to hide it, or to move the toggles (left, middle or right, beside help and config or apart); tick them in config under quick toggles",
                "Sir Alex" to "the little knight: tap to poke him, drag to carry him; alex calls him, alex and words talks to him",
                "esc, back" to "closes the prompt, then clears the console",
            ),
            "editing a value in config" to listOf(
                "digits" to "type a number",
                "←→, scroll" to "slide it",
                "shift ←→" to "ten steps at a time",
                "⏎" to "keeps it",
                "esc, q, back" to "puts the old value back",
            ),
            "resizing a tile" to listOf(
                "←→ h l" to "narrower, wider, a column at a time",
                "↑↓ k j" to "shorter, taller, by one line; favourites and suggestions jump between their row heights (5 to 7 lines a row, 10 to 14 for two...)",
                "shift" to "five at a time",
                "scroll" to "sideways for width, up and down for height",
                "0 or a" to "automatic size",
                "⏎" to "keeps the new size",
                "esc, q, back" to "puts the old size back",
            ),
            "prompt" to listOf(
                "↑↓" to "pick a result; on an empty line, walk the history",
                "⏎" to "runs the selected result",
                "hold ⏎" to "more actions, like favourites, rename or uninstall",
                "shift ⏎, menu" to "the same actions, without holding",
                "tab" to "completes it",
                "? question" to "asks the AI in a new conversation",
                "ai" to "your AI conversations, and its setup",
                "^c" to "cancels the line",
                "^u" to "clears the line",
                "^l" to "clears the console",
                "^d" to "closes an empty prompt",
                "^p ^n" to "previous and next line in history",
            ),
            "games (play)" to listOf(
                "←↑↓→ wasd" to "move, steer, slide; in tetris w turns and s drops faster",
                "space ⏎ x" to "tetris: drop; after game over: play again",
                "swipe, tap" to "the same by touch; tetris follows a drag, a flick down drops, a tap turns",
                "p, r" to "pause, start again",
                "esc, back" to "leave: the game waits, paused",
            ),
            "ai conversation" to listOf(
                "type ⏎" to "sends a message",
                "> do: ..." to "something the AI offers to do on the phone: enter on it runs it, nothing runs until then",
                "^c ^n" to "stop an answer, start a new conversation",
            ),
            "panes" to listOf(
                "↑↓ j k" to "move",
                "←→ h l" to "change a setting",
                "⏎ space" to "toggle or use; on a number or choice, edit it",
                "tab, shift tab" to "next and previous section",
                "g, shift+g" to "top and end",
                "q, esc, back" to "go back",
            ),
        )
    }
}

/** One terminal line per row: `> usage   summary`, `  keys   what they do`. */
internal class HelpAdapter(
    private val onTap: (position: Int) -> Unit,
) : RecyclerView.Adapter<HelpAdapter.Holder>() {

    private var rows: List<HelpRow> = emptyList()

    var cursor = -1
        set(value) {
            val old = field
            field = value
            if (old in rows.indices) notifyItemChanged(old, REBIND)
            if (value in rows.indices) notifyItemChanged(value, REBIND)
        }

    @SuppressLint("NotifyDataSetChanged") // the whole list is rebuilt when help opens
    fun submit(newRows: List<HelpRow>) {
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
        private val dim = view.context.getColor(CoreR.color.tty_dim)
        private val accent = view.context.getColor(CoreR.color.tty_accent)
        private val bg = view.context.getColor(CoreR.color.tty_bg)

        init {
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && rows[position] !is HelpRow.Header) onTap(position)
            }
        }

        fun bind(row: HelpRow, atCursor: Boolean) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, TtyFont.sizeSp)
            // Reverse video for the cursor line, like a terminal menu.
            view.setBackgroundColor(if (atCursor) fg else 0)
            val marker = if (atCursor) "> " else "  "
            when (row) {
                is HelpRow.Header -> {
                    view.setTextColor(accent)
                    view.text = "── ${row.title} ".padEnd(HEADER_WIDTH, '─')
                }
                is HelpRow.CommandRow -> {
                    view.setTextColor(if (atCursor) bg else fg)
                    view.text = line(marker, row.command.usage, row.command.summary, atCursor)
                }
                is HelpRow.KeyRow -> {
                    view.setTextColor(if (atCursor) bg else fg)
                    view.text = line(marker, row.keys, row.does, atCursor)
                }
            }
            // To a screen reader: the row's words, without the marker or drawing; headers as headings.
            view.contentDescription = Speech.row(view.text)
            ViewCompat.setAccessibilityHeading(view, row is HelpRow.Header)
        }

        private fun line(marker: String, left: String, right: String, atCursor: Boolean) = SpannableStringBuilder().apply {
            append(marker).append(left.padEnd(LEFT_WIDTH)).append(' ')
            append(right, ForegroundColorSpan(if (atCursor) bg else dim), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private companion object {
        const val HEADER_WIDTH = 40
        const val LEFT_WIDTH = 22
        val REBIND = Any()
    }
}
