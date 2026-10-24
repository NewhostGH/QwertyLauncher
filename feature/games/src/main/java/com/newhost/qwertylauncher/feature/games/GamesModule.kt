package com.newhost.qwertylauncher.feature.games

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import com.newhost.qwertylauncher.core.Cell
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.Happenings
import com.newhost.qwertylauncher.core.HomeScreen
import com.newhost.qwertylauncher.core.HomeWidget
import com.newhost.qwertylauncher.core.IconColors
import com.newhost.qwertylauncher.core.LauncherHost
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.MenuItem
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.Services
import com.newhost.qwertylauncher.core.Size
import com.newhost.qwertylauncher.core.WidgetContent
import com.newhost.qwertylauncher.core.fact
import com.newhost.qwertylauncher.core.find
import com.newhost.qwertylauncher.core.icon
import com.newhost.qwertylauncher.core.tile
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Games on the physical keyboard, on a small 2D engine of coloured
 * character cells ([Engine]): snake, tetris and 2048, each full screen.
 * `play <game>`; a tile keeps the best scores. A game waits, paused, where
 * it was when you leave it.
 */
class GamesModule : LauncherModule {
    override val id = "games"
    override val title = "games"

    override fun install(scope: ModuleScope) {
        val best = BestScores(scope.appContext, scope.services)
        val games = listOf(Snake(), Tetris(), Twenty48())
        scope.widget(GamesWidget(games, best))
        scope.command(PlayCommand(games, best))
    }
}

/** Each game's best score, kept; beating one is news (see [Happenings]). */
internal class BestScores(context: Context, private val services: Services) {
    private val prefs = context.getSharedPreferences("games", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)

    /** Bumped when a best is beaten. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    operator fun get(game: String) = prefs.getInt(game, 0)

    /** A game started: news too. */
    fun played(game: String) {
        services.find<Happenings>()?.post("games.played", game)
    }

    fun offer(game: String, score: Int) {
        if (score <= get(game)) return
        prefs.edit().putInt(game, score).apply()
        _changes.value++
        services.find<Happenings>()?.post("games.best", game)
    }
}

/**
 * A game full screen, with every key: presses and releases (so held keys
 * repeat the game's way), and the keyboard's swipes as taps of the
 * arrows. Esc leaves; the game waits, paused.
 */
internal class GameScreen(private val game: Game, private val best: BestScores) : HomeScreen {
    override fun createView(host: LauncherHost, parent: ViewGroup): View {
        best.played(game.id)
        return GameView(parent.context, game, { best[game.id] }, { best.offer(game.id, game.score) })
    }

    override fun onKey(event: KeyEvent): Boolean {
        val key = keyOf(event.keyCode) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                // Starting again keeps the score it had, if it's the best.
                if (key == Key.RESTART) best.offer(game.id, game.score)
                game.keyDown(key)
            }
            KeyEvent.ACTION_UP -> game.keyUp(key)
        }
        return true
    }

    /** The keyboard's swipes (and a wheel): a tap of an arrow a step. */
    override fun onScroll(down: Int, right: Int) {
        repeat(abs(down)) { game.tap(if (down > 0) Key.DOWN else Key.UP) }
        repeat(abs(right)) { game.tap(if (right > 0) Key.RIGHT else Key.LEFT) }
    }

    override fun onClose() {
        best.offer(game.id, game.score)
        game.held.clear()
        if (!game.over) game.paused = true
    }
}

/** A joystick, and each game's best. Tapping picks a game. */
internal class GamesWidget(private val games: List<Game>, private val best: BestScores) : HomeWidget {
    override val id = "games"
    override val order = 80
    override val minColumns = 16
    override val minLines = 3

    override fun render(): Flow<WidgetContent> = best.changes.map {
        val headline = "games"
        val facts = games.map { g -> fact("games:${g.id}", "${g.title} ${best[g.id]}", TROPHY) }
        WidgetContent(headline, layout = tile(Cell.Art("games:stick", Size(PICTURE_COLUMNS, PICTURE_LINES)) { JOYSTICK() }, headline, facts))
    }

    override fun onClick(host: LauncherHost) {
        host.showMenu("play", games.map { g -> MenuItem("${g.title}  best ${best[g.id]}") { h -> h.showScreen(GameScreen(g, best)) } })
    }

    private companion object {
        const val PICTURE_COLUMNS = 8
        const val PICTURE_LINES = 4

        val JOYSTICK = icon {
            fill(IconColors.DARK); round(10f, 62f, 90f, 92f, 12f)
            fill(IconColors.GREY); rect(46f, 30f, 54f, 66f)
            fill(IconColors.RED); circle(50f, 26f, 16f)
            fill(IconColors.YELLOW); circle(76f, 74f, 7f)
            fill(IconColors.CYAN); circle(24f, 74f, 7f)
        }
        val TROPHY = icon {
            fill(IconColors.YELLOW)
            round(26f, 10f, 74f, 52f, 14f); rect(44f, 50f, 56f, 76f); round(28f, 76f, 72f, 92f, 4f)
            stroke(IconColors.YELLOW, 7f); arc(8f, 16f, 38f, 44f, 90f, 180f); arc(62f, 16f, 92f, 44f, 270f, 180f)
        }
    }
}

/** `play <game>`: snake, tetris or 2048. */
internal class PlayCommand(private val games: List<Game>, private val best: BestScores) : Command {
    override val name = "play"
    override val aliases = listOf("games")
    override val usage = "play [snake|tetris|2048]"
    override val summary = "keyboard games: snake, tetris, 2048"

    override suspend fun suggest(args: String): List<SearchResult> {
        val typed = args.trim().lowercase()
        return games.filter { typed.isEmpty() || it.id.startsWith(typed) || it.title.startsWith(typed) }.mapIndexed { i, g ->
            val waiting = g.paused && !g.over && g.score > 0
            SearchResult(
                key = "play:${g.id}",
                tag = "game",
                title = "play ${g.title}",
                detail = "best ${best[g.id]}" + if (waiting) "  (a game waiting: ${g.score})" else "",
                score = Score.EXACT - i,
                completion = "play ${g.id}",
                action = { host ->
                    host.clearQuery()
                    host.showScreen(GameScreen(g, best))
                },
            )
        }
    }
}
