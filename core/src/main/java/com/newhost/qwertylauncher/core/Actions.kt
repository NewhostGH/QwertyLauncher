package com.newhost.qwertylauncher.core

/**
 * What the launcher can do, for the AI (or any module) to act with, the
 * way the user would: its commands, and what a line typed at the prompt
 * would offer. Offered as a service by the shell. Nothing runs by itself:
 * a preview's action runs only when its [SearchResult.action] is run.
 */
interface LauncherActions {
    /** Every command there is now: its usage (`timer <5m|1h30m|90s>`) and what it does. */
    val commands: List<Pair<String, String>>

    /**
     * What typing [line] at the prompt would offer first (a command's
     * result, an app, a contact...), or null if nothing.
     */
    suspend fun preview(line: String): SearchResult?
}
