package com.newhost.qwertylauncher.shell

import android.net.Uri
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.Query
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchProvider
import com.newhost.qwertylauncher.core.SearchResult

/**
 * Routes `name args` to the matching [Command], and offers command names as
 * completions while the first word is still being typed.
 */
internal class CommandSearchProvider(private val commands: () -> List<Command>) : SearchProvider {
    override val id = "commands"

    override suspend fun search(query: Query): List<SearchResult> {
        val head = query.head
        val exact = commands().firstOrNull { head == it.name || head in it.aliases }
        if (exact != null) {
            // Typing a command name is a strong signal: rank its suggestions above everything else.
            return exact.suggest(query.tail).map { it.copy(score = it.score + Score.COMMAND) }
        }
        if (' ' in query.text) return emptyList()
        return commands()
            .filter { it.name.startsWith(head) }
            .map { command ->
                SearchResult(
                    key = "cmd:${command.name}",
                    tag = "cmd",
                    title = command.usage,
                    detail = command.summary,
                    score = Score.WORD,
                    completion = "${command.name} ",
                    action = { host -> host.setQuery("${command.name} ") },
                )
            }
    }
}

/** What shell commands need from the home screen beyond [com.newhost.qwertylauncher.core.LauncherHost]. */
interface ShellHost {
    fun openHelp()

    /** Lets the user pick a file of one of [types]; [onPicked] gets it, if they did. */
    fun pickFile(types: Array<String>, onPicked: (Uri) -> Unit)

    /** Starts the launcher afresh, in a new process: how modules load again. */
    fun restart()
}

/** `help`: opens the help pane, listing every command and key. */
internal class HelpCommand(private val commands: () -> List<Command>) : Command {
    override val name = "help"
    override val usage = "help"
    override val summary = "all commands and keys"

    override suspend fun suggest(args: String) = listOf(
        SearchResult(
            key = "help",
            tag = "cmd",
            title = "help",
            detail = summary,
            action = { host ->
                host.clearQuery()
                val shell = host as? ShellHost
                if (shell != null) {
                    shell.openHelp()
                } else {
                    commands().sortedBy { it.name }.forEach { command ->
                        host.console.println("${command.usage.padEnd(USAGE_WIDTH)} ${command.summary}")
                    }
                }
            },
        ),
    )

    private companion object {
        const val USAGE_WIDTH = 24
    }
}

internal class ClearCommand : Command {
    override val name = "clear"
    override val aliases = listOf("cls")
    override val usage = "clear"
    override val summary = "clear the console"

    override suspend fun suggest(args: String) = listOf(
        SearchResult(
            key = "clear",
            tag = "cmd",
            title = "clear",
            detail = summary,
            action = { host ->
                host.clearQuery()
                host.console.clear()
            },
        ),
    )
}
