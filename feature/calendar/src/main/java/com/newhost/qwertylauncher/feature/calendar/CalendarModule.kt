package com.newhost.qwertylauncher.feature.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import com.newhost.qwertylauncher.core.Cell
import com.newhost.qwertylauncher.core.ChoiceSetting
import com.newhost.qwertylauncher.core.ChoiceSetting.Choice
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.DebugLog
import com.newhost.qwertylauncher.core.Extra
import com.newhost.qwertylauncher.core.HomeWidget
import com.newhost.qwertylauncher.core.IconColors
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.NumberSetting
import com.newhost.qwertylauncher.core.PaneRow
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.ShapeIcon
import com.newhost.qwertylauncher.core.Size
import com.newhost.qwertylauncher.core.TileSize
import com.newhost.qwertylauncher.core.WidgetContent
import com.newhost.qwertylauncher.core.fact
import com.newhost.qwertylauncher.core.icon
import com.newhost.qwertylauncher.core.tile
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import com.newhost.qwertylauncher.core.R as CoreR

/** A `cal`-style month with the next events, and an agenda pane. Reads the phone's calendars. */
class CalendarModule : LauncherModule {
    override val id = "calendar"

    override fun install(scope: ModuleScope) {
        scope.declareSettings(*CalendarSettings.all)
        val repository = CalendarRepository(scope.appContext, scope.settings, scope.log)
        val pane = AgendaPane(repository)
        scope.widget(CalendarWidget(scope.appContext, repository, scope.settings, pane))
        scope.command(AgendaCommand(pane))
    }
}

internal object CalendarSettings {
    val weekStart = ChoiceSetting(
        "week_start",
        "week starts on",
        listOf(Choice("monday"), Choice("sunday")),
        default = "monday",
    )
    val daysAhead = NumberSetting("days_ahead", "agenda: days ahead", default = 14, min = 1, max = 60, unit = "days")
    val tileEvents = NumberSetting("tile_events", "calendar events on the tile", default = 3, min = 0, max = 8)
    val view = ChoiceSetting(
        "view",
        "calendar tile shows",
        listOf(Choice("week", "this week"), Choice("month", "the month")),
        default = "week",
        summary = "The week takes three lines, the month up to eight.",
    )

    val all = arrayOf(view, weekStart, daysAhead, tileEvents)
}

internal data class CalendarEvent(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String?,
) {
    val day: LocalDate get() = Instant.ofEpochMilli(begin).atZone(zone(allDay)).toLocalDate()

    /** `09:00-10:30`, or `all day`. */
    fun timeRange(): String {
        if (allDay) return "all day"
        val format = DateTimeFormatter.ofPattern("HH:mm")
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(begin).atZone(zone).format(format) + "-" + Instant.ofEpochMilli(end).atZone(zone).format(format)
    }

    private companion object {
        // All-day events are stored in UTC, midnight to midnight.
        fun zone(allDay: Boolean): ZoneId = if (allDay) ZoneId.of("UTC") else ZoneId.systemDefault()
    }
}

internal sealed interface CalendarState {
    data object NoPermission : CalendarState
    data class Ready(val events: List<CalendarEvent>) : CalendarState
}

/** Upcoming events from all calendars, reloaded when calendars change, access is granted, or the day rolls over. */
internal class CalendarRepository(
    private val context: Context,
    private val settings: SettingsStore,
    private val log: DebugLog,
) {
    private val reload = MutableStateFlow(0)

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun requestReload() {
        reload.value++
    }

    fun state(): Flow<CalendarState> = combine(
        merge(reload, calendarChanges(), ticks()),
        settings.observe(CalendarSettings.daysAhead),
    ) { _, days -> load(days) }.flowOn(Dispatchers.IO)

    private fun load(days: Int): CalendarState {
        if (!hasPermission()) return CalendarState.NoPermission
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = start + days * DAY_MS
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .let { ContentUris.appendId(it, start); ContentUris.appendId(it, end); it }
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        return try {
            val events = context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")
                ?.use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            add(
                                CalendarEvent(
                                    eventId = c.getLong(0),
                                    title = c.getString(1)?.ifBlank { null } ?: "(no title)",
                                    begin = c.getLong(2),
                                    end = c.getLong(3),
                                    allDay = c.getInt(4) == 1,
                                    location = c.getString(5)?.ifBlank { null },
                                ),
                            )
                        }
                    }
                }.orEmpty()
            CalendarState.Ready(events)
        } catch (e: SecurityException) {
            log.e("calendar read refused", e)
            CalendarState.NoPermission
        }
    }

    private fun calendarChanges(): Flow<Int> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(0)
            }
        }
        if (hasPermission()) {
            context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        }
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    /** "Today" and "in 2h" move on even when nothing changes. */
    private fun ticks(): Flow<Int> = flow {
        while (true) {
            delay(TICK_MS)
            emit(0)
        }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val TICK_MS = 15 * 60 * 1000L
    }
}

/**
 * ```
 *    September 2026        today
 * Mo Tu We Th Fr Sa Su     09:00 standup
 *     1  2  3  4  5  6     13:30 dentist
 *  7  8  9 10 11 12 13     fri 26
 * ...
 * ```
 * Today in reverse video, days with events in amber.
 */
internal class CalendarWidget(
    private val context: Context,
    private val repository: CalendarRepository,
    private val settings: SettingsStore,
    private val pane: AgendaPane,
) : HomeWidget {
    override val id = "calendar"
    override val order = 45
    override val size = TileSize.WIDE

    override fun render(): Flow<WidgetContent> = combine(
        repository.state(),
        settings.observe(CalendarSettings.weekStart),
        settings.observe(CalendarSettings.tileEvents),
        settings.observe(CalendarSettings.view),
    ) { state, weekStart, maxEvents, view -> content(state, weekStart, maxEvents, weekOnly = view == "week") }

    /**
     * A calendar page drawn as art with today's number beside the date, the
     * next events (each with a dot, today's in cyan), and on a big tile the
     * month or this week as a grid across the bottom.
     */
    private fun content(state: CalendarState, weekStart: String, maxEvents: Int, weekOnly: Boolean): WidgetContent {
        val today = LocalDate.now()
        val picture = Cell.Art("calendar:$today", Size(PICTURE_COLUMNS, PICTURE_LINES)) { page(today.dayOfMonth) }
        val headline = today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())).lowercase()
        return when (state) {
            CalendarState.NoPermission -> {
                val grid = month(emptySet(), weekStart, weekOnly)
                val hint = SpannableStringBuilder().apply {
                    append("Tap to allow calendar access.", ForegroundColorSpan(color(CoreR.color.tty_dim)), SPAN)
                }
                WidgetContent(headline, layout = tile(picture, headline, listOf(Cell.Text(hint)), extras = gridExtra(grid, weekOnly)))
            }
            is CalendarState.Ready -> {
                val grid = month(state.events.map { it.day }.toSet(), weekStart, weekOnly)
                val facts = events(state.events, today)
                WidgetContent(
                    headline,
                    layout = tile(
                        picture,
                        headline,
                        facts,
                        factRows = maxEvents.coerceAtLeast(1),
                        factColumns = EVENT_COLUMNS,
                        pack = false,
                        extras = gridExtra(grid, weekOnly),
                    ),
                )
            }
        }
    }

    /** The coming events, the day named when it isn't today. */
    private fun events(events: List<CalendarEvent>, today: LocalDate): List<Cell> {
        val now = System.currentTimeMillis()
        val coming = events.filter { it.end >= now }
        if (coming.isEmpty()) return listOf(Cell.Text("no upcoming events", wrap = false))
        return coming.map { event ->
            val day = if (event.day == today) "" else dayLabel(event.day, today) + " "
            val time = if (event.allDay) "all day" else event.timeRange().substringBefore('-')
            val isToday = event.day == today
            fact("calendar:dot:$isToday", "$day$time ${event.title}", if (isToday) TODAY_DOT else LATER_DOT)
        }
    }

    private fun gridExtra(grid: CharSequence, weekOnly: Boolean) =
        listOf(Extra(Cell.Text(grid, wrap = false), if (weekOnly) WEEK_LINES else MONTH_LINES))

    override fun onClick(host: LauncherHost) {
        if (repository.hasPermission()) {
            host.showPane(pane)
        } else {
            host.requestPermission(Manifest.permission.READ_CALENDAR) { granted -> if (granted) repository.requestReload() }
        }
    }

    /** The grid, `cal` style: the whole month, or just this week ([weekOnly]). */
    private fun month(eventDays: Set<LocalDate>, weekStart: String, weekOnly: Boolean): SpannableStringBuilder {
        val today = LocalDate.now()
        val first = today.withDayOfMonth(1)
        val startDay = if (weekStart == "sunday") DayOfWeek.SUNDAY else DayOfWeek.MONDAY
        val offset = (first.dayOfWeek.value - startDay.value + 7) % 7
        val header = first.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + first.year
        val names = (0 until 7).joinToString(" ") {
            startDay.plus(it.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2)
        }
        if (weekOnly) return week(today, startDay, header, names, eventDays)
        return SpannableStringBuilder().apply {
            append(header.padStart((GRID_WIDTH + header.length) / 2).padEnd(GRID_WIDTH), ForegroundColorSpan(color(CoreR.color.tty_accent)), SPAN)
            append('\n')
            append(names, ForegroundColorSpan(color(CoreR.color.tty_dim)), SPAN)
            var column = offset
            append('\n')
            append("   ".repeat(offset))
            for (day in 1..first.lengthOfMonth()) {
                val date = first.withDayOfMonth(day)
                val start = length
                append("%2d".format(day))
                when {
                    date == today -> {
                        setSpan(BackgroundColorSpan(color(CoreR.color.tty_fg)), start, length, SPAN)
                        setSpan(ForegroundColorSpan(color(CoreR.color.tty_bg)), start, length, SPAN)
                    }
                    date in eventDays -> setSpan(ForegroundColorSpan(color(CoreR.color.tty_accent)), start, length, SPAN)
                }
                column++
                if (column == 7 && day < first.lengthOfMonth()) {
                    append('\n')
                    column = 0
                } else if (day < first.lengthOfMonth()) {
                    append(' ')
                }
            }
        }
    }

    /** Three lines: the month, the day names, and this week's days (which may span two months). */
    private fun week(
        today: LocalDate,
        startDay: DayOfWeek,
        header: String,
        names: String,
        eventDays: Set<LocalDate>,
    ) = SpannableStringBuilder().apply {
        append(header.padStart((GRID_WIDTH + header.length) / 2).padEnd(GRID_WIDTH), ForegroundColorSpan(color(CoreR.color.tty_accent)), SPAN)
        append('\n')
        append(names, ForegroundColorSpan(color(CoreR.color.tty_dim)), SPAN)
        append('\n')
        val weekStart = today.minusDays(((today.dayOfWeek.value - startDay.value + 7) % 7).toLong())
        for (i in 0 until 7) {
            val date = weekStart.plusDays(i.toLong())
            if (i > 0) append(' ')
            val start = length
            append("%2d".format(date.dayOfMonth))
            when {
                date == today -> {
                    setSpan(BackgroundColorSpan(color(CoreR.color.tty_fg)), start, length, SPAN)
                    setSpan(ForegroundColorSpan(color(CoreR.color.tty_bg)), start, length, SPAN)
                }
                date in eventDays -> setSpan(ForegroundColorSpan(color(CoreR.color.tty_accent)), start, length, SPAN)
            }
        }
    }

    private fun color(id: Int) = context.getColor(id)

    private companion object {
        const val GRID_WIDTH = 20
        const val PICTURE_COLUMNS = 6
        const val PICTURE_LINES = 3

        /** Events sit in columns at least this wide. */
        const val EVENT_COLUMNS = 24
        const val WEEK_LINES = 3
        const val MONTH_LINES = 8

        /** A page off a desk calendar: a red top, and [day] big on the white. */
        fun page(day: Int) = ShapeIcon {
            fill(IconColors.WHITE); round(10f, 12f, 90f, 94f, 10f)
            fill(IconColors.RED); round(10f, 12f, 90f, 36f, 10f); rect(10f, 26f, 90f, 36f)
            fill(IconColors.GREY); round(26f, 4f, 34f, 20f, 3f); round(66f, 4f, 74f, 20f, 3f)
            fill(IconColors.DARK); text(day.toString(), 50f, 66f, 44f)
        }

        val TODAY_DOT = icon { fill(IconColors.CYAN); circle(50f, 50f, 30f) }
        val LATER_DOT = icon { fill(IconColors.BLUE); circle(50f, 50f, 22f) }
        const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    }
}

internal fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "today"
    today.plusDays(1) -> "tomorrow"
    else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())).lowercase()
}

/** Upcoming events by day; Enter opens one in the calendar app. */
internal class AgendaPane(private val repository: CalendarRepository) : ListPane {
    override val title = "agenda"
    override val keysHelp = "⏎ open  q back"

    override fun rows(): Flow<List<PaneRow>> = repository.state().map { state ->
        when (state) {
            CalendarState.NoPermission -> listOf(
                PaneRow.Header("calendar"),
                PaneRow.Item(ACCESS, "Allow calendar access", status = "needed to show your events\n⏎ allow"),
            )
            is CalendarState.Ready -> {
                val today = LocalDate.now()
                if (state.events.isEmpty()) {
                    listOf(PaneRow.Header("agenda"), PaneRow.Item(EMPTY, "No upcoming events."))
                } else {
                    state.events.groupBy { it.day }.flatMap { (day, events) ->
                        listOf(PaneRow.Header(dayLabel(day, today))) + events.map { event ->
                            PaneRow.Item(
                                id = "${event.eventId}:${event.begin}",
                                text = "${event.timeRange().padEnd(TIME_WIDTH)} ${event.title}",
                                detail = event.location,
                                status = listOfNotNull(event.title, event.timeRange(), event.location).joinToString("\n"),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
        when (item.id) {
            ACCESS -> host.requestPermission(Manifest.permission.READ_CALENDAR) { granted -> if (granted) repository.requestReload() }
            EMPTY -> Unit
            else -> {
                val eventId = item.id.substringBefore(':').toLongOrNull() ?: return false
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                return host.startActivitySafely(Intent(Intent.ACTION_VIEW, uri))
            }
        }
        return false
    }

    private companion object {
        const val ACCESS = "calendar:access"
        const val EMPTY = "calendar:empty"
        const val TIME_WIDTH = 11
    }
}

/** `agenda` (or `cal`): opens the agenda. */
internal class AgendaCommand(private val pane: AgendaPane) : Command {
    override val name = "agenda"
    override val aliases = listOf("cal", "calendar")
    override val usage = "agenda"
    override val summary = "upcoming calendar events"

    override suspend fun suggest(args: String) = listOf(
        SearchResult(
            key = "agenda",
            tag = "cal",
            title = "agenda",
            detail = summary,
            action = { host ->
                host.clearQuery()
                host.showPane(pane)
            },
        ),
    )
}
