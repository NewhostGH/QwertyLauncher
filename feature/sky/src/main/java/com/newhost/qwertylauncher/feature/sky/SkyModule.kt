package com.newhost.qwertylauncher.feature.sky

import android.Manifest
import android.content.Context
import android.text.format.DateFormat
import com.newhost.qwertylauncher.core.Cell
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.HomeWidget
import com.newhost.qwertylauncher.core.IconColors
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.PaneRow
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.Size
import com.newhost.qwertylauncher.core.WidgetContent
import com.newhost.qwertylauncher.core.fact
import com.newhost.qwertylauncher.core.icon
import com.newhost.qwertylauncher.core.tile
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * The sun and the moon, worked out on the phone (no network): a tile with
 * the moon's phase drawn as art, sunrise and sunset, the day's length, the
 * golden hour and twilight, and `sky` for the week ahead and the next
 * phases. The sun needs roughly where you are: the phone's last known
 * location, which never leaves it.
 */
class SkyModule : LauncherModule {
    override val id = "sky"
    override val title = "sun and moon"

    override fun install(scope: ModuleScope) {
        val where = Where(scope.appContext)
        val sky = Sky(scope.appContext, where)
        val pane = SkyPane(sky, where, scope.appScope)
        scope.widget(SkyWidget(sky, where, pane, scope.appScope))
        scope.command(SkyCommand(sky, where, pane))
    }
}

/** The sky at a moment, in words and pictures, for the tile, the pane and the command. */
internal class Sky(private val context: Context, private val where: Where) {

    fun sun(date: LocalDate): SunDay? = where.spot.value?.let { Astro.sun(date, it.latitude, it.longitude) }

    /** The moon's picture: lit on the right while waxing, north of the equator; the other way south of it. */
    fun moonArt(phase: MoonPhase): Cell.Art {
        val south = (where.spot.value?.latitude ?: 0.0) < 0
        val step = (phase.age * MOON_STEPS).roundToInt() % MOON_STEPS
        return Cell.Art("moon:$step:$south", Size(MOON_COLUMNS, MOON_LINES)) { moon(phase, south)() }
    }

    /** `sun 07:02 to 18:52`, or what the sun does instead. */
    fun sunLine(day: SunDay): String = when {
        day.rise != null && day.set != null -> "sun ${time(day.rise)} to ${time(day.set)}"
        day.rise != null -> "sun up at ${time(day.rise)}"
        day.set != null -> "sun down at ${time(day.set)}"
        day.allDay -> "sun up all day"
        else -> "sun down all day"
    }

    fun moonLine(phase: MoonPhase) = "${phase.name} ${(phase.lit * 100).roundToInt()}%"

    /** The nearer of the next full and new moon: `full moon in 3 days`. */
    fun nextMoon(now: Instant): String {
        val full = Astro.next(FULL, now)
        val new = Astro.next(NEW, now)
        val (name, at) = if (full < new) "full moon" to full else "new moon" to new
        return "$name ${inDays(at)}"
    }

    /** The moon's next four phases, soonest first. */
    fun phases(now: Instant): List<Pair<String, Instant>> =
        listOf(NEW to "new moon", FIRST to "first quarter", FULL to "full moon", LAST to "last quarter")
            .map { (age, name) -> name to Astro.next(age, now) }
            .sortedBy { it.second }

    /** `11h50`. */
    fun length(minutes: Long) = "${minutes / 60}h%02d".format(minutes % 60)

    /** `+2m`, `-3m`: how much longer the day is than the one before. */
    fun change(today: SunDay, yesterday: SunDay): String {
        val minutes = today.lengthMinutes - yesterday.lengthMinutes
        return if (minutes >= 0) "+${minutes}m" else "${minutes}m"
    }

    /**
     * The day as 24 marks from midnight, one an hour: `=` with the sun up,
     * `-` in twilight, `.` at night, `|` for the hour it is now.
     */
    fun dayLine(day: SunDay, now: ZonedDateTime): String {
        val zone = now.zone
        return (0 until HOURS).joinToString("") { h ->
            val middle = day.date.atTime(h, HALF_HOUR).atZone(zone).toInstant()
            when {
                day.date == now.toLocalDate() && h == now.hour -> "|"
                between(middle, day.rise, day.set, day.allDay) -> "="
                between(middle, day.dawn, day.dusk, day.allDay) -> "-"
                else -> "."
            }
        }
    }

    private fun between(at: Instant, from: Instant?, to: Instant?, allDay: Boolean) = when {
        from != null && to != null -> at in from..to
        from != null -> at >= from
        to != null -> at <= to
        else -> allDay
    }

    fun time(at: Instant): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mma"
        return at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern, Locale.US)).lowercase()
    }

    fun day(at: Instant): String =
        at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())).lowercase()

    /** `today`, `tomorrow`, `in 3 days`. */
    fun inDays(at: Instant): String {
        val days = ChronoUnit.DAYS.between(LocalDate.now(), at.atZone(ZoneId.systemDefault()).toLocalDate())
        return when (days) {
            0L -> "today"
            1L -> "tomorrow"
            else -> "in $days days"
        }
    }

    fun weekday(date: LocalDate) = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).lowercase()

    companion object {
        const val NEW = 0.0
        const val FIRST = 0.25
        const val FULL = 0.5
        const val LAST = 0.75

        private const val MOON_STEPS = 32
        const val MOON_COLUMNS = 10
        const val MOON_LINES = 5
        private const val HOURS = 24
        private const val HALF_HOUR = 30

        private val LIT = 0xFFF4EFD0.toInt()

        /** A disc, half lit, then an ellipse over the middle: dark for a crescent, lit for a gibbous moon. */
        fun moon(phase: MoonPhase, south: Boolean) = icon {
            fill(IconColors.DARK)
            circle(50f, 50f, 44f)
            val litRight = phase.waxing != south
            fill(LIT)
            arc(6f, 6f, 94f, 94f, if (litRight) -90f else 90f, 180f, pie = true)
            val half = (44 * abs(cos(2 * Math.PI * phase.age))).toFloat()
            fill(if (phase.lit < 0.5) IconColors.DARK else LIT)
            arc(50f - half, 6f, 50f + half, 94f, 0f, 360f, pie = true)
        }

        /** Half a sun on the horizon, with an arrow up (rising) or down (setting). */
        fun horizon(rising: Boolean, color: Int = IconColors.YELLOW) = icon {
            fill(color)
            arc(22f, 42f, 78f, 98f, 180f, 180f, pie = true)
            rect(4f, 72f, 96f, 80f)
            if (rising) polygon(50f, 4f, 70f, 30f, 30f, 30f) else polygon(50f, 34f, 70f, 8f, 30f, 8f)
        }

        val SUN = icon {
            fill(IconColors.YELLOW)
            circle(50f, 50f, 24f)
            stroke(IconColors.YELLOW, 8f)
            for (i in 0 until 8) turned(i * 45f) { line(50f, 16f, 50f, 4f) }
        }

        val GOLDEN = horizon(rising = false, color = IconColors.ORANGE)

        val TWILIGHT = icon {
            fill(IconColors.VIOLET)
            arc(22f, 42f, 78f, 98f, 180f, 180f, pie = true)
            rect(4f, 72f, 96f, 80f)
            fill(IconColors.WHITE)
            circle(20f, 20f, 5f)
            circle(78f, 14f, 4f)
            circle(56f, 30f, 3f)
        }

        val PIN = icon {
            fill(IconColors.RED)
            circle(50f, 36f, 28f)
            polygon(25f, 48f, 75f, 48f, 50f, 96f)
            erase()
            circle(50f, 36f, 11f)
        }
    }
}

/** Now, then every minute. */
private fun minutes(): Flow<ZonedDateTime> = flow {
    while (true) {
        val now = ZonedDateTime.now()
        emit(now)
        val next = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        delay(Duration.between(now, next).toMillis() + 50)
    }
}

/** Asks for location if it isn't allowed yet, then looks. */
private fun allowLocation(where: Where, host: LauncherHost, scope: CoroutineScope, then: () -> Unit = {}) {
    val console = host.console
    host.requestPermission(Manifest.permission.ACCESS_COARSE_LOCATION) { granted ->
        if (granted) {
            scope.launch {
                where.refresh(force = true)
                then()
            }
        } else {
            console.println("sky: sun times need your approximate location")
        }
    }
}

internal class SkyWidget(
    private val sky: Sky,
    private val where: Where,
    private val pane: SkyPane,
    private val scope: CoroutineScope,
) : HomeWidget {
    override val id = "sky"
    override val title = "sun and moon"
    override val order = 25
    override val minColumns = 18
    override val minLines = 3

    override fun render(): Flow<WidgetContent> =
        combine(where.spot, minutes()) { _, now -> content(now) }.onStart { scope.launch { where.refresh() } }

    override fun onClick(host: LauncherHost) {
        if (where.spot.value == null && !where.allowed()) allowLocation(where, host, scope) else host.showPane(pane)
    }

    private fun content(now: ZonedDateTime): WidgetContent {
        val phase = Astro.moon(now.toInstant())
        val today = sky.sun(now.toLocalDate())
        val moonFacts = listOf(
            fact("sky:moon", sky.moonLine(phase), Sky.moon(phase, south = false)),
            fact("sky:next", sky.nextMoon(now.toInstant()), Sky.moon(MoonPhase(Sky.FULL), south = false)),
        )
        if (today == null) {
            val headline = sky.moonLine(phase)
            val facts = listOf(fact("sky:where", "tap for sun times", Sky.PIN)) + moonFacts.drop(1)
            return WidgetContent(headline, layout = tile(sky.moonArt(phase), headline, facts))
        }
        val yesterday = sky.sun(now.toLocalDate().minusDays(1))!!
        val headline = sky.sunLine(today)
        val facts = listOfNotNull(
            fact("sky:length", "${sky.length(today.lengthMinutes)} day ${sky.change(today, yesterday)}", Sky.SUN),
            golden(today, now.toInstant()),
            twilight(today, now.toInstant()),
        ) + moonFacts
        return WidgetContent(headline, layout = tile(sky.moonArt(phase), headline, facts))
    }

    /** The golden hour coming next: the morning's until it ends, then the evening's. */
    private fun golden(day: SunDay, now: Instant): Cell? {
        val ends = day.goldenEnds
        val starts = day.goldenStarts
        return when {
            ends != null && now < ends -> fact("sky:golden", "golden hour to ${sky.time(ends)}", Sky.GOLDEN)
            starts != null -> fact("sky:golden", "golden hour ${sky.time(starts)}", Sky.GOLDEN)
            else -> null
        }
    }

    /** Dawn before sunrise, dusk after. */
    private fun twilight(day: SunDay, now: Instant): Cell? {
        val dawn = day.dawn
        val dusk = day.dusk
        return when {
            dawn != null && day.rise != null && now < day.rise -> fact("sky:twilight", "dawn ${sky.time(dawn)}", Sky.TWILIGHT)
            dusk != null -> fact("sky:twilight", "dusk ${sky.time(dusk)}", Sky.TWILIGHT)
            else -> null
        }
    }
}

/**
 * `sky` as a pane: today's sun and moon, the week ahead, the moon's next
 * phases, and where it's worked out for.
 * ```
 *   [moon art]  sun 07:02 to 18:52
 * ── today
 *   sunrise 07:02  sunset 18:52      .......==========|=--.....
 *   dawn 06:30  dusk 19:24
 *   golden hour to 07:40  from 18:10
 *   11h50 of day  -3m on yesterday
 * ── next 7 days
 *   sun   07:04 to 18:49  11h45  -3m
 * ── moon
 *   waxing gibbous 78%
 *   full moon  sat 3 oct  in 6 days
 * ```
 */
internal class SkyPane(
    private val sky: Sky,
    private val where: Where,
    private val scope: CoroutineScope,
) : ListPane {
    override val title = "sky"
    override val keysHelp = "⏎ on where: locate  q back"

    override fun rows(): Flow<List<PaneRow>> =
        combine(where.spot, minutes()) { spot, now -> rows(spot, now) }.onStart { scope.launch { where.refresh() } }

    private fun rows(spot: Spot?, now: ZonedDateTime): List<PaneRow> = buildList {
        val phase = Astro.moon(now.toInstant())
        val today = sky.sun(now.toLocalDate())
        add(PaneRow.Block(tile(sky.moonArt(phase), today?.let { sky.sunLine(it) } ?: sky.moonLine(phase))))
        if (today != null) {
            val yesterday = sky.sun(now.toLocalDate().minusDays(1))!!
            add(PaneRow.Header("today"))
            add(
                PaneRow.Item(
                    "sun",
                    listOfNotNull(today.rise?.let { "sunrise ${sky.time(it)}" }, today.set?.let { "sunset ${sky.time(it)}" })
                        .joinToString("  ").ifEmpty { sky.sunLine(today) },
                    detail = sky.dayLine(today, now),
                    status = "The day hour by hour from midnight: = sun up, - twilight, . night, | now. " +
                        "Noon is at ${sky.time(today.noon)}.",
                ),
            )
            twoTimes("dawn", today.dawn, "dusk", today.dusk)?.let {
                add(PaneRow.Item("twilight", it, status = "Civil twilight: light enough to see by, with the sun just under the horizon."))
            }
            twoTimes("golden hour to", today.goldenEnds, "from", today.goldenStarts)?.let {
                add(PaneRow.Item("golden", it, status = "The golden hour: the sun under 6° high, soft warm light for photos."))
            }
            add(
                PaneRow.Item(
                    "length",
                    "${sky.length(today.lengthMinutes)} of day  ${sky.change(today, yesterday)} on yesterday",
                    status = "How long the sun is up today, and how much longer than yesterday.",
                ),
            )
            add(PaneRow.Header("next 7 days"))
            var before: SunDay = today
            for (i in 1..WEEK) {
                val date = now.toLocalDate().plusDays(i.toLong())
                val day = sky.sun(date)!!
                add(
                    PaneRow.Item(
                        "day:$date",
                        "%-5s %-20s %s".format(sky.weekday(date), sky.sunLine(day).removePrefix("sun "), sky.length(day.lengthMinutes)),
                        detail = sky.change(day, before),
                        status = "${sky.day(date.atStartOfDay(now.zone).toInstant())}: ${sky.sunLine(day)}, " +
                            "${sky.length(day.lengthMinutes)} of day.",
                    ),
                )
                before = day
            }
        }
        add(PaneRow.Header("moon"))
        add(
            PaneRow.Item(
                "moon",
                sky.moonLine(phase),
                detail = "%.1f days old".format(phase.age * Astro.SYNODIC_DAYS),
                status = "The moon is ${sky.moonLine(phase)} lit, ${"%.1f".format(phase.age * Astro.SYNODIC_DAYS)} days after new moon.",
            ),
        )
        sky.phases(now.toInstant()).forEach { (name, at) ->
            add(
                PaneRow.Item(
                    "phase:$name",
                    "%-14s %s".format(name, sky.day(at)),
                    detail = sky.inDays(at),
                    status = "The next $name: ${sky.day(at)} about ${sky.time(at)}, give or take a day.",
                ),
            )
        }
        add(PaneRow.Header("where"))
        add(
            PaneRow.Item(
                WHERE,
                if (spot == null) {
                    "location not known yet"
                } else {
                    "%.2f %.2f".format(spot.latitude, spot.longitude)
                },
                detail = spot?.let { "seen ${ago(it.at)}" },
                status = if (where.allowed()) {
                    "the phone's last known location (it stays on the phone)\n⏎ look again"
                } else {
                    "Enter allows your approximate location, for sunrise and sunset. It never leaves the phone."
                },
            ),
        )
    }

    private fun twoTimes(first: String, a: Instant?, second: String, b: Instant?): String? =
        listOfNotNull(a?.let { "$first ${sky.time(it)}" }, b?.let { "$second ${sky.time(it)}" }).joinToString("  ").ifEmpty { null }

    private fun ago(at: Long): String {
        val minutes = (System.currentTimeMillis() - at) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 24 * 60 -> "${minutes / 60}h ago"
            else -> "${minutes / (24 * 60)}d ago"
        }
    }

    override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
        if (item.id == WHERE) {
            if (where.allowed()) scope.launch { where.refresh(force = true) } else allowLocation(where, host, scope)
        }
        return false
    }

    private companion object {
        const val WHERE = "where"
        const val WEEK = 7
    }
}

/** `sky` (or `sun`, `moon`): today's sun and moon, and the pane. */
internal class SkyCommand(private val sky: Sky, private val where: Where, private val pane: SkyPane) : Command {
    override val name = "sky"
    override val aliases = listOf("sun", "moon")
    override val usage = "sky"
    override val summary = "sunrise and sunset, the moon's phase, and the week ahead"

    override suspend fun suggest(args: String): List<SearchResult> {
        val now = ZonedDateTime.now()
        val phase = Astro.moon(now.toInstant())
        val sun = sky.sun(now.toLocalDate())?.let { sky.sunLine(it) }
        return listOf(
            SearchResult(
                key = "sky",
                tag = "sky",
                title = sun ?: "sun and moon",
                detail = sky.moonLine(phase) + if (where.spot.value == null) "  enter for sun times" else "",
                score = Score.EXACT,
                action = { host ->
                    host.clearQuery()
                    host.showPane(pane)
                },
            ),
        )
    }
}
