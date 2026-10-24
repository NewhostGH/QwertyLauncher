package com.newhost.qwertylauncher.feature.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.newhost.qwertylauncher.core.ChoiceSetting
import com.newhost.qwertylauncher.core.ChoiceSetting.Choice
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.MenuItem
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.PaneRow
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.ToggleSetting
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * The phone's files, like a shell: `ls` opens a pane on a folder, to walk
 * about, open files in their app, share, delete and copy paths; `ls <folder>`
 * goes straight there; `find <name>` looks through all of storage. Needs
 * Android's "All files access" for the launcher, asked for in the pane.
 */
class FilesModule : LauncherModule {
    override val id = "files"
    override val title = "files"

    override fun install(scope: ModuleScope) {
        scope.declareSettings(*FilesSettings.all)
        val browser = Browser(scope.appContext)
        val pane = FilesPane(scope.appContext, browser, scope.settings, scope.appScope)
        scope.command(LsCommand(browser, pane))
        scope.command(FindCommand(scope.appContext, browser, pane))
    }
}

internal object FilesSettings {
    val hidden = ToggleSetting("hidden", "show hidden files", default = false, summary = "Files and folders whose names start with a dot.")
    val order = ChoiceSetting(
        "order",
        "sort files by",
        listOf(Choice("name"), Choice("newest", "newest first"), Choice("biggest", "biggest first")),
        default = "name",
    )

    val all = arrayOf(hidden, order)
}

/** Where the pane is: a folder, remembered for next time. */
internal class Browser(context: Context) {
    private val prefs = context.getSharedPreferences("files", Context.MODE_PRIVATE)

    /** Internal storage: `~` in paths. */
    val home: File = Environment.getExternalStorageDirectory()

    val dir = MutableStateFlow(prefs.getString(DIR, null)?.let(::File)?.takeIf { it.isDirectory } ?: home)

    /** Bumped after a change (a delete), to list the folder again. */
    val changes = MutableStateFlow(0)

    fun go(to: File) {
        dir.value = to
        prefs.edit().putString(DIR, to.path).apply()
    }

    fun allowed() = Environment.isExternalStorageManager()

    /** `~/Download` for a file in internal storage; the full path otherwise. */
    fun show(file: File): String {
        val path = file.path
        return when {
            path == home.path -> "~"
            path.startsWith(home.path + "/") -> "~" + path.removePrefix(home.path)
            else -> path
        }
    }

    /** A path as typed: `~/...`, absolute, or relative to the current folder. */
    fun resolve(typed: String): File = when {
        typed == "~" -> home
        typed.startsWith("~/") -> File(home, typed.removePrefix("~/"))
        typed.startsWith("/") -> File(typed)
        else -> File(dir.value, typed)
    }

    /** The usual folders, by name. */
    fun places(): List<Pair<String, File>> = listOf(
        "home" to home,
        "download" to File(home, Environment.DIRECTORY_DOWNLOADS),
        "documents" to File(home, Environment.DIRECTORY_DOCUMENTS),
        "camera" to File(home, Environment.DIRECTORY_DCIM),
        "pictures" to File(home, Environment.DIRECTORY_PICTURES),
        "music" to File(home, Environment.DIRECTORY_MUSIC),
        "movies" to File(home, Environment.DIRECTORY_MOVIES),
    ).filter { it.second.isDirectory }

    /** A folder's entries, folders first, in [order]; hidden ones only if [hidden]. Blocking. */
    fun list(folder: File, hidden: Boolean, order: String): List<File> {
        val entries = folder.listFiles()?.filter { hidden || !it.name.startsWith(".") }.orEmpty()
        val sorted = when (order) {
            "newest" -> entries.sortedByDescending { it.lastModified() }
            "biggest" -> entries.sortedByDescending { if (it.isDirectory) -1 else it.length() }
            else -> entries.sortedBy { it.name.lowercase() }
        }
        return sorted.sortedBy { !it.isDirectory }
    }

    private companion object {
        const val DIR = "dir"
    }
}

/** Handing files to other apps: open with, share. */
internal object Files {
    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun mime(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    fun open(host: LauncherHost, file: File) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(host.context, file), mime(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        host.startActivitySafely(Intent.createChooser(intent, "open ${file.name}"))
    }

    fun share(host: LauncherHost, file: File) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime(file))
            .putExtra(Intent.EXTRA_STREAM, uri(host.context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        host.startActivitySafely(Intent.createChooser(intent, "share ${file.name}"))
    }

    /** `1.2 MB`. */
    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return (if (value < 10) "%.1f %s" else "%.0f %s").format(value, units[unit])
    }

    /** `sep 27`, or `2024 sep 27` for another year, or `14:05` for today. */
    fun date(millis: Long): String {
        val at = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val today = LocalDate.now()
        val pattern = when {
            at.toLocalDate() == today -> "HH:mm"
            at.year == today.year -> "MMM d"
            else -> "yyyy MMM d"
        }
        return at.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault())).lowercase()
    }

    /** How many entries a folder has, cheaply; null if it can't be read. */
    fun count(folder: File): Int? = folder.list()?.size
}

/**
 * A folder as a pane, `ls -l` style: `..` to go up, folders (`name/`)
 * then files with their size and date, then the usual places. Enter opens
 * (a folder, or a file in its app); Esc goes up, and closes at the top.
 */
internal class FilesPane(
    private val context: Context,
    private val browser: Browser,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) : ListPane {
    override val title = "ls"
    override val keysHelp = "⏎ open  s share  d delete  y copy path  h hidden  o sort  esc up"

    private val access = MutableStateFlow(browser.allowed())

    override fun rows(): Flow<List<PaneRow>> = combine(
        browser.dir,
        browser.changes,
        settings.observe(FilesSettings.hidden),
        settings.observe(FilesSettings.order),
        access,
    ) { dir, _, hidden, order, _ -> Listing(dir, hidden, order) }
        .map { listing -> rows(listing) }
        .flowOn(Dispatchers.IO)

    private class Listing(val dir: File, val hidden: Boolean, val order: String)

    private fun rows(l: Listing): List<PaneRow> = buildList {
        // Checked again each time: it may have just been allowed in settings.
        access.value = browser.allowed()
        if (!access.value) {
            add(PaneRow.Header("files"))
            add(
                PaneRow.Item(
                    ALLOW,
                    "allow access to files",
                    status = "⏎ open Android's \"All files access\" switch (needed to see files)",
                ),
            )
            return@buildList
        }
        val entries = browser.list(l.dir, l.hidden, l.order)
        val folders = entries.count { it.isDirectory }
        add(PaneRow.Header("${browser.show(l.dir)}  $folders folders ${entries.size - folders} files"))
        l.dir.parentFile?.takeIf { it.canRead() }?.let { add(PaneRow.Item(UP, "../", status = "⏎ up to ${browser.show(it)}")) }
        if (entries.isEmpty()) add(PaneRow.Item(EMPTY, "(empty)", status = "Nothing here."))
        for (file in entries) {
            val status = "${browser.show(file)}\n" + if (file.isDirectory) {
                "⏎ open  d delete (and all in it)"
            } else {
                "${Files.size(file.length())}  ${Files.mime(file)}  ${Files.date(file.lastModified())}\n⏎ open  s share  d delete"
            }
            add(
                PaneRow.Item(
                    ENTRY + file.path,
                    if (file.isDirectory) file.name + "/" else file.name,
                    detail = if (file.isDirectory) {
                        Files.count(file)?.let { "$it items" } ?: "locked"
                    } else {
                        "%8s  %s".format(Files.size(file.length()), Files.date(file.lastModified()))
                    },
                    status = status,
                ),
            )
        }
        add(PaneRow.Header("places"))
        for ((name, folder) in browser.places()) {
            add(PaneRow.Item(PLACE + folder.path, name, detail = browser.show(folder), status = "⏎ go to ${browser.show(folder)}"))
        }
    }

    private fun fileOf(item: PaneRow.Item?): File? = item?.id?.takeIf { it.startsWith(ENTRY) }?.removePrefix(ENTRY)?.let(::File)

    override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
        when {
            item.id == ALLOW -> {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                if (!host.startActivitySafely(intent)) host.startActivitySafely(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            item.id == UP -> browser.dir.value.parentFile?.let { browser.go(it) }
            item.id.startsWith(PLACE) -> browser.go(File(item.id.removePrefix(PLACE)))
            else -> {
                val file = fileOf(item) ?: return false
                if (file.isDirectory) browser.go(file) else Files.open(host, file)
            }
        }
        return false
    }

    override fun onKey(item: PaneRow.Item?, event: KeyEvent, host: LauncherHost): Boolean {
        val file = fileOf(item)
        when (event.keyCode) {
            KeyEvent.KEYCODE_S -> if (file != null && file.isFile) Files.share(host, file)
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_FORWARD_DEL -> file?.let { confirmDelete(host, it) }
            KeyEvent.KEYCODE_Y -> {
                val path = (file ?: browser.dir.value).path
                host.context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("path", path))
                host.console.println("Copied $path")
            }
            KeyEvent.KEYCODE_H -> settings.set(FilesSettings.hidden, !settings[FilesSettings.hidden])
            KeyEvent.KEYCODE_O -> {
                val choices = FilesSettings.order.options.map { it.value }
                val next = choices[(choices.indexOf(settings[FilesSettings.order]) + 1) % choices.size]
                settings.set(FilesSettings.order, next)
            }
            KeyEvent.KEYCODE_DEL -> browser.dir.value.parentFile?.takeIf { it.canRead() }?.let { browser.go(it) }
            else -> return false
        }
        return true
    }

    /** Asks first: a menu with the file's name, and what goes with it. */
    private fun confirmDelete(host: LauncherHost, file: File) {
        val what = if (file.isDirectory) "${file.name}/ and all in it" else file.name
        val console = host.console
        host.showMenu(
            "delete $what?",
            listOf(
                MenuItem("yes, delete it") {
                    scope.launch {
                        val gone = withContext(Dispatchers.IO) { file.deleteRecursively() }
                        console.println(if (gone) "Deleted ${browser.show(file)}" else "Couldn't delete all of ${browser.show(file)}")
                        browser.changes.value++
                    }
                },
                MenuItem("no, keep it") { },
            ),
        )
    }

    /** Esc goes up a folder; at internal storage (or the top), it closes. */
    override fun onBack(host: LauncherHost): Boolean {
        val dir = browser.dir.value
        val parent = dir.parentFile
        if (dir.path == browser.home.path || parent == null || !parent.canRead()) return false
        browser.go(parent)
        return true
    }

    private companion object {
        const val ALLOW = "files:allow"
        const val UP = "files:up"
        const val EMPTY = "files:empty"
        const val ENTRY = "file:"
        const val PLACE = "place:"
    }
}

/** `ls` opens the files pane; `ls <folder>` goes there, a place (`ls download`) or a path, completed with tab. */
internal class LsCommand(private val browser: Browser, private val pane: FilesPane) : Command {
    override val name = "ls"
    override val aliases = listOf("files", "cd")
    override val usage = "ls [folder]"
    override val summary = "your files: a folder to walk about, open, share and delete in"

    override suspend fun suggest(args: String): List<SearchResult> {
        val typed = args.trim()
        if (typed.isEmpty()) {
            return listOf(open("files:here", "ls ${browser.show(browser.dir.value)}", browser.dir.value, Score.EXACT))
        }
        return withContext(Dispatchers.IO) {
            buildList {
                browser.places().filter { it.first.startsWith(typed.lowercase()) }.forEach { (place, folder) ->
                    add(open("files:place:$place", "ls ${browser.show(folder)}", folder, Score.EXACT, place))
                }
                // A path: the folder typed so far, and those in it starting with the rest.
                val path = browser.resolve(typed)
                if (path.isDirectory) add(open("files:path", "ls ${browser.show(path)}", path, Score.EXACT - 1))
                val parent = if (typed.endsWith("/")) path else path.parentFile
                val prefix = if (typed.endsWith("/")) "" else path.name
                parent?.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith(prefix, ignoreCase = true) && !it.name.startsWith(".") }
                    ?.sortedBy { it.name.lowercase() }
                    ?.take(MAX_SUGGESTIONS)
                    ?.forEachIndexed { i, folder ->
                        add(open("files:dir:${folder.path}", "ls ${browser.show(folder)}/", folder, Score.PREFIX - i))
                    }
            }.distinctBy { it.key }
        }
    }

    private fun open(key: String, title: String, folder: File, score: Int, detail: String? = null) = SearchResult(
        key = key,
        tag = "ls",
        title = title,
        detail = detail,
        score = score,
        completion = "ls ${browser.show(folder)}/",
        action = { host ->
            host.clearQuery()
            browser.go(folder)
            host.showPane(pane)
        },
    )

    private companion object {
        const val MAX_SUGGESTIONS = 12
    }
}

/** `find <name>`: files and folders in storage whose names hold [name]; picking one opens it (or its folder in the pane). */
internal class FindCommand(private val context: Context, private val browser: Browser, private val pane: FilesPane) : Command {
    override val name = "find"
    override val usage = "find <name>"
    override val summary = "find files by name, in all of storage"

    override suspend fun suggest(args: String): List<SearchResult> {
        val name = args.trim()
        if (name.length < MIN_NAME) return emptyList()
        if (!browser.allowed()) {
            return listOf(
                SearchResult(key = "find:allow", tag = "find", title = "find needs access to files", detail = "type ls to allow it", action = {}),
            )
        }
        val found = withContext(Dispatchers.IO) { search(name) }
        return found.mapIndexed { i, file ->
            SearchResult(
                key = "find:${file.path}",
                tag = "find",
                title = if (file.isDirectory) file.name + "/" else file.name,
                detail = browser.show(file.parentFile ?: file),
                score = Score.EXACT - i,
                action = { host ->
                    host.clearQuery()
                    if (file.isDirectory) {
                        browser.go(file)
                        host.showPane(pane)
                    } else {
                        Files.open(host, file)
                    }
                },
                menu = listOf(
                    MenuItem("show in its folder") { host ->
                        host.clearQuery()
                        file.parentFile?.let { browser.go(it) }
                        host.showPane(pane)
                    },
                    MenuItem("share") { host -> if (file.isFile) Files.share(host, file) },
                ),
            )
        }
    }

    /** Walks storage, skipping hidden folders and Android's app data, up to [MAX_FILES] looked at. */
    private suspend fun search(name: String): List<File> {
        val found = mutableListOf<File>()
        var looked = 0
        val queue = ArrayDeque(listOf(browser.home))
        while (queue.isNotEmpty() && looked < MAX_FILES && found.size < MAX_FOUND) {
            coroutineContext.ensureActive()
            val dir = queue.removeFirst()
            for (file in dir.listFiles().orEmpty()) {
                looked++
                if (file.name.startsWith(".")) continue
                if (file.name.contains(name, ignoreCase = true)) found += file
                if (file.isDirectory && file.path != SKIPPED) queue.addLast(file)
            }
        }
        // Closest names first: starting with it, then shortest.
        return found.sortedWith(compareBy<File>({ !it.name.startsWith(name, ignoreCase = true) }, { it.name.length }))
    }

    private companion object {
        const val MIN_NAME = 2
        const val MAX_FILES = 20_000
        const val MAX_FOUND = 30
        val SKIPPED = Environment.getExternalStorageDirectory().path + "/Android"
    }
}
