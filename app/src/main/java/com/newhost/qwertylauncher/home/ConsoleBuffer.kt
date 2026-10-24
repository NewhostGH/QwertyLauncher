package com.newhost.qwertylauncher.home

import android.text.SpannableStringBuilder
import com.newhost.qwertylauncher.core.Console
import com.newhost.qwertylauncher.core.ConsoleLine

/** Bounded scrollback that keeps spans (colours). Main thread only, like the [Console] contract says. */
internal class ConsoleBuffer(
    private val maxLines: Int = 200,
    private val onChange: (CharSequence) -> Unit,
) : Console {

    private val lines = ArrayDeque<SpannableStringBuilder>()

    override fun println(text: CharSequence) = add(SpannableStringBuilder(text))

    override fun newLine(prefix: CharSequence): ConsoleLine {
        val line = SpannableStringBuilder(prefix)
        add(line)
        val start = prefix.length
        return object : ConsoleLine {
            override fun append(text: CharSequence) {
                line.append(text)
                publish()
            }

            override fun set(text: CharSequence) {
                line.replace(start, line.length, text)
                publish()
            }
        }
    }

    override fun clear() {
        lines.clear()
        publish()
    }

    private fun add(line: SpannableStringBuilder) {
        lines.addLast(line)
        while (lines.size > maxLines) lines.removeFirst()
        publish()
    }

    private fun publish() {
        val text = SpannableStringBuilder()
        lines.forEachIndexed { i, line ->
            if (i > 0) text.append('\n')
            text.append(line)
        }
        onChange(text)
    }
}
