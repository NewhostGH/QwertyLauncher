package com.newhost.qwertylauncher.feature.news

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Xml
import com.newhost.qwertylauncher.core.Cell
import com.newhost.qwertylauncher.core.ChoiceSetting
import com.newhost.qwertylauncher.core.ChoiceSetting.Choice
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.DebugLog
import com.newhost.qwertylauncher.core.HomeWidget
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.NumberSetting
import com.newhost.qwertylauncher.core.PaneRow
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.Size
import com.newhost.qwertylauncher.core.TileSize
import com.newhost.qwertylauncher.core.WidgetContent
import com.newhost.qwertylauncher.core.tile
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import com.newhost.qwertylauncher.core.R as CoreR
import com.newhost.qwertylauncher.core.ShapeIcon

/** Headlines from an RSS or Atom feed: a tile, a pane to open them, and `news`. */
class NewsModule : LauncherModule {
    override val id = "news"

    override fun install(scope: ModuleScope) {
        scope.declareSettings(*NewsSettings.all)
        val repository = NewsRepository(scope.appContext, scope.settings, scope.log)
        val pane = NewsPane(repository, scope.appScope)
        scope.widget(NewsWidget(scope.appContext, repository, scope.settings, pane))
        scope.command(NewsCommand(pane))
    }
}

internal object NewsSettings {
    /** Choice value = the feed's URL. */
    val feed = ChoiceSetting(
        "feed",
        "news feed",
        listOf(
            Choice("https://hnrss.org/frontpage", "hacker news"),
            Choice("https://feeds.bbci.co.uk/news/world/rss.xml", "bbc world"),
            Choice("https://feeds.npr.org/1001/rss.xml", "npr news"),
            Choice("https://feeds.arstechnica.com/arstechnica/index", "ars technica"),
            Choice("https://www.theverge.com/rss/index.xml", "the verge"),
            Choice("https://lwn.net/headlines/rss", "lwn.net"),
        ),
        default = "https://hnrss.org/frontpage",
    )
    val refreshMinutes = NumberSetting("refresh_minutes", "refresh news every", default = 60, min = 15, max = 240, step = 15, unit = "min")
    val tileHeadlines = NumberSetting("tile_headlines", "news headlines on the tile", default = 3, min = 1, max = 8)

    val all = arrayOf(feed, refreshMinutes, tileHeadlines)

    fun feedName(url: String) = feed.options.firstOrNull { it.value == url }?.label ?: url
}

internal data class Headline(val title: String, val link: String)

internal sealed interface NewsState {
    data object Loading : NewsState
    data class Ready(val feed: String, val headlines: List<Headline>, val fetchedAt: Long) : NewsState
    data class Failed(val message: String) : NewsState
}

/** Fetches the chosen feed, keeps the last good copy on disk, and serves it while offline. */
internal class NewsRepository(
    private val context: Context,
    private val settings: SettingsStore,
    private val log: DebugLog,
) {
    private val prefs = context.getSharedPreferences("news", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readCache() ?: NewsState.Loading)
    val state: StateFlow<NewsState> = _state.asStateFlow()
    private val mutex = Mutex()

    suspend fun refresh(force: Boolean = false) = mutex.withLock {
        val feed = settings[NewsSettings.feed]
        val current = _state.value
        val maxAge = settings[NewsSettings.refreshMinutes] * 60_000L
        val fresh = current is NewsState.Ready && current.feed == feed && System.currentTimeMillis() - current.fetchedAt < maxAge
        if (fresh && !force) return@withLock
        _state.value = try {
            val headlines = withContext(Dispatchers.IO) { parse(download(feed)) }
            NewsState.Ready(feed, headlines, System.currentTimeMillis()).also(::writeCache)
        } catch (e: IOException) {
            log.e("news fetch failed", e)
            keepOr(current, feed, "Offline. Showing nothing new.")
        } catch (e: XmlPullParserException) {
            log.e("news feed unreadable", e)
            keepOr(current, feed, "The feed could not be read.")
        }
    }

    /** Keeps showing old headlines of the same feed rather than an error. */
    private fun keepOr(current: NewsState, feed: String, message: String): NewsState =
        if (current is NewsState.Ready && current.feed == feed) current else NewsState.Failed(message)

    private fun download(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "QwertyLauncher")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Titles and links from RSS `<item>`s or Atom `<entry>`s, in feed order. */
    private fun parse(xml: String): List<Headline> {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        val headlines = mutableListOf<Headline>()
        var inItem = false
        var title: String? = null
        var link: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT && headlines.size < MAX_HEADLINES) {
            val name = parser.name?.lowercase()
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when {
                    name == "item" || name == "entry" -> {
                        inItem = true
                        title = null
                        link = null
                    }
                    inItem && name == "title" -> title = parser.nextText().trim()
                    // RSS puts the URL in the text, Atom in an href attribute. Atom entries can have
                    // several links (comments, self); the first is the article.
                    inItem && name == "link" && link == null ->
                        link = parser.getAttributeValue(null, "href") ?: parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> if (name == "item" || name == "entry") {
                    inItem = false
                    val t = title
                    val l = link
                    if (!t.isNullOrBlank() && !l.isNullOrBlank()) headlines += Headline(t, l)
                }
            }
        }
        return headlines
    }

    private fun writeCache(state: NewsState.Ready) {
        val items = JSONArray()
        state.headlines.forEach { items.put(JSONObject().put("t", it.title).put("l", it.link)) }
        val json = JSONObject().put("feed", state.feed).put("at", state.fetchedAt).put("items", items)
        prefs.edit().putString(CACHE_KEY, json.toString()).apply()
    }

    private fun readCache(): NewsState.Ready? {
        val raw = prefs.getString(CACHE_KEY, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val items = json.getJSONArray("items")
            val headlines = (0 until items.length()).map { i ->
                items.getJSONObject(i).let { Headline(it.getString("t"), it.getString("l")) }
            }
            NewsState.Ready(json.getString("feed"), headlines, json.getLong("at"))
        } catch (e: JSONException) {
            null
        }
    }

    private companion object {
        const val CACHE_KEY = "last"
        const val TIMEOUT_MS = 10_000
        const val MAX_HEADLINES = 30
    }
}

/**
 * ```
 * ▄▄▄▄▄   1 First headline of the feed
 * █▀▀▀█   2 Second headline
 * █▄▄▄█   3 ...
 *         hacker news, 14:05
 * ```
 */
internal class NewsWidget(
    private val context: Context,
    private val repository: NewsRepository,
    private val settings: SettingsStore,
    private val pane: NewsPane,
) : HomeWidget {
    override val id = "news"
    override val order = 60
    override val size = TileSize.WIDE

    override fun render(): Flow<WidgetContent> = channelFlow {
        // Refresh while visible; the repository skips the network while its copy is fresh.
        launch {
            while (true) {
                repository.refresh()
                delay(CHECK_MS)
            }
        }
        combine(
            repository.state,
            settings.observe(NewsSettings.feed),
            settings.observe(NewsSettings.tileHeadlines),
        ) { state, feed, max -> Triple(state, feed, max) }.collect { (state, feed, max) ->
            // A different feed was chosen: fetch it now.
            if (state is NewsState.Ready && state.feed != feed) launch { repository.refresh(force = true) }
            send(content(state, max))
        }
    }

    override fun onClick(host: LauncherHost) = host.showPane(pane)

    /**
     * The newspaper drawn as art beside the feed's name and when it was
     * updated, and the headlines: as many as the tile has room for (at least
     * the tile setting's number), in columns when it's wide.
     */
    private fun content(state: NewsState, max: Int): WidgetContent {
        val picture = Cell.Art("news:paper", Size(PICTURE_COLUMNS, PICTURE_LINES)) {
            val color = context.getColor(CoreR.color.tty_white)
            ShapeIcon { fill(color); path(NEWSPAPER) }
        }
        val dim = context.getColor(CoreR.color.tty_dim)
        return when (state) {
            NewsState.Loading -> {
                val headline = SpannableStringBuilder().apply { append("loading headlines...", ForegroundColorSpan(dim), SPAN) }
                WidgetContent(headline, layout = tile(picture, headline))
            }
            is NewsState.Failed -> {
                val headline = SpannableStringBuilder().apply {
                    append(state.message, ForegroundColorSpan(context.getColor(CoreR.color.tty_yellow)), SPAN)
                }
                WidgetContent(headline, layout = tile(picture, headline))
            }
            is NewsState.Ready -> {
                val time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(state.fetchedAt).atZone(ZoneId.systemDefault()))
                val headline = SpannableStringBuilder().apply {
                    append(NewsSettings.feedName(state.feed))
                    append("  updated $time", ForegroundColorSpan(dim), SPAN)
                }
                val items = state.headlines.mapIndexed { i, item ->
                    Cell.Text(
                        SpannableStringBuilder().apply {
                            append("${i + 1} ", ForegroundColorSpan(context.getColor(CoreR.color.tty_accent)), SPAN)
                            append(item.title)
                        },
                        wrap = false,
                    )
                }
                WidgetContent(
                    headline,
                    layout = tile(picture, headline, items, factRows = max.coerceAtLeast(1), factColumns = HEADLINE_COLUMNS, pack = false),
                )
            }
        }
    }

    private companion object {
        const val CHECK_MS = 5 * 60_000L
        const val PICTURE_COLUMNS = 6
        const val PICTURE_LINES = 3

        /** Material's "newspaper". */
        const val NEWSPAPER = "M22,3l-1.67,1.67L18.67,3L17,4.67L15.33,3l-1.66,1.67L12,3l-1.67,1.67L8.67,3L7,4.67L5.33,3L3.67,4.67L2,3v16" +
            "c0,1.1 0.9,2 2,2l16,0c1.1,0 2,-0.9 2,-2V3zM11,19H4v-6h7V19zM20,19h-7v-2h7V19zM20,15h-7v-2h7V15zM20,11H4V8h16V11z"

        /** Headlines sit in columns at least this wide: they're cut at the edge, so not too narrow. */
        const val HEADLINE_COLUMNS = 34
        const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    }
}

/** All headlines; Enter opens one in the browser, r refreshes. */
internal class NewsPane(
    private val repository: NewsRepository,
    private val scope: CoroutineScope,
) : ListPane {
    override val title = "news"
    override val keysHelp = "⏎ open  r refresh  q back"

    override fun rows(): Flow<List<PaneRow>> = repository.state.map { state ->
        when (state) {
            NewsState.Loading -> listOf(PaneRow.Header("news"), PaneRow.Item(INFO, "Loading headlines..."))
            is NewsState.Failed -> listOf(PaneRow.Header("news"), PaneRow.Item(INFO, state.message))
            is NewsState.Ready -> listOf(PaneRow.Header(NewsSettings.feedName(state.feed))) +
                state.headlines.mapIndexed { i, headline ->
                    PaneRow.Item(
                        id = headline.link,
                        text = "%2d ".format(i + 1) + headline.title,
                        status = headline.title + "\n" + Uri.parse(headline.link).host.orEmpty(),
                    )
                }
        }
    }

    override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
        if (item.id == INFO) return false
        return host.startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse(item.id)))
    }

    override fun onKey(item: PaneRow.Item?, event: android.view.KeyEvent, host: LauncherHost): Boolean {
        if (event.keyCode != android.view.KeyEvent.KEYCODE_R) return false
        scope.launch { repository.refresh(force = true) }
        return true
    }

    private companion object {
        const val INFO = "news:info"
    }
}

/** `news`: opens the headlines. */
internal class NewsCommand(private val pane: NewsPane) : Command {
    override val name = "news"
    override val usage = "news"
    override val summary = "headlines from your chosen feed"

    override suspend fun suggest(args: String) = listOf(
        SearchResult(
            key = "news",
            tag = "news",
            title = "news",
            detail = summary,
            action = { host ->
                host.clearQuery()
                host.showPane(pane)
            },
        ),
    )
}
