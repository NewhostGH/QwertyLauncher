package com.newhost.qwertylauncher.feature.asciiicons

import com.newhost.qwertylauncher.core.AppCatalog
import com.newhost.qwertylauncher.core.ArtAnimator
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.Fuzzy
import com.newhost.qwertylauncher.core.IconRenderer
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.find
import com.newhost.qwertylauncher.core.service
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Renders app icons as ASCII art for every module that shows icons. */
class AsciiIconsModule : LauncherModule {
    override val id = "asciiicons"
    override val title = "ascii art"

    override fun install(scope: ModuleScope) {
        scope.declareSettings(*AsciiSettings.all)
        val renderer = AsciiIconRenderer(scope.settings)
        scope.service<IconRenderer>(renderer)
        val animator = TextArtAnimator(scope.settings)
        scope.service<ArtAnimator>(animator)
        scope.command(AsciiCommand(renderer, animator, scope.services))
    }
}

/** `ascii <app>`: prints a large rendering of an app's icon to the console. */
internal class AsciiCommand(
    private val renderer: AsciiIconRenderer,
    private val animator: ArtAnimator,
    private val services: Services,
) : Command {
    override val name = "ascii"
    override val usage = "ascii <app>"
    override val summary = "print an app icon as ASCII art"

    override suspend fun suggest(args: String): List<SearchResult> {
        val catalog = services.find<AppCatalog>() ?: return emptyList()
        if (args.isBlank()) return emptyList()
        return catalog.apps.value
            .mapNotNull { app -> Fuzzy.score(args, app.label).takeIf { it > 0 }?.let { app to it } }
            .sortedByDescending { it.second }
            .take(MAX_RESULTS)
            .map { (app, score) ->
                SearchResult(
                    key = "ascii:${app.key}",
                    tag = "ascii",
                    title = "ascii ${app.label}",
                    score = score,
                    action = { host ->
                        host.clearQuery()
                        host.openPrompt() // keeps the art in view
                        val console = host.console
                        host.scope.launch {
                            val icon = withContext(Dispatchers.IO) { catalog.loadIcon(app) }
                                ?: return@launch console.println("no icon")
                            // Printed on one line that draws itself in.
                            val line = console.newLine()
                            animator.drawIn(renderer.render(icon, LARGE_COLS, LARGE_ROWS)).collect { line.set(it) }
                        }
                    },
                )
            }
    }

    private companion object {
        const val MAX_RESULTS = 6
        const val LARGE_COLS = 32
        const val LARGE_ROWS = 16
    }
}
