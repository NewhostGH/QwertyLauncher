package com.newhost.qwertylauncher.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.ListPane
import com.newhost.qwertylauncher.core.PaneRow
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.ui.InputMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The debug log, full screen and newest first. y copies it all (with a device
 * header), ⏎ copies one line, c clears, i switches logging of raw input
 * events (keys, scrolling, touch) on and off.
 */
internal class DebugPane(
    private val context: Context,
    private val log: LauncherLog,
) : ListPane {
    override val title = "debug"
    override val keysHelp = "⏎ copy line  y copy all  c clear  i log input  q back"

    override fun rows(): Flow<List<PaneRow>> = combine(log.lines, InputMonitor.changes) { lines, input ->
        val header = PaneRow.Header("log, ${lines.size} lines, input events ${if (input) "on" else "off"}")
        if (lines.isEmpty()) {
            listOf(header, PaneRow.Item(EMPTY, "nothing logged yet"))
        } else {
            // Newest first. Ids count from the oldest line, so they stay put as new lines arrive.
            listOf(header) + lines.indices.reversed().map { index ->
                PaneRow.Item(id = "log:$index", text = lines[index], status = lines[index])
            }
        }
    }

    override fun onOpen(item: PaneRow.Item, host: LauncherHost): Boolean {
        if (item.id != EMPTY) copy(item.text.toString(), "log line")
        return false
    }

    override fun onKey(item: PaneRow.Item?, event: KeyEvent, host: LauncherHost): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_Y -> copy((deviceHeader() + log.lines.value).joinToString("\n"), "debug log")
            KeyEvent.KEYCODE_C -> log.clear()
            KeyEvent.KEYCODE_I -> InputMonitor.enabled = !InputMonitor.enabled
            else -> return false
        }
        return true
    }

    private fun deviceHeader(): List<String> {
        val version = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        return listOf(
            "QwertyLauncher $version debug log",
            "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
    }

    private fun copy(text: String, label: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        // The pane shows the log, so the confirmation appears right away.
        log.tagged("debug").d("copied $label (${text.lines().size} lines)")
    }

    private companion object {
        const val EMPTY = "log:empty"
    }
}

/** `debug`: opens the debug log. */
internal class DebugCommand(private val pane: DebugPane) : Command {
    override val name = "debug"
    override val aliases = listOf("log")
    override val usage = "debug"
    override val summary = "the launcher's debug log"

    override suspend fun suggest(args: String) = listOf(
        SearchResult(
            key = "debug",
            tag = "cmd",
            title = "debug",
            detail = summary,
            action = { host ->
                host.clearQuery()
                host.showPane(pane)
            },
        ),
    )
}
