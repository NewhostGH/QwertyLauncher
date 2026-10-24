package com.newhost.qwertylauncher.feature.clock

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.provider.AlarmClock
import com.newhost.qwertylauncher.core.Align
import com.newhost.qwertylauncher.core.Cell
import com.newhost.qwertylauncher.core.FACT_COLUMNS
import com.newhost.qwertylauncher.core.HomeWidget
import com.newhost.qwertylauncher.core.IconColors
import com.newhost.qwertylauncher.core.IconRenderer
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.Size
import com.newhost.qwertylauncher.core.TileSize
import com.newhost.qwertylauncher.core.WidgetContent
import com.newhost.qwertylauncher.core.auto
import com.newhost.qwertylauncher.core.fact
import com.newhost.qwertylauncher.core.find
import com.newhost.qwertylauncher.core.fr
import com.newhost.qwertylauncher.core.grid
import com.newhost.qwertylauncher.core.icon
import com.newhost.qwertylauncher.core.responsive
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import com.newhost.qwertylauncher.core.R as CoreR

/**
 * A large ASCII clock (the time drawn as text, then run through the installed
 * [IconRenderer]) with the date beside it, and the time in each world clock
 * put on the tile. Without a renderer it falls back to three-line
 * seven-segment digits.
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
internal class ClockWidget(
    private val context: Context,
    private val services: Services,
    private val settings: SettingsStore,
    private val clocks: WorldClocks,
) : HomeWidget {
    override val id = "clock"
    override val order = 0
    override val size = TileSize.WIDE

    /** Below this the time can't be read. */
    override val minColumns = 16
    override val minLines = 3

    override fun render(): Flow<WidgetContent> = combine(
        settings.observe(ClockSettings.clockStyle),
        settings.observe(ClockSettings.hourFormat),
        clocks.clocks,
    ) { style, hourFormat, list -> Options(style, hourFormat, list.filter { it.onTile }) }
        .flatMapLatest { options -> minutes().map { now -> content(now, options) } }

    private data class Options(val style: String, val hourFormat: String, val world: List<WorldClock>)

    override fun onClick(host: LauncherHost) {
        host.startActivitySafely(Intent(AlarmClock.ACTION_SHOW_ALARMS))
    }

    private fun content(now: ZonedDateTime, options: Options): WidgetContent {
        val is24h = is24h(context, options.hourFormat)
        val time = now.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "hh:mm"))
        val facts = facts(now, is24h, options.world)
        if (services.find<IconRenderer>() == null || options.style == "segments") {
            // Seven-segment digits, drawn as text.
            return WidgetContent(
                time,
                layout = grid {
                    columns(fr())
                    rows(auto(), fr())
                    add(Cell.Text(SevenSegment.render(time), wrap = false))
                    flow(facts, FACT_COLUMNS, row = 1, rows = 2, pack = true)
                },
            )
        }
        val fg = context.getColor(CoreR.color.tty_fg)
        val cyan = context.getColor(CoreR.color.tty_cyan)
        // A new minute rolls the digits over.
        val digits = Cell.Art("time:$time", Size(TIME_COLUMNS, TIME_LINES)) { TextDrawable(time, fg, cyan) }
        return WidgetContent(
            time,
            layout = responsive {
                // Wide: the time big on the left, the facts beside it.
                atLeast(columns = WIDE) {
                    columns(fr(TIME_SHARE, min = MIN_TIME_COLUMNS), fr(1f, min = FACT_COLUMNS))
                    rows(fr())
                    gap(columns = 2)
                    add(digits, alignX = Align.CENTER, alignY = Align.CENTER)
                    flow(facts, FACT_COLUMNS, column = 1, rows = WIDE_FACT_ROWS, maxItemLines = 2, pack = true)
                }
                // Narrow: the time across the top, the facts under it.
                otherwise {
                    columns(fr())
                    rows(auto(), fr())
                    add(digits, alignX = Align.CENTER, alignY = Align.CENTER)
                    flow(facts, FACT_COLUMNS, row = 1, rows = 2, pack = true)
                }
            },
        )
    }

    /** The date, the world clocks, the week, how far through the day it is, and am or pm on a 12-hour clock. */
    private fun facts(now: ZonedDateTime, is24h: Boolean, world: List<WorldClock>): List<Cell> {
        val dayFraction = now.toLocalTime().toSecondOfDay() / SECONDS_A_DAY
        val cities = world.map { clock ->
            val zone = ZoneId.of(clock.zone)
            val there = now.withZoneSameInstant(zone).toLocalTime()
            fact("clock:zone:${clock.zone}:${there.hour}:${there.minute}", "${clock.name} ${Zones.time(zone, now, is24h)}", Zones.face(there))
        }
        return listOfNotNull(
            fact(
                "clock:date",
                now.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())).lowercase(),
                icon {
                    fill(IconColors.WHITE); round(12f, 18f, 88f, 92f, 10f)
                    fill(IconColors.RED); round(12f, 18f, 88f, 42f, 10f); rect(12f, 32f, 88f, 42f)
                    fill(IconColors.DARK)
                    for (row in 0 until 2) for (col in 0 until 3) circle(30f + col * 20f, 58f + row * 18f, 5f)
                },
            ),
        ) + cities + listOfNotNull(
            fact(
                "clock:week",
                "week ${now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}",
                icon {
                    fill(IconColors.CYAN)
                    for (i in 0 until 7) rect(6f + i * 13f, 38f, 16f + i * 13f, 62f)
                },
            ),
            fact(
                "clock:progress:${(dayFraction * PIE_STEPS).toInt()}",
                "${(dayFraction * 100).toInt()}% of the day",
                icon {
                    fill(IconColors.DARK); circle(50f, 50f, 42f)
                    fill(IconColors.YELLOW); arc(8f, 8f, 92f, 92f, -90f, (dayFraction * 360).toFloat(), pie = true)
                },
            ),
            if (is24h) {
                null
            } else {
                val pm = now.hour >= NOON
                fact(
                    "clock:${if (pm) "pm" else "am"}",
                    if (pm) "pm" else "am",
                    icon {
                        if (pm) {
                            fill(IconColors.LIGHT_BLUE); circle(50f, 50f, 36f)
                            fill(0xFF000000.toInt()); circle(66f, 40f, 30f)
                        } else {
                            fill(IconColors.YELLOW); circle(50f, 50f, 30f)
                        }
                    },
                )
            },
        )
    }

    private companion object {
        // "12:34" in bold is about five and a half times as wide as tall; in cells, about 22 by 4.
        const val TIME_COLUMNS = 22
        const val TIME_LINES = 4
        const val TIME_SHARE = 3f
        const val MIN_TIME_COLUMNS = 12
        const val WIDE = 34
        const val WIDE_FACT_ROWS = 4
        const val SECONDS_A_DAY = 86_400.0
        const val PIE_STEPS = 48
        const val NOON = 12
    }
}

/** Bold text filled with a left-to-right gradient, sized to its own glyph bounds. */
private class TextDrawable(
    private val text: String,
    private val startColor: Int,
    private val endColor: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = TEXT_SIZE_PX
    }
    private val glyphBounds = Rect().also { paint.getTextBounds(text, 0, text.length, it) }

    override fun getIntrinsicWidth() = glyphBounds.width()
    override fun getIntrinsicHeight() = glyphBounds.height()

    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.shader = LinearGradient(
            b.left.toFloat(), 0f, b.right.toFloat(), 0f, startColor, endColor, Shader.TileMode.CLAMP,
        )
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width().toFloat() / glyphBounds.width(), b.height().toFloat() / glyphBounds.height())
        canvas.drawText(text, -glyphBounds.left.toFloat(), -glyphBounds.top.toFloat(), paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val TEXT_SIZE_PX = 200f
    }
}

/** Three-line seven-segment digits: the fallback when no renderer is installed. */
internal object SevenSegment {
    private val glyphs = mapOf(
        '0' to listOf(" _ ", "| |", "|_|"),
        '1' to listOf("   ", "  |", "  |"),
        '2' to listOf(" _ ", " _|", "|_ "),
        '3' to listOf(" _ ", " _|", " _|"),
        '4' to listOf("   ", "|_|", "  |"),
        '5' to listOf(" _ ", "|_ ", " _|"),
        '6' to listOf(" _ ", "|_ ", "|_|"),
        '7' to listOf(" _ ", "  |", "  |"),
        '8' to listOf(" _ ", "|_|", "|_|"),
        '9' to listOf(" _ ", "|_|", " _|"),
        ':' to listOf(" ", ".", "."),
    )

    fun render(text: String): String = (0 until 3).joinToString("\n") { line ->
        text.map { glyphs[it]?.get(line) ?: " " }.joinToString("")
    }
}
