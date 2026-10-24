package com.newhost.qwertylauncher.shell

import android.content.Context
import android.os.Build
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What was run at the prompt, newest first, like a shell's history file; and
 * the last login time for the boot banner. Main thread only.
 */
internal class ShellHistory(context: Context) {

    private val prefs = context.getSharedPreferences("shell", Context.MODE_PRIVATE)
    private val entries = ArrayDeque(prefs.getString(KEY_HISTORY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty())

    val size: Int get() = entries.size

    /** [index] 0 is the most recent. */
    operator fun get(index: Int): String? = entries.getOrNull(index)

    fun add(line: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        entries.remove(text)
        entries.addFirst(text)
        while (entries.size > MAX) entries.removeLast()
        prefs.edit().putString(KEY_HISTORY, entries.joinToString("\n")).apply()
    }

    /**
     * The boot banner, getty style, and records this login for the next one:
     * ```
     * QwertyLauncher 1.0 tty1
     *
     * titan-2 login: qwerty (automatic login)
     * Last login: Thu Sep 25 21:40 on tty1
     * Type help and press enter to see the commands.
     * ```
     */
    fun bootBanner(version: String, host: String): List<String> {
        val format = DateTimeFormatter.ofPattern("EEE MMM d HH:mm", Locale.ENGLISH)
        val last = prefs.getString(KEY_LAST_LOGIN, null)
        prefs.edit().putString(KEY_LAST_LOGIN, LocalDateTime.now().format(format)).apply()
        return listOfNotNull(
            "QwertyLauncher $version tty1 (Android ${Build.VERSION.RELEASE})",
            "",
            "$host login: $USER (automatic login)",
            last?.let { "Last login: $it on tty1" },
            "help ⏎ lists the commands",
        )
    }

    companion object {
        const val USER = "qwerty"
        private const val KEY_HISTORY = "history"
        private const val KEY_LAST_LOGIN = "last_login"
        private const val MAX = 100
    }
}
