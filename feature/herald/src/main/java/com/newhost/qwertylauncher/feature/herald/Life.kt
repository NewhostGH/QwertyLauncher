package com.newhost.qwertylauncher.feature.herald

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import com.newhost.qwertylauncher.core.Happenings
import com.newhost.qwertylauncher.core.NotificationHost
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.find
import com.newhost.qwertylauncher.core.now
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The parts of his day, by the clock. */
internal enum class Phase { MORNING, MIDDAY, AFTERNOON, EVENING, NIGHT }

internal enum class Season { SPRING, SUMMER, AUTUMN, WINTER }

/** Days that are special in the realm. */
internal enum class Holiday { NONE, HALLOWEEN, CHRISTMAS, NAME_DAY }

/**
 * Sir Alex's own life, kept across restarts: needs that rise slowly
 * (hunger, boredom, tiredness, 0 to 100) and show only in what he does, his
 * mood from how he's treated (wary after pokes, friendly when left alone),
 * the chores he's done today (the realm shows them), what he fears (the
 * tile he was dropped on), and when he last saw you.
 */
internal class Life(context: Context) {
    private val prefs = context.getSharedPreferences("herald_life", Context.MODE_PRIVATE)

    private val _changes = MutableStateFlow(0)

    /** Bumped when something the realm shows changes: a chore done. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    // --- Needs: each is a level and when it was set, rising by the hour since ---

    val hunger: Int get() = need(HUNGER, HUNGER_PER_HOUR)
    val boredom: Int get() = need(BOREDOM, BOREDOM_PER_HOUR)
    val tiredness: Int get() = need(TIRED, TIRED_PER_HOUR)

    fun ate() = setNeed(HUNGER, 0)
    fun played(amount: Int = 40) = setNeed(BOREDOM, (boredom - amount).coerceAtLeast(0))
    fun rested() = setNeed(TIRED, 0)

    private fun need(key: String, perHour: Float): Int {
        val level = prefs.getFloat(key, START)
        val since = prefs.getLong("$key.at", System.currentTimeMillis())
        val hours = (System.currentTimeMillis() - since) / HOUR_MS.toFloat()
        return (level + hours * perHour).toInt().coerceIn(0, MAX)
    }

    private fun setNeed(key: String, level: Int) {
        prefs.edit().putFloat(key, level.toFloat()).putLong("$key.at", System.currentTimeMillis()).apply()
    }

    // --- Mood ---

    /** Poked again: warier, for a few days. */
    fun poked() {
        prefs.edit().putFloat(WARY, wariness + WARY_PER_POKE).putLong("$WARY.at", System.currentTimeMillis())
            .putLong(LAST_POKE, System.currentTimeMillis()).apply()
    }

    /** How wary he is, 0 to 100, fading by the day. */
    val wariness: Float
        get() {
            val level = prefs.getFloat(WARY, 0f)
            val days = (System.currentTimeMillis() - prefs.getLong("$WARY.at", 0L)) / DAY_MS.toFloat()
            return (level - days * WARY_FADE_PER_DAY).coerceIn(0f, MAX.toFloat())
        }

    val wary: Boolean get() = wariness >= WARY_AT

    /** Friendly: not poked for a good while, and not wary. */
    val friendly: Boolean
        get() = !wary && System.currentTimeMillis() - prefs.getLong(LAST_POKE, 0L) > FRIENDLY_AFTER_MS

    // --- Chores: what he's done today ---

    fun choresToday(): Set<String> =
        if (prefs.getString(CHORE_DAY, null) == LocalDate.now().toString()) prefs.getStringSet(CHORES, emptySet()).orEmpty() else emptySet()

    fun did(chore: String) {
        prefs.edit().putString(CHORE_DAY, LocalDate.now().toString()).putStringSet(CHORES, choresToday() + chore).apply()
        _changes.value++
    }

    // --- Callbacks ---

    /** He was dropped onto [tile] (null: the ground) from high up; he'll be nervous there. */
    fun droppedOn(tile: String?) {
        prefs.edit().putString(FEARED, tile ?: GROUND).apply()
    }

    fun feared(tile: String?): Boolean = prefs.getString(FEARED, null) == (tile ?: GROUND)

    /** The tile he's nervous on, "(ground)" for the bottom of the screen; null if none. */
    val fearedTile: String? get() = prefs.getString(FEARED, null)

    /**
     * The home screen shows again: how long since it last did, in
     * milliseconds (0 the first time).
     */
    fun seen(): Long {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(SEEN, 0L)
        prefs.edit().putLong(SEEN, now).apply()
        return if (last == 0L) 0L else now - last
    }

    /** The day he first met the user: his name day. */
    fun met() {
        // The year too, so the day they met isn't already his name day.
        if (!prefs.contains(MET_ON)) {
            prefs.edit().putString(MET_ON, MonthDay.now().toString()).putString(MET_YEAR, LocalDate.now().year.toString()).apply()
        }
    }

    /** Whether he's already made a fuss of [what] today (a full battery, his name day): once a day each. */
    fun celebrated(what: String): Boolean = prefs.getString("$CELEBRATED$what", null) == LocalDate.now().toString()

    fun celebrate(what: String) {
        prefs.edit().putString("$CELEBRATED$what", LocalDate.now().toString()).apply()
    }

    // --- The calendar ---

    fun phase(time: LocalTime = LocalTime.now()): Phase = when (time.hour) {
        in 6..10 -> Phase.MORNING
        in 11..13 -> Phase.MIDDAY
        in 14..17 -> Phase.AFTERNOON
        in 18..21 -> Phase.EVENING
        else -> Phase.NIGHT
    }

    fun season(date: LocalDate = LocalDate.now()): Season = when (date.monthValue) {
        3, 4, 5 -> Season.SPRING
        6, 7, 8 -> Season.SUMMER
        9, 10, 11 -> Season.AUTUMN
        else -> Season.WINTER
    }

    fun holiday(date: LocalDate = LocalDate.now()): Holiday {
        val met = prefs.getString(MET_ON, null)?.let { runCatching { MonthDay.parse(it) }.getOrNull() }
        return when {
            met != null && MonthDay.from(date) == met && prefs.getString(MET_YEAR, null) != date.year.toString() -> Holiday.NAME_DAY
            date.monthValue == 10 && date.dayOfMonth >= 25 -> Holiday.HALLOWEEN
            date.monthValue == 12 && date.dayOfMonth in 20..26 -> Holiday.CHRISTMAS
            else -> Holiday.NONE
        }
    }

    private companion object {
        const val HUNGER = "hunger"
        const val BOREDOM = "boredom"
        const val TIRED = "tired"
        const val START = 30f
        const val MAX = 100
        const val HUNGER_PER_HOUR = 9f
        const val BOREDOM_PER_HOUR = 7f
        const val TIRED_PER_HOUR = 4f

        const val WARY = "wary"
        const val LAST_POKE = "last_poke"
        const val WARY_PER_POKE = 12f
        const val WARY_FADE_PER_DAY = 25f
        const val WARY_AT = 40f
        const val FRIENDLY_AFTER_MS = 2 * 86_400_000L

        const val CHORE_DAY = "chore_day"
        const val CHORES = "chores"
        const val FEARED = "feared"
        const val GROUND = "(ground)"
        const val SEEN = "seen"
        const val MET_ON = "met_on"
        const val MET_YEAR = "met_year"
        const val CELEBRATED = "celebrated:"

        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
    }
}

/**
 * What Sir Alex can tell of the phone: the battery, whether music is
 * playing, how many notifications there are (once notification access is
 * granted), the next alarm, and what other modules have told
 * [Happenings]: the sky, the temperature, a countdown's end, a game.
 */
internal class PhoneSenses(private val context: Context, private val services: Services) {
    private val happenings get() = services.find<Happenings>()

    /** The battery's charge, 0 to 100, and whether it's charging; null if it can't be told. */
    fun battery(): Pair<Int, Boolean>? {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return level * 100 / scale to plugged
    }

    fun musicPlaying(): Boolean = context.getSystemService(AudioManager::class.java)?.isMusicActive == true

    /** How many notifications there are now; null without notification access. */
    fun notifications(): Int? = try {
        NotificationHost.service?.activeNotifications?.size
    } catch (e: RuntimeException) {
        null
    }

    /** Minutes until the next alarm, if one is set. */
    fun minutesToAlarm(): Long? {
        val next = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime ?: return null
        return (next - System.currentTimeMillis()) / 60_000L
    }

    /** clear, cloudy, fog, rain, snow or storm, as the weather module last said; null if it hasn't. */
    fun sky(): String? = happenings?.now("weather.sky")

    fun celsius(): Double? = happenings?.now("weather.celsius")?.toDoubleOrNull()

    /** When the countdown last started ends, in epoch milliseconds. */
    fun timerEnds(): Long? = happenings?.now("timer.ends")?.toLongOrNull()

    /** A game was played (or a best beaten) in the last [withinMs]. */
    fun gamedLately(withinMs: Long = GAME_MS): Boolean {
        val latest = happenings?.latest?.value ?: return false
        val at = listOfNotNull(latest["games.played"]?.at, latest["games.best"]?.at).maxOrNull() ?: return false
        return System.currentTimeMillis() - at < withinMs
    }

    private companion object {
        const val GAME_MS = 15 * 60_000L
    }
}
