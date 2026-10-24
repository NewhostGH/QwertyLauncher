package com.newhost.qwertylauncher.shell

import android.net.Uri
import android.provider.OpenableColumns
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.MenuItem
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.settings.SettingsFile
import com.newhost.qwertylauncher.settings.SettingsTransfer
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `config`: opens config. `config export` saves the settings to a file the
 * user chooses, and `config import` loads them from one, after saying what
 * it would change; importing restarts the launcher.
 */
internal class ConfigCommand(private val transfer: SettingsTransfer) : Command {
    override val name = "config"
    override val usage = "config [export|import]"
    override val summary = "settings; save them to a file, or load them from one"

    override suspend fun suggest(args: String): List<SearchResult> {
        val typed = args.trim().lowercase()
        val open = SearchResult(
            key = "config",
            tag = "cmd",
            title = "config",
            detail = "open the settings",
            score = Score.EXACT,
            action = { host ->
                host.clearQuery()
                (host as? ShellHost)?.openConfig()
            },
        )
        val export = SearchResult(
            key = "config:export",
            tag = "cmd",
            title = "config export",
            detail = "save your settings to a file",
            score = Score.EXACT - 1,
            completion = "config export",
            action = { host -> export(host) },
        )
        val import = SearchResult(
            key = "config:import",
            tag = "cmd",
            title = "config import",
            detail = "load settings from a file; restarts",
            score = Score.EXACT - 2,
            completion = "config import",
            action = { host -> pick(host) },
        )
        return listOfNotNull(
            open.takeIf { typed.isEmpty() },
            export.takeIf { "export".startsWith(typed) },
            import.takeIf { "import".startsWith(typed) },
        )
    }

    private fun export(host: LauncherHost) {
        host.clearQuery()
        val shell = host as? ShellHost ?: return
        shell.createJsonFile("qwertylauncher-settings-${LocalDate.now()}.json") { uri ->
            host.scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val json = transfer.export().toJson()
                        val out = host.context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("can't open it")
                        out.bufferedWriter().use { it.write(json) }
                    }
                } catch (e: IOException) {
                    host.console.println("config export: couldn't save it: ${e.message}")
                    return@launch
                }
                host.console.println(
                    "config export: saved your settings to ${displayName(host, uri)}. " +
                        "AI keys aren't in it: enter them again after importing on another phone.",
                )
            }
        }
    }

    private fun pick(host: LauncherHost) {
        host.clearQuery()
        val shell = host as? ShellHost ?: return
        shell.pickFile(FILE_TYPES) { uri ->
            host.scope.launch {
                val file = try {
                    withContext(Dispatchers.IO) { SettingsFile.parse(read(host, uri)) }
                } catch (e: IOException) {
                    host.console.println("config import: can't use it: ${e.message}")
                    return@launch
                }
                val changes = transfer.changes(file)
                if (changes == null) {
                    host.console.println("config import: that file has the settings you have now; nothing to do.")
                    return@launch
                }
                val from = listOfNotNull(
                    file.exported.takeIf { it.isNotEmpty() }?.let { "saved $it" },
                    file.app.takeIf { it.isNotEmpty() }?.let { "by QwertyLauncher $it" },
                ).joinToString(" ")
                val misfits = transfer.misfits(file).takeIf { it > 0 }
                    ?.let { " $it values are the wrong kind for their setting and will be left out." }.orEmpty()
                host.console.println(
                    "config import: settings ${from.ifEmpty { "from a file" }}. Importing changes $changes. " +
                        "It replaces your settings and restarts the launcher.$misfits",
                )
                host.showMenu(
                    "import these settings?",
                    listOf(
                        MenuItem("import and restart") { h -> import(h, file) },
                        MenuItem("cancel") { h -> h.console.println("config import: cancelled") },
                    ),
                )
            }
        }
    }

    private fun import(host: LauncherHost, file: SettingsFile) {
        host.scope.launch {
            withContext(Dispatchers.IO) { transfer.apply(file) }
            (host as? ShellHost)?.restart()
        }
    }

    /** The file's text; a settings file is small, so a big one is refused. */
    private fun read(host: LauncherHost, uri: Uri): String {
        val input = host.context.contentResolver.openInputStream(uri) ?: throw IOException("can't open it")
        val bytes = input.use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes.size > MAX_BYTES) throw IOException("it's too big to be a settings file")
        return bytes.decodeToString()
    }

    private fun displayName(host: LauncherHost, uri: Uri): String =
        host.context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "the file you chose"

    private companion object {
        const val MAX_BYTES = 1024 * 1024
        val FILE_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")
    }
}
