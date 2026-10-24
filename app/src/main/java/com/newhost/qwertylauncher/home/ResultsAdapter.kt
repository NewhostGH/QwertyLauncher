package com.newhost.qwertylauncher.home

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.newhost.qwertylauncher.R
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.ui.TtyFont
import com.newhost.qwertylauncher.core.R as CoreR

/** One terminal line per result: `> tag  title  detail`. */
internal class ResultsAdapter(
    private val onClick: (index: Int) -> Unit,
    private val onLongClick: (index: Int) -> Unit,
) : ListAdapter<SearchResult, ResultsAdapter.Holder>(Diff) {

    private var selected = 0

    fun submit(results: List<SearchResult>, selectedIndex: Int) {
        if (results === currentList) {
            select(selectedIndex)
        } else {
            submitList(results) { select(selectedIndex) }
        }
    }

    private fun select(index: Int) {
        selected = index
        // Rows may have moved during a diff, so refresh the marker on all of them (≤ 50, cheap).
        notifyItemRangeChanged(0, itemCount, SELECTION_CHANGED)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false) as TextView
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) =
        holder.bind(getItem(position), position == selected)

    inner class Holder(private val view: TextView) : RecyclerView.ViewHolder(view) {
        private val dim = view.context.getColor(CoreR.color.tty_dim)
        private val fg = view.context.getColor(CoreR.color.tty_fg)
        private val bg = view.context.getColor(CoreR.color.tty_bg)

        // ANSI-style colour per result type; unknown tags stay dim.
        private val tagColors = mapOf(
            "app" to CoreR.color.tty_fg,
            "sc" to CoreR.color.tty_fg,
            "cmd" to CoreR.color.tty_accent,
            "ctc" to CoreR.color.tty_cyan,
            "call" to CoreR.color.tty_cyan,
            "sms" to CoreR.color.tty_cyan,
            "web" to CoreR.color.tty_blue,
            "url" to CoreR.color.tty_blue,
            "ai" to CoreR.color.tty_magenta,
            "calc" to CoreR.color.tty_yellow,
            "wx" to CoreR.color.tty_yellow,
            "unin" to CoreR.color.tty_red,
        ).mapValues { (_, res) -> view.context.getColor(res) }

        init {
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(position)
            }
            view.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onLongClick(position)
                true
            }
        }

        fun bind(result: SearchResult, isSelected: Boolean) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, TtyFont.sizeSp)
            // Reverse video for the selected line, like a terminal menu.
            view.setBackgroundColor(if (isSelected) fg else 0)
            view.setTextColor(if (isSelected) bg else fg)
            view.text = SpannableStringBuilder().apply {
                append(if (isSelected) "> " else "  ")
                append(
                    result.tag.padEnd(TAG_WIDTH).take(TAG_WIDTH),
                    ForegroundColorSpan(if (isSelected) bg else tagColors[result.tag] ?: dim),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                append(result.title)
                result.detail?.let {
                    append("  ")
                    append(it, ForegroundColorSpan(if (isSelected) bg else dim), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<SearchResult>() {
        override fun areItemsTheSame(a: SearchResult, b: SearchResult) = a.key == b.key

        // Actions are lambdas and never compare equal, so compare only what is shown.
        override fun areContentsTheSame(a: SearchResult, b: SearchResult) =
            a.tag == b.tag && a.title.toString() == b.title.toString() && a.detail?.toString() == b.detail?.toString()
    }

    private companion object {
        const val TAG_WIDTH = 5
        val SELECTION_CHANGED = Any()
    }
}
