package com.newhost.qwertylauncher.feature.sensors

import android.Manifest
import android.hardware.SensorManager
import com.newhost.qwertylauncher.core.Align
import com.newhost.qwertylauncher.core.Cell
import com.newhost.qwertylauncher.core.ChoiceSetting.Choice
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.HomeWidget
import com.newhost.qwertylauncher.core.IconColors
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.MultiChoiceSetting
import com.newhost.qwertylauncher.core.NumberSetting
import com.newhost.qwertylauncher.core.PaneRow
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.Size
import com.newhost.qwertylauncher.core.TileSize
import com.newhost.qwertylauncher.core.WidgetContent
import com.newhost.qwertylauncher.core.auto
import com.newhost.qwertylauncher.core.fact
import com.newhost.qwertylauncher.core.fr
import com.newhost.qwertylauncher.core.grid
import com.newhost.qwertylauncher.core.icon
import com.newhost.qwertylauncher.core.meter
import com.newhost.qwertylauncher.core.tile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The phone's sensors as a toolbox: a compass (its rose drawn as ASCII
 * art), a spirit level, the barometer (air pressure, whether it's rising,
 * about how high up you are), steps today, and the light. One tile, with
 * what you tick in config; `sensors` (or `compass`, `level`) for all of it.
 * Only what this phone has is offered; sensors listen only while shown.
 */
class SensorsModule : LauncherModule {
    override val id = "sensors"
    override val title = "sensors"

    override fun install(scope: ModuleScope) {
        val readings = Readings(scope.appContext)
        val parts = Part.entries.filter { it.available(readings) }
        if (parts.isEmpty()) return
        val settings = SensorSettings(parts)
        scope.declareSettings(*settings.all)
        val live = Live(readings, StepDay(scope.appContext), PressureLog(scope.appContext))
        val pane = SensorsPane(live, parts, settings, scope.settings)
        scope.widget(SensorsWidget(live, settings, scope.settings, pane))
        scope.command(SensorsCommand(pane))
    }
}

/** What the toolbox can show, and whether this phone has the sensor for it. */
internal enum class Part(val id: String, val label: String, val available: (Readings) -> Boolean) {
    COMPASS("compass", "compass", { it.hasCompass }),
    LEVEL("level", "spirit level", { it.hasLevel }),
    PRESSURE("pressure", "air pressure", { it.hasPressure }),
    ALTITUDE("altitude", "height above sea level", { it.hasPressure }),
    STEPS("steps", "steps today", { it.hasSteps }),
    LIGHT("light", "light", { it.hasLight }),
}

internal class SensorSettings(parts: List<Part>) {
    val shown = MultiChoiceSetting(
        "on_tile",
        "sensors on the tile",
        parts.map { Choice(it.id, it.label) },
        default = parts.map { it.id }.filter { it != Part.ALTITUDE.id && it != Part.LIGHT.id },
        summary = "The first of the compass and the level is the tile's picture; the rest are lines beside it.",
    )
    val stepGoal = NumberSetting("step_goal", "daily step goal", default = 8000, min = 1000, max = 30000, step = 1000, unit = "steps")

    val all = arrayOf(shown, stepGoal)
}

/** One moment's readings; null for what isn't being read. */
internal data class Snapshot(
    val heading: Float? = null,
    val tilt: Tilt? = null,
    val hpa: Float? = null,
    val steps: Int? = null,
    val stepsAllowed: Boolean = true,
    val lux: Float? = null,
)

/** The readings of [parts], a few times a second, while collected. */
internal class Live(private val readings: Readings, private val stepDay: StepDay, val pressureLog: PressureLog) {
    /** Bumped when step counting is allowed, to start reading steps. */
    val permission = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun snapshots(parts: Set<Part>): Flow<Snapshot> = permission.flatMapLatest {
        channelFlow {
            val state = MutableStateFlow(Snapshot(stepsAllowed = readings.stepsAllowed()))
            if (Part.COMPASS in parts) launch { readings.heading().collect { h -> state.update { it.copy(heading = h) } } }
            if (Part.LEVEL in parts) launch { readings.tilt().collect { t -> state.update { it.copy(tilt = t) } } }
            if (Part.PRESSURE in parts || Part.ALTITUDE in parts) {
                launch { readings.pressure(pressureLog).collect { p -> state.update { it.copy(hpa = p) } } }
            }
            if (Part.STEPS in parts) launch { readings.steps(stepDay).collect { s -> state.update { it.copy(steps = s) } } }
            if (Part.LIGHT in parts) launch { readings.light().collect { l -> state.update { it.copy(lux = l) } } }
            var last: Snapshot? = null
            while (true) {
                val now = state.value
                if (now != last) send(now)
                last = now
                delay(FRAME_MS)
            }
        }
    }

    private companion object {
        const val FRAME_MS = 250L
    }
}

/** The readings in words. */
internal object Words {
    private val POINTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun heading(degrees: Float): String = "${POINTS[((degrees + 22.5f) / 45f).toInt() % 8]} ${degrees.roundToInt() % 360}°"

    fun tilt(t: Tilt): String {
        if (abs(t.pitch) < LevelDrawable.LEVEL && abs(t.roll) < LevelDrawable.LEVEL) return "level"
        val parts = listOfNotNull(
            if (abs(t.roll) >= LevelDrawable.LEVEL) "${abs(t.roll).roundToInt()}° ${if (t.roll > 0) "right" else "left"}" else null,
            if (abs(t.pitch) >= LevelDrawable.LEVEL) "${abs(t.pitch).roundToInt()}° ${if (t.pitch > 0) "up" else "down"}" else null,
        )
        return "tipped " + parts.joinToString(" ")
    }

    fun pressure(hpa: Float, change: Float?): String = "%.0f hPa".format(hpa) + when {
        change == null -> ""
        change > STEADY -> " rising"
        change < -STEADY -> " falling"
        else -> " steady"
    }

    fun altitude(hpa: Float) = "about ${SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hpa).roundToInt()} m up"

    fun light(lux: Float): String = "${lux.roundToInt()} lx " + when {
        lux < 10 -> "dark"
        lux < 200 -> "dim"
        lux < 10_000 -> "bright"
        else -> "sunlight"
    }

    private const val STEADY = 1f
}

/** Small icons for the tile's lines. */
internal object Icons {
    val level = icon {
        stroke(IconColors.GREY, 8f); circle(50f, 50f, 40f)
        fill(IconColors.GREEN); circle(50f, 50f, 16f)
    }
    val gauge = icon {
        stroke(IconColors.GREY, 8f); arc(10f, 10f, 90f, 90f, 150f, 240f)
        stroke(IconColors.CYAN, 9f); line(50f, 50f, 72f, 30f)
        fill(IconColors.WHITE); circle(50f, 50f, 8f)
    }
    val mountain = icon {
        fill(IconColors.GREY); polygon(4f, 90f, 40f, 24f, 60f, 56f, 72f, 40f, 96f, 90f)
        fill(IconColors.WHITE); polygon(40f, 24f, 50f, 42f, 31f, 42f)
    }
    val feet = icon {
        fill(IconColors.ORANGE)
        round(18f, 10f, 42f, 56f, 12f); circle(30f, 66f, 10f)
        round(58f, 40f, 82f, 86f, 12f); circle(70f, 96f, 9f)
    }
    val bulb = icon {
        fill(IconColors.YELLOW); circle(50f, 40f, 30f)
        fill(IconColors.GREY); rect(38f, 70f, 62f, 90f)
    }
}

internal class SensorsWidget(
    private val live: Live,
    private val sensorSettings: SensorSettings,
    private val settings: SettingsStore,
    private val pane: SensorsPane,
) : HomeWidget {
    override val id = "sensors"
    override val order = 60
    override val size = TileSize.NARROW
    override val minColumns = 18
    override val minLines = 4

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun render(): Flow<WidgetContent> = settings.observe(sensorSettings.shown).flatMapLatest { ids ->
        val parts = Part.entries.filter { it.id in ids }.toSet()
        combine(live.snapshots(parts), settings.observe(sensorSettings.stepGoal)) { snap, goal -> content(parts, snap, goal) }
    }

    override fun onClick(host: LauncherHost) = host.showPane(pane)

    private fun content(parts: Set<Part>, s: Snapshot, goal: Int): WidgetContent {
        if (parts.isEmpty()) return WidgetContent("")
        val heading = s.heading?.takeIf { Part.COMPASS in parts }
        val tilt = s.tilt?.takeIf { Part.LEVEL in parts }
        // The picture: the compass, else the level, else a gauge.
        val picture = when {
            heading != null -> Cell.Art("compass", Size(PICTURE_COLUMNS, PICTURE_LINES), frame = bucket(heading)) { CompassDrawable(heading) }
            tilt != null -> Cell.Art("level", Size(PICTURE_COLUMNS, PICTURE_LINES), frame = bucket(tilt)) { LevelDrawable(tilt) }
            else -> Cell.Art("gauge", Size(PICTURE_COLUMNS, PICTURE_LINES)) { Icons.gauge() }
        }
        val headline = when {
            heading != null -> Words.heading(heading)
            tilt != null -> Words.tilt(tilt)
            else -> "sensors"
        }
        val facts = listOfNotNull(
            tilt?.takeIf { heading != null }?.let { fact("sensors:level", Words.tilt(it), Icons.level) },
            s.hpa?.takeIf { Part.PRESSURE in parts }?.let { fact("sensors:pressure", Words.pressure(it, live.pressureLog.change(it)), Icons.gauge) },
            s.hpa?.takeIf { Part.ALTITUDE in parts }?.let { fact("sensors:altitude", Words.altitude(it), Icons.mountain) },
            when {
                Part.STEPS !in parts -> null
                !s.stepsAllowed -> fact("sensors:steps", "tap to count steps", Icons.feet)
                s.steps != null -> meter("sensors:steps", "${s.steps} steps", s.steps / goal.toFloat(), Icons.feet, listOf(IconColors.RED, IconColors.YELLOW, IconColors.GREEN))
                else -> null
            },
            s.lux?.takeIf { Part.LIGHT in parts }?.let { fact("sensors:light", Words.light(it), Icons.bulb) },
        )
        return WidgetContent(headline, layout = tile(picture, headline, facts))
    }

    private companion object {
        const val PICTURE_COLUMNS = 10
        const val PICTURE_LINES = 5
    }
}

/** Frames of a moving picture: the heading in 4-degree steps; the tilt in half degrees. */
internal fun bucket(heading: Float) = (heading / 4f).roundToInt()

internal fun bucket(tilt: Tilt) = (tilt.pitch * 2).roundToInt() * 1000 + (tilt.roll * 2).roundToInt()

/**
 * `sensors` as a pane: a big compass and level, the barometer with its
 * last hours as a chart, steps against the goal, the light.
 */
internal class SensorsPane(
    private val live: Live,
    private val parts: List<Part>,
    private val sensorSettings: SensorSettings,
    private val settings: SettingsStore,
) : ListPane {
    override val title = "sensors"
    override val keysHelp = "q back"

    override fun rows(): Flow<List<PaneRow>> =
        combine(live.snapshots(parts.toSet()), settings.observe(sensorSettings.stepGoal)) { s, goal -> rows(s, goal) }

    private fun rows(s: Snapshot, goal: Int): List<PaneRow> = buildList {
        s.heading?.let { h ->
            add(PaneRow.Header("compass"))
            add(PaneRow.Block(big(Cell.Art("compass-big", Size(BIG_COLUMNS, BIG_LINES), frame = bucket(h)) { CompassDrawable(h) })))
            add(PaneRow.Item("heading", "heading ${Words.heading(h)}", status = "Where the top of the phone points, from magnetic north."))
        }
        s.tilt?.let { t ->
            add(PaneRow.Header("level"))
            add(PaneRow.Block(big(Cell.Art("level-big", Size(BIG_COLUMNS, BIG_LINES), frame = bucket(t)) { LevelDrawable(t) })))
            add(
                PaneRow.Item(
                    "tilt",
                    Words.tilt(t),
                    detail = "%.1f° across %.1f° along".format(t.roll, t.pitch),
                    status = "Lay the phone on a surface: the bubble goes green when it's level.",
                ),
            )
        }
        s.hpa?.let { p ->
            add(PaneRow.Header("air pressure"))
            val change = live.pressureLog.change(p)
            add(
                PaneRow.Item(
                    "pressure",
                    Words.pressure(p, change),
                    detail = change?.let { "%+.1f hPa in 3 hours".format(it) },
                    status = "Falling pressure often means worse weather is coming; rising, better.",
                ),
            )
            add(PaneRow.Item("altitude", Words.altitude(p), status = "Worked out from the air pressure, so it drifts with the weather."))
            val log = live.pressureLog.all().map { it.second.toDouble() }
            if (log.size >= MIN_CHART) {
                add(
                    PaneRow.Block(
                        grid {
                            chart(
                                log,
                                lines = CHART_LINES,
                                colors = listOf(IconColors.BLUE, IconColors.CYAN),
                                labels = "%.0f".format(log.max()) to "%.0f".format(log.min()),
                            )
                        },
                    ),
                )
            }
        }
        if (Part.STEPS in parts) {
            add(PaneRow.Header("steps"))
            if (!s.stepsAllowed) {
                add(PaneRow.Item(ALLOW_STEPS, "count steps", status = "⏎ allow the step counter"))
            } else {
                val steps = s.steps ?: 0
                add(PaneRow.Item("steps", "$steps of $goal steps today", status = "Counted by the phone's step counter since midnight."))
                add(
                    PaneRow.Block(
                        grid { add(meter("steps", "${(steps * 100 / goal.coerceAtLeast(1))}% of the goal", steps / goal.toFloat(), Icons.feet)) },
                    ),
                )
            }
        }
        s.lux?.let { l ->
            add(PaneRow.Header("light"))
            add(PaneRow.Item("light", Words.light(l), status = "From the light sensor by the screen."))
        }
    }

    /** A picture in the middle, no wider than [BIG_COLUMNS]: art keeps its shape, so a wide one would be as tall. */
    private fun big(picture: Cell.Art) = grid {
        columns(fr(), auto(max = BIG_COLUMNS), fr())
        rows(auto())
        add(picture, column = 1, alignX = Align.CENTER)
    }

    override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
        if (item.id == ALLOW_STEPS) {
            host.requestPermission(Manifest.permission.ACTIVITY_RECOGNITION) { granted -> if (granted) live.permission.value++ }
        }
        return false
    }

    private companion object {
        const val ALLOW_STEPS = "allow_steps"
        const val BIG_COLUMNS = 16
        const val BIG_LINES = 8
        const val CHART_LINES = 4
        const val MIN_CHART = 3
    }
}

/** `sensors`, `compass` or `level`: the toolbox. */
internal class SensorsCommand(private val pane: SensorsPane) : Command {
    override val name = "sensors"
    override val aliases = listOf("compass", "level")
    override val usage = "sensors"
    override val summary = "compass, spirit level, barometer, steps and light"

    override suspend fun suggest(args: String): List<SearchResult> = listOf(
        SearchResult(
            key = "sensors",
            tag = "sns",
            title = "sensors",
            detail = "compass level barometer steps",
            score = Score.EXACT,
            action = { host ->
                host.clearQuery()
                host.showPane(pane)
            },
        ),
    )
}
