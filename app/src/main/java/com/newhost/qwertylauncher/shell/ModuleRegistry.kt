package com.newhost.qwertylauncher.shell

import android.content.Context
import com.newhost.qwertylauncher.core.Actor
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.Query
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.LauncherActions
import com.newhost.qwertylauncher.core.BarStrip
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.DebugLog
import com.newhost.qwertylauncher.core.Happenings
import com.newhost.qwertylauncher.core.HomePanel
import com.newhost.qwertylauncher.core.HomeWidget
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.SearchProvider
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.Setting
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.TileSize
import com.newhost.qwertylauncher.core.ToggleSetting
import com.newhost.qwertylauncher.debug.DebugCommand
import com.newhost.qwertylauncher.debug.DebugPane
import com.newhost.qwertylauncher.debug.LauncherLog
import com.newhost.qwertylauncher.settings.DataStoreSettings
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One heading and its settings in config. Module sections have an [enabled]
 * switch; the shell's own sections (general, layout) have none.
 */
class SettingsSection(
    val moduleId: String,
    val title: String,
    val enabled: ToggleSetting?,
    val settings: List<Setting<*>>,
    /** Its tiles' sizes, apart from its own settings. */
    val tiles: List<Setting<*>> = emptyList(),
    /** The heading it comes under on config's first page. */
    val group: String = "other",
)

/**
 * A tile on home: a text [widget] or an interactive [panel] (one of the two).
 * [columns] and [lines] are the user's size, 0 for automatic; [wide] is the
 * module's default width, used while [columns] is automatic.
 */
class Tile(
    val id: String,
    val title: String,
    val wide: Boolean,
    val columns: Int,
    val lines: Int,
    /** The smallest the tile can be made. */
    val minColumns: Int,
    val minLines: Int,
    val widget: HomeWidget? = null,
    val panel: HomePanel? = null,
)

/** What a tile is made from, before the user's settings apply. */
private class TileSource(
    val id: String,
    val title: String,
    val order: Int,
    val size: TileSize,
    val minColumns: Int,
    val minLines: Int,
    val widget: HomeWidget?,
    val panel: HomePanel?,
) {
    companion object {
        fun of(widget: HomeWidget) =
            TileSource(widget.id, widget.title, widget.order, widget.size, widget.minColumns, widget.minLines, widget, null)

        fun of(panel: HomePanel) =
            TileSource(panel.id, panel.title, panel.order, panel.size, panel.minColumns, panel.minLines, null, panel)
    }
}

/**
 * Installs [modules] (loaded by [ModuleLoader]) and holds what they contributed, grouped by module so a
 * module switched off in settings disappears as a whole. A module that throws
 * while installing is skipped entirely, so one broken feature can't take the
 * home screen down with it.
 */
class ModuleRegistry(
    context: Context,
    modules: List<LauncherModule>,
    private val settings: DataStoreSettings,
    private val launcherLog: LauncherLog,
    loader: ModuleLoader,
) : Services {

    /** The shell's own channel of the debug log. */
    val log: DebugLog = launcherLog.tagged("shell")

    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> log.e("uncaught error in a module coroutine", e) },
    )

    private class Contributions(val section: SettingsSection) {
        val providers = mutableListOf<SearchProvider>()
        val commands = mutableListOf<Command>()
        val widgets = mutableListOf<HomeWidget>()
        val panels = mutableListOf<HomePanel>()
        val strips = mutableListOf<BarStrip>()
        val actors = mutableListOf<Actor>()
    }

    private val installed = mutableListOf<Contributions>()
    /** Service type → the module offering it, and the implementation. */
    private val serviceMap = mutableMapOf<KClass<*>, Pair<SettingsSection, Any>>()

    /**
     * News modules share for others to react to; the shell's, so it's there
     * whichever modules are on. Made before modules install: they may post straight away.
     */
    private val happenings = HappeningsHub()

    // The shell's own pieces: always on.
    private val shellCommands = listOf(
        HelpCommand { commands },
        ClearCommand(),
        DebugCommand(DebugPane(context.applicationContext, launcherLog)),
        ModuleCommand(loader),
    )
    private val shellProviders = listOf(CommandSearchProvider { commands })

    /** Per-tile layout overrides by tile id, for every installed widget and panel and the shell's own. */
    private val tileSettings = mutableMapOf<String, TileSettings>()
    private val tileOrder = TileOrder(context.applicationContext)


    /** Tiles of modules that are on: their widgets and panels. */
    private fun activeSources(): List<TileSource> =
        active().flatMap { c -> c.widgets.map { TileSource.of(it) } + c.panels.map { TileSource.of(it) } }

    /**
     * Config's sections: general first, then one per module, its own settings
     * followed by the size of each of its tiles. A module that's off shows only
     * its switch; turning it off is also how its tiles are hidden.
     */
    val sections: List<SettingsSection>
        get() = listOf(
            SettingsSection("$GENERAL.text", "text and prompt", null, ShellSettings.textAndPrompt, group = LAUNCHER),
            SettingsSection("$GENERAL.frames", "frames", null, ShellSettings.frames, group = LAUNCHER),
            SettingsSection("$GENERAL.bar", "top bar and start", null, ShellSettings.barAndStart, group = LAUNCHER),
        ) + installed
            .map { c ->
                val tiles = (c.widgets.map { it.id } + c.panels.map { it.id }).flatMap { tileSettings.getValue(it).all }
                c.section.run { SettingsSection(moduleId, title, enabled, settings, tiles, groupOf(moduleId)) }
            }
            // By heading, then in the order that heading lists; anything unlisted last, by name.
            .sortedWith(compareBy({ place(it.moduleId).first }, { place(it.moduleId).second }, { it.title }))

    /** Where a module goes on config's first page: its heading's place, and its place under it. */
    private fun place(moduleId: String): Pair<Int, Int> {
        GROUPS.forEachIndexed { g, (_, ids) -> ids.indexOf(moduleId).takeIf { it >= 0 }?.let { return g to it } }
        return GROUPS.size to 0
    }

    private fun groupOf(moduleId: String) = GROUPS.firstOrNull { moduleId in it.second }?.first ?: "other"

    val searchProviders: List<SearchProvider> get() = active().flatMap { it.providers } + shellProviders
    val commands: List<Command> get() = active().flatMap { it.commands } + shellCommands

    /** The top bar's quick-button strips, of modules that are on. */
    val barStrips: List<BarStrip> get() = active().flatMap { it.strips }

    /** The characters living on home, of modules that are on. */
    val actors: List<Actor> get() = active().flatMap { it.actors }

    /** Home's tiles: widgets and panels of modules that are on, in the user's order and sizes. */
    val tiles: List<Tile>
        get() = tileOrder.sort(activeSources(), { it.id }, { it.order })
            .map { it to tileSettings.getValue(it.id) }
            // Hidden tiles stay out; their modules stay on.
            .filter { (_, tile) -> settings[tile.shown] }
            .map { (source, tile) ->
                Tile(
                    source.id,
                    source.title,
                    wide = source.size == TileSize.WIDE,
                    columns = settings[tile.width],
                    lines = settings[tile.height],
                    minColumns = source.minColumns,
                    minLines = source.minLines,
                    widget = source.widget,
                    panel = source.panel,
                )
            }

    // Arranging tiles: hold enter on one on the home screen.

    /** Saves the tiles' order after the user moved one. */
    fun saveTileOrder(ids: List<String>) = tileOrder.save(ids)

    /** Saves a tile's size in cells; 0 for either means automatic. */
    fun setTileSize(id: String, columns: Int, lines: Int) {
        val tile = tileSettings.getValue(id)
        settings.set(tile.width, columns.coerceIn(TileSettings.AUTO, TileSettings.MAX_COLUMNS))
        settings.set(tile.height, lines.coerceIn(TileSettings.AUTO, TileSettings.MAX_LINES))
    }

    /** The module a tile comes from, or null if it's gone. */
    fun tileModule(id: String): SettingsSection? =
        installed.firstOrNull { c -> c.widgets.any { it.id == id } || c.panels.any { it.id == id } }?.section

    /** Hides a tile from home: just it; its module stays on. */
    fun hideTile(id: String) {
        tileSettings[id]?.let { settings.set(it.shown, false) }
    }

    /** Forgets the arranged order and sizes, and shows hidden tiles again; modules turned off stay off. */
    fun resetTileLayout() {
        tileOrder.reset()
        tileSettings.values.forEach {
            settings.set(it.shown, true)
            settings.set(it.width, TileSettings.AUTO)
            settings.set(it.height, TileSettings.AUTO)
        }
    }

    /**
     * Everything home is built from: modules on, which tiles show, and the
     * general settings that shape the layout. A change means rebuilding home.
     * Not the tiles' order or sizes: those change in place.
     */
    fun homeLayoutKey(): String = buildString {
        append(active().joinToString(",") { it.section.moduleId })
        append('|').append(tiles.map { it.id }.sorted().joinToString(","))
        ShellSettings.layoutAffecting.forEach { append('|').append(settings[it]) }
    }

    /** Emits whenever [homeLayoutKey] changes. */
    val homeLayoutChanges: Flow<String> =
        settings.snapshot.map { homeLayoutKey() }.distinctUntilChanged()

    init {
        ShellSettings.all.forEach { settings.declare(GENERAL, it) }
        val appContext = context.applicationContext
        for (module in modules) {
            val installer = Installer(appContext, module)
            try {
                module.install(installer)
            } catch (e: RuntimeException) {
                log.e("module '${module.id}' failed to install; skipping it", e)
                continue
            } catch (e: LinkageError) {
                // Built against something this launcher doesn't have.
                log.e("module '${module.id}' failed to install; skipping it", e)
                continue
            }
            installer.commit()
        }
        val sources =
            installed.flatMap { c -> c.widgets.map { TileSource.of(it) } + c.panels.map { TileSource.of(it) } }
        for (source in sources) {
            val tile = TileSettings(source.id, source.title)
            // Stored under "layout" as before, so sizes set earlier are kept; shown in the module's section.
            tile.all.forEach { settings.declare(LAYOUT, it) }
            tileSettings[source.id] = tile
        }
    }

    /** Modules that are switched on. */
    private fun active() = installed.filter { section -> section.section.enabled?.let { settings[it] } ?: true }

    /** What the launcher can do, as the AI may use it: every command, and what a line at the prompt offers first. */
    private val actions = object : LauncherActions {
        private val engine = SearchEngine({ searchProviders }, launcherLog.tagged("actions"))

        override val commands: List<Pair<String, String>>
            get() = commands().map { it.usage to it.summary }

        override suspend fun preview(line: String): SearchResult? =
            // Not the AI's own offers (asking it), which would only go round in circles; nor the
            // last-resort web search, which matches anything.
            engine.search(Query(line)).firstOrNull { !it.key.startsWith("ai:") && it.score > Score.FALLBACK }
    }

    private fun commands() = this.commands

    /** A switched-off module's services are hidden too, so "off" really means off. The shell's own are always there. */
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> find(type: KClass<T>): T? {
        if (type == LauncherActions::class) return actions as T
        if (type == Happenings::class) return happenings as T
        val (owner, impl) = serviceMap[type] ?: return null
        val on = owner.enabled?.let { settings[it] } ?: true
        return if (on) impl as T else null
    }

    /** Buffers one module's contributions so a failed install leaves nothing behind. */
    private inner class Installer(
        override val appContext: Context,
        private val module: LauncherModule,
    ) : ModuleScope {
        private val enabled = ToggleSetting(
            "enabled",
            "${module.title} module on",
            default = true,
            summary = "Off turns all of ${module.title} off: its tiles, commands, search and anything it offers. " +
                "To hide just a tile, use its own switch under tile.",
        )
        private val declared = mutableListOf<Setting<*>>()
        private val contributions = Contributions(SettingsSection(module.id, module.title, enabled, declared))
        private val offered = mutableMapOf<KClass<*>, Any>()

        init {
            this@ModuleRegistry.settings.declare(module.id, enabled)
        }

        override val appScope: CoroutineScope get() = this@ModuleRegistry.appScope
        override val services: Services get() = this@ModuleRegistry
        override val settings: SettingsStore get() = this@ModuleRegistry.settings
        override val log: DebugLog = launcherLog.tagged(module.id)

        override fun declareSettings(vararg settings: Setting<*>) {
            for (setting in settings) {
                this@ModuleRegistry.settings.declare(module.id, setting)
                declared += setting
            }
        }

        override fun searchProvider(provider: SearchProvider) { contributions.providers += provider }
        override fun command(command: Command) { contributions.commands += command }
        override fun widget(widget: HomeWidget) { contributions.widgets += widget }
        override fun panel(panel: HomePanel) { contributions.panels += panel }
        override fun barStrip(strip: BarStrip) { contributions.strips += strip }
        override fun actor(actor: Actor) { contributions.actors += actor }
        override fun <T : Any> service(type: KClass<T>, impl: T) { offered[type] = impl }

        fun commit() {
            installed += contributions
            for ((type, impl) in offered) serviceMap[type] = contributions.section to impl
        }
    }

    private companion object {
        const val GENERAL = "general"
        const val LAUNCHER = "launcher"

        /**
         * Config's first page: headings, each with its modules in a sensible
         * order (things that go together, together), after general's own sections.
         */
        val GROUPS = listOf(
            LAUNCHER to listOf("asciiicons", "toggles", "herald"),
            "home tiles" to listOf(
                "clock", "alarm", "weather", "sky", "calendar", "todo", "notifications", "music",
                "battery", "system", "network", "sensors", "news", "favorites", "suggest", "games",
            ),
            "search and commands" to listOf("apps", "contacts", "commands", "files", "ai"),
        )
        const val LAYOUT = "layout"
    }
}
