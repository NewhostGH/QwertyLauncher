package com.newhost.qwertylauncher.feature.herald

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.newhost.qwertylauncher.core.AppCatalog
import com.newhost.qwertylauncher.core.ChoiceSetting
import com.newhost.qwertylauncher.core.ChoiceSetting.Choice
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.Fuzzy
import com.newhost.qwertylauncher.core.Happenings
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.ToggleSetting
import com.newhost.qwertylauncher.core.find
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException

/**
 * Sir Alex of Quertia, hedge knight and herald: a little knight drawn in
 * ASCII art who lives in his tree (the realm tile), comes out onto the home
 * screen, walks over the tiles, gets up to mischief and proclaims sayings.
 * Tap him to poke him, drag him to carry him. `alex` calls him or pokes
 * him; `fortune` has him proclaim a saying.
 */
class HeraldModule : LauncherModule {
    override val id = "herald"
    override val title = "Sir Alex, the herald"

    override fun install(scope: ModuleScope) {
        scope.declareSettings(*HeraldSettings.all)
        val context = scope.appContext
        val sayings = Sayings(context)
        val memory = Memory(context)
        val brain = Brain(scope.services, scope.settings, memory)
        val life = Life(context)
        val chronicle = Chronicle(context)
        val senses = PhoneSenses(context, scope.services)
        val realm = Realm(context, chronicle)
        val alex = Alex(context, scope.settings, scope.services, sayings, memory, brain, life, chronicle, senses) { realm.shownLevel }
        chronicle.onQuestDone = { quest -> alex.proclaim("Quest complete: ${quest.text.substringBefore(':')}! ${quest.gold} gold for the realm.") }
        scope.actor(alex)
        scope.widget(RealmWidget(realm, alex, scope.services, life, chronicle))
        val debug = AlexDebugPane(alex, life, senses, chronicle, realm, memory, brain)
        scope.command(AlexCommand(alex, sayings, memory, chronicle, realm, debug))
        scope.command(FortuneCommand(alex, sayings))
        watch(scope, chronicle)
    }

    /** What his day's log keeps: the apps opened (and whether they're social), the weather, games. */
    private fun watch(scope: ModuleScope, chronicle: Chronicle) {
        val services = scope.services
        scope.appScope.launch {
            val catalog = catalog(services)
            catalog.launches.collect { app -> chronicle.opened(app.label, social(scope.appContext, app.packageName)) }
        }
        scope.appScope.launch {
            val happenings = services.find<Happenings>() ?: return@launch
            happenings.latest.value["weather.sky"]?.let { chronicle.sky(it.value) }
            happenings.stream.collect { h ->
                when (h.kind) {
                    "weather.sky" -> chronicle.sky(h.value)
                    "games.played" -> chronicle.gamePlayed()
                    "games.best" -> chronicle.bestBeaten()
                }
            }
        }
        // Quests that are judged by the clock (no social app before nine, no pokes till evening).
        scope.appScope.launch {
            while (true) {
                chronicle.check()
                delay(CHECK_MS)
            }
        }
    }

    /** The apps module's catalog, waiting for it if it isn't there yet. */
    private suspend fun catalog(services: Services): AppCatalog {
        while (true) {
            services.find<AppCatalog>()?.let { return it }
            delay(CATALOG_WAIT_MS)
        }
    }

    private fun social(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0).category == ApplicationInfo.CATEGORY_SOCIAL
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        const val CHECK_MS = 10 * 60_000L
        const val CATALOG_WAIT_MS = 1_000L
    }
}

internal object HeraldSettings {
    val often = ChoiceSetting(
        "often",
        "Sir Alex comes by",
        listOf(
            Choice("always", "always: he stays out"),
            Choice("often"),
            Choice("sometimes", "now and then"),
            Choice("rarely"),
        ),
        default = "sometimes",
        summary = "How long Sir Alex stays in his tree between visits; always keeps him out on the home screen " +
            "(sent home, he's back in a minute). Type alex to call him any time.",
    )

    val useAi = ToggleSetting(
        "use_ai",
        "Sir Alex thinks with the AI",
        default = true,
        summary = "When the AI is set up, Sir Alex uses it to think up his own lines, answer what thou sayest to him " +
            "(alex and words), and remember. What he remembers, and the names of the tiles on screen, go to the AI.",
    )

    val all = arrayOf(often, useAi)
}

/** The sayings and tips he proclaims, and the ones you teach him. Main thread only. */
internal class Sayings(context: Context) {
    private val prefs = context.getSharedPreferences("herald", Context.MODE_PRIVATE)
    var own: List<String> = read()
        private set

    fun pick(): Fortune = (Fortunes.sayings + Fortunes.tips + own.map { Fortune(it) }).random()

    fun teach(text: String) {
        val clean = text.trim()
        if (clean.isNotEmpty() && clean !in own) write(own + clean)
    }

    fun forget(text: String) = write(own - text)

    private fun read(): List<String> = try {
        val array = JSONArray(prefs.getString(OWN, "[]"))
        (0 until array.length()).map { array.getString(it) }
    } catch (e: JSONException) {
        emptyList()
    }

    private fun write(list: List<String>) {
        own = list
        prefs.edit().putString(OWN, JSONArray(list).toString()).apply()
    }

    private companion object {
        const val OWN = "own"
    }
}

/** A saying as one line: `text -- who`. */
internal fun Fortune.line(): String = when (by) {
    null -> text
    "tip" -> "tip: $text"
    else -> "$text -- $by"
}

/**
 * `alex` calls Sir Alex (or pokes him, when he's here); `alex go` sends him
 * off; `alex teach <saying>` and `alex forget <saying>` edit what he says;
 * `alex forget everything` wipes his memory; `alex journal` reads his
 * journal; `alex quest` shows the day's quest and the gold; `alex wear
 * <outfit>` dresses him (`auto` to let him choose); `alex debug` shows all
 * of him and plays any scene; anything else is said to him.
 */
internal class AlexCommand(
    private val alex: Alex,
    private val sayings: Sayings,
    private val memory: Memory,
    private val chronicle: Chronicle,
    private val realm: Realm,
    private val debug: AlexDebugPane,
) : Command {
    override val name = "alex"
    override val usage = "alex [words|go|journal|quest|wear <outfit>|teach <saying>|forget <saying>|debug]"
    override val summary = "Sir Alex the herald: call him, talk to him, read his journal, see his quest, dress him; debug shows and plays everything he does"

    override suspend fun suggest(args: String): List<SearchResult> {
        val verb = args.substringBefore(' ').lowercase()
        val rest = args.substringAfter(' ', "").trim()
        return when {
            verb == "debug" -> listOf(
                result("alex:debug", "Sir Alex debug", "everything he is, senses and does; play any of it") { host -> host.showPane(debug) },
            )
            verb == "journal" -> listOf(
                result("alex:journal", "Sir Alex's journal", "${chronicle.journal().size} days written") { host ->
                    val entries = chronicle.journal().takeLast(JOURNAL_SHOWN)
                    if (entries.isEmpty()) host.console.println("Sir Alex's journal is empty: he writes a line for each day, the day after.")
                    entries.forEach { (date, line) -> host.console.println("$date  $line") }
                },
            )
            verb == "quest" || verb == "gold" -> listOf(
                result("alex:quest", "today's quest: ${chronicle.quest.text}", if (chronicle.questDone) "done!" else "${chronicle.quest.gold} gold") { host ->
                    host.console.println("Quest: ${chronicle.quest.text}${if (chronicle.questDone) " (done)" else ""}")
                    host.console.println("Gold: ${chronicle.gold}. Realm level ${realm.level}.")
                    val next = Outfit.ALL.filter { !chronicle.owns(it) }.minByOrNull { it.cost }
                    if (next != null) host.console.println("Next: ${next.title} at ${next.cost} gold.")
                },
            )
            verb == "wear" -> (listOf<Outfit?>(null) + Outfit.ALL)
                .filter { o -> rest.isEmpty() || (o?.title ?: "auto").startsWith(rest.lowercase()) || o?.id?.startsWith(rest.lowercase()) == true }
                .map { outfit ->
                    val owned = outfit == null || chronicle.owns(outfit)
                    val title = outfit?.title ?: "auto"
                    result(
                        "alex:wear:${outfit?.id ?: "auto"}",
                        "Sir Alex wears: $title",
                        when {
                            outfit == null -> "as the moment suits"
                            owned -> if (chronicle.worn == outfit) "wearing it" else null
                            else -> "locked: ${outfit.cost} gold (thou hast ${chronicle.gold})"
                        },
                    ) { host ->
                        if (!owned) {
                            host.console.println("Not yet: ${outfit?.cost} gold for the $title. Do quests: alex quest.")
                        } else {
                            chronicle.worn = outfit
                            host.console.println(if (outfit == null) "Sir Alex dresses as the moment suits." else "Sir Alex shall wear the $title.")
                        }
                    }
                }
            verb == "teach" && rest.isNotEmpty() -> listOf(
                result("alex:teach", "teach Sir Alex: $rest") { host ->
                    sayings.teach(rest)
                    memory.remember("The user taught me a saying: \"$rest\"")
                    host.console.println("Sir Alex learnt a new saying.")
                },
            )
            verb == "forget" && "everything".startsWith(rest.lowercase()) && rest.isNotEmpty() -> listOf(
                result("alex:amnesia", "Sir Alex forgets everything", "all he remembers of thee") { host ->
                    memory.forgetAll()
                    host.console.println("Sir Alex remembers nothing. Who art thou?")
                },
            )
            verb == "forget" -> sayings.own
                .filter { rest.isEmpty() || Fuzzy.score(rest, it) > 0 }
                .map { saying ->
                    result("alex:forget:$saying", "forget: $saying") { host ->
                        sayings.forget(saying)
                        host.console.println("Sir Alex forgot it. Or says he did.")
                    }
                }
            verb == "go" && rest.isEmpty() -> listOf(result("alex:go", "send Sir Alex home") { alex.dismiss() })
            // Anything else is said to him.
            args.isNotBlank() -> listOf(
                result("alex:say", "say to Sir Alex: ${args.trim()}", "he answers on the home screen") { alex.hear(args.trim()) },
            )
            else -> listOf(
                if (alex.present) {
                    result("alex", "poke Sir Alex", "he will not like it") { alex.pokes.trySend(Unit) }
                } else {
                    result("alex", "call Sir Alex", "herald and knight") { alex.summon() }
                },
            )
        }
    }

    private companion object {
        const val JOURNAL_SHOWN = 7
    }

    private fun result(key: String, title: String, detail: String? = null, run: (LauncherHost) -> Unit) =
        SearchResult(
            key = key,
            tag = "alex",
            title = title,
            detail = detail,
            score = Score.EXACT,
            action = { host ->
                host.clearQuery()
                run(host)
            },
        )
}

/** `fortune`: a saying, printed here and proclaimed by Sir Alex. */
internal class FortuneCommand(private val alex: Alex, private val sayings: Sayings) : Command {
    override val name = "fortune"
    override val usage = "fortune"
    override val summary = "a saying, proclaimed by Sir Alex"

    override suspend fun suggest(args: String): List<SearchResult> = listOf(
        SearchResult(
            key = "fortune",
            tag = "alex",
            title = "fortune",
            detail = "enter for a saying",
            score = Score.EXACT,
            action = { host ->
                val saying = sayings.pick().line()
                host.clearQuery()
                host.console.println(saying)
                alex.proclaim(saying)
            },
        ),
    )
}
