package com.newhost.qwertylauncher.feature.apps

import android.os.Process
import android.view.KeyEvent
import com.newhost.qwertylauncher.core.AppEntry
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.IconRenderer
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.MenuItem
import com.newhost.qwertylauncher.core.PaneRow
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.find
import com.newhost.qwertylauncher.core.placeholderIcon
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext

/** The apps of one type, in label order. */
internal class Section(val type: AppType, val apps: List<AppEntry>)

/** Every app, grouped by [AppType] in the type's order; with [only], just that type. Blocking. */
internal fun sections(apps: List<AppEntry>, types: AppTypes, only: AppType? = null): List<Section> {
    val guessed = types.guess(apps)
    val chosen = types.chosen.value
    val byType = apps.groupBy { chosen[it.key] ?: guessed[it.key] ?: AppType.OTHER }
    return AppType.entries
        .filter { only == null || it == only }
        .mapNotNull { type -> byType[type]?.let { Section(type, it) } }
}

/**
 * Every installed app, sorted into sections by type: calls and messages, web,
 * games, tools... Enter opens an app; m shows its menu (favourites, rename,
 * uninstall); t moves it to another type, if the guess was wrong.
 * ```
 * games (3)
 *  ▀▀ 2048
 *  ▀▀ chess
 * ```
 */
internal class DrawerPane(
    private val catalog: LauncherAppsCatalog,
    private val types: AppTypes,
    private val menu: AppMenu,
    private val services: Services,
) : ListPane {
    /** The one type shown, from `drawer <type>`; null shows all. */
    private val only = MutableStateFlow<AppType?>(null)

    override val title: String get() = only.value?.let { "drawer ${it.id}" } ?: "drawer"
    override val keysHelp = "⏎ open  m menu  t change type  q back"

    /** Apps' icons, drawn once, by [AppEntry.key]. */
    private val icons = ConcurrentHashMap<String, CharSequence>()

    fun show(host: LauncherHost, type: AppType?) {
        only.value = type
        host.clearQuery()
        host.showPane(this)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun rows(): Flow<List<PaneRow>> =
        combine(catalog.apps, types.chosen, only) { apps, _, type -> sections(apps, types, type) }
            .flowOn(Dispatchers.IO)
            // The list straight away, then again as the icons are drawn, a few at a time.
            .transformLatest { sections ->
                emit(rows(sections))
                val renderer = services.find<IconRenderer>() ?: return@transformLatest
                val missing = sections.flatMap { it.apps }.filter { !icons.containsKey(it.key) }
                for (batch in missing.chunked(ICON_BATCH)) {
                    batch.forEach { app -> icons[app.key] = icon(app, renderer) }
                    emit(rows(sections))
                }
            }

    private fun rows(sections: List<Section>): List<PaneRow> {
        if (sections.isEmpty()) {
            val none = only.value?.let { "no ${it.title} apps" } ?: "no apps"
            return listOf(PaneRow.Header("drawer"), PaneRow.Item(NONE, none, status = ""))
        }
        val chosen = types.chosen.value
        val me = Process.myUserHandle()
        return sections.flatMap { section ->
            listOf(PaneRow.Header("${section.type.title} (${section.apps.size})")) + section.apps.map { app ->
                val why = if (app.key in chosen) "${section.type.title}, as you chose" else section.type.title
                PaneRow.Item(
                    id = APP + app.key,
                    text = app.label,
                    detail = "work".takeIf { app.user != me },
                    // The keys are in the pane's own help line; the status says what the row is.
                    status = "${app.label} in $why\n${app.packageName}",
                    icon = icons[app.key],
                )
            }
        }
    }

    /** The app's own icon in half blocks, one line tall; a placeholder with its initial if it has none. */
    private suspend fun icon(app: AppEntry, renderer: IconRenderer): CharSequence {
        val drawable = withContext(Dispatchers.IO) { runCatching { catalog.loadIcon(app) }.getOrNull() }
            ?: placeholderIcon(app.label)()
        return renderer.render(drawable, ICON_COLS, ICON_ROWS, fine = true)
    }

    private fun appOf(item: PaneRow.Item?): AppEntry? =
        item?.id?.takeIf { it.startsWith(APP) }?.let { catalog.find(it.removePrefix(APP)) }

    override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
        val app = appOf(item) ?: return false
        catalog.launch(app, host)
        return true
    }

    override fun onKey(item: PaneRow.Item?, event: KeyEvent, host: LauncherHost): Boolean {
        val app = appOf(item) ?: return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_M -> host.showMenu(app.label, menu.items(app))
            KeyEvent.KEYCODE_T -> chooseType(app, host)
            // The shell drops the keys left over (KeyRouting).
            else -> return false
        }
        return true
    }

    /** A menu of the types to move [app] to, and, once moved, a way back to its own. */
    private fun chooseType(app: AppEntry, host: LauncherHost) {
        val chosen = types.chosen.value[app.key]
        val own = types.guess(catalog.apps.value)[app.key] ?: AppType.OTHER
        val current = chosen ?: own
        val items = buildList {
            if (chosen != null) add(MenuItem("back to ${own.title}, its own type") { types.forget(app) })
            AppType.entries.filter { it != current }.forEach { type ->
                add(MenuItem(type.title) { types.choose(app, type) })
            }
        }
        host.showMenu("move ${app.label} to", items)
    }

    private companion object {
        const val APP = "drawer:app:"
        const val NONE = "drawer:none"
        const val ICON_COLS = 8
        const val ICON_ROWS = 4
        const val ICON_BATCH = 12
    }
}

/** `drawer [type]`: every app, sorted by type; or just one type's. */
internal class DrawerCommand(
    private val catalog: LauncherAppsCatalog,
    private val types: AppTypes,
    private val pane: DrawerPane,
) : Command {
    override val name = "drawer"
    override val usage = "drawer [type]"
    override val summary = "every app, sorted by type: games, tools, web..."

    override suspend fun suggest(args: String): List<SearchResult> {
        val typed = args.trim().lowercase()
        val counts = withContext(Dispatchers.IO) { sections(catalog.apps.value, types) }
            .associate { it.type to it.apps.size }
        val all = SearchResult(
            key = "drawer",
            tag = "app",
            title = "drawer",
            detail = "all ${catalog.apps.value.size} apps, by type",
            score = Score.EXACT,
            completion = "drawer ",
            action = { host -> pane.show(host, null) },
        )
        // Each type that has apps, in the drawer's order, those starting with what's typed.
        val byType = AppType.entries
            .filter { it in counts && (it.id.startsWith(typed) || it.title.startsWith(typed)) }
            .mapIndexed { i, type ->
                SearchResult(
                    key = "drawer:${type.id}",
                    tag = "app",
                    title = "drawer ${type.id}",
                    detail = "${type.title}, ${counts[type]} apps",
                    score = (if (typed.isEmpty()) Score.PREFIX else Score.EXACT) - i,
                    completion = "drawer ${type.id}",
                    action = { host -> pane.show(host, type) },
                )
            }
        return if (typed.isEmpty()) listOf(all) + byType else byType
    }
}
