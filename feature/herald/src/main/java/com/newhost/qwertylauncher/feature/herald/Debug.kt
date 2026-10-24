package com.newhost.qwertylauncher.feature.herald

import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.PaneRow
import com.newhost.qwertylauncher.core.Tty
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

/**
 * `alex debug`: everything about Sir Alex on one pane, kept up to date. What
 * he is now (needs, mood, day, outfit, realm, quest), what he senses of the
 * phone, what he remembers; every scene, whether it could happen now and how
 * likely it is, with enter to have him do it straight away; outfits to try on;
 * and the realm's looks (level, season, holiday, night, chores) to preview.
 */
internal class AlexDebugPane(
    private val alex: Alex,
    private val life: Life,
    private val senses: PhoneSenses,
    private val chronicle: Chronicle,
    private val realm: Realm,
    private val memory: Memory,
    private val brain: Brain,
) : ListPane {
    override val title = "alex debug"
    override val keysHelp = "Enter on a scene and he does it now; on an outfit, he tries it on; on the realm, its next look. q goes back."

    override fun rows(): Flow<List<PaneRow>> = combine(ticks(), realm.preview, alex.out) { _, preview, out -> rows(preview, out) }

    /** Once a second: his needs and the countdown to his next visit move on their own. */
    private fun ticks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }

    private fun rows(preview: RealmPreview, out: Boolean): List<PaneRow> = buildList {
        add(PaneRow.Header("Sir Alex now"))
        val next = alex.nextVisitAt?.let { it - System.currentTimeMillis() }
        add(fact("where", if (out) "out on the home screen" else "home in his tree", if (!out && next != null) "out again in ${duration(next)}" else null))
        add(fact("time of day", words(life.phase()), "his day: morning 6 to 10, midday 11 to 13, afternoon 14 to 17, evening 18 to 21, night"))
        add(need("hunger", life.hunger, "high: he eats sooner"))
        add(need("boredom", life.boredom, "high: he juggles or kicks a pebble"))
        add(need("tiredness", life.tiredness, "high: he naps sooner"))
        val mood = when {
            life.wary -> "wary"
            life.friendly -> "friendly"
            else -> "neither wary nor friendly"
        }
        add(fact("mood", mood, "wariness ${life.wariness.toInt()} of 100: wary from 40, after pokes; friendly after two days unpoked"))
        add(fact("nervous on", life.fearedTile ?: "nowhere", "where he was last dropped hard"))
        add(fact("chores today", life.choresToday().sorted().joinToString(", ").ifEmpty { "none yet" }))
        add(fact("season and day", "${words(life.season())}, ${words(life.holiday())}"))
        val wearing = when {
            alex.trying != null -> "trying on, from here"
            chronicle.worn != null -> "chosen with alex wear"
            else -> "his own choice"
        }
        add(fact("outfit", (alex.trying ?: Wardrobe.pick(chronicle, life, senses)).title, wearing))
        add(fact("realm", "level ${realm.level}", "${chronicle.gold} gold: the treehouse at 30, the tower at 80"))
        add(fact("today's quest", chronicle.quest.text, if (chronicle.questDone) "done" else "${chronicle.quest.gold} gold"))
        add(fact("wits", if (brain.awake) "the AI: his own lines and answers" else "no AI set up: set lines only"))
        add(
            fact(
                "pokes, carries, catches",
                "${memory.count(Memory.POKED)}, ${memory.count(Memory.CARRIED)}, ${memory.count(Memory.CAUGHT)}",
                "all told",
            ),
        )

        add(PaneRow.Header("What he senses"))
        add(fact("sky", senses.sky() ?: "unknown", "from the weather module"))
        add(fact("temperature", senses.celsius()?.let { "%.0f°C".format(it) } ?: "unknown", "cold under 5°C, warm from 18°C"))
        add(fact("battery", senses.battery()?.let { (level, charging) -> "$level%" + if (charging) ", charging" else "" } ?: "unknown"))
        add(fact("music", if (senses.musicPlaying()) "playing" else "not playing"))
        add(fact("notifications", senses.notifications()?.toString() ?: "no notification access", "a new one brings the raven"))
        add(fact("next alarm", senses.minutesToAlarm()?.let { "in ${duration(it * MINUTE_MS)}" } ?: "none", "within the hour, he tiptoes past the clock"))
        val timer = senses.timerEnds()?.let { it - System.currentTimeMillis() }
        add(fact("timer", timer?.let { if (it > 0) "ends in ${duration(it)}" else "ended ${duration(-it)} ago" } ?: "none", "only the timer command's"))
        add(fact("a game lately", if (senses.gamedLately()) "yes" else "no", "in the last 15 minutes: the tourney outfit"))

        val scenes = alex.scenes()
        add(PaneRow.Header("Scenes: enter and he does it now"))
        if (scenes.isEmpty()) add(PaneRow.Item("scene:none", "Show the home screen once, then come back: he looks around there first."))
        scenes.filter { !it.moment }.sortedByDescending { it.weight }.forEach { add(scene(it)) }
        add(PaneRow.Header("Moments: what happens to him"))
        scenes.filter { it.moment }.forEach { add(PaneRow.Item(SCENE + it.name, it.name.removePrefix(MOMENT), "enter: now", status = "Plays straight away, as if it had just happened.")) }

        add(PaneRow.Header("Outfits: enter and he tries one on, until he's back in his own"))
        add(PaneRow.Item(OUTFIT + AUTO, "his own choice", if (alex.trying == null) "wearing" else null, status = "Takes off what he tried on here."))
        for (outfit in Outfit.ALL) {
            val detail = listOfNotNull(
                "trying on".takeIf { alex.trying == outfit },
                "locked till ${outfit.cost} gold".takeIf { !chronicle.owns(outfit) },
            ).joinToString(", ").ifEmpty { null }
            add(PaneRow.Item(OUTFIT + outfit.id, outfit.title, detail, status = "Not saved: alex wear chooses for good."))
        }

        add(PaneRow.Header("The realm's look: enter for the next one"))
        add(look("level", preview.level?.toString(), realm.level.toString()))
        add(look("season", preview.season?.let(::words), words(life.season())))
        add(look("holiday", preview.holiday?.let(::words), words(life.holiday())))
        add(look("time", preview.night?.let { if (it) "night" else "day" }, "by the clock"))
        add(look("chores", preview.chores?.let { if (it) "all done" else "none done" }, "today's"))
        add(PaneRow.Item(REALM + RESET, "show the realm as it really is", if (preview == RealmPreview()) "it is" else null))

        add(PaneRow.Header("What he remembers, newest last"))
        val memories = memory.recent(MEMORIES)
        if (memories.isEmpty()) add(PaneRow.Item("memory:none", "nothing yet"))
        memories.forEachIndexed { i, line -> add(PaneRow.Item("memory:$i", line)) }
        add(PaneRow.Header("His journal, last days"))
        val journal = chronicle.journal().takeLast(JOURNAL)
        if (journal.isEmpty()) add(PaneRow.Item("journal:none", "empty: he writes each day the day after"))
        journal.forEach { (date, line) -> add(PaneRow.Item("journal:$date", line, date)) }
    }

    private fun fact(name: String, value: String, about: String? = null) =
        PaneRow.Item("fact:$name", "$name: $value", status = about.orEmpty())

    private fun need(name: String, level: Int, about: String) =
        PaneRow.Item("fact:$name", "${name.padEnd(NEED_WIDTH)} ${Tty.meter(level / 100f, METER)} $level", status = "0 to 100, rising by the hour. $about")

    private fun scene(info: Alex.SceneInfo): PaneRow.Item {
        val detail = when {
            info.canNow -> if (info.weight > 0) "could now, weight ${info.weight}" else "could now, but unlikely"
            !info.tileShown -> "needs the ${info.needs} tile"
            else -> "not now"
        }
        val status = when {
            !info.tileShown -> "Put the ${info.needs} tile on home and he'll go there to do it."
            !info.canNow -> "Not the right hour, weather, mood or day for it; enter plays it anyway."
            else -> "Picked by weight among the scenes that could happen now."
        }
        return PaneRow.Item(SCENE + info.name, info.name, detail, status = status)
    }

    private fun look(name: String, shown: String?, real: String) =
        PaneRow.Item(REALM + name, "$name: ${shown ?: "real ($real)"}", status = "Close this pane to see the realm tile.")

    override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
        val id = item.id
        return when {
            id.startsWith(SCENE) && id != "scene:none" -> {
                alex.play(id.removePrefix(SCENE))
                true
            }
            id.startsWith(OUTFIT) -> {
                val outfitId = id.removePrefix(OUTFIT)
                alex.tryOn(Outfit.ALL.firstOrNull { it.id == outfitId })
                true
            }
            id.startsWith(REALM) -> {
                realm.preview.value = nextLook(realm.preview.value, id.removePrefix(REALM))
                false
            }
            else -> false
        }
    }

    private fun nextLook(p: RealmPreview, what: String): RealmPreview = when (what) {
        "level" -> p.copy(level = next(p.level, listOf(1, 2, 3)))
        "season" -> p.copy(season = next(p.season, Season.entries))
        "holiday" -> p.copy(holiday = next(p.holiday, Holiday.entries))
        "time" -> p.copy(night = next(p.night, listOf(false, true)))
        "chores" -> p.copy(chores = next(p.chores, listOf(true, false)))
        else -> RealmPreview()
    }

    /** The option after [current], then back to null (the real one) after the last. */
    private fun <T> next(current: T?, options: List<T>): T? =
        if (current == null) options.first() else options.getOrNull(options.indexOf(current) + 1)

    private fun words(value: Enum<*>) = value.name.lowercase().replace('_', ' ').let { if (it == "none") "no holiday" else it }

    private fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${s % 3600 / 60}m"
        }
    }

    private companion object {
        const val TICK_MS = 1000L
        const val MINUTE_MS = 60_000L
        const val NEED_WIDTH = 9
        const val METER = 10
        const val MEMORIES = 10
        const val JOURNAL = 5
        const val SCENE = "scene:"
        const val MOMENT = "moment: "
        const val OUTFIT = "outfit:"
        const val AUTO = "auto"
        const val REALM = "realm:"
        const val RESET = "reset"
    }
}
