package com.newhost.qwertylauncher.ui.grid

import com.newhost.qwertylauncher.core.Responsive
import com.newhost.qwertylauncher.core.Size
import com.newhost.qwertylauncher.core.WidgetContent
import com.newhost.qwertylauncher.core.auto
import com.newhost.qwertylauncher.core.fr
import com.newhost.qwertylauncher.core.grid
import com.newhost.qwertylauncher.core.responsive
import com.newhost.qwertylauncher.ui.TtyFont
import kotlin.math.ceil

/**
 * A grid for widgets that give text and art rather than one of their own:
 * the art beside the text when that leaves the text [MIN_TEXT_COLUMNS], else
 * above it. The art starts at the widget's own size ([artSp]) and grows with
 * a taller tile.
 */
internal fun defaultLayout(content: WidgetContent, artSp: Float): Responsive {
    val art = content.art
    val text = content.text
    if (art == null) return grid { text(text) }
    val lines = art.split('\n')
    // At its own size the art's characters are artSp/terminal size of a cell each way.
    val scale = artSp / TtyFont.sizeSp
    val size = Size(
        ceil(lines.maxOf { it.length } * scale).toInt().coerceAtLeast(1),
        ceil(lines.size * scale).toInt().coerceAtLeast(1),
    )
    if (text.isEmpty()) return grid { picture(art, size, live = content.live) }
    return responsive {
        atLeast(columns = size.columns + 1 + MIN_TEXT_COLUMNS) {
            columns(auto(), fr())
            rows(fr())
            picture(art, size, live = content.live)
            text(text, column = 1)
        }
        otherwise {
            columns(fr())
            rows(auto(), fr())
            picture(art, size, live = content.live)
            text(text, row = 1)
        }
    }
}

private const val MIN_TEXT_COLUMNS = 14
