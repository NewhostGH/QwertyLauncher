package com.newhost.qwertylauncher.feature.suggest

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.newhost.qwertylauncher.core.AppGridPanel
import com.newhost.qwertylauncher.core.AppCatalog
import com.newhost.qwertylauncher.core.AppEntry
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.Favorites
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.MenuItem
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.ToggleSetting
import com.newhost.qwertylauncher.core.find
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Apps you're likely to want now, learnt on the phone from what you open
 * with the launcher, and when: the time of day, weekday or weekend, what's
 * playing sound (headphones, the car's speakers by name), charging, wifi
 * or mobile data. A tile of them, and `suggest` with the reasons. Nothing
 * leaves the phone.
 */
class SuggestModule : LauncherModule {
    override val id = "suggest"
    override val title = "suggestions"

    override fun install(scope: ModuleScope) {
        scope.declareSettings(*SuggestSettings.all)
        val log = LaunchLog(scope.appContext, scope.appScope)
        val brain = Suggester(scope.appContext, log, scope.services)
        // Learns from every launch through the launcher, whoever started it.
        scope.appScope.launch {
            catalog(scope.services).launches.collect { app -> if (scope.settings[SuggestSettings.learn]) log.add(app.key, Senses.now(scope.appContext)) }
        }
        // The same grid as the favourites, of the apps likely now; holding enter on one says why.
        scope.panel(
            AppGridPanel(
                id = "suggest",
                title = "suggestions",
                order = 3,
                services = scope.services,
                apps = { brain.live().map { list -> list.map { it.app } } },
                menu = { app -> listOfNotNull(brain.latest[app.key]?.let { MenuItem("why: ${it.why}") { } }) },
                emptyText = "Learning: open apps from the launcher and they show up here.",
                // One row as it fits; make the tile taller for more. Just what fits: suggestions have no pages.
                maxRows = 1,
                onePage = true,
            ),
        )
        scope.command(SuggestCommand(brain, log, scope.services))
    }

    /** The apps module's catalog, waiting for it if it isn't there yet (modules install one by one). */
    private suspend fun catalog(services: Services): AppCatalog {
        while (true) {
            services.find<AppCatalog>()?.let { return it }
            delay(CATALOG_WAIT_MS)
        }
    }

    private companion object {
        const val CATALOG_WAIT_MS = 1_000L
    }
}

internal object SuggestSettings {
    val learn = ToggleSetting(
        "learn",
        "suggestions learn from what I open",
        default = true,
        summary = "Learns when you open which app. Stays on the phone.",
    )

    val all = arrayOf(learn)
}

/** The moment an app is opened, as far as suggestions care. */
internal data class Moment(
    /** 0 to 24, with minutes as fractions. */
    val hour: Float,
    val weekend: Boolean,
    /** Where sound goes when it isn't the phone's speaker: headphones or a car, by name. */
    val audio: String?,
    val charging: Boolean,
    /** "wifi", "mobile" or "none". */
    val network: String,
)

/** What the phone is doing now. */
internal object Senses {
    private val OUTPUTS = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    fun now(context: Context): Moment {
        val time = LocalDateTime.now()
        val audio = context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type in OUTPUTS }
            ?.let { device -> device.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "headphones" }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = try {
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        } catch (e: SecurityException) {
            null
        }
        val network = when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
        return Moment(
            hour = time.hour + time.minute / 60f,
            weekend = time.dayOfWeek == DayOfWeek.SATURDAY || time.dayOfWeek == DayOfWeek.SUNDAY,
            audio = audio,
            charging = context.getSystemService(BatteryManager::class.java).isCharging,
            network = network,
        )
    }
}

/** One app opened: which, when, and the moment around it. */
internal data class Launch(val app: String, val at: Long, val moment: Moment)

/** The last [MAX] launches, in a file of the launcher's own. */
internal class LaunchLog(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "launches.json")
    private val _launches = MutableStateFlow<List<Launch>>(emptyList())
    val launches: StateFlow<List<Launch>> = _launches.asStateFlow()

    init {
        scope.launch { _launches.value = withContext(Dispatchers.IO) { read() } }
    }

    fun add(app: String, moment: Moment) {
        _launches.value = (_launches.value + Launch(app, System.currentTimeMillis(), moment)).takeLast(MAX)
        val snapshot = _launches.value
        scope.launch(Dispatchers.IO) { write(snapshot) }
    }

    fun clear() {
        _launches.value = emptyList()
        scope.launch(Dispatchers.IO) { file.delete() }
    }

    private fun read(): List<Launch> = try {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Launch(
                o.getString("app"),
                o.getLong("at"),
                Moment(
                    o.getDouble("hour").toFloat(),
                    o.getBoolean("weekend"),
                    o.optString("audio").takeIf { it.isNotEmpty() },
                    o.getBoolean("charging"),
                    o.getString("network"),
                ),
            )
        }
    } catch (e: JSONException) {
        emptyList()
    } catch (e: java.io.IOException) {
        emptyList()
    }

    @Synchronized
    private fun write(launches: List<Launch>) {
        val array = JSONArray()
        launches.forEach { l ->
            array.put(
                JSONObject()
                    .put("app", l.app).put("at", l.at)
                    .put("hour", l.moment.hour.toDouble()).put("weekend", l.moment.weekend)
                    .put("audio", l.moment.audio ?: "").put("charging", l.moment.charging).put("network", l.moment.network),
            )
        }
        try {
            file.writeText(array.toString())
        } catch (e: java.io.IOException) {
            // Next time.
        }
    }

    private companion object {
        const val MAX = 2000
    }
}

/** An app worth offering now, and why, in a few words. */
internal class Suggestion(val app: AppEntry, val score: Double, val why: String)

/**
 * Scores every app by the launches like now: each counts for more the closer
 * its time of day (a couple of hours either way), on the same kind of day,
 * with the same sound device (a car's speakers count for a lot), charging
 * alike, on the same network, and the more recent it is.
 */
internal class Suggester(
    private val context: Context,
    private val log: LaunchLog,
    private val services: Services,
) {
    fun suggestions(now: Moment, launches: List<Launch>, apps: List<AppEntry>, count: Int): List<Suggestion> {
        val byKey = apps.associateBy { it.key }
        val nowMs = System.currentTimeMillis()
        // The app just used isn't much of a suggestion.
        val justUsed = launches.lastOrNull()?.takeIf { nowMs - it.at < JUST_USED_MS }?.app
        class Tally {
            var score = 0.0
            var time = 0.0
            var audio = 0.0
            var day = 0.0
            var sinHours = 0.0
            var cosHours = 0.0
            var times = 0
        }
        val tallies = mutableMapOf<String, Tally>()
        for (l in launches) {
            if (l.app == justUsed || l.app !in byKey) continue
            val m = l.moment
            val ageDays = (nowMs - l.at) / DAY_MS
            val recency = exp(-ageDays / RECENCY_DAYS)
            val hours = hourGap(m.hour, now.hour)
            val time = exp(-(hours * hours) / (2 * TIME_SPREAD * TIME_SPREAD))
            val sameDay = m.weekend == now.weekend
            val audio = when {
                now.audio != null && m.audio == now.audio -> AUDIO_MATCH
                now.audio == null && m.audio == null -> 1.0
                else -> AUDIO_MISMATCH
            }
            val weight = recency * (TIME_FLOOR + time) * (if (sameDay) 1.0 else OTHER_DAY) * audio *
                (if (m.charging == now.charging) 1.0 else CHARGING_MISMATCH) *
                (if (m.network == now.network) 1.0 else NETWORK_MISMATCH)
            val t = tallies.getOrPut(l.app) { Tally() }
            t.score += weight
            t.time += weight * time
            if (now.audio != null && m.audio == now.audio) t.audio += weight
            if (sameDay) t.day += weight
            val angle = m.hour / 24.0 * 2 * PI
            t.sinHours += sin(angle)
            t.cosHours += cos(angle)
            t.times++
        }
        return tallies.entries.sortedByDescending { it.value.score }.take(count).mapNotNull { (key, t) ->
            val app = byKey[key] ?: return@mapNotNull null
            val usual = ((atan2(t.sinHours, t.cosHours) / (2 * PI) * 24 + 24) % 24).roundToInt() % 24
            val why = when {
                t.audio > t.score / 2 -> "with ${now.audio}"
                t.time > t.score / 2 -> "usually around %02d:00".format(usual)
                t.day > t.score * DAY_SHARE -> if (now.weekend) "on weekends" else "on weekdays"
                t.times >= OFTEN -> "often"
                else -> "lately"
            }
            Suggestion(app, t.score, why)
        }
    }

    /** Hours apart, round the clock: 23:00 and 01:00 are 2 apart. */
    private fun hourGap(a: Float, b: Float): Double {
        val gap = abs(a - b).toDouble()
        return minOf(gap, 24 - gap)
    }

    /** The last suggestions, by app key: for why each is there. */
    @Volatile var latest: Map<String, Suggestion> = emptyMap()
        private set

    /** Suggestions now, as many as the grid holds, again every few minutes, and whenever something's opened or the apps change. */
    fun live(): Flow<List<Suggestion>> {
        val catalog = services.find<AppCatalog>() ?: return flowOf(emptyList())
        val ticks = flow {
            while (true) {
                emit(Unit)
                delay(REFRESH_MS)
            }
        }
        // The favourites on screen aren't suggested: they're right there already.
        val onScreen = services.find<Favorites>()?.visible ?: flowOf(emptyList())
        // More than any tile shows: the grid takes what fits.
        return combine(log.launches, catalog.apps, onScreen, ticks) { launches, apps, favourites, _ ->
            val shown = favourites.toSet()
            val candidates = apps.filter { it.key !in shown }
            val learnt = suggestions(Senses.now(context), launches, candidates, MAX_SUGGESTIONS)
            (learnt + newest(candidates, learnt.map { it.app.key }.toSet(), MAX_SUGGESTIONS - learnt.size))
                .also { list -> latest = list.associateBy { it.app.key } }
        }.flowOn(Dispatchers.Default)
    }

    /** When each package was installed, looked up once: 0 if it can't be told. */
    private val installed = mutableMapOf<String, Long>()

    /**
     * Up to [count] of [apps] not in [taken], newest installed first: what
     * fills the grid while too little is learnt, so a taller tile's rows
     * aren't left empty.
     */
    private fun newest(apps: List<AppEntry>, taken: Set<String>, count: Int): List<Suggestion> {
        if (count <= 0) return emptyList()
        return apps.filter { it.key !in taken }
            .sortedByDescending { app ->
                synchronized(installed) {
                    installed.getOrPut(app.packageName) {
                        try {
                            context.packageManager.getPackageInfo(app.packageName, 0).firstInstallTime
                        } catch (e: PackageManager.NameNotFoundException) {
                            0L
                        }
                    }
                }
            }
            .take(count)
            .map { Suggestion(it, 0.0, "recently installed") }
    }

    private companion object {
        const val DAY_MS = 86_400_000.0
        const val JUST_USED_MS = 10 * 60_000L
        const val RECENCY_DAYS = 21.0
        const val TIME_SPREAD = 1.5
        const val TIME_FLOOR = 0.15
        const val OTHER_DAY = 0.45
        const val AUDIO_MATCH = 3.0
        const val AUDIO_MISMATCH = 0.5
        const val CHARGING_MISMATCH = 0.75
        const val NETWORK_MISMATCH = 0.8
        const val DAY_SHARE = 0.8
        const val OFTEN = 5
        const val REFRESH_MS = 5 * 60_000L
        const val MAX_SUGGESTIONS = 24
    }
}

/** `suggest`: the apps likely now, with why; `suggest forget` starts learning afresh. */
internal class SuggestCommand(private val brain: Suggester, private val log: LaunchLog, private val services: Services) : Command {
    private companion object {
        const val LISTED = 8
    }

    override val name = "suggest"
    override val usage = "suggest [forget]"
    override val summary = "apps you're likely to want now, and why; forget starts learning again"

    override suspend fun suggest(args: String): List<SearchResult> {
        if (args.trim().lowercase().let { it.isNotEmpty() && "forget".startsWith(it) }) {
            return listOf(
                SearchResult(
                    key = "suggest:forget",
                    tag = "sug",
                    title = "forget what I open",
                    detail = "${log.launches.value.size} launches learnt",
                    score = Score.EXACT,
                    action = { host ->
                        log.clear()
                        host.clearQuery()
                        host.console.println("Suggestions start learning again.")
                    },
                ),
            )
        }
        val list = brain.live().firstOrNull().orEmpty().take(LISTED)
        if (list.isEmpty()) {
            return listOf(SearchResult(key = "suggest:none", tag = "sug", title = "nothing learnt yet", detail = "open apps from the launcher", action = {}))
        }
        return list.mapIndexed { i, s ->
            SearchResult(
                key = "suggest:${s.app.key}",
                tag = "sug",
                title = s.app.label,
                detail = s.why,
                score = Score.EXACT - i,
                action = { host ->
                    host.clearQuery()
                    services.find<AppCatalog>()?.launch(s.app, host)
                },
            )
        }
    }
}
