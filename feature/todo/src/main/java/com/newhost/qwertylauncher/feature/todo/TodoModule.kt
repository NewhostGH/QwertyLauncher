package com.newhost.qwertylauncher.feature.todo

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import com.newhost.qwertylauncher.core.Cell
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.HomeWidget
import com.newhost.qwertylauncher.core.IconColors
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.NumberSetting
import com.newhost.qwertylauncher.core.PaneRow
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.ShapeIcon
import com.newhost.qwertylauncher.core.Size
import com.newhost.qwertylauncher.core.ToggleSetting
import com.newhost.qwertylauncher.core.WidgetContent
import com.newhost.qwertylauncher.core.fact
import com.newhost.qwertylauncher.core.icon
import com.newhost.qwertylauncher.core.meter
import com.newhost.qwertylauncher.core.tile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import com.newhost.qwertylauncher.core.R as CoreR

/** A todo list: a tile with progress and open items, `todo <text>` to add, and a pane to tick them off. */
class TodoModule : LauncherModule {
    override val id = "todo"

    override fun install(scope: ModuleScope) {
        scope.declareSettings(*TodoSettings.all)
        val store = TodoStore(scope.appContext)
        val pane = TodoPane(store)
        scope.widget(TodoWidget(scope.appContext, store, scope.settings, pane))
        scope.command(TodoCommand(store, pane))
    }
}

internal object TodoSettings {
    val widgetItems = NumberSetting("widget_items", "todo items on the tile", default = 3, min = 1, max = 10)
    val showDone = ToggleSetting("show_done", "show done items on the tile", default = false)

    val all = arrayOf(widgetItems, showDone)
}

internal data class TodoItem(val id: Long, val text: String, val done: Boolean)

/** The list, saved as JSON in private preferences. Main thread only. */
internal class TodoStore(context: Context) {
    private val prefs = context.getSharedPreferences("todo", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(read())
    val items: StateFlow<List<TodoItem>> = _items.asStateFlow()

    fun add(text: String) = write(_items.value + TodoItem(System.currentTimeMillis(), text.trim(), done = false))

    fun toggle(id: Long) = write(_items.value.map { if (it.id == id) it.copy(done = !it.done) else it })

    fun remove(id: Long) = write(_items.value.filterNot { it.id == id })

    fun clearDone() = write(_items.value.filterNot { it.done })

    private fun read(): List<TodoItem> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            TodoItem(o.getLong("id"), o.getString("text"), o.getBoolean("done"))
        }
    } catch (e: JSONException) {
        emptyList()
    }

    private fun write(items: List<TodoItem>) {
        _items.value = items
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "items"
    }
}

/**
 * ```
 * ▕███░░░▏ 2 of 5
 * [ ] buy milk
 * [ ] call mom
 * ```
 */
internal class TodoWidget(
    private val context: Context,
    private val store: TodoStore,
    private val settings: SettingsStore,
    private val pane: TodoPane,
) : HomeWidget {
    override val id = "todo"
    override val order = 50

    override fun render(): Flow<WidgetContent> = combine(
        store.items,
        settings.observe(TodoSettings.widgetItems),
        settings.observe(TodoSettings.showDone),
    ) { items, max, showDone -> content(items, max, showDone) }

    override fun onClick(host: LauncherHost) = host.showPane(pane)

    /** A checklist drawn as art beside how much is done, and the items, each with its checkbox. */
    private fun content(items: List<TodoItem>, max: Int, showDone: Boolean): WidgetContent {
        val done = items.count { it.done }
        val picture = Cell.Art("todo:${done == items.size && items.isNotEmpty()}", Size(PICTURE_COLUMNS, PICTURE_LINES)) {
            checklist(allDone = done == items.size && items.isNotEmpty())
        }
        val dim = context.getColor(CoreR.color.tty_dim)
        if (items.isEmpty()) {
            val headline = SpannableStringBuilder().apply { append("Nothing to do. Add with: todo <text>", ForegroundColorSpan(dim), SPAN) }
            return WidgetContent(headline, layout = tile(picture, headline))
        }
        val headline = "$done of ${items.size} done"
        // Open ones first; done ones after, if wanted. The tile shows as many as fit.
        val shown = items.filterNot { it.done } + if (showDone) items.filter { it.done } else emptyList()
        // Progress first: a bar drawn as art, green as it fills.
        val progress = meter("todo:progress", "", done.toFloat() / items.size, colors = listOf(IconColors.YELLOW, IconColors.GREEN))
        val facts = listOf(progress) + shown.map { item ->
            val text = if (item.done) SpannableStringBuilder().apply { append(item.text, ForegroundColorSpan(dim), SPAN) } else item.text
            fact("todo:${item.done}", text, if (item.done) TICKED else OPEN)
        }
        return WidgetContent(headline, layout = tile(picture, headline, facts, factRows = max + 1, factColumns = ITEM_COLUMNS, pack = false))
    }

    private companion object {
        const val PICTURE_COLUMNS = 6
        const val PICTURE_LINES = 3

        /** Items sit in columns at least this wide, so their text has room. */
        const val ITEM_COLUMNS = 22
        const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        /** A clipboard with three lines, each ticked green, or the last still open. */
        fun checklist(allDone: Boolean) = ShapeIcon {
            fill(IconColors.ORANGE); round(14f, 10f, 86f, 96f, 8f)
            fill(IconColors.WHITE); round(22f, 20f, 78f, 90f, 4f)
            fill(IconColors.GREY); round(36f, 4f, 64f, 18f, 4f)
            for (i in 0 until 3) {
                val y = 34f + i * 20f
                fill(if (i < 2 || allDone) IconColors.GREEN else IconColors.GREY); rect(28f, y, 38f, y + 10f)
                fill(IconColors.DARK); rect(44f, y + 3f, 72f, y + 7f)
            }
        }

        val OPEN = icon { stroke(IconColors.WHITE, 10f); round(18f, 18f, 82f, 82f, 8f) }
        val TICKED = icon {
            fill(IconColors.GREEN); round(12f, 12f, 88f, 88f, 10f)
            stroke(IconColors.DARK, 12f); line(28f, 52f, 44f, 68f); line(44f, 68f, 74f, 32f)
        }
    }
}

/** `todo`: opens the list; `todo <text>`: adds an item. */
internal class TodoCommand(
    private val store: TodoStore,
    private val pane: TodoPane,
) : Command {
    override val name = "todo"
    override val usage = "todo [text]"
    override val summary = "add a todo, or open the list"

    override suspend fun suggest(args: String): List<SearchResult> {
        val text = args.trim()
        if (text.isEmpty()) {
            return listOf(
                SearchResult(
                    key = "todo:open",
                    tag = "todo",
                    title = "todo",
                    detail = "open the list",
                    action = { host ->
                        host.clearQuery()
                        host.showPane(pane)
                    },
                ),
            )
        }
        return listOf(
            SearchResult(
                key = "todo:add",
                tag = "todo",
                title = "add: $text",
                score = Score.EXACT,
                action = { host ->
                    store.add(text)
                    host.clearQuery()
                    host.console.println("Added to the todo list: $text")
                },
            ),
        )
    }
}

/** The whole list: open items first, then done ones. Enter ticks, d deletes, c clears done. */
internal class TodoPane(private val store: TodoStore) : ListPane {
    override val title = "todo"
    override val keysHelp = "⏎ tick  d delete  c clear done  q back  (add: todo <text>)"

    override fun rows(): Flow<List<PaneRow>> = store.items.map { items ->
        val open = items.filterNot { it.done }
        val done = items.filter { it.done }
        buildList {
            add(PaneRow.Header("open (${open.size})"))
            if (open.isEmpty()) add(PaneRow.Item(EMPTY, "Nothing left to do."))
            open.forEach { add(row(it)) }
            if (done.isNotEmpty()) {
                add(PaneRow.Header("done (${done.size})"))
                done.forEach { add(row(it)) }
            }
        }
    }

    override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
        item.id.toLongOrNull()?.let(store::toggle)
        return false
    }

    override fun onKey(item: PaneRow.Item?, event: KeyEvent, host: LauncherHost): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> item?.id?.toLongOrNull()?.let(store::remove)
            KeyEvent.KEYCODE_C -> store.clearDone()
            else -> return false
        }
        return true
    }

    private fun row(item: TodoItem) = PaneRow.Item(
        id = item.id.toString(),
        text = if (item.done) "[x] ${item.text}" else "[ ] ${item.text}",
        status = if (item.done) "done\n⏎ reopen  d delete" else "open\n⏎ done  d delete",
    )

    private companion object {
        const val EMPTY = "todo:empty"
    }
}
