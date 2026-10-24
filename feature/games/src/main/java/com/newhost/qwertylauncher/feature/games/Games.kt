package com.newhost.qwertylauncher.feature.games

import com.newhost.qwertylauncher.core.IconColors
import kotlin.random.Random

private val DIM = 0xFF3A434C.toInt()
private val FRAME = IconColors.GREY

/**
 * Snake: eat the stars, don't bite yourself or the walls; faster as it
 * grows. Turns are queued, so quick presses aren't lost between steps.
 */
internal class Snake : Game() {
    override val id = "snake"
    override val title = "snake"
    override val help = "←↑↓→/wasd/swipe steer  p pause  r again  esc quit"

    private val w = 16
    private val h = 12
    override val cols = w * 2 + 2
    override val rows = h + 3

    private val body = ArrayDeque<Pair<Int, Int>>()
    private var heading = 1 to 0
    private val turns = ArrayDeque<Pair<Int, Int>>()
    private var food = 0 to 0
    private var clock = 0L

    override var over = false
        private set

    override val score get() = (body.size - START).coerceAtLeast(0)

    private val stepMs get() = (START_MS - score * SPEEDUP_MS).coerceAtLeast(FASTEST_MS)

    init {
        reset()
    }

    override fun reset() {
        body.clear()
        repeat(START) { body.addLast(w / 2 - it to h / 2) }
        heading = 1 to 0
        turns.clear()
        clock = 0
        over = false
        placeFood()
    }

    private fun placeFood() {
        val free = (0 until w).flatMap { x -> (0 until h).map { y -> x to y } }.filter { it !in body }
        if (free.isNotEmpty()) food = free.random()
    }

    override fun press(key: Key) {
        val turn = when (key) {
            Key.UP -> 0 to -1
            Key.DOWN -> 0 to 1
            Key.LEFT -> -1 to 0
            Key.RIGHT -> 1 to 0
            else -> return
        }
        // Against the last turn queued (or the way it's going): not straight back, not the same.
        val last = turns.lastOrNull() ?: heading
        if (turn == last || (turn.first == -last.first && turn.second == -last.second)) return
        if (turns.size < MAX_TURNS) turns.addLast(turn)
    }

    override fun update(ms: Long) {
        clock += ms
        while (clock >= stepMs && !over) {
            clock -= stepMs
            step()
        }
    }

    private fun step() {
        turns.removeFirstOrNull()?.let { heading = it }
        val head = body.first()
        val next = head.first + heading.first to head.second + heading.second
        val tail = body.last()
        val eating = next == food
        if (next.first !in 0 until w || next.second !in 0 until h || (next in body && (eating || next != tail))) {
            over = true
            return
        }
        body.addFirst(next)
        if (eating) placeFood() else body.removeLast()
    }

    override fun draw(cells: Cells, best: Int) {
        cells.text(0, 0, "snake", IconColors.GREEN)
        cells.text(8, 0, "score $score", IconColors.WHITE)
        cells.text(cols - "best $best".length, 0, "best $best", IconColors.YELLOW)
        cells.box(0, 1, cols, h + 2, FRAME)
        for (x in 0 until w) for (y in 0 until h) cells.text(1 + x * 2, 2 + y, ". ", DIM)
        cells.text(1 + food.first * 2, 2 + food.second, "* ", IconColors.RED)
        body.forEachIndexed { i, (x, y) ->
            val (c, color) = if (i == 0) (if (over) "X " else "@ ") to (if (over) IconColors.RED else IconColors.YELLOW) else (if (i % 2 == 0) "o " else "O ") to IconColors.GREEN
            cells.text(1 + x * 2, 2 + y, c, color)
        }
        overlays(cells, best)
    }

    private companion object {
        const val START = 3
        const val START_MS = 260L
        const val SPEEDUP_MS = 2L
        const val FASTEST_MS = 110L
        const val MAX_TURNS = 3
    }
}

/**
 * Tetris, played properly: held left and right repeat after a moment, held
 * down drops faster, a piece rests a moment before it locks (moving it
 * gives it longer), turns nudge off the walls, a fair bag of the seven
 * pieces, a shadow where it lands, cleared lines flash.
 */
internal class Tetris : Game() {
    override val id = "tetris"
    override val title = "tetris"
    override val help = "←→ move  ↑/tap turn  ↓ down  space/flick drop  p pause"

    private val w = 10
    private val h = 20
    override val cols = w * 2 + 2 + SIDE
    override val rows = h + 2

    override val continuous = true

    /** A tap turns it. */
    override fun touched() = tap(Key.UP)

    private val cells = Array(h) { IntArray(w) }

    private class Kind(val shape: List<Pair<Int, Int>>, val color: Int)

    private val kinds = listOf(
        Kind(listOf(0 to 1, 1 to 1, 2 to 1, 3 to 1), IconColors.CYAN),
        Kind(listOf(1 to 0, 2 to 0, 1 to 1, 2 to 1), IconColors.YELLOW),
        Kind(listOf(1 to 0, 0 to 1, 1 to 1, 2 to 1), IconColors.VIOLET),
        Kind(listOf(1 to 0, 2 to 0, 0 to 1, 1 to 1), IconColors.GREEN),
        Kind(listOf(0 to 0, 1 to 0, 1 to 1, 2 to 1), IconColors.RED),
        Kind(listOf(0 to 0, 0 to 1, 1 to 1, 2 to 1), IconColors.BLUE),
        Kind(listOf(2 to 0, 0 to 1, 1 to 1, 2 to 1), IconColors.ORANGE),
    )
    private val bag = ArrayDeque<Kind>()

    private var kind = kinds[0]
    private var shape = kind.shape
    private var next = kinds[0]
    private var px = 3
    private var py = 0

    private var fall = 0L
    private var resting = 0L
    private var nudges = 0
    private var repeat = 0L
    private var clearing = emptyList<Int>()
    private var flash = 0L
    private var lines = 0

    override var score = 0
        private set

    override var over = false
        private set

    private val level get() = lines / LINES_A_LEVEL + 1

    private val gravityMs get() = (START_MS - (level - 1) * SPEEDUP_MS).coerceAtLeast(FASTEST_MS)

    init {
        reset()
    }

    override fun reset() {
        cells.forEach { it.fill(0) }
        bag.clear()
        score = 0
        lines = 0
        over = false
        clearing = emptyList()
        next = draw()
        spawn()
    }

    /** From a shuffled bag of all seven, so none is starved. */
    private fun draw(): Kind {
        if (bag.isEmpty()) bag.addAll(kinds.shuffled())
        return bag.removeFirst()
    }

    private fun spawn() {
        kind = next
        next = draw()
        shape = kind.shape
        px = 3
        py = 0
        fall = 0
        resting = 0
        nudges = 0
        if (!fits(shape, px, py)) over = true
    }

    private fun fits(s: List<Pair<Int, Int>>, x: Int, y: Int) = s.all { (dx, dy) ->
        val cx = x + dx
        val cy = y + dy
        cx in 0 until w && cy < h && (cy < 0 || cells[cy][cx] == 0)
    }

    private fun grounded() = !fits(shape, px, py + 1)

    /** Moving or turning while resting gives it a little longer, a few times. */
    private fun moved() {
        if (grounded() && nudges < MAX_NUDGES) {
            resting = 0
            nudges++
        }
    }

    private fun shift(dx: Int) {
        if (fits(shape, px + dx, py)) {
            px += dx
            moved()
        }
    }

    private fun turn() {
        val size = if (kind.color == IconColors.CYAN) 4 else 3
        if (kind.color == IconColors.YELLOW) return
        val turned = shape.map { (x, y) -> size - 1 - y to x }
        for ((kx, ky) in listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, -2 to 0, 2 to 0)) {
            if (fits(turned, px + kx, py + ky)) {
                shape = turned
                px += kx
                py += ky
                moved()
                return
            }
        }
    }

    override fun press(key: Key) {
        if (clearing.isNotEmpty()) return
        when (key) {
            Key.LEFT -> {
                shift(-1)
                repeat = REPEAT_DELAY_MS
            }
            Key.RIGHT -> {
                shift(1)
                repeat = REPEAT_DELAY_MS
            }
            Key.UP -> turn()
            Key.ACTION -> {
                var fell = 0
                while (fits(shape, px, py + 1)) {
                    py++
                    fell++
                }
                score += fell * 2
                lock()
            }
            else -> Unit
        }
    }

    override fun update(ms: Long) {
        if (clearing.isNotEmpty()) {
            flash -= ms
            if (flash <= 0) collapse()
            return
        }
        // Held left or right: again after a moment, then quickly.
        val side = if (Key.LEFT in held) -1 else if (Key.RIGHT in held) 1 else 0
        if (side != 0) {
            repeat -= ms
            while (repeat <= 0) {
                shift(side)
                repeat += REPEAT_MS
            }
        }
        val soft = Key.DOWN in held
        val interval = if (soft) SOFT_MS else gravityMs
        if (grounded()) {
            resting += ms
            if (resting >= REST_MS) lock()
            return
        }
        fall += ms
        while (fall >= interval && !grounded()) {
            fall -= interval
            py++
            if (soft) score++
        }
    }

    private fun lock() {
        for ((dx, dy) in shape) {
            val y = py + dy
            if (y < 0) {
                over = true
                return
            }
            cells[y][px + dx] = kind.color
        }
        clearing = cells.indices.filter { y -> cells[y].all { it != 0 } }
        if (clearing.isEmpty()) spawn() else flash = FLASH_MS
    }

    private fun collapse() {
        val kept = cells.filterIndexed { y, _ -> y !in clearing }.map { it.copyOf() }
        val rowsNew = List(clearing.size) { IntArray(w) } + kept
        rowsNew.forEachIndexed { y, row -> cells[y] = row }
        lines += clearing.size
        score += CLEARS[clearing.size.coerceAtMost(CLEARS.lastIndex)] * level
        clearing = emptyList()
        spawn()
    }

    override fun draw(cells: Cells, best: Int) {
        cells.box(0, 0, w * 2 + 2, h + 2, FRAME)
        val falling = if (clearing.isEmpty() && !over) shape.map { (dx, dy) -> px + dx to py + dy }.toSet() else emptySet()
        var ghost = py
        while (clearing.isEmpty() && fits(shape, px, ghost + 1)) ghost++
        val shadow = if (falling.isEmpty()) emptySet() else shape.map { (dx, dy) -> px + dx to ghost + dy }.toSet()
        for (y in 0 until h) for (x in 0 until w) {
            val sx = 1 + x * 2
            val sy = 1 + y
            when {
                y in clearing -> cells.text(sx, sy, "==", IconColors.WHITE, if ((flash / FLASH_STEP_MS) % 2 == 0L) IconColors.WHITE else 0)
                (x to y) in falling -> cells.text(sx, sy, "[]", kind.color)
                this.cells[y][x] != 0 -> cells.text(sx, sy, "[]", this.cells[y][x])
                (x to y) in shadow -> cells.text(sx, sy, "::", DIM)
                else -> cells.text(sx, sy, " .", DIM)
            }
        }
        // The side: next, and how it's going.
        val side = w * 2 + 3
        cells.text(side, 1, "next", IconColors.GREY)
        for ((dx, dy) in next.shape) cells.text(side + dx * 2, 3 + dy, "[]", next.color)
        cells.text(side, 7, "score", IconColors.GREY)
        cells.text(side, 8, "$score", IconColors.WHITE)
        cells.text(side, 10, "lines", IconColors.GREY)
        cells.text(side, 11, "$lines", IconColors.WHITE)
        cells.text(side, 13, "level", IconColors.GREY)
        cells.text(side, 14, "$level", IconColors.WHITE)
        cells.text(side, 16, "best", IconColors.GREY)
        cells.text(side, 17, "$best", IconColors.YELLOW)
        overlays(cells, best)
    }

    private companion object {
        const val SIDE = 11
        const val LINES_A_LEVEL = 10
        const val START_MS = 800L
        const val SPEEDUP_MS = 70L
        const val FASTEST_MS = 70L
        const val SOFT_MS = 35L
        const val REST_MS = 450L
        const val MAX_NUDGES = 12
        const val REPEAT_DELAY_MS = 170L
        const val REPEAT_MS = 45L
        const val FLASH_MS = 240L
        const val FLASH_STEP_MS = 60L
        val CLEARS = listOf(0, 100, 300, 500, 800)
    }
}

/** 2048: slide, and the same numbers merge into their sum; merged and new tiles flash. */
internal class Twenty48 : Game() {
    override val id = "2048"
    override val title = "2048"
    override val help = "←↑↓→/wasd/swipe slide  r again  esc quit"

    private val n = 4
    private val tileW = 7
    private val tileH = 3
    override val cols = n * (tileW + 1) + 1
    override val rows = n * (tileH + 1) + 1 + 2

    private var tiles = Array(n) { IntArray(n) }
    private val glow = mutableMapOf<Pair<Int, Int>, Long>()
    private var won = false
    private var cheered = false

    override var score = 0
        private set

    override var over = false
        private set

    init {
        reset()
    }

    override fun reset() {
        tiles = Array(n) { IntArray(n) }
        glow.clear()
        score = 0
        over = false
        won = false
        cheered = false
        add()
        add()
    }

    private fun add() {
        val free = (0 until n).flatMap { y -> (0 until n).map { x -> x to y } }.filter { (x, y) -> tiles[y][x] == 0 }
        val (x, y) = free.randomOrNull() ?: return
        tiles[y][x] = if (Random.nextInt(10) == 0) 4 else 2
        glow[x to y] = GLOW_MS
    }

    override fun press(key: Key) {
        if (won && !cheered) {
            cheered = true
            return
        }
        val before = tiles.map { it.copyOf() }
        when (key) {
            Key.LEFT -> for (y in 0 until n) line(List(n) { it to y })
            Key.RIGHT -> for (y in 0 until n) line(List(n) { n - 1 - it to y })
            Key.UP -> for (x in 0 until n) line(List(n) { x to it })
            Key.DOWN -> for (x in 0 until n) line(List(n) { x to n - 1 - it })
            else -> return
        }
        if (tiles.indices.any { !tiles[it].contentEquals(before[it]) }) add()
        if (tiles.any { row -> row.any { it >= GOAL } }) won = true
        over = !canMove()
    }

    /** Slides the tiles at [spots] (from the side they slide to), merging pairs. */
    private fun line(spots: List<Pair<Int, Int>>) {
        val values = spots.map { (x, y) -> tiles[y][x] }.filter { it != 0 }
        val out = mutableListOf<Int>()
        val merged = mutableListOf<Int>()
        var i = 0
        while (i < values.size) {
            if (i + 1 < values.size && values[i] == values[i + 1]) {
                merged += out.size
                out += values[i] * 2
                score += values[i] * 2
                i += 2
            } else {
                out += values[i]
                i++
            }
        }
        spots.forEachIndexed { k, (x, y) -> tiles[y][x] = out.getOrElse(k) { 0 } }
        merged.forEach { glow[spots[it]] = GLOW_MS }
    }

    private fun canMove(): Boolean {
        for (y in 0 until n) for (x in 0 until n) {
            val v = tiles[y][x]
            if (v == 0) return true
            if (x + 1 < n && tiles[y][x + 1] == v) return true
            if (y + 1 < n && tiles[y + 1][x] == v) return true
        }
        return false
    }

    override fun update(ms: Long) {
        glow.entries.removeAll { (_, left) -> left - ms <= 0 }
        glow.replaceAll { _, left -> left - ms }
    }

    override fun draw(cells: Cells, best: Int) {
        cells.text(0, 0, "2048", IconColors.ORANGE)
        cells.text(7, 0, "score $score", IconColors.WHITE)
        cells.text(cols - "best $best".length, 0, "best $best", IconColors.YELLOW)
        val top = 2
        for (y in 0 until n) for (x in 0 until n) {
            val left = x * (tileW + 1)
            val up = top + y * (tileH + 1)
            cells.box(left, up, tileW + 2, tileH + 2, FRAME)
            val v = tiles[y][x]
            if (v == 0) continue
            val lit = (x to y) in glow
            val back = if (lit) IconColors.WHITE else backOf(v)
            for (i in 1..tileW) for (j in 1..tileH) cells.put(left + i, up + j, ' ', 0, back)
            val label = v.toString()
            cells.text(left + 1 + (tileW - label.length) / 2, up + 2, label, if (lit || v <= 4) 0xFF1B1F24.toInt() else IconColors.WHITE, back)
        }
        if (won && !cheered) cells.banner(listOf("2048!", "any key: keep going"), IconColors.GREEN)
        overlays(cells, best)
    }

    private fun backOf(v: Int): Int = when (v) {
        2 -> 0xFFD8D2C4.toInt()
        4 -> 0xFFD6C99E.toInt()
        8 -> 0xFFE0934A.toInt()
        16 -> 0xFFE27A3C.toInt()
        32 -> 0xFFE05A3A.toInt()
        64 -> 0xFFD83A2A.toInt()
        128 -> 0xFFD8B84A.toInt()
        256 -> 0xFFD2AE32.toInt()
        512 -> 0xFFC89F1E.toInt()
        1024 -> 0xFF3AA86A.toInt()
        else -> 0xFF2A8FBF.toInt()
    }

    private companion object {
        const val GOAL = 2048
        const val GLOW_MS = 160L
    }
}
