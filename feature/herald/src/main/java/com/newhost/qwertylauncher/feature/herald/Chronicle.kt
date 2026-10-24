package com.newhost.qwertylauncher.feature.herald

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A quest for the day: what to do, and the gold it's worth. */
internal enum class Quest(val text: String, val gold: Int) {
    NO_SOCIAL("Open no social app before nine", 5),
    KNOCK("Knock on my door: tap my realm", 3),
    PLAY("Play a game with me watching", 4),
    BEST("Beat a best score", 8),
    NO_POKES("Leave me be: no pokes all day, till eight of the evening", 6),
    TALK("Say something to me: alex <words>", 3),
}

/** One day as he saw it. */
internal class DayLog(
    val date: String,
    var pokes: Int = 0,
    var carried: Int = 0,
    var knocks: Int = 0,
    var games: Int = 0,
    var bests: Int = 0,
    var talks: Int = 0,
    var socialBeforeNine: Boolean = false,
    var sky: String? = null,
    var questDone: Boolean = false,
    /** Apps opened, by name, and how often. */
    val apps: MutableMap<String, Int> = mutableMapOf(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("date", date).put("pokes", pokes).put("carried", carried).put("knocks", knocks).put("games", games)
        .put("bests", bests).put("talks", talks).put("social", socialBeforeNine).put("sky", sky ?: "")
        .put("quest", questDone).put("apps", JSONObject(apps as Map<*, *>))

    companion object {
        fun from(o: JSONObject) = DayLog(
            o.getString("date"), o.optInt("pokes"), o.optInt("carried"), o.optInt("knocks"), o.optInt("games"),
            o.optInt("bests"), o.optInt("talks"), o.optBoolean("social"), o.optString("sky").ifEmpty { null },
            o.optBoolean("quest"),
            o.optJSONObject("apps")?.let { a -> a.keys().asSequence().associateWith { a.optInt(it) }.toMutableMap() } ?: mutableMapOf(),
        )
    }
}

/**
 * What Sir Alex keeps of the days: today's log (what you opened, how often
 * you poked him, the weather), the day's [Quest] and the gold quests have
 * earned (which buys [Outfit]s), his journal (a line for each day past),
 * and the outfit you told him to wear. Main thread only.
 */
internal class Chronicle(context: Context) {
    private val prefs = context.getSharedPreferences("herald_chronicle", Context.MODE_PRIVATE)

    /** Hears a quest done, to cheer about it. */
    var onQuestDone: (Quest) -> Unit = {}

    private var today: DayLog = read()

    /** Today's log, starting a new one on a new day; the day before is kept for the journal. */
    private fun log(): DayLog {
        val date = LocalDate.now().toString()
        if (today.date != date) {
            prefs.edit().putString(ENDED, today.toJson().toString()).apply()
            today = DayLog(date)
            save()
        }
        return today
    }

    private fun read(): DayLog = try {
        prefs.getString(TODAY, null)?.let { DayLog.from(JSONObject(it)) } ?: DayLog(LocalDate.now().toString())
    } catch (e: JSONException) {
        DayLog(LocalDate.now().toString())
    }

    private fun save() {
        prefs.edit().putString(TODAY, today.toJson().toString()).apply()
    }

    private fun record(change: DayLog.() -> Unit) {
        log().change()
        save()
        check()
    }

    fun poked() = record { pokes++ }
    fun carried() = record { carried++ }
    fun knocked() = record { knocks++ }
    fun talked() = record { talks++ }
    fun gamePlayed() = record { games++ }
    fun bestBeaten() = record { bests++ }
    fun sky(sky: String) = record { this.sky = sky }

    fun opened(label: String, social: Boolean) = record {
        apps[label] = (apps[label] ?: 0) + 1
        if (social && LocalTime.now().hour < NINE) socialBeforeNine = true
    }

    // --- Quests and gold ---

    /** Today's quest: the same all day, a different one most days. */
    val quest: Quest get() = Quest.entries[Math.floorMod(LocalDate.now().toEpochDay() * 7 + 3, Quest.entries.size.toLong()).toInt()]

    val questDone: Boolean get() = log().questDone

    /** Gold earned from quests, all told. */
    val gold: Int get() = prefs.getInt(GOLD, 0)

    /** Whether today's quest is done by now; if it just was, the gold is paid and [onQuestDone] hears. */
    fun check() {
        val log = log()
        if (log.questDone) return
        val hour = LocalTime.now().hour
        val done = when (quest) {
            Quest.NO_SOCIAL -> hour >= NINE && !log.socialBeforeNine
            Quest.KNOCK -> log.knocks > 0
            Quest.PLAY -> log.games > 0
            Quest.BEST -> log.bests > 0
            Quest.NO_POKES -> hour >= EIGHT_PM && log.pokes == 0
            Quest.TALK -> log.talks > 0
        }
        if (!done) return
        log.questDone = true
        save()
        prefs.edit().putInt(GOLD, gold + quest.gold).apply()
        onQuestDone(quest)
    }

    // --- The journal ---

    /** The day just ended, if it hasn't had its journal line yet: to write one for it. */
    fun unwritten(): DayLog? {
        log()
        val ended = prefs.getString(ENDED, null) ?: return null
        return try {
            DayLog.from(JSONObject(ended)).takeIf { it.date !in journal().map { e -> e.first } }
        } catch (e: JSONException) {
            null
        }
    }

    /** Writes [line] in the journal for [date]. */
    fun write(date: String, line: String) {
        val entries = journal() + (date to line)
        val array = JSONArray()
        entries.takeLast(MAX_ENTRIES).forEach { (d, l) -> array.put(JSONObject().put("date", d).put("line", l)) }
        prefs.edit().putString(JOURNAL, array.toString()).remove(ENDED).apply()
    }

    /** The journal, oldest first: each day and its line. */
    fun journal(): List<Pair<String, String>> = try {
        val array = JSONArray(prefs.getString(JOURNAL, "[]"))
        (0 until array.length()).map { array.getJSONObject(it).let { o -> o.getString("date") to o.getString("line") } }
    } catch (e: JSONException) {
        emptyList()
    }

    /** [day] as a line in his own words, without the AI. */
    fun plainEntry(day: DayLog): String {
        val parts = mutableListOf<String>()
        day.apps.maxByOrNull { it.value }?.let { (app, times) -> parts += "The liege opened $app ${times(times)}." }
        when {
            day.pokes == 0 -> parts += "Not one poke. A fine day."
            day.pokes > 5 -> parts += "I was poked ${times(day.pokes)}. Mine honour is bruised."
            else -> parts += "I was poked ${times(day.pokes)}."
        }
        if (day.carried > 0) parts += "Carried off ${times(day.carried)}, most undignified."
        if (day.bests > 0) parts += "A best score was beaten! Songs shall be sung."
        when (day.sky) {
            "rain", "storm" -> parts += "It rained. Mine armour squeaks."
            "snow" -> parts += "Snow! I made a snow-knight."
            "clear" -> parts += "The sun shone upon the realm."
        }
        if (day.questDone) parts += "The quest was done, and gold was paid."
        return parts.take(MAX_PARTS).joinToString(" ")
    }

    /** What happened on [day], plainly, for the AI to write up. */
    fun facts(day: DayLog): String = buildString {
        append("On ${day.date}: ")
        day.apps.entries.sortedByDescending { it.value }.take(3).forEach { append("opened ${it.key} ${it.value} times; ") }
        append("poked you ${day.pokes} times; carried you off ${day.carried} times; ")
        if (day.games > 0) append("played ${day.games} games; ")
        if (day.bests > 0) append("beat a best score; ")
        day.sky?.let { append("the weather was $it; ") }
        if (day.questDone) append("finished the day's quest.")
    }

    // --- What he wears ---

    /** The outfit the user chose, or null for "as the moment suits". */
    var worn: Outfit?
        get() = prefs.getString(WORN, null)?.let { id -> Outfit.ALL.firstOrNull { it.id == id } }?.takeIf { owns(it) }
        set(value) {
            prefs.edit().putString(WORN, value?.id).apply()
        }

    /** Whether the quests have earned [outfit] yet. */
    fun owns(outfit: Outfit) = gold >= outfit.cost

    private fun times(n: Int) = when (n) {
        1 -> "once"
        2 -> "twice"
        3 -> "thrice"
        else -> "$n times"
    }

    private companion object {
        const val TODAY = "today"
        const val ENDED = "ended"
        const val JOURNAL = "journal"
        const val GOLD = "gold"
        const val WORN = "worn"
        const val NINE = 9
        const val EIGHT_PM = 20
        const val MAX_ENTRIES = 90
        const val MAX_PARTS = 3
    }
}

/**
 * What he wears when: the user's choice when there is one (among those
 * earned); else what suits the moment: pyjamas to nap at night, plate after
 * a game, the night watch after dark, furs in the cold, the herald's colours
 * on a Sunday, and his hedge knight's green the rest of the time.
 */
internal object Wardrobe {
    fun pick(chronicle: Chronicle, life: Life, senses: PhoneSenses, napping: Boolean = false): Outfit {
        chronicle.worn?.let { return it }
        val celsius = senses.celsius()
        val cold = celsius?.let { it < COLD } ?: (life.season() == Season.WINTER)
        return when {
            napping && life.phase() == Phase.NIGHT -> Outfit.PYJAMAS
            senses.gamedLately() -> Outfit.TOURNEY
            life.phase() == Phase.NIGHT -> Outfit.NIGHT_WATCH
            cold -> Outfit.WINTER
            java.time.LocalDate.now().dayOfWeek == java.time.DayOfWeek.SUNDAY -> Outfit.HERALD
            else -> Outfit.HEDGE_KNIGHT
        }
    }

    private const val COLD = 5.0
}
