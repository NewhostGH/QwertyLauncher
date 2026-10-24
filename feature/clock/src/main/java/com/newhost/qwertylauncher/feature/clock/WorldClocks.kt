package com.newhost.qwertylauncher.feature.clock

import android.content.Context
import android.text.format.DateFormat
import android.view.KeyEvent
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.Fuzzy
import com.newhost.qwertylauncher.core.IconColors
import com.newhost.qwertylauncher.core.IconRenderer
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.MenuItem
import com.newhost.qwertylauncher.core.PaneRow
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.find
import com.newhost.qwertylauncher.core.icon
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A place's time zone, and whether its time shows on the clock tile. */
internal data class WorldClock(val zone: String, val onTile: Boolean) {
    val name: String get() = Zones.city(zone)
}

/** The added clocks, in the order they were added, saved in private preferences. Main thread only. */
internal class WorldClocks(context: Context) {
    private val prefs = context.getSharedPreferences("clock", Context.MODE_PRIVATE)
    private val _clocks = MutableStateFlow(read())
    val clocks: StateFlow<List<WorldClock>> = _clocks.asStateFlow()

    fun has(zone: String) = _clocks.value.any { it.zone == zone }

    fun add(zone: String) {
        if (!has(zone)) write(_clocks.value + WorldClock(zone, onTile = true))
    }

    fun remove(zone: String) = write(_clocks.value.filterNot { it.zone == zone })

    fun toggleTile(zone: String) = write(_clocks.value.map { if (it.zone == zone) it.copy(onTile = !it.onTile) else it })

    private fun read(): List<WorldClock> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            // A zone this phone no longer knows is dropped.
            o.getString("zone").takeIf { Zones.valid(it) }?.let { WorldClock(it, o.optBoolean("tile", true)) }
        }
    } catch (e: JSONException) {
        emptyList()
    }

    private fun write(clocks: List<WorldClock>) {
        _clocks.value = clocks
        val array = JSONArray()
        clocks.forEach { array.put(JSONObject().put("zone", it.zone).put("tile", it.onTile)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "zones"
    }
}

/** Time zones found by city (`tokyo`) or country (`japan`), and how to show their time. */
internal object Zones {
    class Match(val zone: String, val where: String, val score: Int)

    private val REGIONS = setOf("Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

    /** The cities' zones, without the old aliases like `US/Eastern`. */
    private val cities: List<String> by lazy {
        ZoneId.getAvailableZoneIds().filter { it.substringBefore('/') in REGIONS }.sorted()
    }

    /** Country code to its name, in the phone's language. */
    private val countries: Map<String, String> by lazy {
        Locale.getISOCountries().associateWith { code -> Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault()) }
    }

    fun valid(zone: String) = try {
        ZoneId.of(zone)
        true
    } catch (e: Exception) {
        false
    }

    /** `America/Argentina/Buenos_Aires` is `buenos aires`. */
    fun city(zone: String) = zone.substringAfterLast('/').replace('_', ' ').lowercase()

    fun search(query: String): List<Match> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val found = mutableMapOf<String, Match>()
        fun offer(match: Match) {
            if (match.score > (found[match.zone]?.score ?: 0)) found[match.zone] = match
        }
        if (q.equals("utc", ignoreCase = true) || q.equals("gmt", ignoreCase = true)) offer(Match("UTC", "universal time", Score.EXACT))
        cities.forEach { zone ->
            val score = Fuzzy.score(q, city(zone))
            if (score >= Score.WORD) offer(Match(zone, zone.substringBefore('/').lowercase(), score))
        }
        countries.forEach { (code, country) ->
            val score = Fuzzy.score(q, country)
            if (score < Score.WORD) return@forEach
            // A country's zones rank just under a city of the same name.
            android.icu.util.TimeZone.getAvailableIDs(code).filter { it in cities }.forEach { zone ->
                offer(Match(zone, country.lowercase(), score - 1))
            }
        }
        return found.values.sortedWith(compareByDescending<Match> { it.score }.thenBy { city(it.zone) }).take(MAX_MATCHES)
    }

    /** How far ahead of (or behind) here: `+7h`, `-5h30`, `same time`. */
    fun difference(zone: ZoneId, now: ZonedDateTime): String {
        val minutes = (zone.rules.getOffset(now.toInstant()).totalSeconds - now.offset.totalSeconds) / 60
        if (minutes == 0) return "same time"
        val sign = if (minutes > 0) "+" else "-"
        val h = abs(minutes) / 60
        val m = abs(minutes) % 60
        return if (m == 0) "$sign${h}h" else "$sign${h}h$m"
    }

    /** `UTC+9`, `UTC-3:30`. */
    fun utc(zone: ZoneId, now: ZonedDateTime): String {
        val offset = zone.rules.getOffset(now.toInstant()).id
        return if (offset == "Z") "UTC" else "UTC" + offset.replace(Regex(":00$"), "").replace(Regex("^([+-])0"), "$1")
    }

    /** The time there, and its weekday when that's not today here. */
    fun time(zone: ZoneId, now: ZonedDateTime, is24h: Boolean): String {
        val there = now.withZoneSameInstant(zone)
        val time = there.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mma", Locale.US)).lowercase()
        return if (there.toLocalDate() == now.toLocalDate()) time else "$time ${weekday(there)}"
    }

    fun weekday(time: ZonedDateTime) = time.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).lowercase()

    /**
     * The day there as 24 marks, one an hour from midnight: `.` at night,
     * `=` in the day (7 to 19), `|` for the hour it is now.
     */
    fun dayLine(zone: ZoneId, now: ZonedDateTime): String {
        val hour = now.withZoneSameInstant(zone).hour
        return (0 until HOURS).joinToString("") { h ->
            when {
                h == hour -> "|"
                h in DAY -> "="
                else -> "."
            }
        }
    }

    /** A small clock face showing [time]: yellow by day, blue by night. */
    fun face(time: LocalTime) = icon {
        fill(if (time.hour in DAY) IconColors.YELLOW else IconColors.LIGHT_BLUE)
        circle(50f, 50f, 46f)
        stroke(IconColors.DARK, 10f)
        turned((time.hour % 12 + time.minute / 60f) * 30f) { line(50f, 50f, 50f, 24f) }
        stroke(IconColors.DARK, 7f)
        turned(time.minute * 6f) { line(50f, 50f, 50f, 12f) }
    }

    private val DAY = 7 until 19
    private const val HOURS = 24
    private const val MAX_MATCHES = 8
}

/** Now, then again just after each minute starts. */
internal fun minutes(): Flow<ZonedDateTime> = flow {
    while (true) {
        val now = ZonedDateTime.now()
        emit(now)
        val next = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        delay(Duration.between(now, next).toMillis() + 50)
    }
}

/** The hour format setting, or the phone's own when it's "system". */
internal fun is24h(context: Context, hourFormat: String) = when (hourFormat) {
    "24h" -> true
    "12h" -> false
    else -> DateFormat.is24HourFormat(context)
}

/**
 * `clock` as a pane: here, then each added city with its time, how far
 * ahead or behind it is, and its day as a line of marks.
 * ```
 * ── here ─────────
 *   [o] berlin      14:05       same time  .......=======|=====.....
 * ── world ────────
 *   [o] tokyo       21:05       +7h        .......============|.....
 *   [o] new york    8:05am      -6h        .......=|===========.....
 * ```
 */
internal class WorldClockPane(
    private val context: Context,
    private val clocks: WorldClocks,
    private val settings: SettingsStore,
    private val services: Services,
) : ListPane {
    override val title = "clock"
    override val keysHelp = "⏎ on tile / off  d remove  q back"

    private val faces = ConcurrentHashMap<String, CharSequence>()

    override fun rows(): Flow<List<PaneRow>> =
        combine(clocks.clocks, minutes(), settings.observe(ClockSettings.hourFormat)) { list, now, hourFormat ->
            val is24h = is24h(context, hourFormat)
            buildList {
                add(PaneRow.Header("here"))
                add(row(HERE, now.zone, now, is24h, "Your phone's time zone.", "here"))
                add(PaneRow.Header("world"))
                list.forEach { clock ->
                    val zone = ZoneId.of(clock.zone)
                    val status = (if (clock.onTile) "on the clock tile\n⏎ take off" else "⏎ put on the clock tile") +
                        "  d remove"
                    add(row(clock.zone, zone, now, is24h, status, clock.name + if (clock.onTile) " *" else ""))
                }
                add(PaneRow.Item(ADD, "add a city", status = "⏎ types: clock <city or country>"))
            }
        }

    private suspend fun row(id: String, zone: ZoneId, now: ZonedDateTime, is24h: Boolean, status: String, name: String): PaneRow.Item {
        val there = now.withZoneSameInstant(zone)
        val full = there.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))
        return PaneRow.Item(
            id,
            "%-14s %-11s %-9s".format(name.take(NAME), Zones.time(zone, now, is24h), Zones.difference(zone, now)),
            detail = Zones.dayLine(zone, now),
            status = "${zone.id}: $full, ${Zones.utc(zone, now)}.\n$status",
            icon = face(there.toLocalTime()),
        )
    }

    /** The face for a pane row, one line tall; one a minute of the day at most. */
    private suspend fun face(time: LocalTime): CharSequence? {
        val renderer = services.find<IconRenderer>() ?: return null
        val key = "%02d%02d".format(time.hour, time.minute)
        faces[key]?.let { return it }
        if (faces.size > MAX_FACES) faces.clear()
        return renderer.render(Zones.face(time)(), ROW_ICON_COLS, ROW_ICON_ROWS, fine = true).also { faces[key] = it }
    }

    override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
        when (item.id) {
            HERE -> Unit
            ADD -> {
                host.setQuery("clock ")
                return true
            }
            else -> clocks.toggleTile(item.id)
        }
        return false
    }

    override fun onKey(item: PaneRow.Item?, event: KeyEvent, host: LauncherHost): Boolean {
        if (event.keyCode !in DELETE_KEYS) return false
        val zone = item?.id?.takeIf { clocks.has(it) } ?: return true
        clocks.remove(zone)
        return true
    }

    private companion object {
        const val HERE = "clock:here"
        const val ADD = "clock:add"
        const val NAME = 14
        const val MAX_FACES = 200
        const val ROW_ICON_COLS = 8
        const val ROW_ICON_ROWS = 4
        val DELETE_KEYS = setOf(KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL)
    }
}

/**
 * `clock` opens the world clocks; `clock <city or country>` shows the time
 * there and adds it; `clock rm <city>` removes one.
 */
internal class ClockCommand(
    private val context: Context,
    private val clocks: WorldClocks,
    private val settings: SettingsStore,
    private val pane: WorldClockPane,
) : Command {
    override val name = "clock"
    override val aliases = listOf("tz")
    override val usage = "clock [city|rm city]"
    override val summary = "world clocks: the time in a city or country, and adding it to the clock tile"

    override suspend fun suggest(args: String): List<SearchResult> {
        val now = ZonedDateTime.now()
        val is24h = is24h(context, settings[ClockSettings.hourFormat])
        val text = args.trim()
        if (text.isEmpty()) {
            val detail = clocks.clocks.value.joinToString("  ") { "${it.name} ${Zones.time(ZoneId.of(it.zone), now, is24h)}" }
            return listOf(
                SearchResult(
                    key = "clock",
                    tag = "clk",
                    title = "world clocks",
                    detail = detail.ifEmpty { "none yet: type a city" },
                    score = Score.EXACT,
                    action = { host -> open(host) },
                ),
            )
        }
        if (text.startsWith("rm ") || text == "rm") return remove(text.removePrefix("rm").trim(), now, is24h)
        return Zones.search(text).mapIndexed { i, match ->
            val zone = ZoneId.of(match.zone)
            val added = clocks.has(match.zone)
            SearchResult(
                key = "clock:${match.zone}",
                tag = "clk",
                title = "${Zones.city(match.zone)} ${Zones.time(zone, now, is24h)}",
                detail = "${match.where}  ${Zones.difference(zone, now)}" + if (added) "  added" else "  enter adds",
                score = Score.EXACT - i,
                action = { host ->
                    if (!added) {
                        clocks.add(match.zone)
                        host.console.println("Added ${Zones.city(match.zone)} to the world clocks.")
                    }
                    open(host)
                },
                menu = listOf(MenuItem("world clocks") { host -> open(host) }),
            )
        }
    }

    private fun remove(name: String, now: ZonedDateTime, is24h: Boolean): List<SearchResult> =
        clocks.clocks.value
            .filter { name.isEmpty() || Fuzzy.score(name, it.name) > 0 }
            .map { clock ->
                SearchResult(
                    key = "clock:rm:${clock.zone}",
                    tag = "clk",
                    title = "remove ${clock.name}",
                    detail = Zones.time(ZoneId.of(clock.zone), now, is24h),
                    action = { host ->
                        clocks.remove(clock.zone)
                        host.clearQuery()
                        host.console.println("Removed ${clock.name} from the world clocks.")
                    },
                )
            }

    private fun open(host: LauncherHost) {
        host.clearQuery()
        host.showPane(pane)
    }
}
