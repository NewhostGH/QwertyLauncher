package com.newhost.qwertylauncher.home

import android.annotation.SuppressLint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.PasswordTransformationMethod
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.newhost.qwertylauncher.R
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.PaneInput
import com.newhost.qwertylauncher.core.PaneRow
import com.newhost.qwertylauncher.databinding.ItemPaneRowBinding
import com.newhost.qwertylauncher.databinding.ViewPaneBinding
import com.newhost.qwertylauncher.ui.ArtShape
import com.newhost.qwertylauncher.ui.PaneCursor
import com.newhost.qwertylauncher.ui.PaneState
import com.newhost.qwertylauncher.ui.Speech
import com.newhost.qwertylauncher.ui.TtyFont
import com.newhost.qwertylauncher.ui.VimKeys
import com.newhost.qwertylauncher.ui.applyTtyFont
import com.newhost.qwertylauncher.ui.syncFrame
import com.newhost.qwertylauncher.ui.grid.CellGridView
import com.newhost.qwertylauncher.ui.grid.GridEnv
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.newhost.qwertylauncher.core.R as CoreR

/** Draws a module's [ListPane] with the same layout, cursor and keys as config and help. */
internal class ListPaneScreen(
    private val binding: ViewPaneBinding,
    private val state: PaneState,
    private val host: LauncherHost,
    private val owner: LifecycleOwner,
    private val gridEnv: GridEnv,
    private val onClose: () -> Unit,
) {
    private var pane: ListPane? = null
    private var rows: List<PaneRow> = emptyList()
    private var collecting: Job? = null

    /** The pane's input line now, if it has one. */
    private var input: PaneInput? = null

    private val adapter: PaneRowAdapter = PaneRowAdapter(gridEnv) { position ->
        cursor.moveTo(position)
        open()
    }
    private val cursor: PaneCursor = PaneCursor(binding.list, state, { rows.size }, { rows[it] is PaneRow.Header }) { position ->
        adapter.cursor = position
        updateStatus()
    }

    init {
        binding.list.adapter = adapter
        // Enter from a keyboard app arrives as its "send" action; hardware enter comes through onKey.
        binding.input.setOnEditorActionListener { _, _, _ -> submitInput() }
        binding.input.onPreImeKey = { event ->
            // Enter is always taken before the keyboard app, which would make it a new line:
            // it sends what's typed, or with nothing typed opens the row under the cursor.
            if (input != null && isEnter(event)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !submitInput()) open()
                true
            } else {
                false
            }
        }
    }

    /** Starts showing [newPane]; its rows are collected while home is visible. */
    fun show(newPane: ListPane) {
        if (newPane !== pane) state.cursor = -1
        pane = newPane
        binding.title.text = binding.root.context.getString(R.string.pane_title, newPane.title)
        binding.syncFrame()
        binding.keys.text = newPane.keysHelp
        binding.applyTtyFont()
        collecting?.cancel()
        collecting = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { newPane.input().collect { showInput(it) } }
                newPane.rows().collect { submit(it) }
            }
        }
    }

    fun hide() {
        collecting?.cancel()
        collecting = null
        showInput(null)
    }

    /** Shows or hides the input line, keeping what's typed when it only changes hint. */
    private fun showInput(new: PaneInput?) {
        val old = input
        input = new
        binding.inputRow.isVisible = new != null
        binding.inputLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, TtyFont.sizeSp)
        binding.input.setTextSize(TypedValue.COMPLEX_UNIT_SP, TtyFont.sizeSp)
        if (new == null) {
            binding.input.text?.clear()
            binding.input.clearFocus()
            return
        }
        binding.input.hint = new.hint
        if (old?.secret != new.secret) {
            binding.input.transformationMethod = if (new.secret) PasswordTransformationMethod.getInstance() else null
        }
        // Typed keys go to it: it has the focus while it's there.
        binding.input.requestFocus()
    }

    /** Hands the typed text to the pane; false (so enter opens the row) when nothing's typed. */
    private fun submitInput(): Boolean {
        val line = input ?: return false
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return false
        binding.input.text?.clear()
        line.onSubmit(text, host)
        return true
    }

    /** Esc, q or back: the pane may go back somewhere itself; else it closes. */
    fun back() {
        if (pane?.onBack(host) != true) onClose()
    }

    fun rowHeight() = cursor.rowHeight()

    fun move(delta: Int) = cursor.move(delta)

    fun onKey(event: KeyEvent): Boolean {
        if (input != null) return onKeyWithInput(event)
        if (cursor.onKey(event)) return true
        when {
            VimKeys.isRight(event) ||
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_SPACE -> open()
            event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_Q -> back()
            else -> return pane?.onKey(currentItem(), event, host) ?: false
        }
        return true
    }

    /**
     * With an input line, letters are typed into it: only arrows, page keys
     * and home/end move the cursor. Enter sends what's typed (with nothing
     * typed, opens the row), esc clears the line (when empty, goes back).
     */
    private fun onKeyWithInput(event: KeyEvent): Boolean {
        val typed = binding.input.text?.isNotEmpty() == true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> cursor.move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> cursor.move(+1)
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END ->
                return cursor.onKey(event)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0 && !submitInput()) open()
            }
            KeyEvent.KEYCODE_ESCAPE -> if (typed) binding.input.text?.clear() else back()
            // Everything else types (or is the pane's own shortcut, with ctrl).
            else -> return event.isCtrlPressed && pane?.onKey(currentItem(), event, host) == true
        }
        return true
    }

    private fun isEnter(event: KeyEvent) =
        event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    /** New rows from the pane: keep the cursor on the same item if it's still there. */
    private fun submit(newRows: List<PaneRow>) {
        val currentId = currentItem()?.id
        // A chat keeps up with new messages when the cursor was on the last one (or nowhere yet).
        val atEnd = state.cursor < 0 || state.cursor >= rows.lastIndex
        rows = newRows
        adapter.submit(newRows)
        val sameItem = currentId?.let { id -> newRows.indexOfFirst { it is PaneRow.Item && it.id == id } } ?: -1
        state.cursor = when {
            pane?.followsEnd == true && atEnd -> newRows.lastIndex
            sameItem >= 0 -> sameItem
            else -> state.cursor.coerceAtMost(newRows.lastIndex)
        }
        cursor.show()
        if (newRows.none { it is PaneRow.Item }) binding.status.text = ""
    }

    private fun currentItem() = rows.getOrNull(cursor.position) as? PaneRow.Item

    private fun open() {
        val item = currentItem() ?: return
        if (pane?.onOpen(item, host) == true) onClose()
    }

    private fun updateStatus() {
        binding.status.text = currentItem()?.status ?: ""
    }
}

/**
 * One terminal line per row: `> icon text  detail`, headers as `── title ───`;
 * art blocks drawn like a widget tile.
 */
internal class PaneRowAdapter(
    private val gridEnv: GridEnv,
    private val onTap: (position: Int) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<PaneRow> = emptyList()

    var cursor = -1
        set(value) {
            val old = field
            field = value
            if (old in rows.indices) notifyItemChanged(old, REBIND)
            if (value in rows.indices) notifyItemChanged(value, REBIND)
        }

    @SuppressLint("NotifyDataSetChanged") // panes are short; rows re-emit as a whole
    fun submit(newRows: List<PaneRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = if (rows[position] is PaneRow.Block) BLOCK else LINE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == BLOCK) {
            val grid = CellGridView(parent.context, gridEnv)
            grid.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            BlockHolder(grid)
        } else {
            LineHolder(ItemPaneRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is LineHolder -> holder.bind(rows[position], position == cursor)
            is BlockHolder -> {
                holder.grid.setLayout((rows[position] as PaneRow.Block).layout)
                // Under the cursor, its art flickers like a selected tile's.
                holder.grid.select(position == cursor)
            }
        }
    }

    inner class LineHolder(private val item: ItemPaneRowBinding) : RecyclerView.ViewHolder(item.root) {
        private val context = item.root.context
        private val fg = context.getColor(CoreR.color.tty_fg)
        private val dim = context.getColor(CoreR.color.tty_dim)
        private val accent = context.getColor(CoreR.color.tty_accent)
        private val bg = context.getColor(CoreR.color.tty_bg)

        init {
            item.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && rows[position] is PaneRow.Item) onTap(position)
            }
        }

        fun bind(row: PaneRow, atCursor: Boolean) {
            val view = item.text
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, TtyFont.sizeSp)
            // Reverse video for the cursor line, like a terminal menu.
            item.root.setBackgroundColor(if (atCursor) fg else 0)
            item.icon.isVisible = false
            item.marker.isVisible = row is PaneRow.Item
            item.marker.setTextSize(TypedValue.COMPLEX_UNIT_SP, TtyFont.sizeSp)
            item.marker.setTextColor(if (atCursor) bg else fg)
            item.marker.text = if (atCursor) "> " else "  "
            when (row) {
                is PaneRow.Header -> {
                    view.setTextColor(accent)
                    view.text = "── ${row.title} ".padEnd(HEADER_WIDTH, '─')
                }
                is PaneRow.Item -> {
                    view.setTextColor(if (atCursor) bg else fg)
                    view.text = SpannableStringBuilder().apply {
                        row.detail?.let { detail ->
                            append(row.text)
                            append("  ")
                            append(detail, ForegroundColorSpan(if (atCursor) bg else dim), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        } ?: append(row.text)
                    }
                    showIcon(row.icon)
                }
                is PaneRow.Block -> Unit
            }
            // To a screen reader: the row's words, without the marker, icon or drawing; headers as headings.
            item.root.contentDescription = Speech.row(view.text)
            ViewCompat.setAccessibilityHeading(item.root, row is PaneRow.Header)
        }

        /**
         * The icon between the marker and the text, a column of space after it:
         * drawn a little under a line tall and centred in it, because block
         * characters reach past a line's edges and would be cut off otherwise.
         */
        private fun showIcon(icon: CharSequence?) {
            val shape = ArtShape.of(icon) ?: return
            val res = item.root.resources
            item.icon.isVisible = true
            item.icon.text = icon
            item.icon.gravity = Gravity.CENTER
            item.icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, shape.sizeForHeight((TtyFont.lineHeight(res) * ICON_FILL).toInt(), res))
            item.text.text = SpannableStringBuilder(" ").append(item.text.text)
        }
    }

    /** A grid block, as wide as the pane. */
    class BlockHolder(val grid: CellGridView) : RecyclerView.ViewHolder(grid)

    private companion object {
        const val HEADER_WIDTH = 40

        /** A row's icon fills this much of its line, leaving room for block characters' overhang. */
        const val ICON_FILL = 0.8f
        const val LINE = 0
        const val BLOCK = 1
        val REBIND = Any()
    }
}
